"""Tests for /media/register and /media/{token} aiohttp routes.

Uses ``aiohttp.test_utils.AioHTTPTestCase`` — this base class ships with
aiohttp (already a runtime dependency) and integrates with unittest / pytest
without needing pytest-aiohttp.

The loopback-only gate on ``/media/register`` is normally enforced by
checking ``request.remote`` against ``("127.0.0.1", "::1")``. The aiohttp
test client produces fake peers with ``remote="127.0.0.1"`` when using
``TestServer``, so the gate passes naturally in-process.
"""

from __future__ import annotations

import os
import shutil
import tempfile
import time
import unittest

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


def _write_file(root: str, name: str, content: bytes = b"hello-bytes") -> str:
    path = os.path.join(root, name)
    with open(path, "wb") as fh:
        fh.write(content)
    return path


class RelayMediaRoutesTests(AioHTTPTestCase):
    """Exercises the /media/* routes end-to-end against a live test server."""

    async def get_application(self) -> web.Application:
        self._sandbox = tempfile.mkdtemp(prefix="hermes_relay_routes_")
        self._cleanup_paths: list[str] = [self._sandbox]

        config = RelayConfig()
        # Only allow the sandbox — we'll pin this after app creation too.
        config.media_allowed_roots = [self._sandbox]

        app = create_app(config)

        # Pin allowed_roots so no auto-derived tmp root leaks in.
        server = app["server"]
        server.media.allowed_roots = [os.path.realpath(self._sandbox)]
        return app

    async def tearDownAsync(self) -> None:
        await super().tearDownAsync()
        for path in getattr(self, "_cleanup_paths", []):
            shutil.rmtree(path, ignore_errors=True)

    # ── Helpers ─────────────────────────────────────────────────────────

    def _server(self):
        return self.app["server"]

    async def _create_session_token(self) -> str:
        """Mint a valid relay session directly via SessionManager."""
        session = self._server().sessions.create_session("test-device", "test-id")
        return session.token

    # ── /media/register ────────────────────────────────────────────────

    async def test_register_rejects_non_loopback(self) -> None:
        """Call the handler directly with a forged non-loopback peer.

        aiohttp's test client is hard-wired to 127.0.0.1, so the only
        clean way to exercise the forbidden branch is to invoke the
        handler function with a lightweight request stand-in that
        exposes the attributes the handler actually reads (``remote``
        and ``app``). This mirrors how the upstream ``/pairing/register``
        handler is structured — both read ``request.remote`` directly.
        """
        from plugin.relay.server import handle_media_register

        class _FakeRequest:
            remote = "203.0.113.10"  # TEST-NET-3, non-loopback
            app = self.app

            async def json(self):
                return {}

        with self.assertRaises(web.HTTPForbidden):
            await handle_media_register(_FakeRequest())

    async def test_register_happy_path_returns_token(self) -> None:
        path = _write_file(self._sandbox, "shot.jpg", content=b"\xff\xd8\xff")
        resp = await self.client.post(
            "/media/register",
            json={
                "path": path,
                "content_type": "image/jpeg",
                "file_name": "shot.jpg",
            },
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["ok"])
        self.assertIn("token", body)
        self.assertIn("expires_at", body)
        self.assertTrue(body["token"])

    async def test_register_validation_error_returns_400(self) -> None:
        resp = await self.client.post(
            "/media/register",
            json={
                "path": "/nonexistent/file.png",
                "content_type": "image/png",
            },
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertFalse(body["ok"])
        self.assertIn("error", body)

    async def test_register_invalid_json_returns_400(self) -> None:
        resp = await self.client.post(
            "/media/register",
            data="not json",
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(resp.status, 400)

    # ── /media/{token} auth ────────────────────────────────────────────

    async def test_fetch_without_authorization_returns_401(self) -> None:
        # First register a real token so we know the 401 comes from auth
        # rather than from token lookup.
        path = _write_file(self._sandbox, "a.bin", content=b"data")
        entry = await self._server().media.register(
            path, "application/octet-stream", file_name="a.bin"
        )

        resp = await self.client.get(f"/media/{entry.token}")
        self.assertEqual(resp.status, 401)

    async def test_fetch_with_invalid_bearer_returns_401(self) -> None:
        path = _write_file(self._sandbox, "b.bin", content=b"data")
        entry = await self._server().media.register(
            path, "application/octet-stream"
        )

        resp = await self.client.get(
            f"/media/{entry.token}",
            headers={"Authorization": "Bearer not-a-real-token"},
        )
        self.assertEqual(resp.status, 401)

    async def test_fetch_with_valid_bearer_streams_bytes(self) -> None:
        contents = b"\x89PNG\r\n\x1a\n-fake-png-body-"
        path = _write_file(self._sandbox, "pic.png", content=contents)
        entry = await self._server().media.register(
            path, "image/png", file_name="pic.png"
        )
        token_bearer = await self._create_session_token()

        resp = await self.client.get(
            f"/media/{entry.token}",
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.headers.get("Content-Type"), "image/png")
        # Content-Disposition with the file_name
        cd = resp.headers.get("Content-Disposition", "")
        self.assertIn("inline", cd)
        self.assertIn("pic.png", cd)

        body = await resp.read()
        self.assertEqual(body, contents)

    async def test_fetch_expired_token_returns_404(self) -> None:
        path = _write_file(self._sandbox, "gone.bin", content=b"x")
        entry = await self._server().media.register(
            path, "application/octet-stream"
        )
        # Force expiry under the lock
        async with self._server().media._lock:
            self._server().media._entries[entry.token].expires_at = time.time() - 60

        token_bearer = await self._create_session_token()
        resp = await self.client.get(
            f"/media/{entry.token}",
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 404)

    async def test_fetch_unknown_token_returns_404(self) -> None:
        token_bearer = await self._create_session_token()
        resp = await self.client.get(
            "/media/this-token-does-not-exist",
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 404)


if __name__ == "__main__":
    unittest.main()
