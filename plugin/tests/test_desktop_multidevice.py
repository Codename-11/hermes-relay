"""Targeted multi-PC routing for the desktop RPC channel."""

from __future__ import annotations

import asyncio
import json
import unittest
from dataclasses import dataclass
from typing import Any
from unittest.mock import AsyncMock, Mock, patch

from plugin.relay.channels.desktop import DesktopError, DesktopHandler
from plugin.relay.server import handle_desktop_dispatch
from plugin.tools import desktop_tool


class _FakeWs:
    def __init__(self) -> None:
        self.sent: list[dict[str, Any]] = []
        self.closed = False

    async def send_str(self, payload: str) -> None:
        self.sent.append(json.loads(payload))


@dataclass
class _FakeSession:
    device_id: str
    device_name: str


def _status(host: str) -> dict[str, Any]:
    return {
        "channel": "desktop",
        "type": "desktop.status",
        "payload": {
            "host": host,
            "advertised_tools": ["desktop_powershell", "desktop_read_file"],
        },
    }


async def _register_two() -> tuple[DesktopHandler, _FakeWs, _FakeWs]:
    handler = DesktopHandler()
    office = _FakeWs()
    laptop = _FakeWs()
    await handler.handle(
        office,  # type: ignore[arg-type]
        _status("AXIOM-DESKTOP"),
        session=_FakeSession("desktop-1", "AXIOM-DESKTOP"),
    )
    await handler.handle(
        laptop,  # type: ignore[arg-type]
        _status("Axiom-Latitude"),
        session=_FakeSession("desktop-2", "Axiom-Latitude"),
    )
    return handler, office, laptop


