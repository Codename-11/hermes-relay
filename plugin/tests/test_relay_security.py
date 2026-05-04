"""Tests for runtime relay security toggles and their CLI wrapper."""

from __future__ import annotations

import argparse
import io
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin import cli
from plugin.relay.config import RelayConfig
from plugin.relay.server import (
    create_app,
    handle_relay_security_get,
    handle_relay_security_patch,
)


class RelaySecurityRouteTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        return create_app(RelayConfig())

    async def test_get_returns_runtime_security_flags(self) -> None:
        resp = await self.client.get("/relay/security")

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["ok"])
        self.assertEqual(body["scope"], "runtime")
        self.assertFalse(body["allow_insecure_api_bearer"])
        self.assertFalse(body["trust_proxy_headers"])

    async def test_patch_toggles_insecure_api_bearer_immediately(self) -> None:
        server = self.app["server"]
        self.assertFalse(server.config.allow_insecure_api_bearer)

        resp = await self.client.patch(
            "/relay/security",
            json={"allow_insecure_api_bearer": True},
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["allow_insecure_api_bearer"])
        self.assertTrue(server.config.allow_insecure_api_bearer)

        resp = await self.client.patch(
            "/relay/security",
            json={"allow_insecure_api_bearer": False},
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertFalse(body["allow_insecure_api_bearer"])
        self.assertFalse(server.config.allow_insecure_api_bearer)

    async def test_patch_rejects_missing_field(self) -> None:
        resp = await self.client.patch("/relay/security", json={})

        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertFalse(body["ok"])
        self.assertIn("allow_insecure_api_bearer", body["error"])

    async def test_patch_rejects_non_boolean_field(self) -> None:
        resp = await self.client.patch(
            "/relay/security",
            json={"allow_insecure_api_bearer": "true"},
        )

        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertFalse(body["ok"])
        self.assertIn("boolean", body["error"])


class RelaySecurityNonLoopbackTests(unittest.IsolatedAsyncioTestCase):
    async def test_get_rejects_non_loopback(self) -> None:
        app = create_app(RelayConfig())

        class _Req:
            def __init__(self) -> None:
                self.remote = "10.1.2.3"
                self.app = app

        with self.assertRaises(web.HTTPForbidden):
            await handle_relay_security_get(_Req())  # type: ignore[arg-type]

    async def test_patch_rejects_non_loopback_before_reading_body(self) -> None:
        app = create_app(RelayConfig())

        class _Req:
            def __init__(self) -> None:
                self.remote = "10.1.2.3"
                self.app = app

        with self.assertRaises(web.HTTPForbidden):
            await handle_relay_security_patch(_Req())  # type: ignore[arg-type]


def _args(**overrides: object) -> types.SimpleNamespace:
    defaults: dict[str, object] = {
        "state": "status",
        "host": "127.0.0.1",
        "port": 8767,
        "json": False,
    }
    defaults.update(overrides)
    return types.SimpleNamespace(**defaults)


class RelayInsecureApiKeyCliTests(unittest.TestCase):
    def test_relay_parser_registers_insecure_api_key_command(self) -> None:
        parser = argparse.ArgumentParser()
        cli.register_relay_cli(parser)

        args = parser.parse_args(["insecure-api-key", "on"])

        self.assertEqual(args.state, "on")
        self.assertIs(args.func, cli.relay_insecure_api_key_command)

    def test_status_calls_get_and_prints_state(self) -> None:
        captured: dict[str, object] = {}

        def fake_request(**kwargs):
            captured.update(kwargs)
            return {"ok": True, "allow_insecure_api_bearer": False}

        with patch.object(cli, "_relay_security_request", side_effect=fake_request):
            out = io.StringIO()
            with redirect_stdout(out):
                cli.relay_insecure_api_key_command(_args())

        self.assertIsNone(captured["allow_insecure_api_bearer"])
        self.assertIn("disabled", out.getvalue())

    def test_on_patches_true(self) -> None:
        captured: dict[str, object] = {}

        def fake_request(**kwargs):
            captured.update(kwargs)
            return {"ok": True, "allow_insecure_api_bearer": True}

        with patch.object(cli, "_relay_security_request", side_effect=fake_request):
            out = io.StringIO()
            with redirect_stdout(out):
                cli.relay_insecure_api_key_command(_args(state="on"))

        self.assertIs(captured["allow_insecure_api_bearer"], True)
        self.assertIn("enabled", out.getvalue())
        self.assertIn("off", out.getvalue())

    def test_off_patches_false(self) -> None:
        captured: dict[str, object] = {}

        def fake_request(**kwargs):
            captured.update(kwargs)
            return {"ok": True, "allow_insecure_api_bearer": False}

        with patch.object(cli, "_relay_security_request", side_effect=fake_request):
            out = io.StringIO()
            with redirect_stdout(out):
                cli.relay_insecure_api_key_command(_args(state="off"))

        self.assertIs(captured["allow_insecure_api_bearer"], False)
        self.assertIn("disabled", out.getvalue())

    def test_unreachable_exits_one(self) -> None:
        with patch.object(
            cli,
            "_relay_security_request",
            side_effect=RuntimeError("relay down"),
        ):
            err = io.StringIO()
            with redirect_stderr(err), self.assertRaises(SystemExit) as ctx:
                cli.relay_insecure_api_key_command(_args(state="on"))

        self.assertEqual(ctx.exception.code, 1)
        self.assertIn("relay down", err.getvalue())


if __name__ == "__main__":
    unittest.main()
