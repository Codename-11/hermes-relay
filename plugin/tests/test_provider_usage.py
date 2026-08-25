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
    fetch_codex_usage,
    fetch_nous_usage,
    fetch_opencode_go_usage,
    resolve_profile_home,
    serialize_account_snapshot,
    unavailable_provider,
)
from plugin.relay.active_credentials import record_active_credential
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

    async def test_nous_exposes_structured_balances_without_raw_mobile_details(self) -> None:
        account = SimpleNamespace(
            logged_in=True,
            paid_service_access=True,
            paid_service_access_info=SimpleNamespace(
                subscription_credits_remaining=31.98,
                purchased_credits_remaining=0.0,
                total_usable_credits=31.98,
            ),
            subscription=SimpleNamespace(
                plan="Plus",
                monthly_credits=None,
                credits_remaining=31.98,
                rollover_credits=10.0,
                current_period_end="2026-09-18T00:11:42.000Z",
            ),
            portal_base_url="https://portal.nousresearch.com",
            org_slug="example",
        )

        result = await fetch_nous_usage(
            account_fetcher=lambda **_kwargs: account,
        )

        self.assertEqual(result["status"], "available")
        self.assertEqual(result["plan"], "Plus")
        self.assertEqual(result["balances"][0], {
            "id": "total",
            "label": "Total usable",
            "amount": 31.98,
            "currency": "USD",
        })
        self.assertEqual(result["renews_at"], "2026-09-18T00:11:42.000Z")
        self.assertTrue(result["action_url"].endswith("/orgs/example/billing?topup=open"))
        self.assertEqual(result["details"], [])

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
        async def codex(_home, **_kwargs):
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
        self.assertEqual(result["schema_version"], 2)
        self.assertEqual(
            result["capabilities"],
            ["credential_pools", "structured_balances", "opencode_go"],
        )
        self.assertEqual(
            [row["id"] for row in result["providers"]],
            ["openai-codex", "nous", "opencode-go"],
        )

    async def test_codex_pool_marks_exact_live_session_credential_active(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            record_active_credential(
                home,
                session_id="session-2",
                provider_id="openai-codex",
                credential_id="entry-2",
            )
            entries = [
                SimpleNamespace(
                    id=f"entry-{index}",
                    label=f"Account {index}",
                    last_status="ok",
                    last_status_at=None,
                    last_error_reset_at=None,
                    runtime_base_url="https://chatgpt.com/backend-api/codex",
                    runtime_api_key=f"secret-{index}",
                )
                for index in (1, 2)
            ]
            snapshots = {
                "secret-1": SimpleNamespace(
                    available=True,
                    source="usage_api",
                    fetched_at=datetime(2026, 8, 21, tzinfo=timezone.utc),
                    plan="Pro",
                    windows=(SimpleNamespace(label="Session", used_percent=100, reset_at=None, detail=None),),
                    details=(),
                ),
                "secret-2": SimpleNamespace(
                    available=True,
                    source="usage_api",
                    fetched_at=datetime(2026, 8, 21, tzinfo=timezone.utc),
                    plan="Pro",
                    windows=(SimpleNamespace(label="Session", used_percent=24, reset_at=None, detail=None),),
                    details=(),
                ),
            }

            result = await fetch_codex_usage(
                home,
                session_id="session-2",
                pool_loader=lambda _provider: SimpleNamespace(entries=lambda: entries),
                snapshot_fetcher=lambda *, api_key, base_url: snapshots[api_key],
            )

        self.assertEqual(result["active_credential_state"], "known")
        active = next(row for row in result["credentials"] if row["active"])
        self.assertEqual(active["label"], "Account 2")
        self.assertEqual(active["windows"][0]["used_percent"], 24.0)
        limited = next(row for row in result["credentials"] if row["label"] == "Account 1")
        self.assertEqual(limited["status"], "at_limit")
        self.assertNotIn("secret-2", str(result))


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

    async def test_invalid_profile_error_does_not_reflect_request_input(self) -> None:
        token = await self._mint()
        response = await self.client.get(
            "/usage/providers?profile=../private-token",
            headers={"Authorization": f"Bearer {token}"},
        )

        body = await response.text()
        self.assertEqual(response.status, 400 if self.usage_enabled else 404)
        if self.usage_enabled:
            self.assertEqual(body, "invalid or unknown profile")
        self.assertNotIn("private-token", body)

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
