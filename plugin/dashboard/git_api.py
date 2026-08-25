"""Read and write Git state endpoints for the Hermes-Relay dashboard plugin.

Mounted by hermes-agent at ``/api/plugins/hermes-relay/git/*``. These routes
power both the dashboard tab and the Android mobile page.

Route map
---------
Read (GET, no grant):
- ``GET /git/repos``      → scanned repo list under the configured base path
- ``GET /git/status``     → grouped working-tree status for one repo
- ``GET /git/branches``   → branch list (name, upstream, ahead/behind, current)
- ``GET /git/diff``       → per-file diff (kind=staged|unstaged)
- ``GET /git/file``       → read a tracked file

Write (POST, require the plugin's ``plugin.api.write`` grant, which the app
enforces client-side before ever sending the request — the same gate used for
every mutating plugin action in PluginsViewModel.invokeAction):
- ``POST /git/stage``          — stage path list → fresh status
- ``POST /git/unstage``        — unstage path list → fresh status
- ``POST /git/discard``        — discard paths (confirmation) → fresh status
- ``POST /git/commit``         — commit staged index (message) → {head,status}
- ``POST /git/commit_selected``— commit selected paths (message+paths)
- ``POST /git/fetch``          — fetch a remote → {branches,status}
- ``POST /git/pull``           — pull remote/branch → fresh status
- ``POST /git/push``           — push (confirmation) → {branches,status}
- ``POST /git/checkout``       — switch branch (new_branch/track; dirty→confirmation)

Destructive writes (discard, push, dirty checkout) enforce a per-use
confirmation string server-side: missing/wrong → 403. Dirty/conflict trees →
409. The HTTP mapping keeps raw stack traces and JSON dumps out of the UI.

Security
--------
- ``repo`` is an opaque id validated against the scanned allowlist; unknown
  ids → 400.
- File paths are validated to reject traversal and absolute escapes.
- Remote URLs are scrubbed of embedded userinfo.
- No ``shell=True`` anywhere: every git invocation uses argument lists.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, HTTPException, Query

from .. import git_state

router = APIRouter(prefix="/git")


def _bad_request(exc: Exception) -> HTTPException:
    return HTTPException(status_code=400, detail=str(exc))


# Structured error taxonomy → HTTP status. The UI renders ``detail`` (a
# human-readable message) plus ``code`` for styling; no raw traces/JSON dumps.
_GIT_ERROR_STATUS = {
    "non-repo": 400,
    "dirty": 409,
    "conflict": 409,
    "auth": 502,
    "network": 502,
    "invalid-input": 400,
    "missing-confirmation": 403,
    "wrong-confirmation": 403,
}


def _write_error(exc: git_state.GitError) -> HTTPException:
    status = _GIT_ERROR_STATUS.get(exc.code, 400)
    return HTTPException(status_code=status, detail=str(exc))


def _resolve(repo: str) -> Any:
    return git_state.resolve_repo(git_state.base_path(), repo)


@router.get("/repos")
async def get_repos() -> dict[str, Any]:
    """Return the scanned repo list plus a notice when the base path is missing."""
    base = git_state.base_path()
    repos = git_state.scan_repos(base)
    notice = None
    if not base.is_dir():
        notice = f"Git base path not found: {base}"
    return {"repos": repos, "base_path": str(base), "notice": notice}


@router.get("/status")
async def get_status(repo: str = Query(...)) -> dict[str, Any]:
    try:
        return git_state.repo_status(_resolve(repo))
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.get("/branches")
async def get_branches(repo: str = Query(...)) -> dict[str, Any]:
    try:
        return {"branches": git_state.repo_branches(_resolve(repo))}
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.get("/diff")
async def get_diff(
    repo: str = Query(...),
    path: str = Query(...),
    kind: str = Query("unstaged"),
) -> dict[str, Any]:
    try:
        return git_state.repo_diff(_resolve(repo), path, kind)
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.get("/file")
async def get_file(repo: str = Query(...), path: str = Query(...)) -> dict[str, Any]:
    try:
        return git_state.read_file(_resolve(repo), path)
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


# ── Write endpoints ────────────────────────────────────────────────────────
# Every write requires the plugin.api.write grant (enforced client-side). The
# router re-validates repo + paths against the allowlist/traversal rules and
# classifies git failures into a structured error the UI can render.

def _require_repo(body: dict[str, Any]) -> Any:
    repo = body.get("repo")
    if not isinstance(repo, str) or not repo:
        raise git_state.GitStateError("repo is required")
    return git_state.resolve_repo(git_state.base_path(), repo)


@router.post("/stage")
async def post_stage(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.stage(_require_repo(body), _paths(body))
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.post("/unstage")
async def post_unstage(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.unstage(_require_repo(body), _paths(body))
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.post("/discard")
async def post_discard(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.discard(
            _require_repo(body),
            _paths(body),
            confirmation=body.get("confirmation"),
            delete_untracked=bool(body.get("delete_untracked", False)),
        )
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.post("/commit")
async def post_commit(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.commit(_require_repo(body), _message(body))
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.post("/commit_selected")
async def post_commit_selected(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.commit_selected(_require_repo(body), _message(body), _paths(body))
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.post("/fetch")
async def post_fetch(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.fetch(_require_repo(body), _remote(body))
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.post("/pull")
async def post_pull(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.pull(_require_repo(body), _remote(body), _branch(body))
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.post("/push")
async def post_push(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.push(
            _require_repo(body),
            _remote(body),
            _branch(body),
            confirmation=body.get("confirmation"),
        )
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.post("/checkout")
async def post_checkout(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        return git_state.checkout(
            _require_repo(body),
            _ref(body),
            confirmation=body.get("confirmation"),
            new_branch=_str_opt(body, "new_branch"),
            track=bool(body.get("track", False)),
        )
    except git_state.GitError as exc:
        raise _write_error(exc) from exc
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


def _paths(body: dict[str, Any]) -> list[str]:
    paths = body.get("paths")
    if not isinstance(paths, list) or not paths or not all(isinstance(p, str) for p in paths):
        raise git_state.GitStateError("paths must be a non-empty list of strings")
    return paths


def _message(body: dict[str, Any]) -> str:
    message = body.get("message")
    if not isinstance(message, str):
        raise git_state.GitStateError("message is required")
    return message


def _remote(body: dict[str, Any]) -> str:
    return body.get("remote") or "origin"


def _branch(body: dict[str, Any]) -> str:
    return body.get("branch") or ""


def _ref(body: dict[str, Any]) -> str:
    ref = body.get("ref")
    if not isinstance(ref, str) or not ref:
        raise git_state.GitStateError("ref is required")
    return ref


def _str_opt(body: dict[str, Any], key: str) -> str:
    value = body.get(key)
    return value if isinstance(value, str) else ""


__all__ = ["router"]
