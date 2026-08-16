from __future__ import annotations

import json
import os
import tempfile
import time
import unittest
from pathlib import Path

from plugin.gateway_diagnostics import assess_gateway_heartbeat, assess_gateway_prior_exit


class GatewayHeartbeatTests(unittest.TestCase):
    def write_heartbeat(self, home: Path, payload: object, *, mtime: float) -> None:
        path = home / "state" / "gateway.heartbeat"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload), encoding="utf-8")
        os.utime(path, (mtime, mtime))

    def test_missing_and_legacy_are_non_warning_unsupported_states(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            self.assertEqual(assess_gateway_heartbeat(home=home)["status"], "missing")
            (home / "gateway.pid").write_text('{"pid":42}', encoding="utf-8")
            result = assess_gateway_heartbeat(home=home)
            self.assertEqual(result, {"status": "legacy", "supported": False})

    def test_fresh_and_stale_require_payload_and_mtime_freshness(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            payload = {"pid": 42, "updated_at": "2026-07-19T12:00:00+00:00", "start_time": 10.0}
            payload_now = 1784462400.0
            self.write_heartbeat(home, payload, mtime=payload_now)
            fresh = assess_gateway_heartbeat(
                home=home, now=payload_now + 30, process_started_at=lambda _pid: 10.0
            )
            self.assertEqual(fresh["status"], "fresh")
            stale = assess_gateway_heartbeat(
                home=home, now=payload_now + 120, process_started_at=lambda _pid: 10.0
            )
            self.assertEqual(stale["status"], "stale")

    def test_malformed_pid_and_start_mismatch_are_sanitized(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            self.write_heartbeat(home, "bad", mtime=time.time())
            self.assertEqual(assess_gateway_heartbeat(home=home)["status"], "malformed")

            payload = {"pid": 42, "updated_at": "2026-07-19T12:00:00+00:00", "start_time": 10.0}
            self.write_heartbeat(home, payload, mtime=1784462400.0)
            (home / "gateway.pid").write_text('{"pid":43}', encoding="utf-8")
            self.assertEqual(assess_gateway_heartbeat(home=home)["status"], "pid_mismatch")
            (home / "gateway.pid").write_text('{"pid":42}', encoding="utf-8")
            result = assess_gateway_heartbeat(
                home=home, now=1784462410.0, process_started_at=lambda _pid: 99.0
            )
            self.assertEqual(result, {"status": "start_mismatch", "supported": True})


class GatewayPriorExitTests(unittest.TestCase):
    def write_heartbeat(self, home: Path, payload: object, *, mtime: float) -> None:
        path = home / "state" / "gateway.heartbeat"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload), encoding="utf-8")
        os.utime(path, (mtime, mtime))

    def write_records(self, home: Path, *records: object) -> None:
        path = home / "logs" / "gateway-exit-diag.log"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(json.dumps(record) for record in records), encoding="utf-8")

    def test_missing_or_first_start_is_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            self.assertEqual(assess_gateway_prior_exit(home=home), {"prior_exit": "unknown"})
            self.write_records(home, {"tag": "gateway.start", "argv": ["secret"]})
            self.assertEqual(assess_gateway_prior_exit(home=home), {"prior_exit": "unknown"})

    def test_clean_exit_is_sanitized(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            self.write_records(
                home,
                {"tag": "gateway.start"},
                {"tag": "asyncio.run.SystemExit", "traceback": "private"},
                {"tag": "atexit.hook", "path": "/private"},
                {"tag": "gateway.start", "argv": ["private"]},
            )
            self.assertEqual(assess_gateway_prior_exit(home=home), {"prior_exit": "clean"})

    def test_unclean_exit_exposes_only_bounded_oom_hint(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            self.write_records(
                home,
                {"tag": "gateway.start"},
                {
                    "tag": "gateway.previous_unclean_exit",
                    "prior_pid": 42,
                    "last_heartbeat_mem": {"mem_available_kib": 1},
                    "suspected_oom": True,
                },
                {"tag": "gateway.start"},
            )
            self.assertEqual(
                assess_gateway_prior_exit(home=home),
                {"prior_exit": "unclean", "suspected_oom": True},
            )

    def test_malformed_tail_does_not_crash_or_leak(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            path = home / "logs" / "gateway-exit-diag.log"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("not-json\n{}\n", encoding="utf-8")
            self.assertEqual(assess_gateway_prior_exit(home=home), {"prior_exit": "unknown"})

    def test_runner_start_after_process_start_is_not_a_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            payload_now = 1784462400.0
            payload = {
                "pid": 42,
                "updated_at": "2026-07-19T12:00:00+00:00",
                "start_time": 110.0,
            }
            self.write_heartbeat(home, payload, mtime=payload_now)
            (home / "gateway.pid").write_text(
                '{"pid":42,"start_time":100.0}', encoding="utf-8"
            )

            result = assess_gateway_heartbeat(
                home=home,
                now=payload_now + 10,
                process_started_at=lambda _pid: 100.0,
            )

            self.assertEqual(result["status"], "fresh")

    def test_pid_record_start_must_match_live_process(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            payload = {
                "pid": 42,
                "updated_at": "2026-07-19T12:00:00+00:00",
                "start_time": 100.0,
            }
            self.write_heartbeat(home, payload, mtime=1784462400.0)
            (home / "gateway.pid").write_text(
                '{"pid":42,"start_time":50.0}', encoding="utf-8"
            )

            result = assess_gateway_heartbeat(
                home=home,
                now=1784462410.0,
                process_started_at=lambda _pid: 100.0,
            )

            self.assertEqual(result, {"status": "start_mismatch", "supported": True})


if __name__ == "__main__":
    unittest.main()
