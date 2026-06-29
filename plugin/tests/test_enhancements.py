"""Tests for relay enhancement registry and agent-context injection."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from plugin import config as plugin_config
from plugin.enhancements import get_enhancements
from plugin.enhancements import context_injection
from plugin.enhancements.context_injection import (
    MEDIA_SENSITIVITY_BLOCK_NAME,
    MEDIA_SENSITIVITY_INSTRUCTION,
    PHONE_PLATFORM_BLOCK_NAME,
    PHONE_PLATFORM_INSTRUCTION,
    apply_context_injection,
    get_injected_context_blocks,
    injected_context_payload,
)

_ENV_KEYS = (
    "HERMES_HOME",
    plugin_config.RELAY_AGENT_CONTEXT_ENABLED,
    plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY,
    plugin_config.RELAY_CONTEXT_PHONE_PLATFORM,
    plugin_config.PHONE_ENABLED,
)


class _IsolatedEnvMixin:
    def setUp(self) -> None:
        super().setUp()
        self._old_env = {key: os.environ.get(key) for key in _ENV_KEYS}
        self._tmpdir = tempfile.TemporaryDirectory(prefix="hermes_relay_enh_")
        os.environ["HERMES_HOME"] = self._tmpdir.name
        os.environ.pop(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, None)
        os.environ.pop(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, None)
        os.environ.pop(plugin_config.RELAY_CONTEXT_PHONE_PLATFORM, None)
        os.environ.pop(plugin_config.PHONE_ENABLED, None)

    def tearDown(self) -> None:
        for key in _ENV_KEYS:
            os.environ.pop(key, None)
            old = self._old_env.get(key)
            if old is not None:
                os.environ[key] = old
        self._tmpdir.cleanup()
        super().tearDown()

    def _set_env(self, key: str, value: str) -> None:
        os.environ[key] = value

    def _write_dotenv(self, **values: str) -> None:
        env_path = Path(os.environ["HERMES_HOME"]) / ".env"
        env_path.write_text(
            "".join(f"{key}={value}\n" for key, value in values.items()),
            encoding="utf-8",
        )


class PluginConfigTests(_IsolatedEnvMixin, unittest.TestCase):
    def test_context_flags_default_on(self) -> None:
        # Default ON: the relay plugin install is itself the opt-in, so an unset
        # env enables the layer. Explicit "0"/"false" still opts out (covered by
        # test_context_flags_accept_strict_bool_tokens).
        self.assertTrue(plugin_config.agent_context_enabled())
        self.assertTrue(plugin_config.context_media_sensitivity_enabled())

    def test_context_flags_accept_strict_bool_tokens(self) -> None:
        for value in ("1", "true", "yes", "on"):
            with self.subTest(value=value):
                self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, value)
                self.assertTrue(plugin_config.agent_context_enabled())

        for value in ("0", "false", "no", "off", ""):
            with self.subTest(value=value):
                self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, value)
                self.assertFalse(plugin_config.agent_context_enabled())

    def test_invalid_context_flag_falls_back_to_default(self) -> None:
        # An unrecognized value falls back to the default (now ON). A clean "0"
        # / "false" is the supported opt-out, not a typo.
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "definitely")
        self.assertTrue(plugin_config.agent_context_enabled())

    def test_dotenv_value_wins_for_dashboard_saved_toggles(self) -> None:
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "0")
        self._write_dotenv(**{plugin_config.RELAY_AGENT_CONTEXT_ENABLED: "1"})
        self.assertTrue(plugin_config.agent_context_enabled())


class EnhancementRegistryTests(_IsolatedEnvMixin, unittest.TestCase):
    def test_registry_exposes_plugin_load_context_injection(self) -> None:
        enhancements = get_enhancements("plugin_load")
        by_name = {enhancement.name: enhancement for enhancement in enhancements}

        self.assertIn("context_injection", by_name)
        self.assertEqual(by_name["context_injection"].phase, "plugin_load")
        self.assertIn("system-prompt context hook", by_name["context_injection"].retirement_note)
        # Default ON when unset; explicit "0" opts out.
        self.assertTrue(by_name["context_injection"].enabled())

        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "0")
        self.assertFalse(by_name["context_injection"].enabled())


class ContextInjectionTests(_IsolatedEnvMixin, unittest.TestCase):
    def _agent_class(self) -> type:
        class DummyAgent:
            def _build_system_prompt(self, system_message: str | None = None) -> str:
                if system_message:
                    return f"base prompt\n\n{system_message}"
                return "base prompt"

        return DummyAgent

    def test_disabled_context_returns_prompt_unchanged(self) -> None:
        agent_class = self._agent_class()
        self.assertTrue(apply_context_injection(agent_class))
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "0")

        self.assertEqual(agent_class()._build_system_prompt(), "base prompt")

    def test_master_enabled_without_block_returns_prompt_unchanged(self) -> None:
        agent_class = self._agent_class()
        self.assertTrue(apply_context_injection(agent_class))
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "0")

        self.assertEqual(agent_class()._build_system_prompt(), "base prompt")

    def test_enabled_media_block_appends_labeled_fence(self) -> None:
        agent_class = self._agent_class()
        self.assertTrue(apply_context_injection(agent_class))
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "1")

        prompt = agent_class()._build_system_prompt()

        self.assertTrue(prompt.startswith("base prompt\n\n"))
        self.assertIn(
            "<!-- hermes-relay:media-sensitivity -->\n"
            f"{MEDIA_SENSITIVITY_INSTRUCTION}\n"
            "<!-- /hermes-relay:media-sensitivity -->",
            prompt,
        )
        self.assertEqual(prompt.count("<!-- hermes-relay:media-sensitivity -->"), 1)

    def test_apply_is_idempotent(self) -> None:
        agent_class = self._agent_class()
        self.assertTrue(apply_context_injection(agent_class))
        self.assertTrue(apply_context_injection(agent_class))
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "1")

        prompt = agent_class()._build_system_prompt()

        self.assertEqual(prompt.count("<!-- hermes-relay:media-sensitivity -->"), 1)

    def test_missing_seam_is_fail_open(self) -> None:
        class NoSeam:
            pass

        self.assertFalse(apply_context_injection(NoSeam))

    def test_block_builder_failure_returns_base_prompt(self) -> None:
        agent_class = self._agent_class()
        self.assertTrue(apply_context_injection(agent_class))
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "1")

        original = context_injection.get_injected_context_blocks

        def _broken_blocks() -> list[dict[str, str]]:
            raise RuntimeError("boom")

        context_injection.get_injected_context_blocks = _broken_blocks
        try:
            self.assertEqual(agent_class()._build_system_prompt(), "base prompt")
        finally:
            context_injection.get_injected_context_blocks = original

    def test_audit_payload_uses_inner_block_text_only(self) -> None:
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "1")

        payload = injected_context_payload()

        self.assertTrue(payload["enabled"])
        self.assertEqual(
            payload["blocks"],
            [
                {
                    "name": MEDIA_SENSITIVITY_BLOCK_NAME,
                    "text": MEDIA_SENSITIVITY_INSTRUCTION,
                }
            ],
        )


class ContextInjectedRouteTests(_IsolatedEnvMixin, unittest.IsolatedAsyncioTestCase):
    async def test_loopback_returns_contract_shape(self) -> None:
        from plugin.relay.config import RelayConfig
        from plugin.relay.server import create_app

        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "1")

        app = create_app(RelayConfig())
        async with TestClient(TestServer(app)) as client:
            resp = await client.get("/context/injected")
            self.assertEqual(resp.status, 200)
            body = await resp.json()

        self.assertTrue(body["enabled"])
        self.assertEqual(len(body["blocks"]), 1)
        self.assertEqual(body["blocks"][0]["name"], MEDIA_SENSITIVITY_BLOCK_NAME)
        self.assertEqual(body["blocks"][0]["text"], MEDIA_SENSITIVITY_INSTRUCTION)

    async def test_disabled_loopback_returns_empty_blocks(self) -> None:
        from plugin.relay.config import RelayConfig
        from plugin.relay.server import create_app

        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "0")
        app = create_app(RelayConfig())
        async with TestClient(TestServer(app)) as client:
            resp = await client.get("/context/injected")
            self.assertEqual(resp.status, 200)
            body = await resp.json()

        self.assertFalse(body["enabled"])
        self.assertEqual(body["blocks"], [])

    async def test_remote_without_bearer_requires_auth(self) -> None:
        from plugin.relay.config import RelayConfig
        from plugin.relay.server import create_app, handle_context_injected

        app = create_app(RelayConfig())

        class _Req:
            remote = "192.168.1.40"
            headers: dict[str, str] = {}

            def __init__(self, relay_app: web.Application) -> None:
                self.app = relay_app

        with self.assertRaises(web.HTTPUnauthorized):
            await handle_context_injected(_Req(app))  # type: ignore[arg-type]

    async def test_remote_with_bearer_returns_shape(self) -> None:
        from plugin.relay.config import RelayConfig
        from plugin.relay.server import create_app, handle_context_injected

        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "0")
        app = create_app(RelayConfig())
        session = app["server"].sessions.create_session("phone", "phone-id")

        class _Req:
            remote = "192.168.1.40"

            def __init__(self, relay_app: web.Application) -> None:
                self.app = relay_app
                self.headers = {"Authorization": f"Bearer {session.token}"}

        resp = await handle_context_injected(_Req(app))  # type: ignore[arg-type]
        self.assertEqual(resp.status, 200)
        body = json.loads(resp.text)
        self.assertTrue(body["enabled"])
        self.assertEqual(body["blocks"], [])


class PhonePlatformContextBlockTests(_IsolatedEnvMixin, unittest.TestCase):
    def _block_names(self) -> list[str]:
        return [b["name"] for b in get_injected_context_blocks()]

    def test_phone_helpers_defaults(self) -> None:
        # Platform off by default; the per-block hint defaults on (only matters
        # once the platform is enabled).
        self.assertFalse(plugin_config.phone_platform_enabled())
        self.assertTrue(plugin_config.context_phone_platform_enabled())

    def test_phone_block_absent_when_platform_disabled(self) -> None:
        # Context layer is on by default, but PHONE_ENABLED is unset → no hint.
        self.assertNotIn(PHONE_PLATFORM_BLOCK_NAME, self._block_names())

    def test_phone_block_present_when_platform_enabled(self) -> None:
        self._set_env(plugin_config.PHONE_ENABLED, "1")
        self.assertIn(PHONE_PLATFORM_BLOCK_NAME, self._block_names())

    def test_phone_block_suppressed_by_per_block_flag(self) -> None:
        self._set_env(plugin_config.PHONE_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_PHONE_PLATFORM, "0")
        self.assertNotIn(PHONE_PLATFORM_BLOCK_NAME, self._block_names())

    def test_phone_block_absent_when_context_layer_off(self) -> None:
        self._set_env(plugin_config.PHONE_ENABLED, "1")
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "0")
        self.assertEqual(get_injected_context_blocks(), [])

    def test_phone_block_appends_labeled_fence(self) -> None:
        agent_class = self._agent_class()
        self.assertTrue(apply_context_injection(agent_class))
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "0")
        self._set_env(plugin_config.PHONE_ENABLED, "1")

        prompt = agent_class()._build_system_prompt()

        self.assertIn(
            "<!-- hermes-relay:phone-platform -->\n"
            f"{PHONE_PLATFORM_INSTRUCTION}\n"
            "<!-- /hermes-relay:phone-platform -->",
            prompt,
        )
        self.assertEqual(prompt.count("<!-- hermes-relay:phone-platform -->"), 1)

    def test_audit_payload_includes_phone_block(self) -> None:
        self._set_env(plugin_config.RELAY_AGENT_CONTEXT_ENABLED, "1")
        self._set_env(plugin_config.RELAY_CONTEXT_MEDIA_SENSITIVITY, "0")
        self._set_env(plugin_config.PHONE_ENABLED, "1")

        payload = injected_context_payload()

        self.assertTrue(payload["enabled"])
        self.assertEqual(
            payload["blocks"],
            [{"name": PHONE_PLATFORM_BLOCK_NAME, "text": PHONE_PLATFORM_INSTRUCTION}],
        )

    def _agent_class(self) -> type:
        class DummyAgent:
            def _build_system_prompt(self, system_message: str | None = None) -> str:
                return "base prompt"

        return DummyAgent


if __name__ == "__main__":
    unittest.main()
