"""Forced-summary response validation (`_bad_forced_summary_reason`).

The forced-summary step is the FINAL spoken answer for a completed background
run. When the provider responds with deferral filler or reads identifiers
instead of the answer, the validator must flag it so the fallback speaks the
real result — otherwise the answer is silently lost (observed live 2026-07-08:
"One moment while I look that up. I'll report back as soon as I have the
info." was spoken instead of a completed result, and the user never heard it).
"""

from __future__ import annotations

import tempfile
import time
import unittest
from pathlib import Path

from plugin.relay.config import RelayConfig
from plugin.relay.realtime_agent.broker import (
    RealtimeAgentHandler,
    RealtimeAgentSession,
    _answer_evidence_tokens,
    _bad_forced_summary_reason,
    _summary_overlaps_answer,
)


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


class AnswerOverlapTest(unittest.TestCase):
    """Positive validation: the summary must share content with the answer."""

    ANSWER = "It's 1:26 AM in Tokyo on Thursday, July 9, 2026."

    def test_summary_reflecting_answer_passes(self) -> None:
        self.assertIsNone(
            _bad_forced_summary_reason("It is currently 1:26 AM in Tokyo.", self.ANSWER)
        )

    def test_unrelated_smalltalk_fails_overlap(self) -> None:
        # Passes every blocklist phrase check, but delivers nothing.
        self.assertEqual(
            "no_answer_overlap",
            _bad_forced_summary_reason(
                "All right, everything went smoothly on my end.", self.ANSWER
            ),
        )

    def test_bare_confirmation_answers_never_false_positive(self) -> None:
        # An answer with no content tokens can't be overlap-checked — the
        # check is vacuous rather than sending every summary to the fallback.
        self.assertTrue(_summary_overlaps_answer("All done for you.", "OK."))
        self.assertIsNone(_bad_forced_summary_reason("All done for you.", "OK."))

    def test_evidence_tokens_pick_numbers_and_content_words(self) -> None:
        tokens = _answer_evidence_tokens(self.ANSWER)
        self.assertIn("tokyo", tokens)
        self.assertIn("1:26", tokens)
        self.assertNotIn("it's", tokens)

    def test_blocklist_still_wins_even_with_overlap(self) -> None:
        # Deferral filler that happens to mention the topic is still filler.
        self.assertEqual(
            "acknowledgement_not_summary",
            _bad_forced_summary_reason(
                "One moment while I look that up about Tokyo.", self.ANSWER
            ),
        )

    def test_queue_speak_in_a_final_answer_is_flagged(self) -> None:
        # Observed live: the summary response was a queue acknowledgement —
        # the completed answer was lost behind it.
        self.assertEqual(
            "acknowledgement_not_summary",
            _bad_forced_summary_reason(
                "It's queued and will start automatically once the Minnesota "
                "check finishes. I'll let you know the dog's name as soon as "
                "both results come back.",
                "Obsidian's Minnesota context is one active project: St. Paul "
                "relocation, moving from Tampa Bay, Florida by July 2027, "
                "renting first before starting a purchase.",
            ),
        )

    def test_overlap_is_whole_word_not_substring(self) -> None:
        # "will START automatically" must not count as evidence for an answer
        # containing "starting" — the substring hole behind the live miss.
        answer = "The job is starting tomorrow at 9am in the main warehouse."
        self.assertEqual(
            "no_answer_overlap",
            _bad_forced_summary_reason(
                "Sure — it will start automatically for you.", answer
            ),
        )


class EarlyCommitTest(unittest.IsolatedAsyncioTestCase):
    """Forced-summary early flush: stream live once the prefix validates."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.handler = RealtimeAgentHandler(RelayConfig())
        self.session = RealtimeAgentSession(
            session_id="sess-early-commit",
            provider="xai_realtime",
            model="grok-voice-latest",
            voice="ember",
            sample_rate=24000,
            profile=None,
            chat_session_id="chat-1",
            config_scope="test",
            config_path=None,
            auth_kind="test",
            bearer_token=None,
            created_at=time.time(),
            event_log_path=Path(self._tmp.name) / "events.jsonl",
            resume_token_hash="hash",
        )
        self.session.native_forced_summary_active = True
        self.session.native_forced_summary_result = {
            "answer": "It's 1:26 AM in Tokyo on Thursday, July 9, 2026."
        }

    def _buffer(self, delta: str) -> None:
        self.session.native_forced_summary_text_parts.append(delta)
        self.session.native_forced_summary_buffer.append(
            {"type": "voice.response.delta", "source": "provider", "delta": delta}
        )

    async def test_commits_once_prefix_shows_answer_overlap(self) -> None:
        self._buffer("It is currently ")
        await self.handler._maybe_commit_forced_summary_early(None, self.session)
        self.assertFalse(self.session.native_forced_summary_committed)  # too short

        self._buffer("1:26 in the morning in Tokyo, ")
        await self.handler._maybe_commit_forced_summary_early(None, self.session)
        self.assertTrue(self.session.native_forced_summary_committed)
        # Buffer flushed into the event ring, and stays flushed.
        self.assertEqual(0, len(self.session.native_forced_summary_buffer))
        ring_deltas = [
            e for e in self.session.event_ring if e.get("type") == "voice.response.delta"
        ]
        self.assertEqual(2, len(ring_deltas))
        log_text = self.session.event_log_path.read_text(encoding="utf-8")
        self.assertIn("voice.response.forced_summary_streaming", log_text)

    async def test_never_commits_deferral_filler(self) -> None:
        self._buffer("One moment while I look that up. I'll report back soon, ")
        self._buffer("as soon as I have the info for you right here.")
        await self.handler._maybe_commit_forced_summary_early(None, self.session)
        self.assertFalse(self.session.native_forced_summary_committed)
        self.assertEqual(2, len(self.session.native_forced_summary_buffer))

    async def test_never_commits_without_answer_overlap(self) -> None:
        self._buffer("Everything went really well on my side of things today.")
        await self.handler._maybe_commit_forced_summary_early(None, self.session)
        self.assertFalse(self.session.native_forced_summary_committed)

    async def test_single_weak_hit_does_not_commit_early(self) -> None:
        # Early commit is irreversible, so it needs TWO whole-word evidence
        # hits. The live regression prefix ("It's queued and will start
        # automatically once…") must keep buffering — end validation and the
        # fallback then deliver the real answer.
        self.session.native_forced_summary_result = {
            "answer": "The relocation project is starting in Tokyo in 2027."
        }
        self._buffer("It's fine — this one will start automatically in Tokyo, ")
        await self.handler._maybe_commit_forced_summary_early(None, self.session)
        self.assertFalse(self.session.native_forced_summary_committed)


if __name__ == "__main__":
    unittest.main()
