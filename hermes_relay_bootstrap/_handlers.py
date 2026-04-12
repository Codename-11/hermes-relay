"""Ported handlers from `feat/api-server-enhancements` (Codename-11/hermes-agent).

This file mirrors the management endpoints from the fork branch, adapted to
take the `APIServerAdapter` instance as an explicit parameter rather than
relying on `self`. That keeps the patch loosely coupled to upstream's class
shape — we don't bind methods onto the adapter, just register closures that
capture an `adapter` reference.

Endpoints injected (all bearer-auth gated via `adapter._check_auth`):

  GET    /api/sessions                          — list sessions
  POST   /api/sessions                          — create a new session
  GET    /api/sessions/search?q=...             — full-text message search
  GET    /api/sessions/{session_id}             — fetch one session
  GET    /api/sessions/{session_id}/messages    — fetch session messages
  PATCH  /api/sessions/{session_id}             — rename / update metadata
  DELETE /api/sessions/{session_id}             — delete a session
  POST   /api/sessions/{session_id}/fork        — clone a session

  GET    /api/memory                            — read memory state
  POST   /api/memory                            — append memory entry
  PATCH  /api/memory                            — replace memory entry
  DELETE /api/memory                            — remove memory entry

  GET    /api/skills                            — list skills (optional ?category=)
  GET    /api/skills/categories                 — list skill categories
  GET    /api/skills/{name}                     — fetch skill body

  GET    /api/config                            — read model + config
  PATCH  /api/config                            — update model/provider/base_url

  GET    /api/available-models                  — provider model list

NOT injected — chat streaming intentionally goes through upstream's standard
`/v1/runs` endpoint, which already emits `tool.started`/`tool.completed` SSE
events. Avoiding the chat handlers also means we don't have to coordinate
with `_create_agent` / `agent.run_conversation`, which the fork modified in
ways we'd otherwise have to mirror exactly.

Removal note: when upstream PR #8556 merges and a released hermes-agent
version contains these endpoints, this entire file becomes dead weight. The
bootstrap's feature detection no-ops on the existing routes, so leaving it in
place is harmless during the rollout window. Cleanup is a clean delete of
the `hermes_relay_bootstrap/` package and its `.pth` file.
"""

from __future__ import annotations

import json
import logging
import uuid
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Lazy upstream imports
# ---------------------------------------------------------------------------
#
# These get pulled in only when `register_routes()` runs (i.e. only when the
# gateway is actually starting up an APIServerAdapter against vanilla
# upstream). The bootstrap's `__init__.py` deliberately avoids importing
# anything from hermes-agent so it stays cheap for unrelated Python processes
# in the same venv.

def _resolve_upstream():
    """Pull in upstream symbols. Returns a dict or raises if anything is missing."""
    from aiohttp import web

    from hermes_state import SessionDB
    from hermes_cli.config import load_config, save_config
    from hermes_cli.models import (
        curated_models_for_provider,
        list_available_providers,
    )
    from tools.skills_tool import skill_view, skills_categories, skills_list

    # MemoryStore lives at tools/memory_tool.py upstream. We import it lazily
    # because it pulls in a chain of optional deps that we don't want to crash
    # the bootstrap over if memory tooling is misconfigured.
    try:
        from tools.memory_tool import MemoryStore
    except Exception as exc:  # pragma: no cover - defensive
        logger.debug("hermes_relay_bootstrap: MemoryStore unavailable: %s", exc)
        MemoryStore = None  # type: ignore[assignment]

    return {
        "web": web,
        "SessionDB": SessionDB,
        "MemoryStore": MemoryStore,
        "load_config": load_config,
        "save_config": save_config,
        "curated_models_for_provider": curated_models_for_provider,
        "list_available_providers": list_available_providers,
        "skills_list": skills_list,
        "skills_categories": skills_categories,
        "skill_view": skill_view,
    }


