"""Forced-summary response validation (`_bad_forced_summary_reason`).

The forced-summary step is the FINAL spoken answer for a completed background
run. When the provider responds with deferral filler or reads identifiers
instead of the answer, the validator must flag it so the fallback speaks the
real result — otherwise the answer is silently lost (observed live 2026-07-08:
"One moment while I look that up. I'll report back as soon as I have the
info." was spoken instead of a completed result, and the user never heard it).
"""

from __future__ import annotations

import unittest

from plugin.relay.realtime_agent.broker import _bad_forced_summary_reason


class BadForcedSummaryReasonTest(unittest.TestCase):
    def test_flags_observed_live_deferral_filler(self) -> None:
        self.assertEqual(
            "acknowledgement_not_summary",
            _bad_forced_summary_reason(
                "One moment while I look that up. I'll report back as soon "
                "as I have the info."
            ),
        )

    def test_flags_report_back_and_looking_variants(self) -> None:
        for text in (
            "I'll report back when it's done.",
            "I will report back shortly.",
            "Looking into it now.",
            "I'm looking that up for you.",
            "I'll look into that right away.",
        ):
            self.assertEqual(
                "acknowledgement_not_summary",
                _bad_forced_summary_reason(text),
                text,
            )

    def test_flags_run_id_speech(self) -> None:
        self.assertEqual(
            "asked_for_run_id",
            _bad_forced_summary_reason(
                "Got it — the run ID is run_9e3aa0c2aab24ffa92e55707adecf003."
            ),
        )

    def test_flags_empty(self) -> None:
        self.assertEqual("empty_summary", _bad_forced_summary_reason("   "))

    def test_accepts_real_answers(self) -> None:
        for text in (
            "It's 1:26 AM in Tokyo on Thursday, July 9, 2026.",
            "The build finished successfully — all 64 tests pass.",
            "Your server is up; CPU is at 12 percent and disk usage is normal.",
        ):
            self.assertIsNone(_bad_forced_summary_reason(text), text)


if __name__ == "__main__":
    unittest.main()
