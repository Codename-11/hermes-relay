"""Delivery-outcome rollup (`plugin.relay.realtime_agent.report`)."""

from __future__ import annotations

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from plugin.relay.realtime_agent.report import (
    scan_run_dir,
    scan_session_log,
    summarize,
)


def _write_log(path: Path, events: list[dict]) -> None:
    path.write_text(
        "\n".join(json.dumps(event) for event in events) + "\n",
        encoding="utf-8",
    )


class ScanSessionLogTest(unittest.TestCase):
    def test_tallies_outcomes_and_reasons(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "realtime-agent-a.jsonl"
            _write_log(
                path,
                [
                    {"type": "voice.session.ready", "provider": "xai_realtime", "model": "grok-voice-latest"},
                    {"type": "hermes.run.background_completed", "ok": True},
                    {"type": "voice.response.forced_summary_streaming"},
                    {"type": "hermes.run.background_completed", "ok": True},
                    {"type": "voice.response.forced_summary_fallback", "reason": "acknowledgement_not_summary"},
                    {"type": "voice.response.delivery_fallback", "reason": "foreground_request_failed"},
                    {"type": "voice.realtime_agent.delivery_preempted", "reason": "new_user_turn"},
                    {"type": "not json relevant"},
                ],
            )
            report = scan_session_log(path)
        self.assertEqual("xai_realtime", report.provider)
        self.assertEqual("grok-voice-latest", report.model)
        self.assertEqual(2, report.background_runs)
        self.assertEqual(1, report.counts.get("provider_spoken_early_commit"))
        self.assertEqual(1, report.counts.get("validator_fallback"))
        self.assertEqual(1, report.counts.get("provider_death_fallback"))
        self.assertEqual(1, report.counts.get("preempted"))
        self.assertEqual(
            1,
            report.fallback_reasons.get("validator_fallback:acknowledgement_not_summary"),
        )
        self.assertEqual(1, report.provider_spoken)
        self.assertEqual(2, report.fallbacks)

    def test_resolved_model_overrides_requested_alias(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "realtime-agent-b.jsonl"
            _write_log(
                path,
                [
                    {"type": "voice.session.ready", "provider": "xai_realtime", "model": "grok-voice-latest"},
                    {
                        "type": "voice.realtime_agent.provider_model_resolved",
                        "requested_model": "grok-voice-latest",
                        "resolved_model": "grok-voice-think-fast-1.0",
                    },
                ],
            )
            report = scan_session_log(path)
        self.assertEqual("grok-voice-think-fast-1.0", report.model)

    def test_tolerates_malformed_lines(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "realtime-agent-c.jsonl"
            path.write_text(
                'not json\n{"type": "voice.response.forced_summary_delivered"}\n[1,2]\n',
                encoding="utf-8",
            )
            report = scan_session_log(path)
        self.assertEqual(1, report.counts.get("provider_spoken_end_validated"))


class SummarizeTest(unittest.TestCase):
    def test_summary_rate_across_sessions(self) -> None:
        with TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            _write_log(
                run_dir / "realtime-agent-a.jsonl",
                [
                    {"type": "voice.response.forced_summary_streaming"},
                    {"type": "voice.response.forced_summary_delivered"},
                    {"type": "hermes.run.background_completed", "ok": True},
                ],
            )
            _write_log(
                run_dir / "realtime-agent-b.jsonl",
                [
                    {"type": "voice.response.forced_summary_fallback", "reason": "no_answer_overlap"},
                ],
            )
            (run_dir / "unrelated.txt").write_text("skip", encoding="utf-8")
            reports = scan_run_dir(run_dir)
            summary = summarize(reports)
        self.assertEqual(2, summary["sessions"])
        self.assertEqual(1, summary["background_runs"])
        self.assertEqual(3, summary["deliveries"])
        self.assertEqual(2, summary["provider_spoken"])
        self.assertEqual(1, summary["fallbacks"])
        self.assertAlmostEqual(0.667, summary["provider_spoken_rate"], places=3)

    def test_empty_dir_has_no_rate(self) -> None:
        with TemporaryDirectory() as tmp:
            summary = summarize(scan_run_dir(Path(tmp)))
        self.assertEqual(0, summary["deliveries"])
        self.assertIsNone(summary["provider_spoken_rate"])


if __name__ == "__main__":
    unittest.main()
