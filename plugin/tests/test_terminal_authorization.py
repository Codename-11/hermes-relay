"""Authorization tests for terminal WebSocket dispatch."""

from __future__ import annotations

import asyncio
import json
import time
import unittest
from unittest.mock import AsyncMock

from plugin.relay.config import RelayConfig
from plugin.relay.server import RelayServer, _on_message


class _FakeWebSocket:
    def __init__(self) -> None:
        self.closed = False
        self.sent: list[dict[str, object]] = []

    async def send_str(self, raw: str) -> None:
        self.sent.append(json.loads(raw))

    async def close(self, **_kwargs: object) -> None:
        self.closed = True


class TerminalAuthorizationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.server = RelayServer(RelayConfig())
        self.server.terminal.handle = AsyncMock()
        self.ws = _FakeWebSocket()
        self.server._client_tasks[self.ws] = set()
        self.session = self.server.sessions.create_session(
            "terminal-test",
            "terminal-test-id",
            grants={"terminal": 3600},
        )
        self.server._clients[self.ws] = self.session.token

    async def asyncTearDown(self) -> None:
        for task in self.server._client_tasks.get(self.ws, set()):
            task.cancel()
        await asyncio.gather(
            *self.server._client_tasks.get(self.ws, set()),
            return_exceptions=True,
        )
        await self.server.close()

    async def _dispatch(self, msg_type: str = "terminal.attach") -> None:
        await _on_message(
            self.ws,
            self.server,
            json.dumps(
                {
                    "channel": "terminal",
                    "type": msg_type,
                    "id": "terminal-request",
                    "payload": {},
                }
            ),
        )
        await asyncio.sleep(0)

    def _assert_authorization_error(self) -> None:
        self.server.terminal.handle.assert_not_awaited()
        self.assertEqual(len(self.ws.sent), 1)
        self.assertEqual(self.ws.sent[0]["channel"], "system")
        self.assertEqual(self.ws.sent[0]["type"], "error")
        self.assertEqual(self.ws.sent[0]["id"], "terminal-request")
        self.assertIn("Terminal grant", self.ws.sent[0]["payload"]["message"])

    async def test_missing_terminal_grant_rejects_every_terminal_action(self) -> None:
        self.session.grants.pop("terminal")

        for msg_type in (
            "terminal.attach",
            "terminal.input",
            "terminal.resize",
            "terminal.list",
            "terminal.detach",
            "terminal.kill",
        ):
            with self.subTest(msg_type=msg_type):
                self.ws.sent.clear()
                self.server.terminal.handle.reset_mock()
                await self._dispatch(msg_type)
                self._assert_authorization_error()

    async def test_expired_terminal_grant_is_rejected(self) -> None:
        self.session.grants["terminal"] = time.time() - 1

        await self._dispatch()

        self._assert_authorization_error()

    async def test_revoked_session_on_connected_socket_is_rejected(self) -> None:
        self.server.sessions.revoke_session(self.session.token)

        await self._dispatch()

        self._assert_authorization_error()

    async def test_current_terminal_grant_dispatches_to_handler(self) -> None:
        await self._dispatch()

        self.server.terminal.handle.assert_awaited_once()
        self.assertEqual(self.ws.sent, [])


if __name__ == "__main__":
    unittest.main()
