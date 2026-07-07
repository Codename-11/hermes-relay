"""Provider-neutral streaming voice output broker.

This route renders final Hermes assistant text to audio. Hermes remains the
owner of chat, tool execution, approvals, and final answers; this broker only
turns already-decided text into streamed PCM for paired clients.
"""

from __future__ import annotations

import asyncio
import base64
from collections import deque
import hashlib
import hmac
import json
import math
import os
import secrets
import struct
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web

from ..voice_lab.expressions import VoiceExpression
from ..voice_lab.metrics import MetricsRecorder
from ..voice_lab.providers.base import (
    ProviderRunError,
    ProviderUnavailable,
    VoiceRequest,
)
from ..voice_lab.registry import default_registry

from .config import (
    RelayConfig,
    default_realtime_voice_config_path,
    save_voice_output_config_file,
)
from .profile_voice import (
    request_profile,
    save_profile_voice_section,
    voice_output_settings,
)
from .provider_options import (
    PROVIDER_OPTIONS_SCHEMA_VERSION,
    XAIOptionAuth,
    fetch_voice_output_provider_options,
    merge_provider_options,
    validate_provider_selection,
)
from . import upstream_voice
from .realtime_voice import _read_relay_xai_oauth_token
from .voice_auth import AuthPrincipal, require_voice_auth

DEFAULT_SAMPLE_RATE = 24000
DEFAULT_CHANNELS = 1
DEFAULT_SAMPLE_WIDTH = 2
_TRUE_ENV_VALUES = {"1", "true", "yes", "on"}
_RESUME_TTL_SECONDS = 30.0
_EVENT_RING_LIMIT = 256
_AUDIO_RING_LIMIT = 96


@dataclass(slots=True)
class VoiceOutputSession:
    session_id: str
    provider: str
    model: str
    voice: str
    sample_rate: int
    language: str
    codec: str
    optimize_streaming_latency: int
    text_normalization: bool
    fallback_enabled: bool
    profile: str | None
    config_scope: str
    config_path: Path | None
    created_at: float
    event_log_path: Path
    resume_token_hash: str
    auth_kind: str
    auth_session_device_id: str | None = None
    auth_token_hash: str | None = None
    # xAI expressive speech tags (xai_tts only). Applied to the render text at
    # the relay layer; other providers ignore it.
    auto_speech_tags: bool = False
    resume_ttl_seconds: float = _RESUME_TTL_SECONDS
    resume_deadline: float | None = None
    attached_ws: web.WebSocketResponse | None = None
    detached_at: float | None = None
    closed: bool = False
    explicit_close_requested: bool = False
    event_seq: int = 0
    audio_seq: int = 0
    event_ring: deque[dict[str, Any]] = field(
        default_factory=lambda: deque(maxlen=_EVENT_RING_LIMIT)
    )
    audio_ring: deque[dict[str, Any]] = field(
        default_factory=lambda: deque(maxlen=_AUDIO_RING_LIMIT)
    )
    acked_event_id_by_client: int = 0
    acked_audio_event_id_by_client: int = 0
    played_audio_event_id_by_client: int = 0
    response_task: asyncio.Task[None] | None = None
    close_task: asyncio.Task[None] | None = None


