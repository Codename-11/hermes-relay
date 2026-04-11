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

    # ── /media/by-path ─────────────────────────────────────────────────

    async def test_by_path_without_authorization_returns_401(self) -> None:
        path = _write_file(self._sandbox, "bp1.jpg", content=b"\xff\xd8")
        resp = await self.client.get("/media/by-path", params={"path": path})
        self.assertEqual(resp.status, 401)

    async def test_by_path_with_invalid_bearer_returns_401(self) -> None:
        path = _write_file(self._sandbox, "bp2.jpg", content=b"\xff\xd8")
        resp = await self.client.get(
            "/media/by-path",
            params={"path": path},
            headers={"Authorization": "Bearer nope"},
        )
        self.assertEqual(resp.status, 401)

    async def test_by_path_missing_path_param_returns_400(self) -> None:
        token_bearer = await self._create_session_token()
        resp = await self.client.get(
            "/media/by-path",
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertFalse(body["ok"])
        self.assertIn("path", body["error"])

    async def test_by_path_outside_sandbox_returns_403(self) -> None:
        # Create a file in a totally unrelated tmpdir and try to fetch it.
        # Path is valid/exists but outside allowed_roots — expect 403.
        outside = tempfile.mkdtemp(prefix="hermes_outside_")
        self._cleanup_paths.append(outside)
        outside_file = _write_file(outside, "secret.txt", content=b"nope")
        token_bearer = await self._create_session_token()

        resp = await self.client.get(
            "/media/by-path",
            params={"path": outside_file},
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 403)

    async def test_by_path_nonexistent_in_sandbox_returns_404(self) -> None:
        token_bearer = await self._create_session_token()
        missing = os.path.join(self._sandbox, "not-there.jpg")
        resp = await self.client.get(
            "/media/by-path",
            params={"path": missing},
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 404)

    async def test_by_path_relative_path_returns_403(self) -> None:
        token_bearer = await self._create_session_token()
        resp = await self.client.get(
            "/media/by-path",
            params={"path": "relative/path.jpg"},
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 403)

    async def test_by_path_happy_path_streams_bytes(self) -> None:
        contents = b"\x89PNG\r\n\x1a\nby-path-body"
        path = _write_file(self._sandbox, "screen.png", content=contents)
        token_bearer = await self._create_session_token()

        resp = await self.client.get(
            "/media/by-path",
            params={"path": path},
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 200)
        # Content-Type auto-guessed from .png extension
        self.assertEqual(resp.headers.get("Content-Type"), "image/png")
        cd = resp.headers.get("Content-Disposition", "")
        self.assertIn("inline", cd)
        self.assertIn("screen.png", cd)
        body = await resp.read()
        self.assertEqual(body, contents)

    async def test_by_path_content_type_hint_overrides_guess(self) -> None:
        contents = b"{\"k\":\"v\"}"
        path = _write_file(self._sandbox, "weird.bin", content=contents)
        token_bearer = await self._create_session_token()

        resp = await self.client.get(
            "/media/by-path",
            params={"path": path, "content_type": "application/json"},
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 200)
        # Phone-provided hint overrides the guess for .bin
        self.assertEqual(resp.headers.get("Content-Type"), "application/json")

    async def test_by_path_oversized_returns_403(self) -> None:
        # Shrink the registry's size cap to 10 bytes, write a bigger file.
        # Restore in finally so later tests in the class (which share one
        # app instance) see the normal default.
        original = self._server().media.max_size_bytes
        self._server().media.max_size_bytes = 10
        try:
            path = _write_file(self._sandbox, "big.bin", content=b"x" * 100)
            token_bearer = await self._create_session_token()

            resp = await self.client.get(
                "/media/by-path",
                params={"path": path},
                headers={"Authorization": f"Bearer {token_bearer}"},
            )
            # "too large" falls through to the generic sandbox 403
            self.assertEqual(resp.status, 403)
        finally:
            self._server().media.max_size_bytes = original


if __name__ == "__main__":
    unittest.main()
