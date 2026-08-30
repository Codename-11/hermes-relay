"""Authenticated dashboard routes for declarative Android plugin pages."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, HTTPException, Path

from ..mobile_plugin_store import (
    MobilePluginConflictError,
    MobilePluginNotFoundError,
    MobilePluginStore,
    MobilePluginStoreError,
)


router = APIRouter(prefix="/mobile")


def _store() -> MobilePluginStore:
    return MobilePluginStore()


def _bad_request(exc: MobilePluginStoreError) -> HTTPException:
    return HTTPException(status_code=400, detail=str(exc))


@router.get("/manifest")
async def get_mobile_manifest() -> dict[str, Any]:
    return _store().manifest()


@router.get("/pages/{plugin_id}")
def get_mobile_page(plugin_id: str = Path(...)) -> dict[str, Any]:
    # The static read-only Git page is served directly from the git_state
    # module (not the generated-page store). It carries no filesystem paths
    # per the android-plugins.md document contract.
    if plugin_id == "git":
        from .. import git_state

        document = git_state.build_git_document(git_state.base_path())
        document["host_revision"] = 1
        return document
    try:
        entry = _store().get(plugin_id)
        document = dict(entry["document"])
        document["host_revision"] = entry.get("revision", 1)
        return document
    except MobilePluginStoreError as exc:
        raise _bad_request(exc) from exc
    except MobilePluginNotFoundError as exc:
        raise HTTPException(status_code=404, detail="mobile plugin not found") from exc


@router.get("/plugins")
async def list_mobile_plugins() -> dict[str, Any]:
    return {"plugins": _store().list()}


@router.get("/plugins/{plugin_id}")
async def get_mobile_plugin(plugin_id: str = Path(...)) -> dict[str, Any]:
    try:
        return _store().get(plugin_id)
    except MobilePluginStoreError as exc:
        raise _bad_request(exc) from exc
    except MobilePluginNotFoundError as exc:
        raise HTTPException(status_code=404, detail="mobile plugin not found") from exc


@router.put("/plugins/{plugin_id}/draft")
async def put_mobile_plugin_draft(
    plugin_id: str = Path(...),
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    try:
        return _store().draft(
            plugin_id,
            title=body.get("title", ""),
            description=body.get("description", ""),
            document=body.get("document"),
            lifecycle=body.get("lifecycle", "session"),
        )
    except (AttributeError, MobilePluginStoreError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/plugins/{plugin_id}/promote")
async def promote_mobile_plugin(
    plugin_id: str = Path(...),
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    try:
        expected_digest = body.get("expected_digest")
        if not isinstance(expected_digest, str) or not expected_digest:
            raise MobilePluginStoreError("expected_digest is required")
        return _store().publish(plugin_id, expected_digest=expected_digest)
    except MobilePluginConflictError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except MobilePluginStoreError as exc:
        raise _bad_request(exc) from exc
    except MobilePluginNotFoundError as exc:
        raise HTTPException(status_code=404, detail="mobile plugin not found") from exc


@router.post("/plugins/{plugin_id}/remove")
async def delete_mobile_plugin(
    plugin_id: str = Path(...),
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    try:
        expected_digest = body.get("expected_digest")
        if not isinstance(expected_digest, str) or not expected_digest:
            raise MobilePluginStoreError("expected_digest is required")
        return _store().remove(plugin_id, expected_digest=expected_digest)
    except MobilePluginConflictError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except MobilePluginStoreError as exc:
        raise _bad_request(exc) from exc
    except MobilePluginNotFoundError as exc:
        raise HTTPException(status_code=404, detail="mobile plugin not found") from exc


__all__ = ["router"]
