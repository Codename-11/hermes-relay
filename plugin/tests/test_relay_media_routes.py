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
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay import media
from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


def _write_file(root: str, name: str, content: bytes = b"hello-bytes") -> str:
    path = os.path.join(root, name)
    with open(path, "wb") as fh:
        fh.write(content)
    return path


class RelayMediaRoutesTests(AioHTTPTestCase):
    """Exercises the /media/* routes end-to-end against a live test server.

    Strict-mode test class — pins ``strict_sandbox=True`` so the by-path
    route enforces ``allowed_roots``. The permissive-mode behavior (new
    default as of 2026-04-11) is covered by
    :class:`RelayMediaByPathPermissiveTests` below.
    """

    async def get_application(self) -> web.Application:
        self._sandbox = tempfile.mkdtemp(prefix="hermes_relay_routes_")
        self._cleanup_paths: list[str] = [self._sandbox]

        config = RelayConfig()
        # Only allow the sandbox — we'll pin this after app creation too.
        config.media_allowed_roots = [self._sandbox]
        # Opt into strict-mode sandbox checks so the legacy test assertions
        # (outside-sandbox → 403) still hold. Permissive mode is the default
        # in production and is covered by the sibling test class below.
        config.media_strict_sandbox = True

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

    async def test_by_path_denies_credentials_inside_sandbox_in_strict_mode(self) -> None:
        """The credential denylist is always-on — it outranks the allowlist.

        Even when a credential file sits INSIDE an allowed root (an operator
        who allowlisted a directory containing Hermes state), strict mode
        must still refuse to serve it. Mirrors upstream media-delivery
        hardening where the denylist applies in every mode.
        """
        fake_home = os.path.join(self._sandbox, "hermes-home")
        os.makedirs(fake_home, exist_ok=True)
        env_file = _write_file(fake_home, ".env", content=b"API_KEY=sk-test")
        token_bearer = await self._create_session_token()

        with mock.patch.dict(os.environ, {"HERMES_HOME": fake_home}):
            resp = await self.client.get(
                "/media/by-path",
                params={"path": env_file},
                headers={"Authorization": f"Bearer {token_bearer}"},
            )
        self.assertEqual(resp.status, 403)

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


class RelayMediaByPathPermissiveTests(AioHTTPTestCase):
    """Covers the default (non-strict) /media/by-path behavior.

    In permissive mode the by-path route serves any absolute regular file
    that fits under the size cap, regardless of allowed_roots. This is the
    default on-server behavior — it was introduced to fix the "LLM finds
    a file in ~/projects and the phone says Path not allowed" friction.
    The allowlist is still honored by the token path (/media/register) and
    by by-path when ``RELAY_MEDIA_STRICT_SANDBOX=1`` is set. The always-on
    credential/system denylist (covered by
    :class:`RelayMediaByPathDenylistTests`) is the one exception to
    "any absolute regular file".
    """

    async def get_application(self) -> web.Application:
        self._sandbox = tempfile.mkdtemp(prefix="hermes_relay_permissive_")
        self._outside = tempfile.mkdtemp(prefix="hermes_outside_permissive_")
        self._cleanup_paths: list[str] = [self._sandbox, self._outside]

        config = RelayConfig()
        # Register _sandbox as the allowlist but leave strict_sandbox=False
        # (the production default). by-path should serve files OUTSIDE this
        # list just fine — the registry/register path still enforces it.
        config.media_allowed_roots = [self._sandbox]
        config.media_strict_sandbox = False

        app = create_app(config)
        server = app["server"]
        server.media.allowed_roots = [os.path.realpath(self._sandbox)]
        return app

    async def tearDownAsync(self) -> None:
        await super().tearDownAsync()
        for path in getattr(self, "_cleanup_paths", []):
            shutil.rmtree(path, ignore_errors=True)

    def _server(self):
        return self.app["server"]

    async def _create_session_token(self) -> str:
        session = self._server().sessions.create_session("test-device", "test-id")
        return session.token

    async def test_by_path_outside_allowlist_still_streams_in_permissive_mode(self) -> None:
        """The killer test — regressions here re-break the Bailey workflow.

        A file that lives outside any allowed root should still be served
        when ``strict_sandbox`` is False. This matches real-world LLM emits
        like ``MEDIA:/home/bailey/projects/foo/readme.png``.
        """
        contents = b"\x89PNG\r\n\x1a\npermissive-outside"
        outside_file = _write_file(self._outside, "readme.png", content=contents)
        token_bearer = await self._create_session_token()

        resp = await self.client.get(
            "/media/by-path",
            params={"path": outside_file},
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.headers.get("Content-Type"), "image/png")
        body = await resp.read()
        self.assertEqual(body, contents)

    async def test_by_path_still_rejects_relative_in_permissive_mode(self) -> None:
        """Absolute-path check is unconditional — not gated on strict mode."""
        token_bearer = await self._create_session_token()
        resp = await self.client.get(
            "/media/by-path",
            params={"path": "relative/path.jpg"},
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 403)

    async def test_by_path_still_404s_nonexistent_in_permissive_mode(self) -> None:
        token_bearer = await self._create_session_token()
        resp = await self.client.get(
            "/media/by-path",
            params={"path": os.path.join(self._outside, "ghost.png")},
            headers={"Authorization": f"Bearer {token_bearer}"},
        )
        self.assertEqual(resp.status, 404)

    async def test_register_still_enforces_allowlist_in_permissive_mode(self) -> None:
        """The token path (POST /media/register) is ALWAYS strict regardless.

        The tool-facing loopback route keeps defense-in-depth because it's
        trivially enforceable there — tools register paths via a deterministic
        API surface, unlike the LLM's free-form MEDIA: markers.
        """
        outside_file = _write_file(self._outside, "shot.jpg", content=b"\xff\xd8")
        resp = await self.client.post(
            "/media/register",
            json={
                "path": outside_file,
                "content_type": "image/jpeg",
            },
        )
        # Register still rejects files outside the allowlist — 400 with a
        # validation-error body (handled by handle_media_register).
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertFalse(body["ok"])
        self.assertIn("allowed root", body["error"])


