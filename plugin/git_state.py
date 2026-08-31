"""Read-only Git state surface for the Hermes-Relay plugin.

Discovers repositories under a configurable base path (default ``~/projects``)
and exposes working-tree status, branches, per-file diffs, and tracked-file
reads. All git execution uses argument lists with ``git -C <repo>`` and never
shell interpolation. Every operation is bounded (timeouts + size caps) and every
``repo`` reference is validated against the scanned allowlist.

Security contract
-----------------
- ``repo`` params are opaque ids resolved against the scanned repo set; an
  unknown id is rejected before any filesystem access.
- File paths are validated to reject traversal (``..``), absolute escapes, and
  null bytes. Working-tree reads additionally require canonical containment.
- Remote URLs are scrubbed of embedded userinfo before they reach any client.
"""

from __future__ import annotations

import logging
import os
import re
import stat
import subprocess
import tempfile
from functools import wraps
from pathlib import Path
from threading import Lock, RLock
from typing import Any

from .config import raw_config_value

logger = logging.getLogger(__name__)

# Bounded response caps (bytes / entries). Configurable via env for operators.
MAX_STATUS_ENTRIES = int(os.environ.get("GIT_STATE_MAX_STATUS_ENTRIES", "200"))
MAX_DIFF_BYTES = int(os.environ.get("GIT_STATE_MAX_DIFF_BYTES", "64_000"))
MAX_FILE_BYTES = int(os.environ.get("GIT_STATE_MAX_FILE_BYTES", "256_000"))
MAX_GIT_OUTPUT_BYTES = int(os.environ.get("GIT_STATE_MAX_OUTPUT_BYTES", "1_000_000"))
MAX_GIT_ERROR_BYTES = int(os.environ.get("GIT_STATE_MAX_ERROR_BYTES", "16_000"))
MAX_GIT_SCALAR_LENGTH = 512
GIT_TIMEOUT_SECONDS = float(os.environ.get("GIT_STATE_TIMEOUT_SECONDS", "10"))

# Default base path for repo discovery.
DEFAULT_BASE_PATH = "~/projects"
_BASE_PATH_ENV = "GIT_STATE_BASE_PATH"

# Matches a remote URL's userinfo (user[:password]@) so it can be scrubbed.
_USERINFO_RE = re.compile(r"^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@]+)@")
_SSH_USERINFO_RE = re.compile(r"^([^/@:]+)@([^:]+):")
_ERROR_USERINFO_RE = re.compile(r"([a-zA-Z][a-zA-Z0-9+.-]*://)([^\s/@]+)@")
_ERROR_SSH_USERINFO_RE = re.compile(r"(?<![\w@])([^\s/@:]+)@([^\s:]+):")

# FastAPI executes synchronous routes concurrently. These locks serialize Git
# operations per repository without creating worker threads, so one API call
# cannot replace a validated working-tree path underneath another.
_REPO_LOCKS_GUARD = Lock()
_REPO_LOCKS: dict[str, RLock] = {}


class GitStateError(ValueError):
    """A caller supplied an invalid repo id, path, or diff kind."""


class GitError(GitStateError):
    """A mutation failed in a way git or the security gate reported.

    ``code`` is a stable, machine-readable taxonomy tag the UI can map to a
    readable message without ever rendering a raw stack trace or JSON dump:
    ``non-repo``, ``dirty``, ``conflict``, ``auth``, ``network``,
    ``invalid-input``, ``missing-confirmation``, ``wrong-confirmation``.
    """

    _CODES = {
        "non-repo",
        "dirty",
        "conflict",
        "auth",
        "network",
        "invalid-input",
        "missing-confirmation",
        "wrong-confirmation",
    }

    def __init__(self, message: str, code: str = "invalid-input") -> None:
        if code not in self._CODES:
            raise ValueError(f"unknown git error code: {code}")
        super().__init__(message)
        self.code = code


def base_path() -> Path:
    """Resolve the configured discovery base path (default ``~/projects``)."""
    raw = raw_config_value(_BASE_PATH_ENV) or DEFAULT_BASE_PATH
    return Path(raw).expanduser()


