"""Read-only Git state endpoints for the Hermes-Relay dashboard plugin.

Mounted by hermes-agent at ``/api/plugins/hermes-relay/git/*``. These routes
are the source of truth for the Git read surface and power both the dashboard
tab and the Android mobile page. All operations are read-only; no
``plugin.api.write`` grant is required.

Route map
---------
- ``GET /git/repos``      → scanned repo list under the configured base path
- ``GET /git/status``     → grouped working-tree status for one repo
- ``GET /git/branches``   → branch list (name, upstream, ahead/behind, current)
- ``GET /git/diff``       → per-file diff (kind=staged|unstaged)
- ``GET /git/file``       → read a tracked file

Security
--------
- ``repo`` is an opaque id validated against the scanned allowlist; unknown
  ids → 400.
- File paths are validated to reject traversal and absolute escapes.
- Remote URLs are scrubbed of embedded userinfo.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Query

from .. import git_state

router = APIRouter(prefix="/git")


def _bad_request(exc: Exception) -> HTTPException:
    return HTTPException(status_code=400, detail=str(exc))


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
        root = git_state.resolve_repo(git_state.base_path(), repo)
        return git_state.repo_status(root)
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.get("/branches")
async def get_branches(repo: str = Query(...)) -> dict[str, Any]:
    try:
        root = git_state.resolve_repo(git_state.base_path(), repo)
        return {"branches": git_state.repo_branches(root)}
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.get("/diff")
async def get_diff(
    repo: str = Query(...),
    path: str = Query(...),
    kind: str = Query("unstaged"),
) -> dict[str, Any]:
    try:
        root = git_state.resolve_repo(git_state.base_path(), repo)
        return git_state.repo_diff(root, path, kind)
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


@router.get("/file")
async def get_file(repo: str = Query(...), path: str = Query(...)) -> dict[str, Any]:
    try:
        root = git_state.resolve_repo(git_state.base_path(), repo)
        return git_state.read_file(root, path)
    except git_state.GitStateError as exc:
        raise _bad_request(exc) from exc


__all__ = ["router"]
