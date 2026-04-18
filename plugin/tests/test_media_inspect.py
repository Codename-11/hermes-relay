"""Tests for plugin.relay.media.MediaRegistry.list_all().

The dashboard's media-inspector tab relies on ``list_all()`` returning a
sanitized snapshot — no absolute paths, expired entries filtered by
default, newest-first ordering. Structured on
``plugin/tests/test_media_registry.py``.
"""

from __future__ import annotations

import os
import shutil
import tempfile
import time
import unittest

from plugin.relay.media import MediaRegistry


# ── Helpers ─────────────────────────────────────────────────────────────────


def _write_file(root: str, name: str, size: int = 16) -> str:
    """Write a small file under ``root`` and return its absolute path."""
    path = os.path.join(root, name)
    with open(path, "wb") as fh:
        fh.write(b"x" * size)
    return path


def _make_registry(
    sandbox: str,
    max_entries: int = 500,
    ttl_seconds: int = 86400,
    max_size_bytes: int = 100 * 1024 * 1024,
) -> MediaRegistry:
    registry = MediaRegistry(
        max_entries=max_entries,
        ttl_seconds=ttl_seconds,
        max_size_bytes=max_size_bytes,
        allowed_roots=[sandbox],
    )
    # Force-override auto-derived roots so ONLY the sandbox is allowed.
    registry.allowed_roots = [os.path.realpath(sandbox)]
    return registry


class MediaRegistryListAllTests(unittest.IsolatedAsyncioTestCase):
    """Unit tests for MediaRegistry.list_all()."""

    def setUp(self) -> None:
        self._sandbox = tempfile.mkdtemp(prefix="hermes_relay_inspect_")

    def tearDown(self) -> None:
        shutil.rmtree(self._sandbox, ignore_errors=True)

    # ── Empty state ─────────────────────────────────────────────────────

    async def test_empty_registry_returns_empty_list(self) -> None:
        registry = _make_registry(self._sandbox)
        result = await registry.list_all()
        self.assertEqual(result, [])

    async def test_empty_registry_include_expired_also_empty(self) -> None:
        registry = _make_registry(self._sandbox)
        self.assertEqual(await registry.list_all(include_expired=True), [])

    # ── Shape / sanitization ────────────────────────────────────────────

    async def test_registered_entry_appears_with_expected_keys(self) -> None:
        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "screenshot.png", size=42)
        entry = await registry.register(
            path, "image/png", file_name="screenshot.png"
        )

        result = await registry.list_all()
        self.assertEqual(len(result), 1)
        item = result[0]

        expected_keys = {
            "token",
            "file_name",
            "content_type",
            "size",
            "created_at",
            "expires_at",
            "last_accessed",
            "is_expired",
        }
        self.assertEqual(set(item.keys()), expected_keys)
        # Critical: absolute path MUST NOT leak.
        self.assertNotIn("path", item)

        self.assertEqual(item["token"], entry.token)
        self.assertEqual(item["file_name"], "screenshot.png")
        self.assertEqual(item["content_type"], "image/png")
        self.assertEqual(item["size"], 42)
        self.assertFalse(item["is_expired"])

    async def test_file_name_is_basename_when_missing(self) -> None:
        """If file_name wasn't set on register, basename is derived from path."""
        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "derived.png")
        await registry.register(path, "image/png")  # no file_name

        result = await registry.list_all()
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["file_name"], "derived.png")
        # Still no absolute path.
        self.assertNotIn(self._sandbox, result[0]["file_name"])

    async def test_file_name_with_absolute_value_gets_basenamed(self) -> None:
        """Even if file_name is stored as an absolute path, only basename surfaces."""
        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "abs.png")
        # Abuse the register API: pass an absolute string as file_name.
        await registry.register(path, "image/png", file_name=path)

        result = await registry.list_all()
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["file_name"], "abs.png")
        self.assertNotIn("path", result[0])

    # ── Expiry filter ───────────────────────────────────────────────────

    async def test_expired_entries_hidden_by_default(self) -> None:
        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "stale.png")
        entry = await registry.register(path, "image/png")

        # Force expiry by rewriting expires_at under the lock.
        async with registry._lock:
            registry._entries[entry.token].expires_at = time.time() - 10

        self.assertEqual(await registry.list_all(), [])

    async def test_include_expired_surfaces_expired_entries(self) -> None:
        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "stale.png")
        entry = await registry.register(path, "image/png", file_name="stale.png")

        async with registry._lock:
            registry._entries[entry.token].expires_at = time.time() - 10

        result = await registry.list_all(include_expired=True)
        self.assertEqual(len(result), 1)
        self.assertTrue(result[0]["is_expired"])
        self.assertEqual(result[0]["file_name"], "stale.png")
        self.assertNotIn("path", result[0])

    # ── Ordering ────────────────────────────────────────────────────────

    async def test_ordering_newest_first_by_created_at(self) -> None:
        registry = _make_registry(self._sandbox)
        tokens: list[str] = []
        for i in range(3):
            path = _write_file(self._sandbox, f"ordered{i}.bin")
            entry = await registry.register(
                path, "application/octet-stream", file_name=f"ordered{i}.bin"
            )
            tokens.append(entry.token)

        # Force distinct created_at values — first registered gets oldest
        # timestamp, last registered gets newest.
        now = time.time()
        async with registry._lock:
            registry._entries[tokens[0]].created_at = now - 300
            registry._entries[tokens[1]].created_at = now - 200
            registry._entries[tokens[2]].created_at = now - 100

        result = await registry.list_all()
        self.assertEqual(len(result), 3)
        # Newest first → tokens[2], tokens[1], tokens[0].
        self.assertEqual(result[0]["token"], tokens[2])
        self.assertEqual(result[1]["token"], tokens[1])
        self.assertEqual(result[2]["token"], tokens[0])
        # And the created_at sequence is strictly descending.
        self.assertGreater(result[0]["created_at"], result[1]["created_at"])
        self.assertGreater(result[1]["created_at"], result[2]["created_at"])


