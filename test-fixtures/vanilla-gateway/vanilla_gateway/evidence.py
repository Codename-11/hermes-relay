"""Bounded, content-free fixture evidence."""

from __future__ import annotations

from collections import deque
from dataclasses import asdict, dataclass
import re
from typing import Any


_SAFE_METADATA = re.compile(r"[A-Za-z0-9_.:-]{1,120}")


def _safe_metadata(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value)
    return text if _SAFE_METADATA.fullmatch(text) else "<redacted>"


@dataclass(frozen=True)
class EvidenceEntry:
    sequence: int
    kind: str
    connection: int | None = None
    method: str | None = None
    event_type: str | None = None
    scope: str | None = None
    outcome: str | None = None


class EvidenceLog:
    def __init__(self, maximum: int = 512) -> None:
        if maximum < 1 or maximum > 4_096:
            raise ValueError("evidence maximum must be between 1 and 4096")
        self._entries: deque[EvidenceEntry] = deque(maxlen=maximum)
        self._sequence = 0

    def add(self, kind: str, **fields: Any) -> None:
        self._sequence += 1
        allowed = {
            "connection": fields.get("connection"),
            "method": _safe_metadata(fields.get("method")),
            "event_type": _safe_metadata(fields.get("event_type")),
            "scope": _safe_metadata(fields.get("scope")),
            "outcome": _safe_metadata(fields.get("outcome")),
        }
        self._entries.append(
            EvidenceEntry(self._sequence, _safe_metadata(kind) or "<redacted>", **allowed),
        )

    def export(self, scenario: str) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "scenario": _safe_metadata(scenario) or "<redacted>",
            "bounded": True,
            "entries": [
                {key: value for key, value in asdict(entry).items() if value is not None}
                for entry in self._entries
            ],
        }
