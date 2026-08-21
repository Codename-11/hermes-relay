"""Tests for provider-neutral account usage and the paired-device endpoint."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.provider_usage import (
    collect_provider_usage,
    fetch_opencode_go_usage,
    resolve_profile_home,
    serialize_account_snapshot,
    unavailable_provider,
)
from plugin.relay.server import create_app


class _FakeResponse:
    def __init__(self, status: int = 200, payload: dict | None = None):
        self.status = status
        self._payload = payload or {}

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def json(self):
        return self._payload


class _FakeSession:
    def __init__(self, response: _FakeResponse):
        self.response = response
        self.headers: dict | None = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def get(self, _url, *, headers=None, timeout=None):
        self.headers = headers
        return self.response


class ProviderUsageModelTests(unittest.IsolatedAsyncioTestCase):
    def test_profile_home_is_exact_and_rejects_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "config.yaml").write_text("model: {}\n", encoding="utf-8")
            victor = root / "profiles" / "victor"
            victor.mkdir(parents=True)
            (victor / "config.yaml").write_text("model: {}\n", encoding="utf-8")
            self.assertEqual(resolve_profile_home(str(root / "config.yaml"), "Victor"), victor)
            with self.assertRaises(ValueError):
                resolve_profile_home(str(root / "config.yaml"), "../victor")

    def test_serializes_upstream_snapshot_without_credentials(self) -> None:
        snapshot = SimpleNamespace(
            available=True,
            source="usage_api",
            fetched_at=datetime(2026, 8, 21, tzinfo=timezone.utc),
            plan="Plus",
            windows=(
                SimpleNamespace(
                    label="Session",
                    used_percent=42.5,
                    reset_at=datetime(2026, 8, 22, tzinfo=timezone.utc),
                    detail=None,
                ),
            ),
            details=("Credits balance: $4.20",),
        )
        result = serialize_account_snapshot(
            snapshot,
            provider_id="openai-codex",
            display_name="Codex",
        )
        self.assertEqual(result["status"], "available")
        self.assertEqual(result["windows"][0]["used_percent"], 42.5)
        self.assertEqual(result["plan"], "Plus")
        self.assertNotIn("token", result)

    async def test_opencode_missing_key_is_not_configured(self) -> None:
        result = await fetch_opencode_go_usage(credential_resolver=lambda _provider: {})
        self.assertEqual(result["id"], "opencode-go")
        self.assertEqual(result["status"], "not_configured")

    async def test_opencode_normalizes_windows_without_inventing_dollars(self) -> None:
        fake = _FakeSession(
            _FakeResponse(
                payload={
                    "usage": {
                        "rolling": {"percent": 42, "resetsAt": "2026-08-22T00:00:00Z"},
                        "weekly": {"percent": 18},
                    }
                }
            )
        )
        result = await fetch_opencode_go_usage(
            session_factory=lambda: fake,
            credential_resolver=lambda _provider: {
                "api_key": "secret",
                "base_url": "https://opencode.ai/zen/go/v1",
            },
        )
        self.assertEqual(result["status"], "available")
        self.assertEqual([row["id"] for row in result["windows"]], ["rolling", "weekly"])
        self.assertNotIn("limits", result)
        self.assertEqual(fake.headers["Authorization"], "Bearer secret")

    async def test_collection_keeps_provider_order_and_schema(self) -> None:
        async def codex(_home):
            return unavailable_provider("openai-codex", "Codex")

        async def nous(_home):
            return unavailable_provider("nous", "Nous")

        async def opencode(*, profile_home=None):
            return unavailable_provider("opencode-go", "OpenCode Go")

        result = await collect_provider_usage(
            codex_fetcher=codex,
            nous_fetcher=nous,
            opencode_fetcher=opencode,
        )
        self.assertEqual(result["schema_version"], 1)
        self.assertEqual(
            [row["id"] for row in result["providers"]],
            ["openai-codex", "nous", "opencode-go"],
        )


class ProviderUsageEndpointTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        return create_app(RelayConfig(provider_usage_enabled=self.usage_enabled))

    @property
    def usage_enabled(self) -> bool:
        return True

    def _server(self):
        return self.app["server"]

    async def _mint(self) -> str:
        return self._server().sessions.create_session("phone", "device").token

    async def test_requires_bearer(self) -> None:
        response = await self.client.get("/usage/providers")
        self.assertEqual(response.status, 401)

    @mock.patch(
        "plugin.relay.server.collect_provider_usage",
        new=mock.AsyncMock(return_value={"schema_version": 1, "providers": []}),
    )
    async def test_returns_normalized_payload(self) -> None:
        token = await self._mint()
        response = await self.client.get(
            "/usage/providers",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status, 200)
        self.assertEqual((await response.json())["schema_version"], 1)


class ProviderUsageDisabledEndpointTests(ProviderUsageEndpointTests):
    @property
    def usage_enabled(self) -> bool:
        return False

    async def test_returns_normalized_payload(self) -> None:
        token = await self._mint()
        response = await self.client.get(
            "/usage/providers",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status, 404)


if __name__ == "__main__":
    unittest.main()
