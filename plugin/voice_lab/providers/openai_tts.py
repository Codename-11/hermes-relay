"""OpenAI streaming TTS provider for voice-lab and relay voice output."""

from __future__ import annotations

import json
import os
import urllib.error
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

DEFAULT_BASE_URL = "https://api.openai.com/v1"
DEFAULT_MODEL = "gpt-4o-mini-tts"
DEFAULT_VOICE = "coral"
DEFAULT_RESPONSE_FORMAT = "pcm"
DEFAULT_SAMPLE_RATE = 24000
DEFAULT_TIMEOUT_SECONDS = 60.0


class HttpResponseLike(Protocol):
    headers: Any

    def read(self, size: int = -1) -> bytes: ...

    def __enter__(self) -> "HttpResponseLike": ...

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> Any: ...


HttpStreamFactory = Callable[[urllib.request.Request, float], HttpResponseLike]


class OpenAITTSProvider(VoiceProvider):
    """Render final assistant text through OpenAI's streaming speech API."""

    info = ProviderInfo(
        id="openai_tts",
        name="OpenAI TTS",
        description=(
            "Provider-neutral TTS renderer using OpenAI speech output with "
            "raw PCM streaming for the relay voice broker."
        ),
        models=(DEFAULT_MODEL, "tts-1", "tts-1-hd"),
        voices=(
            "alloy",
            "ash",
            "ballad",
            "coral",
            "echo",
            "fable",
            "onyx",
            "nova",
            "sage",
            "shimmer",
            "verse",
            "marin",
            "cedar",
        ),
        languages=("en",),
        sample_rates=(24000,),
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
                "openai_tts requires OPENAI_API_KEY or VOICE_TOOLS_OPENAI_KEY"
            )

        model = _option(options, "model") or DEFAULT_MODEL
        voice = _option(options, "voice") or DEFAULT_VOICE
        response_format = (_option(options, "response_format") or DEFAULT_RESPONSE_FORMAT).lower()
        sample_rate = _int_option(options, "sample_rate", DEFAULT_SAMPLE_RATE)
        timeout = _float_option(options, "timeout", DEFAULT_TIMEOUT_SECONDS)
        base_url = (_option(options, "url") or DEFAULT_BASE_URL).rstrip("/")
        endpoint = f"{base_url}/audio/speech"

        body = {
            "model": model,
            "voice": voice,
            "input": request.text,
            "response_format": response_format,
        }
        instructions = _instructions_for_request(request)
        if instructions:
            body["instructions"] = instructions

        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        http_request = urllib.request.Request(
            endpoint,
            data=payload,
            method="POST",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                "Accept": "application/octet-stream",
            },
        )

        request.output_path.parent.mkdir(parents=True, exist_ok=True)
        recorder.event(
            "request_started",
            provider=self.info.id,
            model=model,
            voice=voice,
            text_chars=len(request.text),
            expression=request.expression.to_dict(),
            response_format=response_format,
        )

        audio_bytes = 0
        try:
            with self._stream_factory(http_request, timeout) as response:
                with wave.open(str(request.output_path), "wb") as wav:
                    wav.setnchannels(1)
                    wav.setsampwidth(2)
                    wav.setframerate(sample_rate)
                    while True:
                        chunk = response.read(4096)
                        if not chunk:
                            break
                        if response_format != "pcm":
                            raise ProviderUnavailable(
                                "openai_tts relay renderer currently requires response_format=pcm"
                            )
                        wav.writeframes(chunk)
                        audio_bytes += len(chunk)
                        recorder.audio_chunk(
                            byte_count=len(chunk),
                            label="openai_tts_stream",
                            pcm=chunk,
                        )
                        if request.audio_sink is not None:
                            request.audio_sink(
                                chunk,
                                {
                                    "label": "openai_tts_stream",
                                    "sample_rate": sample_rate,
                                    "channels": 1,
                                    "sample_width": 2,
                                },
                            )
        except urllib.error.HTTPError as exc:
            body_text = exc.read().decode("utf-8", errors="replace")
            _delete_empty_output(request.output_path)
            raise ProviderRunError(f"OpenAI TTS error: {body_text}") from exc
        except urllib.error.URLError as exc:
            _delete_empty_output(request.output_path)
            raise ProviderRunError(f"OpenAI TTS request failed: {exc}") from exc

        if audio_bytes <= 0:
            _delete_empty_output(request.output_path)
            raise ProviderRunError("OpenAI TTS completed without audio bytes")

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
                "response_format": response_format,
                "streaming_tts": True,
            },
        )


def _urlopen_stream(
    request: urllib.request.Request,
    timeout: float,
) -> HttpResponseLike:
    return urllib.request.urlopen(request, timeout=timeout)


def _instructions_for_request(request: VoiceRequest) -> str | None:
    explicit = _option(request.provider_options, "instructions")
    if explicit:
        return explicit
    expression = request.expression
    delivery = []
    if expression.emotion:
        delivery.append(expression.emotion)
    if expression.tone:
        delivery.append(expression.tone)
    if expression.pace and expression.pace != "normal":
        delivery.append(f"{expression.pace} pace")
    if not delivery:
        return None
    return "Speak in a " + ", ".join(delivery) + " style."


def _resolve_api_key(options: dict[str, Any]) -> str:
    explicit = _option(options, "api_key") or _option(options, "openai_api_key")
    if explicit:
        return explicit
    load_voice_lab_env_file()
    for name in ("OPENAI_API_KEY", "VOICE_TOOLS_OPENAI_KEY"):
        value = os.getenv(name, "").strip()
        if value:
            return value
    return ""


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
