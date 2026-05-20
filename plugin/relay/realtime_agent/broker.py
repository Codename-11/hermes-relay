"""Experimental realtime voice agent route handler.

This surface is intentionally separate from ``/voice/realtime/*``. The old
route remains a provider lab; this route binds a realtime provider renderer to
the Hermes session/tool loop.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import secrets
import struct
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web

from plugin.voice_lab.providers.base import ProviderRunError, ProviderUnavailable
from plugin.voice_lab.registry import default_registry

from ..config import (
    RelayConfig,
    default_realtime_voice_config_path,
    save_realtime_voice_config_file,
)
from ..profile_voice import (
    realtime_voice_settings,
    request_profile,
    save_profile_voice_section,
)
from ..provider_options import (
    PROVIDER_OPTIONS_SCHEMA_VERSION,
    XAIOptionAuth,
    fetch_realtime_provider_options,
    merge_provider_options,
    validate_provider_selection,
)
from ..realtime_voice import _read_relay_xai_oauth_token, _websocket_url_from_base
from ..voice_auth import AuthPrincipal, require_voice_auth
from .hermes_tool_broker import HermesTaskRequest, HermesToolBroker
from .providers import adapter_for
from .providers.base import RealtimeAgentRenderConfig

DEFAULT_SAMPLE_RATE = 24000
DEFAULT_CHANNELS = 1
DEFAULT_SAMPLE_WIDTH = 2
_TRUE_ENV_VALUES = {"1", "true", "yes", "on"}
_TOOL_SURFACE = ("hermes_run_task", "hermes_get_status", "hermes_cancel", "hermes_confirm")


@dataclass(slots=True)
class RealtimeAgentSession:
    session_id: str
    provider: str
    model: str
    voice: str
    sample_rate: int
    profile: str | None
    chat_session_id: str | None
    config_scope: str
    config_path: Path | None
    auth_kind: str
    bearer_token: str | None
    created_at: float
    event_log_path: Path


class RealtimeAgentHandler:
    """Relay-owned realtime-agent broker preserving Hermes as authority."""

    def __init__(self, config: RelayConfig) -> None:
        self.config = config
        self.registry = default_registry()
        self.sessions: dict[str, RealtimeAgentSession] = {}
        self.hermes = HermesToolBroker(config.webapi_url)

    async def handle_config(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:realtime")
        profile = request_profile(None, request.query)
        return web.json_response(self.config_payload(profile))

    async def handle_update_config(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:realtime")
        payload = await _optional_json(request)
        profile = request_profile(payload, request.query)
        payload.pop("profile", None)
        updates = self._validate_config_updates(payload)
        config_path = save_profile_voice_section(
            self.config,
            profile,
            "realtime_voice",
            updates,
        )
        if config_path is None and profile:
            raise web.HTTPNotFound(text=f"profile not found or not writable: {profile}")
        if config_path is None:
            config_path = save_realtime_voice_config_file(self.config, updates)
        body = self.config_payload(profile)
        body["updated"] = sorted(updates)
        body["config_path"] = str(config_path)
        return web.json_response(body)

    async def handle_provider_options(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:realtime")
        provider_id = str(request.match_info.get("provider_id", "")).strip()
        profile = request_profile(None, request.query)
        return web.json_response(await self.provider_options_payload(provider_id, profile))

    async def handle_provider_validate(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:realtime")
        provider_id = str(request.match_info.get("provider_id", "")).strip()
        payload = await _optional_json(request)
        profile = request_profile(payload, request.query)
        settings = realtime_voice_settings(self.config, profile)
        options = await self.provider_options_payload(provider_id, profile)
        model = _str_option(payload, "model") or str(settings["model"])
        voice = _str_option(payload, "voice") or str(settings["voice"])
        sample_rate = _int_option(
            payload,
            "sample_rate",
            int(settings["sample_rate"] or DEFAULT_SAMPLE_RATE),
        )
        validation = validate_provider_selection(
            options["provider"],
            model=model,
            voice=voice,
            sample_rate=sample_rate,
        )
        return web.json_response(
            {
                "success": True,
                "mode": "realtime_agent",
                "protocol": "hermes.voice.realtime_agent.validate.v0",
                "provider_id": provider_id,
                "model": model,
                "voice": voice,
                "sample_rate": sample_rate,
                "dynamic": options["dynamic"],
                **validation,
            }
        )

    async def handle_create_session(self, request: web.Request) -> web.StreamResponse:
        principal = await require_voice_auth(request, "voice:realtime")
        bearer_token = _bearer_from_request(request)
        payload = await _optional_json(request)
        profile = request_profile(payload, request.query)
        settings = realtime_voice_settings(self.config, profile)
        if not bool(settings["enabled"]):
            raise web.HTTPNotFound(text="realtime agent voice is disabled")

        provider = _str_option(payload, "provider") or str(settings["provider"])
        model = _str_option(payload, "model") or str(settings["model"])
        voice = _str_option(payload, "voice") or str(settings["voice"])
        sample_rate = _int_option(
            payload,
            "sample_rate",
            int(settings["sample_rate"] or DEFAULT_SAMPLE_RATE),
        )
        self._validate_provider(provider)

        chat_session_id = _str_option(payload, "chat_session_id")
        session_id = secrets.token_urlsafe(18)
        event_log_path = self._new_event_log_path(session_id)
        session = RealtimeAgentSession(
            session_id=session_id,
            provider=provider,
            model=model,
            voice=voice,
            sample_rate=sample_rate,
            profile=settings.get("profile"),
            chat_session_id=chat_session_id,
            config_scope=str(settings["config_scope"]),
            config_path=settings.get("config_path"),
            auth_kind=principal.kind,
            bearer_token=bearer_token if principal.kind == "hermes_api" else None,
            created_at=time.time(),
            event_log_path=event_log_path,
        )
        self.sessions[session_id] = session
        self._log(session, "voice.realtime_agent.session.created")

        return web.json_response(
            {
                "success": True,
                "session_id": session_id,
                "websocket_path": f"/voice/realtime-agent/{session_id}",
                "provider": provider,
                "model": model,
                "voice": voice,
                "sample_rate": sample_rate,
                "event_log_path": str(event_log_path),
                "protocol": "hermes.voice.realtime_agent.v0",
                "profile": session.profile,
                "chat_session_id": session.chat_session_id,
                "config_scope": session.config_scope,
                "config_path": str(session.config_path) if session.config_path else None,
                "fallback_to_global": bool(settings["fallback_to_global"]),
                "experimental": True,
                "tool_surface": list(_TOOL_SURFACE),
            }
        )

    async def handle_ws(self, request: web.Request) -> web.StreamResponse:
        if not self.enabled:
            raise web.HTTPNotFound(text="realtime agent voice is disabled")
        await require_voice_auth(request, "voice:realtime")

        session_id = request.match_info.get("session_id", "")
        session = self.sessions.get(session_id)
        if session is None:
            raise web.HTTPNotFound(text="unknown realtime agent session")

        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=2 * 1024 * 1024)
        await ws.prepare(request)
        await self._send(ws, session, self._ready_event(session))

        input_audio_bytes = 0
        input_sample_rate = DEFAULT_SAMPLE_RATE
        running: asyncio.Task[None] | None = None
        try:
            async for msg in ws:
                if msg.type == WSMsgType.ERROR:
                    break
                if msg.type != WSMsgType.TEXT:
                    await self._send_error(ws, session, "unsupported websocket frame")
                    continue
                try:
                    payload = json.loads(msg.data)
                except json.JSONDecodeError:
                    await self._send_error(ws, session, "invalid JSON message")
                    continue
                if not isinstance(payload, dict):
                    await self._send_error(ws, session, "message must be a JSON object")
                    continue

                msg_type = str(payload.get("type", "")).strip()
                if msg_type == "session.start":
                    await self._send(ws, session, self._ready_event(session))
                elif msg_type == "input_audio.append":
                    byte_count, input_sample_rate = await self._handle_input_audio(
                        ws,
                        session,
                        payload,
                        input_audio_bytes,
                    )
                    input_audio_bytes += byte_count
                elif msg_type in {"input_audio.commit", "response.create"}:
                    if running is not None and not running.done():
                        await self._send_error(ws, session, "response already running")
                        continue
                    running = asyncio.create_task(
                        self._run_agent(
                            ws,
                            session,
                            payload,
                            input_audio_bytes,
                            input_sample_rate,
                        )
                    )
                elif msg_type == "response.cancel":
                    if running is not None and not running.done():
                        running.cancel()
                    await self._send(
                        ws,
                        session,
                        {"type": "hermes.run.cancelled", "session_id": session.chat_session_id},
                    )
                elif msg_type == "hermes.confirm":
                    await self._send(
                        ws,
                        session,
                        {
                            "type": "hermes.confirmation.forwarded",
                            "confirmation_id": _str_option(payload, "confirmation_id"),
                            "answer": _str_option(payload, "answer"),
                        },
                    )
                elif msg_type == "session.close":
                    await ws.close()
                else:
                    await self._send_error(ws, session, f"unsupported message type: {msg_type}")
        finally:
            if running is not None and not running.done():
                running.cancel()
            self._log(session, "voice.realtime_agent.session.closed")
        return ws

    @property
    def enabled(self) -> bool:
        if self.config.realtime_voice_enabled:
            return True
        return os.getenv("RELAY_REALTIME_VOICE_ENABLED", "").strip().lower() in _TRUE_ENV_VALUES

    def config_payload(self, profile: str | None = None) -> dict[str, Any]:
        settings = realtime_voice_settings(self.config, profile)
        providers = [info.to_dict() for info in self.registry.provider_infos()]
        return {
            "success": True,
            "enabled": bool(settings["enabled"]),
            "available": bool(settings["enabled"]),
            "experimental": True,
            "mode": "realtime_agent",
            "stable_engine": "hermes_voice_output",
            "protocol": "hermes.voice.realtime_agent.v0",
            "config_path": str(settings["config_path"] or _config_path_for_payload(self.config)),
            "default_provider": settings["provider"],
            "default_model": settings["model"],
            "default_voice": settings["voice"],
            "sample_rate": settings["sample_rate"] or DEFAULT_SAMPLE_RATE,
            "providers": providers,
            "auth": _xai_auth_status(self.config),
            "profile": settings["profile"],
            "config_scope": settings["config_scope"],
            "fallback_to_global": settings["fallback_to_global"],
            "tool_surface": list(_TOOL_SURFACE),
            "limits": [
                "Hermes owns tools, confirmations, memory, and transcript state.",
                "Provider receives only broker-approved response text and audio rendering context.",
                "If provider audio fails, switch Voice engine back to Hermes chat + voice output.",
            ],
        }

    async def provider_options_payload(
        self,
        provider_id: str,
        profile: str | None = None,
    ) -> dict[str, Any]:
        info = self._validate_realtime_provider(provider_id)
        settings = realtime_voice_settings(self.config, profile)
        provider = info.to_dict()
        dynamic = await asyncio.to_thread(
            fetch_realtime_provider_options,
            provider_id,
            xai_auth=_xai_option_auth(self.config),
        )
        merge_provider_options(provider, dynamic.get("provider", {}))
        return {
            "success": True,
            "mode": "realtime_agent",
            "protocol": "hermes.voice.realtime_agent.options.v0",
            "schema_version": PROVIDER_OPTIONS_SCHEMA_VERSION,
            "provider_id": provider_id,
            "provider": provider,
            "default_provider": settings["provider"],
            "default_model": settings["model"],
            "default_voice": settings["voice"],
            "sample_rate": settings["sample_rate"] or DEFAULT_SAMPLE_RATE,
            "profile": settings["profile"],
            "config_scope": settings["config_scope"],
            "fallback_to_global": settings["fallback_to_global"],
            "dynamic": dynamic["dynamic"],
            "tool_surface": list(_TOOL_SURFACE),
        }

    async def _handle_input_audio(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        payload: dict[str, Any],
        previous_bytes: int,
    ) -> tuple[int, int]:
        audio64 = str(payload.get("audio_base64", "") or "").strip()
        if not audio64:
            await self._send_error(ws, session, "input_audio.append missing audio_base64")
            return 0, DEFAULT_SAMPLE_RATE
        try:
            chunk = base64.b64decode(audio64)
        except Exception:
            await self._send_error(ws, session, "input_audio.append audio_base64 is invalid")
            return 0, DEFAULT_SAMPLE_RATE
        sample_rate = _int_option(payload, "sample_rate", DEFAULT_SAMPLE_RATE)
        await self._send(
            ws,
            session,
            {
                "type": "voice.input_audio.received",
                "byte_count": len(chunk),
                "total_bytes": previous_bytes + len(chunk),
                "sample_rate": sample_rate,
            },
        )
        return len(chunk), sample_rate

    async def _run_agent(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        payload: dict[str, Any],
        input_audio_bytes: int,
        input_sample_rate: int,
    ) -> None:
        text = (_str_option(payload, "text") or _str_option(payload, "prompt") or "").strip()
        if not text:
            await self._send_error(
                ws,
                session,
                "realtime agent mode requires client transcript text for this MVP",
            )
            return
        text = text[:5000]
        await self._send(
            ws,
            session,
            {
                "type": "voice.input_transcript.final",
                "text": text,
                "sample_rate": input_sample_rate,
                "input_audio_bytes": input_audio_bytes,
            },
        )
        await self._send(
            ws,
            session,
            {
                "type": "voice.response.started",
                "provider": session.provider,
                "model": session.model,
                "voice": session.voice,
                "session_id": session.session_id,
                "chat_session_id": session.chat_session_id,
                "input_audio_bytes": input_audio_bytes,
            },
        )

        final_parts: list[str] = []
        hermes_error = False
        async for event in self.hermes.stream_task(
            HermesTaskRequest(
                text=text,
                profile=session.profile,
                session_id=session.chat_session_id,
                bearer_token=session.bearer_token,
            )
        ):
            if event.get("type") == "hermes.session.bound":
                bound = str(event.get("session_id") or "").strip()
                if bound:
                    session.chat_session_id = bound
            if event.get("type") == "voice.response.delta":
                final_parts.append(str(event.get("delta") or ""))
            elif event.get("type") == "voice.response.turn_completed":
                content = str(event.get("content") or "")
                if content and not final_parts:
                    final_parts.append(content)
            elif event.get("type") == "voice.error":
                hermes_error = True
            await self._send(ws, session, event)
            if hermes_error:
                return

        final_text = "".join(final_parts).strip()
        if not final_text:
            await self._send_error(ws, session, "Hermes completed without assistant text")
            return

        await self._render_provider_audio(ws, session, final_text, payload)

    async def _render_provider_audio(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        text: str,
        payload: dict[str, Any],
    ) -> None:
        queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()
        loop = asyncio.get_running_loop()
        output_path = session.event_log_path.with_suffix(".wav")
        provider_options = self._provider_options(session, payload)

        def audio_sink(chunk: bytes, meta: dict[str, Any]) -> None:
            peak, rms = _pcm_levels(chunk)
            loop.call_soon_threadsafe(
                queue.put_nowait,
                {
                    "type": "voice.output_audio.delta",
                    "audio_base64": base64.b64encode(chunk).decode("ascii"),
                    "byte_count": len(chunk),
                    "sample_rate": int(meta.get("sample_rate") or session.sample_rate),
                    "channels": int(meta.get("channels") or DEFAULT_CHANNELS),
                    "sample_width": int(meta.get("sample_width") or DEFAULT_SAMPLE_WIDTH),
                    "label": meta.get("label"),
                    "peak_level": peak,
                    "rms_level": rms,
                },
            )

        async def run_provider():
            try:
                adapter = adapter_for(session.provider)
                return await adapter.render_text(
                    text,
                    RealtimeAgentRenderConfig(
                        provider=session.provider,
                        model=session.model,
                        voice=session.voice,
                        sample_rate=session.sample_rate,
                        output_path=output_path,
                        provider_options=provider_options,
                    ),
                    audio_sink,
                )
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, None)

        task = asyncio.create_task(run_provider())
        try:
            while True:
                event = await queue.get()
                if event is None:
                    break
                await self._send(ws, session, event)
            response = await task
        except (ProviderUnavailable, ProviderRunError) as exc:
            await self._send_error(ws, session, str(exc), provider=session.provider)
            return
        except Exception as exc:
            await self._send_error(
                ws,
                session,
                f"realtime agent provider failed: {exc.__class__.__name__}: {exc}",
                provider=session.provider,
            )
            return

        await self._send(ws, session, {"type": "voice.output_audio.done"})
        await self._send(
            ws,
            session,
            {
                "type": "voice.response.done",
                "provider": response.provider,
                "model": response.model,
                "voice": response.voice,
                "audio_path": str(response.audio_path),
                "event_log_path": str(session.event_log_path),
                "chat_session_id": session.chat_session_id,
                "final_text": text,
                "metrics": response.metrics.to_dict(),
                "metadata": _safe_metadata(response.metadata),
            },
        )

    def _provider_options(
        self,
        session: RealtimeAgentSession,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        provider_options = dict(payload.get("provider_options") or {})
        provider_options.setdefault("model", session.model)
        provider_options.setdefault("voice", session.voice)
        provider_options.setdefault("sample_rate", str(session.sample_rate))
        if session.provider == "xai_realtime":
            token = _read_relay_xai_oauth_token(self.config)
            if token is not None:
                provider_options.setdefault("oauth_access_token", token.access_token)
                ws_url = _websocket_url_from_base(token.base_url)
                if ws_url:
                    provider_options.setdefault("url", ws_url)
                provider_options.setdefault("auth_source", token.source)
        return provider_options

    def _validate_provider(self, provider: str) -> None:
        try:
            self.registry.info(provider)
        except KeyError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc

    def _validate_realtime_provider(self, provider: str):
        try:
            info = self.registry.info(provider)
        except KeyError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc
        if not info.supports_realtime and provider != "stub":
            raise web.HTTPBadRequest(text=f"{provider} is not a realtime voice provider")
        return info

    def _validate_config_updates(self, payload: dict[str, Any]) -> dict[str, Any]:
        updates: dict[str, Any] = {}
        allowed = {"enabled", "provider", "model", "voice", "sample_rate"}
        unsupported = sorted(set(payload) - allowed)
        if unsupported:
            raise web.HTTPBadRequest(
                text=f"unsupported realtime agent config field(s): {', '.join(unsupported)}"
            )
        if "enabled" in payload:
            enabled = _bool_value(payload["enabled"])
            if enabled is None:
                raise web.HTTPBadRequest(text="enabled must be a boolean")
            updates["enabled"] = enabled
        if "provider" in payload:
            provider = _bounded_string(payload["provider"], "provider", max_len=80)
            self._validate_provider(provider)
            updates["provider"] = provider
        if "model" in payload:
            updates["model"] = _bounded_string(payload["model"], "model", max_len=160)
        if "voice" in payload:
            updates["voice"] = _bounded_string(payload["voice"], "voice", max_len=160)
        if "sample_rate" in payload:
            sample_rate = _int_value(payload["sample_rate"])
            if sample_rate is None or sample_rate < 8_000 or sample_rate > 96_000:
                raise web.HTTPBadRequest(text="sample_rate must be between 8000 and 96000")
            updates["sample_rate"] = sample_rate
        if not updates:
            raise web.HTTPBadRequest(text="no realtime agent config fields supplied")
        return updates

    def _new_event_log_path(self, session_id: str) -> Path:
        run_dir = _run_dir(self.config)
        run_dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
        safe_id = "".join(ch for ch in session_id if ch.isalnum())[:12]
        return run_dir / f"realtime-agent-{stamp}-{safe_id}.jsonl"

    def _ready_event(self, session: RealtimeAgentSession) -> dict[str, Any]:
        return {
            "type": "voice.session.ready",
            "session_id": session.session_id,
            "provider": session.provider,
            "model": session.model,
            "voice": session.voice,
            "sample_rate": session.sample_rate,
            "event_log_path": str(session.event_log_path),
            "mode": "realtime_agent",
            "experimental": True,
            "profile": session.profile,
            "chat_session_id": session.chat_session_id,
            "config_scope": session.config_scope,
            "config_path": str(session.config_path) if session.config_path else None,
            "tool_surface": list(_TOOL_SURFACE),
        }

    async def _send(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        event: dict[str, Any],
    ) -> None:
        event.setdefault("at_ms", round((time.time() - session.created_at) * 1000, 3))
        self._log(session, str(event["type"]), event)
        await ws.send_json(event)

    async def _send_error(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        message: str,
        **data: Any,
    ) -> None:
        await self._send(ws, session, {"type": "voice.error", "message": message, **data})

    def _log(
        self,
        session: RealtimeAgentSession,
        event_type: str,
        event: dict[str, Any] | None = None,
    ) -> None:
        payload = dict(event or {})
        payload.setdefault("type", event_type)
        payload.setdefault("unix_ms", int(time.time() * 1000))
        if "audio_base64" in payload:
            payload = dict(payload)
            payload["audio_base64"] = f"<{payload.get('byte_count', 0)} bytes>"
        session.event_log_path.parent.mkdir(parents=True, exist_ok=True)
        with session.event_log_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n")


async def _optional_json(request: web.Request) -> dict[str, Any]:
    if request.can_read_body:
        try:
            payload = await request.json()
        except json.JSONDecodeError as exc:
            raise web.HTTPBadRequest(text="invalid JSON body") from exc
        if payload is None:
            return {}
        if not isinstance(payload, dict):
            raise web.HTTPBadRequest(text="JSON body must be an object")
        return payload
    return {}


def _bearer_from_request(request: web.Request) -> str:
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        return ""
    return auth_header[len("Bearer ") :].strip()


def _run_dir(config: RelayConfig) -> Path:
    configured = config.realtime_voice_run_dir or os.getenv("RELAY_REALTIME_VOICE_RUN_DIR")
    if configured:
        return Path(configured).expanduser()
    home = Path(os.getenv("HERMES_RELAY_HOME", str(Path.home() / ".hermes-relay")))
    return home / "realtime-agent-runs"


def _config_path_for_payload(config: RelayConfig) -> Path:
    configured = config.realtime_voice_config_path or os.getenv("RELAY_REALTIME_VOICE_CONFIG")
    if configured:
        return Path(configured).expanduser()
    return default_realtime_voice_config_path()


def _xai_option_auth(config: RelayConfig) -> XAIOptionAuth | None:
    token = _read_relay_xai_oauth_token(config)
    if token is None:
        return None
    return XAIOptionAuth(
        access_token=token.access_token,
        base_url=token.base_url,
        source=token.source,
    )


def _xai_auth_status(config: RelayConfig) -> dict[str, Any]:
    token = _read_relay_xai_oauth_token(config)
    if token is not None:
        return {
            "xai_oauth_configured": True,
            "xai_oauth_source": token.source,
            "xai_oauth_path": str(config.realtime_voice_xai_oauth_path or ""),
        }
    return {
        "xai_oauth_configured": False,
        "xai_oauth_path": str(config.realtime_voice_xai_oauth_path or ""),
    }


def _str_option(payload: dict[str, Any], key: str) -> str | None:
    value = payload.get(key)
    if isinstance(value, str):
        value = value.strip()
        return value or None
    return None


def _int_option(payload: dict[str, Any], key: str, default: int) -> int:
    value = _int_value(payload.get(key))
    return default if value is None else value


def _int_value(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return None
    return None


def _bool_value(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"1", "true", "yes", "on"}:
            return True
        if lowered in {"0", "false", "no", "off"}:
            return False
    return None


def _bounded_string(value: Any, field: str, *, max_len: int) -> str:
    if not isinstance(value, str):
        raise web.HTTPBadRequest(text=f"{field} must be a string")
    text = value.strip()
    if not text:
        raise web.HTTPBadRequest(text=f"{field} must not be empty")
    if len(text) > max_len:
        raise web.HTTPBadRequest(text=f"{field} is too long")
    return text


def _pcm_levels(chunk: bytes) -> tuple[float, float]:
    if len(chunk) < 2:
        return 0.0, 0.0
    sample_count = len(chunk) // 2
    if sample_count <= 0:
        return 0.0, 0.0
    peak = 0
    total = 0.0
    for (sample,) in struct.iter_unpack("<h", chunk[: sample_count * 2]):
        value = abs(sample)
        peak = max(peak, value)
        total += sample * sample
    rms = (total / sample_count) ** 0.5
    return min(1.0, peak / 32768.0), min(1.0, rms / 32768.0)


def _safe_metadata(metadata: dict[str, Any]) -> dict[str, Any]:
    safe: dict[str, Any] = {}
    for key, value in metadata.items():
        if key.lower().endswith("token") or "secret" in key.lower():
            continue
        if isinstance(value, (str, int, float, bool)) or value is None:
            safe[key] = value
        elif isinstance(value, (list, tuple)):
            safe[key] = [item for item in value if isinstance(item, (str, int, float, bool))]
        elif isinstance(value, dict):
            safe[key] = {
                str(k): v
                for k, v in value.items()
                if isinstance(v, (str, int, float, bool)) or v is None
            }
    return safe
