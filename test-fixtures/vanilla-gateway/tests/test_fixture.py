from __future__ import annotations

import asyncio
import contextlib
import io
import sys
import unittest
from pathlib import Path
from typing import Any

from aiohttp import ClientSession, WSMsgType, web

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from vanilla_gateway import GatewayFixture, ScenarioError, load_scenario  # noqa: E402
from vanilla_gateway.cli import parse_args  # noqa: E402
from vanilla_gateway.evidence import EvidenceLog  # noqa: E402


class FixtureTestCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.session = ClientSession()
        self.runner: web.AppRunner | None = None
        self.websockets = []

    async def asyncTearDown(self) -> None:
        for websocket in self.websockets:
            if not websocket.closed:
                await websocket.close()
        await self.session.close()
        if self.runner is not None:
            await self.runner.cleanup()

    async def start(self, scenario_name: str) -> tuple[GatewayFixture, str]:
        fixture = GatewayFixture(load_scenario(scenario_name), evidence_limit=64)
        self.runner = web.AppRunner(fixture.app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, "127.0.0.1", 0)
        await site.start()
        server = site._server
        assert server is not None
        port = server.sockets[0].getsockname()[1]
        return fixture, f"http://127.0.0.1:{port}"

    async def connect(self, base_url: str):
        async with self.session.post(f"{base_url}/api/auth/ws-ticket") as response:
            self.assertEqual(200, response.status)
            ticket = (await response.json())["ticket"]
        ws = await self.session.ws_connect(f"{base_url}/api/ws?ticket={ticket}")
        self.websockets.append(ws)
        ready = await ws.receive_json()
        self.assertEqual("gateway.ready", ready["params"]["type"])
        return ws, ticket

    async def rpc(self, ws, request_id: int, method: str, params: dict[str, Any] | None = None):
        await ws.send_json(
            {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}},
        )

    async def frames_until(self, ws, predicate, limit: int = 30) -> list[dict[str, Any]]:
        frames: list[dict[str, Any]] = []
        for _ in range(limit):
            message = await asyncio.wait_for(ws.receive(), timeout=5)
            if message.type != WSMsgType.TEXT:
                break
            frame = message.json()
            frames.append(frame)
            if predicate(frame):
                break
        return frames

    async def test_ticket_is_single_use(self) -> None:
        _, base_url = await self.start("ordinary_turn")
        ws, ticket = await self.connect(base_url)
        rejected = await self.session.get(f"{base_url}/api/ws?ticket={ticket}")
        self.assertEqual(401, rejected.status)
        await rejected.release()
        await ws.close()

    async def test_ordinary_turn_persists_authoritative_history(self) -> None:
        fixture, base_url = await self.start("ordinary_turn")
        ws, _ = await self.connect(base_url)
        await self.rpc(ws, 1, "session.create", {"profile": "default"})
        create = (await ws.receive_json())["result"]
        self.assertEqual(fixture.scenario.live_session_id, create["session_id"])
        await self.rpc(ws, 2, "prompt.submit", {"text": "not recorded in evidence"})
        frames = await self.frames_until(
            ws,
            lambda frame: frame.get("params", {}).get("type") == "message.complete",
        )
        event_types = [frame.get("params", {}).get("type") for frame in frames]
        self.assertIn("message.start", event_types)
        self.assertIn("message.delta", event_types)
        self.assertIn("message.complete", event_types)
        async with self.session.get(
            f"{base_url}/api/sessions/{fixture.scenario.stored_session_id}/messages",
            params={"profile": "default", "limit": 500, "offset": 0, "order": "asc"},
        ) as response:
            history = await response.json()
        self.assertEqual(["user", "assistant"], [row["role"] for row in history["messages"]])
        self.assertEqual(2, history["pagination"]["returned"])

    async def test_rapid_chunks_tools_and_interims_keep_wire_order(self) -> None:
        _, base_url = await self.start("rapid_tools_interims")
        ws, _ = await self.connect(base_url)
        await self.rpc(ws, 1, "prompt.submit", {"text": "fixture"})
        frames = await self.frames_until(
            ws,
            lambda frame: frame.get("params", {}).get("type") == "message.complete",
        )
        event_types = [
            frame["params"]["type"] for frame in frames if frame.get("method") == "event"
        ]
        self.assertEqual(
            [
                "message.start",
                "reasoning.delta",
                "tool.start",
                "message.delta",
                "message.delta",
                "tool.complete",
                "message.delta",
                "message.complete",
            ],
            event_types,
        )

    async def test_terminal_gap_reconnect_activation_is_authoritatively_settled(self) -> None:
        fixture, base_url = await self.start("terminal_gap_activate")
        ws, _ = await self.connect(base_url)
        await self.rpc(ws, 1, "session.create")
        await ws.receive_json()
        await self.rpc(ws, 2, "prompt.submit", {"text": "fixture"})
        first_connection = await self.frames_until(ws, lambda _frame: False)
        event_types = [frame.get("params", {}).get("type") for frame in first_connection]
        self.assertIn("message.delta", event_types)
        self.assertNotIn("message.complete", event_types)
        ws2, _ = await self.connect(base_url)
        await self.rpc(
            ws2, 3, "session.activate", {"session_id": fixture.scenario.live_session_id},
        )
        activation = await ws2.receive_json()
        self.assertFalse(activation["result"]["running"])
        self.assertEqual(fixture.scenario.stored_session_id, activation["result"]["stored_session_id"])
        await ws2.close()

    async def test_scoped_session_info_settles_without_message_complete(self) -> None:
        fixture, base_url = await self.start("terminal_gap_session_info")
        ws, _ = await self.connect(base_url)
        await self.rpc(ws, 1, "prompt.submit", {"text": "fixture"})
        frames = await self.frames_until(
            ws,
            lambda frame: frame.get("params", {}).get("type") == "session.info",
        )
        events = [frame["params"] for frame in frames if frame.get("method") == "event"]
        self.assertFalse(events[-1]["payload"]["running"])
        self.assertEqual(fixture.scenario.live_session_id, events[-1]["session_id"])
        self.assertNotIn("message.complete", [event["type"] for event in events])

    async def test_queued_follow_up_runs_after_first_turn(self) -> None:
        _, base_url = await self.start("queued_follow_up")
        ws, _ = await self.connect(base_url)
        await self.rpc(ws, 1, "prompt.submit", {"text": "first"})
        frames = await self.frames_until(
            ws,
            lambda frame: frame.get("params", {}).get("type") == "message.delta",
        )
        self.assertTrue(frames)
        await self.rpc(ws, 2, "prompt.submit", {"text": "queued"})
        complete_count = 0

        def two_completions(frame: dict[str, Any]) -> bool:
            nonlocal complete_count
            if frame.get("params", {}).get("type") == "message.complete":
                complete_count += 1
            return complete_count >= 2

        frames += await self.frames_until(
            ws,
            two_completions,
        )
        completes = [
            frame for frame in frames if frame.get("params", {}).get("type") == "message.complete"
        ]
        self.assertEqual(2, len(completes))

    async def test_scope_scenario_exposes_rejection_inputs_and_exact_terminal(self) -> None:
        fixture, base_url = await self.start("scope_rejection_inputs")
        ws, _ = await self.connect(base_url)
        await self.rpc(ws, 1, "prompt.submit", {"text": "fixture"})
        frames = await self.frames_until(
            ws,
            lambda frame: (
                frame.get("params", {}).get("type") == "message.complete"
                and frame.get("params", {}).get("session_id") == fixture.scenario.live_session_id
            ),
        )
        events = [frame["params"] for frame in frames if frame.get("method") == "event"]
        self.assertTrue(any("session_id" not in event for event in events))
        self.assertTrue(any(event.get("session_id") == "fixture-foreign-session" for event in events))
        self.assertEqual(fixture.scenario.live_session_id, events[-1]["session_id"])

    async def test_active_list_exposes_all_live_states_then_authoritative_absence(self) -> None:
        _, base_url = await self.start("active_status_lifecycle")
        ws, _ = await self.connect(base_url)

        await self.rpc(ws, 1, "session.active_list", {"current_session_id": "fixture-live-working"})
        first = (await ws.receive_json())["result"]["sessions"]
        self.assertEqual(
            ["starting", "working", "waiting", "idle"],
            [row["status"] for row in first],
        )

        await self.rpc(ws, 2, "session.active_list", {"current_session_id": "fixture-live-working"})
        second = (await ws.receive_json())["result"]["sessions"]
        self.assertEqual([], second)

        # An exhausted script remains on its last successful snapshot so polling
        # cannot accidentally resurrect an earlier live row.
        await self.rpc(ws, 3, "session.active_list")
        third = (await ws.receive_json())["result"]["sessions"]
        self.assertEqual([], third)

    async def test_active_list_rows_require_client_owned_profile_resolution(self) -> None:
        fixture, base_url = await self.start("active_status_profile_scope")
        ws, _ = await self.connect(base_url)

        # Upstream accepts current_session_id, not a profile filter. The fixture
        # deliberately ignores this extra hint and returns an unscoped row.
        await self.rpc(ws, 1, "session.active_list", {"profile": "default"})
        rows = (await ws.receive_json())["result"]["sessions"]

        self.assertEqual("research", fixture.scenario.profile)
        self.assertEqual("fixture-shared-stored-session", rows[0]["session_key"])
        self.assertNotIn("profile", rows[0])

    async def test_active_list_unsupported_is_explicit_method_not_found(self) -> None:
        _, base_url = await self.start("active_status_unsupported")
        ws, _ = await self.connect(base_url)
        await self.rpc(ws, 1, "session.active_list")
        frame = await ws.receive_json()
        self.assertEqual(-32601, frame["error"]["code"])

    async def test_evidence_is_bounded_and_contains_no_rpc_payloads(self) -> None:
        _, base_url = await self.start("ordinary_turn")
        ws, _ = await self.connect(base_url)
        await self.rpc(ws, 1, "prompt.submit", {"text": "private sentinel"})
        await self.frames_until(
            ws,
            lambda frame: frame.get("params", {}).get("type") == "message.complete",
        )
        async with self.session.get(f"{base_url}/__fixture__/evidence") as response:
            evidence = await response.json()
        rendered = str(evidence)
        self.assertNotIn("private sentinel", rendered)
        self.assertNotIn("Fixture response", rendered)
        self.assertTrue(evidence["bounded"])
        allowed = {"sequence", "kind", "connection", "method", "event_type", "scope", "outcome"}
        self.assertTrue(all(set(entry) <= allowed for entry in evidence["entries"]))


