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
    fetch_supergrok_usage,
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


class _SequencedSession:
    """Yield queued responses in call order, like aiohttp's request context manager."""

    def __init__(self, responses: list[_FakeResponse]):
        self._responses = list(responses)
        self.calls: list[dict] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def get(self, url, *, headers=None, timeout=None):
        self.calls.append({"url": url, "headers": headers or {}})
        if not self._responses:
            raise AssertionError("unexpected extra provider request")
        return self._responses.pop(0)


class ProviderUsageModelTests(unittest.IsolatedAsyncioTestCase):
    def test_profile_home_is_exact_and_rejects_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            # Resolve the temp root so macOS /var -> /private/var matches
            # Path.resolve() inside resolve_profile_home.
            root = Path(raw).resolve()
            (root / "config.yaml").write_text("model: {}\n", encoding="utf-8")
            victor = (root / "profiles" / "victor").resolve()
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

    async def test_supergrok_without_oauth_is_not_configured(self) -> None:
        result = await fetch_supergrok_usage(credential_resolver=lambda: {})

        self.assertEqual(result["id"], "supergrok")
        self.assertEqual(result["status"], "not_configured")
        self.assertEqual(result["windows"], [])

    async def test_supergrok_missing_oauth_state_is_not_configured(self) -> None:
        class MissingOAuthState(Exception):
            code = "xai_auth_missing"

        def resolve_credentials() -> dict:
            raise MissingOAuthState("No credentials stored")

        result = await fetch_supergrok_usage(credential_resolver=resolve_credentials)

        self.assertEqual(result["status"], "not_configured")

    async def test_supergrok_oauth_refresh_failure_is_unavailable(self) -> None:
        class RefreshFailure(Exception):
            code = "xai_refresh_failed"

        def resolve_credentials() -> dict:
            raise RefreshFailure("private token details")

        result = await fetch_supergrok_usage(credential_resolver=resolve_credentials)

        self.assertEqual(result["status"], "unavailable")
        self.assertEqual(result["message"], "Could not resolve SuperGrok credentials")
        self.assertNotIn("private token details", str(result))

    async def test_supergrok_maps_subscription_and_product_windows(self) -> None:
        session = _SequencedSession(
            [
                _FakeResponse(payload={"userId": "user-1"}),
                _FakeResponse(
                    payload={
                        "subscriptionTier": "SuperGrok",
                        "onDemandEnabled": True,
                        "config": {
                            "creditUsagePercent": 14,
                            "currentPeriod": {
                                "type": "USAGE_PERIOD_TYPE_WEEKLY",
                                "start": "2026-09-06T08:34:12.348291+00:00",
                                "end": "2026-09-13T08:34:12.348291+00:00",
                            },
                            "productUsage": [
                                {"product": "GrokBuild", "usagePercent": 11},
                                {"product": "GrokImagine", "usagePercent": 2},
                                {"product": "GrokChat", "usagePercent": None},
                            ],
                            "onDemandCap": {"val": 500},
                            "onDemandUsed": {"val": 125},
                            "prepaidBalance": {"val": 0},
                        },
                    }
                ),
            ]
        )

        result = await fetch_supergrok_usage(
            session_factory=lambda: session,
            credential_resolver=lambda: {"api_key": "secret"},
        )

        self.assertEqual(result["status"], "available")
        self.assertEqual(result["source"], "provider_api")
        self.assertEqual(result["plan"], "SuperGrok")
        self.assertEqual(
            [row["id"] for row in result["windows"]],
            ["period", "product_grok_build", "product_grok_imagine"],
        )
        self.assertEqual(result["windows"][0]["label"], "Weekly")
        self.assertEqual(result["windows"][0]["used_percent"], 14.0)
        self.assertEqual(result["windows"][0]["reset_at"], "2026-09-13T08:34:12.348291+00:00")
        self.assertEqual(result["windows"][1]["label"], "Grok Build")
        self.assertEqual(result["windows"][1]["used_percent"], 11.0)
        self.assertEqual(result["details"], ["On-demand: $1.25 used of $5.00"])
        self.assertEqual(session.calls[0]["headers"]["Authorization"], "Bearer secret")
        self.assertEqual(session.calls[1]["headers"]["x-userid"], "user-1")
        self.assertIn("/billing?format=credits", session.calls[1]["url"])
        self.assertNotIn("secret", str(result))

    async def test_supergrok_stops_before_billing_without_account_identity(self) -> None:
        session = _SequencedSession([_FakeResponse(payload={"userId": ""})])

        result = await fetch_supergrok_usage(
            session_factory=lambda: session,
            credential_resolver=lambda: {"api_key": "secret"},
        )

        self.assertEqual(result["status"], "unavailable")
        self.assertEqual(len(session.calls), 1)
        self.assertNotIn("secret", str(result))

    async def test_supergrok_reports_top_level_on_demand_state_without_amounts(self) -> None:
        session = _SequencedSession(
            [
                _FakeResponse(payload={"userId": "user-1"}),
                _FakeResponse(
                    payload={
                        "onDemandEnabled": True,
                        "config": {
                            "creditUsagePercent": 0,
                            "currentPeriod": {"type": "USAGE_PERIOD_TYPE_WEEKLY"},
                        },
                    }
                ),
            ]
        )

        result = await fetch_supergrok_usage(
            session_factory=lambda: session,
            credential_resolver=lambda: {"api_key": "secret"},
        )

        self.assertEqual(result["status"], "available")
        self.assertEqual(result["details"], ["On-demand enabled"])

    async def test_supergrok_fresh_period_surfaces_window_without_inventing_a_percent(self) -> None:
        session = _SequencedSession(
            [
                _FakeResponse(payload={"userId": "user-1"}),
                _FakeResponse(
                    payload={
                        "config": {
                            "currentPeriod": {
                                "type": "USAGE_PERIOD_TYPE_WEEKLY",
                                "start": "2026-09-13T08:34:12.348291+00:00",
                                "end": "2026-09-20T08:34:12.348291+00:00",
                            },
                            "billingPeriodEnd": "2026-09-20T08:34:12.348291+00:00",
                        }
                    }
                ),
            ]
        )

        result = await fetch_supergrok_usage(
            session_factory=lambda: session,
            credential_resolver=lambda: {"api_key": "secret"},
        )

        self.assertEqual(result["status"], "available")
        self.assertEqual(len(result["windows"]), 1)
        self.assertEqual(result["windows"][0]["label"], "Weekly")
        self.assertIsNone(result["windows"][0]["used_percent"])
        self.assertEqual(result["windows"][0]["reset_at"], "2026-09-20T08:34:12.348291+00:00")
        self.assertEqual(result["windows"][0]["detail"], "No usage reported yet")

    async def test_supergrok_unusable_payload_is_unavailable(self) -> None:
        session = _SequencedSession(
            [
                _FakeResponse(payload={"userId": "user-1"}),
                _FakeResponse(payload={"config": {"isUnifiedBillingUser": True}}),
            ]
        )

        result = await fetch_supergrok_usage(
            session_factory=lambda: session,
            credential_resolver=lambda: {"api_key": "secret"},
        )

        self.assertEqual(result["status"], "unavailable")
        self.assertEqual(result["windows"], [])
        self.assertEqual(result["message"], "Provider returned no usage windows")

    async def test_collection_keeps_provider_order_and_schema(self) -> None:
        async def codex(_home, **_kwargs):
            return unavailable_provider("openai-codex", "Codex")

        async def nous(_home):
            return unavailable_provider("nous", "Nous")

        async def opencode(*, profile_home=None):
            return unavailable_provider("opencode-go", "OpenCode Go")

        async def supergrok(*, profile_home=None):
            return unavailable_provider("supergrok", "SuperGrok")

        result = await collect_provider_usage(
            codex_fetcher=codex,
            nous_fetcher=nous,
            opencode_fetcher=opencode,
            supergrok_fetcher=supergrok,
        )
        self.assertEqual(result["schema_version"], 2)
        self.assertEqual(
            result["capabilities"],
            ["credential_pools", "structured_balances", "opencode_go", "supergrok"],
        )
        self.assertEqual(
            [row["id"] for row in result["providers"]],
            ["openai-codex", "nous", "opencode-go", "supergrok"],
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
