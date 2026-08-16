"""Compatibility-only handlers for surfaces upstream does not serve natively.

This module began as a mirror of the management endpoints from the pre-upstream
Hermes-Relay fork branch. Upstream hermes-agent has since absorbed the major
surfaces natively, and the bootstrap retires per surface as that happens. The
split as of the sessions/skills retirement (HRUI-002):

RETIRED — native upstream owns these; the bootstrap no longer injects them,
not even as a fallback for pre-#33134 core builds (older builds degrade to
`/v1/chat/completions` / `/v1/runs` via the client's capability probe):

  GET/POST /api/sessions, GET/PATCH/DELETE /api/sessions/{id},
  GET /api/sessions/{id}/messages, POST /api/sessions/{id}/fork
      — native session control API, PR #33134
  POST /api/sessions/{id}/chat + /chat/stream
      — native via PR #33134 (never injected here; see note below)
  GET /api/skills (legacy read-only list)
      — superseded by native `/v1/skills` + `/v1/toolsets`, PR #33016

STILL INJECTED — genuine compatibility gaps with no native API-server
replacement yet (all bearer-auth gated via `adapter._check_auth`):

  GET    /api/sessions/search?q=...             — full-text message search
  GET    /api/memory                            — read memory state
  POST   /api/memory                            — append memory entry
  PATCH  /api/memory                            — replace memory entry
  DELETE /api/memory                            — remove memory entry
  GET    /api/skills/{name}                     — fetch skill body (legacy detail)
  PUT    /api/skills/toggle                     — STUB (501 Not Implemented).
                                                  Registered so the Android client's
                                                  capability probe observes the route
                                                  and renders the UI as disabled
                                                  instead of missing. See
                                                  ``toggle_skill`` docstring for the
                                                  upstream gap explanation.
  GET    /api/config                            — read model + config
  PATCH  /api/config                            — update model/provider/base_url
  GET    /api/available-models                  — provider model list

Handlers take the `APIServerAdapter` instance as an explicit parameter rather
than relying on `self`. That keeps the patch loosely coupled to upstream's
class shape — we don't bind methods onto the adapter, just register closures
that capture an `adapter` reference.

Notes on surfaces that were never injected:

- `POST /api/sessions/{session_id}/chat/stream` — even before the sessions
  retirement, the bootstrap never injected a chat-stream handler because that
  path requires coordinating with `_create_agent` / `run_conversation` — the
  fork's riskiest cross-cutting dependencies. Clients fall back to
  `/v1/chat/completions` or `/v1/runs` when chat streaming is not advertised.

- `GET /api/skills/categories` — removed from upstream as dead code in commit
  8d023e43 ("refactor: remove dead code — 1,784 lines across 77 files"). The
  app does not call this endpoint. Re-injecting it would require importing a
  symbol that no longer exists.

Removal note: registration stays method/path-aware (`_add_route_if_missing`)
so native upstream routes always win if any of the remaining paths ever land
in core. Each remaining surface retires individually when core exposes a
stable equivalent or Hermes-Relay stops depending on it: config, memory,
legacy skill detail/toggle, available-models, and session search.
"""

from __future__ import annotations

import asyncio
import json
import logging
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from typing import Any, Dict

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
    try:
        from hermes_state import AsyncSessionDB
    except ImportError:
        # AsyncSessionDB was added after the compatibility bootstrap. Older
        # supported Hermes installs still get event-loop-safe access through
        # asyncio.to_thread() in _call_session_db().
        AsyncSessionDB = None  # type: ignore[assignment]
    from hermes_cli.config import load_config, save_config
    from hermes_cli.models import (
        curated_models_for_provider,
        list_available_providers,
    )
    # Only the legacy skill *detail* view remains bootstrap territory; the
    # read-only list surface retired in favor of native /v1/skills (#33016).
    from tools.skills_tool import skill_view

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
        "AsyncSessionDB": AsyncSessionDB,
        "MemoryStore": MemoryStore,
        "load_config": load_config,
        "save_config": save_config,
        "curated_models_for_provider": curated_models_for_provider,
        "list_available_providers": list_available_providers,
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


