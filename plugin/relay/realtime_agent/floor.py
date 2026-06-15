"""Relay-side audio floor owner for realtime-agent sessions (ADR 33).

Up to three sources can produce ``voice.output_audio.delta`` on Android's single
``AudioTrack``:

1. the realtime provider (xAI / OpenAI),
2. the relay TTS fallback render (``_render_provider_audio``), and
3. Android local filler, driven by the ``should_speak`` hint on
   ``hermes.run.progress``.

Today a blocking ``await`` inside the provider event pump serializes them so they
never overlap. ADR 33 backgrounds long Hermes runs, which removes that implicit
mutex. ``RealtimeFloor`` replaces it with an explicit, single-owner floor so a
completed background result never barges in and two mouths never speak at once.

The floor is pure and synchronous — every mutation happens inside the session's
asyncio task, so no locking is required. Keep it free of I/O so it stays
unit-testable in isolation (``plugin/tests/test_realtime_floor.py``).
"""

from __future__ import annotations

from enum import Enum


class FloorMouth(str, Enum):
    """A source that can emit audio to Android."""

    PROVIDER = "provider"
    RELAY_TTS = "relay_tts"
    ANDROID_FILLER = "android_filler"


#: Mouths that emit primary speech as ``voice.output_audio.delta``. Only one of
#: these may hold the floor at a time, and either preempts filler.
PRIMARY_MOUTHS: frozenset[FloorMouth] = frozenset(
    {FloorMouth.PROVIDER, FloorMouth.RELAY_TTS}
)

#: Wire labels for the ``floor`` field on ``hermes.run.progress`` (ADR 33).
FLOOR_IDLE = "idle"
FLOOR_PROVIDER_SPEAKING = "provider_speaking"
FLOOR_HERMES_FILLER = "hermes_filler"
FLOOR_RESULT_PENDING = "result_pending"


class RealtimeFloor:
    """Single-owner audio floor for one realtime-agent session."""

    __slots__ = ("_holder", "_result_pending")

    def __init__(self) -> None:
        self._holder: FloorMouth | None = None
        self._result_pending = False

    @property
    def holder(self) -> FloorMouth | None:
        return self._holder

    @property
    def result_pending(self) -> bool:
        return self._result_pending

    def can_speak(self, mouth: FloorMouth) -> bool:
        """Whether ``mouth`` may begin emitting audio now.

        - A primary mouth may speak when the floor is free, when it already holds
          it, or by preempting Android filler — but not while the *other* primary
          mouth holds it.
        - Android filler may speak only when the floor is free or it already
          holds it; any primary mouth suppresses it.
        """
        if mouth in PRIMARY_MOUTHS:
            return self._holder in (None, mouth, FloorMouth.ANDROID_FILLER)
        return self._holder in (None, FloorMouth.ANDROID_FILLER)

    def acquire(self, mouth: FloorMouth) -> bool:
        """Take the floor for ``mouth`` if allowed. Returns success.

        A primary mouth acquiring while filler holds preempts the filler.
        """
        if not self.can_speak(mouth):
            return False
        self._holder = mouth
        return True

    def release(self, mouth: FloorMouth) -> None:
        """Release the floor if ``mouth`` currently holds it (idempotent)."""
        if self._holder == mouth:
            self._holder = None

    def note_result_ready(self) -> None:
        """Mark that a background Hermes result is ready to be spoken."""
        self._result_pending = True

    def clear_result(self) -> None:
        self._result_pending = False

    def consume_result_if_idle(self) -> bool:
        """If a result is pending and the floor is free, claim it for delivery.

        Returns True exactly once per pending result, when it is safe to speak
        (the floor is idle). The caller is then responsible for acquiring the
        floor for whichever mouth speaks the summary.
        """
        if self._holder is None and self._result_pending:
            self._result_pending = False
            return True
        return False

    def state_label(self) -> str:
        """Total snapshot label for the wire ``floor`` field."""
        if self._holder in PRIMARY_MOUTHS:
            return FLOOR_PROVIDER_SPEAKING
        if self._holder == FloorMouth.ANDROID_FILLER:
            return FLOOR_HERMES_FILLER
        if self._result_pending:
            return FLOOR_RESULT_PENDING
        return FLOOR_IDLE


__all__ = [
    "FLOOR_HERMES_FILLER",
    "FLOOR_IDLE",
    "FLOOR_PROVIDER_SPEAKING",
    "FLOOR_RESULT_PENDING",
    "FloorMouth",
    "PRIMARY_MOUTHS",
    "RealtimeFloor",
]
