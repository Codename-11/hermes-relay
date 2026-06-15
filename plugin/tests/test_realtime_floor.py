"""Invariant tests for the realtime-agent audio floor (ADR 33 Phase 1)."""

from __future__ import annotations

import unittest

from plugin.relay.realtime_agent.floor import (
    FLOOR_HERMES_FILLER,
    FLOOR_IDLE,
    FLOOR_PROVIDER_SPEAKING,
    FLOOR_RESULT_PENDING,
    FloorMouth,
    RealtimeFloor,
)


class RealtimeFloorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.floor = RealtimeFloor()

    def test_starts_idle_and_free(self) -> None:
        self.assertIsNone(self.floor.holder)
        self.assertEqual(self.floor.state_label(), FLOOR_IDLE)
        self.assertTrue(self.floor.can_speak(FloorMouth.PROVIDER))
        self.assertTrue(self.floor.can_speak(FloorMouth.RELAY_TTS))
        self.assertTrue(self.floor.can_speak(FloorMouth.ANDROID_FILLER))

    def test_filler_suppressed_while_provider_speaks(self) -> None:
        # Invariant: filler-suppressed-while-provider-speaks.
        self.assertTrue(self.floor.acquire(FloorMouth.PROVIDER))
        self.assertFalse(self.floor.can_speak(FloorMouth.ANDROID_FILLER))
        self.assertEqual(self.floor.state_label(), FLOOR_PROVIDER_SPEAKING)

    def test_relay_tts_only_when_provider_not_holding(self) -> None:
        # Invariant: relay-TTS-only-when-owned — two primary mouths never overlap.
        self.assertTrue(self.floor.acquire(FloorMouth.PROVIDER))
        self.assertFalse(self.floor.can_speak(FloorMouth.RELAY_TTS))
        self.assertFalse(self.floor.acquire(FloorMouth.RELAY_TTS))

        self.floor.release(FloorMouth.PROVIDER)
        self.assertTrue(self.floor.can_speak(FloorMouth.RELAY_TTS))
        self.assertTrue(self.floor.acquire(FloorMouth.RELAY_TTS))
        # Now the provider must not be able to barge into the relay's render.
        self.assertFalse(self.floor.can_speak(FloorMouth.PROVIDER))

    def test_background_result_never_barges(self) -> None:
        # Invariant: background-result-never-barges.
        self.assertTrue(self.floor.acquire(FloorMouth.PROVIDER))
        self.floor.note_result_ready()
        self.assertTrue(self.floor.result_pending)
        # Provider still holds the floor -> result must wait.
        self.assertFalse(self.floor.consume_result_if_idle())
        self.assertEqual(self.floor.state_label(), FLOOR_PROVIDER_SPEAKING)

        self.floor.release(FloorMouth.PROVIDER)
        self.assertEqual(self.floor.state_label(), FLOOR_RESULT_PENDING)
        # Now idle -> result becomes deliverable exactly once.
        self.assertTrue(self.floor.consume_result_if_idle())
        self.assertFalse(self.floor.consume_result_if_idle())
        self.assertFalse(self.floor.result_pending)
        self.assertEqual(self.floor.state_label(), FLOOR_IDLE)

    def test_provider_preempts_filler(self) -> None:
        self.assertTrue(self.floor.acquire(FloorMouth.ANDROID_FILLER))
        self.assertEqual(self.floor.state_label(), FLOOR_HERMES_FILLER)
        # A primary mouth may preempt active filler.
        self.assertTrue(self.floor.can_speak(FloorMouth.PROVIDER))
        self.assertTrue(self.floor.acquire(FloorMouth.PROVIDER))
        self.assertEqual(self.floor.holder, FloorMouth.PROVIDER)
        self.assertEqual(self.floor.state_label(), FLOOR_PROVIDER_SPEAKING)

    def test_release_is_idempotent_and_owner_scoped(self) -> None:
        self.assertTrue(self.floor.acquire(FloorMouth.PROVIDER))
        # A non-owner release must not free the floor.
        self.floor.release(FloorMouth.RELAY_TTS)
        self.assertEqual(self.floor.holder, FloorMouth.PROVIDER)
        # Owner release frees it; repeated release is harmless.
        self.floor.release(FloorMouth.PROVIDER)
        self.floor.release(FloorMouth.PROVIDER)
        self.assertIsNone(self.floor.holder)

    def test_filler_allowed_when_idle_during_hermes_run(self) -> None:
        # During a Hermes run the provider is not emitting audio (floor idle),
        # so filler is allowed — this is what preserves today's behavior.
        self.assertTrue(self.floor.can_speak(FloorMouth.ANDROID_FILLER))
        self.assertTrue(self.floor.acquire(FloorMouth.ANDROID_FILLER))
        self.assertTrue(self.floor.can_speak(FloorMouth.ANDROID_FILLER))

    def test_result_pending_label_only_when_idle(self) -> None:
        self.floor.note_result_ready()
        self.assertEqual(self.floor.state_label(), FLOOR_RESULT_PENDING)
        # While filler holds, the label reflects the active speaker, not pending.
        self.floor.acquire(FloorMouth.ANDROID_FILLER)
        self.assertEqual(self.floor.state_label(), FLOOR_HERMES_FILLER)


if __name__ == "__main__":
    unittest.main()