async def _get_session_db(adapter, upstream):
    """Return the cached SessionDB without constructing it on the event loop."""
    state = _state_for(adapter)
    db = state.get("session_db")
    if db is not None:
        return db

    # Cache the in-flight task as well as the completed instance. Two first
    # requests can arrive before SQLite schema/FTS initialization completes;
    # they must share one constructor rather than opening competing stores.
    init_task = state.get("session_db_init_task")
    if init_task is None:
        init_task = asyncio.create_task(asyncio.to_thread(upstream["SessionDB"]))
        state["session_db_init_task"] = init_task

    try:
        # A disconnected HTTP client must not cancel the shared constructor
        # while another first request is waiting for the same database.
        db = await asyncio.shield(init_task)
    except asyncio.CancelledError:
        raise
    except Exception:
        if state.get("session_db_init_task") is init_task:
            state.pop("session_db_init_task", None)
        raise

    state["session_db"] = db
    if state.get("session_db_init_task") is init_task:
        state.pop("session_db_init_task", None)
    return db


async def _call_session_db(adapter, upstream, method: str, *args, **kwargs):
    """Call a SessionDB method without blocking aiohttp's event loop.

    Newer Hermes versions provide AsyncSessionDB as the canonical async door.
    The explicit to_thread fallback keeps the same behavior on older upstream
    versions instead of making the compatibility hook depend on a new symbol.
    """
    state = _state_for(adapter)
    async_db_cls = upstream.get("AsyncSessionDB")
    if async_db_cls is not None:
        db = state.get("async_session_db")
        if db is None:
            db = async_db_cls(await _get_session_db(adapter, upstream))
            state["async_session_db"] = db
        return await getattr(db, method)(*args, **kwargs)

    db = await _get_session_db(adapter, upstream)
    return await asyncio.to_thread(getattr(db, method), *args, **kwargs)


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


def _normalized_route_url(value: Any) -> str:
    raw = str(value or "").strip()
    if not raw:
        return ""
    try:
        parsed = urlsplit(raw)
    except ValueError:
        return raw.rstrip("/")
    scheme = parsed.scheme.lower()
    host = (parsed.hostname or "").lower()
    if not scheme or not host:
        return raw.rstrip("/")
    port = parsed.port
    netloc = host
    if port is not None and not (
        (scheme == "http" and port == 80) or
        (scheme == "https" and port == 443)
    ):
        netloc = f"{host}:{port}"
    path = parsed.path.rstrip("/")
    query = urlencode(sorted(parse_qsl(parsed.query, keep_blank_values=True)))
    return urlunsplit((scheme, netloc, path, query, ""))


def _model_route_identity(model_cfg: Dict[str, Any]) -> tuple[str, str, str]:
    return (
        str(model_cfg.get("default") or model_cfg.get("model") or "").strip(),
        str(model_cfg.get("provider") or "").strip().lower(),
        _normalized_route_url(model_cfg.get("base_url")),
    )


def _maybe_clear_context_pin(
    original_model_cfg: Dict[str, Any],
    updated_model_cfg: Dict[str, Any],
) -> None:
    """Drop stale route-owned context pins when the configured route changes."""
    if "context_length" not in updated_model_cfg:
        return
    if _model_route_identity(original_model_cfg) != _model_route_identity(updated_model_cfg):
        updated_model_cfg.pop("context_length", None)


def _parse_int(value: Any, default: int, minimum: int = 0) -> int:
    """Parse an integer query parameter with bounds."""
    if value in (None, ""):
        return default
    parsed = int(value)
    if parsed < minimum:
        raise ValueError(f"Value must be >= {minimum}")
    return parsed


