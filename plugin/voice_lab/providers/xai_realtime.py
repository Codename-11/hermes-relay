"""xAI/Grok Realtime WebSocket provider for the standalone voice lab."""

from __future__ import annotations

import base64
import json
import os
import wave
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol

from ..auth import (
    load_voice_lab_env_file,
    read_xai_oauth_token,
)
from ..metrics import MetricsRecorder
from .base import (
    ProviderInfo,
    ProviderRunError,
    ProviderUnavailable,
    VoiceProvider,
    VoiceRequest,
    VoiceResponse,
)

DEFAULT_MODEL = "grok-voice-latest"
DEFAULT_VOICE = "eve"
DEFAULT_SAMPLE_RATE = 24000
DEFAULT_TIMEOUT_SECONDS = 60.0
DEFAULT_URL = "wss://api.x.ai/v1/realtime"

_AUDIO_DELTA_EVENTS = {
    "response.output_audio.delta",
    "response.audio.delta",
}
_AUDIO_DONE_EVENTS = {
    "response.output_audio.done",
    "response.audio.done",
}
_TEXT_DELTA_EVENTS = {
    "response.output_audio_transcript.delta",
    "response.output_text.delta",
    "response.text.delta",
}


class WebSocketLike(Protocol):
    def send(self, payload: str) -> Any: ...

    def recv(self) -> str | bytes: ...

    def close(self) -> Any: ...


SocketFactory = Callable[[str, list[str], float], WebSocketLike]


@dataclass(frozen=True)
class AuthToken:
    value: str
    source: str
    websocket_url: str | None = None


