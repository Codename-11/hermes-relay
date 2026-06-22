"""Regression tests for the realtime-agent Hermes session/tool broker.

Covers issue #101:
  * ``_create_session`` must parse both the legacy flat shape and the current
    nested ``{"session": {"id": ...}}`` API Server response.
  * ``stream_task`` must recover from a ``404 session_not_found`` when a
    caller-supplied ``session_id`` was created outside the API Server, by
    minting a fresh API Server session and retrying the turn once.

These exercise the real aiohttp client path against a local fake API Server
(the repo does not ship ``aioresponses``, and the broker creates its own
``aiohttp.ClientSession``).
"""

from __future__ import annotations

import unittest

import aiohttp
from aiohttp import web
from aiohttp.test_utils import TestServer

from plugin.relay.realtime_agent.hermes_tool_broker import (
    HermesTaskRequest,
    HermesToolBroker,
    _is_session_not_found,
    _session_id_from_create_response,
)


class FakeHermesApiServer:
    """Minimal stand-in for the Hermes API Server session/chat surface."""

    def __init__(self) -> None:
        self.known_sessions: set[str] = set()
        self.create_bodies: list[dict] = []
        self.chat_calls: list[str] = []
        self.create_shape = "nested"  # nested | flat_id | flat_session_id
        self.next_created_id = "api_1700000000_abcd1234"

    def _create_payload(self, session_id: str) -> dict:
        if self.create_shape == "flat_id":
            return {"id": session_id}
        if self.create_shape == "flat_session_id":
            return {"session_id": session_id}
        return {"object": "hermes.session", "session": {"id": session_id}}

    async def create_session(self, request: web.Request) -> web.Response:
        self.create_bodies.append(await request.json())
        session_id = self.next_created_id
        self.known_sessions.add(session_id)
        return web.json_response(self._create_payload(session_id), status=201)

    async def chat_stream(self, request: web.Request) -> web.StreamResponse:
        session_id = request.match_info["session_id"]
        self.chat_calls.append(session_id)
        if session_id not in self.known_sessions:
            return web.json_response(
                {
                    "error": {
                        "message": f"Session not found: {session_id}",
                        "code": "session_not_found",
                    }
                },
                status=404,
            )
        resp = web.StreamResponse(
            status=200, headers={"Content-Type": "text/event-stream"}
        )
        await resp.prepare(request)
        await resp.write(b'event: assistant.delta\ndata: {"delta": "hello"}\n\n')
        await resp.write(b"event: run.completed\ndata: {}\n\n")
        await resp.write_eof()
        return resp


def _make_app(server: FakeHermesApiServer) -> web.Application:
    app = web.Application()
    app.router.add_post("/api/sessions", server.create_session)
    app.router.add_post(
        "/api/sessions/{session_id}/chat/stream", server.chat_stream
    )
    return app


class SessionIdParseTest(unittest.TestCase):
    """Pure-function coverage for the response-shape parsers."""

    def test_nested_session_id(self) -> None:
        data = {"object": "hermes.session", "session": {"id": "api_123"}}
        self.assertEqual(_session_id_from_create_response(data), "api_123")

    def test_nested_session_session_id_key(self) -> None:
        data = {"session": {"session_id": "api_456"}}
        self.assertEqual(_session_id_from_create_response(data), "api_456")

    def test_flat_id(self) -> None:
        self.assertEqual(_session_id_from_create_response({"id": "api_flat"}), "api_flat")

    def test_flat_session_id(self) -> None:
        self.assertEqual(
            _session_id_from_create_response({"session_id": "api_flat2"}), "api_flat2"
        )

    def test_top_level_id_wins_over_nested(self) -> None:
        data = {"id": "api_top", "session": {"id": "api_nested"}}
        self.assertEqual(_session_id_from_create_response(data), "api_top")

    def test_missing_id_returns_empty(self) -> None:
        self.assertEqual(_session_id_from_create_response({"object": "hermes.session"}), "")
        self.assertEqual(_session_id_from_create_response({"session": {}}), "")
        self.assertEqual(_session_id_from_create_response(None), "")

    def test_session_not_found_detection(self) -> None:
        body = '{"error": {"message": "Session not found: x", "code": "session_not_found"}}'
        self.assertTrue(_is_session_not_found(404, body))
        # Only a 404 should ever be treated as a handoff trigger.
        self.assertFalse(_is_session_not_found(500, body))
        # Plain-text body still recognised.
        self.assertTrue(_is_session_not_found(404, "Session not found: abc"))
        # Unrelated 404 is not a session handoff.
        self.assertFalse(_is_session_not_found(404, '{"error": {"code": "not_found"}}'))


class HermesToolBrokerStreamTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.fake = FakeHermesApiServer()
        self.server = TestServer(_make_app(self.fake))
        await self.server.start_server()
        base_url = str(self.server.make_url("")).rstrip("/")
        self.broker = HermesToolBroker(base_url)

    async def asyncTearDown(self) -> None:
        await self.server.close()

    async def _collect(self, request: HermesTaskRequest) -> list[dict]:
        return [event async for event in self.broker.stream_task(request)]

    async def test_create_session_parses_nested_shape(self) -> None:
        async with aiohttp.ClientSession() as http:
            session_id = await self.broker._create_session(
                http,
                HermesTaskRequest(text="hi", profile=None, session_id=None),
                {},
            )
        self.assertEqual(session_id, self.fake.next_created_id)

    async def test_create_session_parses_flat_shape(self) -> None:
        self.fake.create_shape = "flat_id"
        async with aiohttp.ClientSession() as http:
            session_id = await self.broker._create_session(
                http,
                HermesTaskRequest(text="hi", profile=None, session_id=None),
                {},
            )
        self.assertEqual(session_id, self.fake.next_created_id)

    async def test_no_session_id_creates_api_session(self) -> None:
        events = await self._collect(
            HermesTaskRequest(text="hi", profile=None, session_id=None)
        )
        bound = [e for e in events if e["type"] == "hermes.session.bound"]
        self.assertEqual(len(bound), 1)
        self.assertEqual(bound[0]["session_id"], self.fake.next_created_id)
        deltas = [e for e in events if e["type"] == "voice.response.delta"]
        self.assertEqual("".join(e["delta"] for e in deltas), "hello")
        self.assertEqual(self.fake.chat_calls, [self.fake.next_created_id])
        self.assertEqual(len(self.fake.create_bodies), 1)

    async def test_existing_api_session_is_reused(self) -> None:
        self.fake.known_sessions.add("api_existing")
        events = await self._collect(
            HermesTaskRequest(text="hi", profile=None, session_id="api_existing")
        )
        # No session creation, no handoff bound event.
        self.assertEqual(self.fake.create_bodies, [])
        self.assertEqual(
            [e for e in events if e["type"] == "hermes.session.bound"], []
        )
        self.assertEqual(self.fake.chat_calls, ["api_existing"])
        deltas = [e for e in events if e["type"] == "voice.response.delta"]
        self.assertEqual("".join(e["delta"] for e in deltas), "hello")

    async def test_session_not_found_triggers_handoff_and_retry(self) -> None:
        # "gateway-xyz" is from another namespace and not in the API Server store.
        events = await self._collect(
            HermesTaskRequest(text="hi", profile=None, session_id="gateway-xyz")
        )
        # First chat hit the stale id, then the freshly minted API session.
        self.assertEqual(
            self.fake.chat_calls, ["gateway-xyz", self.fake.next_created_id]
        )
        self.assertEqual(len(self.fake.create_bodies), 1)
        bound = [e for e in events if e["type"] == "hermes.session.bound"]
        self.assertEqual(len(bound), 1)
        self.assertEqual(bound[0]["session_id"], self.fake.next_created_id)
        self.assertEqual(bound[0].get("reason"), "session_not_found_handoff")
        # The turn still completes after the handoff.
        deltas = [e for e in events if e["type"] == "voice.response.delta"]
        self.assertEqual("".join(e["delta"] for e in deltas), "hello")
        self.assertTrue(
            any(e["type"] == "hermes.run.completed" for e in events)
        )
        # No stray voice.error surfaced to the user.
        self.assertEqual([e for e in events if e["type"] == "voice.error"], [])

    async def test_handoff_retried_only_once(self) -> None:
        # Pathological case: the API Server never recognises any session (even the
        # one we just minted). The broker must give up after a single resolution
        # attempt rather than loop forever creating sessions.
        fake = FakeHermesApiServer()

        async def always_missing(request: web.Request) -> web.StreamResponse:
            fake.chat_calls.append(request.match_info["session_id"])
            return web.json_response(
                {"error": {"code": "session_not_found"}}, status=404
            )

        fake.chat_stream = always_missing  # type: ignore[assignment]
        server = TestServer(_make_app(fake))
        await server.start_server()
        try:
            broker = HermesToolBroker(str(server.make_url("")).rstrip("/"))
            events = [
                e
                async for e in broker.stream_task(
                    HermesTaskRequest(text="hi", profile=None, session_id="gateway-xyz")
                )
            ]
        finally:
            await server.close()
        # Exactly two chat attempts: original + one handoff retry, then give up.
        self.assertEqual(len(fake.chat_calls), 2)
        self.assertEqual(len(fake.create_bodies), 1)
        self.assertTrue(any(e["type"] == "voice.error" for e in events))


if __name__ == "__main__":
    unittest.main()
