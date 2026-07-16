"""Tests for host-side Hermes profile avatar discovery."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class ProfileAvatarEndpointTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.hermes_dir = Path(self._tmp.name)
        self.profiles_dir = self.hermes_dir / "profiles"
        self.profiles_dir.mkdir(parents=True)
        config_path = self.hermes_dir / "config.yaml"
        config_path.write_text("model:\n  default: test-model\n", encoding="utf-8")
        return create_app(RelayConfig(hermes_config_path=str(config_path)))

    def _profile(self, name: str) -> Path:
        home = self.profiles_dir / name
        home.mkdir(parents=True)
        return home

    async def test_discovers_avatar_png(self) -> None:
        expected = b"\x89PNG\r\n\x1a\nprofile-image"
        (self._profile("mizu") / "avatar.png").write_bytes(expected)

        response = await self.client.get("/api/profiles/mizu/avatar")

        self.assertEqual(response.status, 200)
        self.assertEqual(response.headers["Content-Type"], "image/png")
        self.assertIn("avatar.png", response.headers["Content-Disposition"])
        self.assertEqual(await response.read(), expected)

    async def test_matching_is_case_insensitive_and_prefers_avatar(self) -> None:
        home = self._profile("casey")
        (home / "PROFILE.JPG").write_bytes(b"\xff\xd8\xffprofile")
        (home / "Avatar.JpG").write_bytes(b"\xff\xd8\xffavatar")

        response = await self.client.get("/api/profiles/casey/avatar")

        self.assertEqual(response.status, 200)
        self.assertEqual(await response.read(), b"\xff\xd8\xffavatar")

    async def test_default_profile_reads_root_avatar(self) -> None:
        (self.hermes_dir / "profile.webp").write_bytes(
            b"RIFF\x10\x00\x00\x00WEBPVP8 "
        )

        response = await self.client.get("/api/profiles/default/avatar")

        self.assertEqual(response.status, 200)
        self.assertEqual(response.headers["Content-Type"], "image/webp")

    async def test_default_profile_follows_sticky_active_profile(self) -> None:
        active = self._profile("active")
        (active / "config.yaml").write_text("model:\n  default: active\n", encoding="utf-8")
        (active / "avatar.png").write_bytes(b"\x89PNG\r\n\x1a\nactive")
        (self.hermes_dir / "avatar.png").write_bytes(b"\x89PNG\r\n\x1a\nroot")
        (self.hermes_dir / "active_profile").write_text("active\n", encoding="utf-8")

        response = await self.client.get("/api/profiles/default/avatar")

        self.assertEqual(response.status, 200)
        self.assertEqual(await response.read(), b"\x89PNG\r\n\x1a\nactive")

    async def test_absent_avatar_returns_expected_names(self) -> None:
        self._profile("bare")

        response = await self.client.get("/api/profiles/bare/avatar")

        self.assertEqual(response.status, 404)
        body = await response.json()
        self.assertEqual(body["error"], "profile_avatar_not_found")
        self.assertIn("avatar.png", body["expected_names"])
        self.assertIn("profile.jpg", body["expected_names"])

    async def test_ignores_nested_images(self) -> None:
        nested = self._profile("nested") / "art"
        nested.mkdir()
        (nested / "avatar.png").write_bytes(b"\x89PNG\r\n\x1a\n")

        response = await self.client.get("/api/profiles/nested/avatar")

        self.assertEqual(response.status, 404)

    async def test_unknown_profile_returns_404(self) -> None:
        response = await self.client.get("/api/profiles/ghost/avatar")
        self.assertEqual(response.status, 404)
        self.assertEqual((await response.json())["error"], "profile_not_found")


if __name__ == "__main__":
    unittest.main()