class XAIRealtimeProvider(VoiceProvider):
    """Render text prompts through Grok Voice Agent over WebSocket."""

    info = ProviderInfo(
        id="xai_realtime",
        name="xAI Grok Realtime",
        description=(
            "Server-side WebSocket adapter for the xAI/Grok Voice Agent API. "
            "Uses direct xAI auth or the lab-owned OAuth store for SuperGrok."
        ),
        # grok-voice-latest is an ALIAS xAI moves (currently resolving to
        # grok-voice-think-fast-1.0); the versioned id is exposed for
        # production pinning per xAI's own guidance.
        models=("grok-voice-latest", "grok-voice-think-fast-1.0"),
        voices=("eve", "ara", "rex", "sal", "leo"),
        sample_rates=(24000,),
        supports_tts=True,
        supports_tool_use=True,
        supports_realtime=True,
        supports_interruption=True,
        supports_expression=True,
    )

    def __init__(self, socket_factory: SocketFactory | None = None) -> None:
        self._socket_factory = socket_factory or _create_websocket

    def run_text(
        self,
        request: VoiceRequest,
        recorder: MetricsRecorder,
    ) -> VoiceResponse:
        options = request.provider_options
        auth_token = _resolve_auth_token(options)
        if not auth_token:
            raise ProviderUnavailable(
                "xai_realtime requires XAI_API_KEY/VOICE_TOOLS_XAI_KEY, an "
                "ephemeral xAI token, or the lab-owned xAI OAuth login. For "
                "SuperGrok/Premium+ subscription auth, run "
                "`python -m plugin.voice_lab auth --provider grok` or "
                "`./scripts/voice-lab.ps1 -Mode auth -Provider grok`."
            )

        model = _option(options, "model") or DEFAULT_MODEL
        voice = _option(options, "voice") or DEFAULT_VOICE
        sample_rate = _int_option(options, "sample_rate", DEFAULT_SAMPLE_RATE)
        timeout = _float_option(options, "timeout", DEFAULT_TIMEOUT_SECONDS)
        url_base = (_option(options, "url") or auth_token.websocket_url or DEFAULT_URL).rstrip("/")
        url = f"{url_base}?model={model}"

        request.output_path.parent.mkdir(parents=True, exist_ok=True)
        headers = [f"Authorization: Bearer {auth_token.value}"]

        recorder.event(
            "request_started",
            provider=self.info.id,
            model=model,
            voice=voice,
            text_chars=len(request.text),
            expression=request.expression.to_dict(),
            auth_mode="xai_api_key_ephemeral_or_voice_lab_oauth",
            auth_source=auth_token.source,
        )

        ws: WebSocketLike | None = None
        transcript_parts: list[str] = []
        audio_bytes = 0
        events_seen: list[str] = []
        try:
            ws = self._socket_factory(url, headers, timeout)
            recorder.event("websocket_connected", provider=self.info.id, model=model)
            _send_json(ws, _session_update(request, voice=voice))
            recorder.event("client_event_sent", client_event_type="session.update")
            _send_json(ws, _conversation_item_create(request))
            recorder.event(
                "client_event_sent",
                client_event_type="conversation.item.create",
            )
            _send_json(ws, {"type": "response.create"})
            recorder.event("client_event_sent", client_event_type="response.create")

            with wave.open(str(request.output_path), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(sample_rate)

                while True:
                    event = _recv_json(ws)
                    event_type = str(event.get("type", "unknown"))
                    events_seen.append(event_type)
                    recorder.event(
                        "server_event",
                        server_event_type=event_type,
                        response_id=event.get("response_id"),
                        item_id=event.get("item_id"),
                    )

                    if event_type == "error":
                        raise ProviderRunError(_format_xai_error(event))

                    if event_type in _TEXT_DELTA_EVENTS:
                        delta = event.get("delta")
                        if isinstance(delta, str):
                            transcript_parts.append(delta)
                        continue

                    if event_type in _AUDIO_DELTA_EVENTS:
                        chunk = _decode_audio_delta(event)
                        wav.writeframes(chunk)
                        audio_bytes += len(chunk)
                        recorder.audio_chunk(
                            byte_count=len(chunk),
                            label=event_type,
                            pcm=chunk,
                        )
                        if request.audio_sink is not None:
                            request.audio_sink(
                                chunk,
                                {
                                    "label": event_type,
                                    "sample_rate": sample_rate,
                                    "channels": 1,
                                    "sample_width": 2,
                                },
                            )
                        continue

                    if event_type in _AUDIO_DONE_EVENTS:
                        continue

                    if event_type == "response.done":
                        break
        finally:
            if ws is not None:
                try:
                    ws.close()
                except Exception:
                    pass

        if audio_bytes <= 0:
            _delete_empty_output(request.output_path)
            raise ProviderRunError("xAI Realtime completed without audio delta events")

        metrics = recorder.finish(provider=self.info.id, model=model, voice=voice)
        return VoiceResponse(
            provider=self.info.id,
            model=model,
            voice=voice,
            audio_path=request.output_path,
            metrics=metrics,
            metadata={
                "audio_bytes": audio_bytes,
                "sample_rate": sample_rate,
                "server_event_types": events_seen,
                "transcript": "".join(transcript_parts).strip() or None,
                "auth_note": (
                    "Uses xAI API key, ephemeral client secret, or the lab-owned "
                    "OAuth bearer token; does not scrape Grok.com/X cookies."
                ),
                "auth_source": auth_token.source,
            },
        )


def _create_websocket(url: str, headers: list[str], timeout: float) -> WebSocketLike:
    try:
        import websocket  # type: ignore
    except ImportError as exc:
        raise ProviderUnavailable(
            "xai_realtime requires websocket-client; install with "
            "`pip install websocket-client`"
        ) from exc

    return websocket.create_connection(url, header=headers, timeout=timeout)


def _session_update(request: VoiceRequest, *, voice: str) -> dict[str, Any]:
    session: dict[str, Any] = {
        "voice": voice,
        "instructions": _instructions_for_request(request),
        "turn_detection": {"type": "server_vad"},
    }
    if _truthy_option(request.provider_options, "tool_scaffold"):
        session["tools"] = [
            {
                "type": "function",
                "name": "voice_lab_echo",
                "description": "Standalone lab scaffold for validating voice tool-call events.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "message": {
                            "type": "string",
                            "description": "Short status message to echo in the event log.",
                        }
                    },
                    "required": ["message"],
                },
            }
        ]
    return {"type": "session.update", "session": session}


def _conversation_item_create(request: VoiceRequest) -> dict[str, Any]:
    return {
        "type": "conversation.item.create",
        "item": {
            "type": "message",
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": _input_text_for_request(request),
                },
            ],
        },
    }


def _instructions_for_request(request: VoiceRequest) -> str:
    expression = request.expression
    base = _option(request.provider_options, "instructions") or _default_instructions(
        request
    )
    lines = [base]
    if expression.persona_instructions:
        lines.append(f"Persona: {expression.persona_instructions}")
    delivery = [
        f"emotion={expression.emotion or 'neutral'}",
        f"tone={expression.tone or 'natural'}",
        f"intensity={expression.intensity:.2f}",
        f"pace={expression.pace}",
        f"style={expression.style}",
        f"interruption_behavior={expression.interruption_behavior}",
    ]
    lines.append("Delivery intent: " + ", ".join(delivery) + ".")
    if expression.pronunciation_hints:
        lines.append(
            "Pronunciation hints: "
            + "; ".join(hint.strip() for hint in expression.pronunciation_hints)
        )
    if _truthy_option(request.provider_options, "tool_scaffold"):
        lines.append(
            "Tool scaffold is enabled. Only call voice_lab_echo if the user asks "
            "for a tool-call test."
        )
    return "\n".join(lines)