# ---------------------------------------------------------------------------
# Per-adapter state cache
# ---------------------------------------------------------------------------
#
# We can't add attributes directly to upstream's `APIServerAdapter` instance
# without risking name collisions on future refactors. Instead we keep a
# small WeakKeyDictionary keyed on the adapter, holding our SessionDB and
# MemoryStore references. The lifetime of the cache entries matches the
# adapter's lifetime — when the adapter is garbage-collected, the cache
# entries follow.

import weakref

_adapter_state: "weakref.WeakKeyDictionary[Any, Dict[str, Any]]" = weakref.WeakKeyDictionary()


def _state_for(adapter) -> Dict[str, Any]:
    state = _adapter_state.get(adapter)
    if state is None:
        state = {}
        _adapter_state[adapter] = state
    return state


def _get_session_db(adapter, upstream):
    state = _state_for(adapter)
    db = state.get("session_db")
    if db is None:
        db = upstream["SessionDB"]()
        state["session_db"] = db
    return db


def _get_memory_store(adapter, upstream):
    if upstream["MemoryStore"] is None:
        return None
    state = _state_for(adapter)
    store = state.get("memory_store")
    if store is None:
        store = upstream["MemoryStore"]()
        store.load_from_disk()
        state["memory_store"] = store
    return store


# ---------------------------------------------------------------------------
# Pure helpers (no adapter coupling)
# ---------------------------------------------------------------------------

