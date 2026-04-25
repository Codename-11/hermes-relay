"""Tests for the Phase 1 TUI channel handler.

Validated surface:
  * ``tui.attach`` spawns a subprocess (mocked) and emits ``tui.attached``
    with pid + server_version.
  * ``tui.rpc.request`` forwards a JSON-RPC object to subprocess stdin as
    a single newline-terminated line.
  * Subprocess stdout lines are parsed and emitted as ``tui.rpc.response``
    when they carry an ``id`` field, and as ``tui.rpc.event`` when they
    use ``method: "event"``.
  * Response correlation — the id on the subprocess reply flows through
    unchanged in the outbound envelope payload.
  * ``tui.detach`` SIGTERMs the subprocess; client disconnect (``detach_ws``)
    kills the subprocess too.
  * Malformed envelope (bad payload shape) emits ``tui.error`` and leaves
    the subprocess untouched.
  * Malformed stdout lines are dropped without blowing up the pump.

Runs under plain ``unittest`` so it skips the repo's ``conftest.py``
(which imports ``responses`` — not always installed in this venv). Match
the pattern used by ``test_bridge_channel.py``.

Run with::

    python -m unittest plugin.tests.test_tui_channel
"""

from __future__ import annotations

import asyncio
import json
import signal
import unittest
from typing import Any
from unittest.mock import AsyncMock, MagicMock

from plugin.relay.channels.tui import TuiHandler


class _FakeWs:
    """Minimal stand-in for ``aiohttp.web.WebSocketResponse``."""

    def __init__(self) -> None:
        self.sent: list[dict[str, Any]] = []
        self.closed: bool = False

    async def send_str(self, payload: str) -> None:
        if self.closed:
            raise ConnectionResetError("ws closed")
        self.sent.append(json.loads(payload))


class _FakeStream:
    """Bytes queue standing in for an asyncio stream reader/writer pair.

    Supports ``readline`` (returns queued bytes, b"" on EOF once closed),
    ``write`` (appends to an in-memory buffer), and ``drain`` (no-op).
    """

    def __init__(self) -> None:
        self._queue: asyncio.Queue[bytes] = asyncio.Queue()
        self._eof = False
        self.written: list[bytes] = []

    async def readline(self) -> bytes:
        if self._eof and self._queue.empty():
            return b""
        item = await self._queue.get()
        if item == b"":
            self._eof = True
        return item

    def feed(self, line: bytes) -> None:
        self._queue.put_nowait(line)

    def feed_eof(self) -> None:
        self._queue.put_nowait(b"")

    def write(self, data: bytes) -> None:
        self.written.append(data)

    async def drain(self) -> None:
        return None


class _FakeProcess:
    """Stand-in for ``asyncio.subprocess.Process``.

    Tracks signals sent and remembers a ``returncode`` so ``wait()`` can
    complete. By default SIGTERM flips returncode to ``-signal.SIGTERM``
    which is what a real child would report.
    """

    def __init__(self, pid: int = 4242) -> None:
        self.pid = pid
        self.returncode: int | None = None
        self.stdin = _FakeStream()
        self.stdout = _FakeStream()
        self.stderr = _FakeStream()
        self.sent_signals: list[int] = []
        self.killed = False
        # Auto-exit on SIGTERM by default; tests that want to simulate a
        # stubborn process can flip this to False and then call kill()
        # themselves.
        self.die_on_sigterm = True
        self._wait_event = asyncio.Event()

    def send_signal(self, sig: int) -> None:
        self.sent_signals.append(sig)
        if sig == signal.SIGTERM and self.die_on_sigterm:
            self._exit(-sig)

    def kill(self) -> None:
        self.killed = True
        self.sent_signals.append(signal.SIGKILL)
        self._exit(-signal.SIGKILL)

    def _exit(self, code: int) -> None:
        if self.returncode is None:
            self.returncode = code
            self.stdout.feed_eof()
            self.stderr.feed_eof()
            self._wait_event.set()

    async def wait(self) -> int:
        await self._wait_event.wait()
        assert self.returncode is not None
        return self.returncode


