"""Tests for plugin.relay.tailscale — optional Tailscale helper.

Uses ``unittest.mock.patch`` to stub ``subprocess.run`` and
``shutil.which`` — no test actually invokes the real ``tailscale``
binary. All public functions are asserted to *never raise*; they
return structured ``dict``s (or ``None`` for :func:`status`) so
callers can rely on that contract in the relay startup path and the
``install.sh`` optional step.
"""

from __future__ import annotations

import json
import subprocess
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from plugin.relay import tailscale


def _mk_completed(returncode: int, stdout: str = "", stderr: str = "") -> SimpleNamespace:
    """Build a ``subprocess.CompletedProcess``-shaped object.

    We use SimpleNamespace (rather than the real CompletedProcess) to
    sidestep any version-specific argument juggling — the tailscale
    module only reads ``.returncode``, ``.stdout``, and ``.stderr``.
    """
    return SimpleNamespace(returncode=returncode, stdout=stdout, stderr=stderr)


class StatusTests(unittest.TestCase):
    """``status()`` — graceful when CLI absent, parses canned JSON."""

    def test_returns_none_when_binary_missing(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value=None):
            self.assertIsNone(tailscale.status())

    def test_returns_none_when_binary_raises_file_not_found(self) -> None:
        """shutil.which may lie on exotic PATH configs — the subprocess
        call itself must also handle FileNotFoundError gracefully."""
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run", side_effect=FileNotFoundError):
            self.assertIsNone(tailscale.status())

    def test_parses_status_json(self) -> None:
        fixture = {
            "Self": {
                "HostName": "mybox",
                "DNSName": "mybox.tail1234.ts.net.",
                "TailscaleIPs": ["100.64.1.2", "fd7a:115c:a1e0::1"],
            },
            "Peer": {},
        }
        serve_fixture = {
            "TCP": {"443": {"HTTPS": True}},
            "Web": {"mybox.tail1234.ts.net:443": {"Handlers": {"/": {}}}},
        }

        def run_side_effect(argv, **kwargs):
            if argv[:3] == ["tailscale", "status", "--json"]:
                return _mk_completed(0, stdout=json.dumps(fixture))
            if argv[:4] == ["tailscale", "serve", "status", "--json"]:
                return _mk_completed(0, stdout=json.dumps(serve_fixture))
            return _mk_completed(1, stderr="unexpected argv")

        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run", side_effect=run_side_effect):
            result = tailscale.status()

        self.assertIsNotNone(result)
        assert result is not None  # type-narrowing for mypy/pyright
        self.assertTrue(result["available"])
        self.assertEqual(result["hostname"], "mybox.tail1234.ts.net")
        self.assertEqual(result["tailscale_ip"], "100.64.1.2")
        self.assertEqual(result["serve_ports"], [443])

    def test_status_nonzero_returns_none(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(1, stderr="not logged in")):
            self.assertIsNone(tailscale.status())

    def test_status_unparseable_json_returns_none(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(0, stdout="not-json")):
            self.assertIsNone(tailscale.status())

    def test_status_serve_ports_best_effort_on_failure(self) -> None:
        """If ``tailscale status`` works but ``serve status`` fails, we
        still return the outer dict — just with ``serve_ports=[]``."""
        status_fixture = {"Self": {"HostName": "mybox", "TailscaleIPs": ["100.64.1.2"]}}

        def run_side_effect(argv, **kwargs):
            if argv[:3] == ["tailscale", "status", "--json"]:
                return _mk_completed(0, stdout=json.dumps(status_fixture))
            if argv[:4] == ["tailscale", "serve", "status", "--json"]:
                return _mk_completed(1, stderr="serve not enabled")
            return _mk_completed(1, stderr="unexpected argv")

        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run", side_effect=run_side_effect):
            result = tailscale.status()

        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual(result["serve_ports"], [])
        self.assertEqual(result["tailscale_ip"], "100.64.1.2")

    def test_status_handles_timeout(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   side_effect=subprocess.TimeoutExpired(cmd="tailscale", timeout=5)):
            # Must not raise; returns None on the outer call.
            self.assertIsNone(tailscale.status())


