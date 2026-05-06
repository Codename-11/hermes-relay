"""Tests for the ``GET /desktop/health`` loopback endpoint.

Validated surface:
  * No client connected → 200 (not 503!) with ``connected: false`` and the
    advertised_tools list empty. ``desktop_health`` must remain callable
    when the client is down — that's its whole purpose.
  * After a ``desktop.status`` envelope with the enriched fields
    (host/platform/version/uptime_ms/last_error), the response surfaces
    them all at top level.
  * Non-loopback peer → 403 (matches every other ``/desktop/*`` route).

Run with::

    python -m unittest plugin.tests.test_desktop_health
"""

from __future__ import annotations

import asyncio
import unittest
from typing import Any

from plugin.relay.channels.desktop import DesktopHandler
from plugin.relay.server import handle_desktop_health, handle_desktop_ping


def _run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


class _FakeWs:
    def __init__(self) -> None:
        self.closed: bool = False

    async def send_str(self, payload: str) -> None:  # pragma: no cover
        pass


class _FakeRequest:
    def __init__(self, remote: str, server: Any) -> None:
        self.remote = remote
        self.app = {"server": server}
        # /desktop/health uses GET with no params.
        self.query: dict[str, str] = {}


class _FakeServer:
    def __init__(self, desktop: DesktopHandler) -> None:
        self.desktop = desktop


SAMPLE_STATUS_PAYLOAD: dict[str, Any] = {
    "advertised_tools": [
        "desktop_read_file",
        "desktop_terminal",
        "desktop_powershell",
        "desktop_job_start",
        "desktop_health",
        "desktop_computer_status",
    ],
    "host": "TEST-HOST",
    "platform": "win32",
    "arch": "x64",
    "node": "v22.0.0",
    "version": "0.3.0-alpha.14",
    "pid": 12345,
    "started_at_ms": 1_700_000_000_000,
    "uptime_ms": 60_000,
    "interactive": False,
    "last_error": {"message": "boom", "tool": "desktop_terminal", "ts": 1_700_000_059_000},
    "computer_use": {
        "stage": "experimental",
        "protocol_version": 2,
        "enabled": True,
        "input": "blocked_headless",
    },
}


def _resp_body(resp) -> dict[str, Any]:
    """aiohttp.web.json_response stashes the encoded JSON in ._body —
    we decode it back so tests can assert on individual fields without
    mocking aiohttp's request lifecycle."""
    import json

    body = resp.body
    if isinstance(body, (bytes, bytearray)):
        return json.loads(body.decode("utf-8"))
    return json.loads(body)


class DesktopHealthEndpointTests(unittest.TestCase):
    def test_no_client_returns_200_with_connected_false(self) -> None:
        """desktop_health must answer even when no client is connected.

        The whole point of the tool is to *report* whether one is
        connected — gating it on connection would be circular.
        """

        async def run() -> None:
            h = DesktopHandler()
            server = _FakeServer(h)
            req = _FakeRequest(remote="127.0.0.1", server=server)

            resp = await handle_desktop_health(req)
            body = _resp_body(resp)
            self.assertEqual(resp.status, 200)
            self.assertFalse(body["connected"])
            self.assertEqual(body["advertised_tools"], [])
            self.assertEqual(body["pending_commands"], 0)
            self.assertIsNone(body.get("host"))

        _run(run())

    def test_status_payload_is_surfaced_at_top_level(self) -> None:
        async def run() -> None:
            h = DesktopHandler()
            ws = _FakeWs()
            envelope = {
                "channel": "desktop",
                "type": "desktop.status",
                "id": "evt1",
                "payload": SAMPLE_STATUS_PAYLOAD,
            }
            await h.handle(ws, envelope)
            # Latch ws as the connected client (status envelope already does
            # this via _latch_client_ws).
            self.assertTrue(h.is_client_connected())

            server = _FakeServer(h)
            req = _FakeRequest(remote="127.0.0.1", server=server)

            resp = await handle_desktop_health(req)
            body = _resp_body(resp)

            self.assertEqual(resp.status, 200)
            self.assertTrue(body["connected"])
            self.assertEqual(body["host"], "TEST-HOST")
            self.assertEqual(body["platform"], "win32")
            self.assertEqual(body["version"], "0.3.0-alpha.14")
            self.assertEqual(body["uptime_ms"], 60_000)
            self.assertEqual(body["pid"], 12345)
            self.assertIn("desktop_terminal", body["advertised_tools"])
            self.assertIn("desktop_health", body["advertised_tools"])
            self.assertIsNotNone(body["last_error"])
            self.assertEqual(body["last_error"]["tool"], "desktop_terminal")
            self.assertEqual(body["computer_use"]["protocol_version"], 2)

        _run(run())

    def test_computer_use_ping_requires_explicit_advertisement(self) -> None:
        async def run() -> None:
            h = DesktopHandler()
            ws = _FakeWs()
            await h.handle(
                ws,
                {
                    "channel": "desktop",
                    "type": "desktop.status",
                    "id": "evt1",
                    "payload": {"advertised_tools": []},
                },
            )
            server = _FakeServer(h)
            req = _FakeRequest(remote="127.0.0.1", server=server)

            req.query = {"tool": "desktop_terminal"}
            normal_resp = await handle_desktop_ping(req)
            self.assertEqual(normal_resp.status, 200)

            req.query = {"tool": "desktop_computer_status"}
            computer_resp = await handle_desktop_ping(req)
            self.assertEqual(computer_resp.status, 503)

            await h.handle(
                ws,
                {
                    "channel": "desktop",
                    "type": "desktop.status",
                    "id": "evt2",
                    "payload": {"advertised_tools": ["desktop_computer_status"]},
                },
            )
            computer_resp = await handle_desktop_ping(req)
            self.assertEqual(computer_resp.status, 200)

        _run(run())

    def test_non_loopback_returns_forbidden(self) -> None:
        async def run() -> None:
            h = DesktopHandler()
            server = _FakeServer(h)
            req = _FakeRequest(remote="10.0.0.42", server=server)

            from aiohttp import web

            with self.assertRaises(web.HTTPForbidden):
                await handle_desktop_health(req)

        _run(run())


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