# ---------------------------------------------------------------------------
# Session search handler
# ---------------------------------------------------------------------------
#
# The only surviving `/api/sessions*` surface. Sessions CRUD, messages, and
# fork retired with native upstream PR #33134; full-text message search has
# no native API-server equivalent, so it stays a compatibility injection.

def _make_session_search_handlers(adapter, upstream):
    web = upstream["web"]

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

        results = await _call_session_db(
            adapter,
            upstream,
            "search_messages",
            query=query,
            limit=limit,
            offset=offset,
        )
        return web.json_response({"query": query, "count": len(results), "results": results})

    return {
        "search_sessions": search_sessions,
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

    def _reset_request_failure_budget(store) -> None:
        """Treat each REST mutation as its own upstream memory turn."""
        reset = getattr(store, "reset_consolidation_failures", None)
        if callable(reset):
            reset()

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
        _reset_request_failure_budget(store)
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
        _reset_request_failure_budget(store)
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
        _reset_request_failure_budget(store)
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
# Skills handlers (legacy detail + toggle stub only)
# ---------------------------------------------------------------------------
#
# The legacy read-only list (`GET /api/skills`) retired in favor of native
# `/v1/skills` + `/v1/toolsets` (PR #33016). The per-skill detail view and
# the 501 toggle stub remain: neither has a native API-server equivalent.

def _make_skills_handlers(adapter, upstream):
    web = upstream["web"]
    skill_view = upstream["skill_view"]

    async def view_skill(request):
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        name = request.match_info["name"]
        file_path = (request.query.get("file_path") or "").strip() or None
        return web.json_response(json.loads(skill_view(name, file_path=file_path)))

    async def toggle_skill(request):
        """PUT /api/skills/toggle — stubbed 501.

        Body: ``{"skill": "<name>", "enabled": true|false}``
        Response (always 501):
          ``{"error": "skill_toggle_not_implemented",
             "detail": "Skill enable/disable requires upstream
                        hermes_cli.web_server API — proxy not yet available"}``

        Rationale: ``tools.skills_tool`` (the symbol we already import for
        ``list_skills``/``view_skill``) has no clean enable/disable hook.
        Upstream's dashboard implements the toggle in
        ``hermes_cli.web_server`` — a separate, loopback-only web server —
        which we don't proxy through the relay today. Surfacing the
        endpoint with a clear 501 keeps the Kotlin client's capability
        probe honest: it can observe the route exists, parse the
        structured error, and hide the toggle UI until a real backend
        lands. Returning 404 would make the probe confuse "server
        doesn't know about toggles" (upstream gap) with "wrong URL"
        (client bug).
        """
        auth_err = adapter._check_auth(request)
        if auth_err:
            return auth_err
        # We still accept + ignore the body so misconfigured clients get
        # a stable shape — not a JSON-decode error on top of the 501.
        try:
            await request.json()
        except Exception:
            pass
        return web.json_response(
            {
                "error": "skill_toggle_not_implemented",
                "detail": (
                    "Skill enable/disable requires upstream "
                    "hermes_cli.web_server API — proxy not yet available"
                ),
            },
            status=501,
        )

    return {
        "view_skill": view_skill,
        "toggle_skill": toggle_skill,
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
            original_model_cfg = dict(model_cfg)
        elif isinstance(model_cfg, str) and model_cfg.strip():
            updated_model_cfg = {"default": model_cfg.strip()}
            original_model_cfg = {"default": model_cfg.strip()}
        else:
            updated_model_cfg = {}
            original_model_cfg = {}

        if "model" in body:
            updated_model_cfg["default"] = str(body.get("model") or "").strip()
        if "provider" in body:
            updated_model_cfg["provider"] = str(body.get("provider") or "").strip()
        if "base_url" in body:
            updated_model_cfg["base_url"] = str(body.get("base_url") or "").strip()
        _maybe_clear_context_pin(original_model_cfg, updated_model_cfg)

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

def _route_exists(app, method: str, path: str) -> bool:
    """Return true when *method path* is already registered on the app."""
    wanted_method = method.upper()
    try:
        resources = list(app.router.resources())
    except Exception:
        # If introspection fails, report the route as present. That prevents
        # duplicate registration from crashing gateway startup.
        logger.warning(
            "hermes_relay_bootstrap: cannot inspect routes while checking "
            "%s %s; skipping that compatibility route.",
            wanted_method,
            path,
        )
        return True

    for resource in resources:
        if getattr(resource, "canonical", None) != path:
            continue
        try:
            routes = list(resource)
        except TypeError:
            routes = []
        for route in routes:
            route_method = str(getattr(route, "method", "")).upper()
            if route_method in {wanted_method, "*"}:
                return True
    return False


def _add_route_if_missing(app, method: str, path: str, handler) -> bool:
    """Register a compatibility route only when upstream has not done so."""
    method = method.upper()
    if _route_exists(app, method, path):
        logger.debug(
            "hermes_relay_bootstrap: native route exists; skipping %s %s",
            method,
            path,
        )
        return False
    if method == "GET":
        # Preserve aiohttp's add_get default behavior: HEAD should work for
        # capability probes even when this route is bootstrap-provided.
        app.router.add_get(path, handler)
    elif method == "POST":
        app.router.add_post(path, handler)
    elif method == "PATCH":
        app.router.add_patch(path, handler)
    elif method == "DELETE":
        app.router.add_delete(path, handler)
    elif method == "PUT":
        app.router.add_put(path, handler)
    else:
        app.router.add_route(method, path, handler)
    return True


def register_routes(app, adapter) -> int:
    """Bind missing compatibility routes to the live aiohttp router.

    Called from `_patch._maybe_register_routes()` after the adapter has just
    finished its own setup. Routes are added directly to `app.router`, which
    aiohttp keeps mutable until `AppRunner.setup()` freezes it shortly after
    `connect()` returns. Native upstream routes win per method/path.

    Only compatibility-only surfaces are registered here. Sessions CRUD,
    messages, and fork (native via PR #33134) and the legacy read-only skill
    list (native `/v1/skills` via PR #33016) are retired — see the module
    docstring for the full split.

    Returns the number of compatibility routes actually added.
    """
    upstream = _resolve_upstream()

    search = _make_session_search_handlers(adapter, upstream)
    memory = _make_memory_handlers(adapter, upstream)
    skills = _make_skills_handlers(adapter, upstream)
    config = _make_config_handlers(adapter, upstream)

    routes = [
        # Full-text message search — no native API-server equivalent.
        ("GET", "/api/sessions/search", search["search_sessions"]),
        # Memory CRUD — no native API-server equivalent.
        ("GET", "/api/memory", memory["get_memory"]),
        ("POST", "/api/memory", memory["add_memory"]),
        ("PATCH", "/api/memory", memory["replace_memory"]),
        ("DELETE", "/api/memory", memory["delete_memory"]),
        # Legacy skill detail — native /v1/skills is list-only.
        ("GET", "/api/skills/{name}", skills["view_skill"]),
    ]
    # Stubbed 501 — see `toggle_skill` docstring. Registered so the
    # Kotlin client's capability probe observes the route and renders a
    # disabled toggle rather than hitting a 404 and showing "unknown
    # feature."
    routes.append(("PUT", "/api/skills/toggle", skills["toggle_skill"]))

    routes.extend(
        [
            # Model/config + provider model list — dashboard web_server has
            # equivalents, but the API server does not.
            ("GET", "/api/config", config["get_config"]),
            ("PATCH", "/api/config", config["update_config"]),
            ("GET", "/api/available-models", config["available_models"]),
        ]
    )

    injected = 0
    for method, path, handler in routes:
        if _add_route_if_missing(app, method, path, handler):
            injected += 1
    return injected
