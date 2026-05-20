"""OpenAI realtime adapter for relay realtime-agent sessions."""

from __future__ import annotations

from .base import RealtimeAgentProviderAdapter


class OpenAIRealtimeAgentAdapter(RealtimeAgentProviderAdapter):
    provider_id = "openai_realtime"

