"""Tests for the ``hermes-pair --register-code`` manual-fallback flow.

Stdlib ``unittest`` only — the plugin tests dir has a ``conftest.py`` that
imports ``responses``, which isn't always installed; running these via
``python -m unittest plugin.tests.test_register_code`` bypasses the
conftest entirely.

Coverage:

* ``normalize_pairing_code`` — accepts valid codes, rejects bad chars,
  rejects wrong length, rejects empty/None.
* ``pair_command`` — keeps the API bearer in top-level ``key`` while the
  one-shot relay pairing code stays in ``relay.code``.
* ``register_code_command`` — happy path posts to ``/pairing/register``
  with the right body shape (incl. TTL/grants/transport-hint), surfaces
  validation failures as exit code 2, surfaces relay-down as exit code 1,
  surfaces relay-rejection as exit code 1.
"""

from __future__ import annotations

import argparse
import io
import json
import base64
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from urllib.parse import parse_qs, urlparse
from unittest.mock import patch

from plugin import cli, pair


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


class DashboardIngressPairingTests(unittest.TestCase):
    def test_dashboard_ingress_replaces_implicit_direct_relay(self) -> None:
        payload = json.loads(pair.build_pairing_qr_payload(
            host="192.168.1.20",
            port=8642,
            key="api-key",
            tls=False,
            relay={
                "url": "ws://192.168.1.20:8767",
                "code": "ABC123",
                "transport_hint": "ws",
            },
            dashboard_url="https://hermes.example.test/base/",
            sign=False,
        ))

        self.assertEqual(payload["hermes"], 3)
        self.assertEqual(payload["dashboard_url"], "https://hermes.example.test/base")
        self.assertEqual(payload["endpoints"][0], {
            "role": "public",
            "priority": 0,
            "recommended": False,
            "dashboard": {"url": "https://hermes.example.test/base"},
            "relay": {
                "url": "wss://hermes.example.test/base/api/plugins/hermes-relay/transport",
                "transport_hint": "wss",
            },
        })
        self.assertEqual(len(payload["endpoints"]), 1)
        self.assertNotIn(":8767", json.dumps(payload))
        self.assertEqual(
            payload["relay"]["url"],
            "wss://hermes.example.test/base/api/plugins/hermes-relay/transport",
        )

    def test_explicit_legacy_direct_relay_is_last_and_non_recommended(self) -> None:
        payload = json.loads(pair.build_pairing_qr_payload(
            host="192.168.1.20",
            port=8642,
            key="api-key",
            tls=False,
            relay={"url": "ws://192.168.1.20:8767", "code": "ABC123"},
            dashboard_url="https://hermes.example.test",
            legacy_direct_relay=True,
            sign=False,
        ))

        legacy = payload["endpoints"][-1]
        self.assertEqual(legacy["role"], "legacy_direct")
        self.assertTrue(legacy["legacy"])
        self.assertFalse(legacy["recommended"])
        self.assertEqual(legacy["relay"]["url"], "ws://192.168.1.20:8767")
        self.assertEqual(payload["relay"]["url"], "ws://192.168.1.20:8767")

    def test_api_less_payload_omits_legacy_api_fields(self) -> None:
        payload = json.loads(pair.build_pairing_qr_payload(
            host=None,
            port=None,
            key=None,
            tls=None,
            relay={"url": "ws://192.168.1.20:8767", "code": "ABC123"},
            dashboard_url="http://192.168.1.20:9119",
            sign=False,
        ))

        self.assertNotIn("host", payload)
        self.assertNotIn("port", payload)
        self.assertNotIn("key", payload)
        self.assertNotIn("tls", payload)
        self.assertNotIn("api", payload["endpoints"][0])
        self.assertEqual(len(payload["endpoints"]), 1)
        self.assertEqual(payload["endpoints"][0]["role"], "lan")

    def test_dashboard_ingress_does_not_duplicate_existing_candidate(self) -> None:
        ingress = {
            "role": "tailscale",
            "priority": 4,
            "dashboard": {"url": "https://host.tailnet.ts.net"},
            "relay": {
                "url": "wss://host.tailnet.ts.net/api/plugins/hermes-relay/transport",
                "transport_hint": "wss",
            },
        }
        payload = json.loads(pair.build_pairing_qr_payload(
            host=None,
            port=None,
            key=None,
            tls=None,
            relay={"url": "ws://100.64.0.10:8767", "code": "ABC123"},
            endpoints=[ingress],
            dashboard_url="https://host.tailnet.ts.net",
            sign=False,
        ))

        self.assertEqual(
            sum(1 for candidate in payload["endpoints"] if candidate.get("dashboard")),
            1,
        )
        self.assertEqual(payload["endpoints"][0]["priority"], 0)

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


