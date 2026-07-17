"""Security regression tests for operator-authorized Relay pairing."""

from __future__ import annotations

import math
import os
import tempfile
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.auth import Session
from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class PairingAuthorityTests(AioHTTPTestCase):
    async def asyncSetUp(self) -> None:
        self._hermes_home = tempfile.TemporaryDirectory()
        self._env_patch = mock.patch.dict(
            os.environ,
            {"HERMES_HOME": self._hermes_home.name},
        )
        self._env_patch.start()
        await super().asyncSetUp()

    async def asyncTearDown(self) -> None:
        await super().asyncTearDown()
        self._env_patch.stop()
        self._hermes_home.cleanup()

    async def get_application(self) -> web.Application:
        return create_app(RelayConfig(profile_discovery_enabled=False))

    async def _authenticate(
        self,
        code: str,
        *,
        ttl_seconds: int,
        grants: dict[str, int],
    ) -> Session:
        ws = await self.client.ws_connect("/ws")
        await ws.send_json(
            {
                "channel": "system",
                "type": "auth",
                "payload": {
                    "pairing_code": code,
                    "device_name": "security-regression",
                    "device_id": f"security-regression-{code}",
                    "ttl_seconds": ttl_seconds,
                    "grants": grants,
                },
            }
        )
        message = await ws.receive_json()
        await ws.close()
        self.assertEqual(message["type"], "auth.ok")
        token = message["payload"]["session_token"]
        session = self.app["server"].sessions.get_session(token)
        self.assertIsNotNone(session)
        assert session is not None
        return session

    async def test_anonymous_pairing_code_mint_is_not_exposed(self) -> None:
        server = self.app["server"]

        response = await self.client.post("/pairing")

        self.assertEqual(response.status, 404)
        self.assertEqual(server.pairing._codes, {})
        self.assertEqual(server.sessions.active_count(), 0)
        self.assertEqual(server.sessions._trusted_devices, {})

    async def test_client_policy_is_ignored_when_host_metadata_is_absent(
        self,
    ) -> None:
        response = await self.client.post(
            "/pairing/register",
            json={"code": "SAFE01"},
        )
        self.assertEqual(response.status, 200, await response.text())

        session = await self._authenticate(
            "SAFE01",
            ttl_seconds=0,
            grants={"terminal": 0, "bridge": 0},
        )

        self.assertFalse(math.isinf(session.expires_at))
        self.assertFalse(math.isinf(session.grants["terminal"]))
        self.assertFalse(math.isinf(session.grants["bridge"]))

    async def test_explicit_host_policy_remains_authoritative(self) -> None:
        response = await self.client.post(
            "/pairing/register",
            json={
                "code": "SAFE02",
                "ttl_seconds": 0,
                "grants": {"terminal": 0},
            },
        )
        self.assertEqual(response.status, 200, await response.text())

        session = await self._authenticate(
            "SAFE02",
            ttl_seconds=60,
            grants={"terminal": 60},
        )

        self.assertTrue(math.isinf(session.expires_at))
        self.assertTrue(math.isinf(session.grants["terminal"]))