class EnableDisableTests(unittest.TestCase):
    """``enable()`` / ``disable()`` — builds correct argv, never raises."""

    def test_enable_builds_https_command_by_default(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(0, stdout="ok")) as mock_run:
            result = tailscale.enable(port=8767)

        self.assertTrue(result["ok"])
        self.assertEqual(
            result["command"],
            ["tailscale", "serve", "--bg", "--https=8767", "http://127.0.0.1:8767"],
        )
        # subprocess.run saw the same argv (positional arg 0).
        called_argv = mock_run.call_args.args[0]
        self.assertEqual(
            called_argv,
            ["tailscale", "serve", "--bg", "--https=8767", "http://127.0.0.1:8767"],
        )

    def test_enable_with_custom_port_and_plain_http(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(0)):
            result = tailscale.enable(port=9000, https=False)

        self.assertEqual(
            result["command"],
            ["tailscale", "serve", "--bg", "--http=9000", "http://127.0.0.1:9000"],
        )

    def test_enable_returns_ok_false_when_binary_missing(self) -> None:
        """Must not raise — structured ``ok=False`` response instead."""
        with patch("plugin.relay.tailscale.shutil.which", return_value=None):
            result = tailscale.enable(port=8767)
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["message"])
        # Command is still returned so callers can surface what *would*
        # have run.
        self.assertEqual(
            result["command"],
            ["tailscale", "serve", "--bg", "--https=8767", "http://127.0.0.1:8767"],
        )

    def test_enable_surfaces_nonzero_exit_as_ok_false(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(1, stderr="permission denied")):
            result = tailscale.enable(port=8767)
        self.assertFalse(result["ok"])
        self.assertIn("permission denied", result["message"])

    def test_disable_builds_expected_command(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(0, stdout="ok")) as mock_run:
            result = tailscale.disable(port=8767)

        self.assertTrue(result["ok"])
        self.assertEqual(
            result["command"],
            ["tailscale", "serve", "--https=8767", "off"],
        )
        called_argv = mock_run.call_args.args[0]
        self.assertEqual(called_argv, ["tailscale", "serve", "--https=8767", "off"])

    def test_disable_no_binary_returns_ok_false(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value=None):
            result = tailscale.disable(port=8767)
        self.assertFalse(result["ok"])
        self.assertIn("not found", result["message"])


class CanonicalUpstreamPresentTests(unittest.TestCase):
    """The exit-criteria probe for removing this module post-PR-#9295."""

    def test_true_when_help_mentions_tailscale_flag(self) -> None:
        help_output = (
            "Usage: hermes gateway run [OPTIONS]\n"
            "\n"
            "Options:\n"
            "  --tailscale           Serve via Tailscale.\n"
        )
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/hermes"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(0, stdout=help_output)):
            self.assertTrue(tailscale.canonical_upstream_present())

    def test_false_when_flag_absent(self) -> None:
        help_output = "Usage: hermes gateway run [OPTIONS]\n  --config PATH\n"
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/hermes"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(0, stdout=help_output)):
            self.assertFalse(tailscale.canonical_upstream_present())

    def test_false_when_hermes_missing(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value=None):
            self.assertFalse(tailscale.canonical_upstream_present())

    def test_false_when_help_exits_nonzero(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/hermes"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(2, stderr="usage error")):
            self.assertFalse(tailscale.canonical_upstream_present())

    def test_matches_flag_when_emitted_on_stderr(self) -> None:
        """Some CLI parsers write help output to stderr, not stdout."""
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/hermes"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   return_value=_mk_completed(0, stdout="", stderr="--tailscale  Serve via tailnet")):
            self.assertTrue(tailscale.canonical_upstream_present())


class NeverRaisesContractTests(unittest.TestCase):
    """Defensive coverage — the public API must never raise.

    This is load-bearing for the ``install.sh`` optional step and the
    ``plugin/pair.py --mode auto`` integration — they call these
    functions unconditionally and must be able to rely on a return
    value, not a try/except."""

    def test_status_never_raises_on_oserror(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   side_effect=OSError("disk is on fire")):
            self.assertIsNone(tailscale.status())

    def test_enable_never_raises_on_oserror(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   side_effect=OSError("kernel panic")):
            result = tailscale.enable(port=8767)
        self.assertFalse(result["ok"])

    def test_disable_never_raises_on_timeout(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/tailscale"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   side_effect=subprocess.TimeoutExpired(cmd="tailscale", timeout=5)):
            result = tailscale.disable(port=8767)
        self.assertFalse(result["ok"])

    def test_canonical_upstream_present_never_raises_on_oserror(self) -> None:
        with patch("plugin.relay.tailscale.shutil.which", return_value="/usr/bin/hermes"), \
             patch("plugin.relay.tailscale.subprocess.run",
                   side_effect=OSError("nope")):
            self.assertFalse(tailscale.canonical_upstream_present())


if __name__ == "__main__":
    unittest.main()
