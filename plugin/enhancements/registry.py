"""Enhancement registry for relay-owned host patches."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Callable, Literal

logger = logging.getLogger(__name__)

EnhancementPhase = Literal["startup", "plugin_load"]


@dataclass(frozen=True)
class Enhancement:
    """A removable relay enhancement applied to the host at a known phase."""

    name: str
    phase: EnhancementPhase
    enabled: Callable[[], bool]
    apply: Callable[[], bool]
    retirement_note: str


def filter_enhancements(
    enhancements: list[Enhancement],
    phase: EnhancementPhase | None = None,
) -> list[Enhancement]:
    if phase is None:
        return list(enhancements)
    return [enhancement for enhancement in enhancements if enhancement.phase == phase]


def apply_enhancements(
    enhancements: list[Enhancement],
    phase: EnhancementPhase,
) -> list[tuple[str, bool]]:
    """Apply all enhancements for ``phase`` without letting failures escape."""
    results: list[tuple[str, bool]] = []
    for enhancement in filter_enhancements(enhancements, phase):
        try:
            results.append((enhancement.name, bool(enhancement.apply())))
        except Exception:
            logger.debug(
                "Relay enhancement %s failed during %s; continuing without it",
                enhancement.name,
                phase,
                exc_info=True,
            )
            results.append((enhancement.name, False))
    return results


__all__ = [
    "Enhancement",
    "EnhancementPhase",
    "apply_enhancements",
    "filter_enhancements",
]
