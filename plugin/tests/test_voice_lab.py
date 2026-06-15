from __future__ import annotations

import base64
import asyncio
import importlib.util
import io
import json
import os
import tempfile
import time
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

from plugin.voice_lab.auth import XAIAuthResult
from plugin.voice_lab.cli import main
from plugin.voice_lab.expressions import VoiceExpression
from plugin.voice_lab.metrics import MetricsRecorder
from plugin.voice_lab.providers.base import ProviderUnavailable, VoiceRequest
from plugin.voice_lab.providers.elevenlabs_tts import ElevenLabsTTSProvider
from plugin.voice_lab.providers.openai_tts import OpenAITTSProvider
from plugin.voice_lab.providers.openai_realtime import OpenAIRealtimeProvider
from plugin.voice_lab.providers.stub import StubToneProvider
from plugin.voice_lab.providers.xai_tts import XAITTSProvider
from plugin.voice_lab.providers.xai_realtime import XAIRealtimeProvider
from plugin.voice_lab.registry import default_registry
from plugin.voice_lab.terminal_ui import render_waveform, run_with_waveform


class _FakeSocket:
    def __init__(self, events: list[dict]) -> None:
        self._messages = [json.dumps(event) for event in events]
        self.sent: list[dict] = []
        self.closed = False

    def send(self, payload: str) -> None:
        self.sent.append(json.loads(payload))

    def recv(self) -> str:
        if not self._messages:
            raise AssertionError("fake socket has no more messages")
        return self._messages.pop(0)

    def close(self) -> None:
        self.closed = True


class _FakeHttpResponse:
    def __init__(self, chunks: list[bytes], headers: dict[str, str] | None = None) -> None:
        self._chunks = list(chunks)
        self.headers = headers or {}

    def read(self, size: int = -1) -> bytes:
        if not self._chunks:
            return b""
        return self._chunks.pop(0)

    def __enter__(self) -> "_FakeHttpResponse":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None