def _run(coro):
    """Drive a coroutine to completion on a fresh event loop."""
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coro)
    finally:
        loop.close()


async def _spawn_with_fake(handler: TuiHandler, ws: _FakeWs, proc: _FakeProcess):
    """Run ``tui.attach`` with ``asyncio.create_subprocess_exec`` stubbed."""
    create_sub = AsyncMock(return_value=proc)
    import plugin.relay.channels.tui as tui_mod

    original = tui_mod.asyncio.create_subprocess_exec
    tui_mod.asyncio.create_subprocess_exec = create_sub  # type: ignore[assignment]
    try:
        await handler.handle(
            ws,
            {
                "channel": "tui",
                "type": "tui.attach",
                "id": "evt-attach",
                "payload": {"cols": 80, "rows": 24},
            },
        )
    finally:
        tui_mod.asyncio.create_subprocess_exec = original  # type: ignore[assignment]
    return create_sub


class TuiHandlerAttachTests(unittest.TestCase):
    def test_attach_spawns_subprocess_and_sends_tui_attached(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess(pid=9999)
            create_sub = await _spawn_with_fake(handler, ws, proc)

            # Yield so the pumps start; not strictly required for attach
            # but keeps the event loop consistent.
            await asyncio.sleep(0)

            # Subprocess spawn was invoked with -m tui_gateway.entry.
            self.assertEqual(create_sub.call_count, 1)
            args, kwargs = create_sub.call_args
            self.assertIn("-m", args)
            self.assertIn("tui_gateway.entry", args)
            self.assertIn("env", kwargs)
            self.assertIn("PYTHONPATH", kwargs["env"])

            # Outbound tui.attached envelope.
            self.assertEqual(len(ws.sent), 1)
            attached = ws.sent[0]
            self.assertEqual(attached["channel"], "tui")
            self.assertEqual(attached["type"], "tui.attached")
            self.assertEqual(attached["payload"]["pid"], 9999)
            self.assertIn("server_version", attached["payload"])

            await handler.detach_ws(ws, reason="test cleanup")

        _run(run())

    def test_attach_profile_and_resume_propagate_to_env(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess()

            create_sub = AsyncMock(return_value=proc)
            import plugin.relay.channels.tui as tui_mod

            original = tui_mod.asyncio.create_subprocess_exec
            tui_mod.asyncio.create_subprocess_exec = create_sub  # type: ignore[assignment]
            try:
                await handler.handle(
                    ws,
                    {
                        "channel": "tui",
                        "type": "tui.attach",
                        "id": "evt",
                        "payload": {
                            "cols": 100,
                            "rows": 40,
                            "profile": "mizu",
                            "resume_session_id": "sess-123",
                        },
                    },
                )
            finally:
                tui_mod.asyncio.create_subprocess_exec = original  # type: ignore[assignment]

            _, kwargs = create_sub.call_args
            env = kwargs["env"]
            self.assertEqual(env.get("HERMES_PROFILE"), "mizu")
            self.assertEqual(env.get("HERMES_TUI_RESUME"), "sess-123")

            await handler.detach_ws(ws, reason="test cleanup")

        _run(run())


class TuiHandlerRpcTests(unittest.TestCase):
    def test_rpc_request_forwarded_to_subprocess_stdin(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess()
            await _spawn_with_fake(handler, ws, proc)
            await asyncio.sleep(0)

            rpc = {"jsonrpc": "2.0", "id": "req-1", "method": "ping", "params": {}}
            await handler.handle(
                ws,
                {
                    "channel": "tui",
                    "type": "tui.rpc.request",
                    "id": "evt-req-1",
                    "payload": rpc,
                },
            )

            # Exactly one write, trailing newline, exact bytes.
            self.assertEqual(len(proc.stdin.written), 1)
            written = proc.stdin.written[0].decode("utf-8")
            self.assertTrue(written.endswith("\n"))
            decoded = json.loads(written)
            self.assertEqual(decoded, rpc)

            await handler.detach_ws(ws, reason="test cleanup")

        _run(run())

    def test_stdout_response_emits_tui_rpc_response_with_id(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess()
            await _spawn_with_fake(handler, ws, proc)
            await asyncio.sleep(0)

            # Feed a JSON-RPC response line to subprocess stdout.
            response = {"jsonrpc": "2.0", "id": "req-1", "result": {"pong": True}}
            proc.stdout.feed((json.dumps(response) + "\n").encode("utf-8"))

            # Allow the pump to consume the line.
            for _ in range(5):
                await asyncio.sleep(0)

            # We should have tui.attached + tui.rpc.response.
            rpc_envs = [e for e in ws.sent if e["type"] == "tui.rpc.response"]
            self.assertEqual(len(rpc_envs), 1)
            self.assertEqual(rpc_envs[0]["payload"], response)
            # The id echoed on the inner payload (correlation contract).
            self.assertEqual(rpc_envs[0]["payload"]["id"], "req-1")

            await handler.detach_ws(ws, reason="test cleanup")

        _run(run())

    def test_stdout_event_emits_tui_rpc_event(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess()
            await _spawn_with_fake(handler, ws, proc)
            await asyncio.sleep(0)

            event = {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {"type": "gateway.ready", "payload": {}},
            }
            proc.stdout.feed((json.dumps(event) + "\n").encode("utf-8"))

            for _ in range(5):
                await asyncio.sleep(0)

            rpc_envs = [e for e in ws.sent if e["type"] == "tui.rpc.event"]
            self.assertEqual(len(rpc_envs), 1)
            self.assertEqual(rpc_envs[0]["payload"], event)

            await handler.detach_ws(ws, reason="test cleanup")

        _run(run())

    def test_malformed_stdout_line_dropped_without_crashing_pump(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess()
            await _spawn_with_fake(handler, ws, proc)
            await asyncio.sleep(0)

            proc.stdout.feed(b"not-json garbage\n")
            # Follow up with a real event so we can prove the pump survived.
            event = {"jsonrpc": "2.0", "method": "event", "params": {}}
            proc.stdout.feed((json.dumps(event) + "\n").encode("utf-8"))

            for _ in range(10):
                await asyncio.sleep(0)

            # Only the event got through; the garbage line was dropped.
            rpc_envs = [e for e in ws.sent if e["type"] == "tui.rpc.event"]
            self.assertEqual(len(rpc_envs), 1)
            await handler.detach_ws(ws, reason="test cleanup")

        _run(run())


class TuiHandlerTeardownTests(unittest.TestCase):
    def test_detach_envelope_terminates_subprocess(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess()
            await _spawn_with_fake(handler, ws, proc)
            await asyncio.sleep(0)

            await handler.handle(
                ws,
                {
                    "channel": "tui",
                    "type": "tui.detach",
                    "id": "evt-detach",
                    "payload": {},
                },
            )

            self.assertIn(signal.SIGTERM, proc.sent_signals)
            self.assertIsNotNone(proc.returncode)
            # Session dropped.
            self.assertNotIn(ws, handler._sessions)

        _run(run())

    def test_client_disconnect_via_detach_ws_kills_subprocess(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess()
            await _spawn_with_fake(handler, ws, proc)
            await asyncio.sleep(0)

            # Simulate the main server calling detach_ws on disconnect.
            await handler.detach_ws(ws, reason="client disconnect")
            self.assertIn(signal.SIGTERM, proc.sent_signals)
            self.assertNotIn(ws, handler._sessions)

        _run(run())

    def test_sigterm_grace_escalates_to_sigkill(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            # Proc ignores SIGTERM — tests the grace-period escalation.
            proc = _FakeProcess()
            proc.die_on_sigterm = False

            await _spawn_with_fake(handler, ws, proc)
            await asyncio.sleep(0)

            # Shrink the grace window so the test isn't slow.
            import plugin.relay.channels.tui as tui_mod

            original = tui_mod.SHUTDOWN_GRACE_SECONDS
            tui_mod.SHUTDOWN_GRACE_SECONDS = 0.05
            try:
                await handler.detach_ws(ws, reason="stubborn proc test")
            finally:
                tui_mod.SHUTDOWN_GRACE_SECONDS = original

            self.assertIn(signal.SIGTERM, proc.sent_signals)
            self.assertIn(signal.SIGKILL, proc.sent_signals)
            self.assertTrue(proc.killed)

        _run(run())

    def test_close_tears_down_every_session(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws1 = _FakeWs()
            ws2 = _FakeWs()
            proc1 = _FakeProcess(pid=1)
            proc2 = _FakeProcess(pid=2)
            await _spawn_with_fake(handler, ws1, proc1)
            await _spawn_with_fake(handler, ws2, proc2)

            await handler.close()
            self.assertEqual(handler._sessions, {})
            self.assertIsNotNone(proc1.returncode)
            self.assertIsNotNone(proc2.returncode)

        _run(run())


class TuiHandlerErrorTests(unittest.TestCase):
    def test_malformed_envelope_payload_emits_tui_error(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            # Payload is an array, not an object — must be rejected.
            await handler.handle(
                ws,
                {
                    "channel": "tui",
                    "type": "tui.attach",
                    "id": "evt-bad",
                    "payload": ["not", "an", "object"],
                },
            )
            self.assertEqual(len(ws.sent), 1)
            self.assertEqual(ws.sent[0]["type"], "tui.error")
            self.assertIn("message", ws.sent[0]["payload"])
            # No session was spawned.
            self.assertEqual(handler._sessions, {})

        _run(run())

    def test_rpc_without_attach_emits_tui_error(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            await handler.handle(
                ws,
                {
                    "channel": "tui",
                    "type": "tui.rpc.request",
                    "id": "evt",
                    "payload": {"jsonrpc": "2.0", "id": "r1", "method": "ping"},
                },
            )
            self.assertEqual(len(ws.sent), 1)
            self.assertEqual(ws.sent[0]["type"], "tui.error")

        _run(run())

    def test_unknown_message_type_emits_tui_error(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            await handler.handle(
                ws,
                {
                    "channel": "tui",
                    "type": "tui.bogus",
                    "id": "evt",
                    "payload": {},
                },
            )
            self.assertEqual(len(ws.sent), 1)
            self.assertEqual(ws.sent[0]["type"], "tui.error")
            self.assertIn("tui.bogus", ws.sent[0]["payload"]["message"])

        _run(run())


class TuiHandlerResizeTests(unittest.TestCase):
    def test_resize_forwards_as_json_rpc_request(self) -> None:
        async def run() -> None:
            handler = TuiHandler()
            ws = _FakeWs()
            proc = _FakeProcess()
            await _spawn_with_fake(handler, ws, proc)
            await asyncio.sleep(0)

            await handler.handle(
                ws,
                {
                    "channel": "tui",
                    "type": "tui.resize",
                    "id": "evt-resize",
                    "payload": {"cols": 120, "rows": 40},
                },
            )

            self.assertEqual(len(proc.stdin.written), 1)
            payload = json.loads(proc.stdin.written[0])
            self.assertEqual(payload["method"], TuiHandler.RESIZE_METHOD)
            self.assertEqual(payload["params"], {"cols": 120, "rows": 40})
            self.assertEqual(payload["jsonrpc"], "2.0")
            self.assertIn("id", payload)

            await handler.detach_ws(ws, reason="test cleanup")

        _run(run())


if __name__ == "__main__":
    unittest.main()
