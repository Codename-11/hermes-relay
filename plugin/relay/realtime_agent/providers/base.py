"""Provider adapter contract for relay realtime-agent sessions."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from ....voice_lab.expressions import VoiceExpression
from ....voice_lab.metrics import MetricsRecorder
from ....voice_lab.providers.base import VoiceRequest, VoiceResponse
from ....voice_lab.registry import default_registry

from ..models import (
    ProviderEvent,
    RealtimeAgentCapabilities,
    RealtimeAgentSessionConfig,
)

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


class RealtimeAgentConnection(Protocol):
    async def send_audio(self, pcm: bytes, sample_rate: int) -> None: ...

    async def commit_audio(self) -> None: ...

    async def send_text(self, text: str) -> None: ...

    async def clear_audio(self) -> None: ...

    async def cancel_response(self) -> None: ...

    async def send_tool_result(self, call_id: str, output: dict[str, Any]) -> None: ...

    async def append_context_item(self, *, role: str, text: str) -> None:
        """Add a message to the provider's conversation history WITHOUT
        triggering a response. Used to seed a delivered background result
        (spoken by relay TTS, so absent from the provider's own history) so
        the provider can answer follow-ups about it."""
        ...

    async def request_response(
        self,
        *,
        instructions: str | None = None,
        exact_text: str | None = None,
    ) -> None:
        """Request provider speech, optionally using native exact-text synthesis.

        Providers without an exact-text primitive may ignore ``exact_text`` and
        follow ``instructions`` through normal model inference.
        """
        ...

    async def close(self) -> None: ...

    def events(self) -> AsyncIterator[ProviderEvent]: ...


class RealtimeAgentProvider(Protocol):
    provider_id: str
    capabilities: RealtimeAgentCapabilities

    async def connect(
        self,
        config: RealtimeAgentSessionConfig,
    ) -> RealtimeAgentConnection: ...