class RelayMediaByPathDenylistTests(AioHTTPTestCase):
    """Always-on credential/system denylist on /media/by-path (HRUI-014).

    Runs in PERMISSIVE mode (``strict_sandbox=False``, the production
    default) — the denylist must hold even with the allowlist off, mirroring
    upstream hermes-agent's media-delivery hardening
    (``gateway/platforms/base.py`` ``validate_media_delivery_path``). A
    prompt-injected ``MEDIA:~/.hermes/mcp-tokens/<server>.json`` marker must
    404/403 rather than hand a paired phone a live credential.

    ``HERMES_HOME`` is pointed at a temp dir for the lifetime of the test
    app so credential fixtures never touch the real ``~/.hermes``.
    """

    async def get_application(self) -> web.Application:
        self._plain_dir = tempfile.mkdtemp(prefix="hermes_denylist_plain_")
        self._hermes_home = tempfile.mkdtemp(prefix="hermes_denylist_home_")
        self._cleanup_paths: list[str] = [self._plain_dir, self._hermes_home]

        # The denylist resolves $HERMES_HOME at check time, so patching the
        # env here covers every request the class makes.
        self._env_patcher = mock.patch.dict(
            os.environ, {"HERMES_HOME": self._hermes_home}
        )
        self._env_patcher.start()

        # Credential fixtures inside the fake Hermes home.
        _write_file(self._hermes_home, ".env", content=b"API_KEY=sk-secret")
        _write_file(self._hermes_home, "auth.json", content=b"{}")
        _write_file(self._hermes_home, "config.yaml", content=b"key: value")
        mcp_dir = os.path.join(self._hermes_home, "mcp-tokens")
        os.makedirs(mcp_dir, exist_ok=True)
        _write_file(mcp_dir, "github.json", content=b'{"access_token":"gho_x"}')
        pairing_dir = os.path.join(self._hermes_home, "pairing")
        os.makedirs(pairing_dir, exist_ok=True)
        _write_file(pairing_dir, "pending.json", content=b"{}")
        # An ordinary (non-credential) file at the Hermes home root — the
        # denylist enumerates credentials per-file/per-dir, it is NOT a
        # whole-tree deny of ~/.hermes.
        _write_file(self._hermes_home, "notes.txt", content=b"deliverable")

        config = RelayConfig()
        config.media_allowed_roots = [self._plain_dir]
        config.media_strict_sandbox = False
        return create_app(config)

    async def tearDownAsync(self) -> None:
        await super().tearDownAsync()
        self._env_patcher.stop()
        for path in getattr(self, "_cleanup_paths", []):
            shutil.rmtree(path, ignore_errors=True)

    # ── Helpers ─────────────────────────────────────────────────────────

    def _server(self):
        return self.app["server"]

    async def _get_by_path(self, path: str):
        token = self._server().sessions.create_session(
            "test-device", "test-id"
        ).token
        return await self.client.get(
            "/media/by-path",
            params={"path": path},
            headers={"Authorization": f"Bearer {token}"},
        )

    # ── Permissive mode stays permissive ────────────────────────────────

    async def test_ordinary_file_still_served(self) -> None:
        contents = b"\x89PNG\r\n\x1a\nordinary-bytes"
        path = _write_file(self._plain_dir, "chart.png", content=contents)
        resp = await self._get_by_path(path)
        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), contents)

    async def test_ordinary_file_in_hermes_home_root_still_served(self) -> None:
        """Per-file enumeration — non-credential ~/.hermes files deliver."""
        resp = await self._get_by_path(
            os.path.join(self._hermes_home, "notes.txt")
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), b"deliverable")

    # ── Credential paths → 403 ──────────────────────────────────────────

    async def test_credential_files_return_403(self) -> None:
        denied = [
            os.path.join(self._hermes_home, ".env"),
            os.path.join(self._hermes_home, "auth.json"),
            os.path.join(self._hermes_home, "config.yaml"),
            os.path.join(self._hermes_home, "mcp-tokens", "github.json"),
            os.path.join(self._hermes_home, "pairing", "pending.json"),
        ]
        for path in denied:
            with self.subTest(path=path):
                resp = await self._get_by_path(path)
                self.assertEqual(resp.status, 403)

    async def test_denied_path_returns_403_even_when_missing(self) -> None:
        """The deny check precedes the existence check — a probe for a
        credential file must not learn whether it exists (403, never 404)."""
        resp = await self._get_by_path(
            os.path.join(self._hermes_home, "credentials")
        )
        self.assertEqual(resp.status, 403)

    async def test_symlink_resolving_into_denied_path_returns_403(self) -> None:
        """Symlinks are resolved before the deny check — an innocent-looking
        link in a deliverable directory can't launder a credential path."""
        target = os.path.join(self._hermes_home, ".env")
        link = os.path.join(self._plain_dir, "innocent.txt")
        try:
            os.symlink(target, link)
        except (OSError, NotImplementedError) as exc:
            # Windows requires SeCreateSymbolicLinkPrivilege / Developer
            # Mode; skip rather than fail on unprivileged runners.
            self.skipTest(f"symlink creation unavailable here: {exc}")
        resp = await self._get_by_path(link)
        self.assertEqual(resp.status, 403)


