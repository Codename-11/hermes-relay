from __future__ import annotations

import argparse
import unittest
from unittest import mock
from pathlib import Path

from plugin import cli, doctor


def _fake_probe(url: str, *, method: str = "GET", timeout: float = 2.0):
    return {
        "ok": True,
        "exists": True,
        "status": 200,
        "method": method,
        "timeout": timeout,
        "url": url,
    }


def _probe_map(responses):
    """Probe stub keyed by exact URL; unknown URLs read as unreachable."""

    def probe(url: str, *, method: str = "GET", timeout: float = 2.0):
        base = {
            "ok": False,
            "exists": False,
            "status": None,
            "method": method,
            "url": url,
            "error": "unreachable",
        }
        override = responses.get(url, {})
        return {**base, **override}

    return probe


class DoctorTests(unittest.TestCase):
    def test_collect_doctor_report_uses_standard_route_probes(self) -> None:
        with mock.patch("importlib.util.find_spec", return_value=None):
            report = doctor.collect_doctor_report(
                api_url="http://api.example:8642",
                dashboard_url="http://dash.example:9119",
                relay_port=9999,
                timeout=0.25,
                probe=_fake_probe,
                site_dirs=[],
            )

        self.assertEqual(report["schema_version"], 1)
        self.assertEqual(report["plugin"]["name"], "hermes-relay")
        self.assertEqual(
            report["standard"]["api"]["capabilities"]["url"],
            "http://api.example:8642/v1/capabilities",
        )
        self.assertEqual(
            report["standard"]["dashboard"]["audio_transcribe"]["method"],
            "HEAD",
        )
        self.assertEqual(
            report["standard"]["dashboard"]["ws_ticket"]["url"],
            "http://dash.example:9119/api/auth/ws-ticket",
        )
        self.assertEqual(
            report["relay"]["info"]["url"],
            "http://127.0.0.1:9999/relay/info",
        )
        self.assertFalse(report["bootstrap"]["installed"])

    def test_bootstrap_status_detects_legacy_pth(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as raw:
            tmp_path = Path(raw)
            (tmp_path / "hermes_relay_bootstrap.pth").write_text(
                "import hermes_relay_bootstrap\n",
                encoding="utf-8",
            )

            with mock.patch("importlib.util.find_spec", return_value=None):
                status = doctor._bootstrap_status([tmp_path])

        self.assertTrue(status["installed"])
        self.assertEqual(
            status["pth_files"],
            [str(tmp_path / "hermes_relay_bootstrap.pth")],
        )

    def test_render_doctor_text_includes_checks(self) -> None:
        with mock.patch("importlib.util.find_spec", return_value=None):
            report = doctor.collect_doctor_report(
                api_url="http://api.example:8642",
                dashboard_url="http://dash.example:9119",
                relay_port=9999,
                probe=_fake_probe,
                site_dirs=[],
            )

        rendered = doctor.render_doctor_text(report)

        self.assertIn("Hermes-Relay doctor", rendered)
        self.assertIn("[OK] plugin-root", rendered)
        self.assertIn("vanilla upstream Hermes", rendered)

    def test_classify_manage_surface_ok_for_reachable_dashboard(self) -> None:
        surface = doctor.classify_manage_surface(
            {"ok": True, "exists": True, "status": 200},
            {"ok": False, "exists": False, "status": None},
        )

        self.assertEqual(surface["kind"], "dashboard")
        self.assertIsNone(surface["recommendation"])

    def test_classify_manage_surface_flags_api_server_url(self) -> None:
        surface = doctor.classify_manage_surface(
            {"ok": False, "exists": False, "status": 404},
            {"ok": True, "exists": True, "status": 200},
        )

        self.assertEqual(surface["kind"], "api-server")
        self.assertIn("hermes dashboard", surface["recommendation"])
        self.assertIn("hermes serve", surface["recommendation"])

    def test_classify_manage_surface_unreachable_recommends_dashboard(self) -> None:
        surface = doctor.classify_manage_surface(
            {"ok": False, "exists": False, "status": None},
            {"ok": False, "exists": False, "status": None},
        )

        self.assertEqual(surface["kind"], "unreachable")
        self.assertIn("hermes dashboard", surface["recommendation"])

    def test_doctor_warns_when_dashboard_url_answers_like_api_server(self) -> None:
        probe = _probe_map(
            {
                "http://dash.example:8642/api/status": {"status": 404, "exists": False},
                "http://dash.example:8642/v1/capabilities": {
                    "ok": True,
                    "exists": True,
                    "status": 200,
                },
            }
        )
        with mock.patch("importlib.util.find_spec", return_value=None):
            report = doctor.collect_doctor_report(
                api_url="http://api.example:8642",
                dashboard_url="http://dash.example:8642",
                relay_port=9999,
                probe=probe,
                site_dirs=[],
            )

        surface = report["standard"]["dashboard"]["manage_surface"]
        self.assertEqual(surface["kind"], "api-server")
        check = next(
            c for c in report["checks"] if c["id"] == "dashboard-manage-surface"
        )
        self.assertEqual(check["status"], "warn")

        rendered = doctor.render_doctor_text(report)
        self.assertIn("hermes dashboard", rendered)
        self.assertIn("hermes serve", rendered)

    def test_doctor_reports_session_prune_route(self) -> None:
        with mock.patch("importlib.util.find_spec", return_value=None):
            report = doctor.collect_doctor_report(
                api_url="http://api.example:8642",
                dashboard_url="http://dash.example:9119",
                relay_port=9999,
                probe=_fake_probe,
                site_dirs=[],
            )

        prune = report["standard"]["dashboard"]["sessions_prune"]
        self.assertEqual(prune["url"], "http://dash.example:9119/api/sessions/prune")
        # Route probing must never POST — a real POST would run the prune.
        self.assertEqual(prune["method"], "HEAD")
        check = next(
            c for c in report["checks"] if c["id"] == "dashboard-session-prune"
        )
        self.assertEqual(check["status"], "ok")

    def test_doctor_warns_when_session_prune_route_missing(self) -> None:
        probe = _probe_map(
            {
                "http://dash.example:9119/api/status": {
                    "ok": True,
                    "exists": True,
                    "status": 200,
                },
            }
        )
        with mock.patch("importlib.util.find_spec", return_value=None):
            report = doctor.collect_doctor_report(
                api_url="http://api.example:8642",
                dashboard_url="http://dash.example:9119",
                relay_port=9999,
                probe=probe,
                site_dirs=[],
            )

        check = next(
            c for c in report["checks"] if c["id"] == "dashboard-session-prune"
        )
        self.assertEqual(check["status"], "warn")

    def test_relay_cli_registers_doctor_command(self) -> None:
        parser = argparse.ArgumentParser()
        cli.register_relay_cli(parser)

        parsed = parser.parse_args(["doctor", "--json", "--timeout", "0.1"])

        self.assertTrue(parsed.json)
        self.assertEqual(parsed.timeout, 0.1)
        self.assertIs(parsed.func, cli.relay_doctor_command)


if __name__ == "__main__":
    unittest.main()
