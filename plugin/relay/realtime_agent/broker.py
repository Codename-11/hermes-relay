"""Experimental realtime voice agent route handler.

This surface is intentionally separate from ``/voice/realtime/*``. The old
route remains a provider lab; this route binds a realtime provider renderer to
the Hermes session/tool loop.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import secrets
import struct
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web

from plugin.voice_lab.auth import load_voice_lab_env_file
from plugin.voice_lab.providers.base import ProviderRunError, ProviderUnavailable
from plugin.voice_lab.registry import default_registry

from ..config import (
    RelayConfig,
    default_realtime_voice_config_path,
    hermes_api_server_key,
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
from .models import (
    CLIENT_MSG_HERMES_CONFIRM,
    CLIENT_MSG_INPUT_AUDIO_APPEND,
    CLIENT_MSG_INPUT_AUDIO_CLEAR,
    CLIENT_MSG_INPUT_AUDIO_COMMIT,
    CLIENT_MSG_PLAYBACK_DRAINED,
    CLIENT_MSG_RESPONSE_CANCEL,
    CLIENT_MSG_SESSION_CLOSE,
    CLIENT_MSG_SESSION_START,
    HERMES_TOOL_SCHEMAS,
    HERMES_TOOL_SURFACE,
    ProviderEvent,
    ProviderEventKind,
    RealtimeAgentSessionConfig,
    SERVER_EVT_INPUT_AUDIO_RECEIVED,
    SERVER_EVT_INPUT_TRANSCRIPT_DELTA,
    SERVER_EVT_INPUT_TRANSCRIPT_FINAL,
    SERVER_EVT_OUTPUT_AUDIO_DELTA,
    SERVER_EVT_OUTPUT_AUDIO_DONE,
    SERVER_EVT_PLAYBACK_DRAIN_REQUESTED,
    SERVER_EVT_RESPONSE_DELTA,
    SERVER_EVT_RESPONSE_DONE,
    SERVER_EVT_RESPONSE_STARTED,
    SERVER_EVT_SESSION_READY,
    ToolCallEvent,
)
from .providers import adapter_for
from .providers.base import RealtimeAgentConnection, RealtimeAgentProvider, RealtimeAgentRenderConfig
from .providers.openai import OpenAIRealtimeAgentProvider
from .providers.xai import XAIRealtimeAgentProvider

DEFAULT_SAMPLE_RATE = 24000
DEFAULT_CHANNELS = 1
DEFAULT_SAMPLE_WIDTH = 2
_TRUE_ENV_VALUES = {"1", "true", "yes", "on"}
_TOOL_SURFACE = HERMES_TOOL_SURFACE
_PLAYBACK_DRAIN_TIMEOUT_SECONDS = 2.5
logger = logging.getLogger(__name__)


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
    hermes_run_id: str | None = None
    hermes_run_status: str = "idle"
    pending_confirmation_id: str | None = None
    cancel_requested: bool = False
    hermes_task: asyncio.Task[dict[str, Any]] | None = None
    response_ids_awaiting_tool_followup: set[str] = field(default_factory=set)


class RealtimeAgentHandler:
    """Relay-owned realtime-agent broker preserving Hermes as authority."""

    def __init__(self, config: RelayConfig) -> None:
        self.config = config
        self.registry = default_registry()
        self.sessions: dict[str, RealtimeAgentSession] = {}
        self.hermes = HermesToolBroker(config.webapi_url)
        self.native_providers: dict[str, RealtimeAgentProvider] = {
            OpenAIRealtimeAgentProvider.provider_id: OpenAIRealtimeAgentProvider(),
            XAIRealtimeAgentProvider.provider_id: XAIRealtimeAgentProvider(),
        }

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
            bearer_token=_hermes_broker_bearer_token(
                principal,
                request_bearer=bearer_token,
                config=self.config,
            ),
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

        if session.provider in self.native_providers:
            return await self._handle_provider_native_ws(request, session)

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

    async def _handle_provider_native_ws(
        self,
        request: web.Request,
        session: RealtimeAgentSession,
    ) -> web.StreamResponse:
        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=2 * 1024 * 1024)
        await ws.prepare(request)

        provider = self.native_providers[session.provider]
        try:
            connection = await provider.connect(self._native_session_config(session))
        except (ProviderUnavailable, ProviderRunError) as exc:
            await self._send_error(ws, session, str(exc), provider=session.provider)
            await ws.close()
            return ws
        except Exception as exc:
            await self._send_error(
                ws,
                session,
                f"realtime agent provider connection failed: {exc.__class__.__name__}: {exc}",
                provider=session.provider,
            )
            await ws.close()
            return ws

        await self._send(ws, session, self._ready_event(session))
        playback_drained = asyncio.Event()
        provider_task = asyncio.create_task(
            self._pump_provider_events(
                ws,
                session,
                connection,
                playback_drained,
            )
        )
        input_audio_bytes = 0
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
                if msg_type == CLIENT_MSG_SESSION_START:
                    await self._send(ws, session, self._ready_event(session))
                elif msg_type == CLIENT_MSG_INPUT_AUDIO_APPEND:
                    decoded = await self._decode_input_audio_payload(ws, session, payload)
                    if decoded is None:
                        continue
                    chunk, sample_rate = decoded
                    await connection.send_audio(chunk, sample_rate)
                    input_audio_bytes += len(chunk)
                    await self._send(
                        ws,
                        session,
                        {
                            "type": SERVER_EVT_INPUT_AUDIO_RECEIVED,
                            "byte_count": len(chunk),
                            "total_bytes": input_audio_bytes,
                            "sample_rate": sample_rate,
                        },
                    )
                elif msg_type == CLIENT_MSG_INPUT_AUDIO_COMMIT:
                    await connection.commit_audio()
                elif msg_type == CLIENT_MSG_INPUT_AUDIO_CLEAR:
                    await connection.clear_audio()
                elif msg_type == CLIENT_MSG_RESPONSE_CANCEL:
                    self._cancel_active_hermes(session)
                    await connection.cancel_response()
                    await connection.clear_audio()
                    await self._send(
                        ws,
                        session,
                        {
                            "type": "hermes.run.cancelled",
                            "session_id": session.chat_session_id,
                            "run_id": session.hermes_run_id,
                        },
                    )
                    await self._send(
                        ws,
                        session,
                        {
                            "type": SERVER_EVT_RESPONSE_DONE,
                            "provider": session.provider,
                            "model": session.model,
                            "voice": session.voice,
                            "event_log_path": str(session.event_log_path),
                            "chat_session_id": session.chat_session_id,
                            "run_id": session.hermes_run_id,
                            "cancelled": True,
                        },
                    )
                elif msg_type == CLIENT_MSG_PLAYBACK_DRAINED:
                    playback_drained.set()
                elif msg_type == CLIENT_MSG_HERMES_CONFIRM:
                    await self._send(
                        ws,
                        session,
                        {
                            "type": "hermes.confirmation.forwarded",
                            "confirmation_id": _str_option(payload, "confirmation_id"),
                            "answer": _str_option(payload, "answer"),
                        },
                    )
                elif msg_type == CLIENT_MSG_SESSION_CLOSE:
                    await ws.close()
                else:
                    await self._send_error(ws, session, f"unsupported message type: {msg_type}")
                if provider_task.done():
                    break
        finally:
            if not provider_task.done():
                provider_task.cancel()
            await connection.close()
            self._log(session, "voice.realtime_agent.session.closed")
        return ws

    def _native_session_config(
        self,
        session: RealtimeAgentSession,
    ) -> RealtimeAgentSessionConfig:
        return RealtimeAgentSessionConfig(
            provider=session.provider,
            model=session.model,
            voice=session.voice,
            sample_rate=session.sample_rate,
            profile=session.profile,
            hermes_session_id=session.chat_session_id,
            instructions=_native_instructions(session),
            provider_options=self._provider_options(session, {}),
            tools=HERMES_TOOL_SCHEMAS,
        )

    async def _pump_provider_events(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        playback_drained: asyncio.Event,
    ) -> None:
        async for event in connection.events():
            if event.kind == ProviderEventKind.READY:
                continue
            if event.kind == ProviderEventKind.INPUT_TRANSCRIPT_DELTA:
                await self._send(
                    ws,
                    session,
                    {
                        "type": SERVER_EVT_INPUT_TRANSCRIPT_DELTA,
                        "delta": str(event.payload.get("delta") or ""),
                    },
                )
            elif event.kind == ProviderEventKind.INPUT_TRANSCRIPT_FINAL:
                await self._send(
                    ws,
                    session,
                    {
                        "type": SERVER_EVT_INPUT_TRANSCRIPT_FINAL,
                        "text": str(event.payload.get("text") or ""),
                    },
                )
            elif event.kind == ProviderEventKind.RESPONSE_STARTED:
                await self._send(
                    ws,
                    session,
                    {
                        "type": SERVER_EVT_RESPONSE_STARTED,
                        "provider": session.provider,
                        "model": session.model,
                        "voice": session.voice,
                        "session_id": session.session_id,
                        "chat_session_id": session.chat_session_id,
                        "response_id": event.response_id,
                    },
                )
            elif event.kind == ProviderEventKind.OUTPUT_TEXT_DELTA:
                await self._send(
                    ws,
                    session,
                    {
                        "type": SERVER_EVT_RESPONSE_DELTA,
                        "source": "provider",
                        "delta": str(event.payload.get("delta") or ""),
                        "response_id": event.response_id,
                    },
                )
            elif event.kind == ProviderEventKind.AUDIO_DELTA:
                await self._send_provider_audio_delta(ws, session, event)
            elif event.kind == ProviderEventKind.AUDIO_DONE:
                await self._send(
                    ws,
                    session,
                    {
                        "type": SERVER_EVT_OUTPUT_AUDIO_DONE,
                        "response_id": event.response_id,
                    },
                )
            elif event.kind == ProviderEventKind.FUNCTION_CALL_COMPLETED:
                await self._handle_provider_tool_call(
                    ws,
                    session,
                    connection,
                    playback_drained,
                    event,
                )
            elif event.kind == ProviderEventKind.RESPONSE_DONE:
                if self._is_intermediate_tool_response_done(session, event):
                    continue
                await self._send(
                    ws,
                    session,
                    {
                        "type": SERVER_EVT_RESPONSE_DONE,
                        "provider": session.provider,
                        "model": session.model,
                        "voice": session.voice,
                        "event_log_path": str(session.event_log_path),
                        "chat_session_id": session.chat_session_id,
                        "response_id": event.response_id,
                    },
                )
            elif event.kind == ProviderEventKind.ERROR:
                await self._send_error(
                    ws,
                    session,
                    str(event.payload.get("message") or "provider error"),
                    provider=session.provider,
                )

    async def _send_provider_audio_delta(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> None:
        chunk = event.payload.get("audio")
        audio64 = event.payload.get("audio_base64")
        if isinstance(chunk, bytes):
            audio_bytes = chunk
            encoded = (
                audio64
                if isinstance(audio64, str) and audio64
                else base64.b64encode(audio_bytes).decode("ascii")
            )
        elif isinstance(audio64, str) and audio64:
            try:
                audio_bytes = base64.b64decode(audio64)
            except Exception:
                await self._send_error(ws, session, "provider audio delta was invalid")
                return
            encoded = audio64
        else:
            await self._send_error(ws, session, "provider audio delta missing audio")
            return
        peak, rms = _pcm_levels(audio_bytes)
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_OUTPUT_AUDIO_DELTA,
                "audio_base64": encoded,
                "byte_count": len(audio_bytes),
                "sample_rate": session.sample_rate,
                "channels": DEFAULT_CHANNELS,
                "sample_width": DEFAULT_SAMPLE_WIDTH,
                "peak_level": peak,
                "rms_level": rms,
                "response_id": event.response_id,
            },
        )

    async def _handle_provider_tool_call(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        playback_drained: asyncio.Event,
        event: ProviderEvent,
    ) -> None:
        call = event.payload.get("call")
        if not isinstance(call, ToolCallEvent):
            call = ToolCallEvent(
                call_id=str(event.payload.get("call_id") or ""),
                name=str(event.payload.get("name") or ""),
                arguments=dict(event.payload.get("arguments") or {}),
            )
        if call.is_hermes_tool() and event.response_id:
            session.response_ids_awaiting_tool_followup.add(event.response_id)
        result = await self._run_brokered_tool(ws, session, call)
        await connection.send_tool_result(call.call_id, result)
        if call.name == "hermes_run_task" and result.get("cancelled"):
            return
        playback_drained.clear()
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_PLAYBACK_DRAIN_REQUESTED,
                "call_id": call.call_id,
                "tool_name": call.name,
                "timeout_ms": int(_PLAYBACK_DRAIN_TIMEOUT_SECONDS * 1000),
            },
        )
        try:
            await asyncio.wait_for(
                playback_drained.wait(),
                timeout=_PLAYBACK_DRAIN_TIMEOUT_SECONDS,
            )
        except asyncio.TimeoutError:
            await self._send(
                ws,
                session,
                {
                    "type": "voice.playback_drain.timeout",
                    "call_id": call.call_id,
                    "timeout_ms": int(_PLAYBACK_DRAIN_TIMEOUT_SECONDS * 1000),
                },
            )
        await connection.request_response()

    def _is_intermediate_tool_response_done(
        self,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> bool:
        response_id = str(event.response_id or "").strip()
        if not response_id:
            return False
        if response_id not in session.response_ids_awaiting_tool_followup:
            return False
        session.response_ids_awaiting_tool_followup.discard(response_id)
        self._log(
            session,
            "voice.response.intermediate_done",
            {
                "type": "voice.response.intermediate_done",
                "response_id": response_id,
                "reason": "tool_followup_pending",
            },
        )
        return True

    async def _run_brokered_tool(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        call: ToolCallEvent,
    ) -> dict[str, Any]:
        if call.name != "hermes_run_task":
            return await self._execute_brokered_tool(ws, session, call)

        task = asyncio.create_task(self._execute_brokered_tool(ws, session, call))
        session.hermes_task = task
        try:
            return await task
        except asyncio.CancelledError:
            session.hermes_run_status = "cancelled"
            return {
                "ok": False,
                "cancelled": True,
                "run_id": session.hermes_run_id,
                "session_id": session.chat_session_id,
                "interface": _interface_context(session),
            }
        finally:
            if session.hermes_task is task:
                session.hermes_task = None

    async def _execute_brokered_tool(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        call: ToolCallEvent,
    ) -> dict[str, Any]:
        if not call.is_hermes_tool():
            return {
                "ok": False,
                "error": f"tool is not allowed: {call.name}",
                "allowed_tools": list(_TOOL_SURFACE),
            }
        interface_context = _interface_context(session)
        if call.name == "hermes_get_status":
            run_id = str(call.arguments.get("run_id") or session.hermes_run_id or "").strip()
            return {
                "ok": True,
                "run_id": run_id or None,
                "status": session.hermes_run_status,
                "session_id": session.chat_session_id,
                "pending_confirmation_id": session.pending_confirmation_id,
                "interface": interface_context,
            }
        if call.name == "hermes_cancel":
            run_id = str(call.arguments.get("run_id") or session.hermes_run_id or "").strip()
            self._cancel_active_hermes(session)
            await self._send(
                ws,
                session,
                {
                    "type": "hermes.run.cancelled",
                    "session_id": session.chat_session_id,
                    "run_id": run_id or session.hermes_run_id,
                },
            )
            return {
                "ok": True,
                "run_id": run_id or session.hermes_run_id,
                "status": session.hermes_run_status,
                "session_id": session.chat_session_id,
                "interface": interface_context,
            }
        if call.name == "hermes_confirm":
            confirmation_id = str(call.arguments.get("confirmation_id") or "").strip()
            answer = str(call.arguments.get("answer") or "").strip()
            if not confirmation_id or not answer:
                return {"ok": False, "error": "hermes_confirm requires confirmation_id and answer"}
            session.pending_confirmation_id = None
            await self._send(
                ws,
                session,
                {
                    "type": "hermes.confirmation.forwarded",
                    "confirmation_id": confirmation_id,
                    "answer": answer,
                },
            )
            return {
                "ok": True,
                "confirmation_id": confirmation_id,
                "status": "forwarded_to_hermes_ui",
                "interface": interface_context,
            }
        if call.name != "hermes_run_task":
            return {"ok": False, "error": f"unsupported Hermes tool: {call.name}"}

        text = str(call.arguments.get("text") or "").strip()
        if not text:
            return {"ok": False, "error": "hermes_run_task requires text"}
        profile = str(call.arguments.get("profile") or session.profile or "").strip() or None
        chat_session_id = (
            str(call.arguments.get("session_id") or session.chat_session_id or "").strip()
            or None
        )
        final_parts: list[str] = []
        error_message: str | None = None
        session.cancel_requested = False
        session.hermes_run_status = "running"
        async for hermes_event in self.hermes.stream_task(
            HermesTaskRequest(
                text=text[:5000],
                profile=profile,
                session_id=chat_session_id,
                bearer_token=session.bearer_token,
                interface_context=interface_context,
            )
        ):
            if hermes_event.get("type") == "hermes.session.bound":
                bound = str(hermes_event.get("session_id") or "").strip()
                if bound:
                    session.chat_session_id = bound
            run_id = str(hermes_event.get("run_id") or "").strip()
            if run_id:
                session.hermes_run_id = run_id
            if hermes_event.get("type") == "hermes.run.started":
                session.hermes_run_status = "running"
            elif hermes_event.get("type") == "hermes.run.completed":
                session.hermes_run_status = "completed"
            elif hermes_event.get("type") == "hermes.confirmation.requested":
                confirmation_id = str(hermes_event.get("confirmation_id") or "").strip()
                session.pending_confirmation_id = confirmation_id or session.pending_confirmation_id
                session.hermes_run_status = "waiting_for_confirmation"
            if hermes_event.get("type") == SERVER_EVT_RESPONSE_DELTA:
                final_parts.append(str(hermes_event.get("delta") or ""))
            elif hermes_event.get("type") == "voice.response.turn_completed":
                content = str(hermes_event.get("content") or "")
                if content and not final_parts:
                    final_parts.append(content)
            elif hermes_event.get("type") == "voice.error":
                error_message = str(hermes_event.get("message") or "Hermes error")
            await self._send(ws, session, _event_with_hermes_source(hermes_event))
            if session.cancel_requested:
                session.hermes_run_status = "cancelled"
                return {
                    "ok": False,
                    "cancelled": True,
                    "run_id": session.hermes_run_id,
                    "session_id": session.chat_session_id,
                    "interface": interface_context,
                }

        if error_message:
            session.hermes_run_status = "error"
            return {
                "ok": False,
                "error": error_message,
                "run_id": session.hermes_run_id,
                "session_id": session.chat_session_id,
                "interface": interface_context,
            }
        final_text = "".join(final_parts).strip()
        if session.hermes_run_status == "running":
            session.hermes_run_status = "completed"
        return {
            "ok": True,
            "run_id": session.hermes_run_id,
            "session_id": session.chat_session_id,
            "profile": profile,
            "text": final_text[:4000],
            "interface": interface_context,
            "spoken_response": "provider_generated_after_hermes_result",
        }

    def _cancel_active_hermes(self, session: RealtimeAgentSession) -> None:
        session.cancel_requested = True
        session.hermes_run_status = "cancelled"
        task = session.hermes_task
        if task is None or task.done():
            return
        try:
            current = asyncio.current_task()
        except RuntimeError:
            current = None
        if task is not current:
            task.cancel()

    @property
    def enabled(self) -> bool:
        if self.config.realtime_voice_enabled:
            return True
        return os.getenv("RELAY_REALTIME_VOICE_ENABLED", "").strip().lower() in _TRUE_ENV_VALUES

    def config_payload(self, profile: str | None = None) -> dict[str, Any]:
        settings = realtime_voice_settings(self.config, profile)
        providers = [
            self._realtime_agent_provider_payload(info.to_dict())
            for info in self.registry.provider_infos()
        ]
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
            "auth": _realtime_provider_auth_status(self.config),
            "profile": settings["profile"],
            "config_scope": settings["config_scope"],
            "fallback_to_global": settings["fallback_to_global"],
            "tool_surface": list(_TOOL_SURFACE),
            "limits": [
                "Hermes owns tools, confirmations, memory, and transcript state.",
                "Native realtime providers receive relay-brokered mic PCM and only the approved Hermes function surface.",
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
        provider = self._realtime_agent_provider_payload(info.to_dict())
        dynamic = await asyncio.to_thread(
            fetch_realtime_provider_options,
            provider_id,
            xai_auth=_xai_option_auth(self.config),
        )
        merge_provider_options(provider, dynamic.get("provider", {}))
        provider = self._realtime_agent_provider_payload(provider)
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

    def _realtime_agent_provider_payload(self, provider: dict[str, Any]) -> dict[str, Any]:
        provider = dict(provider)
        provider_id = str(provider.get("id") or "")
        native = provider_id in self.native_providers
        provider["supports_realtime_agent_native"] = native
        if native:
            provider["supports_realtime"] = True
            provider["supports_speech_to_speech"] = True
            provider["supports_tool_use"] = True
        return provider

    async def _handle_input_audio(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        payload: dict[str, Any],
        previous_bytes: int,
    ) -> tuple[int, int]:
        decoded = await self._decode_input_audio_payload(ws, session, payload)
        if decoded is None:
            return 0, DEFAULT_SAMPLE_RATE
        chunk, sample_rate = decoded
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_INPUT_AUDIO_RECEIVED,
                "byte_count": len(chunk),
                "total_bytes": previous_bytes + len(chunk),
                "sample_rate": sample_rate,
            },
        )
        return len(chunk), sample_rate

    async def _decode_input_audio_payload(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        payload: dict[str, Any],
    ) -> tuple[bytes, int] | None:
        audio64 = str(payload.get("audio_base64", "") or "").strip()
        if not audio64:
            await self._send_error(ws, session, "input_audio.append missing audio_base64")
            return None
        try:
            chunk = base64.b64decode(audio64)
        except Exception:
            await self._send_error(ws, session, "input_audio.append audio_base64 is invalid")
            return None
        sample_rate = _int_option(payload, "sample_rate", DEFAULT_SAMPLE_RATE)
        return chunk, sample_rate

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
                "render-after-Hermes compatibility mode requires client transcript text",
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
                interface_context=_interface_context(session),
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
        if provider == "stub" or provider in self.native_providers:
            return
        raise web.HTTPBadRequest(
            text=f"{provider} is not a native realtime-agent provider"
        )

    def _validate_realtime_provider(self, provider: str):
        try:
            info = self.registry.info(provider)
        except KeyError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc
        if provider == "stub" or provider in self.native_providers:
            return info
        if not info.supports_realtime and provider != "stub":
            raise web.HTTPBadRequest(text=f"{provider} is not a realtime voice provider")
        raise web.HTTPBadRequest(
            text=f"{provider} is not a native realtime-agent provider"
        )

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
            "interface": _interface_context(session),
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


def _native_instructions(session: RealtimeAgentSession) -> str:
    profile = session.profile or "default"
    interface_context = _interface_context(session)
    engine_label = interface_context["engine_label"]
    stable_label = interface_context["stable_engine_label"]
    current_date = interface_context["current_date"]
    current_time = interface_context["current_time"]
    current_timezone = interface_context["current_timezone"]
    return (
        "You are the provider-native speech loop for Hermes Relay. Keep replies "
        "brief and conversational. Active interface: "
        f"{engine_label} ({interface_context['engine']}) through Android Voice Mode, "
        f"provider={interface_context['provider']}, model={interface_context['model']}, "
        f"voice={interface_context['voice']}. This is not the stable {stable_label} "
        f"path. Current relay date/time: {current_date} {current_time} "
        f"{current_timezone}. If the user asks for today's date or current time, "
        "answer from this relay-local context; do not infer it from model "
        "training data. "
        "If the user asks which mode, path, or interface is active, answer "
        "plainly from this context and explain that the active realtime provider "
        "owns speech recognition and speech output while Hermes remains the "
        "authority for tools, "
        "memory, profile context, confirmations, and persistence. For any request "
        "that needs memory, profile context, app/desktop/phone tools, confirmations, "
        "durable chat state, research, checks, current facts, news, external data, "
        "or any information not present in this prompt/session context, call "
        "hermes_run_task instead of answering directly. If Hermes cannot verify "
        "the requested information, say that briefly instead of guessing. "
        "Route through Hermes for latest/recent/versioned data, device/desktop/app "
        "state, personal/session/project context, side effects, high-stakes or "
        "precision-sensitive answers, explicit check/verify/look-up requests, and "
        "media, files, screenshots, attachments, or artifacts. Answer directly only "
        "for small talk, timeless facts, basic reasoning/math, wording help, or "
        "questions fully contained in the current utterance/session context. "
        "When Hermes returns tool output, speak a natural concise summary; do not "
        "read raw tool output aloud. Format speech for listening: say dates, "
        "times, currency, percentages, versions, measurements, and counts in "
        "natural spoken form. Summarize long IDs, hashes, UUIDs, URLs, file paths, "
        "JSON, logs, stack traces, tables, and dense numeric strings instead of "
        "reading them character by character; include exact raw values only when "
        "short and important. If raw values are not useful to hear, say a brief "
        "label such as 'plus a few IDs and raw values' and summarize the meaning. "
        f"Active profile: {profile}. Do not use non-Hermes web, search, social, "
        "MCP, or built-in provider tools."
    )


def _event_with_hermes_source(event: dict[str, Any]) -> dict[str, Any]:
    event_type = str(event.get("type") or "")
    if event_type.startswith("hermes.") or event_type == SERVER_EVT_RESPONSE_DELTA:
        out = dict(event)
        out.setdefault("source", "hermes")
        return out
    return event


def _interface_context(session: RealtimeAgentSession) -> dict[str, Any]:
    return {
        "client_surface": "android_voice_mode",
        "engine": "realtime_agent",
        "engine_label": "Realtime Agent",
        "stable_engine": "hermes_voice_output",
        "stable_engine_label": "Hermes chat + voice output",
        "provider": session.provider,
        "model": session.model,
        "voice": session.voice,
        "profile": session.profile or "default",
        "chat_session_id": session.chat_session_id,
        "config_scope": session.config_scope,
        "path_summary": (
            "Android mic PCM -> relay provider-native realtime session -> "
            "Hermes brokered tools when needed -> Android PCM playback"
        ),
        **_relay_time_context(),
    }


def _relay_time_context() -> dict[str, str]:
    now = datetime.now().astimezone()
    offset = now.strftime("%z")
    if offset:
        offset = f"{offset[:3]}:{offset[3:]}"
    zone = now.tzname() or "local"
    timezone = f"{zone} (UTC{offset})" if offset else zone
    return {
        "current_date": now.date().isoformat(),
        "current_time": now.strftime("%H:%M:%S"),
        "current_timezone": timezone,
        "current_datetime": now.isoformat(timespec="seconds"),
    }


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


def _hermes_broker_bearer_token(
    principal: AuthPrincipal,
    *,
    request_bearer: str,
    config: RelayConfig,
) -> str | None:
    if principal.kind == "hermes_api":
        return request_bearer or None
    token = hermes_api_server_key(config)
    if token is None:
        logger.info(
            "Realtime-agent Hermes broker has no relay-side Hermes API bearer; "
            "continuing without Authorization for open/local WebAPI targets"
        )
    return token


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


def _realtime_provider_auth_status(config: RelayConfig) -> dict[str, Any]:
    status = _xai_auth_status(config)
    status["xai_oauth"] = bool(status.get("xai_oauth_configured"))
    xai_env_names = _configured_env_names(
        (
            "VOICE_TOOLS_XAI_KEY",
            "XAI_API_KEY",
            "GROK_API_KEY",
            "XAI_REALTIME_CLIENT_SECRET",
            "XAI_EPHEMERAL_TOKEN",
            "VOICE_LAB_XAI_OAUTH_ACCESS_TOKEN",
        )
    )
    openai_env_names = _configured_env_names(
        (
            "OPENAI_REALTIME_API_KEY",
            "OPENAI_API_KEY",
            "VOICE_TOOLS_OPENAI_KEY",
        )
    )
    status["xai_env"] = bool(xai_env_names)
    status["xai_env_names"] = xai_env_names
    status["openai_env"] = bool(openai_env_names)
    status["openai_env_names"] = openai_env_names
    return status


def _configured_env_names(names: tuple[str, ...]) -> list[str]:
    load_voice_lab_env_file()
    return [name for name in names if os.getenv(name, "").strip()]


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