class MediaInspectRouteTests(unittest.IsolatedAsyncioTestCase):
    """Covers the ``GET /media/inspect`` HTTP handler."""

    def setUp(self) -> None:
        self._sandbox = tempfile.mkdtemp(prefix="hermes_relay_inspect_route_")

    def tearDown(self) -> None:
        shutil.rmtree(self._sandbox, ignore_errors=True)

    async def _make_app(self, registry: MediaRegistry):
        from aiohttp import web

        from plugin.relay.server import handle_media_inspect

        app = web.Application()

        class _StubServer:
            def __init__(self, r: MediaRegistry) -> None:
                self.media = r

        app["server"] = _StubServer(registry)
        app.router.add_get("/media/inspect", handle_media_inspect)
        return app

    async def test_loopback_caller_returns_media_shape(self) -> None:
        from aiohttp.test_utils import TestClient, TestServer

        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "shot.png")
        await registry.register(path, "image/png", file_name="shot.png")

        app = await self._make_app(registry)
        async with TestClient(TestServer(app)) as client:
            resp = await client.get("/media/inspect")
            self.assertEqual(resp.status, 200)
            body = await resp.json()
            self.assertIn("media", body)
            self.assertEqual(len(body["media"]), 1)
            self.assertEqual(body["media"][0]["file_name"], "shot.png")
            self.assertNotIn("path", body["media"][0])

    async def test_include_expired_flag_surfaces_expired(self) -> None:
        from aiohttp.test_utils import TestClient, TestServer

        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "old.png")
        entry = await registry.register(path, "image/png", file_name="old.png")
        async with registry._lock:
            registry._entries[entry.token].expires_at = time.time() - 10

        app = await self._make_app(registry)
        async with TestClient(TestServer(app)) as client:
            # Default — expired hidden.
            resp = await client.get("/media/inspect")
            body = await resp.json()
            self.assertEqual(body["media"], [])

            # Explicit flag — expired surfaced.
            resp = await client.get("/media/inspect?include_expired=true")
            body = await resp.json()
            self.assertEqual(len(body["media"]), 1)
            self.assertTrue(body["media"][0]["is_expired"])

    async def test_non_loopback_returns_403(self) -> None:
        from aiohttp import web

        from plugin.relay.server import handle_media_inspect

        registry = _make_registry(self._sandbox)

        class _Req:
            def __init__(self, remote: str, r: MediaRegistry) -> None:
                self.remote = remote

                class _S:
                    media = r

                self.app = {"server": _S()}
                self.query: dict[str, str] = {}

            @property
            def headers(self):
                return {}

        req = _Req(remote="192.168.1.5", r=registry)
        with self.assertRaises(web.HTTPForbidden):
            await handle_media_inspect(req)  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
