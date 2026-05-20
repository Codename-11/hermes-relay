"""xAI realtime adapter skeleton for relay realtime-agent sessions."""

from __future__ import annotations

from .base import RealtimeAgentProviderAdapter


class XAIRealtimeAgentAdapter(RealtimeAgentProviderAdapter):
    provider_id = "xai_realtime"

