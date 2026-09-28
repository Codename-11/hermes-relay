"""aiohttp implementation of the deterministic Gateway contract fixture."""

from __future__ import annotations

import asyncio
import secrets
from collections import deque
from contextlib import suppress
from dataclasses import dataclass
from typing import Any

from aiohttp import WSMsgType, web

from .evidence import EvidenceLog
from .scenario import Scenario


@dataclass
class _QueuedTurn:
    turn: dict[str, Any]
    socket: web.WebSocketResponse
    connection: int


class GatewayFixture:
    """Real HTTP/WebSocket server driven by a declarative :class:`Scenario`."""

    def __init__(self, scenario: Scenario, *, evidence_limit: int = 512) -> None:
        self.scenario = scenario
        self.evidence = EvidenceLog(evidence_limit)
        self.app = web.Application()
        self._setup_mode = "signin"
        self._setup_cookie = secrets.token_urlsafe(24)
        if scenario.dashboard_setup:
            self.app.middlewares.append(self._setup_auth)
            self.app.add_routes([
                web.get("/api/status", self._setup_status),
                web.get("/api/auth/providers", self._setup_providers),
                web.get("/api/auth/me", self._setup_me),
                web.post("/auth/password-login", self._setup_login),
                web.get("/api/profiles", self._setup_profiles),
                web.get("/api/profiles/active", self._setup_profile_scope),
                web.post("/__fixture__/auth", self._setup_control),
            ])
        self.app.add_routes(
            [
                web.post("/api/auth/ws-ticket", self._ticket),
                web.get("/api/ws", self._websocket),
                web.get("/api/sessions", self._sessions),
                web.get("/api/sessions/{session_id}/messages", self._history),
                web.get("/__fixture__/state", self._state),
                web.get("/__fixture__/evidence", self._evidence),
            ],
        )
        self.app.on_cleanup.append(self._on_cleanup)
        self._tickets: set[str] = set()
        self._history_rows = [dict(row) for row in scenario.initial_history]
        self._turns = deque(dict(turn) for turn in scenario.turns)
        self._active_list_snapshots = deque(
            [dict(row) for row in snapshot]
            for snapshot in scenario.active_list_snapshots
        )
        self._last_active_list_snapshot: list[dict[str, Any]] = []
        self._queued: deque[_QueuedTurn] = deque()
        self._running = False
        self._turn_active = False
        self._closing = False
        self._connection_sequence = 0
        self._tasks: set[asyncio.Task[None]] = set()
        self._sockets: set[web.WebSocketResponse] = set()
        self._clarify: dict[str, Any] | None = None
        self._native_request: dict[str, Any] | None = None
        self._capable: set[web.WebSocketResponse] = set()
        self._clarify_answers: dict[str, str] = {}
        self._clarify_done = asyncio.Event()
        self._clarify_owner: tuple[web.WebSocketResponse, int] | None = None

    @property
    def running(self) -> bool:
        return self._running

    @web.middleware
    async def _setup_auth(self, request: web.Request, handler: Any) -> web.StreamResponse:
        protected = request.path.startswith("/api/") and request.path not in {
            "/api/status", "/api/auth/providers", "/api/ws",
        }
        if protected and (self._setup_mode == "loopback" or
                          request.cookies.get("hermes_session") != self._setup_cookie):
            self.evidence.add("auth", outcome="rejected_cookie" if request.cookies else "no_cookie")
            body = {"detail": "Unauthorized"}
            if self._setup_mode != "loopback":
                body["error"] = "session_expired" if self._setup_mode == "expired" else "unauthenticated"
            return web.json_response(body, status=401)
        return await handler(request)

    async def _setup_status(self, _request: web.Request) -> web.Response:
        return web.json_response({
            "auth_required": self._setup_mode != "loopback",
            "auth_providers": ["basic"], "auth_flows": [],
            "profiles": [self.scenario.profile],
            "gateway_mode": "multiplex",
            "gateways": [{"profile": self.scenario.profile, "served_profiles": [self.scenario.profile]}],
            "version": "synthetic-setup-fixture", "install_id": self.scenario.name,
        })

    async def _setup_providers(self, _request: web.Request) -> web.Response:
        return web.json_response({"providers": [{"name": "basic", "supports_password": True}]})

    async def _setup_me(self, _request: web.Request) -> web.Response:
        return web.json_response({"authenticated": True, "username": "Fixture", "provider": "basic"})

    async def _setup_profiles(self, _request: web.Request) -> web.Response:
        return web.json_response({"profiles": [{"name": self.scenario.profile, "model": "fixture"}]})

    async def _setup_profile_scope(self, _request: web.Request) -> web.Response:
        return web.json_response({"active": self.scenario.profile, "current": self.scenario.profile})

    async def _setup_login(self, request: web.Request) -> web.Response:
        body = await request.json()
        if body.get("username") != "fixture" or body.get("password") != "fixture":
            return web.json_response({"detail": "Invalid fixture credentials"}, status=401)
        response = web.json_response({"ok": True, "next": "/"})
        response.set_cookie("hermes_session", self._setup_cookie, httponly=True, path="/")
        return response

    async def _setup_control(self, request: web.Request) -> web.Response:
        mode = (await request.json()).get("mode")
        if mode not in {"signin", "expired", "loopback"}:
            raise web.HTTPBadRequest(text="invalid fixture auth mode")
        self._setup_mode = mode
        self._setup_cookie = secrets.token_urlsafe(24)
        self._tickets.clear()
        for socket in tuple(self._sockets):
            await socket.close(code=4401, message=b"fixture auth changed")
        return web.json_response({"mode": mode})

    async def close(self) -> None:
        self._closing = True
        self._queued.clear()
        sockets = tuple(self._sockets)
        if sockets:
            await asyncio.gather(
                *(socket.close(code=1001, message=b"fixture shutdown") for socket in sockets),
                return_exceptions=True,
            )
        tasks = tuple(self._tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _on_cleanup(self, _app: web.Application) -> None:
        await self.close()

    async def _ticket(self, _request: web.Request) -> web.Response:
        token = f"fixture-{secrets.token_urlsafe(12)}"
        self._tickets.add(token)
        self.evidence.add("ticket", outcome="minted")
        return web.json_response({"ticket": token, "ttl_seconds": 30})

    async def _websocket(self, request: web.Request) -> web.StreamResponse:
        protocols = [p.strip() for p in request.headers.get("Sec-WebSocket-Protocol", "").split(",")]
        protocol_tickets = [p.removeprefix("hermes-gateway-ticket.") for p in protocols
                            if p.startswith("hermes-gateway-ticket.")]
        if protocol_tickets and (len(protocol_tickets) != 1 or "hermes-gateway-v1" not in protocols):
            raise web.HTTPUnauthorized(text="ambiguous ticket protocol")
        ticket = protocol_tickets[0] if protocol_tickets else request.query.get("ticket", "")
        if ticket not in self._tickets:
            self.evidence.add("socket", outcome="ticket_rejected")
            raise web.HTTPUnauthorized(text="invalid or already-used ticket")
        self._tickets.remove(ticket)
        socket = web.WebSocketResponse(
            heartbeat=None, protocols=["hermes-gateway-v1"] if protocol_tickets else [],
        )
        await socket.prepare(request)
        self._sockets.add(socket)
        self._connection_sequence += 1
        connection = self._connection_sequence
        self.evidence.add("socket", connection=connection, outcome="opened")
        await self._send_event(socket, connection, "gateway.ready", None, None)
        try:
            async for message in socket:
                if message.type == WSMsgType.TEXT:
                    await self._handle_rpc(socket, connection, message.json())
                elif message.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            self._sockets.discard(socket)
            self._capable.discard(socket)
            self.evidence.add("socket", connection=connection, outcome="closed")
        return socket

    async def _handle_rpc(
        self,
        socket: web.WebSocketResponse,
        connection: int,
        frame: Any,
    ) -> None:
        if not isinstance(frame, dict) or frame.get("jsonrpc") != "2.0":
            return
        method = frame.get("method")
        request_id = frame.get("id")
        params = frame.get("params") if isinstance(frame.get("params"), dict) else {}
        if method is None and request_id is not None:
            pending = self._native_request
            if pending and request_id == pending["id"] and ("result" in frame or "error" in frame):
                self.evidence.add("rpc", connection=connection, method=pending["method"], outcome="answered")
                self._clarify_done.set()
            return
        if not isinstance(method, str) or request_id is None:
            return
        self.evidence.add("rpc", connection=connection, method=method, outcome="received")
        if method == "client.capabilities":
            if params.get("server_requests") is True:
                self._capable.add(socket)
            result = {"ok": True}
        elif self.scenario.dashboard_setup and method == "profiles.list":
            result = {"profiles": [{"name": self.scenario.profile, "model": "fixture", "is_default": True}]}
        elif self.scenario.dashboard_setup and method == "pet.info":
            result = {"enabled": False}
        elif method == "session.create":
            result = self._session_snapshot(include_stored=True)
            if self.scenario.session_initialization_error:
                result["info"]["lazy"] = True
                await self._send_event(socket, connection, "error", {
                    "message": self.scenario.session_initialization_error,
                }, self.scenario.live_session_id)
        elif method == "session.resume":
            requested = params.get("session_id")
            if requested != self.scenario.stored_session_id:
                await self._rpc_error(socket, request_id, 4040, "Stored session not found")
                return
            result = self._session_snapshot(include_stored=True)
            if self._clarify is not None or self._native_request is not None:
                self._clarify_owner = socket, connection
        elif method == "session.activate":
            requested = params.get("session_id")
            if requested != self.scenario.live_session_id:
                await self._rpc_error(socket, request_id, 4041, "Live session not found")
                return
            result = self._session_snapshot(include_stored=True)
            if self._clarify is not None or self._native_request is not None:
                self._clarify_owner = socket, connection
        elif method == "prompt.submit":
            await self._rpc_result(socket, request_id, {"ok": True})
            await self._submit(socket, connection)
            return
        elif method == "clarify.lock":
            pending = self._native_request
            if pending is None or params.get("request_id") != pending["id"]:
                result = {"status": "expired"}
            else:
                qids = [q["qid"] for q in pending["params"].get("questions", [])]
                qid = params.get("question_id")
                if qid not in qids:
                    await self._rpc_error(socket, request_id, 4002, "unknown question_id")
                    return
                self._clarify_answers[qid] = params.get("answer", "")
                remaining = [q for q in qids if q not in self._clarify_answers]
                result = {"status": "ok", "remaining": remaining}
                if not remaining:
                    self._clarify_done.set()
        elif method == "clarify.respond":
            pending = self._clarify
            if pending is None or params.get("request_id") != pending["request_id"]:
                result = {"status": "expired"}
            else:
                qids = [q["qid"] for q in pending.get("questions", [])]
                qid = params.get("question_id")
                if qids and qid:
                    if qid not in qids:
                        await self._rpc_error(socket, request_id, 4002, "unknown question_id")
                        return
                    self._clarify_answers[qid] = params.get("answer", "")
                    remaining = [q for q in qids if q not in self._clarify_answers]
                    result = {"status": "ok", "remaining": remaining}
                    if not remaining:
                        self._clarify_done.set()
                else:
                    # Upstream's no-qid batch response cancels the whole request.
                    self._clarify_done.set()
                    result = {"status": "ok"}
        elif method == "session.interrupt":
            was_active = self._turn_active
            tasks = tuple(self._tasks)
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            self._running = False
            if was_active:
                await self._send_event(
                    socket, connection, "message.complete",
                    {"text": "", "status": "interrupted"}, self.scenario.live_session_id,
                )
            result = {"ok": True}
        elif method == "session.active_list" and self.scenario.active_list_supported:
            if self._active_list_snapshots:
                self._last_active_list_snapshot = self._active_list_snapshots.popleft()
            result = {"sessions": [dict(row) for row in self._last_active_list_snapshot]}
            self.evidence.add(
                "activity", connection=connection, method=method, outcome="snapshot",
            )
        else:
            await self._rpc_error(socket, request_id, -32601, f"Method not found: {method}")
            return
        await self._rpc_result(socket, request_id, result)

    def _session_snapshot(self, *, include_stored: bool) -> dict[str, Any]:
        snapshot: dict[str, Any] = {
            "session_id": self.scenario.live_session_id,
            "running": self._running,
            "info": {"profile_name": self.scenario.profile},
        }
        if include_stored:
            snapshot["stored_session_id"] = self.scenario.stored_session_id
        snapshot["open_requests"] = []
        if self._native_request is not None:
            frame = self._native_request
            snapshot["open_requests"] = [dict(frame, params=dict(frame["params"], answers=dict(self._clarify_answers)))]
        if self._clarify is not None:
            snapshot["pending_clarify"] = dict(self._clarify, answers=dict(self._clarify_answers))
            snapshot["info"]["pending_clarify"] = snapshot["pending_clarify"]
        if self._running:
            snapshot["inflight"] = {"user": "fixture turn", "assistant": "", "streaming": True}
        return snapshot

    async def _submit(self, socket: web.WebSocketResponse, connection: int) -> None:
        if not self._turns:
            self.evidence.add("turn", connection=connection, outcome="unexpected_submit")
            return
        turn = self._turns.popleft()
        if self._turn_active:
            self._queued.append(_QueuedTurn(turn, socket, connection))
            self.evidence.add("turn", connection=connection, outcome="queued")
            return
        self._start_turn(turn, socket, connection)

    def _start_turn(
        self,
        turn: dict[str, Any],
        socket: web.WebSocketResponse,
        connection: int,
    ) -> None:
        self._turn_active = True
        self._running = True
        self.evidence.add("turn", connection=connection, outcome="started")
        task = asyncio.create_task(self._run_steps(turn, socket, connection))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _run_steps(
        self,
        turn: dict[str, Any],
        socket: web.WebSocketResponse,
        connection: int,
    ) -> None:
        try:
            for step in turn["steps"]:
                operation = step["op"]
                if operation == "sleep":
                    await asyncio.sleep(step["milliseconds"] / 1_000)
                elif operation == "server_request":
                    if socket not in self._capable:
                        self.evidence.add("rpc", connection=connection, method=step["method"], outcome="legacy_client")
                        continue
                    self._native_request = {"id": step["id"], "method": step["method"],
                                            "params": dict(step["payload"], session_id=self.scenario.live_session_id)}
                    self._clarify_answers = {}
                    self._clarify_owner = socket, connection
                    self._clarify_done.clear()
                    await socket.send_json(dict(self._native_request, jsonrpc="2.0"))
                    self.evidence.add("rpc", connection=connection, method=step["method"], outcome="sent")
                    await self._clarify_done.wait()
                    socket, connection = self._clarify_owner
                    self._native_request = None
                elif operation == "clarify":
                    self._clarify = dict(step["payload"])
                    self._clarify_answers = {}
                    self._clarify_owner = socket, connection
                    self._clarify_done.clear()
                    await self._send_event(
                        socket, connection, "clarify.request", self._clarify,
                        self.scenario.live_session_id,
                    )
                    await self._clarify_done.wait()
                    socket, connection = self._clarify_owner
                    self._clarify = None
                elif operation == "set_running":
                    self._running = bool(step["value"])
                    self.evidence.add(
                        "runtime", connection=connection,
                        outcome="running" if self._running else "settled",
                    )
                elif operation == "persist":
                    rows = step.get("messages", [])
                    self._history_rows.extend(dict(row) for row in rows)
                    self.evidence.add("history", connection=connection, outcome="persisted")
                elif operation == "event":
                    scope = step.get("scope", "exact")
                    session_id = {
                        "exact": self.scenario.live_session_id,
                        "foreign": "fixture-foreign-session",
                        "unscoped": None,
                    }[scope]
                    await self._send_event(
                        socket, connection, step["type"], step.get("payload"), session_id,
                    )
                elif operation == "close":
                    await socket.close(
                        code=int(step.get("code", 1011)),
                        message=b"fixture-controlled socket gap",
                    )
                    self.evidence.add("fault", connection=connection, outcome="socket_gap")
        finally:
            if self._native_request is not None:
                await self._send_event(socket, connection, "request.cancel", {
                    "id": self._native_request["id"], "method": self._native_request["method"], "reason": "interrupted",
                }, self.scenario.live_session_id)
            self._native_request = None
            self._clarify = None
            self._clarify_owner = None
            self._turn_active = False
            if self._running:
                self._running = False
                self.evidence.add("runtime", connection=connection, outcome="settled")
            if self._queued and not self._closing:
                queued = self._queued.popleft()
                self._start_turn(queued.turn, queued.socket, queued.connection)

    async def _send_event(
        self,
        socket: web.WebSocketResponse,
        connection: int,
        event_type: str,
        payload: Any,
        session_id: str | None,
    ) -> None:
        params: dict[str, Any] = {"type": event_type}
        if payload is not None:
            params["payload"] = payload
        if session_id is not None:
            params["session_id"] = session_id
        scope = (
            "unscoped" if session_id is None
            else "exact" if session_id == self.scenario.live_session_id
            else "foreign"
        )
        self.evidence.add(
            "event", connection=connection, event_type=event_type, scope=scope, outcome="sent",
        )
        if not socket.closed:
            with suppress(ConnectionResetError):
                await socket.send_json({"jsonrpc": "2.0", "method": "event", "params": params})

    async def _rpc_result(
        self, socket: web.WebSocketResponse, request_id: Any, result: dict[str, Any],
    ) -> None:
        await socket.send_json({"jsonrpc": "2.0", "id": request_id, "result": result})

    async def _rpc_error(
        self, socket: web.WebSocketResponse, request_id: Any, code: int, message: str,
    ) -> None:
        await socket.send_json(
            {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}},
        )

    async def _sessions(self, request: web.Request) -> web.Response:
        profile = request.query.get("profile")
        if profile not in (None, self.scenario.profile):
            raise web.HTTPNotFound(text="profile not found")
        self.evidence.add("directory", outcome="listed")
        sessions = [{
            "id": self.scenario.stored_session_id,
            "title": "Fixture conversation", "source": "cli",
            "model": "fixture", "message_count": len(self._history_rows),
            "created_at": 1.0, "updated_at": 2.0,
        }] if self.scenario.dashboard_setup and self._history_rows else []
        return web.json_response(
            {
                "sessions": sessions,
                "pagination": {"limit": 50, "offset": 0, "returned": len(sessions)},
            },
        )

    async def _history(self, request: web.Request) -> web.Response:
        if request.match_info["session_id"] != self.scenario.stored_session_id:
            raise web.HTTPNotFound(text="session not found")
        limit = max(1, min(int(request.query.get("limit", "500")), 1_000))
        offset = max(0, int(request.query.get("offset", "0")))
        order = request.query.get("order", "asc")
        rows = list(self._history_rows)
        if order == "desc":
            rows.reverse()
        page = rows[offset : offset + limit]
        self.evidence.add("history", outcome="read")
        return web.json_response(
            {
                "session_id": self.scenario.stored_session_id,
                "messages": page,
                "pagination": {
                    "limit": limit, "offset": offset, "order": order, "returned": len(page),
                },
            },
        )

    async def _state(self, _request: web.Request) -> web.Response:
        return web.json_response(
            {
                "scenario": self.scenario.name,
                "running": self._running,
                "remaining_turns": len(self._turns),
                "queued_turns": len(self._queued),
                "history_rows": len(self._history_rows),
                "profile": self.scenario.profile,
                "remaining_active_list_snapshots": len(self._active_list_snapshots),
            },
        )

    async def _evidence(self, _request: web.Request) -> web.Response:
        return web.json_response(self.evidence.export(self.scenario.name))
