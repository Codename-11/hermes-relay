"""Tests for multi-device Android bridge routing.

Covers the Phase 1–3 server-side routing work:
  * multiple bridge clients are tracked by device_id
  * device aliases resolve targeted commands/status
  * wrong-device responses cannot satisfy another device's pending request
  * HTTP dispatch strips relay-only routing selectors before forwarding

Run with::

    python -m unittest plugin.tests.test_bridge_multidevice
"""

from __future__ import annotations

import asyncio
import json
import math
import unittest
from dataclasses import dataclass, field
from typing import Any

from plugin.relay.channels.bridge import BridgeHandler
from plugin.relay.server import (
    handle_bridge_devices,
    handle_bridge_select_active,
    handle_bridge_tap,
)


class _FakeWs:
    def __init__(self) -> None:
        self.sent: list[dict[str, Any]] = []
        self.closed = False

    async def send_str(self, payload: str) -> None:
        self.sent.append(json.loads(payload))


@dataclass
class _FakeSession:
    token: str
    device_name: str
    device_id: str
    client_surface: str = "android"
    device_form_factor: str = "unknown"
    grants: dict[str, float] = field(default_factory=lambda: {"bridge": math.inf})

    def channel_is_expired(self, channel: str) -> bool:
        return False


class _FakeServer:
    def __init__(self, bridge: BridgeHandler) -> None:
        self.bridge = bridge


class _FakeRequest:
    def __init__(
        self,
        bridge: BridgeHandler,
        *,
        method: str = "GET",
        query: dict[str, str] | None = None,
        body: dict[str, Any] | None = None,
        remote: str = "127.0.0.1",
    ) -> None:
        self.app = {"server": _FakeServer(bridge)}
        self.method = method
        self.query = query or {}
        self._body = body
        self.remote = remote

    @property
    def body_exists(self) -> bool:
        return self._body is not None

    async def json(self) -> dict[str, Any]:
        return dict(self._body or {})


def _run(coro):
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coro)
    finally:
        loop.close()


def _status_envelope(name: str, package: str) -> dict[str, Any]:
    return {
        "channel": "bridge",
        "type": "bridge.status",
        "id": f"status-{name}",
        "payload": {
            "device": {
                "name": name,
                "battery_percent": 80,
                "screen_on": True,
                "current_app": package,
            },
            "bridge": {
                "master_enabled": True,
                "accessibility_granted": True,
                "screen_capture_granted": True,
                "overlay_granted": True,
                "notification_listener_granted": True,
            },
            "safety": {"blocklist_count": 0, "destructive_verbs_count": 0},
            "current_app": package,
        },
    }


async def _register_two_devices() -> tuple[BridgeHandler, _FakeWs, _FakeWs]:
    h = BridgeHandler()
    phone_ws = _FakeWs()
    boox_ws = _FakeWs()
    await h.handle(
        phone_ws,  # type: ignore[arg-type]
        _status_envelope("Pixel 9 Pro Fold", "com.android.chrome"),
        session=_FakeSession(
            token="phone-token-123",
            device_name="Pixel 9 Pro Fold",
            device_id="pixel-fold-1",
            device_form_factor="phone",
        ),
    )
    await h.handle(
        boox_ws,  # type: ignore[arg-type]
        _status_envelope("NoteMax", "com.onyx.kreader"),
        session=_FakeSession(
            token="boox-token-456",
            device_name="NoteMax",
            device_id="boox-notemax-1",
            device_form_factor="tablet",
        ),
    )
    return h, phone_ws, boox_ws


