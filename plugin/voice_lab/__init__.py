"""Standalone realtime voice provider lab.

This package is intentionally separate from ``plugin.relay.voice``. It gives
provider experiments a CLI and reusable primitives without registering relay
routes or changing Android/mobile behavior.
"""

from .expressions import VoiceExpression
from .metrics import MetricsRecorder, VoiceRunMetrics
from .registry import ProviderRegistry, default_registry

__all__ = [
    "MetricsRecorder",
    "ProviderRegistry",
    "VoiceExpression",
    "VoiceRunMetrics",
    "default_registry",
]
