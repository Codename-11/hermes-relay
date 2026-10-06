"""HTTP tests for POST /push/token."""

from __future__ import annotations

import os
import tempfile
import unittest
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class PushTokenHttpTests(AioHTTPTestCase):
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

    def _session(self):
        return self.app["server"].sessions.create_session("fcm-phone", "device-fcm-1")

    async def test_anonymous_rejected(self) -> None:
        resp = await self.client.post("/push/token", json={"token": "abc"})
        self.assertEqual(resp.status, 401)

    async def test_register_and_clear(self) -> None:
        session = self._session()
        headers = {"Authorization": f"Bearer {session.token}"}
        ok = await self.client.post(
            "/push/token",
            json={"token": "fcm-device-token", "platform": "android", "project_id": "p"},
            headers=headers,
        )
        self.assertEqual(ok.status, 200)
        body = await ok.json()
        self.assertTrue(body.get("ok"))
        tokens = self.app["server"].fcm_tokens.tokens()
        self.assertEqual(tokens, ["fcm-device-token"])
        regs = self.app["server"].fcm_tokens.registrations()
        self.assertTrue(regs[0]["include_preview"])

        hide = await self.client.post(
            "/push/token",
            json={
                "token": "fcm-device-token",
                "platform": "android",
                "project_id": "p",
                "include_preview": False,
            },
            headers=headers,
        )
        self.assertEqual(hide.status, 200)
        self.assertFalse(
            self.app["server"].fcm_tokens.registrations()[0]["include_preview"]
        )

        cleared = await self.client.post(
            "/push/token",
            json={"token": ""},
            headers=headers,
        )
        self.assertEqual(cleared.status, 200)
        self.assertEqual(self.app["server"].fcm_tokens.tokens(), [])


if __name__ == "__main__":
    unittest.main()