class BridgeMultiDeviceRegistryTests(unittest.TestCase):
    def test_devices_payload_lists_both_devices_with_aliases(self) -> None:
        async def run() -> None:
            h, _phone_ws, _boox_ws = await _register_two_devices()
            payload = h.devices_payload()
            self.assertEqual(payload["count"], 2)
            by_id = {d["device_id"]: d for d in payload["devices"]}
            self.assertIn("phone", by_id["pixel-fold-1"]["aliases"])
            self.assertIn("fold", by_id["pixel-fold-1"]["aliases"])
            self.assertIn("boox", by_id["boox-notemax-1"]["aliases"])
            self.assertIn("notemax", by_id["boox-notemax-1"]["aliases"])

        _run(run())

    def test_targeted_status_by_alias_returns_requested_device(self) -> None:
        async def run() -> None:
            h, _phone_ws, _boox_ws = await _register_two_devices()
            status = h.status_payload("boox")
            self.assertEqual(status["device_id"], "boox-notemax-1")
            self.assertEqual(status["device"]["name"], "NoteMax")
            self.assertEqual(status["current_app"], "com.onyx.kreader")

        _run(run())

    def test_targeted_command_routes_only_to_matching_device(self) -> None:
        async def run() -> None:
            h, phone_ws, boox_ws = await _register_two_devices()
            task = asyncio.create_task(h.handle_command("GET", "/screen", device="boox"))
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            self.assertEqual(len(phone_ws.sent), 0)
            self.assertEqual(len(boox_ws.sent), 1)
            sent = boox_ws.sent[0]
            request_id = sent["payload"]["request_id"]
            self.assertEqual(sent["payload"]["path"], "/screen")

            await h.handle(
                boox_ws,  # type: ignore[arg-type]
                {
                    "channel": "bridge",
                    "type": "bridge.response",
                    "id": request_id,
                    "payload": {"request_id": request_id, "status": 200, "result": {"ok": True}},
                },
            )
            result = await asyncio.wait_for(task, timeout=1.0)
            self.assertEqual(result["status"], 200)
            self.assertEqual(h.pending, {})

        _run(run())

    def test_wrong_device_response_is_ignored_until_target_replies(self) -> None:
        async def run() -> None:
            h, phone_ws, boox_ws = await _register_two_devices()
            task = asyncio.create_task(h.handle_command("GET", "/screen", device="boox"))
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            request_id = boox_ws.sent[0]["payload"]["request_id"]
            wrong_response = {
                "channel": "bridge",
                "type": "bridge.response",
                "id": request_id,
                "payload": {"request_id": request_id, "status": 200, "result": {"wrong": True}},
            }
            await h.handle(phone_ws, wrong_response)  # type: ignore[arg-type]
            self.assertFalse(task.done())
            self.assertIn(request_id, h.pending)

            right_response = {
                "channel": "bridge",
                "type": "bridge.response",
                "id": request_id,
                "payload": {"request_id": request_id, "status": 200, "result": {"right": True}},
            }
            await h.handle(boox_ws, right_response)  # type: ignore[arg-type]
            result = await asyncio.wait_for(task, timeout=1.0)
            self.assertEqual(result["result"], {"right": True})

        _run(run())

    def test_detach_one_device_preserves_other_pending_command(self) -> None:
        async def run() -> None:
            h, phone_ws, boox_ws = await _register_two_devices()
            phone_task = asyncio.create_task(h.handle_command("GET", "/ping", device="phone"))
            boox_task = asyncio.create_task(h.handle_command("GET", "/screen", device="boox"))
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            boox_ws.closed = True
            await h.detach_ws(boox_ws, reason="unit test")  # type: ignore[arg-type]
            self.assertTrue(phone_task.done() is False)
            with self.assertRaises(ConnectionError):
                await asyncio.wait_for(boox_task, timeout=1.0)

            request_id = phone_ws.sent[0]["payload"]["request_id"]
            await h.handle(
                phone_ws,  # type: ignore[arg-type]
                {
                    "channel": "bridge",
                    "type": "bridge.response",
                    "id": request_id,
                    "payload": {"request_id": request_id, "status": 200, "result": {"pong": True}},
                },
            )
            result = await asyncio.wait_for(phone_task, timeout=1.0)
            self.assertEqual(result["result"], {"pong": True})

        _run(run())


class BridgeMultiDeviceRouteTests(unittest.TestCase):
    def _read_json(self, response: Any) -> dict[str, Any]:
        body = response.body
        if isinstance(body, (bytes, bytearray)):
            return json.loads(body.decode("utf-8"))
        return json.loads(str(body))

    def test_bridge_devices_route_returns_registry(self) -> None:
        async def run() -> None:
            h, _phone_ws, _boox_ws = await _register_two_devices()
            response = await handle_bridge_devices(_FakeRequest(h))  # type: ignore[arg-type]
            self.assertEqual(response.status, 200)
            body = self._read_json(response)
            self.assertEqual(body["count"], 2)

        _run(run())

    def test_select_active_route_changes_default_target(self) -> None:
        async def run() -> None:
            h, _phone_ws, _boox_ws = await _register_two_devices()
            response = await handle_bridge_select_active(  # type: ignore[arg-type]
                _FakeRequest(h, method="POST", body={"device": "phone"})
            )
            self.assertEqual(response.status, 200)
            body = self._read_json(response)
            self.assertEqual(body["active_device_id"], "pixel-fold-1")
            self.assertEqual(h.active_device_id, "pixel-fold-1")

        _run(run())

    def test_dispatch_strips_device_selector_before_forwarding(self) -> None:
        async def run() -> None:
            h, phone_ws, boox_ws = await _register_two_devices()
            request = _FakeRequest(
                h,
                method="POST",
                body={"device": "boox", "x": 10, "y": 20},
            )
            task = asyncio.create_task(handle_bridge_tap(request))  # type: ignore[arg-type]
            await asyncio.sleep(0)
            await asyncio.sleep(0)

            self.assertEqual(len(phone_ws.sent), 0)
            self.assertEqual(len(boox_ws.sent), 1)
            payload = boox_ws.sent[0]["payload"]
            self.assertEqual(payload["body"], {"x": 10, "y": 20})
            self.assertNotIn("device", payload["body"])

            request_id = payload["request_id"]
            await h.handle(
                boox_ws,  # type: ignore[arg-type]
                {
                    "channel": "bridge",
                    "type": "bridge.response",
                    "id": request_id,
                    "payload": {"request_id": request_id, "status": 200, "result": {"tapped": True}},
                },
            )
            response = await asyncio.wait_for(task, timeout=1.0)
            self.assertEqual(response.status, 200)
            body = self._read_json(response)
            self.assertEqual(body, {"tapped": True})

        _run(run())


if __name__ == "__main__":
    unittest.main()
