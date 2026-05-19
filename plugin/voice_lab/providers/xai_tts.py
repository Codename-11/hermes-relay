"""xAI streaming TTS provider for voice-lab and relay voice output."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
import wave
from collections.abc import Callable
from typing import Any, Protocol

from ..auth import load_voice_lab_env_file, read_xai_oauth_token
from ..metrics import MetricsRecorder
from .base import (
    ProviderInfo,
    ProviderRunError,
    ProviderUnavailable,
    VoiceProvider,
    VoiceRequest,
    VoiceResponse,
)

DEFAULT_BASE_URL = "https://api.x.ai"
DEFAULT_VOICE = "eve"
DEFAULT_LANGUAGE = "en"
DEFAULT_CODEC = "pcm"
DEFAULT_SAMPLE_RATE = 24000
DEFAULT_TIMEOUT_SECONDS = 60.0


class HttpResponseLike(Protocol):
    headers: Any

    def read(self, size: int = -1) -> bytes: ...

    def __enter__(self) -> "HttpResponseLike": ...

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> Any: ...


HttpStreamFactory = Callable[[urllib.request.Request, float], HttpResponseLike]


class XAITTSProvider(VoiceProvider):
    """Render final assistant text through xAI's text-to-speech endpoint."""

    info = ProviderInfo(
        id="xai_tts",
        name="xAI Grok TTS",
        description=(
            "Provider-neutral streaming TTS renderer for final Hermes text. "
            "This is the default target for deterministic assistant speech."
        ),
        models=("xai-tts",),
        voices=("eve", "ara", "rex", "sal", "leo"),
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
        api_key = _resolve_auth_token(options)
        if not api_key:
            raise ProviderUnavailable(
                "xai_tts requires XAI_API_KEY, VOICE_TOOLS_XAI_KEY, GROK_API_KEY, "
                "or a relay/voice-lab xAI OAuth access token"
            )

        voice = _option(options, "voice") or _option(options, "voice_id") or DEFAULT_VOICE
        language = _option(options, "language") or DEFAULT_LANGUAGE
        codec = (_option(options, "codec") or DEFAULT_CODEC).lower()
        sample_rate = _int_option(options, "sample_rate", DEFAULT_SAMPLE_RATE)
        timeout = _float_option(options, "timeout", DEFAULT_TIMEOUT_SECONDS)
        base_url = (_option(options, "url") or DEFAULT_BASE_URL).rstrip("/")
        endpoint = _tts_endpoint(base_url)

        body = _request_body(
            request,
            voice=voice,
            language=language,
            codec=codec,
            sample_rate=sample_rate,
            options=options,
        )
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
            model="xai-tts",
            voice=voice,
            text_chars=len(request.text),
            expression=request.expression.to_dict(),
            codec=codec,
            sample_rate=sample_rate,
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
                        if codec != "pcm":
                            raise ProviderUnavailable(
                                "xai_tts relay renderer currently requires codec=pcm"
                            )
                        wav.writeframes(chunk)
                        audio_bytes += len(chunk)
                        recorder.audio_chunk(
                            byte_count=len(chunk),
                            label="xai_tts_stream",
                            pcm=chunk,
                        )
                        if request.audio_sink is not None:
                            request.audio_sink(
                                chunk,
                                {
                                    "label": "xai_tts_stream",
                                    "sample_rate": sample_rate,
                                    "channels": 1,
                                    "sample_width": 2,
                                },
                            )
        except urllib.error.HTTPError as exc:
            body_text = exc.read().decode("utf-8", errors="replace")
            _delete_empty_output(request.output_path)
            raise ProviderRunError(f"xAI TTS error: {body_text}") from exc
        except urllib.error.URLError as exc:
            _delete_empty_output(request.output_path)
            raise ProviderRunError(f"xAI TTS request failed: {exc}") from exc

        if audio_bytes <= 0:
            _delete_empty_output(request.output_path)
            raise ProviderRunError("xAI TTS completed without audio bytes")

        metrics = recorder.finish(provider=self.info.id, model="xai-tts", voice=voice)
        return VoiceResponse(
            provider=self.info.id,
            model="xai-tts",
            voice=voice,
            audio_path=request.output_path,
            metrics=metrics,
            metadata={
                "audio_bytes": audio_bytes,
                "sample_rate": sample_rate,
                "codec": codec,
                "language": language,
                "streaming_tts": True,
            },
        )


def _urlopen_stream(
    request: urllib.request.Request,
    timeout: float,
) -> HttpResponseLike:
    return urllib.request.urlopen(request, timeout=timeout)


def _tts_endpoint(base_url: str) -> str:
    base = base_url.rstrip("/")
    if base.endswith("/v1"):
        return f"{base}/tts"
    return f"{base}/v1/tts"


def _request_body(
    request: VoiceRequest,
    *,
    voice: str,
    language: str,
    codec: str,
    sample_rate: int,
    options: dict[str, Any],
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "text": _text_for_request(request),
        "voice_id": voice,
        "language": language,
        "output_format": {
            "codec": codec,
            "sample_rate": sample_rate,
        },
    }
    latency = _int_option_or_none(options, "optimize_streaming_latency")
    if latency is not None:
        body["optimize_streaming_latency"] = latency
    normalization = _bool_option_or_none(options, "text_normalization")
    if normalization is not None:
        body["text_normalization"] = normalization
    return body


def _text_for_request(request: VoiceRequest) -> str:
    if _render_mode(request) == "verbatim":
        return request.text
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


def _resolve_auth_token(options: dict[str, Any]) -> str:
    for name in ("api_key", "xai_api_key", "oauth_access_token"):
        explicit = _option(options, name)
        if explicit:
            return explicit
    load_voice_lab_env_file()
    for name in (
        "VOICE_TOOLS_XAI_KEY",
        "XAI_API_KEY",
        "GROK_API_KEY",
        "VOICE_LAB_XAI_OAUTH_ACCESS_TOKEN",
    ):
        value = os.getenv(name, "").strip()
        if value:
            return value
    oauth = read_xai_oauth_token()
    return oauth.access_token if oauth else ""


def _render_mode(request: VoiceRequest) -> str:
    return (_option(request.provider_options, "render_mode") or "verbatim").lower()


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


def _int_option_or_none(options: dict[str, Any], name: str) -> int | None:
    value = _option(options, name)
    if not value:
        return None
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


def _bool_option_or_none(options: dict[str, Any], name: str) -> bool | None:
    value = _option(options, name)
    if not value:
        return None
    lowered = value.lower()
    if lowered in {"1", "true", "yes", "on"}:
        return True
    if lowered in {"0", "false", "no", "off"}:
        return False
    raise ProviderUnavailable(f"{name} must be a boolean")
