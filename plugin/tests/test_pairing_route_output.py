"""Connection receipts for terminal, code-only and in-session pairing."""
from __future__ import annotations

import io
import json
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from plugin import pair, slash
from plugin.tests.test_register_code import _pair_args, _args


class PairingRouteOutputTests(unittest.TestCase):
    def test_all_advertised_routes_include_protocol_and_effective_port(self) -> None:
        output = pair.render_endpoint_routes([
            {"role": "lan", "dashboard": {"url": "http://192.168.1.50:9119"},
             "relay": {"url": "ws://192.168.1.50:9119/api/plugins/hermes-relay/transport"}},
            {"role": "tailscale", "dashboard": {"url": "https://host.tail-example.ts.net:10443"}},
            {"role": "public", "dashboard": {"url": "https://hermes.example.com"},
             "relay": {"url": "wss://hermes.example.com/api/plugins/hermes-relay/transport"}},
        ])
        for expected in ("Internal / LAN", "Remote / Tailscale", "External / public",
                         "HTTP, port 9119", "WS, port 9119", "HTTPS, port 10443", "WSS, port 443"):
            self.assertIn(expected, output)
        self.assertIn("hermes pair --legacy-direct-relay", output)

    def test_ipv6_and_direct_relay_are_not_treated_as_dashboard_ingress(self) -> None:
        output = pair.render_endpoint_routes([
            {"role": "lan", "relay": {"url": "ws://[fd00::5]:8767"},
             "api": {"host": "fd00::5", "port": 8642, "tls": False}},
        ])
        self.assertIn("ws://[fd00::5]:8767 (WS, port 8767)", output)
        self.assertIn("http://[fd00::5]:8642", output)
        self.assertIn("External route: not configured", output)
        self.assertNotIn("cannot authenticate", output)

    def test_receipt_omits_credentials_and_preserves_pinned_route_instructions(self) -> None:
        output = pair.render_endpoint_routes([
            {"role": "outbound_broker", "proxy": {
                "url": "https://secure.example:9443", "surfaces": ["relay", "dashboard"],
                "cert_der": "private-cert", "pin_sha256": "private-pin",
             }, "broker": {"url": "wss://broker.example/connect", "token": "broker-secret"}},
            {"role": "lan", "relay": {"url": "ws://user:secret@example:8767"},
             "dashboard": {"url": "https://example?token=secret"}, "api_key": "api-secret"},
        ])
        self.assertIn("wss://broker.example/connect", output)
        self.assertIn("wss://secure.example:9443/relay/ws", output)
        self.assertIn("Import the signed invite", output)
        for secret in ("broker-secret", "private-cert", "private-pin", "api-secret", "user:secret", "token=secret"):
            self.assertNotIn(secret, output)

    def test_host_receipt_omits_disabled_api(self) -> None:
        with patch.object(pair, "read_server_config", return_value={
            "host": "192.168.1.50", "port": 8642, "tls": False, "enabled": False,
        }), patch.object(pair, "read_relay_config", return_value={
            "host": "192.168.1.50", "port": 8767, "tls": False,
        }), patch.object(pair, "build_endpoint_candidates", return_value=[{
            "role": "lan", "api": {"host": "192.168.1.50", "port": 8642},
            "relay": {"url": "ws://192.168.1.50:8767"},
        }]):
            output = pair.render_host_connection_routes()
        self.assertNotIn("8642", output)
        self.assertIn("8767", output)

    def test_pair_output_uses_authoritative_minted_endpoints_and_disabled_api(self) -> None:
        authoritative = {"hermes": 3, "relay": {"url": "wss://secure.example:9443/relay/ws", "code": "ABC123"},
                         "endpoints": [{"role": "public", "relay": {"url": "wss://secure.example:9443/relay/ws"}}]}
        captured = {}
        def capture(*args, **kwargs):
            captured.update(kwargs)
            return "receipt"
        with patch.object(pair, "read_server_config", return_value={
            "host": "192.168.1.50", "port": 8642, "key": "", "tls": False, "enabled": False,
        }), patch.object(pair, "read_relay_config", return_value={
            "host": "192.168.1.50", "port": 8767, "tls": False,
        }), patch.object(pair, "probe_relay", return_value={"status": "ok"}), patch.object(
            pair, "build_endpoint_candidates", return_value=[]
        ), patch.object(pair, "mint_relay_pairing", return_value={
            "qr_payload": json.dumps(authoritative), "pairing_url": "hermes-relay://pair?payload=test",
        }), patch.object(pair, "render_text_block", side_effect=capture), redirect_stdout(io.StringIO()):
            pair.pair_command(_pair_args())
        self.assertFalse(captured["api_enabled"])
        self.assertEqual(captured["endpoints"], authoritative["endpoints"])

    def test_code_only_surfaces_show_addresses_and_host_only_admin_label(self) -> None:
        receipt = "Internal / LAN: ws://192.168.1.50:8767 (WS, port 8767)"
        with patch.object(pair, "render_host_connection_routes", return_value=receipt), patch.object(
            pair, "register_relay_code", return_value=True
        ), patch.object(pair, "probe_relay", return_value={"status": "ok"}), patch.object(
            pair, "read_relay_config", return_value={"host": "0.0.0.0", "port": 8767, "tls": False}
        ):
            result = slash.relay_slash_handler("pair")
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(pair.register_code_command(_args(register_code="ABC123")), 0)
        self.assertIn(receipt, result)
        self.assertIn(receipt, output.getvalue())
        self.assertIn("Host-only pairing API: http://127.0.0.1:8767", output.getvalue())


if __name__ == "__main__":
    unittest.main()
