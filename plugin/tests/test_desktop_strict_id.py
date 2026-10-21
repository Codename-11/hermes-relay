"""Strict authenticated desktop addressing across the native public boundaries."""

from __future__ import annotations

import asyncio
import json
import unittest
from types import SimpleNamespace
from typing import Any
from unittest.mock import Mock, patch

from aiohttp import web
from aiohttp.test_utils import TestServer

from plugin.relay.channels.desktop import DesktopError, DesktopHandler
from plugin.relay.server import handle_desktop_dispatch
from plugin.tools import desktop_tool


class DesktopSocket:
    def __init__(self, handler: DesktopHandler, *, respond: bool = False) -> None:
        self.handler = handler
        self.respond = respond
        self.closed = False
        self.sent: list[dict[str, Any]] = []

    async def send_str(self, raw: str) -> None:
        message = json.loads(raw)
        self.sent.append(message)
        if self.respond and message["type"] == "desktop.command":
            await self.handler.handle_response(self, {"payload": {
                "request_id": message["payload"]["request_id"], "ok": True,
                "result": {"stdout": "selected", "exit_code": 0}}})


async def connect(handler: DesktopHandler, device_id: Any, name: str,
                  *, claim: str | None = None, respond: bool = False) -> DesktopSocket:
    ws = DesktopSocket(handler, respond=respond)
    payload: dict[str, Any] = {"host": name, "advertised_tools": ["desktop_powershell"]}
    if claim is not None:
        payload["device_id"] = claim
    await handler.handle(ws, {"type": "desktop.status", "payload": payload},
                         session=SimpleNamespace(device_id=device_id, device_name=name))
    return ws


