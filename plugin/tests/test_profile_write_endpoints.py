"""Tests for PUT /api/profiles/{name}/soul and
/api/profiles/{name}/memory/{filename}.

Covers the Commit-4 write endpoints — happy path, size cap (413),
profile-not-found (404), filename validation (400), and atomic write
semantics.
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class ProfileWriteEndpointsBase(AioHTTPTestCase):
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

    def _write_profile(self, name: str, *, soul: str | None = None) -> Path:
        pdir = self.profiles_dir / name
        pdir.mkdir(parents=True, exist_ok=True)
        (pdir / "config.yaml").write_text(
            "model:\n  default: x\n", encoding="utf-8"
        )
        if soul is not None:
            (pdir / "SOUL.md").write_text(soul, encoding="utf-8")
        return pdir


class ProfileSoulPutHappyPathTests(ProfileWriteEndpointsBase):
    async def test_writes_new_soul(self) -> None:
        """A profile without SOUL.md accepts a fresh write and lands
        the content on disk exactly."""
        pdir = self._write_profile("fresh", soul=None)
        resp = await self.client.put(
            "/api/profiles/fresh/soul",
            json={"content": "# Fresh soul\n\nHello.\n"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["ok"])
        self.assertEqual(body["profile"], "fresh")
        self.assertEqual(body["bytes_written"], len("# Fresh soul\n\nHello.\n".encode("utf-8")))
        self.assertTrue(body["path"].endswith("SOUL.md"))

        on_disk = (pdir / "SOUL.md").read_text(encoding="utf-8")
        self.assertEqual(on_disk, "# Fresh soul\n\nHello.\n")

    async def test_overwrites_existing_soul(self) -> None:
        pdir = self._write_profile("overwrite", soul="OLD\n")
        resp = await self.client.put(
            "/api/profiles/overwrite/soul",
            json={"content": "NEW\n"},
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual((pdir / "SOUL.md").read_text(encoding="utf-8"), "NEW\n")

    async def test_utf8_payload_roundtrips(self) -> None:
        pdir = self._write_profile("unicode")
        resp = await self.client.put(
            "/api/profiles/unicode/soul",
            json={"content": "混沌の魂 🧠\n"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        # bytes_written reports UTF-8 byte count, not char count.
        self.assertEqual(body["bytes_written"], len("混沌の魂 🧠\n".encode("utf-8")))
        self.assertEqual((pdir / "SOUL.md").read_text(encoding="utf-8"), "混沌の魂 🧠\n")


class ProfileSoulPutValidationTests(ProfileWriteEndpointsBase):
    async def test_unknown_profile_returns_404(self) -> None:
        resp = await self.client.put(
            "/api/profiles/ghost/soul",
            json={"content": "x"},
        )
        self.assertEqual(resp.status, 404)
        body = await resp.json()
        self.assertEqual(body["error"], "profile_not_found")

    async def test_missing_body_rejected(self) -> None:
        self._write_profile("nobody")
        resp = await self.client.put(
            "/api/profiles/nobody/soul",
            data="not json",
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(resp.status, 400)

    async def test_missing_content_field_rejected(self) -> None:
        self._write_profile("nocontent")
        resp = await self.client.put(
            "/api/profiles/nocontent/soul",
            json={},
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertEqual(body["error"], "invalid_body")

    async def test_non_string_content_rejected(self) -> None:
        self._write_profile("bad")
        resp = await self.client.put(
            "/api/profiles/bad/soul",
            json={"content": 42},
        )
        self.assertEqual(resp.status, 400)

    async def test_payload_over_1mb_rejected(self) -> None:
        self._write_profile("big")
        # 1MB + 1 byte of ASCII.
        blob = "A" * (1024 * 1024 + 1)
        resp = await self.client.put(
            "/api/profiles/big/soul",
            json={"content": blob},
        )
        self.assertEqual(resp.status, 413)
        body = await resp.json()
        self.assertEqual(body["error"], "payload_too_large")
        self.assertEqual(body["limit_bytes"], 1024 * 1024)

    async def test_exactly_1mb_accepted(self) -> None:
        self._write_profile("edge")
        blob = "B" * (1024 * 1024)
        resp = await self.client.put(
            "/api/profiles/edge/soul",
            json={"content": blob},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["bytes_written"], 1024 * 1024)


class ProfileMemoryPutHappyPathTests(ProfileWriteEndpointsBase):
    async def test_writes_new_memory_file(self) -> None:
        pdir = self._write_profile("alice")
        resp = await self.client.put(
            "/api/profiles/alice/memory/MEMORY.md",
            json={"content": "# Memory\n\nNotes.\n"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["ok"])
        self.assertEqual(body["filename"], "MEMORY.md")
        self.assertEqual(body["profile"], "alice")

        disk = (pdir / "memories" / "MEMORY.md").read_text(encoding="utf-8")
        self.assertEqual(disk, "# Memory\n\nNotes.\n")

    async def test_creates_memories_dir_if_absent(self) -> None:
        pdir = self._write_profile("newmem")
        self.assertFalse((pdir / "memories").exists())
        resp = await self.client.put(
            "/api/profiles/newmem/memory/MEMORY.md",
            json={"content": "x"},
        )
        self.assertEqual(resp.status, 200)
        self.assertTrue((pdir / "memories").is_dir())

    async def test_writes_custom_filename(self) -> None:
        pdir = self._write_profile("custom")
        resp = await self.client.put(
            "/api/profiles/custom/memory/project_notes.md",
            json={"content": "Custom body.\n"},
        )
        self.assertEqual(resp.status, 200)
        self.assertTrue((pdir / "memories" / "project_notes.md").is_file())


class ProfileMemoryPutFilenameValidationTests(ProfileWriteEndpointsBase):
    """Filenames that would allow path traversal, hidden files, or
    non-markdown content must be rejected with a 400
    ``invalid_filename``."""

    async def test_traversal_with_slash_rejected(self) -> None:
        self._write_profile("trav")
        # Router won't match a filename with "/" in the path segment,
        # but any call that reaches our handler with a "/" in the
        # decoded filename is rejected. Test the guard directly:
        from plugin.relay.server import _validate_memory_filename
        self.assertIsNotNone(_validate_memory_filename("../etc/passwd.md"))
        self.assertIsNotNone(_validate_memory_filename("sub/note.md"))

    async def test_traversal_with_backslash_rejected(self) -> None:
        from plugin.relay.server import _validate_memory_filename
        self.assertIsNotNone(_validate_memory_filename("..\\etc\\passwd.md"))
        self.assertIsNotNone(_validate_memory_filename("sub\\note.md"))

    async def test_double_dot_rejected(self) -> None:
        from plugin.relay.server import _validate_memory_filename
        self.assertIsNotNone(_validate_memory_filename("..md"))
        self.assertIsNotNone(_validate_memory_filename("note..md"))

    async def test_leading_dot_rejected(self) -> None:
        self._write_profile("hidden")
        resp = await self.client.put(
            "/api/profiles/hidden/memory/.hidden.md",
            json={"content": "x"},
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertEqual(body["error"], "invalid_filename")

    async def test_non_md_extension_rejected(self) -> None:
        self._write_profile("extcheck")
        resp = await self.client.put(
            "/api/profiles/extcheck/memory/MEMORY.txt",
            json={"content": "x"},
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertEqual(body["error"], "invalid_filename")

    async def test_soul_md_rejected_via_memory_path(self) -> None:
        """Writing SOUL.md via the memory endpoint is blocked — the
        soul endpoint is the canonical entry point."""
        self._write_profile("soulroute")
        resp = await self.client.put(
            "/api/profiles/soulroute/memory/SOUL.md",
            json={"content": "x"},
        )
        self.assertEqual(resp.status, 400)

    async def test_valid_filenames_pass_guard(self) -> None:
        """Guard positive cases."""
        from plugin.relay.server import _validate_memory_filename
        self.assertIsNone(_validate_memory_filename("MEMORY.md"))
        self.assertIsNone(_validate_memory_filename("USER.md"))
        self.assertIsNone(_validate_memory_filename("project_notes.md"))
        self.assertIsNone(_validate_memory_filename("a-b-c.md"))
        self.assertIsNone(_validate_memory_filename("file_1.md"))


class ProfileMemoryPutValidationTests(ProfileWriteEndpointsBase):
    async def test_unknown_profile_returns_404(self) -> None:
        resp = await self.client.put(
            "/api/profiles/ghost/memory/MEMORY.md",
            json={"content": "x"},
        )
        self.assertEqual(resp.status, 404)
        body = await resp.json()
        self.assertEqual(body["error"], "profile_not_found")

    async def test_payload_over_1mb_rejected(self) -> None:
        self._write_profile("big")
        blob = "A" * (1024 * 1024 + 1)
        resp = await self.client.put(
            "/api/profiles/big/memory/MEMORY.md",
            json={"content": blob},
        )
        self.assertEqual(resp.status, 413)
        body = await resp.json()
        self.assertEqual(body["error"], "payload_too_large")


class ProfileWriteAtomicityTests(ProfileWriteEndpointsBase):
    """The SOUL.md.tmp sibling file must be renamed, not left lying."""

    async def test_no_tmp_left_after_successful_write(self) -> None:
        pdir = self._write_profile("atomic")
        resp = await self.client.put(
            "/api/profiles/atomic/soul",
            json={"content": "Content.\n"},
        )
        self.assertEqual(resp.status, 200)
        self.assertFalse((pdir / "SOUL.md.tmp").exists())
        self.assertTrue((pdir / "SOUL.md").is_file())


class ProfileDefaultWriteTests(ProfileWriteEndpointsBase):
    """The synthetic ``default`` profile should route writes to the
    root ``~/.hermes`` directory — same as the GET path."""

    async def test_default_soul_writes_to_root(self) -> None:
        resp = await self.client.put(
            "/api/profiles/default/soul",
            json={"content": "# Root soul\n"},
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(
            (self.hermes_dir / "SOUL.md").read_text(encoding="utf-8"),
            "# Root soul\n",
        )

    async def test_default_memory_writes_to_root(self) -> None:
        resp = await self.client.put(
            "/api/profiles/default/memory/MEMORY.md",
            json={"content": "# Root memory\n"},
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(
            (self.hermes_dir / "memories" / "MEMORY.md").read_text(encoding="utf-8"),
            "# Root memory\n",
        )


if __name__ == "__main__":
    unittest.main()
