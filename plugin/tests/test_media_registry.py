"""Tests for plugin.relay.media.MediaRegistry.

Uses ``unittest.IsolatedAsyncioTestCase`` instead of pytest-asyncio because
pytest-asyncio isn't in the dev dependency set — pytest discovers and runs
unittest-style tests natively, and ``IsolatedAsyncioTestCase`` ships in the
stdlib.
"""

from __future__ import annotations

import asyncio
import logging
import os
import shutil
import tempfile
import time
import unittest

from plugin.relay.media import MediaRegistrationError, MediaRegistry, _MediaEntry


def test_registry_logs_omit_tokens_and_media_paths(caplog) -> None:
    async def run() -> tuple[str, str]:
        with tempfile.TemporaryDirectory() as root:
            path = _write_file(root, "private-image.png")
            registry = MediaRegistry(allowed_roots=[root])
            entry = await registry.register(path, "image/png")
            return path, entry.token

    caplog.set_level(logging.INFO, logger="hermes_relay.media")
    path, token = asyncio.run(run())
    assert path not in caplog.text
    assert token[:8] not in caplog.text


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
    # Force-override auto-derived roots so ONLY the sandbox is allowed —
    # otherwise gettempdir() defaults can leak in and make "outside allowed
    # roots" tests flaky on platforms where the OS tempdir is a parent of
    # our sandbox.
    registry.allowed_roots = [os.path.realpath(sandbox)]
    return registry


