"""Tests for the ``hermes-pair --register-code`` manual-fallback flow.

Stdlib ``unittest`` only — the plugin tests dir has a ``conftest.py`` that
imports ``responses``, which isn't always installed; running these via
``python -m unittest plugin.tests.test_register_code`` bypasses the
conftest entirely.

Coverage:

* ``normalize_pairing_code`` — accepts valid codes, rejects bad chars,
  rejects wrong length, rejects empty/None.
* ``register_code_command`` — happy path posts to ``/pairing/register``
  with the right body shape (incl. TTL/grants/transport-hint), surfaces
  validation failures as exit code 2, surfaces relay-down as exit code 1,
  surfaces relay-rejection as exit code 1.
"""

from __future__ import annotations

import io
import json
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

from plugin import pair


# ── normalize_pairing_code ───────────────────────────────────────────────────


class NormalizePairingCodeTests(unittest.TestCase):
    def test_accepts_valid_uppercase(self) -> None:
        self.assertEqual(pair.normalize_pairing_code("ABCD12"), "ABCD12")

    def test_uppercases_lowercase(self) -> None:
        self.assertEqual(pair.normalize_pairing_code("abcd12"), "ABCD12")

    def test_strips_whitespace(self) -> None:
        self.assertEqual(pair.normalize_pairing_code("  ABCD12 \n"), "ABCD12")

    def test_accepts_all_digits(self) -> None:
        self.assertEqual(pair.normalize_pairing_code("123456"), "123456")

    def test_accepts_all_letters(self) -> None:
        self.assertEqual(pair.normalize_pairing_code("ABCDEF"), "ABCDEF")

    def test_rejects_empty(self) -> None:
        with self.assertRaises(pair.InvalidPairingCodeError) as ctx:
            pair.normalize_pairing_code("")
        self.assertIn("empty", str(ctx.exception).lower())

    def test_rejects_whitespace_only(self) -> None:
        with self.assertRaises(pair.InvalidPairingCodeError):
            pair.normalize_pairing_code("   ")

    def test_rejects_none(self) -> None:
        with self.assertRaises(pair.InvalidPairingCodeError):
            pair.normalize_pairing_code(None)  # type: ignore[arg-type]

    def test_rejects_too_short(self) -> None:
        with self.assertRaises(pair.InvalidPairingCodeError) as ctx:
            pair.normalize_pairing_code("ABC12")
        self.assertIn("6 characters", str(ctx.exception))

    def test_rejects_too_long(self) -> None:
        with self.assertRaises(pair.InvalidPairingCodeError) as ctx:
            pair.normalize_pairing_code("ABCD1234")
        self.assertIn("6 characters", str(ctx.exception))

    def test_rejects_invalid_chars_dash(self) -> None:
        with self.assertRaises(pair.InvalidPairingCodeError) as ctx:
            pair.normalize_pairing_code("AB-D12")
        self.assertIn("invalid", str(ctx.exception).lower())

    def test_rejects_invalid_chars_punct(self) -> None:
        with self.assertRaises(pair.InvalidPairingCodeError):
            pair.normalize_pairing_code("ABCD1!")

    def test_rejects_unicode(self) -> None:
        with self.assertRaises(pair.InvalidPairingCodeError):
            pair.normalize_pairing_code("ABCDé2")


# ── register_code_command ───────────────────────────────────────────────────


def _args(**overrides: object) -> types.SimpleNamespace:
    """Build an argparse-Namespace stand-in for register_code_command."""
    defaults: dict[str, object] = {
        "register_code": None,
        "ttl": None,
        "grants": None,
        "transport_hint": None,
    }
    defaults.update(overrides)
    return types.SimpleNamespace(**defaults)


