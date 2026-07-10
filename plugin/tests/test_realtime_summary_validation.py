"""Forced-summary response validation (`_bad_forced_summary_reason`).

The forced-summary step is the FINAL spoken answer for a completed background
run. When the provider responds with deferral filler or reads identifiers
instead of the answer, the validator must flag it so the fallback speaks the
real result — otherwise the answer is silently lost (observed live 2026-07-08:
"One moment while I look that up. I'll report back as soon as I have the
info." was spoken instead of a completed result, and the user never heard it).
"""

from __future__ import annotations

import json
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
    _forced_hermes_exact_prompt,
    _result_delivery_prompt,
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
        # The committed prefix text is logged so the signoff can confirm a
        # provider-voiced delivery read the answer verbatim (not paraphrased).
        streaming = [
            ev
            for ev in (json.loads(line) for line in log_text.splitlines() if line.strip())
            if ev.get("type") == "voice.response.forced_summary_streaming"
        ]
        self.assertTrue(streaming)
        self.assertIn("Tokyo", streaming[0].get("prefix_preview", ""))

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

    async def test_end_validated_delivery_logs_rollup_marker(self) -> None:
        # A clean end-validated delivery has no other distinct event — the
        # forced_summary_delivered marker is what makes it visible to the
        # delivery-outcome report (python -m plugin.relay.realtime_agent.report).
        from plugin.relay.realtime_agent.models import (
            ProviderEvent,
            ProviderEventKind,
        )

        self._buffer("It's 1:26 in the morning in Tokyo right now.")
        await self.handler._finish_forced_summary_provider_response(
            None,
            self.session,
            ProviderEvent(ProviderEventKind.RESPONSE_DONE, response_id="resp-ok"),
        )
        log_text = self.session.event_log_path.read_text(encoding="utf-8")
        self.assertIn("voice.response.forced_summary_delivered", log_text)
        self.assertNotIn("voice.response.forced_summary_fallback", log_text)
        # The delivered text is logged so a clean provider-voiced delivery is
        # confirmable as a verbatim reading, not just a char count.
        delivered = [
            ev
            for ev in (json.loads(line) for line in log_text.splitlines() if line.strip())
            if ev.get("type") == "voice.response.forced_summary_delivered"
        ]
        self.assertTrue(delivered)
        self.assertIn("Tokyo", delivered[0].get("provider_text_preview", ""))

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


class ResultDeliveryPromptTest(unittest.TestCase):
    """Prompt selection for provider-spoken result delivery.

    ``speak_verbatim`` delivers through the provider too (voice continuity)
    but instructs an exact reading of the authoritative answer; only the
    other spoken modes ask for a natural summary.
    """

    RESULT = {"answer": "Your dog's name is Biscuit and the vet visit is Friday."}

    def test_speak_verbatim_selects_exact_reading_prompt(self) -> None:
        prompt = _result_delivery_prompt("speak_verbatim", "what's my dog's name", self.RESULT)
        self.assertIn("word for word as written", prompt)
        self.assertIn("Your dog's name is Biscuit", prompt)
        self.assertNotIn("concise natural summary", prompt)

    def test_summary_modes_select_summary_prompt(self) -> None:
        for mode in ("speak_when_idle", "notify_then_speak"):
            prompt = _result_delivery_prompt(mode, "what's my dog's name", self.RESULT)
            self.assertIn("concise natural summary", prompt, mode)
            self.assertNotIn("word for word as written", prompt, mode)

    def test_exact_prompt_strips_source_lines(self) -> None:
        prompt = _forced_hermes_exact_prompt(
            "where is the config",
            {"answer": "The config lives under the home directory.\nSource: /home/user/.hermes/config.yaml"},
        )
        self.assertIn("The config lives under the home directory.", prompt)
        self.assertNotIn("/home/user/.hermes/config.yaml", prompt)

    def test_exact_prompt_placeholder_when_answer_missing(self) -> None:
        prompt = _forced_hermes_exact_prompt("do the thing", {})
        self.assertIn("did not return a spoken summary", prompt)

    def test_structured_answer_routes_to_summary_prompt(self) -> None:
        # A JSON answer has no meaningful word-for-word reading —
        # `_provider_safe_answer_for_speech` rewrites it into a summarize-this
        # meta-instruction, which would contradict the exact framing.
        result = {"answer": '{"status": "ok", "open_issues": 3}'}
        prompt = _result_delivery_prompt("speak_verbatim", "check the tracker", result)
        self.assertIn("concise natural summary", prompt)
        self.assertNotIn("word for word", prompt)


class BlocklistAnswerExemptionTest(unittest.TestCase):
    """Blocklist phrases present in the AUTHORITATIVE ANSWER must not flag a
    faithful reading — only phrases the model added on its own count."""

    def test_phrase_from_answer_is_exempt(self) -> None:
        answer = "Your order is queued for Friday pickup at the depot."
        self.assertIsNone(_bad_forced_summary_reason(answer, answer))

    def test_same_phrase_without_answer_support_still_flags(self) -> None:
        self.assertEqual(
            "acknowledgement_not_summary",
            _bad_forced_summary_reason(
                "It's queued and will start automatically once the current "
                "check finishes.",
                "The Minnesota forecast is sunny and 75 tomorrow.",
            ),
        )

    def test_no_answer_context_keeps_old_behavior(self) -> None:
        self.assertEqual(
            "acknowledgement_not_summary",
            _bad_forced_summary_reason("Your order is queued for Friday pickup."),
        )


if __name__ == "__main__":
    unittest.main()