# ── pair_command ─────────────────────────────────────────────────────────────


def _pair_args(**overrides: object) -> types.SimpleNamespace:
    """Build an argparse-Namespace stand-in for pair_command."""
    defaults: dict[str, object] = {
        "register_code": None,
        "host": None,
        "port": None,
        "ttl": None,
        "grants": None,
        "no_relay": False,
        "mode": "auto",
        "public_url": None,
        "prefer": None,
        "png": False,
        "no_qr": True,
    }
    defaults.update(overrides)
    return types.SimpleNamespace(**defaults)


class PairCommandTests(unittest.TestCase):
    def test_plugin_cli_registers_full_pair_surface(self) -> None:
        parser = argparse.ArgumentParser()
        cli.register_cli(parser)

        args = parser.parse_args(
            [
                "--register-code",
                "ABC123",
                "--transport-hint",
                "wss",
                "--dashboard-url",
                "https://dash.example.com",
                "--mode",
                "auto",
                "--public-url",
                "https://hermes.example.com",
                "--prefer",
                "tailscale",
            ]
        )

        self.assertEqual(args.register_code, "ABC123")
        self.assertEqual(args.transport_hint, "wss")
        self.assertEqual(args.dashboard_url, "https://dash.example.com")
        self.assertEqual(args.mode, "auto")
        self.assertEqual(args.public_url, "https://hermes.example.com")
        self.assertEqual(args.prefer, "tailscale")

    def test_pairing_invite_url_round_trips_payload(self) -> None:
        payload = json.dumps({
            "hermes": 2,
            "host": "10.0.0.42",
            "port": 8642,
            "key": "sk-api",
            "tls": False,
            "relay": {"url": "ws://10.0.0.42:8767", "code": "ABCD12"},
        })
        invite = pair.build_pairing_invite_url(payload)

        self.assertTrue(invite.startswith("hermes-relay://pair?payload="))
        query = parse_qs(urlparse(invite).query)
        encoded = query["payload"][0]
        padded = encoded + ("=" * (-len(encoded) % 4))
        decoded = base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8")
        self.assertEqual(json.loads(decoded), json.loads(payload))

    def test_pair_command_rejects_plaintext_public_url_before_mint(self) -> None:
        with patch.object(
            pair,
            "read_server_config",
            return_value={
                "enabled": True,
                "host": "10.0.0.42",
                "port": 8642,
                "key": "api-key",
                "tls": False,
            },
        ), patch.object(
            pair,
            "read_relay_config",
            return_value={"host": "0.0.0.0", "port": 8767, "tls": False},
        ), patch.object(
            pair, "probe_relay", return_value={"status": "ok"}
        ), patch.object(pair, "mint_relay_pairing") as mint:
            with self.assertRaises(SystemExit) as raised:
                pair.pair_command(_pair_args(
                    mode="public",
                    public_url="http://public.example",
                ))

        self.assertEqual(raised.exception.code, 2)
        mint.assert_not_called()

    def test_qr_payload_keeps_api_key_separate_from_relay_pair_code(self) -> None:
        captured: dict[str, object] = {}

        def fake_build_payload(
            host,
            port,
            key,
            tls,
            relay=None,
            sign=True,
            endpoints=None,
            dashboard_url=None,
            legacy_direct_relay=False,
        ):
            payload = {
                "hermes": 2 if relay else 1,
                "host": host,
                "port": port,
                "key": key,
                "tls": tls,
            }
            if relay is not None:
                payload["relay"] = relay
            if endpoints:
                payload["endpoints"] = endpoints
            if dashboard_url:
                payload["dashboard_url"] = dashboard_url
            captured["payload"] = payload
            return json.dumps(payload)

        with patch.object(
            pair,
            "read_server_config",
            return_value={
                "host": "10.0.0.42",
                "port": 8642,
                "key": "sk-cli-config",
                "tls": False,
            },
        ), patch.object(
            pair,
            "read_relay_config",
            return_value={"host": "0.0.0.0", "port": 8767, "tls": False},
        ), patch.object(
            pair, "probe_relay", return_value={"status": "ok"}
        ), patch.object(
            pair, "_generate_relay_code", return_value="ABCD12"
        ), patch.object(
            pair, "register_relay_code", return_value=True
        ), patch.object(
            pair, "_relay_lan_base_url", return_value="ws://10.0.0.42:8767"
        ), patch.object(
            pair, "build_endpoint_candidates", return_value=[]
        ), patch.object(
            pair, "build_payload", side_effect=fake_build_payload
        ):
            out = io.StringIO()
            with redirect_stdout(out):
                pair.pair_command(
                    _pair_args(dashboard_url="https://dash.example.com")
                )

        payload = captured["payload"]
        self.assertEqual(payload["host"], "10.0.0.42")
        self.assertEqual(payload["port"], 8642)
        self.assertEqual(payload["key"], "sk-cli-config")
        self.assertEqual(payload["dashboard_url"], "https://dash.example.com")
        self.assertNotEqual(payload["key"], "ABCD12")
        relay = payload["relay"]
        self.assertEqual(relay["url"], "ws://10.0.0.42:8767")
        self.assertEqual(relay["code"], "ABCD12")
        self.assertIn("Copy/paste pairing invite", out.getvalue())
        self.assertIn("hermes-relay://pair?payload=", out.getvalue())
        self.assertIn("Dashboard: https://dash.example.com", out.getvalue())


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


