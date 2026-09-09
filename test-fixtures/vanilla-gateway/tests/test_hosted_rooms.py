from __future__ import annotations

import asyncio
import json
from copy import deepcopy
import sys
import unittest
from pathlib import Path

from aiohttp import ClientSession, WSMsgType, web

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from vanilla_gateway import GatewayFixture, ScenarioError, load_scenario
from vanilla_gateway.scenario import Scenario


class HostedScenarioValidationTest(unittest.TestCase):
    def test_room_listing_pagination_never_repeats_the_same_room(self):
        from vanilla_gateway.hosted_rooms import HostedRoomsFixture
        peer = HostedRoomsFixture(load_scenario("hosted_room_response_loss").hosted_groups)
        first = peer.call("groups.list", {"limit": 1, "offset": 0})
        self.assertEqual(1, first["next_offset"])
        second = peer.call("groups.list", {"limit": 1, "offset": first["next_offset"]})
        self.assertEqual([], second["rooms"])
        self.assertIsNone(second["next_offset"])

    def test_unavailable_driver_preserves_reads_but_refuses_send(self):
        from vanilla_gateway.hosted_rooms import HostedRoomRpcError, HostedRoomsFixture
        config = deepcopy(load_scenario("hosted_room_response_loss").hosted_groups)
        config["capabilities"]["driver"] = False
        peer = HostedRoomsFixture(config)
        self.assertFalse(peer.call("groups.capabilities", {})["driver"])
        with self.assertRaises(HostedRoomRpcError) as caught:
            peer.call("groups.send", config["examples"]["fresh_thread"])
        self.assertEqual(4123, caught.exception.code)
        self.assertNotIn("driver_status", peer.call("groups.state", {"room_id": "fixture-room"}))
        self.assertEqual([], peer.call("groups.log", {"room_id": "fixture-room"})["events"])

    def test_malformed_hosted_declarations_fail_before_server_start(self):
        path = Path(__file__).parents[1] / "vanilla_gateway/scenarios/hosted_room_response_loss.json"
        valid = json.loads(path.read_text(encoding="utf-8"))
        cases = [[], {}, {**valid["hosted_groups"], "lose_response_event_ids": "not-a-list"},
                 {**valid["hosted_groups"], "room": {"room_id": "room"}}]
        for groups in cases:
            with self.subTest(groups=groups):
                raw = {**valid, "hosted_groups": groups}
                with self.assertRaises(ScenarioError):
                    Scenario.from_dict(raw)


class HostedRoomFixtureTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.fixture = GatewayFixture(load_scenario("hosted_room_response_loss"))
        self.runner = web.AppRunner(self.fixture.app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, "127.0.0.1", 0)
        await site.start()
        self.url = f"http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}"
        self.client = ClientSession()
        self.sockets = []

    async def asyncTearDown(self):
        for ws in self.sockets:
            await ws.close()
        await self.client.close()
        await self.runner.cleanup()

    async def connect(self):
        async with self.client.post(self.url + "/api/auth/ws-ticket") as response:
            ticket = (await response.json())["ticket"]
        ws = await self.client.ws_connect(self.url + "/api/ws?ticket=" + ticket)
        self.sockets.append(ws)
        await ws.receive_json(timeout=5)
        return ws

    async def rpc(self, ws, method, params=None, rid=1):
        await ws.send_json({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}})
        return await ws.receive_json(timeout=5)

    async def test_hosted_discovery_uses_gateway_authority_not_session_identity(self):
        ws = await self.connect()
        frame = await self.rpc(ws, "groups.capabilities")
        self.assertIn("result", frame, frame)
        caps = frame["result"]
        self.assertTrue(caps["driver"])
        self.assertIn("idempotent_send", caps["features"])
        rooms = (await self.rpc(ws, "groups.list"))["result"]
        self.assertIsNone(rooms["next_offset"])
        room = rooms["rooms"][0]
        state = (await self.rpc(ws, "groups.state", {"room_id": room["room_id"]}))["result"]
        self.assertEqual(room, state["room"])
        self.assertEqual(caps["authority_gateway_id"], room["authority_gateway_id"])
        self.assertIn("driver_status", state)
        self.assertNotIn("session_id", state)

    async def test_invalid_room_cursor_and_conflicting_retry_do_not_mutate_history(self):
        ws = await self.connect()
        send = {"room_id": "fixture-room", "event_id": "safe-send",
                "payload": {"text": "@writer First", "thread_id": "thread-one"}}
        accepted = (await self.rpc(ws, "groups.send", send))["result"]["event"]
        cases = [
            ("groups.send", {**send, "payload": {**send["payload"], "text": "Changed"}}, 4111),
            ("groups.send", {**send, "room_id": "foreign-room"}, 4111),
            ("groups.state", {"room_id": "foreign-room"}, 4114),
            ("groups.log", {"room_id": "foreign-room"}, 4112),
            ("groups.log", {"room_id": "fixture-room", "since_seq": 999}, 4112),
            ("groups.log", {"room_id": "fixture-room", "limit": 0}, 4112),
            ("groups.send", {**send, "payload": {**send["payload"], "mentions": ["member-writer"]}}, 5112),
        ]
        for method, params, code in cases:
            with self.subTest(method=method, params=params):
                frame = await self.rpc(ws, method, params)
                self.assertIn("error", frame, frame)
                self.assertEqual(code, frame["error"]["code"])
        history = (await self.rpc(ws, "groups.log", {"room_id": "fixture-room"}))["result"]
        self.assertEqual([accepted], history["events"])

    async def test_response_loss_replay_keeps_one_event_and_thread_history(self):
        ws = await self.connect()
        fresh = self.fixture.scenario.hosted_groups["examples"]["fresh_thread"]
        await ws.send_json({"jsonrpc": "2.0", "id": 10, "method": "groups.send", "params": fresh})
        gap = await asyncio.wait_for(ws.receive(), 5)
        self.assertEqual(WSMsgType.CLOSE, gap.type, gap)
        ws = await self.connect()
        replay = (await self.rpc(ws, "groups.send", fresh, rid=11))["result"]
        self.assertEqual(fresh["event_id"], replay["client_event_id"])
        self.assertTrue(replay["accepted"])
        self.assertTrue(replay["driver_started"])
        first = replay["event"]
        self.assertTrue(first["idempotent"])
        self.assertEqual("message.user", first["kind"])
        self.assertEqual({"kind": "user", "id": "desktop"}, first["actor"])
        self.assertNotEqual(fresh["event_id"], first["event_id"])
        self.assertEqual(first["event_id"], first["payload"]["thread_id"])
        reply = self.fixture.scenario.hosted_groups["examples"]["reply"]
        self.assertEqual(first["payload"]["thread_id"], reply["payload"]["thread_id"])
        second = (await self.rpc(ws, "groups.send", reply))["result"]["event"]
        self.assertFalse(second["idempotent"])
        self.assertEqual(reply["payload"], second["payload"])
        page = (await self.rpc(ws, "groups.log", {"room_id": fresh["room_id"], "since_seq": 0, "limit": 1}))["result"]
        self.assertEqual([first["event_id"]], [e["event_id"] for e in page["events"]])
        self.assertFalse(page["events"][0]["idempotent"])
        self.assertTrue(page["has_more"])
        rest = (await self.rpc(ws, "groups.log", {"room_id": fresh["room_id"], "since_seq": page["cursor"], "limit": 1}))["result"]
        self.assertEqual([second], rest["events"])
        self.assertFalse(rest["has_more"])
        self.assertEqual(second["seq"], rest["cursor"])
        self.assertEqual(rest["latest_seq"], rest["cursor"])
        self.assertEqual({"gateway_id": "fixture-authority", "epoch": 1}, rest["authority"])
        empty = (await self.rpc(ws, "groups.log", {"room_id": fresh["room_id"], "since_seq": rest["cursor"]}))["result"]
        self.assertEqual([], empty["events"])
        state = (await self.rpc(ws, "groups.state", {"room_id": fresh["room_id"]}))["result"]
        self.assertEqual(rest["cursor"], state["room"]["latest_seq"])
        async with self.client.get(self.url + "/__fixture__/evidence") as response:
            evidence = await response.text()
        self.assertNotIn(fresh["payload"]["text"], evidence)
        self.assertNotIn(first["event_id"], evidence)
        self.assertIn("response_loss", evidence)