class RegisterCodeCommandTests(unittest.TestCase):
    def test_happy_path_posts_with_default_ttl(self) -> None:
        captured: dict[str, object] = {}

        def fake_register(port, code, **kwargs):
            captured["port"] = port
            captured["code"] = code
            captured.update(kwargs)
            return True

        def fake_probe(port, timeout_s=1.0):
            return {"status": "ok", "version": "test"}

        with patch.object(pair, "probe_relay", side_effect=fake_probe), patch.object(
            pair, "register_relay_code", side_effect=fake_register
        ), patch.object(pair, "read_relay_config", return_value={"host": "0.0.0.0", "port": 8767, "tls": False}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = pair.register_code_command(_args(register_code="ABCD12"))

        self.assertEqual(rc, 0)
        self.assertEqual(captured["code"], "ABCD12")
        self.assertEqual(captured["port"], 8767)
        # Default TTL is 30d when --ttl not supplied.
        self.assertEqual(captured["ttl_seconds"], 30 * 24 * 3600)
        self.assertEqual(captured["transport_hint"], "ws")
        self.assertIsNone(captured["grants"])
        out = buf.getvalue()
        self.assertIn("ABCD12", out)
        self.assertIn("Manual pairing code", out)
        self.assertIn("Tap Connect", out)

    def test_happy_path_with_ttl_and_grants(self) -> None:
        captured: dict[str, object] = {}

        def fake_register(port, code, **kwargs):
            captured.update(kwargs)
            captured["port"] = port
            captured["code"] = code
            return True

        with patch.object(pair, "probe_relay", return_value={"status": "ok"}), patch.object(
            pair, "register_relay_code", side_effect=fake_register
        ), patch.object(pair, "read_relay_config", return_value={"host": "0.0.0.0", "port": 8767, "tls": True}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = pair.register_code_command(
                    _args(
                        register_code="abcd12",
                        ttl="7d",
                        grants="terminal=1d,bridge=1d",
                    )
                )

        self.assertEqual(rc, 0)
        self.assertEqual(captured["code"], "ABCD12")  # auto-uppercased
        self.assertEqual(captured["ttl_seconds"], 7 * 24 * 3600)
        self.assertEqual(
            captured["grants"], {"terminal": 86400, "bridge": 86400}
        )
        # tls=True in relay_cfg → default transport_hint "wss".
        self.assertEqual(captured["transport_hint"], "wss")

    def test_transport_hint_override(self) -> None:
        captured: dict[str, object] = {}

        def fake_register(port, code, **kwargs):
            captured.update(kwargs)
            return True

        with patch.object(pair, "probe_relay", return_value={"status": "ok"}), patch.object(
            pair, "register_relay_code", side_effect=fake_register
        ), patch.object(pair, "read_relay_config", return_value={"host": "0.0.0.0", "port": 8767, "tls": False}):
            with redirect_stdout(io.StringIO()):
                rc = pair.register_code_command(
                    _args(register_code="ABCD12", transport_hint="wss")
                )
        self.assertEqual(rc, 0)
        self.assertEqual(captured["transport_hint"], "wss")

    def test_invalid_chars_rejected(self) -> None:
        with patch.object(pair, "probe_relay") as probe, patch.object(
            pair, "register_relay_code"
        ) as reg:
            err = io.StringIO()
            with redirect_stderr(err):
                rc = pair.register_code_command(_args(register_code="ABCD-2"))
        self.assertEqual(rc, 2)
        self.assertIn("invalid characters", err.getvalue().lower())
        # Validation failed — we must NOT have hit the network.
        probe.assert_not_called()
        reg.assert_not_called()

    def test_wrong_length_rejected(self) -> None:
        with patch.object(pair, "probe_relay") as probe, patch.object(
            pair, "register_relay_code"
        ) as reg:
            err = io.StringIO()
            with redirect_stderr(err):
                rc = pair.register_code_command(_args(register_code="ABC"))
        self.assertEqual(rc, 2)
        self.assertIn("6 characters", err.getvalue())
        probe.assert_not_called()
        reg.assert_not_called()

    def test_empty_code_rejected(self) -> None:
        with patch.object(pair, "probe_relay") as probe:
            err = io.StringIO()
            with redirect_stderr(err):
                rc = pair.register_code_command(_args(register_code="   "))
        self.assertEqual(rc, 2)
        self.assertIn("empty", err.getvalue().lower())
        probe.assert_not_called()

    def test_relay_unreachable_returns_1(self) -> None:
        with patch.object(pair, "probe_relay", return_value=None), patch.object(
            pair, "register_relay_code"
        ) as reg, patch.object(
            pair,
            "read_relay_config",
            return_value={"host": "0.0.0.0", "port": 8767, "tls": False},
        ):
            err = io.StringIO()
            with redirect_stderr(err):
                rc = pair.register_code_command(_args(register_code="ABCD12"))
        self.assertEqual(rc, 1)
        self.assertIn("No relay reachable", err.getvalue())
        # Probe failed → never tried to register.
        reg.assert_not_called()

    def test_relay_rejects_code_returns_1(self) -> None:
        with patch.object(pair, "probe_relay", return_value={"status": "ok"}), patch.object(
            pair, "register_relay_code", return_value=False
        ), patch.object(
            pair,
            "read_relay_config",
            return_value={"host": "0.0.0.0", "port": 8767, "tls": False},
        ):
            err = io.StringIO()
            with redirect_stderr(err):
                rc = pair.register_code_command(_args(register_code="ABCD12"))
        self.assertEqual(rc, 1)
        self.assertIn("rejected", err.getvalue().lower())
        self.assertIn("loopback", err.getvalue().lower())

    def test_invalid_ttl_rejected(self) -> None:
        with patch.object(pair, "probe_relay") as probe:
            err = io.StringIO()
            with redirect_stderr(err):
                rc = pair.register_code_command(
                    _args(register_code="ABCD12", ttl="bogus")
                )
        self.assertEqual(rc, 2)
        self.assertIn("--ttl", err.getvalue())
        probe.assert_not_called()

    def test_invalid_grants_rejected(self) -> None:
        with patch.object(pair, "probe_relay") as probe:
            err = io.StringIO()
            with redirect_stderr(err):
                rc = pair.register_code_command(
                    _args(register_code="ABCD12", grants="terminal-7d")
                )
        self.assertEqual(rc, 2)
        self.assertIn("--grants", err.getvalue())
        probe.assert_not_called()


# ── register_relay_code wire-shape integration smoke test ────────────────────


class RegisterRelayCodeWireShapeTests(unittest.TestCase):
    """Verify the body the manual-flow path actually puts on the wire is
    the same shape ``handle_pairing_register`` already knows how to parse:
    ``{"code": ..., "ttl_seconds": ..., "grants": ..., "transport_hint": ...}``.
    """

    def test_body_includes_all_optional_fields(self) -> None:
        captured: dict[str, object] = {}

        class _FakeResponse:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"ok": true, "code": "ABCD12"}'

        def fake_urlopen(req, timeout=None):
            captured["url"] = req.full_url
            captured["body"] = json.loads(req.data.decode("utf-8"))
            return _FakeResponse()

        with patch("plugin.pair.urllib.request.urlopen", side_effect=fake_urlopen):
            ok = pair.register_relay_code(
                8767,
                "ABCD12",
                ttl_seconds=86400,
                grants={"terminal": 3600},
                transport_hint="ws",
            )

        self.assertTrue(ok)
        self.assertEqual(captured["url"], "http://127.0.0.1:8767/pairing/register")
        body = captured["body"]
        self.assertEqual(body["code"], "ABCD12")
        self.assertEqual(body["ttl_seconds"], 86400)
        self.assertEqual(body["grants"], {"terminal": 3600})
        self.assertEqual(body["transport_hint"], "ws")

    def test_minimal_body_only_has_code(self) -> None:
        captured: dict[str, object] = {}

        class _FakeResponse:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"ok": true}'

        def fake_urlopen(req, timeout=None):
            captured["body"] = json.loads(req.data.decode("utf-8"))
            return _FakeResponse()

        with patch("plugin.pair.urllib.request.urlopen", side_effect=fake_urlopen):
            ok = pair.register_relay_code(8767, "ABCD12")

        self.assertTrue(ok)
        body = captured["body"]
        self.assertEqual(body, {"code": "ABCD12"})


if __name__ == "__main__":
    unittest.main()
