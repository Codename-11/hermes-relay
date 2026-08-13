"""Targeted multi-PC routing for the desktop RPC channel."""

from __future__ import annotations

import asyncio
import json
import unittest
from dataclasses import dataclass
from typing import Any
from unittest.mock import AsyncMock, Mock, patch

from plugin.relay.channels.desktop import (
    DesktopError,
    DesktopHandler,
    DesktopRequesterContext,
)
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
            "advertised_tools": [
                "desktop_powershell",
                "desktop_read_file",
                "desktop_computer_snapshot",
            ],
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

    async def test_tool_dispatch_uses_executor_context_and_drops_model_identity(self) -> None:
        response = Mock(status_code=200)
        response.json.return_value = {"ok": True, "result": {}}
        with patch.object(desktop_tool.requests, "post", return_value=response) as post:
            desktop_tool._HANDLERS["desktop_computer_action"](
                {
                    "action": "click",
                    "x": 10,
                    "y": 20,
                    "control_session": {"id": "model-forged"},
                    "control_context": {"run_id": "model-forged"},
                },
                session_id="chat-authenticated",
                task_id="run-authenticated",
                profile="default",
            )
        body = post.call_args.kwargs["json"]
        headers = post.call_args.kwargs["headers"]
        self.assertNotIn("control_session", body)
        self.assertNotIn("control_context", body)
        self.assertEqual(headers["X-Hermes-Relay-Chat-Session"], "chat-authenticated")
        self.assertEqual(headers["X-Hermes-Relay-Run-Id"], "run-authenticated")
        self.assertEqual(headers["X-Hermes-Relay-Profile"], "default")

    async def test_http_dispatch_strips_selector_before_client_forwarding(self) -> None:
        desktop = Mock()
        desktop.handle_command = AsyncMock(return_value={"ok": True, "result": {}})
        request = Mock(
            remote="127.0.0.1",
            app={"server": Mock(desktop=desktop)},
            match_info={"tool_name": "desktop_powershell"},
            headers={},
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
            requester=DesktopRequesterContext(),
        )

    async def test_http_dispatch_binds_loopback_executor_context(self) -> None:
        desktop = Mock()
        desktop.handle_command = AsyncMock(return_value={"ok": True, "result": {}})
        request = Mock(
            remote="127.0.0.1",
            app={"server": Mock(desktop=desktop)},
            match_info={"tool_name": "desktop_computer_snapshot"},
            headers={
                "X-Hermes-Relay-Chat-Session": "chat-1",
                "X-Hermes-Relay-Run-Id": "run-1",
                "X-Hermes-Relay-Profile": "default",
            },
        )
        request.json = AsyncMock(return_value={"device": "desktop-1"})

        response = await handle_desktop_dispatch(request)

        self.assertEqual(response.status, 200)
        desktop.handle_command.assert_awaited_once_with(
            "desktop_computer_snapshot",
            {},
            device="desktop-1",
            requester=DesktopRequesterContext(
                requester_device_id="hermes-agent:chat-1",
                chat_session_id="chat-1",
                run_id="run-1",
                profile="default",
            ),
        )

    async def test_http_dispatch_prefers_authenticated_paired_requester(self) -> None:
        desktop = Mock()
        desktop.handle_command = AsyncMock(return_value={"ok": True, "result": {}})
        server = Mock(desktop=desktop)
        request = Mock(
            remote="127.0.0.1",
            app={"server": server},
            match_info={"tool_name": "desktop_computer_snapshot"},
            headers={
                "Authorization": "Bearer authenticated-token",
                "X-Hermes-Relay-Chat-Session": "chat-1",
                "X-Hermes-Relay-Run-Id": "run-1",
            },
        )
        request.json = AsyncMock(return_value={"device": "desktop-1"})
        paired_session = Mock(device_id="paired-phone-1")

        with patch(
            "plugin.relay.server._require_bearer_session",
            return_value=(server, paired_session),
        ):
            response = await handle_desktop_dispatch(request)

        self.assertEqual(response.status, 200)
        requester = desktop.handle_command.await_args.kwargs["requester"]
        self.assertEqual(requester.requester_device_id, "paired-phone-1")
        self.assertEqual(requester.chat_session_id, "chat-1")
        self.assertEqual(requester.run_id, "run-1")

    async def test_computer_control_session_is_server_owned_stable_and_request_bound(self) -> None:
        handler, office, _laptop = await _register_two()
        requester = DesktopRequesterContext(
            requester_device_id="paired-agent-1",
            chat_session_id="chat-1",
            run_id="run-1",
            profile="default",
        )

        async def dispatch_once() -> dict[str, Any]:
            task = asyncio.create_task(
                handler.handle_command(
                    "desktop_computer_snapshot",
                    {},
                    device="desktop-1",
                    requester=requester,
                )
            )
            await asyncio.sleep(0)
            payload = office.sent[-1]["payload"]
            await handler.handle(
                office,  # type: ignore[arg-type]
                {
                    "channel": "desktop",
                    "type": "desktop.response",
                    "payload": {"request_id": payload["request_id"], "ok": True, "result": {}},
                },
            )
            await asyncio.wait_for(task, timeout=1)
            return payload

        first = await dispatch_once()
        second = await dispatch_once()
        first_control = first["control_session"]
        second_control = second["control_session"]
        self.assertEqual(first_control["version"], 1)
        self.assertRegex(first_control["id"], r"^control-[0-9a-f-]+$")
        self.assertEqual(first_control["id"], second_control["id"])
        self.assertNotEqual(first_control["request_id"], second_control["request_id"])
        self.assertEqual(first_control["request_id"], first["request_id"])
        self.assertEqual(first_control["requester_device_id"], "paired-agent-1")
        self.assertEqual(first_control["target_device_id"], "desktop-1")
        self.assertEqual(first_control["chat_session_id"], "chat-1")
        self.assertEqual(first_control["run_id"], "run-1")
        self.assertEqual(len(handler._control_sessions), 1)
        await handler.detach_ws(office, "test disconnect")  # type: ignore[arg-type]
        self.assertEqual(handler._control_sessions, {})

    async def test_computer_command_omits_identity_without_authoritative_run(self) -> None:
        handler, office, _laptop = await _register_two()
        task = asyncio.create_task(
            handler.handle_command(
                "desktop_computer_snapshot",
                {},
                device="desktop-1",
                requester=DesktopRequesterContext(requester_device_id="paired-agent-1"),
            )
        )
        await asyncio.sleep(0)
        payload = office.sent[-1]["payload"]
        self.assertNotIn("control_session", payload)
        await handler.handle(
            office,  # type: ignore[arg-type]
            {
                "channel": "desktop",
                "type": "desktop.response",
                "payload": {"request_id": payload["request_id"], "ok": True, "result": {}},
            },
        )
        await asyncio.wait_for(task, timeout=1)

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

    async def test_two_pcs_can_execute_concurrently_without_cross_talk(self) -> None:
        handler, office, laptop = await _register_two()
        office_task = asyncio.create_task(
            handler.handle_command(
                "desktop_powershell",
                {"script": "'office'"},
                device="desktop-1",
            )
        )
        laptop_task = asyncio.create_task(
            handler.handle_command(
                "desktop_powershell",
                {"script": "'laptop'"},
                device="desktop-2",
            )
        )
        await asyncio.sleep(0)

        self.assertEqual(len(office.sent), 1)
        self.assertEqual(len(laptop.sent), 1)
        office_request_id = office.sent[0]["payload"]["request_id"]
        laptop_request_id = laptop.sent[0]["payload"]["request_id"]
        self.assertNotEqual(office_request_id, laptop_request_id)

        # Complete them in reverse order to prove correlation is by request
        # and target WebSocket, not by the latest status or response.
        await handler.handle(
            laptop,  # type: ignore[arg-type]
            {
                "channel": "desktop",
                "type": "desktop.response",
                "payload": {
                    "request_id": laptop_request_id,
                    "ok": True,
                    "result": {"stdout": "laptop", "exit_code": 0},
                },
            },
        )
        self.assertFalse(office_task.done())
        await handler.handle(
            office,  # type: ignore[arg-type]
            {
                "channel": "desktop",
                "type": "desktop.response",
                "payload": {
                    "request_id": office_request_id,
                    "ok": True,
                    "result": {"stdout": "office", "exit_code": 0},
                },
            },
        )

        office_result, laptop_result = await asyncio.gather(
            office_task,
            laptop_task,
        )
        self.assertEqual(office_result["result"]["stdout"], "office")
        self.assertEqual(laptop_result["result"]["stdout"], "laptop")
        self.assertEqual(office_result["target"]["device_id"], "desktop-1")
        self.assertEqual(laptop_result["target"]["device_id"], "desktop-2")

    async def test_health_snapshot_lists_connected_desktops(self) -> None:
        handler, _office, _laptop = await _register_two()
        clients = handler.status_snapshot()["clients"]
        self.assertEqual({client["device_id"] for client in clients}, {"desktop-1", "desktop-2"})
