"""Flight-recorder hygiene: run-dir retention sweep, wav-tap gating defaults,
and resolved-model surfacing from provider session echoes."""

from __future__ import annotations

import os
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from plugin.relay.config import RelayConfig, _apply_realtime_voice_config
from plugin.relay.realtime_agent.broker import _sweep_run_dir
from plugin.relay.realtime_agent.providers import openai as openai_provider
from plugin.relay.realtime_agent.providers import xai as xai_provider
from plugin.relay.realtime_agent.models import ProviderEventKind


class RunDirSweepTest(unittest.TestCase):
    def _touch(self, run_dir: Path, name: str, *, age_days: float) -> Path:
        path = run_dir / name
        path.write_text("{}", encoding="utf-8")
        stamp = time.time() - age_days * 86400
        os.utime(path, (stamp, stamp))
        return path

    def test_sweep_removes_expired_artifacts_only(self) -> None:
        with TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            old_log = self._touch(run_dir, "realtime-agent-old.jsonl", age_days=20)
            old_wav = self._touch(run_dir, "realtime-agent-old.wav", age_days=20)
            fresh = self._touch(run_dir, "realtime-agent-new.jsonl", age_days=1)
            unrelated = self._touch(run_dir, "keep-me.txt", age_days=30)
            _sweep_run_dir(run_dir, retention_days=14)
            self.assertFalse(old_log.exists())
            self.assertFalse(old_wav.exists())
            self.assertTrue(fresh.exists())
            self.assertTrue(unrelated.exists())

    def test_zero_retention_disables_sweep(self) -> None:
        with TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            old = self._touch(run_dir, "realtime-agent-old.jsonl", age_days=400)
            _sweep_run_dir(run_dir, retention_days=0)
            self.assertTrue(old.exists())


class HygieneConfigTest(unittest.TestCase):
    def test_defaults(self) -> None:
        config = RelayConfig()
        self.assertEqual(14, config.realtime_voice_run_retention_days)
        self.assertFalse(config.realtime_voice_debug_audio_tap)

    def test_section_parsing(self) -> None:
        config = RelayConfig()
        _apply_realtime_voice_config(
            config,
            {"run_retention_days": 3, "debug_audio_tap": True},
        )
        self.assertEqual(3, config.realtime_voice_run_retention_days)
        self.assertTrue(config.realtime_voice_debug_audio_tap)

    def test_negative_retention_clamped(self) -> None:
        config = RelayConfig()
        _apply_realtime_voice_config(config, {"run_retention_days": -5})
        self.assertEqual(0, config.realtime_voice_run_retention_days)


class ResolvedModelEchoTest(unittest.TestCase):
    """session.created/updated echoes carry the model that ACTUALLY served —
    the only record of what an alias like grok-voice-latest resolved to."""

    def test_xai_ready_carries_resolved_model(self) -> None:
        event = xai_provider._provider_event(
            {
                "type": "session.created",
                "session": {"model": "grok-voice-think-fast-1.0"},
            }
        )
        self.assertIsNotNone(event)
        self.assertEqual(ProviderEventKind.READY, event.kind)
        self.assertEqual(
            "grok-voice-think-fast-1.0", event.payload.get("resolved_model")
        )

    def test_xai_ready_without_session_model_is_none(self) -> None:
        event = xai_provider._provider_event({"type": "session.updated"})
        self.assertIsNotNone(event)
        self.assertIsNone(event.payload.get("resolved_model"))

    def test_openai_ready_carries_resolved_model(self) -> None:
        event = openai_provider._provider_event(
            {
                "type": "session.created",
                "session": {"model": "gpt-realtime-2.1"},
            },
            set(),
        )
        self.assertIsNotNone(event)
        self.assertEqual(ProviderEventKind.READY, event.kind)
        self.assertEqual("gpt-realtime-2.1", event.payload.get("resolved_model"))


if __name__ == "__main__":
    unittest.main()