def _run_git_bounded(
    repo: Path,
    args: list[str],
    *,
    mutation: bool,
    literal_pathspecs: bool = False,
) -> tuple[int, str, str]:
    """Run Git without materializing unbounded stdout or stderr in memory."""
    error_type = GitError if mutation else GitStateError
    with tempfile.TemporaryFile() as stdout_file, tempfile.TemporaryFile() as stderr_file:
        try:
            command = ["git", "-C", str(repo)]
            if literal_pathspecs:
                command.append("--literal-pathspecs")
            command.extend(args)
            result = subprocess.run(
                command,
                stdout=stdout_file,
                stderr=stderr_file,
                timeout=GIT_TIMEOUT_SECONDS,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            if mutation:
                raise GitError(f"git timed out for {repo.name}", code="network") from exc
            raise GitStateError(f"git timed out for {repo.name}") from exc
        except OSError as exc:
            if mutation:
                raise GitError(
                    f"could not run git for {repo.name}: {exc}",
                    code="non-repo",
                ) from exc
            raise GitStateError(f"could not run git for {repo.name}: {exc}") from exc

        stdout_file.seek(0)
        stderr_file.seek(0)
        stdout_bytes = stdout_file.read(MAX_GIT_OUTPUT_BYTES + 1)
        stderr_bytes = stderr_file.read(MAX_GIT_ERROR_BYTES + 1)
        if len(stdout_bytes) > MAX_GIT_OUTPUT_BYTES:
            message = f"git {args[0] if args else 'command'} output exceeded the limit"
            if mutation:
                raise GitError(message, code="invalid-input")
            raise error_type(message)

    stdout = stdout_bytes.decode("utf-8", errors="replace")
    stderr = stderr_bytes[:MAX_GIT_ERROR_BYTES].decode("utf-8", errors="replace")
    if len(stderr_bytes) > MAX_GIT_ERROR_BYTES:
        stderr += "\n[error output truncated]"
    return result.returncode, stdout, stderr


def _safe_git_error(text: str) -> str:
    """Bound and scrub URL userinfo before returning Git diagnostics."""
    scrubbed = _ERROR_USERINFO_RE.sub(r"\1", text)
    scrubbed = _ERROR_SSH_USERINFO_RE.sub(r"\2:", scrubbed)
    return scrubbed[:MAX_GIT_ERROR_BYTES].strip()


def _git(repo: Path, *args: str, literal_pathspecs: bool = False) -> str:
    """Run ``git -C <repo> <args>`` and return bounded stdout."""
    returncode, stdout, stderr = _run_git_bounded(
        repo,
        list(args),
        mutation=False,
        literal_pathspecs=literal_pathspecs,
    )
    if returncode != 0:
        raise GitStateError(
            f"git {args[0] if args else 'command'} failed for {repo.name}: "
            f"{_safe_git_error(stderr or stdout)}"
        )
    return stdout


def _is_git_repo(path: Path) -> bool:
    """True if ``path`` is a git work tree (has a .git entry)."""
    return (path / ".git").exists()


def _is_link_or_junction(path: Path) -> bool:
    is_junction = getattr(path, "is_junction", None)
    if path.is_symlink() or bool(is_junction and is_junction()):
        return True
    if os.name != "nt":
        return False
    try:
        attributes = os.lstat(path).st_file_attributes
    except (AttributeError, FileNotFoundError, OSError):
        return False
    reparse_point = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    return bool(attributes & reparse_point)


def _is_within(root: Path, candidate: Path) -> bool:
    return candidate == root or root in candidate.parents


def _canonical_repo_root(repo: Path) -> Path:
    """Return an unlinked absolute repository root or fail closed."""
    lexical = Path(os.path.abspath(repo))
    try:
        if _is_link_or_junction(lexical):
            raise GitStateError("repository root changed during operation")
        canonical = lexical.resolve(strict=True)
    except GitStateError:
        raise
    except (OSError, RuntimeError) as exc:
        raise GitStateError("repository root changed during operation") from exc
    if os.path.normcase(str(canonical)) != os.path.normcase(str(lexical)):
        raise GitStateError("repository root changed during operation")
    return canonical


def _serialized_repo_operation(function: Any) -> Any:
    """Serialize a Git operation against other operations on the same repo."""

    @wraps(function)
    def wrapped(repo: Path, *args: Any, **kwargs: Any) -> Any:
        key = os.path.normcase(os.path.abspath(repo))
        with _REPO_LOCKS_GUARD:
            lock = _REPO_LOCKS.setdefault(key, RLock())
        with lock:
            return function(repo, *args, **kwargs)

    return wrapped


def _resolve_repo_disk_path(
    repo: Path,
    path: str,
    *,
    strict: bool,
) -> Path:
    """Resolve a validated relative path and prove canonical repo containment.

    ``Path.resolve`` follows symlinks and junctions before ``commonpath``
    compares the canonical filesystem paths. Different-drive paths fail
    closed on Windows. Callers that open the result must revalidate the opened
    handle before using it as defense in depth against an unexpected path
    replacement. Concurrent external same-user filesystem mutation is outside
    the plugin trust boundary.
    """
    safe_path = resolve_repo_path(repo, path)
    root = _canonical_repo_root(repo)
    candidate = (root / safe_path).resolve(strict=strict)
    try:
        common = Path(os.path.commonpath((str(root), str(candidate))))
    except ValueError as exc:
        raise GitStateError(f"path escapes repository: {safe_path}") from exc
    if os.path.normcase(str(common)) != os.path.normcase(str(root)):
        raise GitStateError(f"path escapes repository: {safe_path}")
    return candidate


def _has_link_component(base: Path, path: Path) -> bool:
    current = base
    try:
        relative = path.relative_to(base)
    except ValueError:
        return True
    for part in relative.parts:
        current /= part
        if _is_link_or_junction(current):
            return True
    return False


def _validate_untracked_delete_path(repo: Path, path: str) -> str:
    """Validate one exact untracked file before delegating deletion to Git."""
    safe_path = resolve_repo_path(repo, path)
    root = _canonical_repo_root(repo)
    lexical = root / safe_path
    if _has_link_component(root, lexical):
        raise GitStateError(f"path contains a link or junction: {safe_path}")
    candidate = _resolve_repo_disk_path(repo, safe_path, strict=False)
    if candidate.exists() and candidate.is_dir():
        raise GitStateError(f"path is not a file: {safe_path}")
    return safe_path


def repo_id(repo: Path, base: Path | None = None) -> str:
    """Stable collision-free id relative to the configured canonical base."""
    if base is None:
        return repo.name
    return repo.relative_to(base).as_posix()


def scan_repos(base: Path) -> list[dict[str, Any]]:
    """Recursively scan ``base`` for git repositories.

    Returns a list of ``{id, name, root, current_branch, dirty}``. A missing
    base path yields an empty list (never an exception). ``.git`` internals are
    never reported as repositories.
    """
    if not base.is_dir():
        return []

    canonical_base = base.resolve()
    repos: list[dict[str, Any]] = []
    for root in sorted(base.rglob("*")):
        if not root.is_dir():
            continue
        if root.name == ".git":
            continue
        if _has_link_component(base, root):
            continue
        canonical_root = root.resolve()
        if not _is_within(canonical_base, canonical_root):
            continue
        if not _is_git_repo(canonical_root):
            continue
        repos.append(_describe_repo(canonical_root, canonical_base))
    return repos


def _describe_repo(repo: Path, base: Path) -> dict[str, Any]:
    """Build the scan entry for one repository."""
    current_branch = ""
    try:
        current_branch = _git(repo, "symbolic-ref", "--short", "-q", "HEAD").strip()
    except GitStateError:
        # Detached HEAD or unborn branch — leave empty.
        pass

    dirty = False
    try:
        dirty = bool(_git(repo, "status", "--porcelain").strip())
    except GitStateError:
        pass

    return {
        "id": repo_id(repo, base),
        "name": repo.name,
        "root": str(repo),
        "current_branch": current_branch,
        "dirty": dirty,
    }


def _scan_index(base: Path) -> dict[str, Path]:
    """Build the id → root allowlist from a scan."""
    return {entry["id"]: Path(entry["root"]) for entry in scan_repos(base)}


def resolve_repo(base: Path, repo: str) -> Path:
    """Resolve a ``repo`` id against the scanned allowlist.

    Raises [GitStateError] if the id is unknown (never accepts an arbitrary
    path).
    """
    index = _scan_index(base)
    root = index.get(repo)
    if root is None:
        raise GitStateError(f"unknown repository: {repo}")
    return root


def resolve_repo_path(repo: Path, path: str) -> str:
    """Validate a repo-relative file path, rejecting traversal and escapes.

    Returns the normalized path. Raises [GitStateError] on any unsafe input.
    """
    if not isinstance(path, str) or not path:
        raise GitStateError("path is required")
    if "\x00" in path:
        raise GitStateError("path contains a null byte")
    if "%" in path:
        # Reject URL-encoding artifacts; the framework may decode %2F to a
        # path separator, so a literal % is never safe in a repo path.
        raise GitStateError("path contains a percent-encoding artifact")
    normalized = path.replace("\\", "/")
    if normalized.startswith("/"):
        raise GitStateError("absolute paths are not allowed")
    if normalized.startswith("~"):
        raise GitStateError("home-relative paths are not allowed")
    segments = normalized.split("/")
    if any(seg in ("", ".", "..") for seg in segments):
        raise GitStateError("path traversal is not allowed")
    return normalized


def repo_status(repo: Path) -> dict[str, Any]:
    """Return grouped working-tree status with counts and truncation flag."""
    porcelain = _git(repo, "status", "--porcelain=v1", "-z")
    staged: list[dict[str, Any]] = []
    modified: list[dict[str, Any]] = []
    untracked: list[dict[str, Any]] = []
    truncated = False

    def _numstat(*args: str) -> dict[str, tuple[int, int]]:
        """Return bounded text-line deltas keyed by the destination path.

        ``--numstat -z`` keeps tabs/newlines in filenames unambiguous. Binary
        files report ``-`` for each count and intentionally remain without a
        line delta in the UI.
        """
        raw = _git(repo, "diff", "--numstat", "-z", *args)
        values = raw.split("\0")
        result: dict[str, tuple[int, int]] = {}
        index = 0
        while index < len(values):
            record = values[index]
            index += 1
            if not record:
                continue
            fields = record.split("\t", 2)
            if len(fields) != 3:
                continue
            added, deleted, path = fields
            if not path:
                # Rename/copy records place old and new paths in the next two
                # NUL-delimited fields. Status exposes the destination path.
                index += 1
                if index >= len(values):
                    break
                path = values[index]
                index += 1
            if added.isdigit() and deleted.isdigit():
                result[path] = (int(added), int(deleted))
        return result

    staged_numstat = _numstat("--cached", "--")
    modified_numstat = _numstat("--")

    # -z separates records with NUL; each record is "<XY> <path>\0". A rename
    # or copy emits TWO records ("R  new\0old\0"); the bare source-path record
    # must be skipped, not misparsed as an XY record.
    records = porcelain.split("\0")
    i = 0
    while i < len(records):
        record = records[i]
        i += 1
        if not record:
            continue
        xy = record[:2]
        path = record[3:]
        if not path:
            continue
        if xy == "??":
            untracked.append({"path": path, "additions": None, "deletions": None})
        # Independent checks: a file staged AND modified lands in both groups.
        if xy[0] in ("M", "A", "D", "R", "C"):
            additions, deletions = staged_numstat.get(path, (None, None))
            staged.append({"path": path, "additions": additions, "deletions": deletions})
        if xy[1] in ("M", "D"):
            additions, deletions = modified_numstat.get(path, (None, None))
            modified.append({"path": path, "additions": additions, "deletions": deletions})
        if xy[0] in ("R", "C"):
            # The immediately following record is the rename/copy source path;
            # skip it so it is not misparsed as an XY record.
            i += 1

    def _bounded(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
        nonlocal truncated
        if len(items) > MAX_STATUS_ENTRIES:
            truncated = True
            return items[:MAX_STATUS_ENTRIES]
        return items

    staged_count = len(staged)
    modified_count = len(modified)
    untracked_count = len(untracked)
    changed_count = len({entry["path"] for entry in (*staged, *modified, *untracked)})
    additions = sum(added for added, _ in (*staged_numstat.values(), *modified_numstat.values()))
    deletions = sum(deleted for _, deleted in (*staged_numstat.values(), *modified_numstat.values()))

    staged = _bounded(staged)
    modified = _bounded(modified)
    untracked = _bounded(untracked)

    return {
        "counts": {
            "staged": staged_count,
            "modified": modified_count,
            "untracked": untracked_count,
            "changes": changed_count,
            "additions": additions,
            "deletions": deletions,
        },
        "staged": staged,
        "modified": modified,
        "untracked": untracked,
        "truncated": truncated,
    }


def repo_branches(repo: Path) -> list[dict[str, Any]]:
    """Return branch list with name, upstream, ahead/behind, is_current."""
    current = ""
    try:
        current = _git(repo, "symbolic-ref", "--short", "-q", "HEAD").strip()
    except GitStateError:
        pass

    # %(upstream:track) yields "[ahead 1, behind 2]", "[gone]", or "".
    fmt = "%(refname:short)%00%(upstream:short)%00%(upstream:track)"
    raw = _git(repo, "for-each-ref", "refs/heads", f"--format={fmt}")
    branches: list[dict[str, Any]] = []
    for line in raw.splitlines():
        if not line:
            continue
        name, upstream, track = line.split("\x00", 2)
        ahead, behind = _parse_track(track)
        branches.append(
            {
                "name": name,
                "upstream": upstream or None,
                "ahead": ahead,
                "behind": behind,
                "is_current": name == current,
            }
        )
    return branches


def _parse_track(track: str) -> tuple[int, int]:
    """Parse ``[ahead 1, behind 2]`` / ``[gone]`` / ``""`` into (ahead, behind)."""
    if not track or track == "[gone]":
        return 0, 0
    ahead = behind = 0
    for part in re.findall(r"(ahead|behind)\s+(\d+)", track):
        if part[0] == "ahead":
            ahead = int(part[1])
        else:
            behind = int(part[1])
    return ahead, behind


def repo_diff(repo: Path, path: str, kind: str) -> dict[str, Any]:
    """Return a per-file diff for ``kind`` in (``staged``, ``unstaged``)."""
    if kind not in ("staged", "unstaged"):
        raise GitStateError(f"invalid diff kind: {kind}")
    safe_path = resolve_repo_path(repo, path)
    args = ["diff", "--no-color", "--"]
    if kind == "staged":
        args = ["diff", "--cached", "--no-color", "--"]
    output = _git(repo, *args, safe_path, literal_pathspecs=True)
    truncated = len(output) > MAX_DIFF_BYTES
    if truncated:
        output = output[:MAX_DIFF_BYTES]
    return {"path": safe_path, "kind": kind, "diff": output, "truncated": truncated}


@_serialized_repo_operation
def read_file(repo: Path, path: str) -> dict[str, Any]:
    """Read a tracked file's working-tree content. Untracked/binary/missing → error."""
    safe_path = resolve_repo_path(repo, path)
    # Confirm the file is tracked before reading.
    try:
        _git(
            repo,
            "ls-files",
            "--error-unmatch",
            "--",
            safe_path,
            literal_pathspecs=True,
        )
    except GitStateError as exc:
        raise GitStateError(f"file is not tracked: {safe_path}") from exc

    # Read the working-tree file from disk (not `git show HEAD:`), so a
    # modified-but-uncommitted file returns what is on disk. Read bytes first:
    # binary content dies on the NUL check (before any decode), and non-UTF-8
    # text raises a clear GitStateError instead of an unhandled 500.
    try:
        disk_path = _resolve_repo_disk_path(repo, safe_path, strict=True)
        with disk_path.open("rb") as handle:
            # Re-resolve after opening, then prove the open handle still names
            # that in-repo file. This fails closed if a parent, junction, repo
            # root, or leaf is replaced between validation and open; reads use
            # the already-verified handle after this point.
            revalidated = _resolve_repo_disk_path(repo, safe_path, strict=True)
            if not os.path.samestat(os.fstat(handle.fileno()), revalidated.stat()):
                raise GitStateError(f"path changed during read: {safe_path}")
            raw = handle.read(MAX_FILE_BYTES + 1)
    except OSError as exc:
        raise GitStateError(f"could not read file: {safe_path}") from exc

    if b"\x00" in raw:
        raise GitStateError(f"binary file is not supported: {safe_path}")

    truncated = len(raw) > MAX_FILE_BYTES
    if truncated:
        raw = raw[:MAX_FILE_BYTES]

    try:
        content = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise GitStateError(f"file is not valid UTF-8 text: {safe_path}") from exc

    return {"path": safe_path, "content": content, "truncated": truncated}


# ── Write (mutation) surface ──────────────────────────────────────────────
# Every mutation requires the plugin's ``plugin.api.write`` grant (enforced at
# the router boundary, matching the app's existing grant gating). Destructive
# operations additionally require a per-use confirmation string echoed by the
# app/tab. The confirmation values are fixed opaque tokens chosen here; the
# client shows the human-readable description and sends the token back.
CONFIRM_DISCARD = "discard"
CONFIRM_PUSH = "push"
CONFIRM_DIRTY_CHECKOUT = "checkout-dirty"

# Bounds keeping payloads and subprocess argv lists bounded.
MAX_MUTATION_PATHS = 200
MAX_COMMIT_MESSAGE = 500

# Return-code markers used to classify git failures into the structured error
# taxonomy the UI renders (non-repo, dirty, conflict, auth, network).
_DIRTY_MARKERS = (
    "local changes",
    "would be overwritten",
    "cannot pull with rebase",
    "Your local changes to the following files would be overwritten",
    "You have unstaged changes",
    "contains uncommitted changes",
    "working tree contains modifications",
)
_CONFLICT_MARKERS = (
    "merge conflict",
    "CONFLICT",
    "Automatic merge failed",
    "fix conflicts",
    "Merge conflict",
)
_AUTH_MARKERS = (
    "Authentication failed",
    "could not read Username",
    "Permission denied (publickey)",
    "does not appear to be a git repository",
    "Repository not found",
    "Invalid username or password",
    "authentication failed",
    "could not read Password",
)
_NETWORK_MARKERS = (
    "Could not resolve host",
    "Connection timed out",
    "Network is unreachable",
    "Operation timed out",
    "Could not read from remote repository",
    "unable to access",
    "Failed to connect",
    "Name or service not known",
    "getaddrinfo",
    "Temporary failure in name resolution",
)


def _classify_git_failure(stderr: str) -> str:
    """Map a git stderr to a stable taxonomy code."""
    for marker in _DIRTY_MARKERS:
        if marker in stderr:
            return "dirty"
    for marker in _CONFLICT_MARKERS:
        if marker in stderr:
            return "conflict"
    for marker in _AUTH_MARKERS:
        if marker in stderr:
            return "auth"
    for marker in _NETWORK_MARKERS:
        if marker in stderr:
            return "network"
    return "invalid-input"


def _require_confirmation(confirmation: str | None, expected: str) -> None:
    """Enforce the per-use confirmation string for a destructive mutation."""
    if not confirmation:
        raise GitError("this action requires confirmation", code="missing-confirmation")
    if confirmation != expected:
        raise GitError("confirmation did not match", code="wrong-confirmation")


def _validate_paths(paths: list[str] | None) -> list[str]:
    """Normalize a bounded list of repo-relative paths with traversal checks."""
    if not paths:
        raise GitStateError("paths are required")
    if len(paths) > MAX_MUTATION_PATHS:
        raise GitStateError(f"too many paths (max {MAX_MUTATION_PATHS})")
    return [resolve_repo_path(Path("."), p) for p in paths]


def _is_git_repo(path: Path) -> bool:
    """True if ``path`` is a git work tree (has a .git entry)."""
    return (path / ".git").exists()


def _run_mutation(
    repo: Path,
    args: list[str],
    *,
    literal_pathspecs: bool = False,
) -> str:
    """Run a mutating ``git -C <repo> <args>``; raise a classified GitError.

    Arg lists only (never shell interpolation); bounded by a timeout.
    """
    returncode, stdout, stderr_output = _run_git_bounded(
        repo,
        args,
        mutation=True,
        literal_pathspecs=literal_pathspecs,
    )
    if returncode != 0:
        stderr = _safe_git_error(stderr_output or stdout)
        raise GitError(
            f"git {args[0] if args else 'command'} failed for {repo.name}: {stderr}",
            code=_classify_git_failure(stderr),
        )
    return stdout


def _mutate(
    repo: Path,
    args: list[str],
    *,
    literal_pathspecs: bool = False,
) -> str:
    """Validate ``repo`` is a real git work tree, then run the mutation."""
    if not _is_git_repo(repo):
        raise GitError(f"not a git repository: {repo.name}", code="non-repo")
    return _run_mutation(repo, args, literal_pathspecs=literal_pathspecs)


def _is_tracked_path(repo: Path, path: str) -> bool:
    """Classify one literal path without treating Git failures as untracked."""
    returncode, stdout, stderr = _run_git_bounded(
        repo,
        ["ls-files", "--error-unmatch", "--", path],
        mutation=False,
        literal_pathspecs=True,
    )
    if returncode == 0:
        return True
    if returncode == 1:
        return False
    raise GitStateError(
        f"git ls-files failed for {repo.name}: {_safe_git_error(stderr or stdout)}"
    )


def _is_dirty(repo: Path) -> bool:
    try:
        return bool(_git(repo, "status", "--porcelain").strip())
    except GitError:
        return False


def _validate_commit_message(message: str) -> str:
    if not isinstance(message, str) or not message.strip():
        raise GitError("commit message must not be empty", code="invalid-input")
    return message.strip()[:MAX_COMMIT_MESSAGE]


def _validate_git_scalar(value: str, label: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise GitError(f"{label} must be a string", code="invalid-input")
    value = value.strip()
    if not value:
        if allow_empty:
            return ""
        raise GitError(f"{label} is required", code="invalid-input")
    if len(value) > MAX_GIT_SCALAR_LENGTH:
        raise GitError(f"{label} is too long", code="invalid-input")
    if value.startswith("-"):
        raise GitError(f"{label} must not be a git option", code="invalid-input")
    if "\x00" in value or any(ord(char) < 32 for char in value):
        raise GitError(f"{label} contains invalid characters", code="invalid-input")
    return value


def _validate_remote(repo: Path, remote: str) -> str:
    remote = _validate_git_scalar(remote, "remote")
    configured = {line.strip() for line in _git(repo, "remote").splitlines() if line.strip()}
    if remote not in configured:
        raise GitError(f"unknown remote: {remote}", code="invalid-input")
    return remote


def _validate_branch(repo: Path, branch: str, *, allow_empty: bool = False) -> str:
    branch = _validate_git_scalar(branch, "branch", allow_empty=allow_empty)
    if not branch:
        return ""
    try:
        _git(repo, "check-ref-format", "--branch", branch)
    except GitStateError as exc:
        raise GitError(f"invalid branch: {branch}", code="invalid-input") from exc
    return branch


def _validate_revision(repo: Path, ref: str, *, allow_empty: bool = False) -> str:
    ref = _validate_git_scalar(ref, "ref", allow_empty=allow_empty)
    if not ref:
        return ""
    try:
        _git(repo, "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}")
    except GitStateError as exc:
        raise GitError(f"unknown ref: {ref}", code="invalid-input") from exc
    return ref


def _checkout_args(
    repo: Path,
    ref: str,
    *,
    new_branch: str,
    track: bool,
) -> list[str]:
    new_branch = _validate_branch(repo, new_branch, allow_empty=True)
    ref = _validate_revision(repo, ref, allow_empty=bool(new_branch))
    if track and not ref:
        raise GitError("track requires a source ref", code="invalid-input")
    if new_branch:
        if not ref:
            _validate_revision(repo, "HEAD")
        returncode, _, error = _run_git_bounded(
            repo,
            ["show-ref", "--verify", "--quiet", f"refs/heads/{new_branch}"],
            mutation=False,
        )
        if returncode == 0:
            raise GitError(f"branch already exists: {new_branch}", code="invalid-input")
        if returncode != 1:
            raise GitError(
                f"could not validate branch {new_branch}: {_safe_git_error(error)}",
                code="invalid-input",
            )
        args = ["checkout"]
        if track:
            args.append("--track")
        args.extend(["-b", new_branch])
        if ref:
            args.append(ref)
        return args
    args = ["checkout"]
    if track:
        args.append("--track")
    args.append(ref)
    return args


def _fresh_mutation_result(
    repo: Path,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Post-mutation snapshot: new HEAD oid + fresh working-tree status."""
    head = ""
    try:
        head = _git(repo, "rev-parse", "HEAD").strip()
    except GitError:
        # Unborn branch — no HEAD yet.
        pass
    result: dict[str, Any] = {"head": head, "status": repo_status(repo)}
    if extra:
        result.update(extra)
    return result


@_serialized_repo_operation
def stage(repo: Path, paths: list[str]) -> dict[str, Any]:
    """Stage ``paths`` (repo-relative) and return fresh status."""
    safe = _validate_paths(paths)
    _mutate(repo, ["add", "--"] + safe, literal_pathspecs=True)
    return _fresh_mutation_result(repo)


@_serialized_repo_operation
def unstage(repo: Path, paths: list[str]) -> dict[str, Any]:
    """Unstage ``paths`` and return fresh status."""
    safe = _validate_paths(paths)
    _mutate(repo, ["restore", "--staged", "--"] + safe, literal_pathspecs=True)
    return _fresh_mutation_result(repo)


@_serialized_repo_operation
def discard(
    repo: Path,
    paths: list[str],
    confirmation: str | None,
    delete_untracked: bool = False,
) -> dict[str, Any]:
    """Discard local changes to ``paths`` (confirmation required).

    With ``delete_untracked`` the named untracked files are removed from disk.
    Returns fresh status.
    """
    _require_confirmation(confirmation, CONFIRM_DISCARD)
    safe = _validate_paths(paths)
    tracked: list[str] = []
    untracked: list[str] = []
    for path in safe:
        if _is_tracked_path(repo, path):
            tracked.append(path)
        elif delete_untracked:
            untracked.append(path)
    # Reject links and directories before asking Git to remove only the exact
    # literal file names. Concurrent same-user filesystem mutation is outside
    # the plugin trust boundary; stationary redirections fail closed here.
    deletable = [_validate_untracked_delete_path(repo, path) for path in untracked]
    if deletable:
        _mutate(repo, ["clean", "-f", "--"] + deletable, literal_pathspecs=True)
    # Revert tracked modifications for the given paths.
    if tracked:
        _mutate(repo, ["checkout", "--"] + tracked, literal_pathspecs=True)
    return _fresh_mutation_result(repo)


@_serialized_repo_operation
def commit(repo: Path, message: str) -> dict[str, Any]:
    """Create a commit from the staged index. Empty message is rejected."""
    message = _validate_commit_message(message)
    _mutate(repo, ["commit", "-m", message])
    return _fresh_mutation_result(repo)


@_serialized_repo_operation
def commit_selected(repo: Path, message: str, paths: list[str]) -> dict[str, Any]:
    """Commit only the given ``paths`` (staged + modified) under ``message``."""
    message = _validate_commit_message(message)
    safe = _validate_paths(paths)
    _mutate(repo, ["commit", "-m", message, "--"] + safe, literal_pathspecs=True)
    return _fresh_mutation_result(repo)


@_serialized_repo_operation
def fetch(repo: Path, remote: str = "origin") -> dict[str, Any]:
    """Fetch from ``remote`` (default origin) and return fresh status/branches."""
    remote = _validate_remote(repo, remote)
    _mutate(repo, ["fetch", "--prune", remote])
    return _fresh_mutation_result(repo, {"branches": repo_branches(repo)})


@_serialized_repo_operation
def pull(repo: Path, remote: str = "origin", branch: str = "") -> dict[str, Any]:
    """Pull from ``remote``/``branch`` (defaults: origin + current branch).

    Pull never clobbers local work: a tree git refuses to fast-forward without
    discarding local changes surfaces as a structured ``dirty`` GitError.
    """
    remote = _validate_remote(repo, remote)
    branch = _validate_branch(repo, branch, allow_empty=True)
    args = ["pull", "--ff-only", remote]
    if branch:
        args.append(branch)
    try:
        _mutate(repo, args)
    except GitError as exc:
        if exc.code == "dirty":
            raise GitError(
                "Pull would overwrite local changes — commit or discard them first.",
                code="dirty",
            ) from exc
        raise
    return _fresh_mutation_result(repo)


@_serialized_repo_operation
def push(
    repo: Path,
    remote: str = "origin",
    branch: str = "",
    confirmation: str | None = None,
) -> dict[str, Any]:
    """Push to ``remote``/``branch``. Confirmation is required.

    Auth/network failures surface as classified GitErrors; fresh status/branches
    are returned so the UI can reflect ahead/behind after a successful push.
    """
    _require_confirmation(confirmation, CONFIRM_PUSH)
    remote = _validate_remote(repo, remote)
    branch = _validate_branch(repo, branch, allow_empty=True)
    args = ["push", remote]
    if branch:
        args.append(branch)
    _mutate(repo, args)
    return _fresh_mutation_result(repo, {"branches": repo_branches(repo)})


@_serialized_repo_operation
def checkout(
    repo: Path,
    ref: str,
    confirmation: str | None = None,
    new_branch: str = "",
    track: bool = False,
) -> dict[str, Any]:
    """Switch to ``ref`` (optionally creating ``new_branch``, optionally --track).

    A dirty tree switch requires confirmation. Git still refuses to overwrite
    conflicting local changes, so there is no data-loss path.
    """
    args = _checkout_args(repo, ref, new_branch=new_branch, track=track)

    if _is_dirty(repo) and not new_branch:
        _require_confirmation(confirmation, CONFIRM_DIRTY_CHECKOUT)
    _mutate(repo, args)
    return _fresh_mutation_result(repo, {"branches": repo_branches(repo)})


def _staged_diff(repo: Path, paths: list[str] | None) -> tuple[str, bool]:
    """Return the bounded staged diff for ``paths`` (or all staged when None).

    Returns ``(diff_text, truncated)``. The diff is capped at MAX_DIFF_BYTES so
    the LLM prompt stays bounded. Untracked/modified-but-unstaged content is
    never included: only what is staged is eligible for a commit message.
    """
    args = ["diff", "--cached", "--no-color"]
    if paths:
        args.append("--")
        args.extend(paths)
    output = _git(repo, *args, literal_pathspecs=bool(paths))
    truncated = len(output) > MAX_DIFF_BYTES
    if truncated:
        output = output[:MAX_DIFF_BYTES]
    return output, truncated


def _llm_call(messages: list[dict[str, str]]):
    """Call the agent's in-process LLM on a chat ``messages`` list.

    Reuses the plugin's existing upstream plumbing: ``agent.auxiliary_client``
    (the same in-process helper the upstream tools use). The import is deferred
    so the module stays importable in test envs without the agent package; the
    message-generation tests monkeypatch ``_llm_call``/``_llm_extract``.
    """
    client = _resolve_llm()
    return client(messages=messages)


def _llm_extract(response: Any) -> str:
    from agent.auxiliary_client import extract_content_or_reasoning

    return extract_content_or_reasoning(response)


def _resolve_llm():
    """Resolve the deferred async model client callable (raises if absent)."""
    from agent.auxiliary_client import async_call_llm

    return async_call_llm


async def _generate_message(diff: str) -> dict[str, Any]:
    """Build the LLM prompt for a bounded staged diff and return the suggestion.

    A missing model or failed call degrades to an empty message with a
    ``model unavailable`` notice — never an exception/500.
    """
    if not diff.strip():
        return {"message": "", "notice": "nothing staged"}
    messages = [
        {
            "role": "user",
            "content": (
                "Write a conventional commit message for the staged diff below. "
                "Return ONLY the message: a subject line under 72 characters in "
                "the form 'type: summary', then a blank line, then an optional "
                "body describing what and why. Do not wrap in quotes.\n\n"
                f"<staged diff>\n{diff}\n</staged diff>"
            ),
        }
    ]
    try:
        response = await _llm_call(messages=messages)
        text = _llm_extract(response).strip()
    except Exception as exc:  # noqa: BLE001
        logger.info("commit message generation unavailable: %s", exc)
        return {"message": "", "notice": "model unavailable for commit messages"}
    if not text:
        return {"message": "", "notice": "model returned no suggestion"}
    return {"message": text, "notice": ""}


async def commit_message(repo: Path) -> dict[str, Any]:
    """Generate a conventional-style commit-message suggestion from the staged diff.

    Empty staged diff → ``{"message": "", "notice": "nothing staged"}`` WITHOUT
    calling the LLM. A missing model or failed call degrades to an empty message
    with a ``model unavailable`` notice — never an exception/500. Only staged
    content is ever sent.
    """
    diff, _ = _staged_diff(repo, None)
    return await _generate_message(diff)


async def commit_message_selected(
    repo: Path, paths: list[str]
) -> dict[str, Any]:
    """Generate a commit-message suggestion from the staged diff of ``paths``.

    Only the given paths' staged content is considered; a path with nothing
    staged contributes nothing. Empty staged diff → no LLM call.
    """
    safe = _validate_paths(paths)
    diff, _ = _staged_diff(repo, safe)
    return await _generate_message(diff)


@_serialized_repo_operation
def stash_checkout(
    repo: Path,
    ref: str,
    new_branch: str = "",
    track: bool = False,
) -> dict[str, Any]:
    """Checkout that auto-stashes a dirty tree first.

    Dirty tree: ``git stash push -m "git-state: <ref>"`` then checkout; returns
    ``{stashed: true, stash_message}`` so the UI can surface the stash. Clean
    tree: plain checkout with ``{stashed: false}``. No confirmation is required
    because a stash is recoverable (``git stash``), unlike discard — documented
    in the endpoint and UI. Git still refuses to overwrite conflicting changes,
    so there is no data-loss path. ``new_branch``/``track`` mirror the plain
    checkout surface.
    """
    args = _checkout_args(repo, ref, new_branch=new_branch, track=track)

    stashed = False
    stash_message = ""
    stash_oid = ""
    if _is_dirty(repo):
        stash_message = f"git-state: {ref or new_branch}"
        _mutate(repo, ["stash", "push", "--include-untracked", "-m", stash_message])
        stash_oid = _git(repo, "rev-parse", "--verify", "refs/stash").strip()
        stashed = True

    try:
        _mutate(repo, args)
    except GitError as checkout_error:
        if not stashed:
            raise
        try:
            _mutate(repo, ["stash", "apply", "--index", stash_oid])
        except GitError as restore_error:
            raise GitError(
                f"{checkout_error}; changes remain in stash {stash_oid}; "
                f"automatic restore failed: {restore_error}",
                code=checkout_error.code,
            ) from checkout_error
        raise GitError(
            f"{checkout_error}; working changes were restored and remain backed up "
            f"in stash {stash_oid}",
            code=checkout_error.code,
        ) from checkout_error
    return {
        **{"stashed": stashed, "stash_message": stash_message},
        **_fresh_mutation_result(repo, {"branches": repo_branches(repo)}),
    }


def repo_remotes(repo: Path) -> list[dict[str, str]]:
    """Return remote names + URLs with embedded userinfo scrubbed."""
    raw = _git(repo, "remote", "-v")
    remotes: list[dict[str, str]] = []
    seen: set[str] = set()
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        name, url = parts[0], parts[1]
        if name in seen:
            continue
        seen.add(name)
        remotes.append({"name": name, "url": _scrub_url(url)})
    return remotes


def _scrub_url(url: str) -> str:
    """Strip userinfo (``user:pass@`` / ``user@``) from a remote URL."""
    scrubbed = _USERINFO_RE.sub(r"\1", url)
    # scp-style: user@host:path — drop the user but keep the host:path.
    scrubbed = _SSH_USERINFO_RE.sub(r"\2:", scrubbed)
    return scrubbed


def build_git_document(base: Path) -> dict[str, Any]:
    """Build the declarative Git page document served at ``mobile/pages/git``.

    The document is a read-only snapshot: it lists scanned repositories as
    literal text and carries no filesystem paths (per the android-plugins.md
    document contract). Interactive repo → status → diff/file browsing is
    provided by the dedicated Android Compose screen, which calls the
    ``/git/*`` API endpoints directly; this document is the manifest-declared
    fallback surface.
    """
    repos = scan_repos(base)
    children: list[dict[str, Any]] = []
    if not base.is_dir():
        children.append(
            {
                "type": "text",
                "id": "notice",
                "text": {
                    "type": "literal",
                    "value": "Configured Git base path was not found. Check the plugin setting.",
                },
            }
        )
    elif not repos:
        children.append(
            {
                "type": "text",
                "id": "empty",
                "text": {
                    "type": "literal",
                    "value": "No repositories found under the configured base path.",
                },
            }
        )
    else:
        for repo in repos:
            dirty = " (dirty)" if repo["dirty"] else ""
            children.append(
                {
                    "type": "text",
                    "id": f"repo-{repo['id']}",
                    "text": {
                        "type": "literal",
                        "value": f"{repo['name']} — {repo['current_branch'] or 'detached'}{dirty}",
                    },
                }
            )

    return {
        "schemaVersion": 1,
        "pages": [
            {
                "id": "git",
                "title": {"type": "literal", "value": "Git"},
                "content": {
                    "type": "group",
                    "id": "git-root",
                    "direction": "column",
                    "children": children,
                },
            }
        ],
    }


__all__ = [
    "DEFAULT_BASE_PATH",
    "GIT_TIMEOUT_SECONDS",
    "GitStateError",
    "MAX_DIFF_BYTES",
    "MAX_FILE_BYTES",
    "MAX_STATUS_ENTRIES",
    "base_path",
    "build_git_document",
    "commit_message",
    "commit_message_selected",
    "read_file",
    "repo_branches",
    "repo_diff",
    "repo_id",
    "repo_remotes",
    "repo_status",
    "resolve_repo",
    "resolve_repo_path",
    "scan_repos",
    "stash_checkout",
]