class VoiceOutputHandler:
    """Authenticated speech-renderer surface for Android voice mode."""

    def __init__(self, config: RelayConfig) -> None:
        self.config = config
        self.registry = default_registry()
        self.sessions: dict[str, VoiceOutputSession] = {}

    async def handle_config(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:tts")
        profile = request_profile(None, request.query)
        return web.json_response(self.config_payload(profile))

    async def handle_update_config(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:tts")
        payload = await _optional_json(request)
        profile = request_profile(payload, request.query)
        payload.pop("profile", None)
        updates = self._validate_config_updates(payload)
        config_path = save_profile_voice_section(
            self.config,
            profile,
            "voice_output",
            updates,
        )
        if config_path is None and profile:
            raise web.HTTPNotFound(text=f"profile not found or not writable: {profile}")
        if config_path is None:
            config_path = save_voice_output_config_file(self.config, updates)
        body = self.config_payload(profile)
        body["updated"] = sorted(updates)
        body["config_path"] = str(config_path)
        return web.json_response(body)

    async def handle_provider_options(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:tts")
        provider_id = str(request.match_info.get("provider_id", "")).strip()
        profile = request_profile(None, request.query)
        return web.json_response(await self.provider_options_payload(provider_id, profile))

    async def handle_provider_validate(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:tts")
        provider_id = str(request.match_info.get("provider_id", "")).strip()
        payload = await _optional_json(request)
        profile = request_profile(payload, request.query)
        settings = voice_output_settings(self.config, profile)
        options = await self.provider_options_payload(provider_id, profile)
        model = _str_option(payload, "model") or str(settings["model"])
        voice = _str_option(payload, "voice") or str(settings["voice"])
        sample_rate = _int_option(
            payload,
            "sample_rate",
            int(settings["sample_rate"] or DEFAULT_SAMPLE_RATE),
        )
        language = _str_option(payload, "language") or str(settings["language"])
        validation = validate_provider_selection(
            options["provider"],
            model=model,
            voice=voice,
            sample_rate=sample_rate,
            language=language,
        )
        return web.json_response(
            {
                "success": True,
                "mode": "voice_output",
                "protocol": "hermes.voice.output.validate.v0",
                "provider_id": provider_id,
                "model": model,
                "voice": voice,
                "sample_rate": sample_rate,
                "language": language,
                "dynamic": options["dynamic"],
                **validation,
            }
        )

    async def handle_create_session(self, request: web.Request) -> web.StreamResponse:
        principal = await require_voice_auth(request, "voice:tts")
        bearer_token = _bearer_from_request(request)

        payload = await _optional_json(request)
        profile = request_profile(payload, request.query)
        settings = voice_output_settings(self.config, profile)
        if not bool(settings["enabled"]):
            raise web.HTTPNotFound(text="voice output is disabled")
        provider = _str_option(payload, "provider") or str(settings["provider"])
        model = _str_option(payload, "model") or str(settings["model"])
        voice = _str_option(payload, "voice") or str(settings["voice"])
        sample_rate = _int_option(
            payload,
            "sample_rate",
            int(settings["sample_rate"] or DEFAULT_SAMPLE_RATE),
        )
        self._validate_provider(provider)

        session_id = secrets.token_urlsafe(18)
        resume_token, resume_token_hash = _new_resume_token()
        event_log_path = self._new_event_log_path(session_id)
        session = VoiceOutputSession(
            session_id=session_id,
            provider=provider,
            model=model,
            voice=voice,
            sample_rate=sample_rate,
            language=str(settings["language"]),
            codec=str(settings["codec"]),
            optimize_streaming_latency=int(settings["optimize_streaming_latency"]),
            text_normalization=bool(settings["text_normalization"]),
            fallback_enabled=bool(settings["fallback_enabled"]),
            auto_speech_tags=bool(settings["auto_speech_tags"]),
            profile=settings.get("profile"),
            config_scope=str(settings["config_scope"]),
            config_path=settings.get("config_path"),
            created_at=time.time(),
            event_log_path=event_log_path,
            resume_token_hash=resume_token_hash,
            auth_kind=principal.kind,
            resume_ttl_seconds=_configured_resume_ttl_seconds(),
            auth_session_device_id=(
                principal.session.device_id if principal.session is not None else None
            ),
            auth_token_hash=_token_hash(bearer_token),
        )
        self.sessions[session_id] = session
        self._log(session, "voice.output.session.created")

        return web.json_response(
            {
                "success": True,
                "session_id": session_id,
                "websocket_path": f"/voice/output/{session_id}",
                "resume_token": resume_token,
                "resume_supported": True,
                "resume_ttl_ms": int(session.resume_ttl_seconds * 1000),
                "provider": provider,
                "model": model,
                "voice": voice,
                "sample_rate": sample_rate,
                "event_log_path": str(event_log_path),
                "protocol": "hermes.voice.output.v0",
                "profile": session.profile,
                "config_scope": session.config_scope,
                "config_path": str(session.config_path) if session.config_path else None,
                "fallback_to_global": bool(settings["fallback_to_global"]),
            }
        )

    async def handle_ws(self, request: web.Request) -> web.StreamResponse:
        if not self.enabled:
            raise web.HTTPNotFound(text="voice output is disabled")
        principal = await require_voice_auth(request, "voice:tts")

        session_id = request.match_info.get("session_id", "")
        session = self.sessions.get(session_id)
        if session is None:
            raise web.HTTPNotFound(text="unknown voice output session")
        if session.closed:
            raise web.HTTPGone(text="voice output session is closed")
        if not self._auth_matches_session(session, principal, _bearer_from_request(request)):
            raise web.HTTPForbidden(text="voice output session belongs to another principal")

        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=2 * 1024 * 1024)
        await ws.prepare(request)
        was_detached = session.detached_at is not None
        session.attached_ws = ws
        if not was_detached:
            session.detached_at = None
            session.resume_deadline = None
            if session.close_task is not None and not session.close_task.done():
                session.close_task.cancel()
            await self._send(ws, session, self._ready_event(session))

        intentional_close = False
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
                    if _str_option(payload, "resume_token") or session.detached_at is not None:
                        await self._handle_resume_message(ws, session, payload)
                    else:
                        await self._send(ws, session, self._ready_event(session))
                elif msg_type == "session.resume":
                    await self._handle_resume_message(ws, session, payload)
                elif msg_type == "client.ack":
                    self._handle_client_ack(session, payload)
                elif msg_type == "response.create":
                    if session.response_task is not None and not session.response_task.done():
                        await self._send_error(ws, session, "response already running")
                        continue
                    if session.response_task is not None and session.response_task.done():
                        await self._send_error(ws, session, "response already completed")
                        continue
                    session.response_task = asyncio.create_task(
                        self._run_response(ws, session, payload)
                    )
                elif msg_type == "session.close":
                    intentional_close = True
                    session.explicit_close_requested = True
                    await ws.close()
                else:
                    await self._send_error(ws, session, f"unsupported message type: {msg_type}")
        finally:
            if session.attached_ws is ws:
                session.attached_ws = None
            response_task = session.response_task
            should_close = (
                intentional_close
                or session.explicit_close_requested
                or session.closed
                or response_task is None
                or (ws.close_code == 1000 and response_task.done())
            )
            if should_close:
                await self._close_session(session, "closed")
            else:
                await self._detach_session(session, "websocket_disconnected")
        return ws

    @property
    def enabled(self) -> bool:
        if self.config.voice_output_enabled:
            return True
        return os.getenv("RELAY_VOICE_OUTPUT_ENABLED", "").strip().lower() in _TRUE_ENV_VALUES

    async def _detach_session(
        self,
        session: VoiceOutputSession,
        reason: str,
    ) -> None:
        if session.closed:
            return
        now = time.time()
        session.detached_at = now
        session.resume_deadline = now + session.resume_ttl_seconds
        await self._send(
            None,
            session,
            {
                "type": "voice.session.detached",
                "session_id": session.session_id,
                "reason": reason,
                "resume_ttl_ms": int(session.resume_ttl_seconds * 1000),
            },
        )
        self._log(
            session,
            "voice.output.session.detached",
            {
                "type": "voice.output.session.detached",
                "reason": reason,
                "resume_deadline": session.resume_deadline,
            },
        )
        if session.close_task is not None and not session.close_task.done():
            session.close_task.cancel()
        session.close_task = asyncio.create_task(self._close_detached_session_after_ttl(session))

    async def _close_detached_session_after_ttl(self, session: VoiceOutputSession) -> None:
        try:
            await asyncio.sleep(session.resume_ttl_seconds)
        except asyncio.CancelledError:
            return
        if session.attached_ws is not None or session.detached_at is None or session.closed:
            return
        await self._close_session(session, "resume_ttl_expired")

    async def _close_session(self, session: VoiceOutputSession, reason: str) -> None:
        if session.closed:
            return
        session.closed = True
        session.detached_at = None
        session.resume_deadline = None
        if session.close_task is not None and not session.close_task.done():
            session.close_task.cancel()
        response_task = session.response_task
        if response_task is not None and not response_task.done():
            response_task.cancel()
        self._log(
            session,
            "voice.output.session.closed",
            {
                "type": "voice.output.session.closed",
                "reason": reason,
            },
        )

    async def _handle_resume_message(
        self,
        ws: web.WebSocketResponse,
        session: VoiceOutputSession,
        payload: dict[str, Any],
    ) -> None:
        if not self._resume_token_matches(session, _str_option(payload, "resume_token")):
            await self._send(
                ws,
                session,
                {
                    "type": "voice.session.resume_failed",
                    "session_id": session.session_id,
                    "reason": "invalid_resume_token",
                },
                record=False,
            )
            await ws.close(code=4003, message=b"invalid resume token")
            return
        if session.resume_deadline is not None and time.time() > session.resume_deadline:
            await self._send(
                ws,
                session,
                {
                    "type": "voice.session.resume_failed",
                    "session_id": session.session_id,
                    "reason": "resume_expired",
                },
                record=False,
            )
            await ws.close(code=4008, message=b"resume expired")
            await self._close_session(session, "resume_expired")
            return

        last_event_id = _int_option(payload, "last_event_id", 0)
        last_audio_event_id = _int_option(payload, "last_audio_event_id", 0)
        played_audio_event_id = _int_option(payload, "last_played_audio_event_id", 0)
        session.acked_event_id_by_client = max(session.acked_event_id_by_client, last_event_id)
        session.acked_audio_event_id_by_client = max(
            session.acked_audio_event_id_by_client,
            last_audio_event_id,
        )
        session.played_audio_event_id_by_client = max(
            session.played_audio_event_id_by_client,
            played_audio_event_id,
        )
        session.detached_at = None
        session.resume_deadline = None
        if session.close_task is not None and not session.close_task.done():
            session.close_task.cancel()
        await self._send(
            ws,
            session,
            {
                "type": "voice.session.resumed",
                "session_id": session.session_id,
                "last_event_id": last_event_id,
                "last_audio_event_id": last_audio_event_id,
                "last_played_audio_event_id": played_audio_event_id,
            },
            record=False,
        )
        await self._replay_session_events(
            ws,
            session,
            last_event_id=last_event_id,
            last_audio_event_id=last_audio_event_id,
            played_audio_event_id=played_audio_event_id,
        )

    async def _replay_session_events(
        self,
        ws: web.WebSocketResponse,
        session: VoiceOutputSession,
        *,
        last_event_id: int,
        last_audio_event_id: int,
        played_audio_event_id: int,
    ) -> None:
        audio_floor = max(last_audio_event_id, played_audio_event_id)
        replay_events: list[dict[str, Any]] = []
        for event in list(session.event_ring):
            event_id = _event_int(event, "event_id")
            audio_event_id = _event_int(event, "audio_event_id")
            if audio_event_id > 0:
                if audio_event_id <= audio_floor:
                    continue
            elif event_id <= last_event_id:
                continue
            replay_events.append(dict(event))

        await self._send(
            ws,
            session,
            {
                "type": "voice.replay.started",
                "session_id": session.session_id,
                "from_event_id": last_event_id,
                "from_audio_event_id": last_audio_event_id,
                "replay_event_count": len(replay_events),
            },
            record=False,
        )
        for event in replay_events:
            replay = dict(event)
            replay["replayed"] = True
            await ws.send_json(replay)
        await self._send(
            ws,
            session,
            {
                "type": "voice.replay.done",
                "session_id": session.session_id,
                "replay_event_count": len(replay_events),
            },
            record=False,
        )

    def _handle_client_ack(
        self,
        session: VoiceOutputSession,
        payload: dict[str, Any],
    ) -> None:
        event_id = _int_option(payload, "event_id", 0)
        audio_event_id = _int_option(payload, "audio_event_id", 0)
        played_audio_event_id = _int_option(payload, "played_audio_event_id", 0)
        session.acked_event_id_by_client = max(session.acked_event_id_by_client, event_id)
        session.acked_audio_event_id_by_client = max(
            session.acked_audio_event_id_by_client,
            audio_event_id,
        )
        session.played_audio_event_id_by_client = max(
            session.played_audio_event_id_by_client,
            played_audio_event_id,
        )

    def _resume_token_matches(
        self,
        session: VoiceOutputSession,
        token: str | None,
    ) -> bool:
        if not token:
            return False
        return hmac.compare_digest(_token_hash(token), session.resume_token_hash)

    def _auth_matches_session(
        self,
        session: VoiceOutputSession,
        principal: AuthPrincipal,
        bearer_token: str,
    ) -> bool:
        if principal.kind != session.auth_kind:
            return False
        if principal.kind == "relay_session":
            device_id = principal.session.device_id if principal.session is not None else None
            return bool(device_id) and device_id == session.auth_session_device_id
        return hmac.compare_digest(_token_hash(bearer_token), session.auth_token_hash or "")

    def config_payload(self, profile: str | None = None) -> dict[str, Any]:
        providers = [
            info.to_dict()
            for info in self.registry.provider_infos()
            if info.supports_tts and not info.supports_realtime
        ]
        settings = voice_output_settings(self.config, profile)
        return {
            "success": True,
            "enabled": bool(settings["enabled"]),
            "protocol": "hermes.voice.output.v0",
            "config_path": str(settings["config_path"] or _config_path_for_payload(self.config)),
            "default_provider": settings["provider"],
            "default_model": settings["model"],
            "default_voice": settings["voice"],
            "sample_rate": settings["sample_rate"],
            "language": settings["language"],
            "codec": settings["codec"],
            "optimize_streaming_latency": settings["optimize_streaming_latency"],
            "text_normalization": settings["text_normalization"],
            "fallback_enabled": settings["fallback_enabled"],
            "auto_speech_tags": settings["auto_speech_tags"],
            "fallback_provider": "legacy_hermes_tts",
            "providers": providers,
            "auth": _voice_output_auth_status(self.config),
            "profile": settings["profile"],
            "config_scope": settings["config_scope"],
            "fallback_to_global": settings["fallback_to_global"],
        }

    async def provider_options_payload(
        self,
        provider_id: str,
        profile: str | None = None,
    ) -> dict[str, Any]:
        info = self._validate_provider(provider_id)
        settings = voice_output_settings(self.config, profile)
        provider = info.to_dict()
        dynamic = await self._dynamic_provider_options(provider_id)
        merge_provider_options(provider, dynamic.get("provider", {}))
        return {
            "success": True,
            "mode": "voice_output",
            "protocol": "hermes.voice.output.options.v0",
            "schema_version": PROVIDER_OPTIONS_SCHEMA_VERSION,
            "provider_id": provider_id,
            "provider": provider,
            "default_provider": settings["provider"],
            "default_model": settings["model"],
            "default_voice": settings["voice"],
            "sample_rate": settings["sample_rate"],
            "language": settings["language"],
            "profile": settings["profile"],
            "config_scope": settings["config_scope"],
            "fallback_to_global": settings["fallback_to_global"],
            "dynamic": dynamic["dynamic"],
        }

    async def _run_response(
        self,
        ws: web.WebSocketResponse,
        session: VoiceOutputSession,
        payload: dict[str, Any],
    ) -> None:
        text = _str_option(payload, "text") or _str_option(payload, "prompt")
        if not text:
            await self._send_error(ws, session, "response.create missing text")
            return
        text = text[:5000]
        render_mode = (_str_option(payload, "render_mode") or "verbatim").lower()

        await self._send(
            ws,
            session,
            {
                "type": "voice.response.started",
                "provider": session.provider,
                "model": session.model,
                "voice": session.voice,
                "render_mode": render_mode,
                "text_chars": len(text),
                "output_mode": "streaming_tts_renderer",
            },
        )

        queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()
        loop = asyncio.get_running_loop()
        recorder = MetricsRecorder()
        provider = self.registry.create(session.provider)
        output_path = session.event_log_path.with_suffix(".wav")
        provider_options = self._provider_options(session, payload)
        provider_options["render_mode"] = render_mode

        # xAI expressive speech tags: the voice_lab xai_tts renderer streams text
        # verbatim and does not inject xAI's inline/wrapping tags, so apply them
        # here (same transform the basic /voice/synthesize path uses) when the
        # session/request opts in. Other providers ignore the flag.
        if (
            session.provider == "xai_tts"
            and _bool_value(provider_options.get("auto_speech_tags")) is True
        ):
            text = upstream_voice.apply_xai_speech_tags(text)

        def audio_sink(chunk: bytes, meta: dict[str, Any]) -> None:
            peak, rms = _pcm_levels(chunk)
            event = {
                "type": "voice.audio.delta",
                "audio_base64": base64.b64encode(chunk).decode("ascii"),
                "byte_count": len(chunk),
                "sample_rate": int(meta.get("sample_rate") or session.sample_rate),
                "channels": int(meta.get("channels") or DEFAULT_CHANNELS),
                "sample_width": int(meta.get("sample_width") or DEFAULT_SAMPLE_WIDTH),
                "label": meta.get("label"),
                "peak_level": peak,
                "rms_level": rms,
            }
            loop.call_soon_threadsafe(queue.put_nowait, event)

        def run_provider():
            try:
                return provider.run_text(
                    VoiceRequest(
                        text=text,
                        expression=_expression_from_payload(payload),
                        output_path=output_path,
                        provider_options=provider_options,
                        audio_sink=audio_sink,
                    ),
                    recorder,
                )
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, None)

        task = asyncio.create_task(asyncio.to_thread(run_provider))
        try:
            while True:
                event = await queue.get()
                if event is None:
                    break
                await self._send(ws, session, event)
            response = await task
        except (ProviderUnavailable, ProviderRunError) as exc:
            await self._send_error(
                ws,
                session,
                str(exc),
                provider=session.provider,
                fallback_enabled=session.fallback_enabled,
                fallback_provider="legacy_hermes_tts",
            )
            return
        except Exception as exc:
            await self._send_error(
                ws,
                session,
                f"voice output provider failed: {exc.__class__.__name__}: {exc}",
                provider=session.provider,
                fallback_enabled=session.fallback_enabled,
                fallback_provider="legacy_hermes_tts",
            )
            return

        await self._send(ws, session, {"type": "voice.audio.done"})
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
                "metrics": response.metrics.to_dict(),
                "metadata": _safe_metadata(response.metadata),
                "output_mode": "streaming_tts_renderer",
            },
        )

    def _provider_options(
        self,
        session: VoiceOutputSession,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        provider_options = dict(payload.get("provider_options") or {})
        provider_options.setdefault("model", session.model)
        provider_options.setdefault("voice", session.voice)
        provider_options.setdefault("sample_rate", str(session.sample_rate))
        provider_options.setdefault("language", session.language)
        provider_options.setdefault("codec", session.codec)
        provider_options.setdefault(
            "response_format",
            session.codec,
        )
        provider_options.setdefault(
            "optimize_streaming_latency",
            str(session.optimize_streaming_latency),
        )
        provider_options.setdefault(
            "text_normalization",
            "true" if session.text_normalization else "false",
        )
        provider_options.setdefault(
            "auto_speech_tags",
            "true" if session.auto_speech_tags else "false",
        )

        if session.provider == "xai_tts":
            token = _read_relay_xai_oauth_token(self.config)
            if token is not None:
                provider_options.setdefault("oauth_access_token", token.access_token)
                provider_options.setdefault("auth_source", token.source)
                if token.base_url:
                    provider_options.setdefault("url", token.base_url)
        return provider_options

    async def _dynamic_provider_options(self, provider_id: str) -> dict[str, Any]:
        return await asyncio.to_thread(
            fetch_voice_output_provider_options,
            provider_id,
            xai_auth=_xai_option_auth(self.config),
        )

    def _validate_provider(self, provider: str):
        try:
            info = self.registry.info(provider)
        except KeyError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc
        if not info.supports_tts or info.supports_realtime:
            raise web.HTTPBadRequest(
                text=f"{provider} is not a streaming TTS renderer"
            )
        return info

    def _validate_config_updates(self, payload: dict[str, Any]) -> dict[str, Any]:
        updates: dict[str, Any] = {}
        allowed = {
            "enabled",
            "provider",
            "model",
            "voice",
            "sample_rate",
            "language",
            "codec",
            "optimize_streaming_latency",
            "text_normalization",
            "fallback_enabled",
            "auto_speech_tags",
        }
        unsupported = sorted(set(payload) - allowed)
        if unsupported:
            raise web.HTTPBadRequest(
                text=f"unsupported voice output config field(s): {', '.join(unsupported)}"
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

        if "language" in payload:
            updates["language"] = _bounded_string(payload["language"], "language", max_len=24)

        if "codec" in payload:
            codec = _bounded_string(payload["codec"], "codec", max_len=24).lower()
            if codec not in {"pcm", "wav", "mp3", "mulaw", "ulaw", "alaw"}:
                raise web.HTTPBadRequest(text="unsupported codec")
            updates["codec"] = codec

        if "optimize_streaming_latency" in payload:
            value = _int_value(payload["optimize_streaming_latency"])
            if value is None or value < 0 or value > 1:
                raise web.HTTPBadRequest(
                    text="optimize_streaming_latency must be 0 or 1"
                )
            updates["optimize_streaming_latency"] = value

        if "text_normalization" in payload:
            value = _bool_value(payload["text_normalization"])
            if value is None:
                raise web.HTTPBadRequest(text="text_normalization must be a boolean")
            updates["text_normalization"] = value

        if "fallback_enabled" in payload:
            value = _bool_value(payload["fallback_enabled"])
            if value is None:
                raise web.HTTPBadRequest(text="fallback_enabled must be a boolean")
            updates["fallback_enabled"] = value

        if "auto_speech_tags" in payload:
            value = _bool_value(payload["auto_speech_tags"])
            if value is None:
                raise web.HTTPBadRequest(text="auto_speech_tags must be a boolean")
            updates["auto_speech_tags"] = value

        if not updates:
            raise web.HTTPBadRequest(text="no voice output config fields supplied")
        return updates

    def _new_event_log_path(self, session_id: str) -> Path:
        run_dir = _run_dir(self.config)
        run_dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
        safe_id = "".join(ch for ch in session_id if ch.isalnum())[:12]
        return run_dir / f"voice-output-{stamp}-{safe_id}.jsonl"

    def _ready_event(self, session: VoiceOutputSession) -> dict[str, Any]:
        return {
            "type": "voice.session.ready",
            "session_id": session.session_id,
            "provider": session.provider,
            "model": session.model,
            "voice": session.voice,
            "sample_rate": session.sample_rate,
            "event_log_path": str(session.event_log_path),
            "output_mode": "streaming_tts_renderer",
            "resume_supported": True,
            "resume_ttl_ms": int(session.resume_ttl_seconds * 1000),
            "last_event_id": session.event_seq,
            "last_audio_event_id": session.audio_seq,
            "profile": session.profile,
            "config_scope": session.config_scope,
            "config_path": str(session.config_path) if session.config_path else None,
        }

    async def _send(
        self,
        ws: web.WebSocketResponse | None,
        session: VoiceOutputSession,
        event: dict[str, Any],
        *,
        record: bool = True,
    ) -> None:
        if "event_id" not in event:
            session.event_seq += 1
            event["event_id"] = session.event_seq
        if event.get("type") == "voice.audio.delta" and "audio_event_id" not in event:
            session.audio_seq += 1
            event["audio_event_id"] = session.audio_seq
        event.setdefault("at_ms", round((time.time() - session.created_at) * 1000, 3))
        if record:
            snapshot = dict(event)
            session.event_ring.append(snapshot)
            if "audio_event_id" in snapshot:
                session.audio_ring.append(snapshot)
        self._log(session, event["type"], event)
        target = session.attached_ws
        if target is None or target.closed:
            return
        try:
            await target.send_json(event)
        except (ConnectionResetError, RuntimeError):
            return

    async def _send_error(
        self,
        ws: web.WebSocketResponse,
        session: VoiceOutputSession,
        message: str,
        **data: Any,
    ) -> None:
        await self._send(ws, session, {"type": "voice.error", "message": message, **data})

    def _log(
        self,
        session: VoiceOutputSession,
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


def _xai_option_auth(config: RelayConfig) -> XAIOptionAuth | None:
    token = _read_relay_xai_oauth_token(config)
    if token is None:
        return None
    return XAIOptionAuth(
        access_token=token.access_token,
        base_url=token.base_url,
        source=token.source,
    )


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


def _new_resume_token() -> tuple[str, str]:
    token = secrets.token_urlsafe(32)
    return token, _token_hash(token)


def _configured_resume_ttl_seconds() -> float:
    value = _int_value(os.getenv("RELAY_VOICE_RESUME_TTL_MS"))
    if value is not None and value > 0:
        return value / 1000.0
    return _RESUME_TTL_SECONDS


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _run_dir(config: RelayConfig) -> Path:
    configured = config.voice_output_run_dir or os.getenv("RELAY_VOICE_OUTPUT_RUN_DIR")
    if configured:
        return Path(configured).expanduser()
    home = Path(os.getenv("HERMES_RELAY_HOME", str(Path.home() / ".hermes-relay")))
    return home / "voice-output-runs"


def _config_path_for_payload(config: RelayConfig) -> Path:
    configured = config.voice_output_config_path or os.getenv("RELAY_VOICE_OUTPUT_CONFIG")
    if configured:
        return Path(configured).expanduser()
    return default_realtime_voice_config_path()


def _voice_output_auth_status(config: RelayConfig) -> dict[str, Any]:
    xai_env_names = [
        name
        for name in ("VOICE_TOOLS_XAI_KEY", "XAI_API_KEY", "GROK_API_KEY")
        if os.getenv(name, "").strip()
    ]
    openai_env_names = [
        name
        for name in ("VOICE_TOOLS_OPENAI_KEY", "OPENAI_API_KEY")
        if os.getenv(name, "").strip()
    ]
    oauth = _read_relay_xai_oauth_token(config)
    return {
        "xai_env": bool(xai_env_names),
        "xai_env_names": xai_env_names,
        "xai_oauth": oauth is not None,
        "xai_oauth_source": oauth.source if oauth else None,
        "openai_env": bool(openai_env_names),
        "openai_env_names": openai_env_names,
    }


def _expression_from_payload(payload: dict[str, Any]) -> VoiceExpression:
    expression = payload.get("expression")
    if not isinstance(expression, dict):
        expression = {}
    return VoiceExpression(
        emotion=_str_option(expression, "emotion") or "neutral",
        tone=_str_option(expression, "tone") or "natural",
        intensity=_float_option(expression, "intensity", 0.45),
        pace=_str_option(expression, "pace") or "normal",
        style=_str_option(expression, "style") or "natural",
        pronunciation_hints=_string_list(expression.get("pronunciation_hints")),
        interruption_behavior=_str_option(expression, "interruption_behavior") or "allow",
        persona_instructions=_str_option(expression, "persona_instructions"),
        provider_overrides=dict(expression.get("provider_overrides") or {}),
    )


def _safe_metadata(metadata: dict[str, Any]) -> dict[str, Any]:
    safe: dict[str, Any] = {}
    for key, value in metadata.items():
        lowered = key.lower()
        if any(secret in lowered for secret in ("token", "secret", "key")):
            safe[key] = "<redacted>"
        else:
            safe[key] = value
    return safe


def _pcm_levels(pcm: bytes) -> tuple[float, float]:
    if len(pcm) < 2:
        return 0.0, 0.0
    usable = len(pcm) - (len(pcm) % 2)
    if usable <= 0:
        return 0.0, 0.0
    count = usable // 2
    values = struct.unpack(f"<{count}h", pcm[:usable])
    if not values:
        return 0.0, 0.0
    peak = max(abs(item) for item in values)
    rms = math.sqrt(sum(float(item) * float(item) for item in values) / len(values))
    return (
        round(min(1.0, peak / 32767.0), 4),
        round(min(1.0, rms / 32767.0), 4),
    )


def _str_option(payload: dict[str, Any], name: str) -> str | None:
    value = payload.get(name)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _bounded_string(value: Any, name: str, max_len: int) -> str:
    if value is None:
        raise web.HTTPBadRequest(text=f"{name} must be a non-empty string")
    text = str(value).strip()
    if not text:
        raise web.HTTPBadRequest(text=f"{name} must be a non-empty string")
    if len(text) > max_len:
        raise web.HTTPBadRequest(text=f"{name} must be {max_len} chars or fewer")
    return text


def _bool_value(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in ("1", "true", "yes", "on"):
            return True
        if lowered in ("0", "false", "no", "off"):
            return False
    return None


def _int_value(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _event_int(event: dict[str, Any], key: str) -> int:
    value = event.get(key)
    if isinstance(value, bool):
        return 0
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return 0
    return 0


def _int_option(payload: dict[str, Any], name: str, default: int) -> int:
    value = payload.get(name)
    if value is None or value == "":
        return default
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise web.HTTPBadRequest(text=f"{name} must be an integer") from exc


def _float_option(payload: dict[str, Any], name: str, default: float) -> float:
    value = payload.get(name)
    if value is None or value == "":
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]