class DesktopStrictIdTests(unittest.IsolatedAsyncioTestCase):
    async def refused(self, handler: DesktopHandler, sockets: list[DesktopSocket], **selectors: Any) -> None:
        with self.assertRaises(DesktopError):
            await asyncio.wait_for(handler.handle_command("desktop_powershell", {"script": "'x'"},
                                                         **selectors), timeout=0.5)
        self.assertTrue(all(not ws.sent for ws in sockets))

    async def test_offline_id_never_matches_other_computer_name_or_heartbeat(self) -> None:
        offline_id = "d1b912df-684f-47eb-9de8-923dc86ca620"
        handler = DesktopHandler()
        original = await connect(handler, offline_id, "original")
        self.assertEqual(handler.status_snapshot()["clients"][0]["authenticated_device_id"], offline_id)
        original.closed = True
        other = await connect(handler, "online-id", offline_id, claim=offline_id)
        await self.refused(handler, [original, other], device_id=offline_id)

    async def test_normalized_or_casefold_id_collisions_never_match_strict_id(self) -> None:
        handler = DesktopHandler()
        other = await connect(handler, "Desktop-1", "desktop1", claim="desktop1")
        for selector in ("desktop1", "desktop-1", "Desktop1"):
            await self.refused(handler, [other], device_id=selector)

    async def test_duplicate_authenticated_ids_refuse_without_sending(self) -> None:
        handler = DesktopHandler()
        first = await connect(handler, "duplicate", "first")
        second = await connect(handler, "duplicate", "second")
        await self.refused(handler, [first, second], device_id="duplicate")

    async def test_unknown_auth_identity_cannot_be_replaced_by_heartbeat_claim(self) -> None:
        for identity in (None, "unknown", "none", "null", "", " bad-id ", 7):
            with self.subTest(identity=identity):
                handler = DesktopHandler()
                other = await connect(handler, identity, "legacy", claim="claimed-id")
                await self.refused(handler, [other], device_id="claimed-id")
                self.assertIsNone(handler.status_snapshot()["clients"][0]["authenticated_device_id"])

    async def test_explicit_malformed_ids_do_not_downgrade_to_sole_client_or_name(self) -> None:
        handler = DesktopHandler()
        other = await connect(handler, "online-id", "friendly-name")
        for malformed in (None, "", " ", " online-id", "online-id\n", 0, False, [], {}, "x" * 257):
            with self.subTest(device_id=malformed):
                await self.refused(handler, [other], device="friendly-name", device_id=malformed)

    async def test_both_selectors_must_identify_same_connection(self) -> None:
        handler = DesktopHandler()
        first = await connect(handler, "first-id", "first")
        second = await connect(handler, "second-id", "second")
        await self.refused(handler, [first, second], device="second", device_id="first-id")
        first.respond = True
        for name in ("first", "first-id", None):
            result = await handler.handle_command("desktop_powershell", {"script": "'x'"},
                                                  device=name, device_id="first-id")
            self.assertEqual(result["result"]["stdout"], "selected")
        self.assertEqual(second.sent, [])

    async def test_legacy_friendly_names_and_sole_client_keep_working(self) -> None:
        handler = DesktopHandler()
        other = await connect(handler, "online-id", "Office-Laptop", respond=True)
        for selector in (None, "office laptop", "ONLINE-ID"):
            result = await handler.handle_command("desktop_powershell", {"script": "'x'"}, device=selector)
            self.assertEqual(result["result"]["stdout"], "selected")
        self.assertEqual(len(other.sent), 3)

    async def test_exact_id_response_stays_bound_to_selected_socket(self) -> None:
        handler = DesktopHandler()
        first = await connect(handler, "first-id", "first")
        second = await connect(handler, "second-id", "second")
        task = asyncio.create_task(handler.handle_command("desktop_powershell", {}, device_id="first-id"))
        await asyncio.sleep(0)
        request_id = first.sent[0]["payload"]["request_id"]
        response = {"payload": {"request_id": request_id, "ok": True, "result": {"stdout": "first"}}}
        await handler.handle_response(second, response)
        self.assertFalse(task.done())
        await handler.handle_response(first, response)
        self.assertEqual((await asyncio.wait_for(task, 0.5))["result"]["stdout"], "first")
        self.assertEqual(second.sent, [])

    async def test_tool_to_real_http_preserves_strict_mode_and_consumes_both_aliases(self) -> None:
        handler = DesktopHandler()
        target = await connect(handler, "exact-id", "friendly", respond=True)
        app = web.Application()
        app["server"] = SimpleNamespace(desktop=handler)
        app.router.add_post("/desktop/{tool_name}", handle_desktop_dispatch)
        async with TestServer(app) as server:
            with patch.dict("os.environ", DESKTOP_RELAY_URL=str(server.make_url("")).rstrip("/")):
                for selectors in ({"device_id": "exact-id"}, {"device": "friendly", "device_id": "exact-id"},
                                  {"device": None, "device_id": "exact-id"}):
                    result = json.loads(await asyncio.to_thread(desktop_tool._HANDLERS["desktop_powershell"],
                                        {"script": "'x'", **selectors}, session_id="chat", task_id="run"))
                    self.assertTrue(result["ok"])
                for selectors in ({"device_id": "missing"}, {"device_id": None}, {"device_id": ""},
                                  {"device_id": []}, {"device": "other", "device_id": "exact-id"}):
                    result = json.loads(await asyncio.to_thread(desktop_tool._HANDLERS["desktop_powershell"],
                                        {"script": "'x'", **selectors}))
                    self.assertIn("error", result)
        self.assertEqual(len(target.sent), 3)
        for command in target.sent:
            self.assertEqual(command["payload"]["args"], {"script": "'x'", "timeout": 30})
            self.assertEqual(command["payload"]["target_device_id"], "exact-id")

    async def test_tool_context_resets_after_success_and_exception(self) -> None:
        response = Mock(status_code=200)
        response.json.return_value = {"ok": True}
        handler = desktop_tool._HANDLERS["desktop_powershell"]
        with patch.object(desktop_tool.requests, "post", side_effect=RuntimeError("transport failed")):
            with self.assertRaises(RuntimeError):
                handler({"script": "'x'", "device": "friendly", "device_id": "exact-id"},
                        session_id="chat-one", task_id="run-one")
        self.assertIsNone(desktop_tool._DESKTOP_TARGET.get())
        self.assertEqual(desktop_tool._DESKTOP_CALL_CONTEXT.get(), {})
        with patch.object(desktop_tool.requests, "post", return_value=response) as post:
            handler({"script": "'next'"})
        self.assertNotIn("device", post.call_args.kwargs["json"])
        self.assertNotIn("device_id", post.call_args.kwargs["json"])
        self.assertNotIn("X-Hermes-Relay-Chat-Session", post.call_args.kwargs["headers"])
        self.assertIsNone(desktop_tool._DESKTOP_TARGET.get())

    async def test_concurrent_tool_calls_keep_strict_id_context_separate(self) -> None:
        handler = DesktopHandler()
        first = await connect(handler, "first-id", "first", respond=True)
        second = await connect(handler, "second-id", "second", respond=True)
        app = web.Application()
        app["server"] = SimpleNamespace(desktop=handler)
        app.router.add_post("/desktop/{tool_name}", handle_desktop_dispatch)
        async with TestServer(app) as server:
            with patch.dict("os.environ", DESKTOP_RELAY_URL=str(server.make_url("")).rstrip("/")):
                results = await asyncio.gather(*(
                    asyncio.to_thread(desktop_tool._HANDLERS["desktop_powershell"],
                                      {"script": device_id, "device_id": device_id},
                                      session_id="chat-" + device_id, task_id="run-" + device_id)
                    for device_id in ("first-id", "second-id")
                ))
        self.assertTrue(all(json.loads(result)["ok"] for result in results))
        for ws, device_id in ((first, "first-id"), (second, "second-id")):
            self.assertEqual(len(ws.sent), 1)
            self.assertEqual(ws.sent[0]["payload"]["args"]["script"], device_id)
            self.assertEqual(ws.sent[0]["payload"]["target_device_id"], device_id)

    async def test_every_client_schema_exposes_strict_id_without_changing_health(self) -> None:
        for tool, schema in desktop_tool._SCHEMAS.items():
            properties = schema["parameters"]["properties"]
            if tool == "desktop_health":
                self.assertNotIn("device_id", properties)
            else:
                self.assertEqual(properties["device_id"]["type"], "string")
                self.assertIn("device", properties)


if __name__ == "__main__":
    unittest.main()
