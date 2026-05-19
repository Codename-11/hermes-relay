"""Provider adapters for the relay realtime-agent broker."""

from __future__ import annotations

from .base import RealtimeAgentProviderAdapter
from .openai import OpenAIRealtimeAgentAdapter
from .xai import XAIRealtimeAgentAdapter


def adapter_for(provider_id: str) -> RealtimeAgentProviderAdapter:
    if provider_id == OpenAIRealtimeAgentAdapter.provider_id:
        return OpenAIRealtimeAgentAdapter()
    if provider_id == XAIRealtimeAgentAdapter.provider_id:
        return XAIRealtimeAgentAdapter()
    return RealtimeAgentProviderAdapter(provider_id)


__all__ = [
    "OpenAIRealtimeAgentAdapter",
    "RealtimeAgentProviderAdapter",
    "XAIRealtimeAgentAdapter",
    "adapter_for",
]
