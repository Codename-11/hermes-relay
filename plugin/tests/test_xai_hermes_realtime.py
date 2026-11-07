"""Hermes owns subscription auth; adapters receive only per-connection secrets."""
from __future__ import annotations

import os
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from plugin.voice_lab.providers import xai_hermes
from plugin.voice_lab.providers import xai_realtime as lab
from plugin.relay.realtime_agent.providers import xai
from plugin.relay.realtime_agent.models import RealtimeAgentSessionConfig
from plugin.voice_lab.providers.base import ProviderUnavailable
from plugin.tests.test_realtime_agent_xai_provider import FakeXAISocket


class NotSignedIn(RuntimeError):
    pass


def api(request):
    return SimpleNamespace(
        provider_request_capabilities=lambda: {"providers": ["xai-oauth"]},
        credentialed_provider_request=request,
        ProviderNotSignedIn=NotSignedIn,
    )


class HermesMintTests(unittest.TestCase):
    def test_request_returns_only_secret_and_restores_profile(self):
        from hermes_constants import get_hermes_home
        before = get_hermes_home()
        calls = []
        def request(*args, **kwargs):
            calls.append((args, kwargs, get_hermes_home()))
            return SimpleNamespace(status=200, json=lambda: {"client_secret": {"value": "short-lived"}})
        with patch.dict(sys.modules, {"hermes_cli.plugin_provider_requests": api(request)}), patch(
            "hermes_cli.profiles.get_profile_dir", return_value=before / "profiles" / "voice"
        ):
            self.assertEqual(xai_hermes.mint_client_secret("voice"), "short-lived")
        self.assertEqual(get_hermes_home(), before)
        self.assertEqual(calls[0][0], ("xai-oauth", "POST", "https://api.x.ai/v1/realtime/client_secrets"))
        self.assertEqual(calls[0][1]["json"], {"expires_after": {"seconds": 300}})
        self.assertEqual(calls[0][2], before / "profiles" / "voice")

    def test_older_core_and_missing_sign_in_remain_unavailable(self):
        with patch.dict(sys.modules, {"hermes_cli.plugin_provider_requests": SimpleNamespace()}):
            self.assertIsNone(xai_hermes.mint_client_secret())
        def missing(*args, **kwargs):
            raise NotSignedIn()
        with patch.dict(sys.modules, {"hermes_cli.plugin_provider_requests": api(missing)}):
            self.assertIsNone(xai_hermes.mint_client_secret())

    def test_errors_are_sanitized_and_never_retried(self):
        def fail(*args, **kwargs):
            raise RuntimeError("private transport details")
        with patch.dict(sys.modules, {"hermes_cli.plugin_provider_requests": api(fail)}):
            with self.assertRaisesRegex(ProviderUnavailable, "could not mint") as caught:
                xai_hermes.mint_client_secret()
            self.assertNotIn("private", str(caught.exception))

    def test_malformed_or_refused_response_is_unavailable(self):
        for status, body in [(403, {}), (200, {}), (200, []), (200, {"value": 7})]:
            with self.subTest(status=status, body=body), patch.dict(sys.modules, {
                "hermes_cli.plugin_provider_requests": api(
                    lambda *a, **k: SimpleNamespace(status=status, json=lambda: body))
            }), self.assertRaises(ProviderUnavailable):
                xai_hermes.mint_client_secret()

    def test_lab_fallback_mints_each_time_and_explicit_key_wins(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(lab, "load_voice_lab_env_file"), patch.object(
            lab, "read_xai_oauth_token", return_value=None
        ), patch.object(lab, "mint_client_secret", side_effect=["first", "second"]) as mint:
            self.assertEqual(lab._resolve_auth_token({}).value, "first")
            self.assertEqual(lab._resolve_auth_token({}).value, "second")
            self.assertEqual(lab._resolve_auth_token({"api_key": "explicit"}).value, "explicit")
            self.assertEqual(mint.call_count, 2)

    def test_secret_destination_is_official_endpoint(self):
        xai_hermes.check_secret_destination(xai.DEFAULT_URL, "hermes:xai-oauth")
        with self.assertRaises(ProviderUnavailable):
            xai_hermes.check_secret_destination("wss://example.com/realtime", "hermes:xai-oauth")


class HermesConnectTests(unittest.IsolatedAsyncioTestCase):
    async def test_native_reconnect_mints_new_secret_with_sal_and_profile(self):
        headers = []
        sockets = []
        async def factory(url, auth, timeout):
            self.assertEqual(url, xai.DEFAULT_URL + "?model=grok-voice-latest")
            headers.append(auth)
            socket = FakeXAISocket()
            sockets.append(socket)
            return socket
        config = RealtimeAgentSessionConfig(
            provider="xai_realtime", model="grok-voice-latest", voice="sal",
            sample_rate=24000, profile="voice", hermes_session_id=None,
        )
        with patch.dict(os.environ, {}, clear=True), patch.object(xai, "load_voice_lab_env_file"), patch.object(
            xai, "read_xai_oauth_token", return_value=None
        ), patch.object(xai, "mint_client_secret", side_effect=["first", "second"]) as mint:
            provider = xai.XAIRealtimeAgentProvider(socket_factory=factory)
            for _ in range(2):
                connection = await provider.connect(config)
                self.assertEqual(connection.auth_source, "hermes:xai-oauth")
                await connection.close()
            self.assertEqual([c.args for c in mint.call_args_list], [("voice",), ("voice",)])
        self.assertEqual([h["Authorization"] for h in headers], ["Bearer first", "Bearer second"])
        self.assertTrue(all(s.sent[0]["session"]["voice"] == "sal" for s in sockets))