class MediaDenylistUnitTests(unittest.TestCase):
    """Direct unit coverage of the denylist helpers in plugin.relay.media.

    These pin the home-directory semantics that are awkward to exercise
    through HTTP (they'd require fixtures in the real ``~/.ssh``): denied
    home subpaths, denied system prefixes, and the own-home exemption.
    """

    def _patched_home_env(self, home: str) -> dict[str, str]:
        # posixpath.expanduser reads HOME; ntpath.expanduser prefers
        # USERPROFILE — set both so the test is platform-agnostic.
        return {"HOME": home, "USERPROFILE": home}

    def test_home_credential_dirs_are_denied(self) -> None:
        fake_home = tempfile.mkdtemp(prefix="hermes_denylist_userhome_")
        self.addCleanup(shutil.rmtree, fake_home, ignore_errors=True)
        ssh_dir = os.path.join(fake_home, ".ssh")
        os.makedirs(ssh_dir)
        key = _write_file(ssh_dir, "id_rsa", content=b"PRIVATE KEY")
        plain = _write_file(fake_home, "report.pdf", content=b"%PDF")

        with mock.patch.dict(os.environ, self._patched_home_env(fake_home)):
            os.environ.pop("HERMES_HOME", None)
            os.environ.pop("HERMES_RELAY_HOME", None)
            self.assertTrue(
                media._is_denied_media_path(os.path.realpath(key))
            )
            # A plain file directly in the home tree stays deliverable.
            self.assertFalse(
                media._is_denied_media_path(os.path.realpath(plain))
            )

    def test_system_prefixes_are_denied(self) -> None:
        # realpath maps "/etc/passwd" onto the current drive on Windows,
        # matching how the denylist itself resolves "/etc" — so this holds
        # cross-platform without the file existing.
        probe = os.path.realpath(os.path.join("/etc", "passwd"))
        self.assertTrue(media._is_denied_media_path(probe))

    def test_denied_prefix_equal_to_own_home_is_exempt(self) -> None:
        """/root is denied so a non-root relay can't serve root's home, but
        on a root-run relay ($HOME=/root) the operator's own plain files
        stay deliverable — while credential subpaths remain blocked."""
        root_home = os.path.realpath("/root")
        with mock.patch.dict(os.environ, self._patched_home_env(root_home)):
            os.environ.pop("HERMES_HOME", None)
            os.environ.pop("HERMES_RELAY_HOME", None)
            self.assertFalse(
                media._is_denied_media_path(
                    os.path.join(root_home, "deliverable.txt")
                )
            )
            self.assertTrue(
                media._is_denied_media_path(
                    os.path.join(root_home, ".ssh", "id_rsa")
                )
            )


if __name__ == "__main__":
    unittest.main()