class MediaRegistryTests(unittest.IsolatedAsyncioTestCase):
    """Unit tests for MediaRegistry."""

    def setUp(self) -> None:
        self._sandbox = tempfile.mkdtemp(prefix="hermes_relay_sandbox_")

    def tearDown(self) -> None:
        shutil.rmtree(self._sandbox, ignore_errors=True)

    # ── Happy path ──────────────────────────────────────────────────────

    async def test_register_and_get_happy_path(self) -> None:
        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "hello.png", size=42)

        entry = await registry.register(path, "image/png", file_name="hello.png")
        self.assertIsInstance(entry, _MediaEntry)
        self.assertEqual(entry.content_type, "image/png")
        self.assertEqual(entry.size, 42)
        self.assertEqual(entry.file_name, "hello.png")
        self.assertTrue(entry.token)

        fetched = await registry.get(entry.token)
        self.assertIsNotNone(fetched)
        assert fetched is not None  # narrow for type checker
        self.assertEqual(fetched.token, entry.token)
        self.assertEqual(fetched.path, os.path.realpath(path))

    async def test_get_unknown_token_returns_none(self) -> None:
        registry = _make_registry(self._sandbox)
        self.assertIsNone(await registry.get("no-such-token"))
        self.assertIsNone(await registry.get(""))

    # ── Expiry ──────────────────────────────────────────────────────────

    async def test_expired_token_returns_none(self) -> None:
        registry = _make_registry(self._sandbox, ttl_seconds=1)
        path = _write_file(self._sandbox, "expire.png")
        entry = await registry.register(path, "image/png")

        # Force expiry by rewriting expires_at under the lock.
        async with registry._lock:
            registry._entries[entry.token].expires_at = time.time() - 10

        self.assertIsNone(await registry.get(entry.token))
        # And the expired entry has been pruned.
        self.assertEqual(await registry.size(), 0)

    async def test_owned_upload_is_deleted_on_expiry_but_caller_file_is_preserved(self) -> None:
        registry = MediaRegistry()
        with tempfile.NamedTemporaryFile(prefix="hermes-relay-upload-", delete=False) as upload:
            upload.write(b"private-image")
            owned_path = upload.name
        caller_path = _write_file(self._sandbox, "caller.png")
        try:
            owned = await registry.register(owned_path, "image/png", owned_file=True)
            caller = await registry.register(caller_path, "image/png")
            async with registry._lock:
                registry._entries[owned.token].expires_at = time.time() - 1
                registry._entries[caller.token].expires_at = time.time() - 1
            self.assertEqual(await registry.cleanup(), 2)
            self.assertFalse(os.path.exists(owned_path))
            self.assertTrue(os.path.exists(caller_path))
        finally:
            if os.path.exists(owned_path):
                os.unlink(owned_path)

    async def test_owned_upload_is_deleted_on_close(self) -> None:
        registry = MediaRegistry()
        with tempfile.NamedTemporaryFile(prefix="hermes-relay-upload-", delete=False) as upload:
            upload.write(b"private-image")
            owned_path = upload.name
        try:
            await registry.register(owned_path, "image/png", owned_file=True)
            await registry.close()
            self.assertFalse(os.path.exists(owned_path))
        finally:
            if os.path.exists(owned_path):
                os.unlink(owned_path)

    async def test_owned_upload_is_deleted_on_lru_eviction(self) -> None:
        registry = MediaRegistry(max_entries=1)
        with tempfile.NamedTemporaryFile(prefix="hermes-relay-upload-", delete=False) as upload:
            upload.write(b"private-image")
            owned_path = upload.name
        try:
            await registry.register(owned_path, "image/png", owned_file=True)
            await registry.register(_write_file(self._sandbox, "keep.png"), "image/png")
            self.assertFalse(os.path.exists(owned_path))
        finally:
            if os.path.exists(owned_path):
                os.unlink(owned_path)

    async def test_owned_file_survives_while_another_token_references_it(self) -> None:
        registry = MediaRegistry()
        with tempfile.NamedTemporaryFile(prefix="hermes-relay-upload-", delete=False) as upload:
            upload.write(b"shared-image")
            owned_path = upload.name
        try:
            owned = await registry.register(owned_path, "image/png", owned_file=True)
            other = await registry.register(owned_path, "image/png")
            async with registry._lock:
                registry._entries[owned.token].expires_at = time.time() - 1
            await registry.cleanup()
            self.assertTrue(os.path.exists(owned_path))
            async with registry._lock:
                registry._entries[other.token].expires_at = time.time() - 1
            await registry.cleanup()
            self.assertFalse(os.path.exists(owned_path))
        finally:
            if os.path.exists(owned_path):
                os.unlink(owned_path)

    async def test_owned_file_cannot_delete_arbitrary_registered_path(self) -> None:
        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "keep.png")
        with self.assertRaisesRegex(MediaRegistrationError, "managed upload path"):
            await registry.register(path, "image/png", owned_file=True)
        self.assertTrue(os.path.exists(path))

    # ── LRU eviction ────────────────────────────────────────────────────

    async def test_lru_eviction_when_cap_exceeded(self) -> None:
        registry = _make_registry(self._sandbox, max_entries=3)

        tokens: list[str] = []
        for i in range(4):
            path = _write_file(self._sandbox, f"file{i}.bin")
            entry = await registry.register(path, "application/octet-stream")
            tokens.append(entry.token)

        # Oldest should be evicted.
        self.assertIsNone(await registry.get(tokens[0]))
        self.assertIsNotNone(await registry.get(tokens[1]))
        self.assertIsNotNone(await registry.get(tokens[2]))
        self.assertIsNotNone(await registry.get(tokens[3]))
        self.assertEqual(await registry.size(), 3)

    async def test_repeated_get_moves_token_to_end_of_lru(self) -> None:
        """A recently-read entry should survive eviction over stale ones."""
        registry = _make_registry(self._sandbox, max_entries=3)
        tokens: list[str] = []
        for i in range(3):
            path = _write_file(self._sandbox, f"lru{i}.bin")
            entry = await registry.register(path, "application/octet-stream")
            tokens.append(entry.token)

        # Touch the oldest token so it becomes the freshest.
        self.assertIsNotNone(await registry.get(tokens[0]))

        # Register a 4th entry — should evict tokens[1] (now the oldest),
        # not tokens[0].
        path = _write_file(self._sandbox, "lru_new.bin")
        new_entry = await registry.register(path, "application/octet-stream")

        self.assertIsNone(await registry.get(tokens[1]))
        self.assertIsNotNone(await registry.get(tokens[0]))
        self.assertIsNotNone(await registry.get(tokens[2]))
        self.assertIsNotNone(await registry.get(new_entry.token))

    # ── Path validation ────────────────────────────────────────────────

    async def test_rejects_relative_path(self) -> None:
        registry = _make_registry(self._sandbox)
        with self.assertRaisesRegex(MediaRegistrationError, "absolute"):
            await registry.register("relative.png", "image/png")

    async def test_rejects_nonexistent_path(self) -> None:
        registry = _make_registry(self._sandbox)
        ghost = os.path.join(self._sandbox, "ghost.png")
        with self.assertRaisesRegex(MediaRegistrationError, "does not exist"):
            await registry.register(ghost, "image/png")

    async def test_rejects_directory_instead_of_file(self) -> None:
        registry = _make_registry(self._sandbox)
        sub = os.path.join(self._sandbox, "subdir")
        os.makedirs(sub, exist_ok=True)
        with self.assertRaisesRegex(MediaRegistrationError, "regular file"):
            await registry.register(sub, "image/png")

    async def test_rejects_path_outside_allowed_roots(self) -> None:
        """A path in a completely unrelated tmp dir must be rejected."""
        other_root = tempfile.mkdtemp(prefix="hermes_relay_outside_")
        try:
            outside = _write_file(other_root, "outside.png")
            registry = _make_registry(self._sandbox)
            with self.assertRaisesRegex(MediaRegistrationError, "allowed root"):
                await registry.register(outside, "image/png")
        finally:
            shutil.rmtree(other_root, ignore_errors=True)

    async def test_rejects_symlink_escaping_allowed_root(self) -> None:
        """A symlink inside the allowlist pointing outside must be rejected.

        ``os.path.realpath`` resolves the symlink to its target, which is
        outside the sandbox, so the under-root check fails.
        """
        other_root = tempfile.mkdtemp(prefix="hermes_relay_symtarget_")
        try:
            target = _write_file(other_root, "target.png")
            link = os.path.join(self._sandbox, "escape.png")
            try:
                os.symlink(target, link)
            except (OSError, NotImplementedError):
                self.skipTest("symlinks not supported on this platform/user")

            registry = _make_registry(self._sandbox)
            with self.assertRaisesRegex(MediaRegistrationError, "allowed root"):
                await registry.register(link, "image/png")
        finally:
            shutil.rmtree(other_root, ignore_errors=True)

    async def test_rejects_oversized_file(self) -> None:
        registry = _make_registry(self._sandbox, max_size_bytes=100)
        big = _write_file(self._sandbox, "big.bin", size=500)
        with self.assertRaisesRegex(MediaRegistrationError, "too large"):
            await registry.register(big, "application/octet-stream")

    async def test_rejects_missing_content_type(self) -> None:
        registry = _make_registry(self._sandbox)
        path = _write_file(self._sandbox, "file.png")
        with self.assertRaisesRegex(MediaRegistrationError, "content_type"):
            await registry.register(path, "")


if __name__ == "__main__":
    unittest.main()
