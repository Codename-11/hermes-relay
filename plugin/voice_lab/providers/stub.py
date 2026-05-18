"""Deterministic local provider used to verify the lab without paid APIs."""

from __future__ import annotations

import math
import struct
import wave

from ..metrics import MetricsRecorder
from .base import (
    ProviderInfo,
    VoiceProvider,
    VoiceRequest,
    VoiceResponse,
    VoiceSpeechToSpeechRequest,
    VoiceTranscriptionRequest,
    VoiceTranscriptionResponse,
)


class StubToneProvider(VoiceProvider):
    """Generate a short WAV tone while exercising metrics and event logging."""

    info = ProviderInfo(
        id="stub",
        name="Stub Tone",
        description=(
            "Local deterministic WAV generator for validating the voice-lab "
            "CLI, logs, metrics, and output paths before real providers exist."
        ),
        supports_tts=True,
        supports_stt=True,
        supports_speech_to_speech=True,
        supports_realtime=False,
        supports_interruption=False,
        supports_expression=True,
    )

    def run_text(
        self,
        request: VoiceRequest,
        recorder: MetricsRecorder,
    ) -> VoiceResponse:
        recorder.event(
            "request_started",
            provider=self.info.id,
            text_chars=len(request.text),
            expression=request.expression.to_dict(),
        )

        request.output_path.parent.mkdir(parents=True, exist_ok=True)
        sample_rate = int(request.provider_options.get("sample_rate", 24000))
        duration_seconds = _duration_for_text(request.text)
        frequency_hz = _frequency_for_expression(request.expression.emotion)
        amplitude = 8000
        total_frames = int(sample_rate * duration_seconds)
        chunk_frames = max(1, total_frames // 8)

        with wave.open(str(request.output_path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(sample_rate)

            frame_index = 0
            while frame_index < total_frames:
                frames = []
                end = min(total_frames, frame_index + chunk_frames)
                for i in range(frame_index, end):
                    value = int(
                        amplitude
                        * math.sin((2.0 * math.pi * frequency_hz * i) / sample_rate)
                    )
                    frames.append(struct.pack("<h", value))
                payload = b"".join(frames)
                wav.writeframes(payload)
                recorder.audio_chunk(
                    byte_count=len(payload),
                    label=f"frames:{frame_index}-{end}",
                    pcm=payload,
                )
                if request.audio_sink is not None:
                    request.audio_sink(
                        payload,
                        {
                            "label": f"frames:{frame_index}-{end}",
                            "sample_rate": sample_rate,
                            "channels": 1,
                            "sample_width": 2,
                        },
                    )
                frame_index = end

        metrics = recorder.finish(
            provider=self.info.id,
            model="local-tone",
            voice="sine",
        )
        return VoiceResponse(
            provider=self.info.id,
            model="local-tone",
            voice="sine",
            audio_path=request.output_path,
            metrics=metrics,
            metadata={
                "sample_rate": sample_rate,
                "duration_seconds": round(duration_seconds, 3),
                "frequency_hz": frequency_hz,
            },
        )

    def run_transcription(
        self,
        request: VoiceTranscriptionRequest,
        recorder: MetricsRecorder,
    ) -> VoiceTranscriptionResponse:
        recorder.event(
            "request_started",
            provider=self.info.id,
            audio_path=str(request.audio_path),
            expected_text=request.expected_text,
        )
        metadata = _inspect_wav(request.audio_path)
        transcript = (
            request.expected_text
            or str(request.provider_options.get("transcript", "")).strip()
            or f"stub transcript for {request.audio_path.name}"
        )
        recorder.event("transcript_done", text_chars=len(transcript), **metadata)
        metrics = recorder.finish(
            provider=self.info.id,
            model="local-stt",
            voice=None,
        )
        return VoiceTranscriptionResponse(
            provider=self.info.id,
            model="local-stt",
            transcript=transcript,
            audio_path=request.audio_path,
            metrics=metrics,
            metadata=metadata,
        )

    def run_speech_to_speech(
        self,
        request: VoiceSpeechToSpeechRequest,
        recorder: MetricsRecorder,
    ) -> VoiceResponse:
        metadata = _inspect_wav(request.audio_path)
        transcript = str(
            request.provider_options.get(
                "transcript",
                f"stub transcript for {request.audio_path.name}",
            )
        ).strip()
        recorder.event(
            "speech_input_received",
            provider=self.info.id,
            audio_path=str(request.audio_path),
            transcript=transcript,
            **metadata,
        )
        response = self.run_text(
            VoiceRequest(
                text=request.response_text,
                expression=request.expression,
                output_path=request.output_path,
                provider_options=request.provider_options,
            ),
            recorder,
        )
        response.metadata["input_audio_path"] = str(request.audio_path)
        response.metadata["input_transcript"] = transcript
        response.metadata["input_audio"] = metadata
        return response


def _duration_for_text(text: str) -> float:
    # Keep outputs short but make longer prompts visibly different.
    return min(1.5, max(0.25, len(text.strip()) / 120.0))


def _frequency_for_expression(emotion: str | None) -> int:
    if emotion in {"urgent", "energetic", "playful"}:
        return 660
    if emotion in {"calm", "warm", "empathetic"}:
        return 330
    return 440


def _inspect_wav(path) -> dict[str, int | float]:
    with wave.open(str(path), "rb") as wav:
        frames = wav.getnframes()
        sample_rate = wav.getframerate()
        channels = wav.getnchannels()
        sample_width = wav.getsampwidth()
    duration_seconds = frames / float(sample_rate) if sample_rate else 0.0
    return {
        "channels": channels,
        "sample_width": sample_width,
        "sample_rate": sample_rate,
        "frames": frames,
        "duration_seconds": round(duration_seconds, 3),
    }