class MintRelayPairingTests(unittest.TestCase):
    def test_mint_posts_candidate_inputs_and_returns_exact_signed_payload(self) -> None:
        signed = json.dumps({
            "hermes": 3, "host": "10.0.0.42", "port": 8642,
            "key": "api-secret", "tls": False,
            "relay": {"url": "ws://10.0.0.42:8767", "code": "ABC123"},
            "endpoints": [{
                "role": "outbound_broker", "security": "e2ee_pinned_tls",
                "broker": {"protocol_version": 1, "token": "route-secret"},
            }],
            "sig": "server-signature",
        })
        response_body = json.dumps({
            "ok": True, "qr_payload": signed,
            "pairing_url": "hermes-relay://pair?payload=server",
        }).encode()
        captured: dict[str, object] = {}

        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *exc): return False
            def read(self): return response_body

        def urlopen(request, timeout=None):
            captured["url"] = request.full_url
            captured["body"] = json.loads(request.data.decode())
            return Response()

        with patch("plugin.pair.urllib.request.urlopen", side_effect=urlopen):
            result = pair.mint_relay_pairing(
                8767, host="10.0.0.42", port=8642,
                api_key="api-secret", tls=False, ttl_seconds=3600,
                grants={"terminal": 600}, transport_hint="ws",
                endpoints=[{"role": "lan", "priority": 0}],
                dashboard_url="https://dash.example",
            )

        self.assertEqual(captured["url"], "http://127.0.0.1:8767/pairing/mint")
        self.assertEqual(result["qr_payload"], signed)
        body = captured["body"]
        self.assertEqual(body["api_key"], "api-secret")
        self.assertEqual(body["endpoints"], [{"role": "lan", "priority": 0}])

    def test_api_disabled_mint_omits_legacy_api_request_fields(self) -> None:
        signed = json.dumps({
            "hermes": 3,
            "relay": {"url": "ws://10.0.0.42:8767", "code": "ABC123"},
            "endpoints": [{
                "role": "lan", "priority": 0,
                "relay": {"url": "ws://10.0.0.42:8767"},
            }],
        })
        response_body = json.dumps({
            "ok": True,
            "qr_payload": signed,
            "pairing_url": "hermes-relay://pair?payload=server",
        }).encode()
        captured: dict[str, object] = {}

        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *exc): return False
            def read(self): return response_body

        def urlopen(request, timeout=None):
            captured["body"] = json.loads(request.data.decode())
            return Response()

        with patch("plugin.pair.urllib.request.urlopen", side_effect=urlopen):
            result = pair.mint_relay_pairing(
                8767, host="10.0.0.42", port=8642,
                api_key="stale-key", tls=False, ttl_seconds=3600,
                grants=None, transport_hint="ws", endpoints=None,
                dashboard_url="https://dash.example", api_enabled=False,
            )

        self.assertIsNotNone(result)
        body = captured["body"]
        self.assertFalse(body["api_enabled"])
        self.assertNotIn("host", body)
        self.assertNotIn("port", body)
        self.assertNotIn("api_key", body)
        self.assertNotIn("tls", body)

    def test_pair_command_uses_server_minted_reach_invite_without_legacy_register(self) -> None:
        signed = json.dumps({
            "hermes": 3, "host": "10.0.0.42", "port": 8642,
            "key": "api-secret", "tls": False,
            "relay": {"url": "wss://reach.example/relay/ws", "code": "ABC123"},
            "endpoints": [{
                "role": "outbound_broker", "security": "e2ee_pinned_tls",
                "broker": {"protocol_version": 1, "token": "one-use-secret"},
            }],
            "sig": "server-signature",
        })
        minted = {"ok": True, "qr_payload": signed,
                  "pairing_url": "hermes-relay://pair?payload=authoritative"}
        captured: dict[str, object] = {}

        def render(*args, **kwargs):
            captured["relay"] = kwargs.get("relay")
            captured["invite"] = kwargs.get("invite_url")
            return "rendered"

        with patch.object(pair, "read_server_config", return_value={
            "host": "10.0.0.42", "port": 8642, "key": "api-secret", "tls": False,
        }), patch.object(pair, "read_relay_config", return_value={
            "host": "0.0.0.0", "port": 8767, "tls": False,
        }), patch.object(pair, "probe_relay", return_value={
            "status": "ok", "secure_link": {"broker": {"enabled": True}},
        }), patch.object(pair, "build_endpoint_candidates", return_value=[{
            "role": "lan", "priority": 0,
        }]), patch.object(pair, "mint_relay_pairing", return_value=minted), patch.object(
            pair, "register_relay_code"
        ) as legacy, patch.object(pair, "render_text_block", side_effect=render):
            with redirect_stdout(io.StringIO()):
                pair.pair_command(_pair_args())

        legacy.assert_not_called()
        self.assertEqual(captured["invite"], minted["pairing_url"])
        self.assertEqual(captured["relay"], json.loads(signed)["relay"])

    def test_reach_mint_failure_never_falls_back_to_legacy_register(self) -> None:
        with patch.object(pair, "read_server_config", return_value={
            "host": "10.0.0.42", "port": 8642, "key": "api-secret", "tls": False,
        }), patch.object(pair, "read_relay_config", return_value={
            "host": "0.0.0.0", "port": 8767, "tls": False,
        }), patch.object(pair, "probe_relay", return_value={
            "status": "ok", "secure_link": {"broker": {"enabled": True}},
        }), patch.object(pair, "build_endpoint_candidates", return_value=[]), patch.object(
            pair, "mint_relay_pairing", return_value=None
        ), patch.object(pair, "register_relay_code") as legacy:
            error = io.StringIO()
            with redirect_stdout(io.StringIO()), redirect_stderr(error):
                pair.pair_command(_pair_args())

        legacy.assert_not_called()
        self.assertIn("No downgraded relay QR", error.getvalue())


if __name__ == "__main__":
    unittest.main()
