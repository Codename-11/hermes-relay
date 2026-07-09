"""Experimental realtime voice agent route handler.

This surface is intentionally separate from ``/voice/realtime/*``. The old
route remains a provider lab; this route binds a realtime provider renderer to
the Hermes session/tool loop.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
from collections import deque
import hashlib
import hmac
import json
import logging
import os
import secrets
import struct
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web

from ...voice_lab.auth import load_voice_lab_env_file
from ...voice_lab.providers.base import ProviderRunError, ProviderUnavailable
from ...voice_lab.registry import default_registry

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
from .floor import FloorMouth, RealtimeFloor
from .hermes_tool_broker import HermesTaskRequest, HermesToolBroker
from .models import (
    CLIENT_MSG_HERMES_CONFIRM,
    CLIENT_MSG_INPUT_AUDIO_APPEND,
    CLIENT_MSG_INPUT_AUDIO_CLEAR,
    CLIENT_MSG_INPUT_AUDIO_COMMIT,
    CLIENT_MSG_CLIENT_ACK,
    CLIENT_MSG_PLAYBACK_DRAINED,
    CLIENT_MSG_RESPONSE_CREATE,
    CLIENT_MSG_RESPONSE_CANCEL,
    CLIENT_MSG_RESULT_RESPEAK,
    CLIENT_MSG_SESSION_CLOSE,
    CLIENT_MSG_SESSION_RESUME,
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
    SERVER_EVT_REPLAY_DONE,
    SERVER_EVT_REPLAY_STARTED,
    SERVER_EVT_RESPONSE_DELTA,
    SERVER_EVT_RESPONSE_DONE,
    SERVER_EVT_RESPONSE_STARTED,
    SERVER_EVT_HERMES_RUN_PROGRESS,
    SERVER_EVT_HERMES_RUN_PROMOTED,
    SERVER_EVT_HERMES_RUN_BACKGROUND_COMPLETED,
    SERVER_EVT_SESSION_DETACHED,
    SERVER_EVT_SESSION_READY,
    SERVER_EVT_SESSION_RESUMED,
    SERVER_EVT_SESSION_RESUME_FAILED,
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
_PRE_HERMES_STATUS_LEAD_SECONDS = 0.75
_HERMES_PROGRESS_INTERVAL_SECONDS = 5.0
_HERMES_SPOKEN_PROGRESS_AFTER_SECONDS = 15.0
# Calmer cadence: only re-speak the SAME high-level status this far apart, and
# only when the coarse status actually changed (see _should_repeat_spoken_status).
_HERMES_SPOKEN_PROGRESS_REPEAT_SECONDS = 90.0
_RESUME_TTL_SECONDS = 30.0
# Max time a completed background result waits for the floor to clear before it
# is spoken anyway (ADR 33 Tier B result delivery).
_BACKGROUND_FLOOR_WAIT_SECONDS = 12.0
_EVENT_RING_LIMIT = 256
_AUDIO_RING_LIMIT = 96
# A promoted/durable background run can outlive the 30s resume window. While such
# a run is still in flight we keep a *detached* session (and its recorded event
# ring) alive up to this hard cap, so a transient network drop doesn't orphan the
# run — the client can resume within the window and replay the recorded result.
# Bounded so an abandoned session can't pin the provider connection open forever.
_BACKGROUND_DETACHED_MAX_SECONDS = 360.0
# Hard ceiling on a single background run: a hung tool (e.g. a stuck cron call)
# is cancelled and surfaced as a timeout instead of waiting forever.
_BACKGROUND_RUN_MAX_SECONDS = 300.0
# When a known-long tool starts mid-grace-window, the run still gets this short
# quick-finish window before promoting — a long-CLASS tool that completes fast
# (a quick desktop lookup) stays Tier A instead of paying the promote/summarize
# round-trip.
_LONG_TOOL_QUICK_FINISH_SECONDS = 1.5
# Poll cadence for the detached-session keep-alive loop.
_BACKGROUND_DETACHED_POLL_SECONDS = 2.0
# xAI ends a realtime conversation after 900s of true conversation inactivity.
# Four live probe runs on 2026-07-08 showed that neither uncommitted silent PCM
# nor server-acknowledged session.update messages reset that timer. Treat that
# close as routine provider-session expiry: close the Android websocket cleanly
# while idle, and let the next user turn open a fresh provider conversation
# seeded from the synced Hermes session.
_PROVIDER_IDLE_CLOSE_WS_REASON = "provider idle timeout"
# Forced-summary early commit: once the buffered summary prefix is at least
# this long, passes the bad-phrase check, AND shows content overlap with the
# Hermes answer, the buffer flushes and the rest streams live — the user
# hears the answer as it generates instead of after it completes.
_FORCED_SUMMARY_EARLY_COMMIT_MIN_CHARS = 40
# Delivered-or-alarm: a background result must produce a finished (or
# committed-streaming) spoken summary within this window, else the answer is
# force-emitted as text so it can never be silently lost.
_DELIVERY_CONFIRM_SECONDS = 30.0
# A delivery must not speak over the USER: live mic chunks stream in as
# input_audio.append while they talk, so "user quiet for this long" gates
# result delivery alongside the floor (observed live: a task finishing
# mid-utterance ended the user's recording).
_DELIVERY_INPUT_QUIET_SECONDS = 1.5
# Background task queue: a long second ask while the slot is busy is queued
# (FIFO) instead of refused; bounded so the model can't pile up unbounded work.
_BACKGROUND_QUEUE_MAX = 3
_PROFILE_SOUL_PROMPT_MAX_CHARS = 6000
_PROFILE_MEMORY_PROMPT_MAX_FILES = 4
_PROFILE_MEMORY_PROMPT_MAX_CHARS = 6000
_PROFILE_MEMORY_FILE_PROMPT_MAX_CHARS = 2000
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
    resume_token_hash: str
    auth_session_device_id: str | None = None
    auth_token_hash: str | None = None
    context_messages: tuple[dict[str, str], ...] = ()
    resume_ttl_seconds: float = _RESUME_TTL_SECONDS
    resume_deadline: float | None = None
    attached_ws: web.WebSocketResponse | None = None
    detached_at: float | None = None
    closed: bool = False
    explicit_close_requested: bool = False
    event_seq: int = 0
    audio_seq: int = 0
    input_chunk_seq: int = 0
    event_ring: deque[dict[str, Any]] = field(
        default_factory=lambda: deque(maxlen=_EVENT_RING_LIMIT)
    )
    audio_ring: deque[dict[str, Any]] = field(
        default_factory=lambda: deque(maxlen=_AUDIO_RING_LIMIT)
    )
    acked_event_id_by_client: int = 0
    acked_audio_event_id_by_client: int = 0
    played_audio_event_id_by_client: int = 0
    acked_input_chunk_id_by_client: int = 0
    native_connection: RealtimeAgentConnection | None = None
    native_provider_task: asyncio.Task[None] | None = None
    native_playback_drained: asyncio.Event | None = None
    native_close_task: asyncio.Task[None] | None = None
    native_provider_idle_closed: bool = False
    native_input_audio_bytes: int = 0
    native_response_requested_for_input: bool = False
    native_forced_preamble_active: bool = False
    native_forced_preamble_transcript: str | None = None
    native_forced_preamble_response_id: str | None = None
    native_forced_preamble_audio_seen: bool = False
    native_forced_hermes_turn_active: bool = False
    native_forced_summary_active: bool = False
    native_forced_summary_done: bool = False
    native_forced_summary_response_id: str | None = None
    native_forced_summary_result: dict[str, Any] | None = None
    native_forced_summary_buffer: list[dict[str, Any]] = field(default_factory=list)
    native_forced_summary_text_parts: list[str] = field(default_factory=list)
    # True once the buffered summary prefix passed early validation and was
    # flushed — the rest of that response streams live (no more buffering)
    # and the end-of-response validation is skipped (audio already played).
    native_forced_summary_committed: bool = False
    # Last background result that reached the delivery step — kept so the
    # client can request a respeak (DONE-chip tap) and the delivery-confirm
    # watcher can force a text emit if the spoken summary never lands.
    last_background_result: dict[str, Any] | None = None
    # FIFO of queued background tasks ({"text","profile"}), started one at a
    # time as the active run's delivery settles. Cleared on cancel.
    background_queue: deque[dict[str, Any]] = field(default_factory=deque)
    # Reused Hermes side-session for fast-lane asks (created on the first
    # fast-lane run) so quick inline answers don't litter one session each.
    fast_lane_session_id: str | None = None
    # Set when a background result was delivered OUTSIDE the provider's own
    # conversation (fallback TTS / text-only emit) — the provider never saw
    # that delivery, so its history still reads "running in background" and
    # it will claim the task is unfinished (observed live). Attached to the
    # NEXT user turn's per-response instructions, then cleared.
    native_pending_delivery_note: str | None = None
    # time.monotonic() of the last LIVE mic chunk from the client — while
    # this is fresh the user is mid-utterance and deliveries must hold.
    native_last_input_audio_at: float = 0.0
    native_hermes_required_transcript: str | None = None
    native_hermes_required_reason: str | None = None
    # Model id the provider reported actually serving (session.created echo);
    # aliases like grok-voice-latest resolve server-side and can move.
    native_resolved_model: str | None = None
    hermes_run_id: str | None = None
    hermes_run_status: str = "idle"
    hermes_run_tier: str = "foreground"
    hermes_answer_started: bool = False
    pending_confirmation_id: str | None = None
    cancel_requested: bool = False
    hermes_task: asyncio.Task[dict[str, Any]] | None = None
    # ADR 33 promotion state (populated from realtime_voice settings at create).
    promotion_enabled: bool = False
    promote_after_ms: int = 6000
    spoken_handoff: bool = True
    result_delivery: str = "speak_verbatim"
    promoted_transcript: str | None = None
    background_delivery_task: asyncio.Task[None] | None = None
    response_ids_awaiting_tool_followup: set[str] = field(default_factory=set)
    response_ids_started: set[str] = field(default_factory=set)
    provider_response_audio_seen: set[str] = field(default_factory=set)
    hermes_active_tool_call_id: str | None = None
    hermes_active_tool_name: str | None = None
    hermes_last_tool_name: str | None = None
    hermes_last_tool_status: str | None = None
    hermes_last_tool_message: str | None = None
    hermes_completed_tool_count: int = 0
    hermes_seen_tool_call_ids: set[str] = field(default_factory=set)
    hermes_last_spoken_progress_at: float = 0.0
    hermes_last_spoken_progress_key: str | None = None
    # Spoken-progress knobs, wired from realtime_voice settings at create.
    # <= 0 disables timer-driven spoken filler entirely (milestone speech —
    # promotion handoff, completion, failure — is unaffected).
    progress_spoken_after_seconds: float = 0.0
    progress_repeat_seconds: float = _HERMES_SPOKEN_PROGRESS_REPEAT_SECONDS
    # A completed background result that could not be spoken because the phone
    # was detached. Injected on resume; pushed via the proactive fallback if
    # the session closes first.
    pending_background_result: dict[str, Any] | None = None
    # Set by the Hermes event stream when a known-long tool starts, so a
    # hermes_run_task can promote to background immediately instead of
    # waiting out the full grace window. Fresh per hermes_run_task call.
    long_tool_event: asyncio.Event | None = None
    profile_prompt_context: dict[str, Any] = field(default_factory=dict)
    floor: RealtimeFloor = field(default_factory=RealtimeFloor)


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
        # Optional durable-fallback hook (wired by the relay server to
        # ProactiveChannel.push, which buffers for an offline phone): a
        # completed background result whose session died undelivered is pushed
        # as a proactive phone message instead of silently dropped.
        self.proactive_push: Callable[[dict[str, Any]], Awaitable[dict[str, Any]]] | None = None

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
        broker_bearer_token = _hermes_broker_bearer_token(
            principal,
            request_bearer=bearer_token,
            config=self.config,
        )
        context_messages = _parse_context_messages(payload.get("context_messages"))
        fetch_context_messages = getattr(self.hermes, "fetch_context_messages", None)
        if not context_messages and chat_session_id and callable(fetch_context_messages):
            context_messages = await self.hermes.fetch_context_messages(
                session_id=chat_session_id,
                bearer_token=broker_bearer_token,
                limit=14,
            )
        session_id = secrets.token_urlsafe(18)
        resume_token, resume_token_hash = _new_resume_token()
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
            bearer_token=broker_bearer_token,
            created_at=time.time(),
            event_log_path=event_log_path,
            resume_token_hash=resume_token_hash,
            resume_ttl_seconds=_configured_resume_ttl_seconds(),
            auth_session_device_id=(
                principal.session.device_id if principal.session is not None else None
            ),
            auth_token_hash=_token_hash(bearer_token),
            context_messages=context_messages,
            profile_prompt_context=_profile_prompt_context(
                self.config,
                str(settings.get("profile") or profile or "default"),
            ),
            promotion_enabled=bool(settings.get("promotion_enabled", False)),
            promote_after_ms=int(settings.get("promote_after_ms", 6000)),
            spoken_handoff=bool(settings.get("spoken_handoff", True)),
            result_delivery=str(settings.get("result_delivery", "speak_verbatim")),
            progress_spoken_after_seconds=(
                max(0, int(settings.get("progress_spoken_after_ms", 0))) / 1000.0
            ),
            progress_repeat_seconds=(
                max(0, int(settings.get("progress_repeat_ms", 30000))) / 1000.0
            ),
        )
        self.sessions[session_id] = session
        self._log(session, "voice.realtime_agent.session.created")

        return web.json_response(
            {
                "success": True,
                "session_id": session_id,
                "websocket_path": f"/voice/realtime-agent/{session_id}",
                "resume_token": resume_token,
                "resume_supported": True,
                "resume_ttl_ms": int(session.resume_ttl_seconds * 1000),
                "provider": provider,
                "model": model,
                "voice": voice,
                "sample_rate": sample_rate,
                "event_log_path": str(event_log_path),
                "protocol": "hermes.voice.realtime_agent.v0",
                "profile": session.profile,
                "chat_session_id": session.chat_session_id,
                "context_message_count": len(session.context_messages),
                "config_scope": session.config_scope,
                "config_path": str(session.config_path) if session.config_path else None,
                "fallback_to_global": bool(settings["fallback_to_global"]),
                "experimental": True,
                "tool_surface": list(_TOOL_SURFACE),
            }
        )

    async def _handle_common_client_message(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        msg_type: str,
        payload: dict[str, Any],
    ) -> bool:
        """Dispatch client→server messages whose semantics are identical across
        the native and non-native message loops.

        Returns ``True`` if the message was handled here (the caller must skip
        its own provider-specific branches), ``False`` if the loop must handle
        it.

        Keeping these branches in ONE place is deliberate. The two loops drifted
        once: the non-native loop silently lacked a ``playback.drained`` branch,
        so a normal end-of-turn ack fell through to the unsupported-message
        error and tore the session down. Anything genuinely identical for both
        transports lives here so it cannot drift again.
        """
        if msg_type == CLIENT_MSG_SESSION_START:
            if _str_option(payload, "resume_token") or session.detached_at is not None:
                await self._handle_resume_message(ws, session, payload)
            else:
                await self._send(ws, session, self._ready_event(session))
            return True
        if msg_type == CLIENT_MSG_SESSION_RESUME:
            await self._handle_resume_message(ws, session, payload)
            return True
        if msg_type == CLIENT_MSG_CLIENT_ACK:
            self._handle_client_ack(session, payload)
            return True
        if msg_type == CLIENT_MSG_PLAYBACK_DRAINED:
            # Playback ack. The native loop additionally releases the provider
            # backpressure event; it is ``None`` on the non-native path, so this
            # is a plain cursor ack there.
            self._handle_client_ack(session, payload)
            if session.native_playback_drained is not None:
                session.native_playback_drained.set()
            return True
        if msg_type == CLIENT_MSG_HERMES_CONFIRM:
            # Echo-only forward in BOTH paths today: the realtime model answers
            # confirmations via the hermes_confirm tool, which itself returns
            # status "forwarded_to_hermes_ui". This client message is the UI
            # affordance that surfaces the same answer. Not a divergence.
            await self._send(
                ws,
                session,
                {
                    "type": "hermes.confirmation.forwarded",
                    "confirmation_id": _str_option(payload, "confirmation_id"),
                    "answer": _str_option(payload, "answer"),
                },
            )
            return True
        return False

    async def handle_ws(self, request: web.Request) -> web.StreamResponse:
        if not self.enabled:
            raise web.HTTPNotFound(text="realtime agent voice is disabled")
        principal = await require_voice_auth(request, "voice:realtime")

        session_id = request.match_info.get("session_id", "")
        session = self.sessions.get(session_id)
        if session is None:
            raise web.HTTPNotFound(text="unknown realtime agent session")
        if session.closed:
            raise web.HTTPGone(text="realtime agent session is closed")
        if not self._auth_matches_session(session, principal, _bearer_from_request(request)):
            raise web.HTTPForbidden(text="realtime agent session belongs to another principal")

        if session.provider in self.native_providers:
            return await self._handle_provider_native_ws(request, session)

        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=2 * 1024 * 1024)
        await ws.prepare(request)
        session.attached_ws = ws
        session.detached_at = None
        session.resume_deadline = None
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
                if await self._handle_common_client_message(ws, session, msg_type, payload):
                    continue
                if msg_type == CLIENT_MSG_INPUT_AUDIO_APPEND:
                    byte_count, input_sample_rate = await self._handle_input_audio(
                        ws,
                        session,
                        payload,
                        input_audio_bytes,
                    )
                    input_audio_bytes += byte_count
                elif msg_type == CLIENT_MSG_INPUT_AUDIO_CLEAR:
                    # No provider connection on the non-native path; reset the
                    # accumulated input so a re-record starts clean.
                    input_audio_bytes = 0
                elif msg_type in {CLIENT_MSG_INPUT_AUDIO_COMMIT, CLIENT_MSG_RESPONSE_CREATE}:
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
                elif msg_type == CLIENT_MSG_RESPONSE_CANCEL:
                    if running is not None and not running.done():
                        running.cancel()
                    await self._send(
                        ws,
                        session,
                        {"type": "hermes.run.cancelled", "session_id": session.chat_session_id},
                    )
                elif msg_type == CLIENT_MSG_SESSION_CLOSE:
                    session.explicit_close_requested = True
                    await ws.close()
                else:
                    await self._send_error(ws, session, f"unsupported message type: {msg_type}")
        finally:
            if session.attached_ws is ws:
                session.attached_ws = None
            if running is not None and not running.done():
                running.cancel()
            session.closed = True
            self._log(session, "voice.realtime_agent.session.closed")
        return ws

    async def _handle_provider_native_ws(
        self,
        request: web.Request,
        session: RealtimeAgentSession,
    ) -> web.StreamResponse:
        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=2 * 1024 * 1024)
        await ws.prepare(request)
        was_detached = session.detached_at is not None
        session.attached_ws = ws
        if not was_detached:
            session.detached_at = None
            session.resume_deadline = None
            if session.native_close_task is not None and not session.native_close_task.done():
                session.native_close_task.cancel()

        connection = session.native_connection
        if connection is None:
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
            session.native_connection = connection
            session.native_playback_drained = asyncio.Event()
            session.native_provider_task = asyncio.create_task(
                self._pump_provider_events(
                    ws,
                    session,
                    connection,
                    session.native_playback_drained,
                )
            )

        if not was_detached:
            await self._send(ws, session, self._ready_event(session))
        provider_task = session.native_provider_task
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
                if await self._handle_common_client_message(ws, session, msg_type, payload):
                    pass
                elif msg_type == CLIENT_MSG_INPUT_AUDIO_APPEND:
                    decoded = await self._decode_input_audio_payload(ws, session, payload)
                    if decoded is None:
                        continue
                    chunk, sample_rate = decoded
                    chunk_id = _int_option(
                        payload,
                        "chunk_id",
                        session.input_chunk_seq + 1,
                    )
                    if chunk_id <= session.input_chunk_seq:
                        await self._send(
                            ws,
                            session,
                            {
                                "type": SERVER_EVT_INPUT_AUDIO_RECEIVED,
                                "input_chunk_id": chunk_id,
                                "byte_count": len(chunk),
                                "total_bytes": session.native_input_audio_bytes,
                                "sample_rate": sample_rate,
                                "duplicate": True,
                            },
                        )
                        continue
                    await connection.send_audio(chunk, sample_rate)
                    session.native_last_input_audio_at = time.monotonic()
                    session.native_input_audio_bytes += len(chunk)
                    session.input_chunk_seq = max(session.input_chunk_seq, chunk_id)
                    await self._send(
                        ws,
                        session,
                        {
                            "type": SERVER_EVT_INPUT_AUDIO_RECEIVED,
                            "input_chunk_id": chunk_id,
                            "byte_count": len(chunk),
                            "total_bytes": session.native_input_audio_bytes,
                            "sample_rate": sample_rate,
                        },
                    )
                elif msg_type == CLIENT_MSG_INPUT_AUDIO_COMMIT:
                    session.native_response_requested_for_input = False
                    await connection.commit_audio()
                elif msg_type == CLIENT_MSG_RESPONSE_CREATE:
                    text = _str_option(payload, "text")
                    if not text:
                        await self._send_error(
                            ws,
                            session,
                            "response.create requires text for realtime-agent text tests",
                        )
                        continue
                    await self._send(
                        ws,
                        session,
                        {
                            "type": SERVER_EVT_INPUT_TRANSCRIPT_FINAL,
                            "text": text,
                            "source": "client_text",
                        },
                    )
                    await connection.send_text(text[:5000])
                elif msg_type == CLIENT_MSG_INPUT_AUDIO_CLEAR:
                    await connection.clear_audio()
                elif msg_type == CLIENT_MSG_RESPONSE_CANCEL:
                    # Cancel the Hermes run ONLY when one is actually in
                    # flight. A cancel after completion (observed live: the
                    # client cancelled a run that finished 10s earlier) must
                    # not flip hermes_run_status to "cancelled" or emit
                    # hermes.run.cancelled for the finished run — that
                    # re-opens a settled chip and misreports the outcome.
                    # Stopping the CURRENT SPEECH (cancel_response +
                    # clear_audio) always happens.
                    hermes_run_active = (
                        session.hermes_task is not None
                        and not session.hermes_task.done()
                    )
                    if hermes_run_active:
                        self._cancel_active_hermes(session)
                    await connection.cancel_response()
                    await connection.clear_audio()
                    if hermes_run_active:
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
                elif msg_type == CLIENT_MSG_RESULT_RESPEAK:
                    # Respeak the last delivered background result (DONE-chip
                    # tap). Rides the relay-TTS fallback path — deterministic,
                    # no provider round-trip, and immune to summary drift.
                    respeak_result = session.last_background_result
                    if respeak_result is None:
                        await self._send(
                            ws,
                            session,
                            {"type": "hermes.result.respeak_unavailable"},
                        )
                    else:
                        respeak_text = _forced_summary_fallback_text(respeak_result)
                        # Respeak is also invisible to the provider — refresh
                        # the next-turn correction so it stays consistent.
                        session.native_pending_delivery_note = _delivery_note(respeak_text)
                        respeak_id = f"respeak-{session.event_seq + 1}"
                        self._log(
                            session,
                            "voice.result.respeak",
                            {
                                "type": "voice.result.respeak",
                                "response_id": respeak_id,
                                "chars": len(respeak_text),
                            },
                        )
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
                                "response_id": respeak_id,
                                "source": "hermes",
                                "delivery": "respeak",
                            },
                        )
                        await self._send(
                            ws,
                            session,
                            {
                                "type": SERVER_EVT_RESPONSE_DELTA,
                                "source": "hermes",
                                "delta": respeak_text,
                                "response_id": respeak_id,
                                "delivery": "respeak",
                            },
                        )
                        await self._render_provider_audio(
                            ws,
                            session,
                            respeak_text,
                            {},
                            response_id=respeak_id,
                        )
                elif msg_type == CLIENT_MSG_SESSION_CLOSE:
                    intentional_close = True
                    session.explicit_close_requested = True
                    await ws.close()
                else:
                    await self._send_error(ws, session, f"unsupported message type: {msg_type}")
                if provider_task is not None and provider_task.done():
                    break
        finally:
            if session.attached_ws is not ws:
                self._log(
                    session,
                    "voice.realtime_agent.websocket.superseded_closed",
                    {
                        "type": "voice.realtime_agent.websocket.superseded_closed",
                        "reason": "newer_websocket_attached",
                    },
                )
            else:
                session.attached_ws = None
                should_close = (
                    intentional_close
                    or session.explicit_close_requested
                    or session.closed
                    or provider_task is None
                    or provider_task.done()
                )
                if should_close:
                    await self._close_native_session(session, "closed")
                else:
                    await self._detach_native_session(session, "websocket_disconnected")
        return ws

    def _background_run_active(self, session: RealtimeAgentSession) -> bool:
        """True while a promoted/durable Hermes run (or its delivery) is in flight.

        Used to keep a *detached* session alive past the normal 30s resume window
        so a transient drop mid-run doesn't orphan the run and lose the result.
        """
        task = session.hermes_task
        if task is not None and not task.done():
            return True
        delivery = session.background_delivery_task
        return delivery is not None and not delivery.done()

    async def _detach_native_session(
        self,
        session: RealtimeAgentSession,
        reason: str,
    ) -> None:
        if session.closed:
            return
        now = time.time()
        session.detached_at = now
        # A live background run gets a longer resume window than the default 30s,
        # so a network blip mid-run can still resume and replay the result.
        window = session.resume_ttl_seconds
        if self._background_run_active(session):
            window = max(window, _background_detached_max_seconds())
        session.resume_deadline = now + window
        await self._send(
            None,
            session,
            {
                "type": SERVER_EVT_SESSION_DETACHED,
                "session_id": session.session_id,
                "reason": reason,
                "resume_ttl_ms": int(window * 1000),
            },
        )
        self._log(
            session,
            "voice.realtime_agent.session.detached",
            {
                "type": "voice.realtime_agent.session.detached",
                "reason": reason,
                "resume_deadline": session.resume_deadline,
            },
        )
        if session.native_close_task is not None and not session.native_close_task.done():
            session.native_close_task.cancel()
        session.native_close_task = asyncio.create_task(
            self._close_detached_native_session_after_ttl(session)
        )

    async def _close_detached_native_session_after_ttl(
        self,
        session: RealtimeAgentSession,
    ) -> None:
        # Poll until the resume window elapses. For a background run we hold the
        # session open until the run finishes plus a short grace (so a late resume
        # can still replay the recorded result), bounded by resume_deadline — which
        # _detach_native_session already stretched to the background cap.
        grace_after_run = _RESUME_TTL_SECONDS
        run_done_at: float | None = None
        try:
            while True:
                if session.closed or session.attached_ws is not None or session.detached_at is None:
                    return
                now = time.time()
                if session.resume_deadline is not None and now >= session.resume_deadline:
                    break
                if self._background_run_active(session):
                    run_done_at = None
                else:
                    if run_done_at is None:
                        run_done_at = now
                    elif now - run_done_at >= grace_after_run:
                        break
                await asyncio.sleep(_BACKGROUND_DETACHED_POLL_SECONDS)
        except asyncio.CancelledError:
            return
        if session.attached_ws is not None or session.detached_at is None or session.closed:
            return
        await self._close_native_session(session, "resume_ttl_expired")

    async def _close_native_session(
        self,
        session: RealtimeAgentSession,
        reason: str,
    ) -> None:
        if session.closed:
            return
        session.closed = True
        session.detached_at = None
        session.resume_deadline = None
        if session.native_close_task is not None and not session.native_close_task.done():
            session.native_close_task.cancel()
        provider_task = session.native_provider_task
        if provider_task is not None and not provider_task.done():
            provider_task.cancel()
        delivery_task = session.background_delivery_task
        if delivery_task is not None and not delivery_task.done():
            delivery_task.cancel()
        session.background_delivery_task = None
        # Durable fallback: a background result that finished but never reached
        # the phone is pushed as a proactive phone message (buffered while the
        # phone is offline) instead of dying with the session. The full answer
        # already persists in the Hermes chat session.
        pending = session.pending_background_result
        session.pending_background_result = None
        hermes_task = session.hermes_task
        if (
            pending is None
            and hermes_task is not None
            and hermes_task.done()
            and not hermes_task.cancelled()
            and hermes_task.exception() is None
            and session.hermes_run_tier in ("promoted", "durable")
        ):
            # The run finished but its delivery task was cancelled before it
            # could record the result (close raced completion).
            maybe = hermes_task.result()
            if isinstance(maybe, dict) and maybe.get("promoted") is not True:
                pending = maybe
        if pending is not None and self.proactive_push is not None:
            fallback = asyncio.create_task(
                self._push_undelivered_result_notice(session, pending)
            )
            fallback.add_done_callback(
                _log_task_failure(session, "proactive_fallback_task")
            )
        # Cancel any still-running (promoted/durable) Hermes run so a hung tool
        # can't keep executing against the gateway after the session is gone.
        if hermes_task is not None and not hermes_task.done():
            hermes_task.cancel()
        session.hermes_task = None
        connection = session.native_connection
        if connection is not None:
            await connection.close()
        session.native_connection = None
        session.native_provider_task = None
        session.native_playback_drained = None
        self._log(
            session,
            "voice.realtime_agent.session.closed",
            {
                "type": "voice.realtime_agent.session.closed",
                "reason": reason,
            },
        )

    async def _push_undelivered_result_notice(
        self,
        session: RealtimeAgentSession,
        result: dict[str, Any],
    ) -> None:
        """Push a completed-but-undelivered background result to the phone.

        Rides the proactive channel (buffered while the phone is offline), so
        the user learns the task finished even though the voice session died.
        The full answer persists in the Hermes chat session either way.
        """
        push = self.proactive_push
        if push is None:
            return
        text = str(
            result.get("text") or result.get("answer") or result.get("summary") or ""
        ).strip()
        preview = text[:280] + "…" if len(text) > 280 else text
        await push(
            {
                "title": "Background task finished",
                "text": preview
                or "Your background voice task finished — open the chat to see the result.",
                "surfacing": "notification",
                "metadata": {
                    "source": "realtime_agent",
                    "run_id": session.hermes_run_id,
                    "chat_session_id": session.chat_session_id,
                },
            }
        )
        self._log(
            session,
            "voice.realtime_agent.result_pushed_proactive",
            {
                "type": "voice.realtime_agent.result_pushed_proactive",
                "run_id": session.hermes_run_id,
            },
        )

    async def _handle_resume_message(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        payload: dict[str, Any],
    ) -> None:
        if not self._resume_token_matches(session, _str_option(payload, "resume_token")):
            await self._send(
                ws,
                session,
                {
                    "type": SERVER_EVT_SESSION_RESUME_FAILED,
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
                    "type": SERVER_EVT_SESSION_RESUME_FAILED,
                    "session_id": session.session_id,
                    "reason": "resume_expired",
                },
                record=False,
            )
            await ws.close(code=4008, message=b"resume expired")
            await self._close_native_session(session, "resume_expired")
            return

        last_event_id = _int_option(payload, "last_event_id", 0)
        last_audio_event_id = _int_option(payload, "last_audio_event_id", 0)
        played_audio_event_id = _int_option(payload, "last_played_audio_event_id", 0)
        last_input_chunk_id = _int_option(payload, "last_input_chunk_id", 0)
        session.acked_event_id_by_client = max(session.acked_event_id_by_client, last_event_id)
        session.acked_audio_event_id_by_client = max(
            session.acked_audio_event_id_by_client,
            last_audio_event_id,
        )
        session.played_audio_event_id_by_client = max(
            session.played_audio_event_id_by_client,
            played_audio_event_id,
        )
        session.acked_input_chunk_id_by_client = max(
            session.acked_input_chunk_id_by_client,
            last_input_chunk_id,
        )
        session.detached_at = None
        session.resume_deadline = None
        if session.native_close_task is not None and not session.native_close_task.done():
            session.native_close_task.cancel()
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_SESSION_RESUMED,
                "session_id": session.session_id,
                "last_event_id": last_event_id,
                "last_audio_event_id": last_audio_event_id,
                "last_played_audio_event_id": played_audio_event_id,
                "last_input_chunk_id": last_input_chunk_id,
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
        # A background result that completed while detached was deliberately
        # held instead of spoken into the ring — deliver it now that the phone
        # is back and the replay has caught it up.
        await self._deliver_pending_background_result(ws, session)

    async def _replay_session_events(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
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
                "type": SERVER_EVT_REPLAY_STARTED,
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
            if not await self._send_json_best_effort(ws, session, replay):
                return
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_REPLAY_DONE,
                "session_id": session.session_id,
                "replay_event_count": len(replay_events),
            },
            record=False,
        )

    def _handle_client_ack(
        self,
        session: RealtimeAgentSession,
        payload: dict[str, Any],
    ) -> None:
        event_id = _int_option(payload, "event_id", 0)
        audio_event_id = _int_option(payload, "audio_event_id", 0)
        played_audio_event_id = _int_option(payload, "played_audio_event_id", 0)
        input_chunk_id = _int_option(payload, "input_chunk_id", 0)
        session.acked_event_id_by_client = max(session.acked_event_id_by_client, event_id)
        session.acked_audio_event_id_by_client = max(
            session.acked_audio_event_id_by_client,
            audio_event_id,
        )
        session.played_audio_event_id_by_client = max(
            session.played_audio_event_id_by_client,
            played_audio_event_id,
        )
        session.acked_input_chunk_id_by_client = max(
            session.acked_input_chunk_id_by_client,
            input_chunk_id,
        )

    def _resume_token_matches(
        self,
        session: RealtimeAgentSession,
        token: str | None,
    ) -> bool:
        if not token:
            return False
        return hmac.compare_digest(_token_hash(token), session.resume_token_hash)

    def _auth_matches_session(
        self,
        session: RealtimeAgentSession,
        principal: AuthPrincipal,
        bearer_token: str,
    ) -> bool:
        if principal.kind != session.auth_kind:
            return False
        if principal.kind == "relay_session":
            device_id = principal.session.device_id if principal.session is not None else None
            return bool(device_id) and device_id == session.auth_session_device_id
        return hmac.compare_digest(_token_hash(bearer_token), session.auth_token_hash or "")

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

    def _should_forward_provider_response_event(
        self,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> bool:
        response_id = str(event.response_id or "").strip()
        if session.native_forced_summary_done:
            self._log(
                session,
                "voice.response.suppressed_after_forced_summary",
                {
                    "type": "voice.response.suppressed_after_forced_summary",
                    "response_id": response_id or None,
                    "provider": session.provider,
                },
            )
            return False
        if not session.native_forced_summary_active:
            return True

        marker = response_id or "__blank_response_id__"
        if session.native_forced_summary_response_id is None:
            session.native_forced_summary_response_id = marker
        if session.native_forced_summary_response_id != marker:
            self._log(
                session,
                "voice.response.suppressed_duplicate_forced_summary",
                {
                    "type": "voice.response.suppressed_duplicate_forced_summary",
                    "response_id": response_id or None,
                    "accepted_response_id": (
                        None
                        if session.native_forced_summary_response_id == "__blank_response_id__"
                        else session.native_forced_summary_response_id
                    ),
                    "provider": session.provider,
                },
            )
            return False
        return True

    def _mark_provider_response_started(
        self,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> bool:
        response_id = str(event.response_id or "").strip()
        if not response_id:
            return True
        if response_id in session.response_ids_started:
            return False
        session.response_ids_started.add(response_id)
        return True

    def _mark_provider_response_done(
        self,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> None:
        if not session.native_forced_summary_active:
            return
        response_id = str(event.response_id or "").strip() or "__blank_response_id__"
        if session.native_forced_summary_response_id not in {None, response_id}:
            return
        session.native_forced_summary_response_id = response_id
        session.native_forced_summary_active = False
        session.native_forced_summary_done = True
        session.native_forced_summary_result = None
        session.native_forced_summary_buffer.clear()
        session.native_forced_summary_committed = False
        session.native_forced_summary_text_parts.clear()

    async def _start_forced_hermes_preamble(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        transcript: str,
        force_reason: str,
    ) -> None:
        if session.native_forced_preamble_active or session.native_forced_hermes_turn_active:
            return
        session.native_forced_summary_active = False
        session.native_forced_summary_done = False
        session.native_forced_summary_response_id = None
        session.native_forced_summary_result = None
        session.native_forced_summary_buffer.clear()
        session.native_forced_summary_committed = False
        session.native_forced_summary_text_parts.clear()
        session.native_forced_preamble_active = True
        session.native_forced_preamble_transcript = transcript
        session.native_forced_preamble_response_id = None
        session.native_forced_preamble_audio_seen = False
        self._log(
            session,
            "voice.hermes_forced_preamble.started",
            {
                "type": "voice.hermes_forced_preamble.started",
                "reason": force_reason,
                "transcript_preview": _compact_status_text(transcript),
                "provider": session.provider,
            },
        )
        with contextlib.suppress(Exception):
            await connection.cancel_response()
        await connection.request_response(
            instructions=_forced_hermes_preamble_prompt(transcript)
        )

    def _should_forward_forced_preamble_event(
        self,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> bool:
        response_id = str(event.response_id or "").strip()
        marker = response_id or "__blank_response_id__"
        if session.native_forced_preamble_response_id is None:
            session.native_forced_preamble_response_id = marker
        if session.native_forced_preamble_response_id == marker:
            return True
        self._log(
            session,
            "voice.hermes_forced_preamble.suppressed_extra_response",
            {
                "type": "voice.hermes_forced_preamble.suppressed_extra_response",
                "response_id": response_id or None,
                "accepted_response_id": (
                    None
                    if session.native_forced_preamble_response_id == "__blank_response_id__"
                    else session.native_forced_preamble_response_id
                ),
                "provider": session.provider,
            },
        )
        return False

    async def _finish_forced_hermes_preamble_and_run(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        playback_drained: asyncio.Event,
        *,
        reason: str,
    ) -> None:
        transcript = (session.native_forced_preamble_transcript or "").strip()
        response_id = session.native_forced_preamble_response_id
        audio_seen = session.native_forced_preamble_audio_seen
        session.native_forced_preamble_active = False
        session.native_forced_preamble_transcript = None
        session.native_forced_preamble_response_id = None
        session.native_forced_preamble_audio_seen = False
        if not transcript:
            self._log(
                session,
                "voice.hermes_forced_preamble.no_transcript",
                {
                    "type": "voice.hermes_forced_preamble.no_transcript",
                    "reason": reason,
                    "provider": session.provider,
                },
            )
            return

        self._log(
            session,
            "voice.hermes_forced_preamble.finished",
            {
                "type": "voice.hermes_forced_preamble.finished",
                "reason": reason,
                "response_id": None if response_id == "__blank_response_id__" else response_id,
                "audio_seen": audio_seen,
                "provider": session.provider,
            },
        )
        await self._run_forced_hermes_turn_from_transcript(
            ws,
            session,
            connection,
            playback_drained,
            transcript,
            speak_pre_status=not audio_seen,
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
                # Record which model ACTUALLY served: aliases like
                # grok-voice-latest move server-side, and without this the
                # flight recorder only shows what we requested — live-round
                # verdicts become unattributable across alias flips.
                resolved = str(event.payload.get("resolved_model") or "").strip()
                if resolved and resolved != session.native_resolved_model:
                    session.native_resolved_model = resolved
                    self._log(
                        session,
                        "voice.realtime_agent.provider_model_resolved",
                        {
                            "type": "voice.realtime_agent.provider_model_resolved",
                            "requested_model": session.model,
                            "resolved_model": resolved,
                        },
                    )
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
                text = str(event.payload.get("text") or "")
                await self._send(
                    ws,
                    session,
                    {
                        "type": SERVER_EVT_INPUT_TRANSCRIPT_FINAL,
                        "text": text,
                    },
                )
                if text and not session.native_response_requested_for_input:
                    session.native_response_requested_for_input = True
                    force_reason = _force_hermes_reason_for_transcript(text)
                    if force_reason:
                        await self._preempt_pending_forced_summary(
                            ws, session, connection, reason="new_forced_turn"
                        )
                        session.native_hermes_required_transcript = text
                        session.native_hermes_required_reason = force_reason
                        self._log(
                            session,
                            "voice.hermes_required.provider_native",
                            {
                                "type": "voice.hermes_required.provider_native",
                                "reason": force_reason,
                                "strategy": "provider_function_call",
                                "transcript_preview": _compact_status_text(text),
                                "provider": session.provider,
                            },
                        )
                        await connection.request_response()
                    else:
                        await self._preempt_pending_forced_summary(
                            ws, session, connection, reason="new_user_turn"
                        )
                        session.native_hermes_required_transcript = None
                        session.native_hermes_required_reason = None
                        delivery_note = session.native_pending_delivery_note
                        if delivery_note:
                            # A system-side delivery (fallback TTS / text
                            # emit) happened that the provider never saw —
                            # its history still says "running in background".
                            # Carry the correction on this one response
                            # (composed WITH the session instructions, since
                            # per-response instructions replace them).
                            session.native_pending_delivery_note = None
                            self._log(
                                session,
                                "voice.response.delivery_note_attached",
                                {
                                    "type": "voice.response.delivery_note_attached",
                                    "note_preview": _compact_status_text(delivery_note),
                                },
                            )
                            await connection.request_response(
                                instructions=(
                                    _native_instructions(session)
                                    + "\n\n"
                                    + delivery_note
                                )
                            )
                        else:
                            await connection.request_response()
            elif event.kind == ProviderEventKind.RESPONSE_STARTED:
                if session.native_forced_preamble_active:
                    if not self._should_forward_forced_preamble_event(session, event):
                        continue
                    if not self._mark_provider_response_started(session, event):
                        continue
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
                            "phase": "forced_hermes_preamble",
                        },
                    )
                    continue
                if session.native_forced_summary_active:
                    if self._should_forward_provider_response_event(session, event):
                        session.native_forced_summary_buffer.append(
                            {
                                "type": SERVER_EVT_RESPONSE_STARTED,
                                "provider": session.provider,
                                "model": session.model,
                                "voice": session.voice,
                                "session_id": session.session_id,
                                "chat_session_id": session.chat_session_id,
                                "response_id": event.response_id,
                            }
                        )
                    continue
                if not self._should_forward_provider_response_event(session, event):
                    continue
                if not self._mark_provider_response_started(session, event):
                    continue
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
                if session.native_forced_preamble_active:
                    if not self._should_forward_forced_preamble_event(session, event):
                        continue
                    await self._send(
                        ws,
                        session,
                        {
                            "type": SERVER_EVT_RESPONSE_DELTA,
                            "source": "provider",
                            "delta": str(event.payload.get("delta") or ""),
                            "response_id": event.response_id,
                            "phase": "forced_hermes_preamble",
                        },
                    )
                    continue
                if session.native_forced_summary_active:
                    if self._should_forward_provider_response_event(session, event):
                        delta = str(event.payload.get("delta") or "")
                        session.native_forced_summary_text_parts.append(delta)
                        delta_event = {
                            "type": SERVER_EVT_RESPONSE_DELTA,
                            "source": "provider",
                            "delta": delta,
                            "response_id": event.response_id,
                        }
                        if session.native_forced_summary_committed:
                            # Early-committed: stream live.
                            await self._send(ws, session, delta_event)
                        else:
                            session.native_forced_summary_buffer.append(delta_event)
                            await self._maybe_commit_forced_summary_early(ws, session)
                    continue
                if not self._should_forward_provider_response_event(session, event):
                    continue
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
                # The provider is producing audio for this response; take the
                # floor so filler/relay-TTS can't overlap (ADR 33).
                session.floor.acquire(FloorMouth.PROVIDER)
                if session.native_forced_preamble_active:
                    if not self._should_forward_forced_preamble_event(session, event):
                        continue
                    payload = await self._provider_audio_delta_event(ws, session, event)
                    if payload is not None:
                        payload["phase"] = "forced_hermes_preamble"
                        session.native_forced_preamble_audio_seen = True
                        await self._send(ws, session, payload)
                    continue
                if session.native_forced_summary_active:
                    if self._should_forward_provider_response_event(session, event):
                        if session.native_forced_summary_committed:
                            # Early-committed: stream audio live.
                            await self._send_provider_audio_delta(ws, session, event)
                        else:
                            payload = await self._provider_audio_delta_event(ws, session, event)
                            if payload is not None:
                                session.native_forced_summary_buffer.append(payload)
                    continue
                if not self._should_forward_provider_response_event(session, event):
                    continue
                await self._send_provider_audio_delta(ws, session, event)
            elif event.kind == ProviderEventKind.AUDIO_DONE:
                # Provider finished emitting audio for this response; release the
                # floor so a pending background result / filler can proceed.
                session.floor.release(FloorMouth.PROVIDER)
                if session.native_forced_preamble_active:
                    if not self._should_forward_forced_preamble_event(session, event):
                        continue
                    await self._send(
                        ws,
                        session,
                        {
                            "type": SERVER_EVT_OUTPUT_AUDIO_DONE,
                            "response_id": event.response_id,
                            "phase": "forced_hermes_preamble",
                        },
                    )
                    continue
                if session.native_forced_summary_active:
                    if self._should_forward_provider_response_event(session, event):
                        audio_done_event = {
                            "type": SERVER_EVT_OUTPUT_AUDIO_DONE,
                            "response_id": event.response_id,
                        }
                        if session.native_forced_summary_committed:
                            await self._send(ws, session, audio_done_event)
                        else:
                            session.native_forced_summary_buffer.append(audio_done_event)
                    continue
                if not self._should_forward_provider_response_event(session, event):
                    continue
                await self._send(
                    ws,
                    session,
                    {
                        "type": SERVER_EVT_OUTPUT_AUDIO_DONE,
                        "response_id": event.response_id,
                    },
                )
            elif event.kind == ProviderEventKind.FUNCTION_CALL_COMPLETED:
                if session.native_forced_preamble_active:
                    call = _tool_call_from_provider_event(event)
                    self._log(
                        session,
                        "voice.hermes_forced_preamble.tool_call_suppressed",
                        {
                            "type": "voice.hermes_forced_preamble.tool_call_suppressed",
                            "response_id": str(event.response_id or "").strip() or None,
                            "tool_name": call.name or None,
                            "call_id": call.call_id or None,
                            "provider": session.provider,
                        },
                    )
                    with contextlib.suppress(Exception):
                        await connection.cancel_response()
                    await self._finish_forced_hermes_preamble_and_run(
                        ws,
                        session,
                        connection,
                        playback_drained,
                        reason="tool_call_suppressed",
                    )
                    continue
                if session.native_forced_summary_active:
                    await self._handle_forced_summary_tool_call(
                        ws,
                        session,
                        connection,
                        playback_drained,
                        event,
                    )
                    continue
                if session.native_forced_summary_done:
                    self._log(
                        session,
                        "voice.response.suppressed_forced_summary_tool_call",
                        {
                            "type": "voice.response.suppressed_forced_summary_tool_call",
                            "response_id": str(event.response_id or "").strip() or None,
                            "provider": session.provider,
                        },
                    )
                    continue
                if not self._should_forward_provider_response_event(session, event):
                    continue
                await self._handle_provider_tool_call(
                    ws,
                    session,
                    connection,
                    playback_drained,
                    event,
                )
            elif event.kind == ProviderEventKind.RESPONSE_DONE:
                # Safety net: a response may end without a clean AUDIO_DONE.
                session.floor.release(FloorMouth.PROVIDER)
                if session.native_forced_preamble_active:
                    if not self._should_forward_forced_preamble_event(session, event):
                        continue
                    await self._finish_forced_hermes_preamble_and_run(
                        ws,
                        session,
                        connection,
                        playback_drained,
                        reason="response_done",
                    )
                    continue
                if self._is_intermediate_tool_response_done(session, event):
                    continue
                if session.native_forced_summary_active:
                    if not self._should_forward_provider_response_event(session, event):
                        continue
                    await self._finish_forced_summary_provider_response(ws, session, event)
                    continue
                if not self._should_forward_provider_response_event(session, event):
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
                self._mark_provider_response_done(session, event)
            elif event.kind == ProviderEventKind.ERROR:
                if session.native_forced_preamble_active:
                    self._log(
                        session,
                        "voice.hermes_forced_preamble.provider_error",
                        {
                            "type": "voice.hermes_forced_preamble.provider_error",
                            "message": str(event.payload.get("message") or "provider error"),
                            "provider": session.provider,
                        },
                    )
                    await self._finish_forced_hermes_preamble_and_run(
                        ws,
                        session,
                        connection,
                        playback_drained,
                        reason="provider_error",
                    )
                    continue
                error_message = str(event.payload.get("message") or "provider error")
                if _is_provider_idle_timeout(error_message):
                    # The provider ended the conversation after prolonged true
                    # silence (xAI: 900s inactivity). Keepalive probes proved
                    # no protocol no-op resets that timer, so this is a normal
                    # stale-provider-session close. Do not surface a user error:
                    # the next Android turn will open a fresh provider session
                    # seeded from the durable Hermes chat context.
                    session.native_provider_idle_closed = True
                    self._log(
                        session,
                        "voice.realtime_agent.provider_idle_close",
                        {
                            "type": "voice.realtime_agent.provider_idle_close",
                            "message": error_message,
                            "provider": session.provider,
                        },
                    )
                    attached_ws = session.attached_ws
                    if attached_ws is not None and not attached_ws.closed:
                        await attached_ws.close(
                            code=1000,
                            message=_PROVIDER_IDLE_CLOSE_WS_REASON.encode("utf-8"),
                        )
                    return
                elif _is_benign_provider_error(error_message):
                    # A non-fatal provider notice (e.g. cancelling when no
                    # response is active) must NOT be surfaced as a fatal
                    # voice.error — the client closes the session on that, which
                    # killed a live turn right as the reply was arriving.
                    self._log(
                        session,
                        "voice.realtime_agent.provider_notice",
                        {
                            "type": "voice.realtime_agent.provider_notice",
                            "message": error_message,
                            "provider": session.provider,
                        },
                    )
                else:
                    await self._send_error(
                        ws,
                        session,
                        error_message,
                        provider=session.provider,
                    )

    async def _send_provider_audio_delta(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> None:
        payload = await self._provider_audio_delta_event(ws, session, event)
        if payload is not None:
            marker = str(event.response_id or "").strip() or "__blank_response_id__"
            session.provider_response_audio_seen.add(marker)
            await self._send(ws, session, payload)

    async def _provider_audio_delta_event(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> dict[str, Any] | None:
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
            return None
        peak, rms = _pcm_levels(audio_bytes)
        return {
            "type": SERVER_EVT_OUTPUT_AUDIO_DELTA,
            "audio_base64": encoded,
            "byte_count": len(audio_bytes),
            "sample_rate": session.sample_rate,
            "channels": DEFAULT_CHANNELS,
            "sample_width": DEFAULT_SAMPLE_WIDTH,
            "peak_level": peak,
            "rms_level": rms,
            "response_id": event.response_id,
        }

    async def _maybe_commit_forced_summary_early(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
    ) -> None:
        """Flush the buffered forced summary early once its prefix validates.

        The summary response is buffered so a bad one (deferral filler, run-id
        recital, no relation to the answer) can be swapped for the fallback
        before any audio plays — but full-response buffering means the user
        hears NOTHING until the provider finishes generating (observed live as
        a multi-second dead gap, then the whole answer in one burst). Once the
        accumulated text prefix is long enough, clears the bad-phrase check,
        and shows content overlap with the Hermes answer, commit: flush the
        buffer and stream the rest live. A prefix that fails these checks
        simply keeps buffering — the end-of-response validation still decides.
        """
        if session.native_forced_summary_committed:
            return
        text = "".join(session.native_forced_summary_text_parts).strip()
        if len(text) < _FORCED_SUMMARY_EARLY_COMMIT_MIN_CHARS:
            return
        answer = _result_answer_text(session.native_forced_summary_result or {})
        # Pass the answer so blocklist phrases the answer itself contains
        # don't stall an exact reading's early commit.
        if _bad_forced_summary_reason(text, answer) is not None:
            return
        # Committing is irreversible (audio plays), so the early bar is
        # HIGHER than end-of-response validation: two whole-word evidence
        # hits, not one. A single hit let a queue acknowledgement ("will
        # start automatically…") stream as the answer (observed live). A
        # prefix that can't clear the bar simply keeps buffering — the full
        # response still gets end validation + fallback.
        hits = _summary_overlap_hits(text, answer)
        if hits != -1 and hits < 2:
            return
        session.native_forced_summary_committed = True
        buffered = list(session.native_forced_summary_buffer)
        # NOTE: this clear is a FLUSH — committed stays True (every other
        # buffer.clear() site is a state reset and clears the flag).
        session.native_forced_summary_buffer.clear()
        self._log(
            session,
            "voice.response.forced_summary_streaming",
            {
                "type": "voice.response.forced_summary_streaming",
                "prefix_chars": len(text),
                "buffered_events": len(buffered),
            },
        )
        for buffered_event in buffered:
            await self._send(ws, session, buffered_event)

    async def _finish_forced_summary_provider_response(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        event: ProviderEvent,
    ) -> None:
        provider_text = "".join(session.native_forced_summary_text_parts).strip()
        result = session.native_forced_summary_result or {}
        response_id = str(event.response_id or "").strip() or "__blank_response_id__"
        if session.native_forced_summary_committed:
            # Early-committed: the summary already streamed live (audio
            # played) — validation is moot. Close out the response normally.
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
                    "response_id": None if response_id == "__blank_response_id__" else response_id,
                },
            )
            self._mark_provider_response_done(session, event)
            return
        if _is_bad_forced_summary_response(provider_text, _result_answer_text(result)):
            fallback_text = _forced_summary_fallback_text(result)
            self._log(
                session,
                "voice.response.forced_summary_fallback",
                {
                    "type": "voice.response.forced_summary_fallback",
                    "response_id": None if response_id == "__blank_response_id__" else response_id,
                    "provider": session.provider,
                    "reason": _bad_forced_summary_reason(provider_text, _result_answer_text(result)),
                    "provider_text_preview": _compact_status_text(provider_text),
                    "fallback_preview": _compact_status_text(fallback_text),
                },
            )
            session.native_forced_summary_buffer.clear()
            session.native_forced_summary_committed = False
            session.native_forced_summary_text_parts.clear()
            session.native_forced_summary_active = False
            session.native_forced_summary_done = True
            session.native_forced_summary_response_id = response_id
            session.native_forced_summary_result = None
            # The provider never sees this fallback delivery — leave a note
            # for the next user turn so it can't claim the task is still
            # running (observed live after a fallback: "the background task
            # is still going" while the answer had already been spoken).
            session.native_pending_delivery_note = _delivery_note(fallback_text)
            fallback_response_id = f"forced-summary-fallback-{session.event_seq + 1}"
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
                    "response_id": fallback_response_id,
                    "source": "hermes",
                    "delivery": "fallback",
                },
            )
            await self._send(
                ws,
                session,
                {
                    "type": SERVER_EVT_RESPONSE_DELTA,
                    "source": "hermes",
                    "delta": fallback_text,
                    "response_id": fallback_response_id,
                    "delivery": "fallback",
                },
            )
            await self._render_provider_audio(
                ws,
                session,
                fallback_text,
                {},
                response_id=fallback_response_id,
            )
            return

        buffered = list(session.native_forced_summary_buffer)
        for buffered_event in buffered:
            await self._send(ws, session, buffered_event)
        # Rollup marker: an end-validated provider-spoken delivery has no
        # other distinct event (early commits log forced_summary_streaming,
        # fallbacks log their own reasons) — without this, clean deliveries
        # are invisible to the delivery-outcome report.
        self._log(
            session,
            "voice.response.forced_summary_delivered",
            {
                "type": "voice.response.forced_summary_delivered",
                "response_id": None if response_id == "__blank_response_id__" else response_id,
                "chars": len(provider_text),
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
                "response_id": None if response_id == "__blank_response_id__" else response_id,
            },
        )
        self._mark_provider_response_done(session, event)

    async def _handle_forced_summary_tool_call(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        playback_drained: asyncio.Event,
        event: ProviderEvent,
    ) -> None:
        call = _tool_call_from_provider_event(event)
        response_id = str(event.response_id or "").strip()
        if response_id:
            session.response_ids_awaiting_tool_followup.add(response_id)
        session.native_forced_summary_response_id = None
        result = session.native_forced_summary_result
        self._log(
            session,
            "voice.response.forced_summary_tool_call_reused",
            {
                "type": "voice.response.forced_summary_tool_call_reused",
                "response_id": response_id or None,
                "tool_name": call.name or None,
                "call_id": call.call_id or None,
                "has_cached_result": result is not None,
                "provider": session.provider,
            },
        )
        if not call.call_id or result is None:
            with contextlib.suppress(Exception):
                await connection.cancel_response()
            return

        await self._request_playback_drain(
            ws,
            session,
            playback_drained,
            call_id=call.call_id,
            tool_name=call.name or "hermes_run_task",
            reason="forced_summary",
        )
        delivered = await self._send_provider_tool_result(
            ws,
            session,
            connection,
            call.call_id,
            _forced_summary_tool_result(result),
        )
        if not delivered:
            return
        if not await self._request_provider_response(ws, session, connection, call.call_id):
            return

    async def _handle_provider_tool_call(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        playback_drained: asyncio.Event,
        event: ProviderEvent,
    ) -> None:
        call = _tool_call_from_provider_event(event)
        if call.is_hermes_tool() and event.response_id:
            session.response_ids_awaiting_tool_followup.add(event.response_id)
        if call.name == "hermes_run_task":
            if session.native_hermes_required_transcript is not None:
                self._log(
                    session,
                    "voice.hermes_required.provider_tool_call",
                    {
                        "type": "voice.hermes_required.provider_tool_call",
                        "reason": session.native_hermes_required_reason,
                        "call_id": call.call_id,
                        "response_id": str(event.response_id or "").strip() or None,
                        "provider": session.provider,
                    },
                )
            session.native_hermes_required_transcript = None
            session.native_hermes_required_reason = None
            if self._provider_response_had_audio(session, event.response_id):
                await self._request_playback_drain(
                    ws,
                    session,
                    playback_drained,
                    call_id=call.call_id,
                    tool_name=call.name,
                    reason="pre_hermes_ack",
                )
            await self._send_pre_hermes_status(
                ws,
                session,
                call.call_id,
                should_speak=False,
            )
        result = await self._run_brokered_tool(ws, session, call)
        if result.get("promoted"):
            await self._begin_background_delivery(
                ws,
                session,
                connection,
                origin="provider_tool_call",
                call_id=call.call_id,
                transcript=str(call.arguments.get("text") or ""),
            )
            return
        delivered = await self._send_provider_tool_result(
            ws,
            session,
            connection,
            call.call_id,
            result,
        )
        if not delivered:
            return
        if call.name == "hermes_run_task" and result.get("cancelled"):
            return
        await self._request_playback_drain(
            ws,
            session,
            playback_drained,
            call_id=call.call_id,
            tool_name=call.name,
            reason="tool_result_summary",
        )
        if not await self._request_provider_response(ws, session, connection, call.call_id):
            return

    def _provider_response_had_audio(
        self,
        session: RealtimeAgentSession,
        response_id: str | None,
    ) -> bool:
        marker = str(response_id or "").strip() or "__blank_response_id__"
        return marker in session.provider_response_audio_seen

    async def _request_playback_drain(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        playback_drained: asyncio.Event,
        *,
        call_id: str | None,
        tool_name: str | None,
        reason: str,
        timeout_seconds: float = _PLAYBACK_DRAIN_TIMEOUT_SECONDS,
    ) -> bool:
        playback_drained.clear()
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_PLAYBACK_DRAIN_REQUESTED,
                "call_id": call_id,
                "tool_name": tool_name,
                "reason": reason,
                "timeout_ms": int(timeout_seconds * 1000),
            },
        )
        try:
            await asyncio.wait_for(
                playback_drained.wait(),
                timeout=timeout_seconds,
            )
            self._log(
                session,
                "voice.playback_drain.completed",
                {
                    "type": "voice.playback_drain.completed",
                    "call_id": call_id,
                    "reason": reason,
                },
            )
            return True
        except asyncio.TimeoutError:
            await self._send(
                ws,
                session,
                {
                    "type": "voice.playback_drain.timeout",
                    "call_id": call_id,
                    "reason": reason,
                    "timeout_ms": int(timeout_seconds * 1000),
                },
            )
            return False

    async def _send_provider_tool_result(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        call_id: str,
        result: dict[str, Any],
    ) -> bool:
        try:
            await connection.send_tool_result(call_id, result)
            return True
        except Exception as exc:
            await self._send_error(
                ws,
                session,
                "Realtime provider closed before Hermes result could be delivered.",
                error_code="provider_tool_result_send_failed",
                call_id=call_id,
            )
            self._log(
                session,
                "voice.provider_tool_result_send_failed",
                {
                    "type": "voice.provider_tool_result_send_failed",
                    "call_id": call_id,
                    "error": str(exc),
                },
            )
            await self._close_native_session(session, "provider_tool_result_send_failed")
            return False

    async def _request_provider_response(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        call_id: str,
    ) -> bool:
        try:
            await connection.request_response()
            return True
        except Exception as exc:
            await self._send_error(
                ws,
                session,
                "Realtime provider closed before it could summarize the Hermes result.",
                error_code="provider_response_request_failed",
                call_id=call_id,
            )
            self._log(
                session,
                "voice.provider_response_request_failed",
                {
                    "type": "voice.provider_response_request_failed",
                    "call_id": call_id,
                    "error": str(exc),
                },
            )
            await self._close_native_session(session, "provider_response_request_failed")
            return False

    async def _run_forced_hermes_turn_from_transcript(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        playback_drained: asyncio.Event,
        transcript: str,
        *,
        speak_pre_status: bool = True,
    ) -> None:
        if session.native_forced_hermes_turn_active:
            return
        session.native_forced_hermes_turn_active = True
        session.native_forced_summary_active = False
        session.native_forced_summary_done = False
        session.native_forced_summary_response_id = None
        session.native_forced_summary_result = None
        session.native_forced_summary_buffer.clear()
        session.native_forced_summary_committed = False
        session.native_forced_summary_text_parts.clear()
        call_id = f"forced-hermes-{session.event_seq + 1}"
        try:
            with contextlib.suppress(Exception):
                await connection.cancel_response()
            if speak_pre_status:
                await self._send_pre_hermes_status(ws, session, call_id)
            result = await self._run_brokered_tool(
                ws,
                session,
                ToolCallEvent(
                    call_id=call_id,
                    name="hermes_run_task",
                    arguments={
                        "text": transcript,
                        "profile": session.profile or "",
                        "session_id": session.chat_session_id or "",
                        "mode": "run",
                    },
                ),
            )
            if result.get("promoted"):
                await self._begin_background_delivery(
                    ws,
                    session,
                    connection,
                    origin="forced",
                    call_id=None,
                    transcript=transcript,
                )
                return
            if result.get("cancelled"):
                return
            if result.get("ok") is False:
                await self._send_error(
                    ws,
                    session,
                    str(result.get("error") or "Hermes failed"),
                    provider=session.provider,
                )
                return

            # Every spoken mode delivers through the provider so the answer
            # keeps the realtime voice; speak_verbatim differs only in the
            # instructions (read the answer as written vs. natural summary).
            # The forced-summary validator + relay-TTS fallback backstop both.
            session.native_forced_summary_active = True
            session.native_forced_summary_done = False
            session.native_forced_summary_response_id = None
            session.native_forced_summary_result = dict(result)
            session.native_forced_summary_buffer.clear()
            session.native_forced_summary_committed = False
            session.native_forced_summary_text_parts.clear()
            with contextlib.suppress(Exception):
                await connection.cancel_response()
            try:
                await connection.request_response(
                    instructions=_result_delivery_prompt(
                        session.result_delivery, transcript, result
                    )
                )
            except Exception as exc:  # noqa: BLE001 - the answer must still land
                self._log(
                    session,
                    "voice.realtime_agent.summary_send_failed",
                    {
                        "type": "voice.realtime_agent.summary_send_failed",
                        "run_id": session.hermes_run_id,
                        "origin": "foreground",
                        "error": f"{exc.__class__.__name__}: {exc}",
                    },
                )
                await self._speak_fallback_answer(
                    ws, session, result, reason="foreground_request_failed"
                )
                return
            # Delivered-or-alarm, same as the background path: if no spoken
            # delivery lands within the confirm window, force a text emit so
            # a dead/stalled provider response can't silently eat the answer.
            confirm = asyncio.create_task(
                self._confirm_background_delivery(ws, session, dict(result))
            )
            confirm.add_done_callback(
                _log_task_failure(session, "delivery_confirm_task")
            )
        finally:
            session.native_forced_hermes_turn_active = False

    async def _send_pre_hermes_status(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        call_id: str | None,
        *,
        should_speak: bool = True,
    ) -> None:
        # This lead fires BEFORE _execute_brokered_tool resets the per-run
        # progress state, so session.hermes_run_id / completed_tool_count
        # still describe the PREVIOUS run here (observed live: a new run's
        # "I'll check Hermes." event carried the finished prior run's id and
        # tool count, confusing the client chip). When no run is in flight,
        # this event announces a FRESH run: send null/zero identity instead
        # of the stale values. While a run IS active (fast-lane attempt),
        # keep the active run's identity so the chip stays consistent.
        hermes_run_active = (
            session.hermes_task is not None and not session.hermes_task.done()
        )
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_HERMES_RUN_PROGRESS,
                "source": "hermes",
                "session_id": session.chat_session_id,
                "chat_session_id": session.chat_session_id,
                "run_id": session.hermes_run_id if hermes_run_active else None,
                "status": "starting",
                "message": "I'll check Hermes.",
                "status_key": "run:checking_hermes",
                "should_speak": should_speak,
                "call_id": call_id,
                "active_tool_name": None,
                "last_tool_name": None,
                "completed_tool_count": (
                    session.hermes_completed_tool_count if hermes_run_active else 0
                ),
            },
        )
        if _PRE_HERMES_STATUS_LEAD_SECONDS > 0:
            await asyncio.sleep(_PRE_HERMES_STATUS_LEAD_SECONDS)

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
        if session.native_forced_summary_active:
            session.native_forced_summary_response_id = None
            session.native_forced_summary_buffer.clear()
            session.native_forced_summary_committed = False
            session.native_forced_summary_text_parts.clear()
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

        # max_background_runs=1: a second hermes_run_task while one is still in
        # flight must NOT create a new task — that would overwrite the
        # session.hermes_task reference and cancel the first run's delivery,
        # orphaning it.
        #
        # Fast lane (background-run v2 §1): when the in-flight run is DETACHED
        # (promoted/durable — the only way a second call can arrive while one
        # is running), first try the new request INLINE on a separate
        # ephemeral Hermes session within the normal grace window. A quick
        # lookup ("what time is it in Tokyo?") answers immediately instead of
        # being refused; anything that would promote (grace elapsed, known-long
        # tool started) is abandoned and falls through to the busy answer —
        # the single background slot stays owned by the in-flight run.
        existing = session.hermes_task
        if existing is not None and not existing.done():
            if session.hermes_run_tier in ("promoted", "durable"):
                fast = await self._run_fast_lane_task(session, call)
                if fast is not None:
                    return fast
            # Long second ask (fast lane abandoned or skipped): QUEUE it
            # instead of refusing — it starts automatically when the current
            # task's delivery settles. Bounded FIFO; only a full queue still
            # gets the busy answer.
            queued_text = str(call.arguments.get("text") or "").strip()
            if queued_text and len(session.background_queue) < _BACKGROUND_QUEUE_MAX:
                session.background_queue.append(
                    {
                        "text": queued_text,
                        "profile": str(call.arguments.get("profile") or "").strip() or None,
                    }
                )
                position = len(session.background_queue)
                await self._send(
                    ws,
                    session,
                    {
                        "type": "hermes.run.queued",
                        "source": "hermes",
                        "session_id": session.chat_session_id,
                        "chat_session_id": session.chat_session_id,
                        "run_id": session.hermes_run_id,
                        "queued_count": position,
                        "queue_position": position,
                        "transcript_preview": _compact_status_text(queued_text),
                    },
                )
                return {
                    "ok": True,
                    "status": "queued",
                    "queue_position": position,
                    "instruction": (
                        "This request is queued and will start automatically "
                        "when the current background task finishes — tell the "
                        "user that in one short sentence. Do not claim it is "
                        "running yet, and never say run IDs, session IDs, or "
                        "other identifiers aloud."
                    ),
                    "interface": _interface_context(session),
                }
            return {
                "ok": True,
                "status": "already_running",
                "run_id": session.hermes_run_id,
                "session_id": session.chat_session_id,
                "instruction": (
                    "A previous task is still running in the background and "
                    "the task queue is full. Tell the user the earlier work "
                    "is still in progress; they can wait, ask for status, or "
                    "cancel it (hermes_cancel) before starting something new. "
                    "Never say run IDs or other identifiers aloud."
                ),
                "interface": _interface_context(session),
            }

        # Adaptive promotion: the Hermes event stream flags this the moment a
        # known-long tool starts (cron/desktop/browser/...), so the turn can
        # promote right away instead of waiting out the full grace window.
        long_tool_event = asyncio.Event()
        session.long_tool_event = long_tool_event
        task = asyncio.create_task(self._execute_brokered_tool(ws, session, call))
        task.add_done_callback(_log_task_failure(session, "hermes_task"))
        session.hermes_task = task
        # Tier C: an explicit mode="background" request detaches immediately,
        # even when grace-period promotion is otherwise off.
        force_background = str(call.arguments.get("mode") or "").strip().lower() == "background"
        promote_after = 0.0 if force_background else self._promote_after_seconds(session)
        try:
            if promote_after is None:
                return await task
            # Shield so a promotion timeout cancels only the wait, not the run.
            shielded = asyncio.ensure_future(asyncio.shield(task))
            long_tool_waiter = asyncio.create_task(long_tool_event.wait())
            long_tool_seen = False
            try:
                done, _ = await asyncio.wait(
                    {shielded, long_tool_waiter},
                    timeout=promote_after,
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if shielded in done:
                    # Completed (or raised) within the grace window: Tier A.
                    return shielded.result()
                if long_tool_waiter in done:
                    # A known-long tool started mid-grace. Give the run a short
                    # quick-finish window (a long-CLASS tool can still be a fast
                    # call), then promote early instead of burning the full
                    # grace window on a run that is now expected to be long.
                    long_tool_seen = True
                    quick_finish = min(
                        _LONG_TOOL_QUICK_FINISH_SECONDS,
                        max(promote_after, 0.1),
                    )
                    with contextlib.suppress(asyncio.TimeoutError):
                        return await asyncio.wait_for(
                            asyncio.shield(task), timeout=quick_finish
                        )
            finally:
                long_tool_waiter.cancel()
                if not shielded.done():
                    shielded.cancel()
            # Tier B/C: detach the run to the background and hand control back
            # to the pump (ADR 33). The task keeps running;
            # _deliver_background_result awaits and delivers it.
            promote_reason = (
                "explicit_background"
                if force_background
                else ("long_tool_started" if long_tool_seen else "grace_elapsed")
            )
            session.hermes_run_tier = "durable" if force_background else "promoted"
            session.promoted_transcript = str(call.arguments.get("text") or "").strip()
            self._log(
                session,
                "voice.hermes_run.promoted",
                {
                    "type": "voice.hermes_run.promoted",
                    "run_id": session.hermes_run_id,
                    "promote_after_ms": session.promote_after_ms,
                    "call_id": call.call_id,
                    "reason": promote_reason,
                },
            )
            return {
                "ok": True,
                "promoted": True,
                "run_id": session.hermes_run_id,
                "session_id": session.chat_session_id,
                "interface": _interface_context(session),
            }
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
            if session.long_tool_event is long_tool_event:
                session.long_tool_event = None
            # A promoted task is still running; keep it referenced for delivery.
            if session.hermes_task is task and task.done():
                session.hermes_task = None

    async def _run_fast_lane_task(
        self,
        session: RealtimeAgentSession,
        call: ToolCallEvent,
    ) -> dict[str, Any] | None:
        """Inline-only second ``hermes_run_task`` while a durable run is detached.

        Background-run v2 §1 ("fast lane"). Runs the request on a SEPARATE
        ephemeral Hermes session (``session_id=None`` — a fresh api_server
        session) so it cannot interleave with the detached run's gateway
        session, and keeps every observation in locals so it touches NONE of
        the session's ``hermes_*`` run state — run_id, status, progress
        counters, and the chip all stay owned by the in-flight run.

        Returns the tool result dict when the task completes inside the grace
        window, an error dict on a fast-lane failure, or ``None`` when the
        request would promote — grace elapsed, a known-long tool started,
        explicit ``mode=background``, or promotion disabled — in which case
        the caller falls through to the busy answer. An abandoned attempt is
        cancelled client-side; the server side may still finish it into the
        ephemeral session (at-least-once, same property as promotion), where
        it is simply never read.

        Deliberately silent on the client event stream: a fast-lane answer is
        bounded by the grace window (a few seconds), so it needs no chip or
        progress events of its own — and emitting them would fight the
        detached run's chip.
        """
        promote_after = self._promote_after_seconds(session)
        if promote_after is None or promote_after <= 0:
            # Promotion off ⇒ runs are inline-forever; an unbounded second
            # inline run is exactly the collision the busy answer prevents.
            return None
        if str(call.arguments.get("mode") or "").strip().lower() == "background":
            return None  # explicit background request needs the (occupied) slot
        text = str(call.arguments.get("text") or "").strip()
        if not text:
            return {"ok": False, "error": "hermes_run_task requires text"}
        profile = str(call.arguments.get("profile") or session.profile or "").strip() or None
        interface_context = _interface_context(session)
        long_tool_hints = _long_tool_hints()

        final_parts: list[str] = []
        error_message: str | None = None
        long_tool_name: str | None = None
        tool_count = 0

        async def _consume() -> None:
            nonlocal error_message, long_tool_name, tool_count
            async for hermes_event in self.hermes.stream_task(
                HermesTaskRequest(
                    text=text[:5000],
                    profile=profile,
                    # One reused side-session per voice session (created on
                    # the first fast-lane ask) instead of a fresh session per
                    # quick lookup — keeps the drawer clean and gives later
                    # fast-lane asks the earlier ones as context.
                    session_id=session.fast_lane_session_id,
                    bearer_token=session.bearer_token,
                    interface_context=interface_context,
                )
            ):
                etype = str(hermes_event.get("type") or "")
                if etype == "hermes.session.bound":
                    bound = str(hermes_event.get("session_id") or "").strip()
                    if bound:
                        session.fast_lane_session_id = bound
                elif etype == "hermes.tool.started":
                    tool_name = str(hermes_event.get("tool_name") or "")
                    lowered = tool_name.lower()
                    if any(hint in lowered for hint in long_tool_hints):
                        # This request is now expected to be long — it would
                        # promote on the main lane, so the fast lane abandons
                        # it instead of racing the grace window.
                        long_tool_name = tool_name
                        return
                elif etype == SERVER_EVT_RESPONSE_DELTA:
                    final_parts.append(str(hermes_event.get("delta") or ""))
                elif etype == "voice.response.turn_completed":
                    content = str(hermes_event.get("content") or "")
                    if content and not final_parts:
                        final_parts.append(content)
                elif etype == "hermes.tool.completed":
                    tool_count += 1
                elif etype == "voice.error":
                    error_message = str(hermes_event.get("message") or "Hermes error")

        consume_task = asyncio.create_task(_consume())
        try:
            await asyncio.wait_for(consume_task, timeout=promote_after)
        except asyncio.TimeoutError:
            self._log(
                session,
                "voice.hermes_fast_lane.abandoned",
                {
                    "type": "voice.hermes_fast_lane.abandoned",
                    "reason": "grace_elapsed",
                    "grace_ms": int(promote_after * 1000),
                },
            )
            return None
        except Exception as exc:  # infrastructure failure — report, don't busy
            self._log(
                session,
                "voice.hermes_fast_lane.error",
                {
                    "type": "voice.hermes_fast_lane.error",
                    "error": f"{exc.__class__.__name__}: {exc}",
                },
            )
            return {
                "ok": False,
                "error": f"fast-lane task failed: {exc}",
                "interface": interface_context,
            }
        if long_tool_name is not None:
            self._log(
                session,
                "voice.hermes_fast_lane.abandoned",
                {
                    "type": "voice.hermes_fast_lane.abandoned",
                    "reason": "long_tool_started",
                    "tool_name": long_tool_name,
                },
            )
            return None
        if error_message:
            return {"ok": False, "error": error_message, "interface": interface_context}
        final_text = "".join(final_parts).strip()
        speech_safe_text = _provider_safe_answer_for_speech(final_text)
        self._log(
            session,
            "voice.hermes_fast_lane.completed",
            {
                "type": "voice.hermes_fast_lane.completed",
                "tool_count": tool_count,
                "answer_chars": len(speech_safe_text),
            },
        )
        return {
            "ok": True,
            "status": "completed",
            "fast_lane": True,
            "text": speech_safe_text,
            "answer": speech_safe_text,
            "summary": speech_safe_text[:1200],
            "tool_count": tool_count,
            "interface": interface_context,
            "note": (
                "Answered inline on a side session; the earlier background "
                "task is still running and will report separately."
            ),
            "spoken_response": "provider_generated_after_hermes_result",
            "provider_instruction": (
                "Use the Hermes text/answer fields as the authoritative context "
                "for the spoken reply. Summarize naturally; do not say you lack context. "
                "Do not read raw JSON, logs, tables, IDs, or command output verbatim."
            ),
        }

    def _promote_after_seconds(self, session: RealtimeAgentSession) -> float | None:
        """Grace window before a run is promoted, or None when promotion is off."""
        if not session.promotion_enabled:
            return None
        return max(0.0, session.promote_after_ms / 1000.0)

    async def _begin_background_delivery(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        *,
        origin: str,
        call_id: str | None,
        transcript: str,
    ) -> None:
        """Hand a promoted run off to the background and notify the client.

        The Hermes task keeps running (it is still referenced by
        ``session.hermes_task``); ``_deliver_background_result`` awaits it and
        speaks the result when the floor is clear.
        """
        session.promoted_transcript = transcript or session.promoted_transcript
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_HERMES_RUN_PROMOTED,
                "source": "hermes",
                "session_id": session.chat_session_id,
                "chat_session_id": session.chat_session_id,
                "run_id": session.hermes_run_id,
                "tier": session.hermes_run_tier if session.hermes_run_tier in ("promoted", "durable") else "promoted",
                "promote_after_ms": session.promote_after_ms,
                "spoken_handoff": session.spoken_handoff,
                "result_delivery": session.result_delivery,
                "call_id": call_id,
                "queued_count": len(session.background_queue),
                "origin": origin,
            },
        )
        speak = session.spoken_handoff and session.result_delivery != "visual_only"
        if origin == "provider_tool_call" and call_id:
            # Close the pending provider function call so the socket is not left
            # awaiting tool output for the whole background run.
            # Deliberately NO run_id in the model-visible payload — the model
            # read it aloud ("the run ID is run_997f23ec…", observed live).
            # hermes_get_status / hermes_cancel default to the active run, so
            # the model never needs the id; the client gets it via events.
            interim = {
                "ok": True,
                "status": "running_in_background",
                "instruction": (
                    "The task is running in the background. Briefly acknowledge "
                    "that you've started and will report back; do not answer "
                    "yet. Never say run IDs, session IDs, or other identifiers "
                    "aloud. There is no task queue — do not offer to queue or "
                    "claim to have queued anything."
                ),
                "interface": _interface_context(session),
            }
            if await self._send_provider_tool_result(ws, session, connection, call_id, interim):
                if speak:
                    await self._request_provider_response(ws, session, connection, call_id)
        elif origin == "forced" and speak:
            with contextlib.suppress(Exception):
                await connection.request_response(
                    instructions=_background_handoff_prompt(transcript)
                )
        elif origin == "queued" and speak:
            # Broker-initiated start of a previously queued task — one short
            # spoken transition so the user knows the next item began. Wait
            # for the floor first: a fallback TTS render for the previous
            # result may still be speaking, and the transition must not
            # overlap or clip it.
            await self._await_floor_idle_for_result(session)
            with contextlib.suppress(Exception):
                await connection.request_response(
                    instructions=_queued_start_prompt(transcript)
                )

        if (
            session.background_delivery_task is not None
            and not session.background_delivery_task.done()
        ):
            session.background_delivery_task.cancel()
        delivery = asyncio.create_task(
            self._deliver_background_result(ws, session, connection)
        )
        delivery.add_done_callback(_log_task_failure(session, "background_delivery_task"))
        session.background_delivery_task = delivery

    async def _deliver_background_result(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
    ) -> None:
        task = session.hermes_task
        if task is None:
            return
        try:
            # Bound the run: a hung tool (e.g. a stuck cron call) is cancelled and
            # surfaced as a timeout instead of pinning the delivery task forever.
            result = await asyncio.wait_for(task, timeout=_background_run_max_seconds())
        except asyncio.TimeoutError:
            if not task.done():
                task.cancel()
            session.hermes_run_status = "timeout"
            await self._send(
                ws,
                session,
                {
                    "type": SERVER_EVT_HERMES_RUN_BACKGROUND_COMPLETED,
                    "source": "hermes",
                    "session_id": session.chat_session_id,
                    "chat_session_id": session.chat_session_id,
                    "run_id": session.hermes_run_id,
                    "ok": False,
                    "error": "background run timed out",
                },
            )
            await self._send_error(
                ws,
                session,
                "The background task ran too long and was stopped.",
                provider=session.provider,
            )
            session.hermes_run_tier = "foreground"
            return
        except asyncio.CancelledError:
            session.hermes_run_status = "cancelled"
            await self._send(
                ws,
                session,
                {
                    "type": "hermes.run.cancelled",
                    "session_id": session.chat_session_id,
                    "run_id": session.hermes_run_id,
                },
            )
            session.hermes_run_tier = "foreground"
            return
        except Exception as exc:  # noqa: BLE001 - surface as a voice error
            await self._send_error(
                ws,
                session,
                f"background Hermes run failed: {exc.__class__.__name__}: {exc}",
                provider=session.provider,
            )
            session.hermes_run_tier = "foreground"
            return
        finally:
            if session.hermes_task is task:
                session.hermes_task = None
            # Queue: the slot is free (whatever the outcome) — start the next
            # queued task. The starter itself waits for the current summary to
            # settle before speaking, so it never stomps the delivery; a
            # cancel already cleared the queue, making this a no-op there.
            if session.background_queue and not session.closed:
                queued_start = asyncio.create_task(
                    self._start_next_queued_run(ws, session, connection)
                )
                queued_start.add_done_callback(
                    _log_task_failure(session, "queued_run_start_task")
                )

        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_HERMES_RUN_BACKGROUND_COMPLETED,
                "source": "hermes",
                "session_id": session.chat_session_id,
                "chat_session_id": session.chat_session_id,
                "run_id": session.hermes_run_id,
                "ok": bool(result.get("ok", True)),
                "tool_count": result.get("tool_count", session.hermes_completed_tool_count),
                "queued_count": len(session.background_queue),
            },
        )

        if result.get("cancelled"):
            session.hermes_run_tier = "foreground"
            return
        if result.get("ok") is False:
            await self._send_error(
                ws,
                session,
                str(result.get("error") or "Hermes failed"),
                provider=session.provider,
            )
            session.hermes_run_tier = "foreground"
            return

        # Keep the delivered result for the respeak affordance regardless of
        # the delivery mode below.
        session.last_background_result = dict(result)

        if session.result_delivery == "visual_only":
            await self._emit_background_text_only(ws, session, result)
            session.hermes_run_tier = "foreground"
            return

        # speak_verbatim / speak_when_idle / notify_then_speak: wait for the
        # floor to clear, then deliver exactly once through the provider
        # (exact reading vs. natural summary chosen by mode); the validator's
        # relay-TTS fallback and the delivery-confirm alarm backstop it.
        session.floor.note_result_ready()
        floor_idle = await self._await_floor_idle_for_result(session)
        # If the phone is detached, don't speak into the void: the provider's
        # summary audio would only land in the bounded replay ring, and a long
        # summary can evict its own head before the phone resumes. Hold the
        # result; resume injects it, and _close_native_session pushes the
        # proactive fallback if the session dies first.
        attached = session.attached_ws
        if attached is None or attached.closed:
            session.pending_background_result = dict(result)
            self._log(
                session,
                "voice.realtime_agent.result_deferred",
                {
                    "type": "voice.realtime_agent.result_deferred",
                    "run_id": session.hermes_run_id,
                    "reason": "websocket_detached",
                },
            )
            session.hermes_run_tier = "foreground"
            return
        # Only interrupt the provider when it's likely still speaking (the floor
        # didn't clear). If the floor is already idle there's no active response
        # to cancel — cancelling anyway makes xAI emit a benign "no active
        # response" notice that used to tear the whole voice turn down.
        await self._inject_background_summary(
            ws, session, connection, result, cancel_current=not floor_idle
        )
        session.hermes_run_tier = "foreground"
        # Delivered-or-alarm: the summary must actually land (finished or
        # early-committed streaming) within the confirm window, else the
        # answer is force-emitted as text — it can never be silently lost to
        # filler the validator missed, a dead response, or a stray cancel.
        confirm = asyncio.create_task(
            self._confirm_background_delivery(ws, session, dict(result))
        )
        confirm.add_done_callback(
            _log_task_failure(session, "delivery_confirm_task")
        )

    async def _confirm_background_delivery(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        result: dict[str, Any],
    ) -> None:
        """Force a text emit when a spoken background delivery never lands."""
        await asyncio.sleep(_DELIVERY_CONFIRM_SECONDS)
        if session.closed:
            return
        if session.native_forced_summary_done or session.native_forced_summary_committed:
            return
        self._log(
            session,
            "voice.realtime_agent.delivery_unconfirmed",
            {
                "type": "voice.realtime_agent.delivery_unconfirmed",
                "run_id": session.hermes_run_id,
                "confirm_after_ms": int(_DELIVERY_CONFIRM_SECONDS * 1000),
            },
        )
        with contextlib.suppress(Exception):
            await self._emit_background_text_only(ws, session, result)

    async def _start_next_queued_run(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
    ) -> None:
        """Start the next queued background task once the slot + floor settle.

        Spawned when the active run's task completes (any outcome). Waits,
        bounded, for the current result's spoken summary to finish so the
        queued task's handoff can't stomp the delivery; then runs the queued
        task as a durable background run through the same delivery machinery.
        """
        # Phase 1: wait (bounded ~10s) for the current result's spoken
        # summary to actually BEGIN. This task is spawned the moment the run's
        # task completes — before the delivery task has injected — so "no
        # summary active" is true trivially at first; starting then would race
        # the injection (correct ordering previously held only by task
        # scheduling luck). Cancel/timeout/visual-only paths never inject, so
        # the grace expiry lets those proceed.
        for _ in range(20):
            if session.closed:
                return
            if session.native_forced_summary_active or session.native_forced_summary_done:
                break
            await asyncio.sleep(0.5)
        # Phase 2: wait (bounded) for the active summary to finish speaking.
        for _ in range(int(_DELIVERY_CONFIRM_SECONDS * 2)):
            if session.closed:
                return
            if session.hermes_task is not None and not session.hermes_task.done():
                return  # something else took the slot (shouldn't happen)
            if session.native_forced_summary_done or not session.native_forced_summary_active:
                break
            await asyncio.sleep(0.5)
        if session.closed or not session.background_queue:
            return
        item = session.background_queue.popleft()
        text = str(item.get("text") or "").strip()
        if not text:
            return
        call = ToolCallEvent(
            call_id=f"queued-{session.event_seq + 1}",
            name="hermes_run_task",
            arguments={
                "text": text,
                "profile": item.get("profile"),
                "mode": "background",
            },
        )
        session.cancel_requested = False
        task = asyncio.create_task(self._execute_brokered_tool(ws, session, call))
        task.add_done_callback(_log_task_failure(session, "hermes_task"))
        session.hermes_task = task
        session.hermes_run_tier = "durable"
        self._log(
            session,
            "voice.hermes_run.queued_started",
            {
                "type": "voice.hermes_run.queued_started",
                "transcript_preview": _compact_status_text(text),
                "queued_remaining": len(session.background_queue),
            },
        )
        await self._begin_background_delivery(
            ws,
            session,
            connection,
            origin="queued",
            call_id=None,
            transcript=text,
        )

    async def _deliver_pending_background_result(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
    ) -> None:
        """Speak a background result that completed while the phone was away.

        Called after a successful resume + replay. The `background_completed`
        event already replayed from the ring; this injects the provider-spoken
        summary that was deliberately deferred while detached.
        """
        result = session.pending_background_result
        if result is None:
            return
        connection = session.native_connection
        if connection is None:
            return
        session.pending_background_result = None
        if session.result_delivery == "visual_only":
            await self._emit_background_text_only(ws, session, result)
            return
        session.floor.note_result_ready()
        floor_idle = await self._await_floor_idle_for_result(session)
        await self._inject_background_summary(
            ws, session, connection, result, cancel_current=not floor_idle
        )
        # Same delivered-or-alarm guarantee as the attached background path:
        # a resume-injected summary that never lands is force-emitted as text.
        confirm = asyncio.create_task(
            self._confirm_background_delivery(ws, session, dict(result))
        )
        confirm.add_done_callback(
            _log_task_failure(session, "delivery_confirm_task")
        )

    async def _await_floor_idle_for_result(
        self,
        session: RealtimeAgentSession,
        *,
        timeout: float = _BACKGROUND_FLOOR_WAIT_SECONDS,
    ) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            # The user is mid-utterance while live mic chunks are fresh — a
            # delivery starting now would speak over them (and ended their
            # recording, observed live). Hold until they've been quiet a
            # beat; the deadline still bounds the wait.
            input_quiet = (
                time.monotonic() - session.native_last_input_audio_at
                >= _DELIVERY_INPUT_QUIET_SECONDS
            )
            if input_quiet and session.floor.consume_result_if_idle():
                return True
            await asyncio.sleep(0.05)
        # Timed out waiting for the floor; deliver anyway.
        session.floor.clear_result()
        return False

    async def _speak_fallback_answer(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        result: dict[str, Any],
        *,
        reason: str,
    ) -> None:
        """Deliver the authoritative answer through relay TTS when the
        provider cannot speak it (dead socket / failed response request).

        Same mouth and delivery-note semantics as the validator's off-script
        fallback in `_finish_forced_summary_provider_response` — the answer
        always lands as audio even when the provider conversation is gone.
        """
        fallback_text = _forced_summary_fallback_text(result)
        session.native_pending_delivery_note = _delivery_note(fallback_text)
        session.native_forced_summary_active = False
        session.native_forced_summary_done = True
        session.native_forced_summary_response_id = None
        session.native_forced_summary_result = None
        session.native_forced_summary_buffer.clear()
        session.native_forced_summary_committed = False
        session.native_forced_summary_text_parts.clear()
        response_id = f"delivery-fallback-{session.event_seq + 1}"
        self._log(
            session,
            "voice.response.delivery_fallback",
            {
                "type": "voice.response.delivery_fallback",
                "response_id": response_id,
                "reason": reason,
                "chars": len(fallback_text),
            },
        )
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
                "response_id": response_id,
                "source": "hermes",
                "delivery": "fallback",
            },
        )
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_RESPONSE_DELTA,
                "source": "hermes",
                "delta": fallback_text,
                "response_id": response_id,
                "delivery": "fallback",
            },
        )
        await self._render_provider_audio(
            ws,
            session,
            fallback_text,
            {},
            response_id=response_id,
        )

    async def _preempt_pending_forced_summary(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        *,
        reason: str,
    ) -> None:
        """Reset forced-summary state for a new user turn.

        When a forced/exact delivery is still in flight, a plain state wipe
        would let its remaining deltas leak past the validator as a normal
        response AND silently drop the undelivered answer. Cancel the stale
        response and land the answer as text instead — speaking it now would
        collide with the user's new turn. A delivery that already streamed
        (committed) was heard, so it only needs the cancel.
        """
        was_active = session.native_forced_summary_active
        pending = session.native_forced_summary_result
        committed = session.native_forced_summary_committed
        session.native_forced_summary_active = False
        session.native_forced_summary_done = False
        session.native_forced_summary_response_id = None
        session.native_forced_summary_result = None
        session.native_forced_summary_buffer.clear()
        session.native_forced_summary_committed = False
        session.native_forced_summary_text_parts.clear()
        if not was_active:
            return
        # Concluded-by-preemption: the queued-run phase waits and the
        # delivery-confirm alarm both treat the lifecycle as settled.
        session.native_forced_summary_done = True
        with contextlib.suppress(Exception):
            await connection.cancel_response()
        self._log(
            session,
            "voice.realtime_agent.delivery_preempted",
            {
                "type": "voice.realtime_agent.delivery_preempted",
                "reason": reason,
                "had_undelivered_result": pending is not None and not committed,
                "committed": committed,
            },
        )
        if pending is not None and not committed:
            await self._emit_background_text_only(ws, session, pending)

    async def _inject_background_summary(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        connection: RealtimeAgentConnection,
        result: dict[str, Any],
        *,
        cancel_current: bool = True,
    ) -> None:
        transcript = session.promoted_transcript or ""
        session.native_forced_summary_active = True
        session.native_forced_summary_done = False
        session.native_forced_summary_response_id = None
        session.native_forced_summary_result = dict(result)
        session.native_forced_summary_buffer.clear()
        session.native_forced_summary_committed = False
        session.native_forced_summary_text_parts.clear()
        # Cancel only when a response is likely in flight (see caller). Cancelling
        # with nothing active makes xAI reply with a benign "no active response"
        # error that must not be treated as fatal.
        if cancel_current:
            with contextlib.suppress(Exception):
                await connection.cancel_response()
        try:
            await connection.request_response(
                instructions=_result_delivery_prompt(
                    session.result_delivery, transcript, result
                )
            )
        except Exception as exc:  # noqa: BLE001
            # The background_completed event + result are already recorded in the
            # event ring, so a resume will replay them; don't let a dead provider
            # socket turn result delivery into an unhandled background-task crash.
            self._log(
                session,
                "voice.realtime_agent.summary_send_failed",
                {
                    "type": "voice.realtime_agent.summary_send_failed",
                    "run_id": session.hermes_run_id,
                    "error": f"{exc.__class__.__name__}: {exc}",
                },
            )
            # The provider can't speak it, but the phone is (usually) still
            # attached — deliver through relay TTS instead of waiting for the
            # confirm alarm's text-only emit. Detached sessions keep the old
            # behavior: the resume replay + close-path proactive push carry it.
            attached = session.attached_ws
            if attached is not None and not attached.closed:
                await self._speak_fallback_answer(
                    ws, session, result, reason="summary_request_failed"
                )

    async def _emit_background_text_only(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
        result: dict[str, Any],
    ) -> None:
        text = str(result.get("text") or result.get("answer") or result.get("summary") or "").strip()
        # Text-only delivery also bypasses the provider's conversation — same
        # next-turn correction as the fallback path.
        if text:
            session.native_pending_delivery_note = _delivery_note(text)
        response_id = f"background-visual-{session.event_seq + 1}"
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
                "response_id": response_id,
                "source": "hermes",
                "delivery": "visual_only",
            },
        )
        if text:
            await self._send(
                ws,
                session,
                {
                    "type": SERVER_EVT_RESPONSE_DELTA,
                    "source": "hermes",
                    "delta": text,
                    "response_id": response_id,
                    "delivery": "visual_only",
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
                "response_id": response_id,
                "delivery": "visual_only",
            },
        )

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
                "active_tool_name": session.hermes_active_tool_name,
                "last_tool_name": session.hermes_last_tool_name,
                "last_tool_status": session.hermes_last_tool_status,
                "last_tool_message": session.hermes_last_tool_message,
                "completed_tool_count": session.hermes_completed_tool_count,
                "queued_count": len(session.background_queue),
                "queued_items": [
                    {
                        "position": idx + 1,
                        "request_preview": _compact_status_text(
                            str(item.get("text") or "")
                        ),
                    }
                    for idx, item in enumerate(session.background_queue)
                    if str(item.get("text") or "").strip()
                ],
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
        # Redundant answer capture: the gateway streams drafting text as a
        # `_thinking` pseudo-tool; if the response-delta path ever yields an
        # empty answer, the drafted text is the answer of last resort.
        thinking_parts: list[str] = []
        error_message: str | None = None
        session.cancel_requested = False
        session.hermes_run_status = "running"
        self._reset_hermes_progress_state(session)
        progress_task = asyncio.create_task(self._send_hermes_run_progress(ws, session))
        try:
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
                    if not session.hermes_answer_started:
                        session.hermes_answer_started = True
                        session.hermes_last_tool_message = "Hermes is drafting a response."
                        await self._send(
                            ws,
                            session,
                            {
                                "type": SERVER_EVT_HERMES_RUN_PROGRESS,
                                "source": "hermes",
                                "session_id": session.chat_session_id,
                                "chat_session_id": session.chat_session_id,
                                "run_id": session.hermes_run_id,
                                "status": session.hermes_run_status,
                                "message": session.hermes_last_tool_message,
                                "status_key": "run:drafting",
                                "should_speak": False,
                                "active_tool_name": session.hermes_active_tool_name,
                                "last_tool_name": session.hermes_last_tool_name,
                                "completed_tool_count": session.hermes_completed_tool_count,
                            },
                        )
                elif hermes_event.get("type") == "voice.response.turn_completed":
                    content = str(hermes_event.get("content") or "")
                    if content and not final_parts:
                        final_parts.append(content)
                elif (
                    hermes_event.get("type") == "hermes.tool.delta"
                    and str(hermes_event.get("tool_name") or "") == "_thinking"
                ):
                    drafted = str(hermes_event.get("delta") or "")
                    if drafted:
                        thinking_parts.append(drafted)
                elif hermes_event.get("type") == "voice.error":
                    error_message = str(hermes_event.get("message") or "Hermes error")
                for event_to_send in self._prepare_hermes_progress_events(session, hermes_event):
                    if not _should_forward_hermes_progress_event(event_to_send):
                        continue
                    await self._send(ws, session, _event_with_hermes_source(event_to_send))
                if session.cancel_requested:
                    session.hermes_run_status = "cancelled"
                    return {
                        "ok": False,
                        "cancelled": True,
                        "run_id": session.hermes_run_id,
                        "session_id": session.chat_session_id,
                        "interface": interface_context,
                    }
        finally:
            progress_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await progress_task

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
        if not final_text and thinking_parts:
            # Answer of last resort from the drafted (_thinking) text. Deltas
            # are usually incremental fragments (join), but some paths emit
            # whole-snapshot deltas — if the last part dominates, prefer it
            # over a self-duplicating join.
            joined = "".join(thinking_parts).strip()
            last = thinking_parts[-1].strip()
            final_text = last if len(last) >= 0.6 * len(joined) else joined
            self._log(
                session,
                "voice.hermes_run.answer_from_thinking",
                {
                    "type": "voice.hermes_run.answer_from_thinking",
                    "run_id": session.hermes_run_id,
                    "chars": len(final_text),
                },
            )
        speech_safe_text = _provider_safe_answer_for_speech(final_text)
        if session.hermes_run_status == "running":
            session.hermes_run_status = "completed"
        return {
            "ok": True,
            "status": "completed",
            "run_id": session.hermes_run_id,
            "session_id": session.chat_session_id,
            "profile": profile,
            "text": speech_safe_text,
            "answer": speech_safe_text,
            "summary": speech_safe_text[:1200],
            "raw_text_omitted": speech_safe_text != final_text[: len(speech_safe_text)],
            "tool_count": session.hermes_completed_tool_count,
            "last_tool_name": session.hermes_last_tool_name,
            "interface": interface_context,
            "spoken_response": "provider_generated_after_hermes_result",
            "provider_instruction": (
                "Use the Hermes text/answer fields as the authoritative context "
                "for the spoken reply. Summarize naturally; do not say you lack context. "
                "Do not read raw JSON, logs, tables, IDs, or command output verbatim."
            ),
        }

    def _reset_hermes_progress_state(self, session: RealtimeAgentSession) -> None:
        session.hermes_active_tool_call_id = None
        session.hermes_active_tool_name = None
        session.hermes_last_tool_name = None
        session.hermes_last_tool_status = None
        session.hermes_last_tool_message = None
        session.hermes_answer_started = False
        session.hermes_completed_tool_count = 0
        session.hermes_seen_tool_call_ids.clear()
        session.hermes_last_spoken_progress_at = 0.0
        session.hermes_last_spoken_progress_key = None

    def _prepare_hermes_progress_events(
        self,
        session: RealtimeAgentSession,
        hermes_event: dict[str, Any],
    ) -> list[dict[str, Any]]:
        event = dict(hermes_event)
        event_type = str(event.get("type") or "").strip()
        if "chat_session_id" not in event and session.chat_session_id:
            event["chat_session_id"] = session.chat_session_id
        if "run_id" not in event and session.hermes_run_id:
            event["run_id"] = session.hermes_run_id

        if event_type in {"hermes.tool.started", "hermes.tool.delta"}:
            call_id = _tool_call_key(event)
            tool_name = _tool_name_from_event(event)
            is_thinking_delta = event_type == "hermes.tool.delta" and (
                not tool_name or tool_name in {"hermes", "_thinking", "thinking"}
            )
            synthetic: list[dict[str, Any]] = []
            if (
                event_type == "hermes.tool.delta"
                and call_id
                and not is_thinking_delta
                and call_id not in session.hermes_seen_tool_call_ids
            ):
                synthetic.append(
                    {
                        "type": "hermes.tool.started",
                        "session_id": event.get("session_id") or session.chat_session_id,
                        "chat_session_id": event.get("chat_session_id") or session.chat_session_id,
                        "run_id": event.get("run_id") or session.hermes_run_id,
                        "tool_call_id": call_id,
                        "tool_name": tool_name or "hermes",
                        "arguments": event.get("arguments") or event.get("args"),
                        "synthetic": True,
                        "message": _tool_status_line(tool_name, started=True),
                    }
                )
            if call_id and not is_thinking_delta:
                session.hermes_seen_tool_call_ids.add(call_id)
                session.hermes_active_tool_call_id = call_id
            if tool_name and not is_thinking_delta:
                session.hermes_active_tool_name = tool_name
                session.hermes_last_tool_name = tool_name
                session.hermes_last_tool_status = "running"
                _flag_long_tool(session, tool_name)
            delta = str(event.get("delta") or event.get("message") or "").strip()
            if delta:
                session.hermes_last_tool_message = _compact_status_text(delta)
                event.setdefault("message", session.hermes_last_tool_message)
            return [*synthetic, event]

        if event_type in {"hermes.tool.completed", "hermes.tool.failed"}:
            call_id = _tool_call_key(event)
            tool_name = _tool_name_from_event(event)
            synthetic: list[dict[str, Any]] = []
            if call_id and call_id not in session.hermes_seen_tool_call_ids:
                synthetic_started = {
                    "type": "hermes.tool.started",
                    "session_id": event.get("session_id") or session.chat_session_id,
                    "chat_session_id": event.get("chat_session_id") or session.chat_session_id,
                    "run_id": event.get("run_id") or session.hermes_run_id,
                    "tool_call_id": call_id,
                    "tool_name": tool_name or "hermes",
                    "arguments": event.get("arguments") or event.get("args"),
                    "synthetic": True,
                    "message": _tool_status_line(tool_name, started=True),
                }
                synthetic.append(synthetic_started)
                session.hermes_seen_tool_call_ids.add(call_id)
            if event_type == "hermes.tool.completed":
                session.hermes_completed_tool_count += 1
                session.hermes_last_tool_status = "completed"
            else:
                session.hermes_last_tool_status = "failed"
            if tool_name:
                session.hermes_last_tool_name = tool_name
            if call_id and call_id == session.hermes_active_tool_call_id:
                session.hermes_active_tool_call_id = None
                session.hermes_active_tool_name = None
            elif tool_name and tool_name == session.hermes_active_tool_name:
                session.hermes_active_tool_name = None
            event.setdefault("message", _tool_status_line(tool_name, started=False))
            return [*synthetic, event]

        return [event]

    async def _send_hermes_run_progress(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeAgentSession,
    ) -> None:
        started_at = time.time()
        while True:
            await asyncio.sleep(_HERMES_PROGRESS_INTERVAL_SECONDS)
            now = time.time()
            status = session.hermes_run_status
            # Keep the heartbeat alive while the underlying run is still in
            # flight, even if `status` transiently drifts off "running" — the
            # client kills the turn after ~90s of websocket silence, so this is
            # the one thing keeping a long/background run's socket warm.
            if not _should_continue_heartbeat(
                session.hermes_task, status, session_closed=session.closed
            ):
                return
            elapsed_seconds = now - started_at
            message, status_key = _hermes_progress_status(session)
            speakable_progress = status_key != "run:drafting" and not (
                status_key.startswith("progress:")
                and "drafting a response" in status_key.lower()
            )
            coarse_key = _coarse_spoken_status_key(status, status_key)
            # Timer-driven spoken filler is opt-in (progress_spoken_after_ms
            # setting; 0 = off, the default). Milestone speech — the promotion
            # handoff, completion summary, and failures — is unaffected; the
            # progress *events* keep flowing for the visual chip either way.
            spoken_after = session.progress_spoken_after_seconds
            should_speak = (
                spoken_after > 0
                and speakable_progress
                and elapsed_seconds >= spoken_after
                and session.floor.can_speak(FloorMouth.ANDROID_FILLER)
                and _should_repeat_spoken_status(
                    now,
                    session.hermes_last_spoken_progress_at,
                    session.hermes_last_spoken_progress_key,
                    coarse_key,
                    repeat_after_seconds=(
                        session.progress_repeat_seconds
                        if session.progress_repeat_seconds > 0
                        else _HERMES_SPOKEN_PROGRESS_REPEAT_SECONDS
                    ),
                )
            )
            if should_speak:
                session.hermes_last_spoken_progress_at = now
                session.hermes_last_spoken_progress_key = coarse_key
            await self._send(
                ws,
                session,
                {
                    "type": SERVER_EVT_HERMES_RUN_PROGRESS,
                    "source": "hermes",
                    "session_id": session.chat_session_id,
                    "chat_session_id": session.chat_session_id,
                    "run_id": session.hermes_run_id,
                    "status": status,
                    "message": message,
                    "status_key": status_key,
                    "should_speak": should_speak,
                    "floor": session.floor.state_label(),
                    "tier": session.hermes_run_tier,
                    "active_tool_name": session.hermes_active_tool_name,
                    "last_tool_name": session.hermes_last_tool_name,
                    "completed_tool_count": session.hermes_completed_tool_count,
                    "elapsed_ms": int(elapsed_seconds * 1000),
                },
            )

    def _cancel_active_hermes(self, session: RealtimeAgentSession) -> None:
        session.cancel_requested = True
        session.hermes_run_status = "cancelled"
        # Cancelling the active run also drops everything queued behind it —
        # "stop" means stop, not "stop this one and start the next".
        session.background_queue.clear()
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
            "promotion": {
                "enabled": bool(settings["promotion_enabled"]),
                "promote_after_ms": int(settings["promote_after_ms"]),
                "background_default_mode": settings["background_default_mode"],
                "spoken_handoff": bool(settings["spoken_handoff"]),
                "progress_spoken_after_ms": int(settings["progress_spoken_after_ms"]),
                "progress_repeat_ms": int(settings["progress_repeat_ms"]),
                "result_delivery": settings["result_delivery"],
                "max_background_runs": int(settings["max_background_runs"]),
            },
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
        chunk_id = _int_option(payload, "chunk_id", session.input_chunk_seq + 1)
        session.input_chunk_seq = max(session.input_chunk_seq, chunk_id)
        await self._send(
            ws,
            session,
            {
                "type": SERVER_EVT_INPUT_AUDIO_RECEIVED,
                "input_chunk_id": chunk_id,
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
        *,
        response_id: str | None = None,
    ) -> None:
        queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()
        loop = asyncio.get_running_loop()
        output_path = session.event_log_path.with_suffix(".wav")
        provider_options = self._provider_options(session, payload)
        # Relay TTS is a primary mouth; take the floor for the whole render so it
        # cannot overlap provider audio or filler (ADR 33).
        session.floor.acquire(FloorMouth.RELAY_TTS)

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
                    "response_id": response_id,
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
            session.floor.release(FloorMouth.RELAY_TTS)
            await self._send_error(ws, session, str(exc), provider=session.provider)
            return
        except Exception as exc:
            session.floor.release(FloorMouth.RELAY_TTS)
            await self._send_error(
                ws,
                session,
                f"realtime agent provider failed: {exc.__class__.__name__}: {exc}",
                provider=session.provider,
            )
            return

        await self._send(
            ws,
            session,
            {
                "type": "voice.output_audio.done",
                "response_id": response_id,
            },
        )
        # The wav artifact is a debug tap: the audio already streamed to the
        # client as PCM, and a single session's renders were observed at
        # multiple MB. Keep it only when explicitly enabled; the retention
        # sweep bounds even the kept ones.
        keep_tap = bool(
            getattr(self.config, "realtime_voice_debug_audio_tap", False)
        )
        if not keep_tap:
            with contextlib.suppress(OSError):
                Path(response.audio_path).unlink()
        await self._send(
            ws,
            session,
            {
                "type": "voice.response.done",
                "provider": response.provider,
                "model": response.model,
                "voice": response.voice,
                "audio_path": str(response.audio_path) if keep_tap else "",
                "event_log_path": str(session.event_log_path),
                "chat_session_id": session.chat_session_id,
                "final_text": text,
                "response_id": response_id,
                "metrics": response.metrics.to_dict(),
                "metadata": _safe_metadata(response.metadata),
            },
        )
        session.floor.release(FloorMouth.RELAY_TTS)

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
        allowed = {
            "enabled",
            "provider",
            "model",
            "voice",
            "sample_rate",
            # ADR 33 promotion fields.
            "promotion_enabled",
            "promote_after_ms",
            "background_default_mode",
            "spoken_handoff",
            "progress_spoken_after_ms",
            "progress_repeat_ms",
            "result_delivery",
            "max_background_runs",
        }
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
        if "promotion_enabled" in payload:
            value = _bool_value(payload["promotion_enabled"])
            if value is None:
                raise web.HTTPBadRequest(text="promotion_enabled must be a boolean")
            updates["promotion_enabled"] = value
        if "spoken_handoff" in payload:
            value = _bool_value(payload["spoken_handoff"])
            if value is None:
                raise web.HTTPBadRequest(text="spoken_handoff must be a boolean")
            updates["spoken_handoff"] = value
        for ms_field, lo, hi in (
            ("promote_after_ms", 0, 120_000),
            ("progress_spoken_after_ms", 0, 600_000),
            ("progress_repeat_ms", 0, 600_000),
        ):
            if ms_field in payload:
                value = _int_value(payload[ms_field])
                if value is None or value < lo or value > hi:
                    raise web.HTTPBadRequest(text=f"{ms_field} must be between {lo} and {hi}")
                updates[ms_field] = value
        if "max_background_runs" in payload:
            value = _int_value(payload["max_background_runs"])
            if value is None or value < 1 or value > 4:
                raise web.HTTPBadRequest(text="max_background_runs must be between 1 and 4")
            updates["max_background_runs"] = value
        if "background_default_mode" in payload:
            mode = _bounded_string(payload["background_default_mode"], "background_default_mode", max_len=20)
            if mode not in ("promote", "foreground"):
                raise web.HTTPBadRequest(text="background_default_mode must be 'promote' or 'foreground'")
            updates["background_default_mode"] = mode
        if "result_delivery" in payload:
            delivery = _bounded_string(payload["result_delivery"], "result_delivery", max_len=24)
            if delivery not in (
                "speak_verbatim",
                "speak_when_idle",
                "notify_then_speak",
                "visual_only",
            ):
                raise web.HTTPBadRequest(
                    text=(
                        "result_delivery must be speak_verbatim, speak_when_idle, "
                        "notify_then_speak, or visual_only"
                    )
                )
            updates["result_delivery"] = delivery
        if not updates:
            raise web.HTTPBadRequest(text="no realtime agent config fields supplied")
        return updates

    def _new_event_log_path(self, session_id: str) -> Path:
        run_dir = _run_dir(self.config)
        run_dir.mkdir(parents=True, exist_ok=True)
        _sweep_run_dir(
            run_dir,
            retention_days=getattr(
                self.config, "realtime_voice_run_retention_days", 14
            ),
        )
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
            "resume_supported": True,
            "resume_ttl_ms": int(session.resume_ttl_seconds * 1000),
            "last_event_id": session.event_seq,
            "last_audio_event_id": session.audio_seq,
            "last_input_chunk_id": session.input_chunk_seq,
            "profile": session.profile,
            "chat_session_id": session.chat_session_id,
            "config_scope": session.config_scope,
            "config_path": str(session.config_path) if session.config_path else None,
            "tool_surface": list(_TOOL_SURFACE),
            "interface": _interface_context(session),
        }

    async def _send(
        self,
        ws: web.WebSocketResponse | None,
        session: RealtimeAgentSession,
        event: dict[str, Any],
        *,
        record: bool = True,
    ) -> None:
        if "event_id" not in event:
            session.event_seq += 1
            event["event_id"] = session.event_seq
        if event.get("type") == SERVER_EVT_OUTPUT_AUDIO_DELTA and "audio_event_id" not in event:
            session.audio_seq += 1
            event["audio_event_id"] = session.audio_seq
        event.setdefault("at_ms", round((time.time() - session.created_at) * 1000, 3))
        if record:
            snapshot = dict(event)
            session.event_ring.append(snapshot)
            if "audio_event_id" in snapshot:
                session.audio_ring.append(snapshot)
        self._log(session, str(event["type"]), event)
        target = session.attached_ws
        await self._send_json_best_effort(target, session, event)

    async def _send_json_best_effort(
        self,
        target: web.WebSocketResponse | None,
        session: RealtimeAgentSession,
        event: dict[str, Any],
    ) -> bool:
        if target is None or target.closed:
            return False
        try:
            await target.send_json(event)
            return True
        except (OSError, RuntimeError) as exc:
            logger.debug(
                "Realtime-agent send skipped for detached session %s: %s",
                session.session_id,
                exc,
            )
            return False

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
    context_block = _provider_context_block(session.context_messages)
    profile_block = _profile_prompt_block(session.profile_prompt_context)
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
        "If the user asks what is running, what is queued, whether the earlier "
        "background task finished, or asks for current voice-task status, call "
        "hermes_get_status rather than hermes_run_task; do not queue a status "
        "question behind the work it is asking about. "
        "Provider-native realtime sessions are ephemeral; Hermes is the durable "
        "conversation memory. The relay may seed recent normal chat and realtime "
        "voice turns into this provider session. Use that seeded context for "
        "follow-up references such as 'this', 'that', 'it', 'the previous result', "
        "or 'that integration' when it is sufficient. If the needed context is "
        "missing, stale, tool-derived, or requires verification, call hermes_run_task "
        "before answering. Do not say you lack context before a Hermes call. You "
        "may speak one brief acknowledgement such as 'I'll check Hermes' or "
        "'I'll check that' before the tool call, then call hermes_run_task "
        "immediately. Do not give a substantive answer until Hermes returns. "
        "The relay will provide restrained status while Hermes runs. "
        "Route through Hermes for latest/recent/versioned data, device/desktop/app "
        "state, personal/session/project context, side effects, high-stakes or "
        "precision-sensitive answers, explicit check/verify/look-up requests, and "
        "media, files, screenshots, attachments, or artifacts. Answer directly only "
        "for small talk, timeless facts, basic reasoning/math, wording help, or "
        "questions fully contained in the current utterance/session context. "
        "When Hermes returns tool output, speak a natural concise summary; do not "
        "claim missing context if the function output contains a result. If a "
        "message says Hermes has already handled the request, treat it as the "
        "final answer step: do not call tools, do not say you will check, and "
        "summarize the provided Hermes result directly. Format speech for "
        "listening: say dates, "
        "times, currency, percentages, versions, measurements, and counts in "
        "natural spoken form. Summarize long IDs, hashes, UUIDs, URLs, file paths, "
        "JSON, logs, stack traces, tables, and dense numeric strings instead of "
        "reading them character by character; include exact raw values only when "
        "short and important. If raw values are not useful to hear, say a brief "
        "label such as 'plus a few IDs and raw values' and summarize the meaning. "
        f"{context_block}"
        f"{profile_block}"
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


def _tool_call_from_provider_event(event: ProviderEvent) -> ToolCallEvent:
    call = event.payload.get("call")
    if isinstance(call, ToolCallEvent):
        return call
    return ToolCallEvent(
        call_id=str(event.payload.get("call_id") or ""),
        name=str(event.payload.get("name") or ""),
        arguments=dict(event.payload.get("arguments") or {}),
    )


def _should_forward_hermes_progress_event(event: dict[str, Any]) -> bool:
    event_type = str(event.get("type") or "").strip()
    return event_type not in {SERVER_EVT_RESPONSE_DELTA, "voice.response.turn_completed"}


def _parse_context_messages(value: Any) -> tuple[dict[str, str], ...]:
    if not isinstance(value, list):
        return ()
    messages: list[dict[str, str]] = []
    for item in value[-20:]:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "").strip().lower()
        if role not in {"user", "assistant", "system"}:
            continue
        content = _compact_context_text(str(item.get("content") or ""))
        if not content:
            continue
        source = _compact_context_text(str(item.get("source") or ""))[:80]
        message = {"role": role, "content": content}
        if source:
            message["source"] = source
        messages.append(message)
    return tuple(messages[-14:])


def _provider_context_block(messages: tuple[dict[str, str], ...]) -> str:
    if not messages:
        return ""
    lines = [
        "Recent shared chat context is seeded below. Treat it as prior turns "
        "from the same Hermes conversation, oldest to newest:"
    ]
    for message in messages:
        role = message.get("role", "user").capitalize()
        source = message.get("source")
        source_part = f" ({source})" if source else ""
        lines.append(f"- {role}{source_part}: {message.get('content', '')}")
    lines.append("End recent shared chat context. ")
    return "\n".join(lines) + "\n"


def _profile_prompt_context(config: RelayConfig, profile: str | None) -> dict[str, Any]:
    profile_name = (profile or "default").strip() or "default"
    profile_entry = _profile_entry(config, profile_name)
    if profile_entry is None and profile_name != "default":
        profile_entry = _profile_entry(config, "default")

    soul = ""
    description = ""
    if profile_entry:
        soul = _compact_profile_prompt_text(
            str(profile_entry.get("system_message") or ""),
            _PROFILE_SOUL_PROMPT_MAX_CHARS,
        )
        description = _compact_context_text(str(profile_entry.get("description") or ""))

    memories = _profile_memory_snippets(config, profile_name)
    return {
        "profile": profile_name,
        "description": description,
        "soul": soul,
        "memories": memories,
    }


def _profile_entry(config: RelayConfig, profile: str) -> dict[str, Any] | None:
    for entry in config.profiles:
        if not isinstance(entry, dict):
            continue
        if str(entry.get("name") or "") == profile:
            return entry
    return None


def _profile_memory_snippets(config: RelayConfig, profile: str) -> list[dict[str, str]]:
    home = _profile_home_for_prompt(config, profile)
    if home is None:
        return []
    memories_dir = home / "memories"
    if not memories_dir.is_dir():
        return []
    try:
        md_files = [
            path
            for path in memories_dir.iterdir()
            if path.is_file() and path.suffix == ".md"
        ]
    except OSError:
        return []
    priority = {"MEMORY.md": 0, "USER.md": 1}
    md_files.sort(key=lambda path: (priority.get(path.name, 2), path.name.lower()))

    entries: list[dict[str, str]] = []
    total_chars = 0
    for path in md_files[:_PROFILE_MEMORY_PROMPT_MAX_FILES]:
        remaining = _PROFILE_MEMORY_PROMPT_MAX_CHARS - total_chars
        if remaining <= 0:
            break
        limit = min(_PROFILE_MEMORY_FILE_PROMPT_MAX_CHARS, remaining)
        try:
            text = path.read_text(encoding="utf-8")[: limit + 1]
        except Exception:
            logger.warning(
                "Realtime profile memory prompt read failed for %s",
                path,
                exc_info=True,
            )
            continue
        content = _compact_profile_prompt_text(text, limit)
        if not content:
            continue
        total_chars += len(content)
        entries.append({"filename": path.name, "content": content})
    return entries


def _profile_home_for_prompt(config: RelayConfig, profile: str) -> Path | None:
    root = Path(config.hermes_config_path).expanduser().parent
    profile_name = (profile or "default").strip() or "default"
    if profile_name == "default":
        return root if root.exists() else None
    if profile_name in {".", ".."} or "/" in profile_name or "\\" in profile_name:
        return None
    home = root / "profiles" / profile_name
    return home if home.is_dir() else None


def _profile_prompt_block(context: dict[str, Any]) -> str:
    if not context:
        return ""
    soul = str(context.get("soul") or "").strip()
    memories = context.get("memories")
    description = str(context.get("description") or "").strip()
    if not soul and not description and not memories:
        return ""

    profile = str(context.get("profile") or "default")
    lines = [
        "Hermes profile identity context follows. Use it for voice, identity, "
        "style, and stable background only; call Hermes for current facts, "
        "tool work, confirmations, and anything requiring durable authority.",
        f"Profile: {profile}.",
    ]
    if description:
        lines.append(f"Description: {description}")
    if soul:
        lines.append("SOUL.md:")
        lines.append(soul)
    if isinstance(memories, list) and memories:
        lines.append("Profile memory snippets:")
        for item in memories:
            if not isinstance(item, dict):
                continue
            filename = str(item.get("filename") or "memory.md")
            content = str(item.get("content") or "").strip()
            if content:
                lines.append(f"[{filename}]")
                lines.append(content)
    lines.append("End Hermes profile identity context.")
    return "\n".join(lines) + "\n"


def _compact_profile_prompt_text(value: str, max_chars: int) -> str:
    text = value.strip()
    if not text:
        return ""
    if len(text) <= max_chars:
        return text
    return text[: max_chars - 3].rstrip() + "..."


def _compact_context_text(value: str) -> str:
    text = " ".join(value.strip().split())
    if len(text) <= 1500:
        return text
    return text[:1497].rstrip() + "..."


def _tool_call_key(event: dict[str, Any]) -> str:
    for key in ("tool_call_id", "call_id", "id"):
        value = event.get(key)
        if value:
            return str(value).strip()
    tool_name = _tool_name_from_event(event)
    return tool_name or ""


def _tool_name_from_event(event: dict[str, Any]) -> str:
    for key in ("tool_name", "name", "tool"):
        value = event.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if isinstance(value, dict):
            nested = value.get("name") or value.get("tool_name")
            if nested:
                return str(nested).strip()
    return ""


def _compact_status_text(value: str) -> str:
    text = " ".join(value.strip().split())
    if len(text) <= 120:
        return text
    return text[:117].rstrip() + "..."


def _provider_safe_answer_for_speech(value: str, *, max_chars: int = 1800) -> str:
    text = _strip_tts_source_lines(value).strip()
    if not text:
        return ""
    compact = " ".join(text.split())
    parsed = _try_parse_json(text)
    if isinstance(parsed, dict):
        summarized = _summarize_tool_payload_for_speech(parsed)
        if summarized:
            return summarized[:max_chars].rstrip()
    if isinstance(parsed, list):
        return f"Hermes returned {len(parsed)} structured result items; summarize the outcome without reading raw JSON."
    if len(compact) <= max_chars and not _looks_like_raw_machine_output(compact):
        return compact
    excerpt = compact[: max_chars - 3].rstrip()
    return f"{excerpt}..."


def _strip_tts_source_lines(value: str) -> str:
    """Drop source/citation/path lines that are useful on screen but poor TTS."""
    kept: list[str] = []
    in_source_block = False
    for raw_line in value.splitlines():
        stripped = raw_line.strip()
        lowered = stripped.lower()
        if not stripped:
            in_source_block = False
            kept.append(raw_line)
            continue
        if lowered.startswith((
            "source:",
            "sources:",
            "citation:",
            "citations:",
            "references:",
        )):
            in_source_block = True
            continue
        if in_source_block and _looks_like_source_list_line(stripped):
            continue
        kept.append(raw_line)
    return "\n".join(kept)


def _looks_like_source_list_line(line: str) -> bool:
    marker_stripped = line.lstrip("-*•0123456789. )\t")
    lowered = marker_stripped.lower()
    if lowered.startswith(("source:", "citation:", "reference:")):
        return True
    if "\\" in marker_stripped:
        return True
    if "/" in marker_stripped and any(
        part in lowered
        for part in (
            "/",
            "personal/",
            "household/",
            "documents/",
            "desktop/",
            "downloads/",
            "users/",
            "home/",
        )
    ):
        return True
    return False


def _try_parse_json(value: str) -> Any | None:
    stripped = value.strip()
    if not stripped or stripped[0] not in "{[":
        return None
    try:
        return json.loads(stripped)
    except Exception:
        return None


def _summarize_tool_payload_for_speech(payload: dict[str, Any]) -> str | None:
    name = str(payload.get("name") or payload.get("tool_name") or "").strip()
    description = str(payload.get("description") or "").strip()
    if name and description:
        return f"Hermes returned the {name} result: {_compact_status_text(description)}"

    output = payload.get("output")
    if isinstance(output, str) and output.strip():
        compact_output = " ".join(output.split())
        if len(compact_output) <= 700:
            return f"Hermes returned command output: {compact_output}"
        return (
            "Hermes returned command output. Key excerpt: "
            f"{compact_output[:700].rstrip()}..."
        )

    error = payload.get("error") or payload.get("message")
    if isinstance(error, str) and error.strip():
        return f"Hermes returned: {_compact_status_text(error)}"

    keys = [str(key) for key in payload.keys()][:6]
    if keys:
        return (
            "Hermes returned a structured tool result with fields "
            f"{', '.join(keys)}; summarize the outcome without reading raw JSON."
        )
    return None


def _looks_like_raw_machine_output(value: str) -> bool:
    raw_markers = ("{", "}", "[", "]", "===", "Traceback", "Exception:", "HTTP ", "\\n")
    if sum(1 for marker in raw_markers if marker in value) >= 2:
        return True
    if value.count(":") >= 8 and value.count(",") >= 8:
        return True
    return False


def _spoken_tool_label(tool_name: str | None) -> str:
    normalized = (tool_name or "").strip().lower()
    if not normalized:
        return "Hermes"
    if "desktop" in normalized:
        return "desktop"
    if normalized.startswith("android") or "phone" in normalized:
        return "phone"
    if "search" in normalized:
        return "search"
    if "browser" in normalized or "web" in normalized:
        return "web"
    if "terminal" in normalized or "shell" in normalized or normalized == "bash":
        return "terminal"
    if "skill" in normalized:
        return "Hermes skill"
    if "memory" in normalized:
        return "memory"
    if "file" in normalized or "read" in normalized or "write" in normalized:
        return "files"
    return normalized.replace("_", " ")


def _tool_status_line(tool_name: str | None, *, started: bool) -> str:
    label = _spoken_tool_label(tool_name)
    if started:
        if label == "terminal":
            return "Running command."
        if label == "desktop":
            return "Searching desktop."
        if label == "phone":
            return "Checking phone."
        if label == "web" or label == "search":
            return "Searching."
        if label == "Hermes skill":
            return "Checking Hermes skill."
        return f"Using {label}."
    if label == "terminal":
        return "Command finished."
    if label == "desktop":
        return "Desktop check finished."
    if label == "phone":
        return "Phone check finished."
    if label == "Hermes skill":
        return "Hermes skill loaded."
    return f"Finished {label}."


def _should_continue_heartbeat(
    task: asyncio.Task[Any] | None,
    status: str,
    *,
    session_closed: bool,
) -> bool:
    """Whether the Hermes run-progress heartbeat should keep ticking.

    The heartbeat is the only thing keeping the realtime websocket from going
    silent during a long/background Hermes run, and the client kills the turn
    after ~90s of silence. So the heartbeat must NOT self-terminate just because
    ``hermes_run_status`` momentarily drifts off ``running`` (e.g. a status that
    briefly reads ``completed``/``idle`` between SSE bursts on a still-running
    background run). It keeps ticking while the underlying task is alive and the
    socket is open; it only stops once the task is actually finished/None or the
    session has closed.
    """
    if session_closed:
        return False
    if task is not None and not task.done():
        # The run is still in flight regardless of the transient status label.
        return True
    # No live task: fall back to the status. Keep ticking only while a run is
    # genuinely active/awaiting input; otherwise the heartbeat has nothing to
    # guard and should stop.
    return status in {"running", "waiting_for_confirmation"}


def _coarse_spoken_status_key(status: str, status_key: str) -> str:
    """Collapse a fine-grained ``status_key`` to a coarse high-level key.

    The repeat gate should fire on a *meaningful* status change, not on tool
    *message* churn. ``status_key`` values like ``progress:<message text>`` vary
    every time a tool emits a new line even though the high-level state ("Hermes
    is working") is unchanged. Collapsing those to a single ``progress`` bucket
    means message-only churn no longer re-flags ``should_speak`` on the repeat
    cadence, while real transitions (entering/leaving a tool, confirmation,
    drafting) still register.
    """
    if status_key.startswith("progress:"):
        return f"{status}:progress"
    return f"{status}:{status_key}"


def _should_repeat_spoken_status(
    now: float,
    last_spoken_at: float,
    last_coarse_key: str | None,
    coarse_key: str,
    *,
    repeat_after_seconds: float = _HERMES_SPOKEN_PROGRESS_REPEAT_SECONDS,
) -> bool:
    """Whether a *repeat* spoken-progress nudge is warranted.

    Returns True when the coarse high-level status changed since the last spoken
    progress, OR when the same coarse status has persisted past the (now calmer)
    repeat window. A brand-new status (no prior spoken key) always qualifies.
    """
    if last_coarse_key is None or coarse_key != last_coarse_key:
        return True
    return (now - last_spoken_at) >= repeat_after_seconds


def _hermes_progress_status(session: RealtimeAgentSession) -> tuple[str, str]:
    if session.hermes_run_status == "waiting_for_confirmation":
        return "Waiting for confirmation.", "confirmation"
    if session.hermes_active_tool_name:
        line = _tool_status_line(session.hermes_active_tool_name, started=True)
        return line, f"tool:{session.hermes_active_tool_name}:running"
    if session.hermes_last_tool_status == "completed" and session.hermes_last_tool_name:
        label = _spoken_tool_label(session.hermes_last_tool_name)
        return f"Finished {label}; Hermes is summarizing.", f"tool:{session.hermes_last_tool_name}:done"
    if session.hermes_last_tool_status == "failed" and session.hermes_last_tool_name:
        label = _spoken_tool_label(session.hermes_last_tool_name)
        return f"{label.capitalize()} failed; Hermes is handling it.", f"tool:{session.hermes_last_tool_name}:failed"
    if session.hermes_last_tool_message:
        return session.hermes_last_tool_message, f"progress:{session.hermes_last_tool_message}"
    return "Waiting for Hermes response.", "run:waiting_hermes"


def _should_force_hermes_for_transcript(text: str) -> bool:
    return _force_hermes_reason_for_transcript(text) is not None


def _force_hermes_reason_for_transcript(text: str) -> str | None:
    normalized = " ".join(text.lower().strip().split())
    if not normalized:
        return None
    phrase_reasons = (
        ("look up", "lookup"),
        ("lookup", "lookup"),
        ("search", "research"),
        ("research", "research"),
        ("verify", "verification"),
        ("check", "check"),
        ("current", "current_data"),
        ("latest", "current_data"),
        ("recent", "current_data"),
        ("today", "current_time"),
        ("right now", "current_time"),
        ("news", "current_data"),
        ("status", "status"),
        ("queue", "status"),
        ("queued", "status"),
        ("version", "versioned_data"),
        ("release", "versioned_data"),
        ("logs", "logs"),
        ("adb", "device_logs"),
        ("desktop", "tool_surface"),
        ("phone", "tool_surface"),
        ("android", "tool_surface"),
        ("server", "tool_surface"),
        ("relay", "relay_context"),
        ("gateway", "relay_context"),
        ("hermes", "hermes_context"),
        ("tailscale", "network_context"),
        ("wifi", "network_context"),
        ("wi-fi", "network_context"),
        ("last call", "previous_tool_result"),
        ("last tool", "previous_tool_result"),
        ("previous call", "previous_tool_result"),
        ("previous tool", "previous_tool_result"),
        ("tool output", "previous_tool_result"),
        ("tool result", "previous_tool_result"),
        ("final tool", "previous_tool_result"),
        ("final output", "previous_tool_result"),
        ("no data back", "previous_tool_result"),
        ("data back", "previous_tool_result"),
        ("didn't get any data", "previous_tool_result"),
        ("did not get any data", "previous_tool_result"),
        ("didn't read", "previous_tool_result"),
        ("did not read", "previous_tool_result"),
        ("that call", "previous_tool_result"),
        ("that result", "previous_tool_result"),
        ("that output", "previous_tool_result"),
        ("what happened", "previous_tool_result"),
    )
    for phrase, reason in phrase_reasons:
        if phrase in normalized:
            return reason
    return None


def _forced_hermes_preamble_prompt(transcript: str) -> str:
    return (
        "This is a pre-Hermes acknowledgement turn for the user's voice request. "
        "Speak exactly: I'll check Hermes. Do not call tools. Do not add any "
        "other words. After this acknowledgement the relay will run Hermes.\n\n"
        f"User request: {transcript.strip()[:1000]}"
    )


def _delivery_note(delivered_text: str) -> str:
    """Next-turn correction after a system-side (non-provider) delivery."""
    return (
        "Context correction: the earlier background task has ALREADY "
        "COMPLETED, and its answer was already delivered to the user by the "
        f"system voice: \"{delivered_text.strip()[:400]}\". Treat that task "
        "as finished — do not say it is still running, and do not re-deliver "
        "the answer unless the user asks for it again."
    )


def _queued_start_prompt(transcript: str) -> str:
    return (
        "The previous background task has finished and its queued follow-up "
        "task is now starting in the background. Speak one very short, natural "
        "transition — e.g. 'Now starting on the next one.' Do not answer the "
        "request yet, do not call tools, and never say run IDs, session IDs, "
        "or other identifiers aloud.\n\n"
        f"Queued request now starting: {transcript.strip()[:1000]}"
    )


def _background_handoff_prompt(transcript: str) -> str:
    return (
        "The user's request is now running in the background and may take a "
        "little while. Speak one short, natural acknowledgement that you've "
        "started on it and will report back, for example: 'I'm on it — I'll let "
        "you know.' Do not call tools, do not answer the request yet, and never "
        "say run IDs, session IDs, or other identifiers aloud. There is no task "
        "queue — do not offer to queue or claim to have queued anything.\n\n"
        f"User request: {transcript.strip()[:1000]}"
    )


def _forced_hermes_summary_prompt(transcript: str, result: dict[str, Any]) -> str:
    answer = str(
        result.get("answer")
        or result.get("text")
        or result.get("summary")
        or result.get("error")
        or ""
    ).strip()
    if not answer:
        answer = "Hermes completed the request but did not return a spoken summary."
    summary = _provider_safe_answer_for_speech(answer, max_chars=1400)
    # Deliberately NO run/session ids here — anything present in these
    # instructions can end up spoken verbatim (observed live: the summary
    # response read a full 32-char run id aloud). Identifiers stay in the
    # client event stream, never in speech-composition context.
    metadata = {
        "tool_count": result.get("tool_count"),
        "last_tool_name": result.get("last_tool_name"),
    }
    return (
        "Hermes has already handled the user's previous voice request. "
        "This is the final spoken answer step. Speak the answer from the "
        "Hermes result below NOW, as a concise natural summary. Do not call "
        "any tools, do not say you will check or report back later, and never "
        "say run IDs, session IDs, or other identifiers aloud.\n\n"
        f"User request: {transcript.strip()[:1000]}\n"
        f"Hermes result: {summary}\n"
        f"Metadata: {json.dumps(metadata, sort_keys=True)}"
    )


def _forced_hermes_exact_prompt(transcript: str, result: dict[str, Any]) -> str:
    answer = str(
        result.get("answer")
        or result.get("text")
        or result.get("summary")
        or result.get("error")
        or ""
    ).strip()
    if not answer:
        answer = "Hermes completed the request but did not return a spoken summary."
    speech = _provider_safe_answer_for_speech(answer, max_chars=1400)
    # Same no-identifier rule as the summary prompt: anything present in
    # these instructions can be spoken aloud, so ids never appear here.
    return (
        "Hermes has already handled the user's previous voice request. "
        "This is the final spoken answer step. Read the answer below aloud "
        "NOW, word for word as written. Do not paraphrase, shorten, reorder, "
        "or add anything before or after it; only smooth over symbols or "
        "formatting that cannot be spoken. Do not call any tools and do not "
        "say you will check or report back later.\n\n"
        f"User request: {transcript.strip()[:1000]}\n"
        f"Answer to read: {speech}"
    )


def _answer_is_structured(result: dict[str, Any]) -> bool:
    """True when the authoritative answer is a JSON object/array.

    There is no meaningful word-for-word reading of structured output —
    `_provider_safe_answer_for_speech` rewrites it into a summarize-this
    meta-instruction, which would contradict the exact prompt's "do not
    paraphrase" framing — so exact mode defers to the summary prompt.
    """
    answer = str(
        result.get("answer")
        or result.get("text")
        or result.get("summary")
        or result.get("error")
        or ""
    ).strip()
    parsed = _try_parse_json(answer)
    return isinstance(parsed, (dict, list))


def _result_delivery_prompt(
    result_delivery: str, transcript: str, result: dict[str, Any]
) -> str:
    """Per-response instructions for a provider-spoken result delivery.

    Every spoken mode delivers through the realtime provider so the answer
    keeps the session's voice and tone. ``speak_verbatim`` asks for an exact
    reading of the authoritative Hermes answer (except structured JSON
    answers, which have no meaningful verbatim reading); the other spoken
    modes ask for a concise natural summary. The forced-summary validator
    falls back to relay TTS either way when the provider goes off-script.
    """
    if result_delivery == "speak_verbatim" and not _answer_is_structured(result):
        return _forced_hermes_exact_prompt(transcript, result)
    return _forced_hermes_summary_prompt(transcript, result)


def _forced_summary_tool_result(result: dict[str, Any]) -> dict[str, Any]:
    output = dict(result)
    output["ok"] = bool(output.get("ok", True))
    output["spoken_response"] = "provider_generated_after_cached_hermes_result"
    output["provider_instruction"] = (
        "Hermes already completed this request. Use this cached Hermes result "
        "as the authoritative tool output and speak the final concise answer now. "
        "Do not call another tool, do not say you will check, and do not read raw "
        "JSON, logs, IDs, or command output verbatim."
    )
    return output


def _forced_summary_fallback_text(result: dict[str, Any]) -> str:
    answer = str(
        result.get("answer")
        or result.get("text")
        or result.get("summary")
        or result.get("error")
        or ""
    ).strip()
    if not answer:
        answer = "Hermes completed the request but did not return a spoken summary."
    return _provider_safe_answer_for_speech(answer, max_chars=1400)


def _result_answer_text(result: dict[str, Any]) -> str:
    """The authoritative answer text of a Hermes result, for validation."""
    return str(
        result.get("answer")
        or result.get("text")
        or result.get("summary")
        or ""
    ).strip()


_ANSWER_EVIDENCE_STOPWORDS = frozenset(
    (
        "about", "after", "again", "along", "also", "back", "because", "been",
        "before", "being", "between", "both", "cannot", "could", "does",
        "doing", "done", "down", "each", "every", "finished", "from", "have",
        "here", "hermes", "into", "just", "know", "like", "more", "most",
        "much", "need", "only", "other", "over", "request", "result", "should",
        "some", "successfully", "sure", "task", "than", "that", "them", "then",
        "there", "these", "they", "this", "under", "very", "want", "well",
        "were", "what", "when", "where", "which", "while", "will", "with",
        "would", "your",
    )
)


def _answer_evidence_tokens(answer: str) -> set[str]:
    """Content-bearing tokens of the answer: any token with a digit, plus
    words >= 4 chars that aren't generic filler. Used to check that a spoken
    summary actually reflects the answer instead of deferral chatter."""
    tokens: set[str] = set()
    for raw in answer.lower().split():
        token = raw.strip(".,;:!?'\"()[]{}—–-*`")
        if not token:
            continue
        if any(ch.isdigit() for ch in token):
            tokens.add(token)
        elif len(token) >= 4 and token.isalpha() and token not in _ANSWER_EVIDENCE_STOPWORDS:
            tokens.add(token)
    return tokens


def _summary_overlap_hits(summary: str, answer: str) -> int:
    """Count WHOLE-WORD content tokens the summary shares with the answer.

    Returns -1 when the answer has no content tokens to check against (a bare
    'Done.') — the check is vacuous there. Whole-word matching matters:
    substring matching let "will START automatically" (a queue acknowledgement)
    count as evidence for an answer containing "starting" (observed live — a
    completed answer was lost behind a queue-ack that early-committed on one
    weak substring hit)."""
    evidence = _answer_evidence_tokens(answer)
    if not evidence:
        return -1
    words: set[str] = set()
    for raw in summary.lower().split():
        token = raw.strip(".,;:!?'\"()[]{}—–-*`")
        if token:
            words.add(token)
    return len(evidence & words)


def _summary_overlaps_answer(summary: str, answer: str) -> bool:
    """True when the summary shares at least one whole-word content token with
    the answer — positive evidence the model is actually delivering it.
    Vacuously true when the answer has no content tokens."""
    return _summary_overlap_hits(summary, answer) != 0


def _is_bad_forced_summary_response(text: str, answer: str | None = None) -> bool:
    return _bad_forced_summary_reason(text, answer) is not None


def _bad_forced_summary_reason(text: str, answer: str | None = None) -> str | None:
    normalized = " ".join(text.lower().strip().split())
    if not normalized:
        return "empty_summary"
    if "run id" in normalized or "hermes_run_task" in normalized:
        return "asked_for_run_id"
    bad_phrases = (
        "i'll check",
        "i will check",
        "let me check",
        "i can check",
        "i'll fetch",
        "i will fetch",
        "i can fetch",
        "share it and",
        "share the",
        "let me know what task",
        "what task you'd like",
        "what task you would like",
        "do you have",
        "handy",
        # Deferral filler in what should be the final answer (observed live:
        # "One moment while I look that up. I'll report back as soon as I
        # have the info." spoken INSTEAD of a completed result — the answer
        # was never delivered).
        "one moment",
        "i'll report back",
        "i will report back",
        "i'll look",
        "i will look",
        "looking that up",
        "looking into",
        "as soon as i have",
        # Queue-speak in a FINAL answer (observed live: the summary response
        # was "It's queued and will start automatically once the Minnesota
        # check finishes. I'll let you know…" — a queue acknowledgement
        # spoken instead of the completed result).
        "i'll let you know",
        "i will let you know",
        "it's queued",
        "is queued",
        "queued and will",
        "in the queue",
    )
    # A phrase that appears in the AUTHORITATIVE ANSWER itself is not
    # deferral evidence — a faithful exact reading of "your order is queued
    # for Friday" must not be flagged for containing "is queued". Only
    # phrases the model added on its own count against it.
    answer_normalized = " ".join(answer.lower().strip().split()) if answer else ""
    for phrase in bad_phrases:
        if phrase in normalized and phrase not in answer_normalized:
            return "acknowledgement_not_summary"
    if len(normalized) <= 80 and any(
        phrase in normalized
        for phrase in ("got it", "sure", "okay", "ok")
    ) and "check" in normalized:
        return "short_acknowledgement"
    # Positive check (blocklists chase phrasings; this one doesn't): the
    # summary must share content with the answer it claims to deliver.
    if answer is not None and not _summary_overlaps_answer(text, answer):
        return "no_answer_overlap"
    return None


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


def _new_resume_token() -> tuple[str, str]:
    token = secrets.token_urlsafe(32)
    return token, _token_hash(token)


def _configured_resume_ttl_seconds() -> float:
    value = _int_value(os.getenv("RELAY_VOICE_RESUME_TTL_MS"))
    if value is not None and value > 0:
        return value / 1000.0
    return _RESUME_TTL_SECONDS


def _background_detached_max_seconds() -> float:
    """How long a *detached* session with a live background run is kept alive."""
    value = _int_value(os.getenv("RELAY_VOICE_BACKGROUND_DETACHED_MAX_MS"))
    if value is not None and value > 0:
        return value / 1000.0
    return _BACKGROUND_DETACHED_MAX_SECONDS


def _background_run_max_seconds() -> float:
    """Hard ceiling on a single background run before it is timed out/cancelled."""
    value = _int_value(os.getenv("RELAY_VOICE_BACKGROUND_RUN_MAX_MS"))
    if value is not None and value > 0:
        return value / 1000.0
    return _BACKGROUND_RUN_MAX_SECONDS


_DEFAULT_LONG_TOOL_HINTS = (
    "cron",
    "desktop_",
    "browser",
    "execute_code",
    "terminal",
    "spawn",
)


def _long_tool_hints() -> tuple[str, ...]:
    """Substrings of tool names that mark a run as long the moment they start."""
    raw = os.getenv("RELAY_VOICE_LONG_TOOL_HINTS")
    if raw is None:
        return _DEFAULT_LONG_TOOL_HINTS
    return tuple(hint.strip().lower() for hint in raw.split(",") if hint.strip())


def _flag_long_tool(session: RealtimeAgentSession, tool_name: str) -> None:
    """Signal adaptive promotion when a known-long tool starts mid-grace-window."""
    event = session.long_tool_event
    if event is None or event.is_set():
        return
    lowered = tool_name.lower()
    if any(hint in lowered for hint in _long_tool_hints()):
        event.set()


def _log_task_failure(
    session: RealtimeAgentSession,
    label: str,
) -> Callable[[asyncio.Task[Any]], None]:
    """Done-callback that surfaces (and retrieves) unexpected task failures.

    Without this, an exception the task's own handlers missed dies as a silent
    "Task exception was never retrieved" long after the fact.
    """

    def _callback(task: asyncio.Task[Any]) -> None:
        if task.cancelled():
            return
        exc = task.exception()
        if exc is None:
            return
        logger.warning(
            "Realtime-agent %s failed for session %s: %s: %s",
            label,
            session.session_id,
            exc.__class__.__name__,
            exc,
        )

    return _callback


def _is_benign_provider_error(message: str) -> bool:
    """Provider 'errors' that must NOT tear down a live voice turn.

    Cancelling a response when none is active (the background-summary
    re-injection path) makes xAI emit 'Cancellation failed: no active response
    found'. Surfacing that as a fatal voice.error made the client close the
    session right before the summary was spoken, so the reply was never heard.
    """
    lowered = message.lower()
    return "no active response" in lowered or "cancellation failed" in lowered


def _is_provider_idle_timeout(message: str) -> bool:
    """Match provider conversation-inactivity closures.

    Observed live (xAI, 2026-07-08): "xAI Realtime error: Conversation timed
    out after 900.0 seconds due to inactivity".
    """
    lowered = message.lower()
    return "timed out" in lowered and "inactivity" in lowered


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


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


def _sweep_run_dir(run_dir: Path, *, retention_days: int) -> None:
    """Drop session artifacts (JSONL flight logs + wav taps) past retention.

    Runs at session-log creation, so a busy relay sweeps often and an idle
    one accumulates nothing new. The logs carry conversation transcripts —
    bounded retention is privacy hygiene as much as disk hygiene. 0 disables.
    Best-effort: a sweep failure must never block a new voice session.
    """
    if retention_days <= 0:
        return
    cutoff = time.time() - retention_days * 86400
    try:
        candidates = list(run_dir.glob("realtime-agent-*"))
    except OSError:
        return
    removed = 0
    for path in candidates:
        try:
            if path.is_file() and path.stat().st_mtime < cutoff:
                path.unlink()
                removed += 1
        except OSError:
            continue
    if removed:
        logger.info(
            "Realtime-agent run sweep removed %d artifact(s) older than %d day(s)",
            removed,
            retention_days,
        )


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