def _normalize_session_record(session: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Parse serialized session fields into API-friendly JSON."""
    if session is None:
        return None
    normalized = dict(session)
    model_config = normalized.get("model_config")
    if model_config:
        try:
            normalized["model_config"] = json.loads(model_config)
        except (TypeError, json.JSONDecodeError):
            pass
    return normalized


def _current_model_settings(config: Dict[str, Any]) -> Dict[str, Any]:
    """Extract model/provider/base_url/api_mode from config.yaml."""
    model_cfg = config.get("model")
    if isinstance(model_cfg, dict):
        return {
            "model": str(model_cfg.get("default") or model_cfg.get("model") or "").strip(),
            "provider": str(model_cfg.get("provider") or "").strip(),
            "api_mode": str(model_cfg.get("api_mode") or "").strip(),
            "base_url": str(model_cfg.get("base_url") or "").strip(),
        }
    if isinstance(model_cfg, str):
        return {
            "model": model_cfg.strip(),
            "provider": "",
            "api_mode": "",
            "base_url": "",
        }
    return {"model": "", "provider": "", "api_mode": "", "base_url": ""}


def _parse_int(value: Any, default: int, minimum: int = 0) -> int:
    """Parse an integer query parameter with bounds."""
    if value in (None, ""):
        return default
    parsed = int(value)
    if parsed < minimum:
        raise ValueError(f"Value must be >= {minimum}")
    return parsed


# ---------------------------------------------------------------------------
# Sessions handlers
# ---------------------------------------------------------------------------

def _make_sessions_handlers(adapter, upstream):
    web = upstream["web"]

    async def list_sessions(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        try:
            limit = _parse_int(request.query.get("limit"), 50)
            offset = _parse_int(request.query.get("offset"), 0)
        except ValueError as exc:
            return web.json_response({"error": str(exc)}, status=400)

        source = (request.query.get("source") or "").strip() or None
        db = _get_session_db(adapter, upstream)
        items = [
            _normalize_session_record(item)
            for item in db.list_sessions_rich(source=source, limit=limit, offset=offset)
        ]
        total = db.session_count(source=source)
        return web.json_response({"items": items, "total": total})

    async def create_session(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        try:
            body = await request.json()
        except (json.JSONDecodeError, Exception):
            return web.json_response({"error": "Invalid JSON in request body"}, status=400)

        title = body.get("title")
        source = str(body.get("source") or "api_server").strip() or "api_server"
        model = body.get("model")
        system_prompt = body.get("system_prompt")
        session_id = f"sess_{uuid.uuid4().hex}"
        db = _get_session_db(adapter, upstream)

        try:
            db.create_session(
                session_id=session_id,
                source=source,
                model=model,
                system_prompt=system_prompt,
            )
            if title is not None:
                db.set_session_title(session_id, str(title))
        except ValueError as exc:
            return web.json_response({"error": str(exc)}, status=400)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=500)

        session = _normalize_session_record(db.get_session(session_id))
        return web.json_response({"session": session})

    async def search_sessions(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        query = (request.query.get("q") or "").strip()
        if not query:
            return web.json_response({"error": "Missing query parameter: q"}, status=400)
        try:
            limit = _parse_int(request.query.get("limit"), 20)
            offset = _parse_int(request.query.get("offset"), 0)
        except ValueError as exc:
            return web.json_response({"error": str(exc)}, status=400)

        db = _get_session_db(adapter, upstream)
        results = db.search_messages(query=query, limit=limit, offset=offset)
        return web.json_response({"query": query, "count": len(results), "results": results})

    async def get_session(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        session_id = request.match_info["session_id"]
        db = _get_session_db(adapter, upstream)
        session = _normalize_session_record(db.get_session(session_id))
        if session is None:
            return web.json_response({"error": "Session not found"}, status=404)
        return web.json_response({"session": session})

    async def get_session_messages(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        session_id = request.match_info["session_id"]
        db = _get_session_db(adapter, upstream)
        if db.get_session(session_id) is None:
            db.ensure_session(session_id, source="web")
        items = db.get_messages(session_id)
        return web.json_response({"items": items, "total": len(items)})

    async def update_session(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        session_id = request.match_info["session_id"]
        db = _get_session_db(adapter, upstream)
        if db.get_session(session_id) is None:
            return web.json_response({"error": "Session not found"}, status=404)
        try:
            body = await request.json()
        except (json.JSONDecodeError, Exception):
            return web.json_response({"error": "Invalid JSON in request body"}, status=400)

        try:
            if "title" in body:
                db.set_session_title(session_id, body.get("title"))
            if "system_prompt" in body:
                db.update_system_prompt(session_id, body.get("system_prompt"))
            if "end_reason" in body:
                db.end_session(session_id, str(body.get("end_reason") or "updated"))
        except ValueError as exc:
            return web.json_response({"error": str(exc)}, status=400)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=500)

        session = _normalize_session_record(db.get_session(session_id))
        return web.json_response({"session": session})

    async def delete_session(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        session_id = request.match_info["session_id"]
        db = _get_session_db(adapter, upstream)
        deleted = db.delete_session(session_id)
        if not deleted:
            return web.json_response({"error": "Session not found"}, status=404)
        return web.json_response({"ok": True})

    async def fork_session(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        session_id = request.match_info["session_id"]
        db = _get_session_db(adapter, upstream)
        original = db.get_session(session_id)
        if original is None:
            return web.json_response({"error": "Session not found"}, status=404)

        forked_id = f"sess_{uuid.uuid4().hex}"
        try:
            db.create_session(
                session_id=forked_id,
                source=original.get("source") or "api_server",
                model=original.get("model"),
                system_prompt=original.get("system_prompt"),
                user_id=original.get("user_id"),
                parent_session_id=session_id,
            )
            for message in db.get_messages(session_id):
                db.append_message(
                    session_id=forked_id,
                    role=message.get("role"),
                    content=message.get("content"),
                    tool_name=message.get("tool_name"),
                    tool_calls=message.get("tool_calls"),
                    tool_call_id=message.get("tool_call_id"),
                    token_count=message.get("token_count"),
                    finish_reason=message.get("finish_reason"),
                    reasoning=message.get("reasoning"),
                )
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=500)

        session = _normalize_session_record(db.get_session(forked_id))
        return web.json_response({"session": session, "forked_from": session_id})

    return {
        "list_sessions": list_sessions,
        "create_session": create_session,
        "search_sessions": search_sessions,
        "get_session": get_session,
        "get_session_messages": get_session_messages,
        "update_session": update_session,
        "delete_session": delete_session,
        "fork_session": fork_session,
    }


# ---------------------------------------------------------------------------
# Memory handlers
# ---------------------------------------------------------------------------

def _make_memory_handlers(adapter, upstream):
    web = upstream["web"]

    def _memory_unavailable_response():
        return web.json_response(
            {"error": "MemoryStore unavailable in this hermes-agent install"},
            status=503,
        )

    async def get_memory(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        target = (request.query.get("target") or "all").strip().lower()
        if target not in {"all", "memory", "user"}:
            return web.json_response(
                {"error": "target must be one of: all, memory, user"},
                status=400,
            )
        store = _get_memory_store(adapter, upstream)
        if store is None:
            return _memory_unavailable_response()
        store.load_from_disk()
        targets = []
        if target in {"all", "memory"}:
            targets.append({
                "target": "memory",
                "entries": store.memory_entries,
                "entry_count": len(store.memory_entries),
            })
        if target in {"all", "user"}:
            targets.append({
                "target": "user",
                "entries": store.user_entries,
                "entry_count": len(store.user_entries),
            })
        return web.json_response({"targets": targets})

    async def add_memory(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        try:
            body = await request.json()
        except (json.JSONDecodeError, Exception):
            return web.json_response({"error": "Invalid JSON in request body"}, status=400)

        target = str(body.get("target") or "").strip().lower()
        content = str(body.get("content") or "")
        if target not in {"memory", "user"}:
            return web.json_response({"error": "target must be 'memory' or 'user'"}, status=400)
        store = _get_memory_store(adapter, upstream)
        if store is None:
            return _memory_unavailable_response()
        result = store.add(target, content)
        status = 200 if result.get("success") else 400
        return web.json_response(result, status=status)

    async def replace_memory(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        try:
            body = await request.json()
        except (json.JSONDecodeError, Exception):
            return web.json_response({"error": "Invalid JSON in request body"}, status=400)

        target = str(body.get("target") or "").strip().lower()
        old_text = str(body.get("old_text") or "")
        content = str(body.get("content") or "")
        if target not in {"memory", "user"}:
            return web.json_response({"error": "target must be 'memory' or 'user'"}, status=400)
        store = _get_memory_store(adapter, upstream)
        if store is None:
            return _memory_unavailable_response()
        result = store.replace(target, old_text, content)
        status = 200 if result.get("success") else 400
        return web.json_response(result, status=status)

    async def delete_memory(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        try:
            body = await request.json()
        except (json.JSONDecodeError, Exception):
            return web.json_response({"error": "Invalid JSON in request body"}, status=400)

        target = str(body.get("target") or "").strip().lower()
        old_text = str(body.get("old_text") or "")
        if target not in {"memory", "user"}:
            return web.json_response({"error": "target must be 'memory' or 'user'"}, status=400)
        store = _get_memory_store(adapter, upstream)
        if store is None:
            return _memory_unavailable_response()
        result = store.remove(target, old_text)
        status = 200 if result.get("success") else 400
        return web.json_response(result, status=status)

    return {
        "get_memory": get_memory,
        "add_memory": add_memory,
        "replace_memory": replace_memory,
        "delete_memory": delete_memory,
    }


# ---------------------------------------------------------------------------
# Skills handlers
# ---------------------------------------------------------------------------

def _make_skills_handlers(adapter, upstream):
    web = upstream["web"]
    skills_list = upstream["skills_list"]
    skills_categories = upstream["skills_categories"]
    skill_view = upstream["skill_view"]

    async def list_skills(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        category = (request.query.get("category") or "").strip() or None
        return web.json_response(json.loads(skills_list(category=category)))

    async def skill_categories(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        return web.json_response(json.loads(skills_categories()))

    async def view_skill(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        name = request.match_info["name"]
        file_path = (request.query.get("file_path") or "").strip() or None
        return web.json_response(json.loads(skill_view(name, file_path=file_path)))

    return {
        "list_skills": list_skills,
        "skill_categories": skill_categories,
        "view_skill": view_skill,
    }


# ---------------------------------------------------------------------------
# Config + available-models handlers
# ---------------------------------------------------------------------------

def _make_config_handlers(adapter, upstream):
    web = upstream["web"]
    load_config = upstream["load_config"]
    save_config = upstream["save_config"]
    curated_models_for_provider = upstream["curated_models_for_provider"]
    list_available_providers = upstream["list_available_providers"]

    async def get_config(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        config = load_config()
        current = _current_model_settings(config)
        return web.json_response({
            "model": current["model"],
            "provider": current["provider"],
            "api_mode": current["api_mode"],
            "base_url": current["base_url"],
            "config": config,
        })

    async def update_config(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        try:
            body = await request.json()
        except (json.JSONDecodeError, Exception):
            return web.json_response({"error": "Invalid JSON in request body"}, status=400)

        config = load_config()
        model_cfg = config.get("model")
        if isinstance(model_cfg, dict):
            updated_model_cfg = dict(model_cfg)
        elif isinstance(model_cfg, str) and model_cfg.strip():
            updated_model_cfg = {"default": model_cfg.strip()}
        else:
            updated_model_cfg = {}

        if "model" in body:
            updated_model_cfg["default"] = str(body.get("model") or "").strip()
        if "provider" in body:
            updated_model_cfg["provider"] = str(body.get("provider") or "").strip()
        if "base_url" in body:
            updated_model_cfg["base_url"] = str(body.get("base_url") or "").strip()

        config["model"] = updated_model_cfg
        try:
            save_config(config)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=500)

        current = _current_model_settings(config)
        return web.json_response({
            "ok": True,
            "model": current["model"],
            "provider": current["provider"],
            "base_url": current["base_url"],
        })

    async def available_models(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        config = load_config()
        current = _current_model_settings(config)
        provider = (request.query.get("provider") or current["provider"] or "openrouter").strip()
        models = [
            {"id": model_id, "description": description}
            for model_id, description in curated_models_for_provider(provider)
        ]
        providers = list_available_providers()
        return web.json_response({"provider": provider, "models": models, "providers": providers})

    return {
        "get_config": get_config,
        "update_config": update_config,
        "available_models": available_models,
    }


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

def register_routes(app, adapter) -> None:
    """Bind every injected route to the live aiohttp router.

    Called from `_patch._maybe_register_routes()` after feature detection
    determines we're on a vanilla upstream server and the adapter has just
    finished its own setup. Routes are added directly to `app.router`, which
    aiohttp keeps mutable until `AppRunner.setup()` freezes it shortly after
    `connect()` returns.
    """
    upstream = _resolve_upstream()

    sessions = _make_sessions_handlers(adapter, upstream)
    memory = _make_memory_handlers(adapter, upstream)
    skills = _make_skills_handlers(adapter, upstream)
    config = _make_config_handlers(adapter, upstream)

    app.router.add_get("/api/sessions", sessions["list_sessions"])
    app.router.add_post("/api/sessions", sessions["create_session"])
    app.router.add_get("/api/sessions/search", sessions["search_sessions"])
    app.router.add_get("/api/sessions/{session_id}", sessions["get_session"])
    app.router.add_get("/api/sessions/{session_id}/messages", sessions["get_session_messages"])
    app.router.add_patch("/api/sessions/{session_id}", sessions["update_session"])
    app.router.add_delete("/api/sessions/{session_id}", sessions["delete_session"])
    app.router.add_post("/api/sessions/{session_id}/fork", sessions["fork_session"])

    app.router.add_get("/api/memory", memory["get_memory"])
    app.router.add_post("/api/memory", memory["add_memory"])
    app.router.add_patch("/api/memory", memory["replace_memory"])
    app.router.add_delete("/api/memory", memory["delete_memory"])

    app.router.add_get("/api/skills", skills["list_skills"])
    app.router.add_get("/api/skills/categories", skills["skill_categories"])
    app.router.add_get("/api/skills/{name}", skills["view_skill"])

    app.router.add_get("/api/config", config["get_config"])
    app.router.add_patch("/api/config", config["update_config"])
    app.router.add_get("/api/available-models", config["available_models"])
