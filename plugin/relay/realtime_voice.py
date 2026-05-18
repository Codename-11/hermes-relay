"""Realtime voice websocket bridge for the relay-mediated voice path."""

from __future__ import annotations

import asyncio
import base64
import json
import math
import os
import secrets
import struct
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from aiohttp import web, WSMsgType

from plugin.voice_lab.expressions import VoiceExpression
from plugin.voice_lab.metrics import MetricsRecorder
from plugin.voice_lab.providers.base import (
    ProviderRunError,
    ProviderUnavailable,
    VoiceRequest,
)
from plugin.voice_lab.registry import default_registry

from .config import (
    RelayConfig,
    default_realtime_voice_config_path,
    save_realtime_voice_config_file,
)
from .voice_auth import require_voice_auth

DEFAULT_SAMPLE_RATE = 24000
DEFAULT_CHANNELS = 1
DEFAULT_SAMPLE_WIDTH = 2
_TRUE_ENV_VALUES = {"1", "true", "yes", "on"}


@dataclass(slots=True)
class RealtimeVoiceSession:
    session_id: str
    provider: str
    model: str
    voice: str
    sample_rate: int
    created_at: float
    event_log_path: Path


@dataclass(frozen=True, slots=True)
class _RelayXAIToken:
    access_token: str
    source: str
    base_url: str | None = None


