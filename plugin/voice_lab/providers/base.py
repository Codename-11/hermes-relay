"""Provider interface for standalone voice-lab adapters."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from collections.abc import Callable
from typing import Any

from ..expressions import VoiceExpression
from ..metrics import MetricsRecorder, VoiceRunMetrics


class ProviderUnavailable(RuntimeError):
    """Raised when a provider cannot run in the current environment."""


class ProviderRunError(RuntimeError):
    """Raised when a provider starts but the remote run fails."""


@dataclass(frozen=True, slots=True)
class ProviderInfo:
    id: str
    name: str
    status: str = "available"
    description: str = ""
    models: tuple[str, ...] = ()
    voices: tuple[str, ...] = ()
    languages: tuple[str, ...] = ()
    sample_rates: tuple[int, ...] = ()
    supports_tts: bool = False
    supports_stt: bool = False
    supports_speech_to_speech: bool = False
    supports_tool_use: bool = False
    supports_realtime: bool = False
    supports_interruption: bool = False
    supports_expression: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "status": self.status,
            "description": self.description,
            "models": list(self.models),
            "voices": list(self.voices),
            "languages": list(self.languages),
            "sample_rates": list(self.sample_rates),
            "supports_tts": self.supports_tts,
            "supports_stt": self.supports_stt,
            "supports_speech_to_speech": self.supports_speech_to_speech,
            "supports_tool_use": self.supports_tool_use,
            "supports_realtime": self.supports_realtime,
            "supports_interruption": self.supports_interruption,
            "supports_expression": self.supports_expression,
        }


@dataclass(slots=True)
class VoiceRequest:
    text: str
    expression: VoiceExpression
    output_path: Path
    provider_options: dict[str, Any] = field(default_factory=dict)
    audio_sink: Callable[[bytes, dict[str, Any]], None] | None = None


@dataclass(slots=True)
class VoiceResponse:
    provider: str
    model: str | None
    voice: str | None
    audio_path: Path
    metrics: VoiceRunMetrics
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "model": self.model,
            "voice": self.voice,
            "audio_path": str(self.audio_path),
            "metrics": self.metrics.to_dict(),
            "metadata": dict(self.metadata),
        }


@dataclass(slots=True)
class VoiceTranscriptionRequest:
    audio_path: Path
    provider_options: dict[str, Any] = field(default_factory=dict)
    expected_text: str | None = None


@dataclass(slots=True)
class VoiceTranscriptionResponse:
    provider: str
    model: str | None
    transcript: str
    audio_path: Path
    metrics: VoiceRunMetrics
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "model": self.model,
            "transcript": self.transcript,
            "audio_path": str(self.audio_path),
            "metrics": self.metrics.to_dict(),
            "metadata": dict(self.metadata),
        }


@dataclass(slots=True)
class VoiceSpeechToSpeechRequest:
    audio_path: Path
    response_text: str
    expression: VoiceExpression
    output_path: Path
    provider_options: dict[str, Any] = field(default_factory=dict)


class VoiceProvider(ABC):
    """Base class for provider adapters used by the CLI harness."""

    info: ProviderInfo

    @abstractmethod
    def run_text(
        self,
        request: VoiceRequest,
        recorder: MetricsRecorder,
    ) -> VoiceResponse:
        """Render a text prompt to audio and return metrics."""

    def run_transcription(
        self,
        request: VoiceTranscriptionRequest,
        recorder: MetricsRecorder,
    ) -> VoiceTranscriptionResponse:
        raise ProviderUnavailable(
            f"{self.info.id} does not implement STT in the standalone lab yet"
        )

    def run_speech_to_speech(
        self,
        request: VoiceSpeechToSpeechRequest,
        recorder: MetricsRecorder,
    ) -> VoiceResponse:
        raise ProviderUnavailable(
            f"{self.info.id} does not implement speech-to-speech in the "
            "standalone lab yet"
        )
