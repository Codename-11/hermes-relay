"""Unit tests for the realtime-agent Hermes run-progress heartbeat helpers.

Covers two behaviours that keep a long/background Hermes voice turn alive and
calm (see broker.py `_send_hermes_run_progress`):

- `_should_continue_heartbeat`: the heartbeat must keep ticking while the
  underlying run task is still in flight even if `hermes_run_status` transiently
  drifts off "running" (otherwise the websocket goes silent and the client kills
  the turn after ~90s). It only stops once the task is finished/None or the
  session has closed.
- `_should_repeat_spoken_status` + `_coarse_spoken_status_key`: spoken progress
  is re-flagged only on a *meaningful* high-level status change, or after the
  (now calmer) repeat window — tool *message* churn within the same coarse state
  no longer re-triggers speech.
"""

from __future__ import annotations

import unittest

from plugin.relay.realtime_agent.broker import (
    _HERMES_SPOKEN_PROGRESS_REPEAT_SECONDS,
    _coarse_spoken_status_key,
    _should_continue_heartbeat,
    _should_repeat_spoken_status,
)


class _FakeTask:
    """Minimal stand-in for an asyncio.Task exposing only `.done()`."""

    def __init__(self, done: bool) -> None:
        self._done = done

    def done(self) -> bool:
        return self._done


class HeartbeatContinuationTest(unittest.TestCase):
    def test_continues_while_task_running_even_off_status(self) -> None:
        # The run task is still in flight; status briefly read "completed" between
        # SSE bursts on a background run. Heartbeat must NOT self-terminate.
        running = _FakeTask(done=False)
        for status in ("running", "waiting_for_confirmation", "completed", "idle", "error"):
            self.assertTrue(
                _should_continue_heartbeat(running, status, session_closed=False),
                msg=f"should keep ticking for live task at status={status!r}",
            )

    def test_stops_when_session_closed_even_with_live_task(self) -> None:
        running = _FakeTask(done=False)
        self.assertFalse(
            _should_continue_heartbeat(running, "running", session_closed=True)
        )

    def test_no_task_falls_back_to_status(self) -> None:
        # No live task: keep ticking only while a run is genuinely active.
        self.assertTrue(_should_continue_heartbeat(None, "running", session_closed=False))
        self.assertTrue(
            _should_continue_heartbeat(None, "waiting_for_confirmation", session_closed=False)
        )
        for status in ("completed", "idle", "cancelled", "error"):
            self.assertFalse(
                _should_continue_heartbeat(None, status, session_closed=False),
                msg=f"no task + terminal status={status!r} should stop",
            )

    def test_finished_task_falls_back_to_status(self) -> None:
        finished = _FakeTask(done=True)
        # A finished task behaves like no task: status decides.
        self.assertTrue(
            _should_continue_heartbeat(finished, "running", session_closed=False)
        )
        self.assertFalse(
            _should_continue_heartbeat(finished, "completed", session_closed=False)
        )


class CoarseStatusKeyTest(unittest.TestCase):
    def test_progress_messages_collapse_to_one_bucket(self) -> None:
        # Two different tool *messages* under the same high-level status collapse
        # to a single coarse key, so message churn doesn't re-trigger speech.
        a = _coarse_spoken_status_key("running", "progress:Reading file foo.py")
        b = _coarse_spoken_status_key("running", "progress:Reading file bar.py")
        self.assertEqual(a, b)
        self.assertEqual(a, "running:progress")

    def test_distinct_tool_states_keep_distinct_keys(self) -> None:
        self.assertNotEqual(
            _coarse_spoken_status_key("running", "tool:search:running"),
            _coarse_spoken_status_key("running", "tool:search:done"),
        )
        self.assertNotEqual(
            _coarse_spoken_status_key("running", "tool:search:running"),
            _coarse_spoken_status_key("waiting_for_confirmation", "confirmation"),
        )


class SpokenStatusRepeatGateTest(unittest.TestCase):
    def test_first_status_always_speaks(self) -> None:
        self.assertTrue(
            _should_repeat_spoken_status(
                now=100.0,
                last_spoken_at=0.0,
                last_coarse_key=None,
                coarse_key="running:tool:search:running",
            )
        )

    def test_meaningful_change_speaks_immediately(self) -> None:
        # Different coarse key -> speak even though almost no time has passed.
        self.assertTrue(
            _should_repeat_spoken_status(
                now=101.0,
                last_spoken_at=100.0,
                last_coarse_key="running:tool:search:running",
                coarse_key="running:tool:search:done",
            )
        )

    def test_same_status_message_churn_does_not_respeak_early(self) -> None:
        # Same coarse key, only a tool message churned, and the repeat window has
        # NOT elapsed -> stay quiet.
        self.assertFalse(
            _should_repeat_spoken_status(
                now=120.0,
                last_spoken_at=100.0,  # 20s < 90s repeat window
                last_coarse_key="running:progress",
                coarse_key="running:progress",
            )
        )

    def test_same_status_respeaks_after_repeat_window(self) -> None:
        # Same coarse key, but the (calm) repeat window elapsed -> a gentle nudge.
        self.assertTrue(
            _should_repeat_spoken_status(
                now=100.0 + _HERMES_SPOKEN_PROGRESS_REPEAT_SECONDS + 1.0,
                last_spoken_at=100.0,
                last_coarse_key="running:progress",
                coarse_key="running:progress",
            )
        )

    def test_repeat_window_is_calm(self) -> None:
        # Guards the cadence relaxation: the repeat window is at least 90s.
        self.assertGreaterEqual(_HERMES_SPOKEN_PROGRESS_REPEAT_SECONDS, 90.0)


if __name__ == "__main__":
    unittest.main()