class RealtimeVoiceHandler:
    """Feature-flagged provider playground surface for Android dev builds."""

    def __init__(self, config: RelayConfig) -> None:
        self.config = config
        self.registry = default_registry()
        self.sessions: dict[str, RealtimeVoiceSession] = {}

    async def handle_config(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:realtime")
        return web.json_response(self.config_payload())

    async def handle_update_config(self, request: web.Request) -> web.StreamResponse:
        await require_voice_auth(request, "voice:realtime")
        payload = await _optional_json(request)
        updates = self._validate_config_updates(payload)
        config_path = save_realtime_voice_config_file(self.config, updates)
        body = self.config_payload()
        body["updated"] = sorted(updates)
        body["config_path"] = str(config_path)
        return web.json_response(body)

    async def handle_create_session(self, request: web.Request) -> web.StreamResponse:
        if not self.enabled:
            raise web.HTTPNotFound(text="realtime voice is disabled")
        await require_voice_auth(request, "voice:realtime")

        payload = await _optional_json(request)
        provider = _str_option(payload, "provider") or self.config.realtime_voice_provider
        model = _str_option(payload, "model") or self.config.realtime_voice_model
        voice = _str_option(payload, "voice") or self.config.realtime_voice_voice
        sample_rate = _int_option(
            payload,
            "sample_rate",
            self.config.realtime_voice_sample_rate or DEFAULT_SAMPLE_RATE,
        )
        self._validate_provider(provider)

        session_id = secrets.token_urlsafe(18)
        event_log_path = self._new_event_log_path(session_id)
        session = RealtimeVoiceSession(
            session_id=session_id,
            provider=provider,
            model=model,
            voice=voice,
            sample_rate=sample_rate,
            created_at=time.time(),
            event_log_path=event_log_path,
        )
        self.sessions[session_id] = session
        self._log(session, "voice.session.created")

        return web.json_response(
            {
                "success": True,
                "session_id": session_id,
                "websocket_path": f"/voice/realtime/{session_id}",
                "provider": provider,
                "model": model,
                "voice": voice,
                "sample_rate": sample_rate,
                "event_log_path": str(event_log_path),
                "protocol": "hermes.voice.realtime.v0",
            }
        )

    async def handle_ws(self, request: web.Request) -> web.StreamResponse:
        if not self.enabled:
            raise web.HTTPNotFound(text="realtime voice is disabled")
        await require_voice_auth(request, "voice:realtime")

        session_id = request.match_info.get("session_id", "")
        session = self.sessions.get(session_id)
        if session is None:
            raise web.HTTPNotFound(text="unknown realtime voice session")

        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=2 * 1024 * 1024)
        await ws.prepare(request)
        await self._send(ws, session, self._ready_event(session))

        input_audio_bytes = 0
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
                    input_audio_bytes += await self._handle_input_audio(
                        ws,
                        session,
                        payload,
                        input_audio_bytes,
                    )
                elif msg_type == "response.create":
                    if running is not None and not running.done():
                        await self._send_error(ws, session, "response already running")
                        continue
                    running = asyncio.create_task(
                        self._run_response(ws, session, payload, input_audio_bytes)
                    )
                elif msg_type == "session.close":
                    await ws.close()
                else:
                    await self._send_error(ws, session, f"unsupported message type: {msg_type}")
        finally:
            if running is not None and not running.done():
                running.cancel()
            self._log(session, "voice.session.closed")
        return ws

    @property
    def enabled(self) -> bool:
        if self.config.realtime_voice_enabled:
            return True
        return os.getenv("RELAY_REALTIME_VOICE_ENABLED", "").strip().lower() in _TRUE_ENV_VALUES

    def config_payload(self) -> dict[str, Any]:
        provider = self.config.realtime_voice_provider
        model = self.config.realtime_voice_model
        voice = self.config.realtime_voice_voice
        sample_rate = self.config.realtime_voice_sample_rate or DEFAULT_SAMPLE_RATE
        providers = [info.to_dict() for info in self.registry.provider_infos()]
        auth = _xai_auth_status(self.config)
        return {
            "success": True,
            "enabled": self.enabled,
            "protocol": "hermes.voice.realtime.v0",
            "config_path": str(_config_path_for_payload(self.config)),
            "default_provider": provider,
            "default_model": model,
            "default_voice": voice,
            "sample_rate": sample_rate,
            "providers": providers,
            "auth": auth,
        }

    async def _handle_input_audio(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeVoiceSession,
        payload: dict[str, Any],
        previous_bytes: int,
    ) -> int:
        audio64 = str(payload.get("audio_base64", "") or "").strip()
        if not audio64:
            await self._send_error(ws, session, "input_audio.append missing audio_base64")
            return 0
        try:
            chunk = base64.b64decode(audio64)
        except Exception:
            await self._send_error(ws, session, "input_audio.append audio_base64 is invalid")
            return 0
        event = {
            "type": "voice.input_audio.received",
            "byte_count": len(chunk),
            "total_bytes": previous_bytes + len(chunk),
            "sample_rate": _int_option(payload, "sample_rate", DEFAULT_SAMPLE_RATE),
        }
        await self._send(ws, session, event)
        return len(chunk)

    async def _run_response(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeVoiceSession,
        payload: dict[str, Any],
        input_audio_bytes: int,
    ) -> None:
        text = _str_option(payload, "text") or _str_option(payload, "prompt")
        if not text:
            text = (
                "Let me check that. One moment. I am testing the realtime "
                "voice provider path from the Android dev build."
            )
        text = text[:5000]
        tool_scaffold = bool(payload.get("tool_scaffold"))
        render_mode = (_str_option(payload, "render_mode") or "conversation").lower()

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
                "input_audio_bytes": input_audio_bytes,
            },
        )
        if tool_scaffold:
            await self._send(
                ws,
                session,
                {
                    "type": "voice.tool.requested",
                    "name": "voice_realtime_dev_check",
                    "arguments": {"input_audio_bytes": input_audio_bytes},
                },
            )
            await self._send(
                ws,
                session,
                {
                    "type": "voice.tool.completed",
                    "name": "voice_realtime_dev_check",
                    "result": "scaffold-only",
                },
            )

        queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()
        loop = asyncio.get_running_loop()
        recorder = MetricsRecorder()
        provider = self.registry.create(session.provider)
        output_path = session.event_log_path.with_suffix(".wav")
        provider_options = self._provider_options(session, payload, tool_scaffold)

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
            await self._send_error(ws, session, str(exc), provider=session.provider)
            return
        except Exception as exc:
            await self._send_error(
                ws,
                session,
                f"realtime provider failed: {exc.__class__.__name__}: {exc}",
                provider=session.provider,
            )
            return

        transcript = response.metadata.get("transcript")
        if transcript:
            await self._send(
                ws,
                session,
                {"type": "voice.transcript.final", "text": transcript},
            )

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
            },
        )

    def _provider_options(
        self,
        session: RealtimeVoiceSession,
        payload: dict[str, Any],
        tool_scaffold: bool,
    ) -> dict[str, Any]:
        provider_options = dict(payload.get("provider_options") or {})
        provider_options.setdefault("model", session.model)
        provider_options.setdefault("voice", session.voice)
        provider_options.setdefault("sample_rate", str(session.sample_rate))
        render_mode = _str_option(payload, "render_mode")
        if render_mode:
            provider_options.setdefault("render_mode", render_mode)
        if tool_scaffold:
            provider_options["tool_scaffold"] = "true"

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

    def _validate_config_updates(self, payload: dict[str, Any]) -> dict[str, Any]:
        updates: dict[str, Any] = {}
        allowed = {"enabled", "provider", "model", "voice", "sample_rate"}
        unsupported = sorted(set(payload) - allowed)
        if unsupported:
            raise web.HTTPBadRequest(
                text=f"unsupported realtime voice config field(s): {', '.join(unsupported)}"
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
            raise web.HTTPBadRequest(text="no realtime voice config fields supplied")
        return updates

    def _new_event_log_path(self, session_id: str) -> Path:
        run_dir = _run_dir(self.config)
        run_dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
        safe_id = "".join(ch for ch in session_id if ch.isalnum())[:12]
        return run_dir / f"realtime-{stamp}-{safe_id}.jsonl"

    def _ready_event(self, session: RealtimeVoiceSession) -> dict[str, Any]:
        return {
            "type": "voice.session.ready",
            "session_id": session.session_id,
            "provider": session.provider,
            "model": session.model,
            "voice": session.voice,
            "sample_rate": session.sample_rate,
            "event_log_path": str(session.event_log_path),
        }

    async def _send(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeVoiceSession,
        event: dict[str, Any],
    ) -> None:
        event.setdefault("at_ms", round((time.time() - session.created_at) * 1000, 3))
        self._log(session, event["type"], event)
        await ws.send_json(event)

    async def _send_error(
        self,
        ws: web.WebSocketResponse,
        session: RealtimeVoiceSession,
        message: str,
        **data: Any,
    ) -> None:
        await self._send(ws, session, {"type": "voice.error", "message": message, **data})

    def _log(
        self,
        session: RealtimeVoiceSession,
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


def _run_dir(config: RelayConfig) -> Path:
    configured = config.realtime_voice_run_dir or os.getenv("RELAY_REALTIME_VOICE_RUN_DIR")
    if configured:
        return Path(configured).expanduser()
    home = Path(os.getenv("HERMES_RELAY_HOME", str(Path.home() / ".hermes-relay")))
    return home / "realtime-voice-runs"


def _config_path_for_payload(config: RelayConfig) -> Path:
    configured = config.realtime_voice_config_path or os.getenv(
        "RELAY_REALTIME_VOICE_CONFIG"
    )
    if configured:
        return Path(configured).expanduser()
    return default_realtime_voice_config_path()


def _xai_auth_path(config: RelayConfig) -> Path:
    configured = config.realtime_voice_xai_oauth_path or os.getenv(
        "RELAY_REALTIME_VOICE_XAI_OAUTH_PATH"
    )
    if configured:
        return Path(configured).expanduser()
    home = Path(os.getenv("HERMES_RELAY_HOME", str(Path.home() / ".hermes-relay")))
    return home / "auth" / "xai-oauth.json"


def _read_relay_xai_oauth_token(config: RelayConfig) -> _RelayXAIToken | None:
    path = _xai_auth_path(config)
    try:
        store = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(store, dict):
        return None
    for label, tokens in _xai_oauth_token_candidates(store):
        access_token = str(tokens.get("access_token", "") or "").strip()
        if not access_token:
            continue
        expires_at_ms = _int_or_none(tokens.get("expires_at_ms"))
        if expires_at_ms is not None:
            now_ms = int(time.time() * 1000)
            if expires_at_ms <= now_ms + 120_000:
                continue
        base_url = str(
            tokens.get("base_url", "") or store.get("base_url", "") or ""
        ).strip()
        return _RelayXAIToken(
            access_token=access_token,
            source=f"{label} in {path}",
            base_url=base_url or None,
        )
    return None


def _xai_oauth_token_candidates(store: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    """Return supported xAI OAuth token payload shapes without exposing values."""
    candidates: list[tuple[str, dict[str, Any]]] = []
    tokens = store.get("tokens")
    if isinstance(tokens, dict):
        candidates.append(("relay xai oauth store", tokens))

    credential_pool = store.get("credential_pool")
    if isinstance(credential_pool, dict):
        pool_items = credential_pool.get("xai-oauth")
        if isinstance(pool_items, list):
            for item in pool_items:
                if isinstance(item, dict):
                    candidates.append(("Hermes auth credential_pool.xai-oauth", item))

    providers = store.get("providers")
    if isinstance(providers, dict):
        provider = providers.get("xai-oauth")
        if isinstance(provider, dict):
            provider_tokens = provider.get("tokens")
            if isinstance(provider_tokens, dict):
                candidates.append(("Hermes auth providers.xai-oauth", provider_tokens))

    candidates.append(("relay xai oauth root", store))
    return candidates


def _xai_auth_status(config: RelayConfig) -> dict[str, Any]:
    env_names = (
        "VOICE_TOOLS_XAI_KEY",
        "XAI_API_KEY",
        "GROK_API_KEY",
        "XAI_REALTIME_CLIENT_SECRET",
        "XAI_EPHEMERAL_TOKEN",
    )
    env_present = [name for name in env_names if os.getenv(name, "").strip()]
    oauth = _read_relay_xai_oauth_token(config)
    return {
        "xai_env": bool(env_present),
        "xai_env_names": env_present,
        "xai_oauth": oauth is not None,
        "xai_oauth_path": str(_xai_auth_path(config)),
        "xai_oauth_source": oauth.source if oauth else None,
    }


def _websocket_url_from_base(value: str | None) -> str | None:
    if not value:
        return None
    base = value.strip().rstrip("/")
    if not base:
        return None
    if base.startswith("wss://") or base.startswith("ws://"):
        return base if base.endswith("/realtime") else f"{base}/realtime"
    if base.startswith("https://"):
        out = f"wss://{base[len('https://'):]}"
        return out if out.endswith("/realtime") else f"{out}/realtime"
    if base.startswith("http://"):
        out = f"ws://{base[len('http://'):]}"
        return out if out.endswith("/realtime") else f"{out}/realtime"
    return None


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


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]