class VoiceLabTests(unittest.TestCase):
    def test_default_registry_has_stub_and_openai_provider(self) -> None:
        registry = default_registry()
        self.assertEqual(
            registry.provider_ids(),
            [
                "elevenlabs_tts",
                "openai_realtime",
                "openai_tts",
                "stub",
                "xai_realtime",
                "xai_tts",
            ],
        )
        self.assertEqual(registry.info("elevenlabs_tts").name, "ElevenLabs TTS")
        self.assertEqual(registry.info("openai_realtime").name, "OpenAI Realtime")
        self.assertEqual(registry.info("openai_tts").name, "OpenAI TTS")
        self.assertEqual(registry.info("stub").name, "Stub Tone")
        self.assertEqual(registry.info("xai_realtime").name, "xAI Grok Realtime")
        self.assertEqual(registry.info("xai_tts").name, "xAI Grok TTS")

    def test_provider_info_exposes_known_voice_options(self) -> None:
        registry = default_registry()

        xai_tts = registry.info("xai_tts").to_dict()
        self.assertIn("xai-tts", xai_tts["models"])
        self.assertIn("eve", xai_tts["voices"])
        self.assertIn("leo", xai_tts["voices"])
        self.assertIn(24000, xai_tts["sample_rates"])

        elevenlabs = registry.info("elevenlabs_tts").to_dict()
        self.assertIn("eleven_flash_v2_5", elevenlabs["models"])
        self.assertIn("eleven_multilingual_v2", elevenlabs["models"])
        self.assertIn(24000, elevenlabs["sample_rates"])

    def test_expression_validates_intensity(self) -> None:
        with self.assertRaises(ValueError):
            VoiceExpression(intensity=1.1)

    def test_stub_provider_writes_wav_and_metrics(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out.wav"
            recorder = MetricsRecorder()
            response = StubToneProvider().run_text(
                VoiceRequest(
                    text="Testing one two three.",
                    expression=VoiceExpression(emotion="warm"),
                    output_path=output,
                ),
                recorder,
            )

            self.assertEqual(response.provider, "stub")
            self.assertTrue(output.is_file())
            self.assertIsNotNone(response.metrics.first_audio_ms)
            self.assertIsNotNone(response.metrics.response_done_ms)
            self.assertGreater(len(recorder.events_as_dicts()), 1)
            audio_events = [
                item for item in recorder.events_as_dicts() if item["type"] == "audio_chunk"
            ]
            self.assertTrue(any("rms_level" in item["data"] for item in audio_events))
            with wave.open(str(output), "rb") as wav:
                self.assertEqual(wav.getnchannels(), 1)
                self.assertEqual(wav.getframerate(), 24000)
                self.assertGreater(wav.getnframes(), 0)

    def test_cli_realtime_text_json_and_event_log(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / "sample.wav"
            events = root / "events.jsonl"
            with patch("sys.stdout") as stdout:
                rc = main(
                    [
                        "realtime-text",
                        "--provider",
                        "stub",
                        "--text",
                        "Everything is under control.",
                        "--emotion",
                        "calm",
                        "--output",
                        str(output),
                        "--event-log",
                        str(events),
                        "--json",
                    ]
                )

            self.assertEqual(rc, 0)
            self.assertTrue(output.is_file())
            self.assertTrue(events.is_file())
            rendered = "".join(call.args[0] for call in stdout.write.call_args_list)
            data = json.loads(rendered)
            self.assertEqual(data["provider"], "stub")
            self.assertEqual(data["audio_path"], str(output))

    def test_cli_bench_writes_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            script = root / "phrases.txt"
            script.write_text("one\n# ignored\n\ntwo\n", encoding="utf-8")
            out_dir = root / "runs"

            with patch("sys.stdout"):
                rc = main(
                    [
                        "bench",
                        "--providers",
                        "stub",
                        "--script",
                        str(script),
                        "--output-dir",
                        str(out_dir),
                        "--json",
                    ]
                )

            self.assertEqual(rc, 0)
            self.assertTrue((out_dir / "001-stub.wav").is_file())
            self.assertTrue((out_dir / "002-stub.wav").is_file())

    def test_cli_eval_approach_writes_summary_and_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp) / "e2e"
            with patch("sys.stdout") as stdout:
                rc = main(
                    [
                        "eval-approach",
                        "--providers",
                        "stub",
                        "--output-dir",
                        str(out_dir),
                        "--visual",
                        "off",
                        "--json",
                    ]
                )

            self.assertEqual(rc, 0)
            self.assertTrue((out_dir / "summary.json").is_file())
            self.assertTrue((out_dir / "runs.jsonl").is_file())
            self.assertTrue((out_dir / "report.md").is_file())
            rendered = "".join(call.args[0] for call in stdout.write.call_args_list)
            data = json.loads(rendered)
            self.assertEqual(data["providers"]["stub"]["completed"], 4)
            self.assertEqual(data["recommendation"]["primary"], None)
            self.assertEqual(len(list(out_dir.glob("*.wav"))), 4)

    def test_cli_stt_stub_returns_transcript_and_event_log(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            input_audio = root / "input.wav"
            events = root / "stt.jsonl"
            StubToneProvider().run_text(
                VoiceRequest(
                    text="input audio",
                    expression=VoiceExpression(),
                    output_path=input_audio,
                ),
                MetricsRecorder(),
            )

            with patch("sys.stdout") as stdout:
                rc = main(
                    [
                        "stt",
                        "--provider",
                        "stub",
                        "--input",
                        str(input_audio),
                        "--expected-text",
                        "expected transcript",
                        "--event-log",
                        str(events),
                        "--json",
                    ]
                )

            self.assertEqual(rc, 0)
            self.assertTrue(events.is_file())
            rendered = "".join(call.args[0] for call in stdout.write.call_args_list)
            data = json.loads(rendered)
            self.assertEqual(data["provider"], "stub")
            self.assertEqual(data["transcript"], "expected transcript")
            self.assertEqual(data["audio_path"], str(input_audio))
            self.assertEqual(data["metadata"]["sample_rate"], 24000)

    def test_cli_speech_to_speech_stub_writes_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            input_audio = root / "input.wav"
            output_audio = root / "s2s.wav"
            StubToneProvider().run_text(
                VoiceRequest(
                    text="input audio",
                    expression=VoiceExpression(),
                    output_path=input_audio,
                ),
                MetricsRecorder(),
            )

            with patch("sys.stdout") as stdout:
                rc = main(
                    [
                        "speech-to-speech",
                        "--provider",
                        "stub",
                        "--input",
                        str(input_audio),
                        "--text",
                        "stub response",
                        "--output",
                        str(output_audio),
                        "--json",
                    ]
                )

            self.assertEqual(rc, 0)
            self.assertTrue(output_audio.is_file())
            rendered = "".join(call.args[0] for call in stdout.write.call_args_list)
            data = json.loads(rendered)
            self.assertEqual(data["provider"], "stub")
            self.assertEqual(data["audio_path"], str(output_audio))
            self.assertEqual(data["metadata"]["input_audio_path"], str(input_audio))
            self.assertIn("input_transcript", data["metadata"])

    def test_cli_quick_test_defaults_text(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "quick.wav"
            with patch("sys.stdout") as stdout:
                rc = main(
                    [
                        "test",
                        "stub",
                        "--output",
                        str(output),
                        "--json",
                    ]
                )

            self.assertEqual(rc, 0)
            self.assertTrue(output.is_file())
            rendered = "".join(call.args[0] for call in stdout.write.call_args_list)
            data = json.loads(rendered)
            self.assertEqual(data["provider"], "stub")

    def test_cli_auth_uses_lab_owned_xai_oauth_flow(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            auth_path = Path(tmp) / "auth" / "xai-oauth.json"
            with patch("plugin.voice_lab.cli.login_xai_oauth") as login:
                login.return_value = XAIAuthResult(
                    auth_file=auth_path,
                    base_url="https://api.x.ai/v1",
                    expires_at_ms=123,
                    token_type="Bearer",
                )
                with patch("sys.stdout") as stdout:
                    rc = main(["auth", "--provider", "grok", "--json"])

            self.assertEqual(rc, 0)
            login.assert_called_once()
            rendered = "".join(call.args[0] for call in stdout.write.call_args_list)
            data = json.loads(rendered)
            self.assertEqual(data["provider"], "xai_realtime")
            self.assertEqual(data["auth_file"], str(auth_path))

    def test_cli_tui_runs_persistent_prompt_loop(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out_dir = root / "runs"
            event_dir = root / "events"
            stdin = io.StringIO(":emotion warm\n:voice testvoice\nTesting via TUI.\n:quit\n")
            stdout = io.StringIO()
            with patch("sys.stdin", stdin), patch("sys.stdout", stdout):
                rc = main(
                    [
                        "tui",
                        "--provider",
                        "stub",
                        "--output-dir",
                        str(out_dir),
                        "--event-dir",
                        str(event_dir),
                        "--visual",
                        "off",
                    ]
                )

            self.assertEqual(rc, 0)
            rendered = stdout.getvalue()
            self.assertIn("PULSEFORGE VOICE LAB", rendered)
            self.assertIn("EMOTION: warm", rendered)
            wavs = list(out_dir.glob("*.wav"))
            logs = list(event_dir.glob("*.jsonl"))
            self.assertEqual(len(wavs), 1)
            self.assertEqual(len(logs), 1)
            self.assertIn("audio_chunk", logs[0].read_text(encoding="utf-8"))

    @unittest.skipUnless(importlib.util.find_spec("textual"), "textual not installed")
    def test_textual_tui_runs_stub_prompt_with_keyboard_submit(self) -> None:
        from plugin.voice_lab.textual_tui import VoiceLabTextualApp
        from plugin.voice_lab.tui_session import TuiSessionConfig

        async def _scenario() -> tuple[str, str | None, int, int, dict[str, str], str | None, str | None]:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                app = VoiceLabTextualApp(
                    registry=default_registry(),
                    config=TuiSessionConfig(
                        provider_id="stub",
                        expression=VoiceExpression(),
                        output_dir=root / "runs",
                        event_dir=root / "events",
                        visual_mode="on",
                    ),
                )
                async with app.run_test(size=(120, 40)) as pilot:
                    await pilot.pause()
                    dropdown_values = {
                        "provider": str(app.query_one("#provider-select").value),
                        "model": str(app.query_one("#model-select").value),
                        "voice": str(app.query_one("#voice-select").value),
                        "emotion": str(app.query_one("#emotion-select").value),
                        "tone": str(app.query_one("#tone-select").value),
                        "command": str(app.query_one("#command-select").value),
                    }
                    await pilot.press("f2")
                    provider_focus = app.focused.id if app.focused else None
                    await pilot.press("f3")
                    command_focus = app.focused.id if app.focused else None
                    app.query_one("#prompt-input").value = "Textual TUI smoke."
                    await pilot.press("ctrl+r")
                    for _ in range(30):
                        await pilot.pause(0.1)
                        if app.state.last_response or app.state.last_error:
                            break
                    return (
                        app.status_text,
                        app.state.last_error,
                        len(list((root / "runs").glob("*.wav"))),
                        len(list((root / "events").glob("*.jsonl"))),
                        dropdown_values,
                        provider_focus,
                        command_focus,
                    )

        status, error, wav_count, log_count, dropdown_values, provider_focus, command_focus = (
            asyncio.run(_scenario())
        )
        self.assertEqual(status, "READY")
        self.assertIsNone(error)
        self.assertEqual(wav_count, 1)
        self.assertEqual(log_count, 1)
        self.assertEqual(dropdown_values["provider"], "stub")
        self.assertEqual(dropdown_values["model"], "local-tone")
        self.assertEqual(dropdown_values["voice"], "sine")
        self.assertEqual(dropdown_values["command"], "run")
        self.assertEqual(provider_focus, "provider-select")
        self.assertEqual(command_focus, "command-select")

    def test_cli_doctor_reports_missing_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {"VOICE_LAB_HOME": tmp}, clear=True):
                with patch("sys.stdout") as stdout:
                    rc = main(["doctor", "--output-dir", str(Path(tmp) / "runs")])

            self.assertEqual(rc, 1)
            rendered = "".join(call.args[0] for call in stdout.write.call_args_list)
            self.assertIn("Voice Lab Doctor", rendered)
            self.assertIn("openai key", rendered)
            self.assertIn("elevenlabs key", rendered)
            self.assertIn("xai auth", rendered)

    def test_waveform_runner_can_render_to_stream(self) -> None:
        recorder = MetricsRecorder()
        stream = io.StringIO()

        def _work() -> str:
            recorder.audio_chunk(byte_count=128, label="test")
            time.sleep(0.12)
            return "done"

        result = run_with_waveform(
            label="stub",
            recorder=recorder,
            visual_mode="on",
            fn=_work,
            stream=stream,
        )

        self.assertEqual(result, "done")
        self.assertIn("voice stub", stream.getvalue())
        waveform = render_waveform(0, levels=[0.0, 0.5, 1.0], unicode=False)
        self.assertEqual(len(waveform), 18)
        self.assertTrue(waveform.endswith(".~#"))

    def test_cli_play_invokes_local_audio_playback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "quick.wav"
            with patch("plugin.voice_lab.cli.play_audio_file") as play_audio:
                with patch("sys.stdout"):
                    rc = main(
                        [
                            "test",
                            "stub",
                            "--output",
                            str(output),
                            "--play",
                            "--json",
                        ]
                    )

            self.assertEqual(rc, 0)
            play_audio.assert_called_once_with(output)

    def test_openai_provider_requires_api_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {"VOICE_LAB_HOME": tmp}, clear=True):
                with self.assertRaises(ProviderUnavailable):
                    OpenAIRealtimeProvider().run_text(
                        VoiceRequest(
                            text="Testing.",
                            expression=VoiceExpression(),
                            output_path=Path(tmp) / "openai.wav",
                        ),
                        MetricsRecorder(),
                    )

    def test_openai_tts_provider_writes_wav_from_pcm_stream(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 200
        captured = {}

        def _factory(request, timeout):
            captured["url"] = request.full_url
            captured["headers"] = dict(request.header_items())
            captured["body"] = request.data
            captured["timeout"] = timeout
            return _FakeHttpResponse([pcm[:200], pcm[200:]])

        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "openai-tts.wav"
            streamed: list[tuple[bytes, dict]] = []
            response = OpenAITTSProvider(stream_factory=_factory).run_text(
                VoiceRequest(
                    text="Testing final assistant speech.",
                    expression=VoiceExpression(emotion="warm"),
                    output_path=output,
                    provider_options={
                        "api_key": "sk-test",
                        "model": "gpt-4o-mini-tts",
                        "voice": "coral",
                        "timeout": "3",
                    },
                    audio_sink=lambda chunk, meta: streamed.append((chunk, meta)),
                ),
                MetricsRecorder(),
            )
            with wave.open(str(output), "rb") as wav:
                channels = wav.getnchannels()
                rate = wav.getframerate()

        self.assertEqual(response.provider, "openai_tts")
        self.assertEqual(response.model, "gpt-4o-mini-tts")
        self.assertEqual(response.voice, "coral")
        self.assertEqual(response.metadata["audio_bytes"], len(pcm))
        self.assertEqual(captured["url"], "https://api.openai.com/v1/audio/speech")
        self.assertEqual(captured["timeout"], 3.0)
        self.assertEqual(captured["headers"]["Authorization"], "Bearer sk-test")
        body = json.loads(captured["body"].decode("utf-8"))
        self.assertEqual(body["response_format"], "pcm")
        self.assertEqual(body["input"], "Testing final assistant speech.")
        self.assertEqual(sum(len(chunk) for chunk, _ in streamed), len(pcm))
        self.assertEqual(channels, 1)
        self.assertEqual(rate, 24000)

    def test_xai_tts_provider_writes_wav_from_pcm_stream(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 200
        captured = {}

        def _factory(request, timeout):
            captured["url"] = request.full_url
            captured["headers"] = dict(request.header_items())
            captured["body"] = request.data
            captured["timeout"] = timeout
            return _FakeHttpResponse([pcm[:128], pcm[128:]])

        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "xai-tts.wav"
            streamed: list[tuple[bytes, dict]] = []
            response = XAITTSProvider(stream_factory=_factory).run_text(
                VoiceRequest(
                    text="Testing final assistant speech.",
                    expression=VoiceExpression(emotion="warm"),
                    output_path=output,
                    provider_options={
                        "api_key": "xai-test",
                        "voice": "eve",
                        "timeout": "3",
                        "optimize_streaming_latency": "1",
                    },
                    audio_sink=lambda chunk, meta: streamed.append((chunk, meta)),
                ),
                MetricsRecorder(),
            )
            with wave.open(str(output), "rb") as wav:
                channels = wav.getnchannels()
                rate = wav.getframerate()

        self.assertEqual(response.provider, "xai_tts")
        self.assertEqual(response.model, "xai-tts")
        self.assertEqual(response.voice, "eve")
        self.assertEqual(response.metadata["audio_bytes"], len(pcm))
        self.assertEqual(captured["url"], "https://api.x.ai/v1/tts")
        self.assertEqual(captured["timeout"], 3.0)
        self.assertEqual(captured["headers"]["Authorization"], "Bearer xai-test")
        body = json.loads(captured["body"].decode("utf-8"))
        self.assertEqual(body["voice_id"], "eve")
        self.assertEqual(body["output_format"], {"codec": "pcm", "sample_rate": 24000})
        self.assertEqual(body["optimize_streaming_latency"], 1)
        self.assertEqual(sum(len(chunk) for chunk, _ in streamed), len(pcm))
        self.assertEqual(channels, 1)
        self.assertEqual(rate, 24000)

    def test_openai_provider_reads_voice_tools_openai_key_from_voice_lab_env(
        self,
    ) -> None:
        pcm = b"\x00\x00\x01\x00" * 100
        fake = _FakeSocket(
            [
                {
                    "type": "response.output_audio.delta",
                    "delta": base64.b64encode(pcm).decode("ascii"),
                },
                {"type": "response.done", "response": {"id": "resp_1"}},
            ]
        )
        captured = {}

        def _factory(url, headers, timeout):
            captured["headers"] = headers
            return fake

        with tempfile.TemporaryDirectory() as tmp:
            lab_home = Path(tmp) / "voice-lab"
            lab_home.mkdir()
            (lab_home / ".env").write_text(
                "VOICE_TOOLS_OPENAI_KEY=sk-env-test\n",
                encoding="utf-8",
            )
            with patch.dict(os.environ, {"VOICE_LAB_HOME": str(lab_home)}, clear=True):
                response = OpenAIRealtimeProvider(socket_factory=_factory).run_text(
                    VoiceRequest(
                        text="Testing.",
                        expression=VoiceExpression(),
                        output_path=Path(tmp) / "openai-env.wav",
                    ),
                    MetricsRecorder(),
                )

            self.assertEqual(response.provider, "openai_realtime")
            self.assertIn("Authorization: Bearer sk-env-test", captured["headers"])

    def test_openai_provider_writes_wav_from_audio_delta(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 200
        fake = _FakeSocket(
            [
                {"type": "session.created", "event_id": "evt_1"},
                {
                    "type": "response.audio.delta",
                    "response_id": "resp_1",
                    "delta": base64.b64encode(pcm).decode("ascii"),
                },
                {"type": "response.audio.done", "response_id": "resp_1"},
                {"type": "response.done", "response": {"id": "resp_1"}},
            ]
        )
        captured = {}

        def _factory(url, headers, timeout):
            captured["url"] = url
            captured["headers"] = headers
            captured["timeout"] = timeout
            return fake

        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "openai.wav"
            streamed: list[tuple[bytes, dict]] = []
            response = OpenAIRealtimeProvider(socket_factory=_factory).run_text(
                VoiceRequest(
                    text="Testing one two three.",
                    expression=VoiceExpression(emotion="warm", tone="encouraging"),
                    output_path=output,
                    provider_options={
                        "api_key": "sk-test",
                        "timeout": "3",
                        "model": "gpt-realtime-2",
                    },
                    audio_sink=lambda chunk, meta: streamed.append((chunk, meta)),
                ),
                MetricsRecorder(),
            )

            self.assertEqual(response.provider, "openai_realtime")
            self.assertEqual(response.model, "gpt-realtime-2")
            self.assertEqual(response.voice, "marin")
            self.assertEqual(response.metadata["audio_bytes"], len(pcm))
            self.assertIn("model=gpt-realtime-2", captured["url"])
            self.assertIn("Authorization: Bearer sk-test", captured["headers"])
            self.assertEqual(captured["timeout"], 3.0)
            self.assertEqual(streamed, [(pcm, {
                "label": "response.audio.delta",
                "sample_rate": 24000,
                "channels": 1,
                "sample_width": 2,
            })])
            self.assertEqual(
                [event["type"] for event in fake.sent],
                [
                    "session.update",
                    "conversation.item.create",
                    "response.create",
                ],
            )
            self.assertEqual(
                fake.sent[0]["session"]["audio"]["output"]["format"],
                {"type": "audio/pcm", "rate": 24000},
            )
            self.assertEqual(
                fake.sent[2]["response"]["audio"]["output"]["format"],
                {"type": "audio/pcm", "rate": 24000},
            )
            self.assertTrue(fake.closed)
            with wave.open(str(output), "rb") as wav:
                self.assertEqual(wav.getnchannels(), 1)
                self.assertEqual(wav.getframerate(), 24000)
            self.assertGreater(wav.getnframes(), 0)

    def test_openai_provider_verbatim_mode_sends_speech_renderer_prompt(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 100
        fake = _FakeSocket(
            [
                {
                    "type": "response.audio.delta",
                    "response_id": "resp_1",
                    "delta": base64.b64encode(pcm).decode("ascii"),
                },
                {"type": "response.done", "response": {"id": "resp_1"}},
            ]
        )

        with tempfile.TemporaryDirectory() as tmp:
            response = OpenAIRealtimeProvider(socket_factory=lambda *_: fake).run_text(
                VoiceRequest(
                    text="The chat answer is final.",
                    expression=VoiceExpression(),
                    output_path=Path(tmp) / "openai-verbatim.wav",
                    provider_options={
                        "api_key": "sk-test",
                        "render_mode": "verbatim",
                    },
                ),
                MetricsRecorder(),
            )

        self.assertEqual(response.provider, "openai_realtime")
        instructions = fake.sent[0]["session"]["instructions"]
        item_text = fake.sent[1]["item"]["content"][0]["text"]
        self.assertIn("speech renderer", instructions)
        self.assertIn("Do not answer it", instructions)
        self.assertIn("Read aloud exactly", item_text)
        self.assertIn("The chat answer is final.", item_text)

    def test_elevenlabs_provider_writes_wav_from_stream(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 240
        captured = {}

        def _factory(request, timeout):
            captured["url"] = request.full_url
            captured["api_key"] = request.get_header("Xi-api-key")
            captured["content_type"] = request.get_header("Content-type")
            captured["body"] = request.data
            captured["timeout"] = timeout
            return _FakeHttpResponse(
                [pcm[:160], pcm[160:]],
                headers={"request-id": "req_123", "x-character-count": "8"},
            )

        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "eleven.wav"
            streamed: list[tuple[bytes, dict]] = []
            response = ElevenLabsTTSProvider(stream_factory=_factory).run_text(
                VoiceRequest(
                    text="Testing.",
                    expression=VoiceExpression(emotion="calm"),
                    output_path=output,
                    provider_options={
                        "api_key": "el-test",
                        "voice": "voice_123",
                        "model": "eleven_flash_v2_5",
                        "output_format": "pcm_24000",
                        "timeout": "3",
                    },
                    audio_sink=lambda chunk, meta: streamed.append((chunk, meta)),
                ),
                MetricsRecorder(),
            )

            self.assertEqual(response.provider, "elevenlabs_tts")
            self.assertEqual(response.model, "eleven_flash_v2_5")
            self.assertEqual(response.voice, "voice_123")
            self.assertEqual(response.metadata["audio_bytes"], len(pcm))
            self.assertEqual(captured["api_key"], "el-test")
            self.assertEqual(captured["timeout"], 3.0)
            self.assertIn("/v1/text-to-speech/voice_123/stream", captured["url"])
            self.assertIn("output_format=pcm_24000", captured["url"])
            body = json.loads(captured["body"].decode("utf-8"))
            self.assertEqual(body["model_id"], "eleven_flash_v2_5")
            self.assertIn("Delivery note", body["text"])
            self.assertEqual(sum(len(chunk) for chunk, _ in streamed), len(pcm))
            self.assertEqual(
                streamed[0][1],
                {
                    "label": "elevenlabs_stream",
                    "sample_rate": 24000,
                    "channels": 1,
                    "sample_width": 2,
                },
            )
            with wave.open(str(output), "rb") as wav:
                self.assertEqual(wav.getnchannels(), 1)
                self.assertEqual(wav.getframerate(), 24000)
                self.assertGreater(wav.getnframes(), 0)

    def test_elevenlabs_provider_requires_api_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {"VOICE_LAB_HOME": tmp}, clear=True):
                with self.assertRaises(ProviderUnavailable):
                    ElevenLabsTTSProvider().run_text(
                        VoiceRequest(
                            text="Testing.",
                            expression=VoiceExpression(),
                            output_path=Path(tmp) / "eleven.wav",
                        ),
                        MetricsRecorder(),
                    )

    def test_xai_provider_requires_api_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {"VOICE_LAB_HOME": tmp}, clear=True):
                with self.assertRaises(ProviderUnavailable) as ctx:
                    XAIRealtimeProvider().run_text(
                        VoiceRequest(
                            text="Testing.",
                            expression=VoiceExpression(),
                            output_path=Path(tmp) / "xai.wav",
                        ),
                        MetricsRecorder(),
                    )

            self.assertIn("SuperGrok", str(ctx.exception))
            self.assertIn("lab-owned", str(ctx.exception))

    def test_xai_provider_reads_xai_key_from_voice_lab_env(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 100
        fake = _FakeSocket(
            [
                {
                    "type": "response.output_audio.delta",
                    "delta": base64.b64encode(pcm).decode("ascii"),
                },
                {"type": "response.done", "response": {"id": "resp_xai_1"}},
            ]
        )
        captured = {}

        def _factory(url, headers, timeout):
            captured["headers"] = headers
            return fake

        with tempfile.TemporaryDirectory() as tmp:
            lab_home = Path(tmp) / "voice-lab"
            lab_home.mkdir()
            (lab_home / ".env").write_text(
                "VOICE_TOOLS_XAI_KEY=xai-env-test\n",
                encoding="utf-8",
            )
            with patch.dict(os.environ, {"VOICE_LAB_HOME": str(lab_home)}, clear=True):
                response = XAIRealtimeProvider(socket_factory=_factory).run_text(
                    VoiceRequest(
                        text="Testing.",
                        expression=VoiceExpression(),
                        output_path=Path(tmp) / "xai-env.wav",
                    ),
                    MetricsRecorder(),
                )

            self.assertEqual(response.provider, "xai_realtime")
            self.assertIn("Authorization: Bearer xai-env-test", captured["headers"])

    def test_xai_provider_reads_voice_lab_xai_oauth_store(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 100
        fake = _FakeSocket(
            [
                {
                    "type": "response.output_audio.delta",
                    "delta": base64.b64encode(pcm).decode("ascii"),
                },
                {"type": "response.done", "response": {"id": "resp_xai_1"}},
            ]
        )
        captured = {}

        def _factory(url, headers, timeout):
            captured["url"] = url
            captured["headers"] = headers
            return fake

        with tempfile.TemporaryDirectory() as tmp:
            lab_home = Path(tmp) / "voice-lab"
            auth_dir = lab_home / "auth"
            auth_dir.mkdir(parents=True)
            (auth_dir / "xai-oauth.json").write_text(
                json.dumps(
                    {
                        "tokens": {
                            "access_token": "oauth-test-token",
                            "expires_at_ms": int((time.time() + 3600) * 1000),
                            "base_url": "https://api.x.ai/v1",
                        }
                    }
                ),
                encoding="utf-8",
            )
            with patch.dict(os.environ, {"VOICE_LAB_HOME": str(lab_home)}, clear=True):
                response = XAIRealtimeProvider(socket_factory=_factory).run_text(
                    VoiceRequest(
                        text="Testing.",
                        expression=VoiceExpression(),
                        output_path=Path(tmp) / "xai-oauth.wav",
                    ),
                    MetricsRecorder(),
                )

            self.assertEqual(response.provider, "xai_realtime")
            self.assertIn("Authorization: Bearer oauth-test-token", captured["headers"])
            self.assertEqual(
                captured["url"],
                "wss://api.x.ai/v1/realtime?model=grok-voice-latest",
            )
            self.assertIn("xai-oauth", response.metadata["auth_source"])

    def test_xai_provider_writes_wav_from_audio_delta(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 200
        fake = _FakeSocket(
            [
                {"type": "session.created", "event_id": "evt_1"},
                {
                    "type": "response.output_audio.delta",
                    "response_id": "resp_1",
                    "delta": base64.b64encode(pcm).decode("ascii"),
                },
                {"type": "response.output_audio.done", "response_id": "resp_1"},
                {"type": "response.done", "response": {"id": "resp_1"}},
            ]
        )
        captured = {}

        def _factory(url, headers, timeout):
            captured["url"] = url
            captured["headers"] = headers
            captured["timeout"] = timeout
            return fake

        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "xai.wav"
            response = XAIRealtimeProvider(socket_factory=_factory).run_text(
                VoiceRequest(
                    text="Testing one two three.",
                    expression=VoiceExpression(emotion="warm", tone="encouraging"),
                    output_path=output,
                    provider_options={
                        "api_key": "xai-test",
                        "timeout": "3",
                        "model": "grok-voice-latest",
                        "tool_scaffold": "true",
                    },
                ),
                MetricsRecorder(),
            )

            self.assertEqual(response.provider, "xai_realtime")
            self.assertEqual(response.model, "grok-voice-latest")
            self.assertEqual(response.voice, "eve")
            self.assertEqual(response.metadata["audio_bytes"], len(pcm))
            self.assertIn("model=grok-voice-latest", captured["url"])
            self.assertIn("Authorization: Bearer xai-test", captured["headers"])
            self.assertEqual(captured["timeout"], 3.0)
            self.assertEqual(
                [event["type"] for event in fake.sent],
                [
                    "session.update",
                    "conversation.item.create",
                    "response.create",
                ],
            )
            self.assertEqual(fake.sent[0]["session"]["voice"], "eve")
            self.assertEqual(fake.sent[0]["session"]["tools"][0]["name"], "voice_lab_echo")
            self.assertTrue(fake.closed)
            with wave.open(str(output), "rb") as wav:
                self.assertEqual(wav.getnchannels(), 1)
                self.assertEqual(wav.getframerate(), 24000)
                self.assertGreater(wav.getnframes(), 0)

    def test_xai_provider_verbatim_mode_sends_speech_renderer_prompt(self) -> None:
        pcm = b"\x00\x00\x01\x00" * 100
        fake = _FakeSocket(
            [
                {
                    "type": "response.output_audio.delta",
                    "response_id": "resp_1",
                    "delta": base64.b64encode(pcm).decode("ascii"),
                },
                {"type": "response.done", "response": {"id": "resp_1"}},
            ]
        )

        with tempfile.TemporaryDirectory() as tmp:
            response = XAIRealtimeProvider(socket_factory=lambda *_: fake).run_text(
                VoiceRequest(
                    text="The chat answer is final.",
                    expression=VoiceExpression(),
                    output_path=Path(tmp) / "xai-verbatim.wav",
                    provider_options={
                        "api_key": "xai-test",
                        "render_mode": "verbatim",
                    },
                ),
                MetricsRecorder(),
            )

        self.assertEqual(response.provider, "xai_realtime")
        instructions = fake.sent[0]["session"]["instructions"]
        item_text = fake.sent[1]["item"]["content"][0]["text"]
        self.assertNotIn("tools", fake.sent[0]["session"])
        self.assertIn("speech renderer", instructions)
        self.assertIn("Do not answer it", instructions)
        self.assertIn("Read aloud exactly", item_text)
        self.assertIn("The chat answer is final.", item_text)

    def test_cli_reports_openai_missing_key_without_traceback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {"VOICE_LAB_HOME": tmp}, clear=True):
                with patch("sys.stderr") as stderr:
                    rc = main(
                        [
                            "realtime-text",
                            "--provider",
                            "openai_realtime",
                            "--text",
                            "Testing.",
                            "--output",
                            str(Path(tmp) / "openai.wav"),
                        ]
                    )

            self.assertEqual(rc, 3)
            rendered = "".join(call.args[0] for call in stderr.write.call_args_list)
            self.assertIn("OPENAI_API_KEY", rendered)

    def test_cli_reports_xai_missing_key_without_traceback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {"VOICE_LAB_HOME": tmp}, clear=True):
                with patch("sys.stderr") as stderr:
                    rc = main(
                        [
                            "realtime-text",
                            "--provider",
                            "xai_realtime",
                            "--text",
                            "Testing.",
                            "--output",
                            str(Path(tmp) / "xai.wav"),
                        ]
                    )

            self.assertEqual(rc, 3)
            rendered = "".join(call.args[0] for call in stderr.write.call_args_list)
            self.assertIn("XAI_API_KEY", rendered)
            self.assertIn("SuperGrok", rendered)


if __name__ == "__main__":
    unittest.main()