class ScenarioTestCase(unittest.TestCase):
    def test_all_bundled_scenarios_validate(self) -> None:
        for name in (
            "active_status_lifecycle",
            "active_status_profile_scope",
            "active_status_unsupported",
            "ordinary_turn",
            "rapid_tools_interims",
            "terminal_gap_activate",
            "terminal_gap_session_info",
            "queued_follow_up",
            "scope_rejection_inputs",
        ):
            scenario = load_scenario(name)
            self.assertEqual(name, scenario.name)
            self.assertTrue(scenario.contract_requirements)

    def test_active_list_scenario_rejects_unknown_status(self) -> None:
        scenario = {
            "name": "invalid_activity",
            "live_session_id": "live",
            "stored_session_id": "stored",
            "turns": [],
            "active_list": {
                "supported": True,
                "snapshots": [[{"id": "live", "session_key": "stored", "status": "recent"}]],
            },
        }
        with self.assertRaisesRegex(ScenarioError, "invalid status"):
            from vanilla_gateway.scenario import Scenario
            Scenario.from_dict(scenario)

    def test_terminal_gap_manifests_select_upstream_contracts(self) -> None:
        self.assertEqual(
            (
                "gateway.message_complete",
                "gateway.settled_session_info",
                "gateway.session_activate_live",
            ),
            load_scenario("terminal_gap_activate").contract_requirements,
        )
        self.assertEqual(
            ("gateway.settled_session_info",),
            load_scenario("terminal_gap_session_info").contract_requirements,
        )

    def test_tls_arguments_must_be_paired(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                parse_args(["ordinary_turn", "--tls-cert", "fixture.pem"])
            with self.assertRaises(SystemExit):
                parse_args(["ordinary_turn", "--tls-key", "fixture.key"])
        args = parse_args(
            [
                "ordinary_turn",
                "--tls-cert", "fixture.pem",
                "--tls-key", "fixture.key",
            ],
        )
        self.assertEqual(Path("fixture.pem"), args.tls_cert)
        self.assertEqual(Path("fixture.key"), args.tls_key)

    def test_non_loopback_binding_requires_tls(self) -> None:
        self.assertEqual("127.0.0.1", parse_args(["ordinary_turn"]).host)
        self.assertEqual("::1", parse_args(["ordinary_turn", "--host", "::1"]).host)
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                parse_args(["ordinary_turn", "--host", "0.0.0.0"])
        args = parse_args(
            [
                "ordinary_turn",
                "--host", "0.0.0.0",
                "--tls-cert", "fixture.pem",
                "--tls-key", "fixture.key",
            ],
        )
        self.assertEqual("0.0.0.0", args.host)

    def test_invalid_scenario_fails_closed(self) -> None:
        with self.assertRaises(ScenarioError):
            load_scenario(Path(__file__).parent / "missing.json")

    def test_evidence_ring_drops_oldest_entries(self) -> None:
        evidence = EvidenceLog(maximum=2)
        evidence.add("rpc", method="first")
        evidence.add("rpc", method="second")
        evidence.add("rpc", method="third")
        exported = evidence.export("fixture")
        self.assertEqual(["second", "third"], [entry["method"] for entry in exported["entries"]])

    def test_evidence_redacts_unbounded_metadata_fields(self) -> None:
        evidence = EvidenceLog(maximum=4)
        evidence.add(
            "rpc with private content",
            method="prompt.submit secret text",
            outcome="received\ncredential",
        )

        exported = evidence.export("scenario/private/path")

        self.assertEqual("<redacted>", exported["scenario"])
        self.assertEqual("<redacted>", exported["entries"][0]["kind"])
        self.assertEqual("<redacted>", exported["entries"][0]["method"])
        self.assertEqual("<redacted>", exported["entries"][0]["outcome"])


if __name__ == "__main__":
    unittest.main()
