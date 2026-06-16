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

    def test_relay_cli_registers_doctor_command(self) -> None:
        parser = argparse.ArgumentParser()
        cli.register_relay_cli(parser)

        parsed = parser.parse_args(["doctor", "--json", "--timeout", "0.1"])

        self.assertTrue(parsed.json)
        self.assertEqual(parsed.timeout, 0.1)
        self.assertIs(parsed.func, cli.relay_doctor_command)


if __name__ == "__main__":
    unittest.main()
