"""Security tests for ``GET /api/profiles/{name}/config``."""

from __future__ import annotations

import tempfile
import time
import unittest
from pathlib import Path

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class ProfileConfigEndpointTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.hermes_dir = Path(self._tmp.name)
        config_path = self.hermes_dir / "config.yaml"
        config_path.write_text(
            "\n".join(
                [
                    "description: Safe profile description",
                    "model:",
                    "  default: safe-model",
                    "platforms:",
                    "  api_server:",
                    "    api_key: POC_API_SERVER_KEY_DO_NOT_USE",
                    "providers:",
                    "  synthetic:",
                    "    api_key: POC_PROVIDER_KEY_DO_NOT_USE",
                    "extensions:",
                    "  future:",
                    "    nested:",
                    "      credential: POC_UNKNOWN_SECRET_DO_NOT_USE",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        app = create_app(RelayConfig(hermes_config_path=str(config_path)))

        @web.middleware
        async def force_remote_client(
            request: web.Request,
            handler: web.RequestHandler,
        ) -> web.StreamResponse:
            return await handler(request.clone(remote="198.51.100.27"))

        app.middlewares.append(force_remote_client)
        self.session = app["server"].sessions.create_session(
            "profile-reader", "test-device", ttl_seconds=3600
        )
        self.session.grants = {"chat": time.time() + 3600}
        return app

    async def test_remote_profile_config_returns_only_public_schema(self) -> None:
        response = await self.client.get(
            "/api/profiles/default/config",
            headers={"Authorization": f"Bearer {self.session.token}"},
        )

        self.assertEqual(response.status, 200)
        body = await response.json()
        self.assertEqual(body["profile"], "default")
        self.assertEqual(body["path"], "config.yaml")
        self.assertEqual(
            body["config"],
            {
                "description": "Safe profile description",
                "model": {"default": "safe-model"},
            },
        )
        serialized = await response.text()
        self.assertNotIn("POC_API_SERVER_KEY_DO_NOT_USE", serialized)
        self.assertNotIn("POC_PROVIDER_KEY_DO_NOT_USE", serialized)
        self.assertNotIn("POC_UNKNOWN_SECRET_DO_NOT_USE", serialized)
        self.assertNotIn(str(self.hermes_dir), serialized)

    async def test_remote_profile_config_still_requires_bearer(self) -> None:
        response = await self.client.get("/api/profiles/default/config")

        self.assertEqual(response.status, 401)


class ProfileConfigLoopbackEndpointTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.config_path = Path(self._tmp.name) / "config.yaml"
        self.config_path.write_text(
            "model:\n  default: local-model\ncustom:\n  enabled: true\n",
            encoding="utf-8",
        )
        return create_app(
            RelayConfig(hermes_config_path=str(self.config_path))
        )

    async def test_loopback_operator_retains_full_config_view(self) -> None:
        response = await self.client.get("/api/profiles/default/config")

        self.assertEqual(response.status, 200)
        body = await response.json()
        self.assertEqual(body["path"], str(self.config_path))
        self.assertEqual(body["config"]["custom"], {"enabled": True})


if __name__ == "__main__":
    unittest.main()
