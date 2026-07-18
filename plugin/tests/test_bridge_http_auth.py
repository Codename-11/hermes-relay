"""Security regression tests for Relay HTTP-to-Android bridge authorization."""

from __future__ import annotations

import os
import tempfile
import time
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class BridgeHttpAuthorizationTests(AioHTTPTestCase):
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

    def _session(self) -> object:
        return self.app["server"].sessions.create_session(
            "bridge-security-test",
            "bridge-security-test",
        )

    @staticmethod
    def _bearer(token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    async def test_anonymous_and_invalid_callers_are_rejected_before_dispatch(self) -> None:
        dispatch = mock.AsyncMock(return_value={"status": 200, "result": {"ok": True}})
        self.app["server"].bridge.handle_command = dispatch

        anonymous = await self.client.get("/screen?include_bounds=true")
        invalid = await self.client.post(
            "/tap",
            json={"x": 1, "y": 2},
            headers=self._bearer("invalid-token"),
        )

        self.assertEqual(anonymous.status, 401)
        self.assertEqual(invalid.status, 401)
        dispatch.assert_not_awaited()

    async def test_missing_and_expired_bridge_grants_are_rejected(self) -> None:
        dispatch = mock.AsyncMock(return_value={"status": 200, "result": {"ok": True}})
        self.app["server"].bridge.handle_command = dispatch

        missing = self._session()
        missing.grants.pop("bridge")
        expired = self._session()
        expired.grants["bridge"] = time.time() - 1

        missing_response = await self.client.get(
            "/screen", headers=self._bearer(missing.token)
        )
        expired_response = await self.client.post(
            "/tap", json={"x": 1, "y": 2}, headers=self._bearer(expired.token)
        )

        self.assertEqual(missing_response.status, 403)
        self.assertEqual(expired_response.status, 403)
        dispatch.assert_not_awaited()

    async def test_active_bridge_grant_preserves_request_and_device_selector(self) -> None:
        dispatch = mock.AsyncMock(
            return_value={"status": 200, "result": {"ok": True}}
        )
        self.app["server"].bridge.handle_command = dispatch
        session = self._session()

        response = await self.client.post(
            "/tap?device=phone",
            json={"x": 10, "y": 20},
            headers=self._bearer(session.token),
        )

        self.assertEqual(response.status, 200)
        self.assertEqual(await response.json(), {"ok": True})
        dispatch.assert_awaited_once_with(
            method="POST",
            path="/tap",
            params={},
            body={"x": 10, "y": 20},
            device="phone",
        )
