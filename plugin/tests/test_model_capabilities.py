"""Focused contract and isolation tests for model capability resolution."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
import tempfile
import unittest

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase, TestClient, TestServer

from plugin.relay.config import RelayConfig
from plugin.relay.model_capabilities import (
    MAX_MODEL_PAIRS,
    ModelCapabilityResolver,
    ReasoningCapability,
)
from plugin.relay.server import create_app, handle_model_capabilities


class ModelCapabilitiesRouteTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._temp = tempfile.TemporaryDirectory()
        home = Path(self._temp.name)
        (home / "config.yaml").write_text("model: {}\n", encoding="utf-8")
        return create_app(RelayConfig(hermes_config_path=str(home / "config.yaml")))

    async def asyncTearDown(self) -> None:
        await super().asyncTearDown()
        self._temp.cleanup()

    async def test_static_contract_and_info_advertisement(self) -> None:
        info = await self.client.get("/relay/info")
        self.assertEqual(info.status, 200)
        info_body = await info.json()
        self.assertIn("model_reasoning_capabilities_v1", info_body["capabilities"])

        response = await self.client.post(
            "/relay/model-capabilities",
            json={
                "schema_version": 1,
                "models": [
                    {"provider": "zai", "model": "glm-5.2"},
                    {"provider": "unknown", "model": "future-model"},
                ],
            },
        )
        self.assertEqual(response.status, 200)
        body = await response.json()
        self.assertEqual(body["schema_version"], 1)
        self.assertEqual(body["contract_version"], "1.0")
        self.assertEqual(
            body["capabilities"][0],
            {
                "provider": "zai",
                "model": "glm-5.2",
                "reasoning": True,
                "reasoning_efforts": ["none", "high", "max"],
                "reasoning_efforts_exact": True,
                "source": "provider-adapter",
            },
        )
        self.assertFalse(body["capabilities"][1]["reasoning_efforts_exact"])
        self.assertNotIn("scope", json.dumps(body).lower())
        self.assertNotIn("token", json.dumps(body).lower())

    async def test_validation_caps_pairs_and_rejects_unknown_profile(self) -> None:
        too_many = [
            {"provider": "zai", "model": f"model-{index}"}
            for index in range(MAX_MODEL_PAIRS + 1)
        ]
        response = await self.client.post(
            "/relay/model-capabilities",
            json={"schema_version": 1, "models": too_many},
        )
        self.assertEqual(response.status, 400)
        self.assertEqual((await response.json())["max_models"], MAX_MODEL_PAIRS)

        missing = await self.client.post(
            "/relay/model-capabilities",
            json={
                "schema_version": 1,
                "profile": "missing",
                "models": [{"provider": "zai", "model": "glm-5.2"}],
            },
        )
        self.assertEqual(missing.status, 404)
        self.assertEqual((await missing.json())["error"], "profile_not_found")


class ModelCapabilitiesAuthTests(unittest.IsolatedAsyncioTestCase):
    async def test_remote_route_requires_and_accepts_paired_bearer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / "config.yaml").write_text("model: {}\n", encoding="utf-8")
            app = create_app(RelayConfig(hermes_config_path=str(home / "config.yaml")))

            class _Req:
                remote = "10.2.3.4"

                def __init__(self, headers: dict[str, str]) -> None:
                    self.app = app
                    self.headers = headers

                async def json(self):
                    return {
                        "schema_version": 1,
                        "models": [{"provider": "zai", "model": "glm-5.2"}],
                    }

            with self.assertRaises(web.HTTPUnauthorized):
                await handle_model_capabilities(_Req({}))  # type: ignore[arg-type]

            session = app["server"].sessions.create_session("phone", "phone-id")
            response = await handle_model_capabilities(  # type: ignore[arg-type]
                _Req({"Authorization": f"Bearer {session.token}"})
            )
            self.assertEqual(response.status, 200)

            session.grants["chat"] = 1.0
            with self.assertRaises(web.HTTPForbidden):
                await handle_model_capabilities(  # type: ignore[arg-type]
                    _Req({"Authorization": f"Bearer {session.token}"})
                )


class DynamicCapabilityResolverTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.home = Path(self._temp.name)
        (self.home / "config.yaml").write_text("model: {}\n", encoding="utf-8")
        self.profiles = self.home / "profiles"
        self.profiles.mkdir()

    async def asyncTearDown(self) -> None:
        self._temp.cleanup()

    async def _start_provider(self, app: web.Application) -> tuple[TestClient, str]:
        server = TestServer(app)
        client = TestClient(server)
        await client.start_server()
        return client, str(client.make_url("/")).rstrip("/")

    def _profile(self, name: str, env: str) -> None:
        home = self.profiles / name
        home.mkdir()
        (home / "config.yaml").write_text("model: {}\n", encoding="utf-8")
        (home / ".env").write_text(env, encoding="utf-8")

    async def test_lmstudio_and_ollama_live_metadata_are_exact(self) -> None:
        async def lm_models(_request: web.Request) -> web.Response:
            return web.json_response(
                {
                    "models": [
                        {
                            "id": "local-model",
                            "capabilities": {
                                "reasoning": {"allowed_options": ["off", "low", "high"]}
                            },
                        }
                    ]
                }
            )

        active = 0
        peak = 0
        calls = 0

        async def ollama_show(request: web.Request) -> web.Response:
            nonlocal active, peak, calls
            calls += 1
            active += 1
            peak = max(peak, active)
            try:
                await __import__("asyncio").sleep(0.01)
                body = await request.json()
                capabilities = (
                    ["completion", "thinking"]
                    if body["name"].endswith("0")
                    else ["completion"]
                )
                return web.json_response({"capabilities": capabilities})
            finally:
                active -= 1

        provider_app = web.Application()
        provider_app.router.add_get("/api/v1/models", lm_models)
        provider_app.router.add_post("/api/show", ollama_show)
        client, base = await self._start_provider(provider_app)
        self.addAsyncCleanup(client.close)
        self._profile("dynamic", f"LM_BASE_URL={base}/v1\nOLLAMA_BASE_URL={base}/v1\n")

        resolver = ModelCapabilityResolver(
            RelayConfig(hermes_config_path=str(self.home / "config.yaml"))
        )
        pairs = [("lmstudio", "local-model")] + [
            ("ollama-cloud", f"ollama-{index}") for index in range(20)
        ]
        rows = await resolver.resolve_many(pairs, profile="dynamic")
        self.assertEqual(rows[0]["reasoning_efforts"], ["none", "low", "high"])
        self.assertTrue(rows[0]["reasoning_efforts_exact"])
        self.assertEqual(
            rows[1]["reasoning_efforts"], ["none", "low", "medium", "high", "max"]
        )
        self.assertEqual(rows[2]["reasoning_efforts"], [])
        self.assertTrue(rows[2]["reasoning_efforts_exact"])
        self.assertEqual(calls, 16)
        self.assertLessEqual(peak, 4)
        self.assertFalse(rows[-1]["reasoning_efforts_exact"])

    async def test_codex_catalog_reasoning_levels_are_exact(self) -> None:
        async def models(request: web.Request) -> web.Response:
            self.assertEqual(request.headers.get("Authorization"), "Bearer codex-token")
            return web.json_response(
                {
                    "models": [
                        {
                            "slug": "gpt-5.6-sol",
                            "supported_reasoning_levels": [
                                {"effort": "low"},
                                {"effort": "medium"},
                                {"effort": "high"},
                                {"effort": "xhigh"},
                                {"effort": "max"},
                                {"effort": "ultra"},
                            ],
                        }
                    ]
                }
            )

        provider_app = web.Application()
        provider_app.router.add_get("/models", models)
        client, base = await self._start_provider(provider_app)
        self.addAsyncCleanup(client.close)

        home = self.profiles / "codex"
        home.mkdir()
        (home / "config.yaml").write_text("model: {}\n", encoding="utf-8")
        (home / ".env").write_text(
            f"OPENAI_CODEX_BASE_URL={base}\n", encoding="utf-8"
        )
        (home / "auth.json").write_text(
            json.dumps(
                {
                    "credential_pool": {
                        "openai-codex": [{"access_token": "codex-token"}]
                    }
                }
            ),
            encoding="utf-8",
        )
        resolver = ModelCapabilityResolver(
            RelayConfig(hermes_config_path=str(self.home / "config.yaml"))
        )

        rows = await resolver.resolve_many(
            [
                ("openai-codex", "gpt-5.6-sol"),
                ("openai-codex", "future-model"),
            ],
            profile="codex",
        )

        self.assertEqual(
            rows[0]["reasoning_efforts"],
            ["low", "medium", "high", "xhigh", "max", "ultra"],
        )
        self.assertTrue(rows[0]["reasoning_efforts_exact"])
        self.assertEqual(rows[0]["source"], "provider-catalog")
        self.assertFalse(rows[1]["reasoning_efforts_exact"])

    async def test_copilot_catalog_cache_is_profile_and_account_isolated(self) -> None:
        calls = {"account-a=1;kind=api": 0, "account-b=1;kind=api": 0}

        async def models(request: web.Request) -> web.Response:
            token = request.headers["Authorization"].removeprefix("Bearer ")
            calls[token] += 1
            efforts = ["low"] if "account-a" in token else ["xhigh"]
            return web.json_response(
                {
                    "data": [
                        {
                            "id": "gpt-5.5",
                            "capabilities": {"supports": {"reasoning_effort": efforts}},
                        }
                    ]
                }
            )

        provider_app = web.Application()
        provider_app.router.add_get("/models", models)
        client, base = await self._start_provider(provider_app)
        self.addAsyncCleanup(client.close)
        self._profile(
            "account-a",
            f"COPILOT_GITHUB_TOKEN=account-a=1;kind=api\nCOPILOT_BASE_URL={base}\n",
        )
        self._profile(
            "account-b",
            f"COPILOT_GITHUB_TOKEN=account-b=1;kind=api\nCOPILOT_BASE_URL={base}\n",
        )
        resolver = ModelCapabilityResolver(
            RelayConfig(hermes_config_path=str(self.home / "config.yaml"))
        )
        pairs = [("copilot", "gpt-5.5")]
        a_first = await resolver.resolve_many(pairs, profile="account-a")
        b_first = await resolver.resolve_many(pairs, profile="account-b")
        a_cached = await resolver.resolve_many(pairs, profile="account-a")

        self.assertEqual(a_first[0]["reasoning_efforts"], ["low"])
        self.assertEqual(b_first[0]["reasoning_efforts"], ["xhigh"])
        self.assertEqual(a_cached, a_first)
        self.assertEqual(
            calls,
            {
                "account-a=1;kind=api": 1,
                "account-b=1;kind=api": 1,
            },
        )
        serialized = json.dumps(a_first + b_first)
        self.assertNotIn("account-a", serialized)
        self.assertNotIn("account-b", serialized)

        await resolver.resolve_many(pairs, profile="account-a", refresh=True)
        self.assertEqual(calls["account-a=1;kind=api"], 2)
        await resolver.resolve_many(pairs, profile="account-b")
        self.assertEqual(calls["account-b=1;kind=api"], 1)

    async def test_unavailable_copilot_pool_entry_fails_to_non_exact(self) -> None:
        home = self.profiles / "unavailable"
        home.mkdir()
        (home / "config.yaml").write_text("model: {}\n", encoding="utf-8")
        (home / "auth.json").write_text(
            json.dumps(
                {
                    "credential_pool": {
                        "copilot": [
                            {
                                "access_token": "dead-account=1;kind=api",
                                "last_status": "dead",
                            }
                        ]
                    }
                }
            ),
            encoding="utf-8",
        )
        resolver = ModelCapabilityResolver(
            RelayConfig(hermes_config_path=str(self.home / "config.yaml"))
        )

        rows = await resolver.resolve_many(
            [("copilot", "gpt-5.5")], profile="unavailable"
        )

        self.assertFalse(rows[0]["reasoning_efforts_exact"])
        self.assertEqual(rows[0]["source"], "canonical-fallback")

    async def test_copilot_catalog_missing_model_keeps_non_exact_fallback(self) -> None:
        async def models(_request: web.Request) -> web.Response:
            return web.json_response(
                {
                    "data": [
                        {
                            "id": "different-model",
                            "capabilities": {"supports": {"reasoning_effort": []}},
                        }
                    ]
                }
            )

        provider_app = web.Application()
        provider_app.router.add_get("/models", models)
        client, base = await self._start_provider(provider_app)
        self.addAsyncCleanup(client.close)
        self._profile(
            "missing-row",
            f"COPILOT_GITHUB_TOKEN=missing=1;kind=api\nCOPILOT_BASE_URL={base}\n",
        )
        resolver = ModelCapabilityResolver(
            RelayConfig(hermes_config_path=str(self.home / "config.yaml"))
        )

        rows = await resolver.resolve_many(
            [
                ("copilot", "requested-model"),
                ("copilot", "different-model"),
            ],
            profile="missing-row",
        )

        self.assertTrue(rows[0]["reasoning"])
        self.assertFalse(rows[0]["reasoning_efforts_exact"])
        self.assertEqual(rows[0]["source"], "canonical-fallback")
        self.assertFalse(rows[1]["reasoning"])
        self.assertTrue(rows[1]["reasoning_efforts_exact"])
        self.assertEqual(rows[1]["source"], "github-catalog")

    async def test_outbound_probe_limit_is_global_across_concurrent_refreshes(
        self,
    ) -> None:
        active = 0
        peak = 0

        async def _enter() -> None:
            nonlocal active, peak
            active += 1
            peak = max(peak, active)
            await asyncio.sleep(0.03)

        def _leave() -> None:
            nonlocal active
            active -= 1

        async def lm_models(_request: web.Request) -> web.Response:
            await _enter()
            try:
                return web.json_response(
                    {
                        "models": [
                            {
                                "id": "local-model",
                                "capabilities": {
                                    "reasoning": {"allowed_options": ["low"]}
                                },
                            }
                        ]
                    }
                )
            finally:
                _leave()

        async def ollama_show(_request: web.Request) -> web.Response:
            await _enter()
            try:
                return web.json_response({"capabilities": ["completion", "thinking"]})
            finally:
                _leave()

        async def copilot_models(_request: web.Request) -> web.Response:
            await _enter()
            try:
                return web.json_response(
                    {
                        "data": [
                            {
                                "id": "gpt-5.5",
                                "capabilities": {
                                    "supports": {"reasoning_effort": ["high"]}
                                },
                            }
                        ]
                    }
                )
            finally:
                _leave()

        provider_app = web.Application()
        provider_app.router.add_get("/api/v1/models", lm_models)
        provider_app.router.add_post("/api/show", ollama_show)
        provider_app.router.add_get("/models", copilot_models)
        client, base = await self._start_provider(provider_app)
        self.addAsyncCleanup(client.close)
        self._profile(
            "burst",
            (
                f"LM_BASE_URL={base}/v1\n"
                f"OLLAMA_BASE_URL={base}/v1\n"
                f"COPILOT_BASE_URL={base}\n"
                "COPILOT_GITHUB_TOKEN=burst=1;kind=api\n"
            ),
        )
        resolver = ModelCapabilityResolver(
            RelayConfig(hermes_config_path=str(self.home / "config.yaml"))
        )
        pairs = [
            ("lmstudio", "local-model"),
            ("copilot", "gpt-5.5"),
            *(("ollama-cloud", f"ollama-{index}") for index in range(4)),
        ]

        await asyncio.gather(
            *(
                resolver.resolve_many(pairs, profile="burst", refresh=True)
                for _ in range(6)
            )
        )

        self.assertGreater(peak, 1)
        self.assertLessEqual(peak, 4)

    async def test_pre_refresh_generation_cannot_repopulate_cache(self) -> None:
        resolver = ModelCapabilityResolver(
            RelayConfig(hermes_config_path=str(self.home / "config.yaml"))
        )
        profile = "generation-profile"
        key = (profile, "lmstudio", "model", "http://endpoint", "account")
        capability = ReasoningCapability(("high",), True, "provider-catalog")
        old_generation = await resolver._generation(profile)
        new_generation = await resolver._clear(profile)

        stale_stored = await resolver._store(key, capability, old_generation)

        self.assertFalse(stale_stored)
        self.assertIsNone(await resolver._cached(key))
        self.assertTrue(await resolver._store(key, capability, new_generation))
        self.assertEqual(await resolver._cached(key), capability)


if __name__ == "__main__":
    unittest.main()
