"""Metrics and raw event capture for standalone voice-lab runs."""

from __future__ import annotations

import math
import struct
import time
from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class VoiceEvent:
    """A provider-neutral timestamped event emitted by a voice run."""

    type: str
    at_ms: float
    data: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": self.type,
            "at_ms": round(self.at_ms, 3),
            "data": dict(self.data),
        }


@dataclass(slots=True)
class VoiceRunMetrics:
    """Common comparison fields across voice providers."""

    provider: str
    model: str | None = None
    voice: str | None = None
    first_audio_ms: float | None = None
    response_done_ms: float | None = None
    audio_chunk_gap_ms: float | None = None
    audio_underruns: int = 0
    speech_pacing: str | None = None
    tone_consistency: str | None = None
    emotion_consistency: str | None = None
    voice_drift: str | None = None
    interrupt_latency: float | None = None
    pronunciation_quality: str | None = None
    context_retention: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "model": self.model,
            "voice": self.voice,
            "first_audio_ms": _round_optional(self.first_audio_ms),
            "response_done_ms": _round_optional(self.response_done_ms),
            "audio_chunk_gap_ms": _round_optional(self.audio_chunk_gap_ms),
            "audio_underruns": self.audio_underruns,
            "speech_pacing": self.speech_pacing,
            "tone_consistency": self.tone_consistency,
            "emotion_consistency": self.emotion_consistency,
            "voice_drift": self.voice_drift,
            "interrupt_latency": _round_optional(self.interrupt_latency),
            "pronunciation_quality": self.pronunciation_quality,
            "context_retention": self.context_retention,
        }


class MetricsRecorder:
    """Small stopwatch and event recorder used by provider adapters."""

    def __init__(self) -> None:
        self._started = time.perf_counter()
        self._last_audio_ms: float | None = None
        self._max_audio_gap_ms: float | None = None
        self.first_audio_ms: float | None = None
        self.audio_underruns = 0
        self.events: list[VoiceEvent] = []

    def elapsed_ms(self) -> float:
        return (time.perf_counter() - self._started) * 1000.0

    def event(self, event_type: str, **data: Any) -> VoiceEvent:
        event = VoiceEvent(event_type, self.elapsed_ms(), data)
        self.events.append(event)
        return event

    def audio_chunk(
        self,
        *,
        byte_count: int,
        label: str | None = None,
        pcm: bytes | None = None,
        sample_width: int = 2,
    ) -> None:
        now_ms = self.elapsed_ms()
        if self.first_audio_ms is None:
            self.first_audio_ms = now_ms
        if self._last_audio_ms is not None:
            gap = now_ms - self._last_audio_ms
            if self._max_audio_gap_ms is None or gap > self._max_audio_gap_ms:
                self._max_audio_gap_ms = gap
        self._last_audio_ms = now_ms
        data: dict[str, Any] = {
            "byte_count": byte_count,
            "label": label,
        }
        if pcm:
            peak_level, rms_level = _pcm_levels(pcm, sample_width=sample_width)
            data["peak_level"] = peak_level
            data["rms_level"] = rms_level
        self.event("audio_chunk", **data)

    def underrun(self, **data: Any) -> None:
        self.audio_underruns += 1
        self.event("audio_underrun", **data)

    def finish(
        self,
        *,
        provider: str,
        model: str | None = None,
        voice: str | None = None,
    ) -> VoiceRunMetrics:
        done_ms = self.elapsed_ms()
        self.event("response_done", provider=provider, model=model, voice=voice)
        return VoiceRunMetrics(
            provider=provider,
            model=model,
            voice=voice,
            first_audio_ms=self.first_audio_ms,
            response_done_ms=done_ms,
            audio_chunk_gap_ms=self._max_audio_gap_ms,
            audio_underruns=self.audio_underruns,
        )

    def events_as_dicts(self) -> list[dict[str, Any]]:
        return [event.to_dict() for event in self.events]


def _pcm_levels(pcm: bytes, *, sample_width: int) -> tuple[float, float]:
    """Return peak and RMS levels normalized to 0.0-1.0 for PCM chunks."""
    if sample_width != 2 or len(pcm) < 2:
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
    max_sample = 32767.0
    return (
        round(min(1.0, peak / max_sample), 4),
        round(min(1.0, rms / max_sample), 4),
    )


def _round_optional(value: float | None) -> float | None:
    if value is None:
        return None
    return round(value, 3)
