"""Opt-in conformance against real Hermes Dashboard imports, never a live service.

Set HERMES_RELAY_TEST_UPSTREAM=1 and put a current hermes-agent checkout on
PYTHONPATH. Missing upstream dependencies then fail rather than silently skip.
"""
from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from typing import Any

from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from plugin.dashboard import plugin_api
from plugin.dashboard import test_plugin_api as transport_tests


@unittest.skipUnless(os.environ.get("HERMES_RELAY_TEST_UPSTREAM") == "1", "opt-in upstream conformance")
class UpstreamWebSocketGuardTests(unittest.TestCase):
    path = "/api/plugins/hermes-relay/transport/ws"

    def setUp(self) -> None:
        home = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(patch.dict(os.environ, {
            "HERMES_HOME": str(home),
            "HERMES_RELAY_DASHBOARD_PROXY_SECRET": "integration-test-secret",
        }))
        self.enterContext(patch.object(Path, "home", return_value=home))
        self.config_path = home / "config.yaml"
        self.config_path.write_text("plugins:\n  enabled: [hermes-relay]\n", encoding="utf-8")

        from hermes_cli import web_server
        from hermes_cli.dashboard_auth import ws_tickets

        self.host = web_server
        self.tickets = ws_tickets
        self.tickets._reset_for_tests()
        self.addCleanup(self.tickets._reset_for_tests)
        app = FastAPI()
        app.state.auth_required = True
        app.state.bound_host = "127.0.0.1"
        app.state.trusted_public_hosts = frozenset()
        self.enterContext(patch.object(self.host, "app", app))
        # Exercise the facade discovery function and real enabled/disabled config
        # readers, but do not discover or execute the operator's installed plugins.
        self.enterContext(patch.object(self.host, "_dashboard_plugins_cache", [{
            "name": "hermes-relay", "source": "user", "_dir": str(home),
        }]))
        self.assertTrue(plugin_api._dashboard_plugin_is_enabled())
        app.include_router(plugin_api.router, prefix="/api/plugins/hermes-relay")
        self.client = self.enterContext(TestClient(
            app, base_url="http://127.0.0.1", headers={"host": "127.0.0.1"},
        ))
        self.relay = transport_tests._EphemeralRelay()
        self.relay.start()
        self.addCleanup(self.relay.stop)
        self.enterContext(patch.object(plugin_api, "RELAY_PORT", self.relay.port))

    def _ticket(self) -> str:
        return self.tickets.mint_ticket(user_id="test-user", provider="test-provider")

    def _assert_denied(self, path: str, **kwargs: Any) -> None:
        # No connection to the loopback Relay is permitted on a failed guard.
        with patch.object(plugin_api.aiohttp, "ClientSession", side_effect=AssertionError("Relay reached")):
            with self.assertRaises(WebSocketDisconnect) as denied:
                with self.client.websocket_connect(path, **kwargs):
                    pass
        self.assertEqual(denied.exception.code, 1008)

    def test_real_ticket_pairing_reconnect_subprotocol_and_replay(self) -> None:
        self.assertTrue(self.relay.server.pairing.register_code("ABC123"))
        ticket = self._ticket()
        with self.client.websocket_connect(f"{self.path}?ticket={ticket}") as socket:
            socket.send_json(transport_tests.TransportWebSocketIntegrationTests._auth_payload(pairing_code="ABC123"))
            paired = socket.receive_json()
            self.assertEqual(paired["type"], "auth.ok")
            session_token = paired["payload"]["session_token"]
        self._assert_denied(f"{self.path}?ticket={ticket}")

        protocols = ["hermes-gateway-v1", f"hermes-gateway-ticket.{self._ticket()}"]
        with self.client.websocket_connect(self.path, subprotocols=protocols) as socket:
            self.assertEqual(socket.accepted_subprotocol, "hermes-gateway-v1")
            socket.send_json(transport_tests.TransportWebSocketIntegrationTests._auth_payload(session_token=session_token))
            self.assertEqual(socket.receive_json()["type"], "auth.ok")
        self._assert_denied(self.path, subprotocols=protocols)

        # Dashboard admission must not replace the inner Relay session boundary.
        with self.client.websocket_connect(f"{self.path}?ticket={self._ticket()}") as socket:
            socket.send_json(transport_tests.TransportWebSocketIntegrationTests._auth_payload(session_token="invalid"))
            self.assertEqual(socket.receive_json()["type"], "auth.fail")

    def test_real_ungated_token_keeps_loopback_peer_policy(self) -> None:
        self.host.app.state.auth_required = False
        path = f"{self.path}?token={self.host._SESSION_TOKEN}"
        with self.client.websocket_connect(path) as socket:
            socket.send_json(transport_tests.TransportWebSocketIntegrationTests._auth_payload(session_token="invalid"))
            self.assertEqual(socket.receive_json()["type"], "auth.fail")
        with TestClient(
            self.host.app, headers={"host": "127.0.0.1"}, client=("203.0.113.8", 12345),
        ) as remote_client, patch.object(
            plugin_api.aiohttp, "ClientSession", side_effect=AssertionError("Relay reached"),
        ):
            with self.assertRaises(WebSocketDisconnect) as denied:
                with remote_client.websocket_connect(path):
                    pass
            self.assertEqual(denied.exception.code, 1008)

    def test_real_policy_and_plugin_gate_reject_before_ticket_consumption(self) -> None:
        for headers in ({"host": "untrusted.example"}, {"origin": "https://untrusted.example"}):
            with self.subTest(headers=headers):
                ticket = self._ticket()
                self._assert_denied(f"{self.path}?ticket={ticket}", headers=headers)
                self.assertEqual(self.tickets.consume_ticket(ticket)["user_id"], "test-user")

        self._assert_denied(self.path)
        self._assert_denied(f"{self.path}?ticket=invalid")
        with patch.object(self.tickets.time, "time", return_value=0):
            expired = self._ticket()
        self._assert_denied(f"{self.path}?ticket={expired}")

        for config in (
            "plugins:\n  enabled: []\n",
            "plugins:\n  enabled: [hermes-relay]\n  disabled: [hermes-relay]\n",
        ):
            self.config_path.write_text(config, encoding="utf-8")
            self.assertFalse(plugin_api._dashboard_plugin_is_enabled())
            ticket = self._ticket()
            self._assert_denied(f"{self.path}?ticket={ticket}")
            self.assertEqual(self.tickets.consume_ticket(ticket)["user_id"], "test-user")
