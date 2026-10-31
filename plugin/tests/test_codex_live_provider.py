"""Offline contracts for the opt-in Codex subscription-backed GPT Live adapter."""
from __future__ import annotations

import asyncio
import base64
import json
import os
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from plugin.relay.realtime_agent.models import (
    ProviderEventKind, RealtimeAgentSessionConfig,
)
from plugin.relay.realtime_agent.providers.codex_live import (
    CodexLiveConnection, CodexLiveProvider,
)
from plugin.voice_lab.providers.base import ProviderUnavailable
from plugin.voice_lab.registry import default_registry


def sample_config(model: str = "gpt-live-1.1", sample_rate: int = 24000):
    return RealtimeAgentSessionConfig(
        provider="codex_live", model=model, voice="sol", sample_rate=sample_rate,
        profile=None, hermes_session_id=None,
    )


class CodexLiveProviderTests(unittest.IsolatedAsyncioTestCase):
    def test_voice_catalog_advertises_subscription_provider(self):
        info = default_registry().info("codex_live")
        self.assertEqual(info.models, ("gpt-live-1.1",))
        self.assertIn("sol", info.voices)
        self.assertTrue(info.supports_realtime)
        self.assertTrue(info.supports_tool_use)

    async def test_unsupported_model_or_audio_rate_fail_closed(self):
        provider = CodexLiveProvider()
        for model, rate in (("gpt-realtime-2", 24000), ("gpt-live-1.1", 16000)):
            with self.subTest(model=model, rate=rate):
                with self.assertRaises(ProviderUnavailable):
                    await provider.connect(sample_config(model, rate))

    async def test_worker_launch_does_not_inherit_hermes_secrets(self):
        fake_proc = SimpleNamespace(returncode=1, stdout=asyncio.StreamReader(),
                                    stdin=SimpleNamespace(), wait=AsyncMock())
        with patch.dict(os.environ, {"HERMES_API_KEY": "internal-secret-value",
                                     "OPENAI_API_KEY": "paid-api-value",
                                     "CODEX_API_KEY": "paid-codex-value"}), \
             patch("plugin.relay.realtime_agent.providers.codex_live._WORKER_PYTHON", Path(__file__)), \
             patch("plugin.relay.realtime_agent.providers.codex_live._WORKER_FILE", Path(__file__)), \
             patch("asyncio.create_subprocess_exec", new_callable=AsyncMock, return_value=fake_proc) as spawn, \
             patch("plugin.relay.realtime_agent.providers.codex_live.CodexLiveConnection.wait_ready", new_callable=AsyncMock), \
             patch("plugin.relay.realtime_agent.providers.codex_live.CodexLiveConnection.close", new_callable=AsyncMock):
            await CodexLiveProvider().connect(sample_config())
            env = spawn.call_args.kwargs["env"]
            self.assertEqual(env["CODEX_LIVE_MODEL"], "gpt-live-1.1")
            self.assertEqual(env["CODEX_LIVE_VOICE"], "sol")
            self.assertNotIn("HERMES_API_KEY", env)
            self.assertNotIn("OPENAI_API_KEY", env)
            self.assertNotIn("CODEX_API_KEY", env)

    async def test_audio_and_delegation_event_mapping_preserves_broker_contract(self):
        reader = asyncio.StreamReader()
        for item in (
            {"kind": "audio_delta", "audio_base64": base64.b64encode(b"\1\2").decode()},
            {"kind": "function_call_completed", "call_id": "delegate-1", "name": "hermes_run_task",
             "arguments": {"text": "Check my Hermes agent"}},
            {"kind": "response_done", "response_id": "response-1"},
        ):
            reader.feed_data((json.dumps(item) + "\n").encode())
        reader.feed_eof()
        proc = SimpleNamespace(stdout=reader, returncode=1,
                               stdin=SimpleNamespace(), wait=AsyncMock())
        conn = CodexLiveConnection(proc)
        events = await asyncio.wait_for(_collect(conn), 3)
        self.assertEqual([e.kind for e in events], [
            ProviderEventKind.AUDIO_DELTA,
            ProviderEventKind.FUNCTION_CALL_COMPLETED,
            ProviderEventKind.RESPONSE_DONE,
        ])
        self.assertEqual(events[0].payload["audio"], b"\1\2")
        call = events[1].payload["call"]
        self.assertEqual((call.call_id, call.name, call.arguments["text"]),
                         ("delegate-1", "hermes_run_task", "Check my Hermes agent"))
        self.assertTrue(call.is_hermes_tool())
        self.assertEqual(events[2].response_id, "response-1")
        await conn.close()


async def _collect(conn):
    return [e async for e in conn.events()]