class DesktopMultiDeviceTests(unittest.IsolatedAsyncioTestCase):
    async def test_tool_schema_exposes_script_and_device_selector(self) -> None:
        parameters = desktop_tool._SCHEMAS["desktop_powershell"]["parameters"]
        self.assertEqual(parameters["required"], ["script"])
        self.assertIn("script", parameters["properties"])
        self.assertIn("device", parameters["properties"])
        self.assertEqual(
            desktop_tool._SCHEMAS["desktop_powershell"]["x-hermes-capability"],
            "system.execute",
        )

    async def test_adb_schemas_are_serial_bound_and_capability_labeled(self) -> None:
        names = {
            "desktop_adb_devices",
            "desktop_adb_shell",
            "desktop_adb_push",
            "desktop_adb_pull",
            "desktop_adb_install",
            "desktop_adb_logcat",
        }
        for name in names:
            schema = desktop_tool._SCHEMAS[name]
            self.assertEqual(schema["x-hermes-capability"], "devices.usb")
            self.assertIn("device", schema["parameters"]["properties"])
        self.assertEqual(
            desktop_tool._SCHEMAS["desktop_adb_shell"]["parameters"]["required"],
            ["serial", "command"],
        )

    async def test_raw_usb_schemas_are_host_gated_and_direct_spawn(self) -> None:
        for name in {"desktop_usb_devices", "desktop_usb_run"}:
            schema = desktop_tool._SCHEMAS[name]
            self.assertEqual(schema["x-hermes-capability"], "devices.usb")
            self.assertIn("device", schema["parameters"]["properties"])
        run = desktop_tool._SCHEMAS["desktop_usb_run"]["parameters"]
        self.assertEqual(run["required"], ["executable"])
        self.assertEqual(run["properties"]["arguments"]["type"], "array")

        response = Mock(status_code=200)
        response.json.return_value = {"ok": True, "result": {"exit_code": 0}}
        with patch.object(desktop_tool.requests, "post", return_value=response) as post:
            desktop_tool.desktop_usb_run("fastboot", ["devices"], timeout=40)
        self.assertEqual(post.call_args.kwargs["json"]["executable"], "fastboot")
        self.assertEqual(post.call_args.kwargs["json"]["arguments"], ["devices"])

    async def test_adb_http_timeout_covers_approval_and_operation(self) -> None:
        response = Mock(status_code=200)
        response.json.return_value = {"ok": True, "result": {"exit_code": 0}}
        with patch.object(desktop_tool.requests, "post", return_value=response) as post:
            desktop_tool.desktop_adb_install("serial-1", "app.apk", timeout=120)
        self.assertGreaterEqual(post.call_args.kwargs["timeout"], 250)

    async def test_tool_dispatch_forwards_device_as_relay_only_selector(self) -> None:
        response = Mock(status_code=200)
        response.json.return_value = {"ok": True, "result": {"stdout": "ok"}}
        with patch.object(desktop_tool.requests, "post", return_value=response) as post:
            desktop_tool._HANDLERS["desktop_powershell"](
                {"script": "'ok'", "device": "desktop-1"}
            )
        self.assertEqual(post.call_args.kwargs["json"]["device"], "desktop-1")
        self.assertEqual(post.call_args.kwargs["json"]["script"], "'ok'")

    async def test_http_dispatch_strips_selector_before_client_forwarding(self) -> None:
        desktop = Mock()
        desktop.handle_command = AsyncMock(return_value={"ok": True, "result": {}})
        request = Mock(
            remote="127.0.0.1",
            app={"server": Mock(desktop=desktop)},
            match_info={"tool_name": "desktop_powershell"},
        )
        request.json = AsyncMock(
            return_value={"script": "'ok'", "device": "desktop-1"}
        )

        response = await handle_desktop_dispatch(request)

        self.assertEqual(response.status, 200)
        desktop.handle_command.assert_awaited_once_with(
            "desktop_powershell",
            {"script": "'ok'"},
            device="desktop-1",
        )

    async def test_untargeted_command_fails_closed_with_multiple_pcs(self) -> None:
        handler, _office, _laptop = await _register_two()
        with self.assertRaisesRegex(DesktopError, "pass device or device_id explicitly"):
            await handler.handle_command("desktop_powershell", {"script": "pwd"})

    async def test_name_target_routes_only_to_the_selected_pc(self) -> None:
        handler, office, laptop = await _register_two()
        task = asyncio.create_task(
            handler.handle_command(
                "desktop_powershell",
                {"script": "pwd"},
                device="axiom desktop",
            )
        )
        await asyncio.sleep(0)
        self.assertEqual(len(office.sent), 1)
        self.assertEqual(laptop.sent, [])

        request_id = office.sent[0]["payload"]["request_id"]
        await handler.handle(
            office,  # type: ignore[arg-type]
            {
                "channel": "desktop",
                "type": "desktop.response",
                "payload": {
                    "request_id": request_id,
                    "ok": True,
                    "result": {"stdout": "office", "exit_code": 0},
                },
            },
        )
        result = await asyncio.wait_for(task, timeout=1)
        self.assertEqual(result["result"]["stdout"], "office")

    async def test_wrong_pc_cannot_satisfy_targeted_pending_request(self) -> None:
        handler, office, laptop = await _register_two()
        task = asyncio.create_task(
            handler.handle_command(
                "desktop_read_file",
                {"path": "C:/marker.txt"},
                device="desktop-2",
            )
        )
        await asyncio.sleep(0)
        request_id = laptop.sent[0]["payload"]["request_id"]
        response = {
            "channel": "desktop",
            "type": "desktop.response",
            "payload": {"request_id": request_id, "ok": True, "result": {"content": "x"}},
        }
        await handler.handle(office, response)  # type: ignore[arg-type]
        self.assertFalse(task.done())
        await handler.handle(laptop, response)  # type: ignore[arg-type]
        result = await asyncio.wait_for(task, timeout=1)
        self.assertEqual(result["result"]["content"], "x")

    async def test_health_snapshot_lists_connected_desktops(self) -> None:
        handler, _office, _laptop = await _register_two()
        clients = handler.status_snapshot()["clients"]
        self.assertEqual({client["device_id"] for client in clients}, {"desktop-1", "desktop-2"})
