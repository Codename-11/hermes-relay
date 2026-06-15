"""Provider-neutral expression model for voice experiments."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class VoiceExpression:
    """Delivery intent that provider adapters translate to provider controls."""

    emotion: str | None = None
    tone: str | None = None
    intensity: float = 0.5
    pace: str = "normal"
    style: str = "natural"
    pronunciation_hints: list[str] = field(default_factory=list)
    interruption_behavior: str = "allow"
    persona_instructions: str | None = None
    provider_overrides: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not 0.0 <= self.intensity <= 1.0:
            raise ValueError("intensity must be between 0.0 and 1.0")

    def to_dict(self) -> dict[str, Any]:
        return {
            "emotion": self.emotion,
            "tone": self.tone,
            "intensity": self.intensity,
            "pace": self.pace,
            "style": self.style,
            "pronunciation_hints": list(self.pronunciation_hints),
            "interruption_behavior": self.interruption_behavior,
            "persona_instructions": self.persona_instructions,
            "provider_overrides": dict(self.provider_overrides),
        }
