"""Real RSA/JWKS, CSRF, per-download provisioning and cleanup contracts."""
from __future__ import annotations

import asyncio
from dataclasses import asdict
import io
import json
from pathlib import Path
import re
import secrets
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives.asymmetric import rsa
import jwt

from plugin.connector_portal import (
    AccessJWTVerifier, CSRF, DownloadLimiter, PortalConfig,
    build_download_archive, create_app, validate_template,
)


def template(path: Path) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in ("Install.cmd", "install.ps1", "hermes-relay.exe", "manifest.json", "Stop.cmd", "Status.cmd"):
            archive.writestr(name, b"test installer content\x00" * 32)


def config(path: Path, **kwargs: object) -> PortalConfig:
    values = {"public_origin": "https://admin.example.test", "relay_url": "wss://relay.example.test/ws",
              "access_issuer": "https://example.cloudflareaccess.com", "access_audience": "test-application",
              "base_zip_path": path}
    values.update(kwargs)
    return PortalConfig(**values)


class ConfigTests(unittest.TestCase):
    def test_private_configuration_and_fixed_network_destinations(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "config.json"
            data = asdict(config(root / "template.zip"))
            data["base_zip_path"] = str(data["base_zip_path"])
            path.write_text(json.dumps(data))
            path.chmod(0o644)
            with self.assertRaisesRegex(ValueError, "owner-only"):
                PortalConfig.from_file(path)
            path.chmod(0o600)
            self.assertEqual(PortalConfig.from_file(path).relay_url, data["relay_url"])
            link = root / "symlink.json"
            link.symlink_to(path)
            with self.assertRaises(OSError):
                PortalConfig.from_file(link)
            for changes in ({"access_issuer": "https://evil.test"}, {"relay_url": "ws://relay.example.test/ws"},
                            {"invite_url": "http://192.0.2.1:8767/connector/invite"},
                            {"invite_url": "http://127.0.0.1:8767/connector/invite?x=1"}):
                with self.assertRaises(ValueError):
                    config(root / "template.zip", **changes)

    def test_csrf_is_bound_to_identity_expiry_and_service_instance(self) -> None:
        csrf = CSRF()
        with patch("plugin.connector_portal.time.time", return_value=1000):
            token = csrf.issue("user-one")
            self.assertTrue(csrf.valid(token, "user-one"))
            self.assertFalse(csrf.valid(token, "user-two"))
            self.assertFalse(CSRF().valid(token, "user-one"))
            self.assertFalse(csrf.valid(token + "corrupt", "user-one"))
        with patch("plugin.connector_portal.time.time", return_value=1900):
            self.assertFalse(csrf.valid(token, "user-one"))

    def test_limiters_bound_concurrency_and_identity_window(self) -> None:
        limiter = DownloadLimiter(config(Path("/template.zip"), downloads_per_window=1, max_concurrent_downloads=1))
        with patch("plugin.connector_portal.time.monotonic", return_value=1000):
            limiter.acquire("user-one")
            with self.assertRaises(web.HTTPServiceUnavailable):
                limiter.acquire("user-two")
            limiter.active -= 1
            with self.assertRaises(web.HTTPTooManyRequests):
                limiter.acquire("user-one")
        with patch("plugin.connector_portal.time.monotonic", return_value=1601):
            limiter.acquire("user-one")


class ArchiveTests(unittest.IsolatedAsyncioTestCase):
    async def test_two_downloads_have_distinct_invites_without_mutating_template(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root / "base.zip"
            template(base)
            original = base.read_bytes()
            for index in range(2):
                destination = root / f"{index}.zip"
                await build_download_archive(base, destination, relay_url="wss://relay.example.test/ws",
                                             invite=str(index) * 64, expires_at=2000)
                with zipfile.ZipFile(destination) as archive:
                    setup = json.loads(archive.read("setup.json"))
                    self.assertEqual(setup, {"version": 1, "relay_url": "wss://relay.example.test/ws",
                                             "invite": str(index) * 64, "expires_at": 2000})
                    self.assertIsNone(archive.testzip())
                    self.assertEqual(archive.read("hermes-relay.exe"), b"test installer content\x00" * 32)
            self.assertNotEqual((root / "0.zip").read_bytes(), (root / "1.zip").read_bytes())
            self.assertEqual(base.read_bytes(), original)

    async def test_preprovisioned_or_ambiguous_template_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "template.zip"
            for bad in ("setup.json", "SETUP.JSON", "./setup.json", "setup.json.", "../setup.json", "a\\setup.json"):
                template(path)
                with zipfile.ZipFile(path, "a") as archive:
                    archive.writestr(bad, "secret")
                with self.assertRaises(ValueError, msg=bad):
                    validate_template(path)


class PortalNetworkTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.jwk = jwt.algorithms.RSAAlgorithm.to_jwk(cls.private_key.public_key(), as_dict=True)
        cls.jwk.update({"kid": "test-key", "alg": "RS256", "use": "sig"})

    async def asyncSetUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name) / "base.zip"
        template(self.base)
        self.config = config(self.base)
        self.invites: list[str] = []
        self.local_headers: list[dict[str, str]] = []
        self.jwks_calls = 0
        self.jwks_status = 200
        self.split_jwks = False
        self.invite_document: object | None = None
        self.invite_wait: asyncio.Event | None = None
        self.invite_started = asyncio.Event()

        async def certs(request: web.Request) -> web.StreamResponse:
            self.jwks_calls += 1
            if self.jwks_status != 200:
                return web.Response(status=self.jwks_status, headers={"Location": "/redirect-target"})
            data = json.dumps({"keys": [self.jwk]}).encode()
            if self.split_jwks:
                response = web.StreamResponse(headers={"Content-Type": "application/json"})
                await response.prepare(request)
                await response.write(data[:20])
                await asyncio.sleep(0.01)
                await response.write(data[20:])
                await response.write_eof()
                return response
            return web.Response(body=data, content_type="application/json")

        async def invitation(request: web.Request) -> web.Response:
            self.local_headers.append(dict(request.headers))
            self.invite_started.set()
            if self.invite_wait is not None:
                await self.invite_wait.wait()
            invite = secrets.token_hex(32)
            self.invites.append(invite)
            return web.json_response(self.invite_document if self.invite_document is not None
                                     else {"invite": invite, "expires_in": 3600})

        upstream = web.Application()
        upstream.router.add_get("/certs", certs)
        upstream.router.add_post("/connector/invite", invitation)
        self.upstream = TestServer(upstream)
        await self.upstream.start_server()
        self.addAsyncCleanup(self.upstream.close)
        # Only tests substitute the fixed production destinations with a local wire server.
        object.__setattr__(self.config, "invite_url", str(self.upstream.make_url("/connector/invite")))
        self.app = create_app(self.config)
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.verifier = next(value for value in self.app.values() if isinstance(value, AccessJWTVerifier))
        self.verifier._jwks_url = str(self.upstream.make_url("/certs"))
        self.temp_path = next(value for value in self.app.values() if isinstance(value, Path))

    def token(self, **changes: object) -> str:
        claims = {"iss": self.config.access_issuer, "aud": [self.config.access_audience],
                  "exp": int(time.time()) + 3600, "iat": int(time.time()), "sub": "test-user", "type": "app"}
        claims.update(changes)
        return jwt.encode(claims, self.private_key, algorithm="RS256", headers={"kid": "test-key"})

    async def form(self, token: str | None = None) -> tuple[str, dict[str, str]]:
        headers = {"CF-Access-Jwt-Assertion": token or self.token(), "Origin": self.config.public_origin}
        response = await self.client.get("/relay/private/", headers=headers)
        self.assertEqual(response.status, 200, await response.text())
        text = await response.text()
        match = re.search(r'name="csrf" value="([^"]+)"', text)
        assert match is not None
        return match[1], headers

    async def test_anonymous_or_forged_assertions_fail_before_invitation(self) -> None:
        response = await self.client.get("/relay/")
        self.assertEqual(response.status, 200)
        for headers in ({}, {"CF-Access-Jwt-Assertion": "not.a.jwt"}):
            for method, path in ((self.client.get, "/relay/private/"), (self.client.post, "/relay/private/download")):
                response = await method(path, headers=headers)
                self.assertEqual(response.status, 401)
                self.assertIn("no-store", response.headers["Cache-Control"])
        self.assertEqual(self.invites, [])
        self.assertEqual(self.jwks_calls, 0)

    async def test_real_jwks_requires_signature_issuer_audience_expiry_subject_and_type(self) -> None:
        self.split_jwks = True
        self.assertEqual(await self.verifier.verify(self.token()), "test-user")
        for changes in ({"iss": "https://evil.test"}, {"aud": ["another-application"]},
                        {"exp": int(time.time()) - 1}, {"sub": ""}, {"type": "service"}):
            with self.assertRaises(web.HTTPUnauthorized, msg=str(changes)):
                await self.verifier.verify(self.token(**changes))
        claims = jwt.decode(self.token(), options={"verify_signature": False})
        del claims["sub"]
        bad_tokens = [jwt.encode(claims, self.private_key, algorithm="RS256", headers={"kid": "test-key"}),
                      jwt.encode({"sub": "user"}, self.other_key, algorithm="RS256", headers={"kid": "test-key"}),
                      jwt.encode({"sub": "user"}, "x" * 32, algorithm="HS256", headers={"kid": "test-key"}),
                      jwt.encode({"sub": "user"}, self.private_key, algorithm="RS256", headers={"kid": "unknown"})]
        for token in bad_tokens:
            with self.assertRaises(web.HTTPUnauthorized):
                await self.verifier.verify(token)
        self.assertEqual(self.jwks_calls, 1)

    async def test_jwks_redirects_and_unavailable_keys_fail_closed_without_request_flood(self) -> None:
        self.jwks_status = 302
        with self.assertRaises(web.HTTPUnauthorized):
            await self.verifier.verify(self.token())
        self.jwks_status = 200
        with self.assertRaises(web.HTTPUnauthorized):
            await self.verifier.verify(self.token())
        self.assertEqual(self.jwks_calls, 1)
        self.verifier._attempted_at -= 11
        self.assertEqual(await self.verifier.verify(self.token()), "test-user")
        self.verifier._fetched_at -= 301
        self.verifier._attempted_at -= 11
        self.jwks_status = 503
        with self.assertRaises(web.HTTPUnauthorized):
            await self.verifier.verify(self.token())

    async def test_csrf_and_origin_denials_do_not_mint(self) -> None:
        token, headers = await self.form()
        for origin in (None, "https://evil.test", "null"):
            bad = dict(headers)
            if origin is None:
                del bad["Origin"]
            else:
                bad["Origin"] = origin
            response = await self.client.post("/relay/private/download", headers=bad, data={"csrf": token})
            self.assertEqual(response.status, 403)
        for value in ("", token + "invalid"):
            response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": value})
            self.assertEqual(response.status, 403)
        bad = {**headers, "CF-Access-Jwt-Assertion": self.token(sub="another-user")}
        response = await self.client.post("/relay/private/download", headers=bad, data={"csrf": token})
        self.assertEqual(response.status, 403)
        self.assertEqual(self.invites, [])

    async def test_downloads_are_unique_nostore_and_local_headers_never_forward(self) -> None:
        csrf, headers = await self.form()
        before = int(time.time())
        results: list[bytes] = []
        for _ in range(2):
            response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
            self.assertEqual(response.status, 200, await response.text() if response.status != 200 else "")
            self.assertEqual(response.headers["Content-Type"], "application/zip")
            self.assertIn("no-store", response.headers["Cache-Control"])
            self.assertNotIn("invite", str(response.headers).lower())
            body = await response.read()
            self.assertEqual(len(body), int(response.headers["Content-Length"]))
            results.append(body)
            with zipfile.ZipFile(io.BytesIO(body)) as archive:
                self.assertIsNone(archive.testzip())
                setup = json.loads(archive.read("setup.json"))
                self.assertEqual(setup["invite"], self.invites[-1])
                self.assertRegex(setup["invite"], r"^[a-f0-9]{64}$")
                self.assertLessEqual(before + 3600, setup["expires_at"])
                self.assertLessEqual(setup["expires_at"], int(time.time()) + 3600)
            self.assertEqual(list(self.temp_path.iterdir()), [])
        self.assertNotEqual(results[0], results[1])
        for local_headers in self.local_headers:
            for forbidden in ("Origin", "Referer", "CF-Access-Jwt-Assertion", "Cookie", "X-Forwarded-For"):
                self.assertNotIn(forbidden, local_headers)
        with zipfile.ZipFile(self.base) as archive:
            self.assertNotIn("setup.json", archive.namelist())

    async def test_bad_local_invitation_never_leaks_or_leaves_an_archive(self) -> None:
        csrf, headers = await self.form()
        for document in ({"invite": "bad", "expires_in": 3600}, {"invite": "a" * 64, "expires_in": 3601}, []):
            self.invite_document = document
            response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
            self.assertEqual(response.status, 503)
            self.assertNotIn("a" * 64, await response.text())
            self.assertEqual(list(self.temp_path.iterdir()), [])

    async def test_download_uses_the_returned_invitation_lifetime(self) -> None:
        self.invite_document = {"invite": "a" * 64, "expires_in": 1200}
        csrf, headers = await self.form()
        before = int(time.time())
        response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
        self.assertEqual(response.status, 200)
        with zipfile.ZipFile(io.BytesIO(await response.read())) as archive:
            setup = json.loads(archive.read("setup.json"))
        self.assertLessEqual(before + 1200, setup["expires_at"])
        self.assertLessEqual(setup["expires_at"], int(time.time()) + 1200)

    async def test_invalid_invitation_lifetimes_are_rejected(self) -> None:
        object.__setattr__(self.config, "downloads_per_window", 20)
        csrf, headers = await self.form()
        for lifetime in (None, True, False, 0, -1, 3601, 3600.0, "3600"):
            self.invite_document = {"invite": "a" * 64, "expires_in": lifetime}
            response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
            self.assertEqual(response.status, 503)
            self.assertEqual(list(self.temp_path.iterdir()), [])

    async def test_archive_failure_cleanup_and_identity_rate_limit(self) -> None:
        csrf, headers = await self.form()
        async def fail(source: Path, destination: Path, **kwargs: object) -> None:
            self.assertEqual(destination.stat().st_mode & 0o777, 0o600)
            self.assertEqual(destination.parent.stat().st_mode & 0o777, 0o700)
            destination.write_text("partial secret content")
            raise OSError("disk unavailable")
        with patch("plugin.connector_portal.build_download_archive", side_effect=fail):
            response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
        self.assertEqual(response.status, 503)
        self.assertEqual(list(self.temp_path.iterdir()), [])
        for _ in range(2):
            response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
            self.assertEqual(response.status, 200)
            await response.read()
        response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
        self.assertEqual(response.status, 429)
        self.assertEqual(len(self.invites), 3)

    async def test_concurrent_limit_rejects_before_minting_more_invites(self) -> None:
        csrf, headers = await self.form()
        self.invite_wait = asyncio.Event()
        running = [asyncio.create_task(self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})) for _ in range(2)]
        await asyncio.wait_for(self.invite_started.wait(), 2)
        # Both handler slots are acquired before their outbound requests yield.
        await asyncio.sleep(0.02)
        response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
        self.assertEqual(response.status, 503)
        self.invite_wait.set()
        for task in running:
            response = await task
            self.assertEqual(response.status, 200)
            await response.read()
        self.assertEqual(len(self.invites), 2)
        self.assertEqual(list(self.temp_path.iterdir()), [])

    async def test_interrupted_stream_removes_private_archive_and_releases_slot(self) -> None:
        csrf, headers = await self.form()
        async def large_archive(source: Path, destination: Path, **kwargs: object) -> None:
            with destination.open("wb") as stream:
                for _ in range(128):
                    stream.write(b"x" * (256 * 1024))
        with patch("plugin.connector_portal.build_download_archive", side_effect=large_archive):
            response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
            self.assertEqual(response.status, 200)
            response.close()
            for _ in range(100):
                if not list(self.temp_path.iterdir()):
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(list(self.temp_path.iterdir()), [])
        # A disconnected download must not hold one of the concurrency slots.
        response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
        self.assertEqual(response.status, 200)
        await response.read()

    async def test_private_page_aliases_are_authenticated_and_get_never_mints(self) -> None:
        for path in ("/relay/private/", "/relay/private", "/relay/private/download"):
            response = await self.client.get(path)
            self.assertEqual(response.status, 401)
            self.assertEqual(response.content_type, "text/html")
            self.assertIn("返回下载页", await response.text())
            response = await self.client.get(path, headers={"CF-Access-Jwt-Assertion": self.token()})
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers["Referrer-Policy"], "same-origin")
            self.assertIn("no-store", response.headers["Cache-Control"])
            body = await response.text()
            self.assertIn('<form method="post" action="/relay/private/download">', body)
            self.assertIn('name="csrf"', body)
            self.assertIn("下载 Windows 安装包", body)
        self.assertEqual(self.invites, [])

    async def test_recovery_pages_preserve_status_headers_and_hide_exception_details(self) -> None:
        csrf, headers = await self.form()
        sentinel = "private-assertion-and-invitation-must-not-appear"
        for error, status, retry in (
            (web.HTTPUnauthorized(text=sentinel), 401, None),
            (web.HTTPForbidden(text=sentinel), 403, None),
            (web.HTTPTooManyRequests(text=sentinel, headers={"Retry-After": "600"}), 429, "600"),
            (web.HTTPServiceUnavailable(text=sentinel, headers={"Retry-After": "10"}), 503, "10"),
        ):
            with patch("plugin.connector_portal.DownloadLimiter.acquire", side_effect=error):
                response = await self.client.post("/relay/private/download", headers=headers, data={"csrf": csrf})
            self.assertEqual(response.status, status)
            self.assertEqual(response.content_type, "text/html")
            self.assertEqual(response.headers.get("Retry-After"), retry)
            self.assertEqual(response.headers["Referrer-Policy"], "same-origin")
            self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")
            self.assertIn("no-store", response.headers["Cache-Control"])
            body = await response.text()
            self.assertIn('<a href="/relay/private/">返回下载页</a>', body)
            for private in (sentinel, headers["CF-Access-Jwt-Assertion"], csrf):
                self.assertNotIn(private, body)
        self.assertEqual(self.invites, [])
