"""Relay enhancement registry."""

from __future__ import annotations

from plugin.enhancements.context_injection import CONTEXT_INJECTION_ENHANCEMENT
from plugin.enhancements.registry import Enhancement, EnhancementPhase, apply_enhancements, filter_enhancements

_ENHANCEMENTS: list[Enhancement] = [
    CONTEXT_INJECTION_ENHANCEMENT,
]


def get_enhancements(phase: EnhancementPhase | None = None) -> list[Enhancement]:
    return filter_enhancements(_ENHANCEMENTS, phase)


def apply_phase(phase: EnhancementPhase) -> list[tuple[str, bool]]:
    return apply_enhancements(_ENHANCEMENTS, phase)


__all__ = [
    "Enhancement",
    "EnhancementPhase",
    "apply_phase",
    "get_enhancements",
]
