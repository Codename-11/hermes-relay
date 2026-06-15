"""Provider registry for the standalone voice lab."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from .providers.base import ProviderInfo, VoiceProvider
from .providers.elevenlabs_tts import ElevenLabsTTSProvider
from .providers.openai_tts import OpenAITTSProvider
from .providers.openai_realtime import OpenAIRealtimeProvider
from .providers.stub import StubToneProvider
from .providers.xai_tts import XAITTSProvider
from .providers.xai_realtime import XAIRealtimeProvider

ProviderFactory = Callable[[], VoiceProvider]


class ProviderRegistry:
    """In-memory registry of available voice-lab providers."""

    def __init__(self) -> None:
        self._factories: dict[str, ProviderFactory] = {}
        self._info: dict[str, ProviderInfo] = {}

    def register(self, info: ProviderInfo, factory: ProviderFactory) -> None:
        self._factories[info.id] = factory
        self._info[info.id] = info

    def provider_ids(self) -> list[str]:
        return sorted(self._info)

    def provider_infos(self) -> list[ProviderInfo]:
        return [self._info[key] for key in self.provider_ids()]

    def info(self, provider_id: str) -> ProviderInfo:
        try:
            return self._info[provider_id]
        except KeyError as exc:
            raise KeyError(f"unknown voice provider: {provider_id}") from exc

    def create(self, provider_id: str) -> VoiceProvider:
        try:
            factory = self._factories[provider_id]
        except KeyError as exc:
            known = ", ".join(self.provider_ids()) or "none"
            raise KeyError(
                f"unknown voice provider: {provider_id}; available: {known}"
            ) from exc
        return factory()

    def to_dict(self) -> dict[str, Any]:
        return {"providers": [info.to_dict() for info in self.provider_infos()]}


def default_registry() -> ProviderRegistry:
    registry = ProviderRegistry()
    registry.register(ElevenLabsTTSProvider.info, ElevenLabsTTSProvider)
    registry.register(OpenAITTSProvider.info, OpenAITTSProvider)
    registry.register(OpenAIRealtimeProvider.info, OpenAIRealtimeProvider)
    registry.register(StubToneProvider.info, StubToneProvider)
    registry.register(XAITTSProvider.info, XAITTSProvider)
    registry.register(XAIRealtimeProvider.info, XAIRealtimeProvider)
    return registry
