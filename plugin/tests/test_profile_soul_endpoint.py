"""Tests for GET /api/profiles/{name}/soul.

Covers the profile-scoped SOUL.md read endpoint — presence, absence,
the 200KB truncation boundary, unknown-profile 404, and path-traversal
rejection. The Inspector viewer on the phone relies on the field shape
being stable.
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class ProfileSoulEndpointTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.hermes_dir = Path(self._tmp.name)
        self.profiles_dir = self.hermes_dir / "profiles"
        self.profiles_dir.mkdir(parents=True, exist_ok=True)
        # The root config.yaml doesn't have to be populated — the
        # handler only uses its parent path.
        (self.hermes_dir / "config.yaml").write_text(
            "model:\n  default: test-model\n", encoding="utf-8"
        )
        config = RelayConfig(
            hermes_config_path=str(self.hermes_dir / "config.yaml")
        )
        return create_app(config)

    def _write_profile(self, name: str, *, soul: str | None = None) -> Path:
        pdir = self.profiles_dir / name
        pdir.mkdir(parents=True, exist_ok=True)
        (pdir / "config.yaml").write_text(
            "model:\n  default: x\n", encoding="utf-8"
        )
        if soul is not None:
            (pdir / "SOUL.md").write_text(soul, encoding="utf-8")
        return pdir

    # ── presence / absence ──────────────────────────────────────────────

    async def test_present_soul_returns_content(self) -> None:
        self._write_profile("mizu", soul="# Mizu\n\nResearcher body.\n")
        resp = await self.client.get("/api/profiles/mizu/soul")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["profile"], "mizu")
        self.assertTrue(body["exists"])
        self.assertIn("Researcher body.", body["content"])
        self.assertGreater(body["size_bytes"], 0)
        self.assertTrue(body["path"].endswith("SOUL.md"))
        # Untruncated response must not set truncated: true.
        self.assertFalse(body.get("truncated", False))

    async def test_absent_soul_returns_200_exists_false(self) -> None:
        self._write_profile("noSoul", soul=None)
        resp = await self.client.get("/api/profiles/noSoul/soul")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["profile"], "noSoul")
        self.assertFalse(body["exists"])
        self.assertEqual(body["content"], "")
        self.assertEqual(body["size_bytes"], 0)
        self.assertTrue(body["path"].endswith("SOUL.md"))
        self.assertNotIn("truncated", body)

    # ── truncation boundary ────────────────────────────────────────────

    async def test_large_soul_truncates_at_200kb(self) -> None:
        # Write ~201KB of ASCII — byte length = char length for 'A'.
        large = "A" * (200 * 1024 + 50)
        self._write_profile("big", soul=large)
        resp = await self.client.get("/api/profiles/big/soul")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["exists"])
        self.assertTrue(body["truncated"])
        # Content truncated to the cap, size_bytes reflects the on-disk
        # size of the file.
        self.assertEqual(len(body["content"]), 200 * 1024)
        self.assertEqual(body["size_bytes"], 200 * 1024 + 50)

    async def test_exactly_at_cap_is_not_truncated(self) -> None:
        exact = "B" * (200 * 1024)
        self._write_profile("exact", soul=exact)
        resp = await self.client.get("/api/profiles/exact/soul")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["exists"])
        self.assertFalse(body.get("truncated", False))
        self.assertEqual(len(body["content"]), 200 * 1024)

    # ── unknown profile ────────────────────────────────────────────────

    async def test_unknown_profile_returns_404(self) -> None:
        resp = await self.client.get("/api/profiles/ghost/soul")
        self.assertEqual(resp.status, 404)
        body = await resp.json()
        self.assertEqual(body["error"], "profile_not_found")
        self.assertEqual(body["profile"], "ghost")

    # ── path traversal ─────────────────────────────────────────────────

    async def test_path_traversal_rejected(self) -> None:
        # Encoded ``..`` collapses into a different path at the URL
        # normalization layer before aiohttp's router matches, so we
        # assert on status alone — the test is that nothing from
        # outside the profiles dir gets read. Either a plain 404 from
        # the router or our structured payload is acceptable.
        resp = await self.client.get("/api/profiles/%2E%2E/soul")
        self.assertEqual(resp.status, 404)

    async def test_path_traversal_with_separator_rejected(self) -> None:
        resp = await self.client.get("/api/profiles/..%2Fetc/soul")
        # aiohttp may reject the route match (404 "Not Found") or the
        # handler may return our structured profile_not_found payload.
        # Either way the caller does NOT reach another profile's files.
        self.assertEqual(resp.status, 404)

    # ── default profile ────────────────────────────────────────────────

    async def test_default_profile_reads_root_soul(self) -> None:
        (self.hermes_dir / "SOUL.md").write_text(
            "# House default\n", encoding="utf-8"
        )
        resp = await self.client.get("/api/profiles/default/soul")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["exists"])
        self.assertIn("House default", body["content"])


if __name__ == "__main__":
    unittest.main()
