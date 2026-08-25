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
  null bytes. Git itself treats paths as repo-relative, so this is defense in
  depth.
- Remote URLs are scrubbed of embedded userinfo before they reach any client.
"""

from __future__ import annotations

import logging
import os
import re
import subprocess
from pathlib import Path
from typing import Any

from .config import raw_config_value

logger = logging.getLogger(__name__)

# Bounded response caps (bytes / entries). Configurable via env for operators.
MAX_STATUS_ENTRIES = int(os.environ.get("GIT_STATE_MAX_STATUS_ENTRIES", "200"))
MAX_DIFF_BYTES = int(os.environ.get("GIT_STATE_MAX_DIFF_BYTES", "64_000"))
MAX_FILE_BYTES = int(os.environ.get("GIT_STATE_MAX_FILE_BYTES", "256_000"))
GIT_TIMEOUT_SECONDS = float(os.environ.get("GIT_STATE_TIMEOUT_SECONDS", "10"))

# Default base path for repo discovery.
DEFAULT_BASE_PATH = "~/projects"
_BASE_PATH_ENV = "GIT_STATE_BASE_PATH"

# Matches a remote URL's userinfo (user[:password]@) so it can be scrubbed.
_USERINFO_RE = re.compile(r"^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@]+)@")
_SSH_USERINFO_RE = re.compile(r"^([^/@:]+)@([^:]+):")


class GitStateError(ValueError):
    """A caller supplied an invalid repo id, path, or diff kind."""


def base_path() -> Path:
    """Resolve the configured discovery base path (default ``~/projects``)."""
    raw = raw_config_value(_BASE_PATH_ENV) or DEFAULT_BASE_PATH
    return Path(raw).expanduser()


def _git(repo: Path, *args: str) -> str:
    """Run ``git -C <repo> <args>`` and return stdout. Raises on failure."""
    try:
        result = subprocess.run(
            ["git", "-C", str(repo), *args],
            capture_output=True,
            text=True,
            timeout=GIT_TIMEOUT_SECONDS,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise GitStateError(f"git timed out for {repo.name}") from exc
    except OSError as exc:
        raise GitStateError(f"could not run git for {repo.name}: {exc}") from exc
    if result.returncode != 0:
        raise GitStateError(
            f"git {args[0] if args else 'command'} failed for {repo.name}: "
            f"{result.stderr.strip() or result.stdout.strip()}"
        )
    return result.stdout


def _is_git_repo(path: Path) -> bool:
    """True if ``path`` is a git work tree (has a .git entry)."""
    return (path / ".git").exists()


def repo_id(repo: Path) -> str:
    """Opaque, stable id for a repo — its directory basename."""
    return repo.name


def scan_repos(base: Path) -> list[dict[str, Any]]:
    """Recursively scan ``base`` for git repositories.

    Returns a list of ``{id, name, root, current_branch, dirty}``. A missing
    base path yields an empty list (never an exception). ``.git`` internals are
    never reported as repositories.
    """
    if not base.is_dir():
        return []

    repos: list[dict[str, Any]] = []
    for root in sorted(base.rglob("*")):
        if not root.is_dir():
            continue
        if root.name == ".git":
            continue
        if not _is_git_repo(root):
            continue
        repos.append(_describe_repo(root))
    return repos


def _describe_repo(repo: Path) -> dict[str, Any]:
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
        "id": repo_id(repo),
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
    staged: list[dict[str, str]] = []
    modified: list[dict[str, str]] = []
    untracked: list[dict[str, str]] = []
    truncated = False

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
            untracked.append({"path": path})
        # Independent checks: a file staged AND modified lands in both groups.
        if xy[0] in ("M", "A", "D", "R", "C"):
            staged.append({"path": path})
        if xy[1] in ("M", "D"):
            modified.append({"path": path})
        if xy[0] in ("R", "C"):
            # The immediately following record is the rename/copy source path;
            # skip it so it is not misparsed as an XY record.
            i += 1

    def _bounded(items: list[dict[str, str]]) -> list[dict[str, str]]:
        nonlocal truncated
        if len(items) > MAX_STATUS_ENTRIES:
            truncated = True
            return items[:MAX_STATUS_ENTRIES]
        return items

    staged = _bounded(staged)
    modified = _bounded(modified)
    untracked = _bounded(untracked)

    return {
        "counts": {
            "staged": len(staged),
            "modified": len(modified),
            "untracked": len(untracked),
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
    output = _git(repo, *args, safe_path)
    truncated = len(output) > MAX_DIFF_BYTES
    if truncated:
        output = output[:MAX_DIFF_BYTES]
    return {"path": safe_path, "kind": kind, "diff": output, "truncated": truncated}


def read_file(repo: Path, path: str) -> dict[str, Any]:
    """Read a tracked file's working-tree content. Untracked/binary/missing → error."""
    safe_path = resolve_repo_path(repo, path)
    # Confirm the file is tracked before reading.
    try:
        _git(repo, "ls-files", "--error-unmatch", "--", safe_path)
    except GitStateError as exc:
        raise GitStateError(f"file is not tracked: {safe_path}") from exc

    # Read the working-tree file from disk (not `git show HEAD:`), so a
    # modified-but-uncommitted file returns what is on disk. Read bytes first:
    # binary content dies on the NUL check (before any decode), and non-UTF-8
    # text raises a clear GitStateError instead of an unhandled 500.
    disk_path = repo / safe_path
    try:
        raw = disk_path.read_bytes()
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
    "read_file",
    "repo_branches",
    "repo_diff",
    "repo_id",
    "repo_remotes",
    "repo_status",
    "resolve_repo",
    "resolve_repo_path",
    "scan_repos",
]
