"""Slash-command middleware for the bootstrap-patched API server.

Mirrors the upstream Stage 1 slash-command preprocessor from
``gateway/platforms/api_server_slash.py`` (commit ``73de7736`` in the
upstream PR prep tree).  Installed as an aiohttp middleware on vanilla
upstream hermes-agent installs so that ``/help``, ``/commands``,
``/profile``, ``/provider``, and stateful-decline notices work on
``/v1/chat/completions`` and ``/v1/runs`` without waiting for the
upstream PR to land.

The middleware is installed from ``_patch._maybe_register_routes()``
alongside the route injection, using the same ``__setitem__`` timing
window where the app is still mutable.  It feature-detects at install
time: if ``gateway.platforms.api_server_slash`` already exists (meaning
the upstream PR landed or the fork is deployed), the middleware is
skipped entirely.

Design principles:
- **Zero-cost fast path**: requests that don't target the two chat
  endpoints skip the body parse entirely.
- **Auth first**: ``adapter._check_auth(request)`` runs before any
  command logic, matching upstream's order.
- **Fail-open**: any exception in the middleware falls through to the
  original handler via ``return await handler(request)``.  A middleware
  bug must never break normal chat.
- **Body re-reading**: ``await request.json()`` caches internally in
  aiohttp, so the handler can call it again after a fall-through.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Dict, Optional

logger = logging.getLogger(__name__)

_INTERCEPTED_PATHS = frozenset({"/v1/chat/completions", "/v1/runs"})


# ---------------------------------------------------------------------------
# SlashCommandResult (mirrors upstream api_server_slash.SlashCommandResult)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class SlashCommandResult:
    """Result of a handled gateway slash command."""

    text: str
    command: str


# ---------------------------------------------------------------------------
# Stateless command handlers
# ---------------------------------------------------------------------------
# Each returns the textual body of a synthetic reply.  They import their
# dependencies lazily so the middleware stays cheap on code paths that never
# trigger a command.  Each is wrapped in try/except at the call site so a
# missing upstream symbol degrades to a simple fallback.


def _handle_help(_args: str) -> str:
    """Return the canonical gateway help listing."""
    from hermes_cli.commands import gateway_help_lines

    lines = ["**Hermes Commands**", ""]
    lines.extend(gateway_help_lines())
    lines.append("")
    lines.append(
        "Note: commands that mutate session state (for example `/model`, "
        "`/new`, `/retry`, `/yolo`) are not available on the stateless "
        "`/v1/runs` and `/v1/chat/completions` endpoints.  Use a channel "
        "with persistent session state (CLI, Discord, Telegram, Slack) "
        "for those."
    )
    return "\n".join(lines)


def _handle_commands(args: str) -> str:
    """Return the paginated ``/commands`` listing."""
    from hermes_cli.commands import gateway_help_lines

    entries = list(gateway_help_lines())
    if not entries:
        return "No commands available."

    raw = (args or "").strip()
    if raw:
        try:
            requested_page = int(raw)
        except ValueError:
            return "Usage: `/commands [page]`"
    else:
        requested_page = 1

    page_size = 20
    total_pages = max(1, (len(entries) + page_size - 1) // page_size)
    page = max(1, min(requested_page, total_pages))
    start = (page - 1) * page_size
    page_entries = entries[start:start + page_size]

    lines = [
        f"**Commands** ({len(entries)} total, page {page}/{total_pages})",
        "",
        *page_entries,
    ]
    if total_pages > 1:
        nav_parts = []
        if page > 1:
            nav_parts.append(f"`/commands {page - 1}` \u2190 prev")
        if page < total_pages:
            nav_parts.append(f"next \u2192 `/commands {page + 1}`")
        lines.extend(["", " | ".join(nav_parts)])
    if page != requested_page:
        lines.append(
            f"_(Requested page {requested_page} was out of range, "
            f"showing page {page}.)_"
        )
    return "\n".join(lines)


def _handle_profile(_args: str) -> str:
    """Return the active profile name and home directory."""
    from pathlib import Path

    from hermes_constants import display_hermes_home, get_hermes_home

    home = get_hermes_home()
    display = display_hermes_home()

    profiles_parent = Path.home() / ".hermes" / "profiles"
    try:
        rel = home.relative_to(profiles_parent)
        profile_name = str(rel).split("/")[0]
    except ValueError:
        profile_name = None

    if profile_name:
        return f"**Profile:** `{profile_name}`\n**Home:** `{display}`"
    return f"**Profile:** default\n**Home:** `{display}`"


def _handle_provider(_args: str) -> str:
    """Return the current provider plus the list of available providers."""
    import yaml

    from hermes_cli.models import (
        _PROVIDER_LABELS,
        list_available_providers,
        normalize_provider,
    )

    # Resolve current provider from config
    current_provider = "openrouter"
    model_cfg: dict = {}
    try:
        from hermes_constants import get_hermes_home

        config_path = get_hermes_home() / "config.yaml"
        if config_path.exists():
            with open(config_path, encoding="utf-8") as fh:
                cfg = yaml.safe_load(fh) or {}
            raw_model_cfg = cfg.get("model", {})
            if isinstance(raw_model_cfg, dict):
                model_cfg = raw_model_cfg
                current_provider = model_cfg.get("provider", current_provider)
    except Exception:
        pass

    current_provider = normalize_provider(current_provider)
    if current_provider == "auto":
        try:
            from hermes_cli.auth import resolve_provider as _resolve_provider

            current_provider = _resolve_provider(current_provider)
        except Exception:
            current_provider = "openrouter"

    # Detect custom endpoint from config base_url
    if current_provider == "openrouter":
        cfg_base = (
            model_cfg.get("base_url", "") if isinstance(model_cfg, dict) else ""
        )
        if cfg_base and "openrouter.ai" not in cfg_base:
            current_provider = "custom"

    current_label = _PROVIDER_LABELS.get(current_provider, current_provider)

    lines = [
        f"**Current provider:** {current_label} (`{current_provider}`)",
        "",
        "**Available providers:**",
    ]
    try:
        providers = list_available_providers()
    except Exception:
        providers = []
    for provider in providers:
        marker = " \u2190 active" if provider["id"] == current_provider else ""
        auth = (
            "authenticated" if provider["authenticated"] else "not authenticated"
        )
        aliases = (
            f"  _(also: {', '.join(provider['aliases'])})_"
            if provider["aliases"]
            else ""
        )
        lines.append(
            f"- `{provider['id']}` \u2014 {provider['label']} "
            f"({auth}){aliases}{marker}"
        )
    lines.append("")
    lines.append(
        "Note: provider switching via `/model` is not available on "
        "the stateless `/v1/runs` and `/v1/chat/completions` endpoints."
    )
    return "\n".join(lines)


_STATELESS_HANDLERS: Dict[str, Callable[[str], str]] = {
    "help": _handle_help,
    "commands": _handle_commands,
    "profile": _handle_profile,
    "provider": _handle_provider,
}


# ---------------------------------------------------------------------------
# Stateful-command decline notice
# ---------------------------------------------------------------------------


def _stateful_decline_notice(user_token: str, canonical_name: str) -> str:
    """Build the decline notice for a stateful command."""
    displayed = user_token or canonical_name
    return (
        f"The `/{displayed}` command requires a persistent session and "
        f"isn't available on the stateless `/v1/runs` and "
        f"`/v1/chat/completions` endpoints.  Use a channel with session "
        f"state (CLI, Discord, Telegram, Slack) for commands that mutate "
        f"per-session configuration.\n\n"
        f"For the commands that *are* supported here, type `/help`."
    )


# ---------------------------------------------------------------------------
# Core command resolution (mirrors api_server_slash.maybe_handle_gateway_command)
# ---------------------------------------------------------------------------


def _maybe_handle_command(user_message: str) -> Optional[SlashCommandResult]:
    """Check *user_message* for a leading ``/`` and resolve against the
    command registry.

    Returns a :class:`SlashCommandResult` if the message is a recognized
    gateway command.  Returns ``None`` for non-slash text, unknown
    commands, and strictly CLI-only commands — the caller then falls
    through to the normal LLM handler.
    """
    try:
        if not user_message:
            return None
        stripped = user_message.lstrip()
        if not stripped.startswith("/"):
            return None

        parts = stripped.split(None, 1)
        first_token = parts[0][1:]  # drop the leading slash
        args = parts[1] if len(parts) > 1 else ""

        if not first_token:
            return None

        from hermes_cli.commands import resolve_command

        cmd = resolve_command(first_token)
        if cmd is None:
            return None

        # Strictly CLI-only commands are never reachable via the gateway.
        # Config-gated commands (cli_only=True + gateway_config_gate) are
        # still gateway-reachable, so let them through to the decline path.
        if cmd.cli_only and not cmd.gateway_config_gate:
            return None

        handler = _STATELESS_HANDLERS.get(cmd.name)
        if handler is not None:
            try:
                return SlashCommandResult(text=handler(args), command=cmd.name)
            except Exception as exc:
                logger.warning(
                    "hermes_relay_bootstrap: stateless handler for /%s "
                    "raised %s — returning fallback",
                    cmd.name,
                    exc,
                )
                return SlashCommandResult(
                    text=f"The `/{cmd.name}` command is available but "
                    f"encountered an error: {exc}",
                    command=cmd.name,
                )

        return SlashCommandResult(
            text=_stateful_decline_notice(first_token, cmd.name),
            command=cmd.name,
        )
    except Exception as exc:
        logger.warning(
            "hermes_relay_bootstrap: command preprocessor failed for "
            "%r — falling through to LLM path: %s",
            user_message[:80] if user_message else "",
            exc,
        )
        return None


# ---------------------------------------------------------------------------
# User-message extraction helpers
# ---------------------------------------------------------------------------


def _extract_user_message_completions(body: Dict[str, Any]) -> Optional[str]:
    """Extract the last user message from a ``/v1/chat/completions`` body."""
    messages = body.get("messages")
    if not isinstance(messages, list) or not messages:
        return None
    # Walk backwards to find the last user message.
    for msg in reversed(messages):
        if isinstance(msg, dict) and msg.get("role") == "user":
            content = msg.get("content", "")
            if isinstance(content, str):
                return content
    return None


def _extract_user_message_runs(body: Dict[str, Any]) -> Optional[str]:
    """Extract the user message from a ``/v1/runs`` body."""
    raw_input = body.get("input")
    if isinstance(raw_input, str):
        return raw_input
    if isinstance(raw_input, list) and raw_input:
        last = raw_input[-1]
        if isinstance(last, dict):
            content = last.get("content", "")
            if isinstance(content, str):
                return content
    return None


# ---------------------------------------------------------------------------
# Synthetic response builders
# ---------------------------------------------------------------------------


def _build_chat_completion_json(
    text: str,
    model: str,
    completion_id: str,
    created: int,
) -> Dict[str, Any]:
    """Build a non-streaming ``chat.completion`` response."""
    return {
        "id": completion_id,
        "object": "chat.completion",
        "created": created,
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": text,
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        },
    }


async def _write_sse_chat_completion(
    request: Any,
    *,
    text: str,
    model: str,
    completion_id: str,
    created: int,
) -> Any:
    """Write a synthetic SSE stream for ``/v1/chat/completions``.

    Emits the same three-chunk shape as upstream's
    ``_write_sse_slash_command``: role chunk, content chunk, finish
    chunk, ``[DONE]`` terminator.
    """
    from aiohttp import web

    sse_headers: dict[str, str] = {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",
    }
    # CORS: StreamResponse headers must be set before prepare() because
    # they're sent with the initial 200 response.  Web clients (Open WebUI
    # on a different port, etc.) need these.
    origin = request.headers.get("Origin", "")
    if origin:
        sse_headers["Access-Control-Allow-Origin"] = origin
        sse_headers["Access-Control-Allow-Credentials"] = "true"

    response = web.StreamResponse(status=200, headers=sse_headers)
    await response.prepare(request)

    try:
        role_chunk = {
            "id": completion_id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [
                {"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}
            ],
        }
        await response.write(f"data: {json.dumps(role_chunk)}\n\n".encode())

        content_chunk = {
            "id": completion_id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [
                {"index": 0, "delta": {"content": text}, "finish_reason": None}
            ],
        }
        await response.write(f"data: {json.dumps(content_chunk)}\n\n".encode())

        finish_chunk = {
            "id": completion_id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            "usage": {
                "prompt_tokens": 0,
                "completion_tokens": 0,
                "total_tokens": 0,
            },
        }
        await response.write(f"data: {json.dumps(finish_chunk)}\n\n".encode())
        await response.write(b"data: [DONE]\n\n")
    except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
        logger.info(
            "SSE client disconnected during slash-command reply %s",
            completion_id,
        )

    return response


def _inject_run_events(
    adapter: Any,
    run_id: str,
    text: str,
) -> bool:
    """Inject ``message.delta`` + ``run.completed`` + sentinel into the
    adapter's run event queue.

    Returns ``True`` if successful, ``False`` if the adapter's internals
    don't match what we expect (in which case the caller falls through to
    the original handler).
    """
    try:
        q: asyncio.Queue[Optional[Dict]] = asyncio.Queue()
        ts = time.time()
        q.put_nowait({
            "event": "message.delta",
            "run_id": run_id,
            "timestamp": ts,
            "delta": text,
        })
        q.put_nowait({
            "event": "run.completed",
            "run_id": run_id,
            "timestamp": ts,
            "output": text,
            "usage": {
                "input_tokens": 0,
                "output_tokens": 0,
                "total_tokens": 0,
            },
        })
        q.put_nowait(None)  # sentinel

        adapter._run_streams[run_id] = q
        adapter._run_streams_created[run_id] = ts
        return True
    except (AttributeError, TypeError) as exc:
        logger.warning(
            "hermes_relay_bootstrap: cannot inject run events into adapter "
            "internals — _run_streams access failed: %s",
            exc,
        )
        return False


# ---------------------------------------------------------------------------
# Middleware factory
# ---------------------------------------------------------------------------


def make_command_middleware(adapter: Any) -> Any:
    """Build the aiohttp middleware function for slash-command interception.

    *adapter* is the ``APIServerAdapter`` instance, captured by the closure
    so we can call ``_check_auth()`` and inject run events without repeated
    ``request.app`` lookups.
    """
    from aiohttp import web

    @web.middleware
    async def command_middleware(
        request: web.Request,
        handler: Callable,
    ) -> web.StreamResponse:
        # Fast path: skip non-chat endpoints entirely.
        if request.path not in _INTERCEPTED_PATHS:
            return await handler(request)

        # Only intercept POST.
        if request.method != "POST":
            return await handler(request)

        try:
            # Auth check — must run before any command logic, matching
            # upstream's order.
            auth_err = adapter._check_auth(request)
            if auth_err:
                return auth_err

            # Parse the request body.
            try:
                body = await request.json()
            except Exception:
                # Let the handler deal with malformed JSON.
                return await handler(request)

            # Extract user message based on endpoint.
            if request.path == "/v1/chat/completions":
                user_message = _extract_user_message_completions(body)
            else:
                user_message = _extract_user_message_runs(body)

            if not user_message:
                return await handler(request)

            # Resolve against the command registry.
            result = _maybe_handle_command(user_message)
            if result is None:
                return await handler(request)

            # --- Command matched: build synthetic response ---

            if request.path == "/v1/chat/completions":
                model_name = body.get("model") or "hermes-agent"
                completion_id = f"chatcmpl-{uuid.uuid4().hex[:29]}"
                created = int(time.time())
                stream = body.get("stream", False)

                logger.info(
                    "[bootstrap] intercepted gateway command /%s on "
                    "/v1/chat/completions (stream=%s)",
                    result.command,
                    bool(stream),
                )

                if stream:
                    return await _write_sse_chat_completion(
                        request,
                        text=result.text,
                        model=model_name,
                        completion_id=completion_id,
                        created=created,
                    )
                return web.json_response(
                    _build_chat_completion_json(
                        text=result.text,
                        model=model_name,
                        completion_id=completion_id,
                        created=created,
                    ),
                )

            else:
                # /v1/runs — inject events into the adapter's queue.
                run_id = f"run_{uuid.uuid4().hex}"

                logger.info(
                    "[bootstrap] intercepted gateway command /%s on /v1/runs",
                    result.command,
                )

                if _inject_run_events(adapter, run_id, result.text):
                    return web.json_response(
                        {"run_id": run_id, "status": "started"},
                        status=202,
                    )
                # Injection failed — fall through to the handler.
                return await handler(request)

        except Exception as exc:
            # Fail-open: any unexpected error falls through to the
            # original handler.  A middleware bug must never break chat.
            logger.warning(
                "hermes_relay_bootstrap: command middleware failed for "
                "%s %s — falling through: %s",
                request.method,
                request.path,
                exc,
            )
            return await handler(request)

    return command_middleware


# ---------------------------------------------------------------------------
# Installation helper
# ---------------------------------------------------------------------------


def maybe_install_middleware(app: Any, adapter: Any) -> bool:
    """Install the command middleware on *app* if the upstream PR hasn't
    landed yet.

    Returns ``True`` if the middleware was installed, ``False`` if it was
    skipped (because the upstream preprocessor is already present).
    """
    # Feature-detect: if the upstream module exists, the native handler
    # already intercepts commands — no middleware needed.
    try:
        import gateway.platforms.api_server_slash  # noqa: F401

        logger.info(
            "hermes_relay_bootstrap: gateway.platforms.api_server_slash "
            "exists — upstream slash-command preprocessor is active; "
            "skipping middleware installation."
        )
        return False
    except ImportError:
        pass

    try:
        middleware = make_command_middleware(adapter)
    except Exception as exc:
        logger.warning(
            "hermes_relay_bootstrap: failed to create command middleware: %s",
            exc,
        )
        return False

    # Insert the middleware.  aiohttp stores middlewares as a FrozenList on
    # app._middlewares; it's still mutable here because AppRunner.setup()
    # (which calls .freeze()) hasn't run yet.  Append in place so the
    # container type is preserved for the later freeze() call — replacing
    # it with a tuple causes `'tuple' object has no attribute 'freeze'`.
    try:
        app._middlewares.append(middleware)
    except Exception as exc:
        logger.warning(
            "hermes_relay_bootstrap: failed to install command middleware "
            "on app._middlewares: %s",
            exc,
        )
        return False

    logger.info(
        "hermes_relay_bootstrap: installed slash-command middleware for "
        "/v1/chat/completions and /v1/runs"
    )
    return True
