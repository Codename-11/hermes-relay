"""ElevenLabs streaming TTS provider for the standalone voice lab."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
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

DEFAULT_MODEL = "eleven_flash_v2_5"
DEFAULT_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb"
DEFAULT_OUTPUT_FORMAT = "pcm_24000"
DEFAULT_SAMPLE_RATE = 24000
DEFAULT_TIMEOUT_SECONDS = 60.0
DEFAULT_BASE_URL = "https://api.elevenlabs.io"


class HttpResponseLike(Protocol):
    headers: Any

    def read(self, size: int = -1) -> bytes: ...

    def __enter__(self) -> "HttpResponseLike": ...

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> Any: ...


HttpStreamFactory = Callable[[urllib.request.Request, float], HttpResponseLike]


class ElevenLabsTTSProvider(VoiceProvider):
    """Render text prompts through ElevenLabs streaming TTS."""

    info = ProviderInfo(
        id="elevenlabs_tts",
        name="ElevenLabs TTS",
        description=(
            "Streaming TTS adapter using ElevenLabs Flash by default. This is a "
            "cascaded TTS benchmark target, not a native realtime voice agent."
        ),
        models=("eleven_flash_v2_5", "eleven_multilingual_v2", "eleven_turbo_v2_5"),
        voices=(DEFAULT_VOICE_ID,),
        languages=("en",),
        sample_rates=(16000, 22050, 24000, 44100, 48000),
        supports_tts=True,
        supports_realtime=False,
        supports_interruption=False,
        supports_expression=True,
    )

    def __init__(self, stream_factory: HttpStreamFactory | None = None) -> None:
        self._stream_factory = stream_factory or _urlopen_stream

    def run_text(
        self,
        request: VoiceRequest,
        recorder: MetricsRecorder,
    ) -> VoiceResponse:
        options = request.provider_options
        api_key = _resolve_api_key(options)
        if not api_key:
            raise ProviderUnavailable(
                "ELEVENLABS_API_KEY or VOICE_TOOLS_ELEVENLABS_KEY is required "
                "for provider elevenlabs_tts"
            )

        model = _option(options, "model") or DEFAULT_MODEL
        voice_id = _option(options, "voice_id") or _option(options, "voice") or DEFAULT_VOICE_ID
        output_format = _option(options, "output_format") or DEFAULT_OUTPUT_FORMAT
        sample_rate = _sample_rate_from_format(output_format)
        timeout = _float_option(options, "timeout", DEFAULT_TIMEOUT_SECONDS)
        base_url = (_option(options, "url") or DEFAULT_BASE_URL).rstrip("/")
        endpoint = (
            f"{base_url}/v1/text-to-speech/"
            f"{urllib.parse.quote(voice_id, safe='')}/stream"
            f"?output_format={urllib.parse.quote(output_format)}"
        )

        body = _request_body(request, model=model, options=options)
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        http_request = urllib.request.Request(
            endpoint,
            data=payload,
            method="POST",
            headers={
                "Accept": "application/octet-stream",
                "Content-Type": "application/json",
                "xi-api-key": api_key,
            },
        )

        request.output_path.parent.mkdir(parents=True, exist_ok=True)
        recorder.event(
            "request_started",
            provider=self.info.id,
            model=model,
            voice=voice_id,
            text_chars=len(request.text),
            expression=request.expression.to_dict(),
            output_format=output_format,
        )

        audio_bytes = 0
        request_id = None
        character_count = None
        try:
            with self._stream_factory(http_request, timeout) as response:
                request_id = _header(response, "request-id")
                character_count = _header(response, "x-character-count")
                recorder.event(
                    "http_stream_connected",
                    provider=self.info.id,
                    model=model,
                    voice=voice_id,
                    request_id=request_id,
                )
                with wave.open(str(request.output_path), "wb") as wav:
                    wav.setnchannels(1)
                    wav.setsampwidth(2)
                    wav.setframerate(sample_rate)
                    while True:
                        chunk = response.read(4096)
                        if not chunk:
                            break
                        wav.writeframes(chunk)
                        audio_bytes += len(chunk)
                        recorder.audio_chunk(
                            byte_count=len(chunk),
                            label="elevenlabs_stream",
                            pcm=chunk,
                        )
                        if request.audio_sink is not None:
                            request.audio_sink(
                                chunk,
                                {
                                    "label": "elevenlabs_stream",
                                    "sample_rate": sample_rate,
                                    "channels": 1,
                                    "sample_width": 2,
                                },
                            )
        except urllib.error.HTTPError as exc:
            body_text = exc.read().decode("utf-8", errors="replace")
            _delete_empty_output(request.output_path)
            raise ProviderRunError(f"ElevenLabs TTS error: {body_text}") from exc
        except urllib.error.URLError as exc:
            _delete_empty_output(request.output_path)
            raise ProviderRunError(f"ElevenLabs TTS request failed: {exc}") from exc

        if audio_bytes <= 0:
            _delete_empty_output(request.output_path)
            raise ProviderRunError("ElevenLabs TTS completed without audio bytes")

        metrics = recorder.finish(provider=self.info.id, model=model, voice=voice_id)
        return VoiceResponse(
            provider=self.info.id,
            model=model,
            voice=voice_id,
            audio_path=request.output_path,
            metrics=metrics,
            metadata={
                "audio_bytes": audio_bytes,
                "sample_rate": sample_rate,
                "output_format": output_format,
                "request_id": request_id,
                "character_count": character_count,
                "cascaded_tts": True,
            },
        )


def _urlopen_stream(
    request: urllib.request.Request,
    timeout: float,
) -> HttpResponseLike:
    return urllib.request.urlopen(request, timeout=timeout)


def _request_body(
    request: VoiceRequest,
    *,
    model: str,
    options: dict[str, Any],
) -> dict[str, Any]:
    text = _elevenlabs_text(request)
    body: dict[str, Any] = {
        "text": text,
        "model_id": model,
    }
    settings: dict[str, Any] = {}
    for key in ("stability", "similarity_boost", "style", "speed"):
        value = _float_option_or_none(options, key)
        if value is not None:
            settings[key] = value
    speaker_boost = _option(options, "use_speaker_boost")
    if speaker_boost is not None:
        settings["use_speaker_boost"] = speaker_boost.lower() in {"1", "true", "yes", "on"}
    if settings:
        body["voice_settings"] = settings
    return body


def _elevenlabs_text(request: VoiceRequest) -> str:
    expression = request.expression
    hints = []
    if expression.emotion:
        hints.append(expression.emotion)
    if expression.tone:
        hints.append(expression.tone)
    if expression.pace and expression.pace != "normal":
        hints.append(f"{expression.pace} pace")
    if hints:
        return f"{request.text}\n\nDelivery note: speak in a {', '.join(hints)} style."
    return request.text


def _resolve_api_key(options: dict[str, Any]) -> str:
    explicit = _option(options, "api_key") or _option(options, "elevenlabs_api_key")
    if explicit:
        return explicit
    load_voice_lab_env_file()
    for name in ("ELEVENLABS_API_KEY", "VOICE_TOOLS_ELEVENLABS_KEY"):
        value = os.getenv(name, "").strip()
        if value:
            return value
    return ""


def _sample_rate_from_format(output_format: str) -> int:
    if not output_format.startswith("pcm_"):
        raise ProviderUnavailable(
            "elevenlabs_tts currently writes WAV output from PCM only; use "
            "output_format=pcm_24000, pcm_16000, pcm_22050, pcm_44100, or pcm_48000"
        )
    try:
        return int(output_format.split("_", 1)[1])
    except (IndexError, ValueError) as exc:
        raise ProviderUnavailable(f"unsupported ElevenLabs output_format={output_format}") from exc


def _header(response: HttpResponseLike, name: str) -> str | None:
    headers = getattr(response, "headers", None)
    if headers is None:
        return None
    getter = getattr(headers, "get", None)
    if callable(getter):
        value = getter(name)
        if value is None:
            value = getter(name.lower())
        return str(value) if value is not None else None
    return None


def _delete_empty_output(path) -> None:
    try:
        path.unlink()
    except OSError:
        pass


def _option(options: dict[str, Any], name: str) -> str | None:
    value = options.get(name)
    if value is None:
        return None
    return str(value).strip()


def _float_option(options: dict[str, Any], name: str, default: float) -> float:
    value = _option(options, name)
    if not value:
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise ProviderUnavailable(f"{name} must be a number") from exc


def _float_option_or_none(options: dict[str, Any], name: str) -> float | None:
    value = _option(options, name)
    if not value:
        return None
    try:
        return float(value)
    except ValueError as exc:
        raise ProviderUnavailable(f"{name} must be a number") from exc
