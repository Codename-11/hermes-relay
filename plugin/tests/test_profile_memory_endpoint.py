"""Tests for GET /api/profiles/{name}/memory.

Covers the profile-scoped memory-file listing endpoint — empty dir,
single file, MEMORY+USER+custom ordering, the 50KB-per-entry
truncation boundary, and unknown-profile 404.
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class ProfileMemoryEndpointTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.hermes_dir = Path(self._tmp.name)
        self.profiles_dir = self.hermes_dir / "profiles"
        self.profiles_dir.mkdir(parents=True, exist_ok=True)
        (self.hermes_dir / "config.yaml").write_text(
            "model:\n  default: test-model\n", encoding="utf-8"
        )
        config = RelayConfig(
            hermes_config_path=str(self.hermes_dir / "config.yaml")
        )
        return create_app(config)

    def _write_profile(self, name: str) -> Path:
        pdir = self.profiles_dir / name
        pdir.mkdir(parents=True, exist_ok=True)
        (pdir / "config.yaml").write_text(
            "model:\n  default: x\n", encoding="utf-8"
        )
        return pdir

    def _write_memory(self, pdir: Path, filename: str, body: str) -> Path:
        mem_dir = pdir / "memories"
        mem_dir.mkdir(parents=True, exist_ok=True)
        path = mem_dir / filename
        path.write_text(body, encoding="utf-8")
        return path

    # ── empty / absent ──────────────────────────────────────────────────

    async def test_absent_memories_dir_returns_empty(self) -> None:
        self._write_profile("bare")
        resp = await self.client.get("/api/profiles/bare/memory")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["profile"], "bare")
        self.assertEqual(body["entries"], [])
        self.assertEqual(body["total"], 0)
        self.assertTrue(body["memories_dir"].endswith("memories"))

    async def test_empty_memories_dir_returns_empty(self) -> None:
        pdir = self._write_profile("emptydir")
        (pdir / "memories").mkdir()
        resp = await self.client.get("/api/profiles/emptydir/memory")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["entries"], [])
        self.assertEqual(body["total"], 0)

    # ── single file ─────────────────────────────────────────────────────

    async def test_single_memory_file(self) -> None:
        pdir = self._write_profile("alice")
        self._write_memory(pdir, "MEMORY.md", "Just MEMORY.\n")
        resp = await self.client.get("/api/profiles/alice/memory")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["total"], 1)
        entry = body["entries"][0]
        self.assertEqual(entry["name"], "MEMORY")
        self.assertEqual(entry["filename"], "MEMORY.md")
        self.assertEqual(entry["content"], "Just MEMORY.\n")
        self.assertFalse(entry["truncated"])
        self.assertGreater(entry["size_bytes"], 0)
        self.assertTrue(entry["path"].endswith("MEMORY.md"))

    # ── ordering ────────────────────────────────────────────────────────

    async def test_memory_user_custom_ordering(self) -> None:
        pdir = self._write_profile("ordered")
        # Intentionally write USER before MEMORY and a custom file.
        # Filesystem iteration order must NOT leak through.
        self._write_memory(pdir, "zeta.md", "Custom zeta.\n")
        self._write_memory(pdir, "USER.md", "User notes.\n")
        self._write_memory(pdir, "alpha.md", "Custom alpha.\n")
        self._write_memory(pdir, "MEMORY.md", "Memory core.\n")

        resp = await self.client.get("/api/profiles/ordered/memory")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["total"], 4)
        filenames = [e["filename"] for e in body["entries"]]
        # MEMORY.md first, USER.md second, then alphabetical.
        self.assertEqual(filenames, ["MEMORY.md", "USER.md", "alpha.md", "zeta.md"])

    async def test_only_custom_files_alphabetical(self) -> None:
        pdir = self._write_profile("justcustom")
        self._write_memory(pdir, "gamma.md", "g\n")
        self._write_memory(pdir, "alpha.md", "a\n")
        self._write_memory(pdir, "beta.md", "b\n")
        resp = await self.client.get("/api/profiles/justcustom/memory")
        body = await resp.json()
        filenames = [e["filename"] for e in body["entries"]]
        self.assertEqual(filenames, ["alpha.md", "beta.md", "gamma.md"])

    # ── non-.md files ignored + no recursion ───────────────────────────

    async def test_non_markdown_files_ignored(self) -> None:
        pdir = self._write_profile("mixed")
        self._write_memory(pdir, "MEMORY.md", "m\n")
        # A .txt sibling must not surface.
        (pdir / "memories" / "notes.txt").write_text("x", encoding="utf-8")
        resp = await self.client.get("/api/profiles/mixed/memory")
        body = await resp.json()
        filenames = [e["filename"] for e in body["entries"]]
        self.assertEqual(filenames, ["MEMORY.md"])

    async def test_nested_md_files_not_recursed(self) -> None:
        pdir = self._write_profile("nested")
        self._write_memory(pdir, "MEMORY.md", "top\n")
        sub = pdir / "memories" / "sub"
        sub.mkdir(parents=True)
        (sub / "hidden.md").write_text("should not appear\n", encoding="utf-8")
        resp = await self.client.get("/api/profiles/nested/memory")
        body = await resp.json()
        filenames = [e["filename"] for e in body["entries"]]
        self.assertEqual(filenames, ["MEMORY.md"])

    # ── truncation ──────────────────────────────────────────────────────

    async def test_large_file_truncates_at_50kb(self) -> None:
        pdir = self._write_profile("big")
        large_body = "A" * (50 * 1024 + 100)
        self._write_memory(pdir, "MEMORY.md", large_body)
        resp = await self.client.get("/api/profiles/big/memory")
        body = await resp.json()
        entry = body["entries"][0]
        self.assertTrue(entry["truncated"])
        self.assertEqual(len(entry["content"]), 50 * 1024)
        self.assertEqual(entry["size_bytes"], 50 * 1024 + 100)

    async def test_exactly_at_cap_is_not_truncated(self) -> None:
        pdir = self._write_profile("exact")
        exact = "B" * (50 * 1024)
        self._write_memory(pdir, "MEMORY.md", exact)
        resp = await self.client.get("/api/profiles/exact/memory")
        body = await resp.json()
        entry = body["entries"][0]
        self.assertFalse(entry["truncated"])
        self.assertEqual(len(entry["content"]), 50 * 1024)

    # ── unknown profile ────────────────────────────────────────────────

    async def test_unknown_profile_returns_404(self) -> None:
        resp = await self.client.get("/api/profiles/ghost/memory")
        self.assertEqual(resp.status, 404)
        body = await resp.json()
        self.assertEqual(body["error"], "profile_not_found")
        self.assertEqual(body["profile"], "ghost")

    # ── default profile ────────────────────────────────────────────────

    async def test_default_profile_reads_root_memories(self) -> None:
        mem_dir = self.hermes_dir / "memories"
        mem_dir.mkdir()
        (mem_dir / "MEMORY.md").write_text("root memory\n", encoding="utf-8")
        resp = await self.client.get("/api/profiles/default/memory")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["total"], 1)
        self.assertEqual(body["entries"][0]["content"], "root memory\n")


if __name__ == "__main__":
    unittest.main()
