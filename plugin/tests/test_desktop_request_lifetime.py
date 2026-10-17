"""Native Relay request retirement and HTTP wait budgets, without real devices."""

from __future__ import annotations

import asyncio
import json
import os
import unittest
from typing import Any
from unittest.mock import Mock, patch

from plugin.relay.channels import desktop
from plugin.tools import desktop_tool


class Socket:
    def __init__(self) -> None:
        self.closed = False
        self.sent: list[dict[str, Any]] = []
        self.command_sent = asyncio.Event()
        self.fail_cancel = False
        self.block_command = False

    async def send_str(self, wire: str) -> None:
        envelope = json.loads(wire)
        self.sent.append(envelope)
        if envelope["type"] == "desktop.command":
            self.command_sent.set()
            if self.block_command:
                await asyncio.Future()
        if envelope["type"] == "desktop.cancel" and self.fail_cancel:
            raise ConnectionError("socket closed")


async def register(handler: desktop.DesktopHandler, ws: Socket, name: str) -> None:
    await handler.handle(ws, {
        "type": "desktop.status",
        "payload": {"host": name, "advertised_tools": ["desktop_powershell"]},
    })


def response(request_id: str) -> dict[str, Any]:
    return {"payload": {"request_id": request_id, "status": 200, "result": "late"}}


class DesktopRequestLifetimeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.handler = desktop.DesktopHandler()
        self.target, self.neighbor = Socket(), Socket()
        await register(self.handler, self.target, "target")
        await register(self.handler, self.neighbor, "neighbor")

    def assert_retired(self, decision: str) -> str:
        command = self.target.sent[0]["payload"]
        request_id = command["request_id"]
        self.assertEqual(self.target.sent[1]["type"], "desktop.cancel")
        self.assertEqual(self.target.sent[1]["payload"], {"request_id": request_id})
        self.assertNotIn(request_id, self.handler.pending)
        self.assertNotIn(request_id, self.handler.pending_ws)
        self.assertEqual(self.handler.recent_commands[0].decision, decision)
        self.assertEqual(self.neighbor.sent, [])
        return request_id

    async def test_timeout_cancels_original_socket_and_late_response_stays_terminal(self) -> None:
        with patch.object(desktop, "RESPONSE_TIMEOUT", 0.01):
            with self.assertRaises(desktop.DesktopError):
                await self.handler.handle_command("desktop_powershell", {}, device="target")
        request_id = self.assert_retired("timeout")
        await self.handler.handle_response(self.target, response(request_id))
        await self.handler.handle_response(self.neighbor, response(request_id))
        record = self.handler.recent_commands[0]
        self.assertEqual(record.decision, "timeout")
        self.assertIsNone(record.result_summary)
        self.assertIsNone(record.response_status)

    async def test_task_cancellation_cancels_only_its_request(self) -> None:
        task = asyncio.create_task(self.handler.handle_command("desktop_powershell", {}, device="target"))
        await self.target.command_sent.wait()
        other = asyncio.create_task(self.handler.handle_command("desktop_powershell", {}, device="neighbor"))
        await self.neighbor.command_sent.wait()
        try:
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            request_id = self.target.sent[0]["payload"]["request_id"]
            self.assertEqual(self.target.sent[1]["payload"], {"request_id": request_id})
            self.assertEqual(len(self.neighbor.sent), 1)
            other_id = self.neighbor.sent[0]["payload"]["request_id"]
            self.assertIn(other_id, self.handler.pending)
            await self.handler.handle_response(self.target, response(other_id))
            self.assertFalse(other.done())
            await self.handler.handle_response(self.target, response(request_id))
            self.assertEqual(self.handler.recent_commands[0].decision, "cancelled")
            await self.handler.handle_response(self.neighbor, response(other_id))
            self.assertEqual((await other)["result"], "late")
            self.assertEqual(self.handler.recent_commands[1].decision, "executed")
        finally:
            for pending in (task, other):
                if not pending.done():
                    pending.cancel()
            await asyncio.gather(task, other, return_exceptions=True)

    async def test_cancellation_during_send_retires_request(self) -> None:
        self.target.block_command = True
        task = asyncio.create_task(self.handler.handle_command("desktop_powershell", {}, device="target"))
        await self.target.command_sent.wait()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assert_retired("cancelled")

    async def test_failed_cancel_send_preserves_timeout_and_cleanup(self) -> None:
        self.target.fail_cancel = True
        with patch.object(desktop, "RESPONSE_TIMEOUT", 0.01):
            with self.assertRaises(desktop.DesktopError):
                await self.handler.handle_command("desktop_powershell", {}, device="target")
        self.assert_retired("timeout")

    async def test_already_cancelled_future_response_cannot_change_record(self) -> None:
        task = asyncio.create_task(self.handler.handle_command("desktop_powershell", {}, device="target"))
        await self.target.command_sent.wait()
        request_id = self.target.sent[0]["payload"]["request_id"]
        # Match wait_for's state at expiry, before the command task retires it.
        self.handler.pending[request_id].cancel()
        await self.handler.handle_response(self.target, response(request_id))
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assert_retired("cancelled")

    async def test_timely_response_does_not_send_cancel(self) -> None:
        task = asyncio.create_task(self.handler.handle_command("desktop_powershell", {}, device="target"))
        await self.target.command_sent.wait()
        request_id = self.target.sent[0]["payload"]["request_id"]
        await self.handler.handle_response(self.target, response(request_id))
        self.assertEqual((await task)["result"], "late")
        self.assertEqual(len(self.target.sent), 1)
        self.assertEqual(self.handler.recent_commands[0].decision, "executed")
        self.assertFalse(self.handler.pending)


class DesktopHttpBudgetTests(unittest.TestCase):
    def test_default_and_special_budgets(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(desktop.RESPONSE_TIMEOUT, 180)
            self.assertEqual(desktop.COMPUTER_USE_RESPONSE_TIMEOUT, 180)
            self.assertEqual(desktop.ADB_RESPONSE_TIMEOUT, 300)
            self.assertEqual(desktop_tool._timeout(), 190)
            for operation_timeout in (1, 30, 120):
                self.assertGreater(desktop_tool._adb_http_timeout(operation_timeout), desktop.ADB_RESPONSE_TIMEOUT)

    def test_real_tool_wrapper_uses_aligned_http_wait_and_preserves_override(self) -> None:
        reply = Mock(status_code=200)
        reply.json.return_value = {"ok": True, "result": {}}
        with patch.dict(os.environ, {}, clear=True), patch.object(desktop_tool.requests, "post", return_value=reply) as post:
            desktop_tool.desktop_powershell("Write-Output fixture")
            self.assertEqual(post.call_args.kwargs["timeout"], 190)
            self.assertEqual(post.call_args.kwargs["json"]["timeout"], 30)
            desktop_tool.desktop_computer_grant_request("assist")
            self.assertEqual(post.call_args.kwargs["timeout"], 190)
            desktop_tool.desktop_adb_devices()
            self.assertEqual(post.call_args.kwargs["timeout"], 310)
            with patch.dict(os.environ, {"DESKTOP_TOOL_TIMEOUT": "2"}):
                desktop_tool.desktop_powershell("Write-Output fixture")
                self.assertEqual(post.call_args.kwargs["timeout"], 2)
