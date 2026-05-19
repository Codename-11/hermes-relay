"""OpenAI Realtime WebSocket provider for the standalone voice lab."""

from __future__ import annotations

import base64
import json
import os
import wave
from collections.abc import Callable
from typing import Any, Protocol

from ..auth import load_voice_lab_env_file
from ..metrics import MetricsRecorder
from .base import (
    ProviderInfo,
    ProviderRunError,
    ProviderUnavailable,
    VoiceProvider,
    VoiceRequest,
    VoiceResponse,
)

DEFAULT_MODEL = "gpt-realtime-2"
DEFAULT_VOICE = "marin"
DEFAULT_OUTPUT_FORMAT = "audio/pcm"
DEFAULT_SAMPLE_RATE = 24000
DEFAULT_TIMEOUT_SECONDS = 60.0
DEFAULT_URL = "wss://api.openai.com/v1/realtime"

_AUDIO_DELTA_EVENTS = {
    "response.audio.delta",
    "response.output_audio.delta",
}
_AUDIO_DONE_EVENTS = {
    "response.audio.done",
    "response.output_audio.done",
}
_TEXT_DELTA_EVENTS = {
    "response.output_text.delta",
    "response.text.delta",
    "response.audio_transcript.delta",
}


class WebSocketLike(Protocol):
    def send(self, payload: str) -> Any: ...

    def recv(self) -> str | bytes: ...

    def close(self) -> Any: ...


SocketFactory = Callable[[str, list[str], float], WebSocketLike]