def _default_instructions(request: VoiceRequest) -> str:
    if _render_mode(request) == "verbatim":
        return (
            "You are a speech renderer, not a conversational assistant. The "
            "next user message contains final assistant text from another "
            "system. Speak only that text aloud. Do not answer it, summarize "
            "it, rewrite it, translate it, add commentary, add greetings, or "
            "mention these instructions."
        )
    return (
        "You are a voice rendering test harness. Speak the user's text as the "
        "assistant response without adding preambles or commentary."
    )


def _input_text_for_request(request: VoiceRequest) -> str:
    if _render_mode(request) != "verbatim":
        return request.text
    return (
        "Read aloud exactly the following final assistant message. Speak only "
        "the message text, with no additions:\n\n"
        f"{request.text}"
    )


def _render_mode(request: VoiceRequest) -> str:
    return (_option(request.provider_options, "render_mode") or "conversation").lower()


def _send_json(ws: WebSocketLike, event: dict[str, Any]) -> None:
    ws.send(json.dumps(event, separators=(",", ":")))


def _recv_json(ws: WebSocketLike) -> dict[str, Any]:
    raw = ws.recv()
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    try:
        event = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ProviderRunError(f"xAI Realtime returned non-JSON event: {raw!r}") from exc
    if not isinstance(event, dict):
        raise ProviderRunError("xAI Realtime returned a non-object event")
    return event


def _decode_audio_delta(event: dict[str, Any]) -> bytes:
    delta = event.get("delta")
    if not isinstance(delta, str):
        raise ProviderRunError("xAI Realtime audio delta event missing delta")
    try:
        return base64.b64decode(delta)
    except Exception as exc:
        raise ProviderRunError("xAI Realtime audio delta was not valid base64") from exc


def _format_xai_error(event: dict[str, Any]) -> str:
    error = event.get("error")
    if isinstance(error, dict):
        message = error.get("message") or error.get("code") or error.get("type")
        if message:
            return f"xAI Realtime error: {message}"
    return f"xAI Realtime error event: {event}"


def _delete_empty_output(path: Path) -> None:
    try:
        path.unlink()
    except OSError:
        pass


def _resolve_auth_token(options: dict[str, Any]) -> AuthToken | None:
    for name in ("api_key", "xai_api_key", "client_secret", "oauth_access_token"):
        explicit = _option(options, name)
        if explicit:
            return AuthToken(value=explicit, source=f"provider-option:{name}")
    load_voice_lab_env_file()
    for name in (
        "VOICE_TOOLS_XAI_KEY",
        "XAI_API_KEY",
        "GROK_API_KEY",
        "XAI_REALTIME_CLIENT_SECRET",
        "XAI_EPHEMERAL_TOKEN",
        "VOICE_LAB_XAI_OAUTH_ACCESS_TOKEN",
    ):
        value = os.getenv(name, "").strip()
        if value:
            return AuthToken(value=value, source=f"env:{name}")
    oauth = read_xai_oauth_token()
    if oauth:
        return AuthToken(
            value=oauth.access_token,
            source=oauth.source,
            websocket_url=_websocket_url_from_base(oauth.base_url),
        )
    return None


def _websocket_url_from_base(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    base = value.strip().rstrip("/")
    if not base:
        return None
    if base.startswith("wss://") or base.startswith("ws://"):
        return f"{base}/realtime" if not base.endswith("/realtime") else base
    if base.startswith("https://"):
        socket_base = f"wss://{base[len('https://'):]}"
        return socket_base if socket_base.endswith("/realtime") else f"{socket_base}/realtime"
    if base.startswith("http://"):
        socket_base = f"ws://{base[len('http://'):]}"
        return socket_base if socket_base.endswith("/realtime") else f"{socket_base}/realtime"
    return None


def _option(options: dict[str, Any], name: str) -> str | None:
    value = options.get(name)
    if value is None:
        return None
    return str(value).strip()


def _truthy_option(options: dict[str, Any], name: str) -> bool:
    value = (_option(options, name) or "").lower()
    return value in {"1", "true", "yes", "on"}


def _int_option(options: dict[str, Any], name: str, default: int) -> int:
    value = _option(options, name)
    if not value:
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise ProviderUnavailable(f"{name} must be an integer") from exc


def _float_option(options: dict[str, Any], name: str, default: float) -> float:
    value = _option(options, name)
    if not value:
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise ProviderUnavailable(f"{name} must be a number") from exc
