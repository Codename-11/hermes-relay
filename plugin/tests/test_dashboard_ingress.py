"""Trusted loopback metadata tests for Dashboard-hosted Relay ingress."""

from __future__ import annotations

import unittest
from unittest.mock import patch

from plugin.relay import server as relay_server


class _Request(dict):
    def __init__(self, remote: str, headers: dict[str, str]) -> None:
        super().__init__()
        self.remote = remote
        self.headers = headers


class DashboardIngressMetadataTests(unittest.TestCase):
    def test_valid_loopback_marker_preserves_normalized_peer_and_wss(self) -> None:
        request = _Request(
            "127.0.0.1",
            {
                relay_server._DASHBOARD_PROXY_SECRET_HEADER: "expected",
                relay_server._PROXY_PEER_HEADER: "2001:0db8::1",
                relay_server._PROXY_PROTO_HEADER: "wss",
            },
        )
        with patch.object(relay_server, "_dashboard_proxy_secret", return_value="expected"):
            metadata = relay_server._trusted_dashboard_ingress(request, object())
        self.assertEqual(metadata, ("2001:db8::1", "wss"))

    def test_non_loopback_peer_cannot_assert_dashboard_metadata(self) -> None:
        request = _Request(
            "203.0.113.5",
            {
                relay_server._DASHBOARD_PROXY_SECRET_HEADER: "expected",
                relay_server._PROXY_PEER_HEADER: "198.51.100.4",
                relay_server._PROXY_PROTO_HEADER: "wss",
            },
        )
        with patch.object(relay_server, "_dashboard_proxy_secret", return_value="expected"):
            self.assertIsNone(relay_server._trusted_dashboard_ingress(request, object()))

    def test_bad_marker_fails_closed(self) -> None:
        request = _Request(
            "::1",
            {
                relay_server._DASHBOARD_PROXY_SECRET_HEADER: "wrong",
                relay_server._PROXY_PEER_HEADER: "198.51.100.4",
                relay_server._PROXY_PROTO_HEADER: "wss",
            },
        )
        with patch.object(relay_server, "_dashboard_proxy_secret", return_value="expected"):
            self.assertIsNone(relay_server._trusted_dashboard_ingress(request, object()))

    def test_invalid_peer_and_protocol_do_not_become_rate_limit_or_transport_authority(self) -> None:
        request = _Request(
            "127.0.0.1",
            {
                relay_server._DASHBOARD_PROXY_SECRET_HEADER: "expected",
                relay_server._PROXY_PEER_HEADER: "attacker-controlled-bucket",
                relay_server._PROXY_PROTO_HEADER: "file",
            },
        )
        with patch.object(relay_server, "_dashboard_proxy_secret", return_value="expected"):
            metadata = relay_server._trusted_dashboard_ingress(request, object())
        self.assertEqual(metadata, ("127.0.0.1", "unknown"))

        request["hermes_dashboard_outer_proto"] = metadata[1]
        self.assertEqual(relay_server._detect_transport_hint(request), "unknown")


if __name__ == "__main__":
    unittest.main()
