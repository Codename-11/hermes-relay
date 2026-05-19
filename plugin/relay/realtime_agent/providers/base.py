"""Provider adapter contract for relay realtime-agent sessions."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from plugin.voice_lab.expressions import VoiceExpression
from plugin.voice_lab.metrics import MetricsRecorder
from plugin.voice_lab.providers.base import VoiceRequest, VoiceResponse
from plugin.voice_lab.registry import default_registry

AudioSink = Callable[[bytes, dict[str, Any]], None]


@dataclass(frozen=True, slots=True)
class RealtimeAgentRenderConfig:
    provider: str
    model: str
    voice: str
    sample_rate: int
    output_path: Path
    provider_options: dict[str, Any] = field(default_factory=dict)


class RealtimeAgentProviderAdapter:
    """Default production adapter backed by the voice-lab provider registry.

    The realtime-agent broker owns Hermes tool execution. Provider adapters
    receive final broker-approved text and return streamed PCM only.
    """

    provider_id = ""

    def __init__(self, provider_id: str | None = None) -> None:
        self.provider_id = provider_id or self.provider_id
        self.registry = default_registry()

    async def render_text(
        self,
        text: str,
        config: RealtimeAgentRenderConfig,
        audio_sink: AudioSink,
    ) -> VoiceResponse:
        provider = self.registry.create(config.provider)
        options = dict(config.provider_options)
        options.setdefault("model", config.model)
        options.setdefault("voice", config.voice)
        options.setdefault("sample_rate", str(config.sample_rate))

        def _run() -> VoiceResponse:
            return provider.run_text(
                VoiceRequest(
                    text=text,
                    expression=VoiceExpression(),
                    output_path=config.output_path,
                    provider_options=options,
                    audio_sink=audio_sink,
                ),
                MetricsRecorder(),
            )

        return await asyncio.to_thread(_run)