class OpenAIRealtimeProvider(VoiceProvider):
    """Render text prompts through OpenAI Realtime over a server-side socket."""

    info = ProviderInfo(
        id="openai_realtime",
        name="OpenAI Realtime",
        description=(
            "Server-side WebSocket adapter for OpenAI Realtime, defaulting to "
            "gpt-realtime-2 and WAV output for CLI testing."
        ),
        models=(DEFAULT_MODEL,),
        voices=(
            "alloy",
            "ash",
            "ballad",
            "coral",
            "echo",
            "sage",
            "shimmer",
            "verse",
            "marin",
            "cedar",
        ),
        sample_rates=(24000,),
        supports_tts=True,
        supports_realtime=True,
        supports_interruption=False,
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
        api_key = _resolve_api_key(options)
        if not api_key:
            raise ProviderUnavailable(
                "OPENAI_API_KEY or VOICE_TOOLS_OPENAI_KEY is required for "
                "provider openai_realtime"
            )

        model = _option(options, "model") or DEFAULT_MODEL
        voice = _option(options, "voice") or DEFAULT_VOICE
        output_format = _option(options, "output_format") or DEFAULT_OUTPUT_FORMAT
        if output_format != DEFAULT_OUTPUT_FORMAT:
            raise ProviderUnavailable(
                "openai_realtime currently supports output_format=audio/pcm only"
            )
        sample_rate = _int_option(options, "sample_rate", DEFAULT_SAMPLE_RATE)
        timeout = _float_option(options, "timeout", DEFAULT_TIMEOUT_SECONDS)
        url_base = (_option(options, "url") or DEFAULT_URL).rstrip("/")
        url = f"{url_base}?model={model}"
        safety_identifier = _option(options, "safety_identifier")

        request.output_path.parent.mkdir(parents=True, exist_ok=True)
        headers = [
            f"Authorization: Bearer {api_key}",
        ]
        if safety_identifier:
            headers.append(f"OpenAI-Safety-Identifier: {safety_identifier}")

        recorder.event(
            "request_started",
            provider=self.info.id,
            model=model,
            voice=voice,
            text_chars=len(request.text),
            expression=request.expression.to_dict(),
        )

        ws: WebSocketLike | None = None
        transcript_parts: list[str] = []
        audio_bytes = 0
        events_seen: list[str] = []
        try:
            ws = self._socket_factory(url, headers, timeout)
            recorder.event("websocket_connected", provider=self.info.id, model=model)
            _send_json(
                ws,
                _session_update(
                    request,
                    model=model,
                    voice=voice,
                    sample_rate=sample_rate,
                ),
            )
            recorder.event("client_event_sent", client_event_type="session.update")
            _send_json(ws, _conversation_item_create(request))
            recorder.event(
                "client_event_sent",
                client_event_type="conversation.item.create",
            )
            _send_json(ws, _response_create(voice=voice, sample_rate=sample_rate))
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
                        raise ProviderRunError(_format_openai_error(event))

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
            raise ProviderRunError(
                "OpenAI Realtime completed without audio delta events"
            )

        metrics = recorder.finish(provider=self.info.id, model=model, voice=voice)
        return VoiceResponse(
            provider=self.info.id,
            model=model,
            voice=voice,
            audio_path=request.output_path,
            metrics=metrics,
            metadata={
                "audio_bytes": audio_bytes,
                "output_format": output_format,
                "sample_rate": sample_rate,
                "server_event_types": events_seen,
                "transcript": "".join(transcript_parts).strip() or None,
            },
        )


def _create_websocket(url: str, headers: list[str], timeout: float) -> WebSocketLike:
    try:
        import websocket  # type: ignore
    except ImportError as exc:
        raise ProviderUnavailable(
            "openai_realtime requires websocket-client; install with "
            "`pip install websocket-client`"
        ) from exc

    return websocket.create_connection(url, header=headers, timeout=timeout)


def _session_update(
    request: VoiceRequest,
    *,
    model: str,
    voice: str,
    sample_rate: int,
) -> dict[str, Any]:
    return {
        "type": "session.update",
        "session": {
            "type": "realtime",
            "model": model,
            "instructions": _instructions_for_request(request),
            "output_modalities": ["audio"],
            "audio": {
                "output": {
                    "format": {
                        "type": DEFAULT_OUTPUT_FORMAT,
                        "rate": sample_rate,
                    },
                    "voice": voice,
                },
            },
        },
    }


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


def _response_create(*, voice: str, sample_rate: int) -> dict[str, Any]:
    return {
        "type": "response.create",
        "response": {
            "output_modalities": ["audio"],
            "audio": {
                "output": {
                    "format": {
                        "type": DEFAULT_OUTPUT_FORMAT,
                        "rate": sample_rate,
                    },
                    "voice": voice,
                },
            },
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
        raise ProviderRunError(f"OpenAI Realtime returned non-JSON event: {raw!r}") from exc
    if not isinstance(event, dict):
        raise ProviderRunError("OpenAI Realtime returned a non-object event")
    return event


def _decode_audio_delta(event: dict[str, Any]) -> bytes:
    delta = event.get("delta")
    if not isinstance(delta, str):
        raise ProviderRunError("OpenAI Realtime audio delta event missing delta")
    try:
        return base64.b64decode(delta)
    except Exception as exc:
        raise ProviderRunError("OpenAI Realtime audio delta was not valid base64") from exc


def _format_openai_error(event: dict[str, Any]) -> str:
    error = event.get("error")
    if isinstance(error, dict):
        message = error.get("message") or error.get("code") or error.get("type")
        if message:
            return f"OpenAI Realtime error: {message}"
    return f"OpenAI Realtime error event: {event}"


def _delete_empty_output(path: Path) -> None:
    try:
        path.unlink()
    except OSError:
        pass


def _resolve_api_key(options: dict[str, Any]) -> str:
    explicit = _option(options, "api_key")
    if explicit:
        return explicit
    load_voice_lab_env_file()
    for name in ("OPENAI_API_KEY", "VOICE_TOOLS_OPENAI_KEY"):
        value = os.getenv(name, "").strip()
        if value:
            return value
    return ""


def _option(options: dict[str, Any], name: str) -> str | None:
    value = options.get(name)
    if value is None:
        return None
    return str(value).strip()


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
