"""Standalone CLI harness for realtime voice provider experiments."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .auth import (
    VoiceLabAuthError,
    load_voice_lab_env_file,
    login_xai_oauth,
    read_xai_oauth_token,
    voice_lab_env_path,
    xai_oauth_path,
)
from .eval_approach import run_approach_eval
from .expressions import VoiceExpression
from .metrics import MetricsRecorder
from .playback import PlaybackError, play_audio_file
from .providers.base import (
    ProviderRunError,
    ProviderUnavailable,
    VoiceRequest,
    VoiceResponse,
    VoiceSpeechToSpeechRequest,
    VoiceTranscriptionRequest,
    VoiceTranscriptionResponse,
)
from .registry import ProviderRegistry, default_registry
from .terminal_ui import run_with_waveform
from .tui_session import TuiSessionConfig, normalize_provider_id, run_tui_session


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hermes-voice-lab",
        description=(
            "Standalone provider-lab CLI for voice experiments. This does not "
            "start or modify Hermes-Relay routes."
        ),
    )
    sub = parser.add_subparsers(dest="command", required=True)

    list_cmd = sub.add_parser(
        "list-providers",
        aliases=["providers"],
        help="List available lab providers",
    )
    list_cmd.add_argument("--json", action="store_true", help="Print JSON")
    list_cmd.set_defaults(func=_cmd_list_providers)

    doctor = sub.add_parser(
        "doctor",
        help="Check local voice-lab dependencies and provider credentials",
    )
    doctor.add_argument(
        "--output-dir",
        type=Path,
        default=Path("voice-lab-runs"),
        help="Output directory to check (default: voice-lab-runs)",
    )
    doctor.add_argument("--json", action="store_true", help="Print JSON")
    doctor.set_defaults(func=_cmd_doctor)

    auth = sub.add_parser(
        "auth",
        help="Run a lab-owned provider auth flow",
    )
    auth.add_argument(
        "--provider",
        choices=["grok", "xai", "xai_realtime"],
        default="grok",
        help="Provider to authorize (default: grok)",
    )
    auth.add_argument(
        "--auth-file",
        type=Path,
        help="Override lab-owned auth file path",
    )
    auth.add_argument(
        "--no-browser",
        action="store_true",
        help="Print the OAuth URL without trying to open a browser",
    )
    auth.add_argument(
        "--timeout",
        type=float,
        default=180.0,
        help="Seconds to wait for the local browser callback",
    )
    auth.add_argument("--json", action="store_true", help="Print JSON")
    auth.set_defaults(func=_cmd_auth)

    quick_test = sub.add_parser(
        "test",
        help="Run a quick provider test with clean terminal progress",
    )
    quick_test.add_argument(
        "provider",
        nargs="?",
        default="openai_realtime",
        help="Provider id (default: openai_realtime)",
    )
    _add_run_args(quick_test, include_provider=False)
    quick_test.add_argument("--output", type=Path, help="Audio output path")
    quick_test.add_argument(
        "--event-log",
        type=Path,
        help="Optional JSONL raw event log path",
    )
    quick_test.set_defaults(func=_cmd_test_provider)

    realtime = sub.add_parser(
        "realtime-text",
        help="Run one text-to-audio provider experiment",
    )
    _add_run_args(realtime)
    realtime.add_argument("--output", type=Path, help="Audio output path")
    realtime.add_argument(
        "--event-log",
        type=Path,
        help="Optional JSONL raw event log path",
    )
    realtime.set_defaults(func=_cmd_realtime_text)

    stt = sub.add_parser(
        "stt",
        aliases=["transcribe"],
        help="Run one speech-to-text provider experiment",
    )
    _add_audio_input_args(stt)
    stt.add_argument(
        "--expected-text",
        help="Optional expected transcript for stub/scaffold validation",
    )
    stt.set_defaults(func=_cmd_stt)

    s2s = sub.add_parser(
        "speech-to-speech",
        aliases=["s2s"],
        help="Run one speech-to-speech provider experiment",
    )
    _add_run_args(s2s)
    s2s.add_argument(
        "--input",
        required=True,
        type=Path,
        help="Input WAV/PCM audio path for the user utterance",
    )
    s2s.add_argument("--output", type=Path, help="Audio output path")
    s2s.add_argument(
        "--event-log",
        type=Path,
        help="Optional JSONL raw event log path",
    )
    s2s.set_defaults(func=_cmd_speech_to_speech)

    tui = sub.add_parser(
        "tui",
        help="Run a persistent PulseForge-inspired voice iteration TUI",
    )
    _add_run_args(tui)
    tui.add_argument(
        "--output-dir",
        type=Path,
        default=Path("voice-lab-runs"),
        help="Directory for per-turn WAV outputs",
    )
    tui.add_argument(
        "--event-dir",
        type=Path,
        help="Optional directory for per-turn JSONL event logs",
    )
    tui.add_argument(
        "--no-event-log",
        action="store_true",
        help="Do not write per-turn JSONL event logs",
    )
    tui.set_defaults(func=_cmd_tui)

    bench = sub.add_parser(
        "bench",
        help="Run providers over each non-empty line in a script file",
    )
    _add_bench_args(bench)
    bench.set_defaults(func=_cmd_bench)

    bench_expression = sub.add_parser(
        "bench-expression",
        help="Run providers across script lines and emotion labels",
    )
    _add_bench_args(bench_expression)
    bench_expression.add_argument(
        "--emotions",
        required=True,
        help="Comma-separated emotion labels, e.g. calm,warm,serious",
    )
    bench_expression.set_defaults(func=_cmd_bench_expression)

    eval_approach = sub.add_parser(
        "eval-approach",
        aliases=["approach-eval"],
        help="Run the fixed E2E provider matrix for choosing a voice approach",
    )
    eval_approach.add_argument(
        "--providers",
        default="xai_tts,openai_tts,xai_realtime,openai_realtime,elevenlabs_tts,stub",
        help="Comma-separated provider ids",
    )
    eval_approach.add_argument(
        "--output-dir",
        type=Path,
        default=Path("voice-lab-runs") / "e2e",
        help="Directory for WAV, JSONL, summary, and report artifacts",
    )
    eval_approach.add_argument(
        "--event-dir",
        type=Path,
        help="Optional directory for per-run JSONL event logs",
    )
    eval_approach.add_argument(
        "--tool-delay-ms",
        type=int,
        default=750,
        help="Simulated tool wait before the final-answer scenario",
    )
    eval_approach.add_argument(
        "--provider-option",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="Provider-specific option applied to all providers; repeat as needed",
    )
    _add_visual_arg(eval_approach)
    eval_approach.add_argument("--json", action="store_true", help="Print JSON")
    eval_approach.set_defaults(func=_cmd_eval_approach)

    return parser


def _add_run_args(
    parser: argparse.ArgumentParser,
    *,
    include_provider: bool = True,
) -> None:
    if include_provider:
        parser.add_argument("--provider", default="stub", help="Provider id")
    parser.add_argument("--text", help="Text prompt to synthesize")
    parser.add_argument("--text-file", type=Path, help="Read text prompt from a file")
    parser.add_argument("--emotion", help="Expression emotion label")
    parser.add_argument("--tone", help="Expression tone label")
    parser.add_argument(
        "--intensity",
        type=float,
        default=0.5,
        help="Expression intensity from 0.0 to 1.0",
    )
    parser.add_argument("--pace", default="normal", help="Delivery pace label")
    parser.add_argument("--style", default="natural", help="Delivery style label")
    parser.add_argument(
        "--pronunciation-hint",
        action="append",
        default=[],
        help="Pronunciation hint; repeat for multiple hints",
    )
    parser.add_argument(
        "--interruption-behavior",
        default="allow",
        help="Interruption policy label",
    )
    parser.add_argument("--persona-instructions", help="Session persona instructions")
    parser.add_argument(
        "--provider-option",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="Provider-specific option; repeat for multiple options",
    )
    _add_visual_arg(parser)
    parser.add_argument("--json", action="store_true", help="Print JSON")
    parser.add_argument(
        "--play",
        action="store_true",
        help="Play the generated WAV after the run completes",
    )


def _add_bench_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--providers",
        default="stub",
        help="Comma-separated provider ids",
    )
    parser.add_argument(
        "--script",
        required=True,
        type=Path,
        help="Text file with one prompt per non-empty line",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("voice-lab-runs"),
        help="Directory for audio outputs",
    )
    parser.add_argument(
        "--event-dir",
        type=Path,
        help="Optional directory for per-run JSONL event logs",
    )
    parser.add_argument("--tone", help="Expression tone label")
    parser.add_argument(
        "--intensity",
        type=float,
        default=0.5,
        help="Expression intensity from 0.0 to 1.0",
    )
    parser.add_argument("--pace", default="normal", help="Delivery pace label")
    parser.add_argument("--style", default="natural", help="Delivery style label")
    parser.add_argument(
        "--provider-option",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="Provider-specific option; repeat for multiple options",
    )
    _add_visual_arg(parser)
    parser.add_argument("--json", action="store_true", help="Print JSON")


def _add_audio_input_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--provider", default="stub", help="Provider id")
    parser.add_argument(
        "--input",
        required=True,
        type=Path,
        help="Input WAV/PCM audio path",
    )
    parser.add_argument(
        "--provider-option",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="Provider-specific option; repeat for multiple options",
    )
    parser.add_argument(
        "--event-log",
        type=Path,
        help="Optional JSONL raw event log path",
    )
    _add_visual_arg(parser)
    parser.add_argument("--json", action="store_true", help="Print JSON")


def _add_visual_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--visual",
        choices=["auto", "on", "off"],
        default="auto",
        help="Terminal waveform progress mode (default: auto)",
    )


def _cmd_doctor(args: argparse.Namespace, registry: ProviderRegistry) -> int:
    checks = _doctor_checks(args.output_dir, registry)
    if args.json:
        print(json.dumps({"checks": checks}, indent=2, sort_keys=True))
        return 0 if all(item["ok"] for item in checks) else 1

    print("Voice Lab Doctor")
    for item in checks:
        status = "ok" if item["ok"] else "missing"
        print(f"{status:8} {item['name']}: {item['detail']}")
    return 0 if all(item["ok"] for item in checks) else 1


def _cmd_auth(args: argparse.Namespace, registry: ProviderRegistry) -> int:
    if args.provider not in {"grok", "xai", "xai_realtime"}:
        raise SystemExit(f"unsupported auth provider: {args.provider}")
    result = login_xai_oauth(
        auth_file=args.auth_file,
        no_browser=args.no_browser,
        timeout_seconds=args.timeout,
    )
    data = {
        "provider": "xai_realtime",
        "auth_file": str(result.auth_file),
        "base_url": result.base_url,
        "expires_at_ms": result.expires_at_ms,
        "token_type": result.token_type,
    }
    if args.json:
        print(json.dumps(data, indent=2, sort_keys=True))
        return 0
    print("xAI OAuth complete")
    print(f"auth_file: {result.auth_file}")
    print("provider: xai_realtime")
    return 0


def _cmd_list_providers(
    args: argparse.Namespace,
    registry: ProviderRegistry,
) -> int:
    data = registry.to_dict()
    if args.json:
        print(json.dumps(data, indent=2, sort_keys=True))
        return 0
    for info in registry.provider_infos():
        flags = []
        if info.supports_expression:
            flags.append("expression")
        if info.supports_tts:
            flags.append("tts")
        if info.supports_stt:
            flags.append("stt")
        if info.supports_speech_to_speech:
            flags.append("s2s")
        if info.supports_tool_use:
            flags.append("tool-use")
        if info.supports_realtime:
            flags.append("realtime")
        if info.supports_interruption:
            flags.append("interruption")
        suffix = f" ({', '.join(flags)})" if flags else ""
        print(f"{info.id}: {info.name}{suffix}")
        if info.description:
            print(f"  {info.description}")
    return 0


def _cmd_realtime_text(
    args: argparse.Namespace,
    registry: ProviderRegistry,
) -> int:
    text = _read_text_arg(args)
    expression = _expression_from_args(args)
    provider_options = _parse_key_values(args.provider_option)
    output_path = args.output or _default_audio_path(args.provider)
    response, events = _run_once(
        registry=registry,
        provider_id=args.provider,
        text=text,
        expression=expression,
        output_path=output_path,
        provider_options=provider_options,
        visual_mode=_run_visual_mode(args),
        label=_run_label(args.provider, expression),
    )
    if args.event_log:
        _write_event_log(args.event_log, events)
    _print_response(response, json_output=args.json)
    _maybe_play_response(response, args)
    return 0


def _cmd_stt(
    args: argparse.Namespace,
    registry: ProviderRegistry,
) -> int:
    provider_options = _parse_key_values(args.provider_option)
    response, events = _run_transcription_once(
        registry=registry,
        provider_id=args.provider,
        audio_path=args.input,
        expected_text=args.expected_text,
        provider_options=provider_options,
        visual_mode=_run_visual_mode(args),
    )
    if args.event_log:
        _write_event_log(args.event_log, events)
    _print_transcription_response(response, json_output=args.json)
    return 0


def _cmd_speech_to_speech(
    args: argparse.Namespace,
    registry: ProviderRegistry,
) -> int:
    response_text = _read_text_arg(
        args,
        default="I heard the input audio and am responding from the voice lab.",
    )
    expression = _expression_from_args(args)
    provider_options = _parse_key_values(args.provider_option)
    output_path = args.output or _default_audio_path(args.provider)
    response, events = _run_speech_to_speech_once(
        registry=registry,
        provider_id=args.provider,
        audio_path=args.input,
        response_text=response_text,
        expression=expression,
        output_path=output_path,
        provider_options=provider_options,
        visual_mode=_run_visual_mode(args),
        label=_run_label(args.provider, expression, mode="s2s"),
    )
    if args.event_log:
        _write_event_log(args.event_log, events)
    _print_response(response, json_output=args.json)
    _maybe_play_response(response, args)
    return 0


def _cmd_tui(args: argparse.Namespace, registry: ProviderRegistry) -> int:
    initial_text = None
    if getattr(args, "text", None) or getattr(args, "text_file", None):
        initial_text = _read_text_arg(args)
    expression = _expression_from_args(args)
    provider_options = _parse_key_values(args.provider_option)
    return run_tui_session(
        registry=registry,
        config=TuiSessionConfig(
            provider_id=normalize_provider_id(args.provider),
            expression=expression,
            provider_options=provider_options,
            output_dir=args.output_dir,
            event_dir=args.event_dir,
            event_log=not args.no_event_log,
            play=bool(args.play),
            visual_mode=_run_visual_mode(args),
            initial_text=initial_text,
            json_output=bool(args.json),
        ),
    )


def _cmd_test_provider(
    args: argparse.Namespace,
    registry: ProviderRegistry,
) -> int:
    text = _read_text_arg(args, default="Testing one two three.")
    expression = _expression_from_args(args)
    provider_options = _parse_key_values(args.provider_option)
    output_path = args.output or _default_audio_path(args.provider)
    response, events = _run_once(
        registry=registry,
        provider_id=args.provider,
        text=text,
        expression=expression,
        output_path=output_path,
        provider_options=provider_options,
        visual_mode=_run_visual_mode(args),
        label=_run_label(args.provider, expression),
    )
    if args.event_log:
        _write_event_log(args.event_log, events)
    _print_response(response, json_output=args.json)
    _maybe_play_response(response, args)
    return 0


def _cmd_bench(args: argparse.Namespace, registry: ProviderRegistry) -> int:
    results = _run_bench(
        args=args,
        registry=registry,
        emotions=[None],
    )
    return _print_bench(results, json_output=args.json)


def _cmd_bench_expression(
    args: argparse.Namespace,
    registry: ProviderRegistry,
) -> int:
    emotions = _split_csv(args.emotions)
    if not emotions:
        raise SystemExit("--emotions must include at least one emotion label")
    results = _run_bench(args=args, registry=registry, emotions=emotions)
    return _print_bench(results, json_output=args.json)


def _cmd_eval_approach(
    args: argparse.Namespace,
    registry: ProviderRegistry,
) -> int:
    providers = _split_csv(args.providers)
    if not providers:
        raise SystemExit("--providers must include at least one provider id")
    provider_options = _parse_key_values(args.provider_option)
    summary = run_approach_eval(
        registry=registry,
        providers=providers,
        output_dir=args.output_dir,
        event_dir=args.event_dir,
        provider_options=provider_options,
        visual_mode=_run_visual_mode(args),
        tool_delay_ms=max(0, args.tool_delay_ms),
    )
    if args.json:
        print(json.dumps(summary, indent=2, sort_keys=True))
        return 0
    recommendation = summary["recommendation"]
    print(f"summary: {summary['summary_json']}")
    print(f"runs: {summary['runs_jsonl']}")
    print(f"report: {summary['report_md']}")
    print(f"primary: {recommendation.get('primary')}")
    print(f"approach: {recommendation.get('approach')}")
    print(f"confidence: {recommendation.get('confidence')}")
    print(f"reason: {recommendation.get('reason')}")
    return 0


def _run_bench(
    *,
    args: argparse.Namespace,
    registry: ProviderRegistry,
    emotions: list[str | None],
) -> list[dict[str, Any]]:
    providers = _split_csv(args.providers)
    prompts = _read_script(args.script)
    provider_options = _parse_key_values(args.provider_option)
    output_dir: Path = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    if args.event_dir:
        args.event_dir.mkdir(parents=True, exist_ok=True)

    results: list[dict[str, Any]] = []
    for provider_id in providers:
        for emotion in emotions:
            for index, text in enumerate(prompts, start=1):
                expression = VoiceExpression(
                    emotion=emotion,
                    tone=args.tone,
                    intensity=args.intensity,
                    pace=args.pace,
                    style=args.style,
                    provider_overrides=provider_options,
                )
                stem = _run_stem(index=index, provider_id=provider_id, emotion=emotion)
                response, events = _run_once(
                    registry=registry,
                    provider_id=provider_id,
                    text=text,
                    expression=expression,
                    output_path=output_dir / f"{stem}.wav",
                    provider_options=provider_options,
                    visual_mode=_run_visual_mode(args),
                    label=_run_label(provider_id, expression, index=index),
                )
                if args.event_dir:
                    _write_event_log(args.event_dir / f"{stem}.jsonl", events)
                item = response.to_dict()
                item["text"] = text
                item["expression"] = expression.to_dict()
                results.append(item)
    return results


def _run_once(
    *,
    registry: ProviderRegistry,
    provider_id: str,
    text: str,
    expression: VoiceExpression,
    output_path: Path,
    provider_options: dict[str, Any],
    visual_mode: str = "auto",
    label: str | None = None,
) -> tuple[VoiceResponse, list[dict[str, Any]]]:
    provider = registry.create(provider_id)
    recorder = MetricsRecorder()
    voice_request = VoiceRequest(
        text=text,
        expression=expression,
        output_path=output_path,
        provider_options=provider_options,
    )
    response = run_with_waveform(
        label=label or _run_label(provider_id, expression),
        recorder=recorder,
        visual_mode=visual_mode,
        fn=lambda: provider.run_text(voice_request, recorder),
    )
    return response, recorder.events_as_dicts()


def _run_transcription_once(
    *,
    registry: ProviderRegistry,
    provider_id: str,
    audio_path: Path,
    expected_text: str | None,
    provider_options: dict[str, Any],
    visual_mode: str = "auto",
) -> tuple[VoiceTranscriptionResponse, list[dict[str, Any]]]:
    provider = registry.create(provider_id)
    recorder = MetricsRecorder()
    request = VoiceTranscriptionRequest(
        audio_path=audio_path,
        expected_text=expected_text,
        provider_options=provider_options,
    )
    response = run_with_waveform(
        label=f"{provider_id} stt",
        recorder=recorder,
        visual_mode=visual_mode,
        fn=lambda: provider.run_transcription(request, recorder),
    )
    return response, recorder.events_as_dicts()


def _run_speech_to_speech_once(
    *,
    registry: ProviderRegistry,
    provider_id: str,
    audio_path: Path,
    response_text: str,
    expression: VoiceExpression,
    output_path: Path,
    provider_options: dict[str, Any],
    visual_mode: str = "auto",
    label: str | None = None,
) -> tuple[VoiceResponse, list[dict[str, Any]]]:
    provider = registry.create(provider_id)
    recorder = MetricsRecorder()
    request = VoiceSpeechToSpeechRequest(
        audio_path=audio_path,
        response_text=response_text,
        expression=expression,
        output_path=output_path,
        provider_options=provider_options,
    )
    response = run_with_waveform(
        label=label or _run_label(provider_id, expression, mode="s2s"),
        recorder=recorder,
        visual_mode=visual_mode,
        fn=lambda: provider.run_speech_to_speech(request, recorder),
    )
    return response, recorder.events_as_dicts()


def _expression_from_args(args: argparse.Namespace) -> VoiceExpression:
    return VoiceExpression(
        emotion=getattr(args, "emotion", None),
        tone=getattr(args, "tone", None),
        intensity=args.intensity,
        pace=args.pace,
        style=args.style,
        pronunciation_hints=list(getattr(args, "pronunciation_hint", []) or []),
        interruption_behavior=getattr(args, "interruption_behavior", "allow"),
        persona_instructions=getattr(args, "persona_instructions", None),
        provider_overrides=_parse_key_values(getattr(args, "provider_option", [])),
    )


def _read_text_arg(args: argparse.Namespace, *, default: str | None = None) -> str:
    if args.text and args.text_file:
        raise SystemExit("use --text or --text-file, not both")
    if args.text_file:
        return args.text_file.read_text(encoding="utf-8").strip()
    if args.text:
        return args.text
    if default is not None:
        return default
    raise SystemExit("missing --text or --text-file")


def _read_script(path: Path) -> list[str]:
    lines = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        lines.append(line)
    if not lines:
        raise SystemExit(f"script has no prompts: {path}")
    return lines


def _parse_key_values(values: list[str]) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for value in values:
        if "=" not in value:
            raise SystemExit(f"provider option must be KEY=VALUE: {value!r}")
        key, item = value.split("=", 1)
        key = key.strip()
        if not key:
            raise SystemExit(f"provider option has empty key: {value!r}")
        parsed[key] = item.strip()
    return parsed


def _write_event_log(path: Path, events: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        for event in events:
            fh.write(json.dumps(event, sort_keys=True) + "\n")


def _print_response(response: VoiceResponse, *, json_output: bool) -> None:
    if json_output:
        print(json.dumps(response.to_dict(), indent=2, sort_keys=True))
        return
    metrics = response.metrics
    print(f"provider: {response.provider}")
    print(f"audio: {response.audio_path}")
    print(f"first_audio_ms: {_format_metric(metrics.first_audio_ms)}")
    print(f"response_done_ms: {_format_metric(metrics.response_done_ms)}")


def _print_transcription_response(
    response: VoiceTranscriptionResponse,
    *,
    json_output: bool,
) -> None:
    if json_output:
        print(json.dumps(response.to_dict(), indent=2, sort_keys=True))
        return
    metrics = response.metrics
    print(f"provider: {response.provider}")
    print(f"audio: {response.audio_path}")
    print(f"transcript: {response.transcript}")
    print(f"response_done_ms: {_format_metric(metrics.response_done_ms)}")


def _maybe_play_response(response: VoiceResponse, args: argparse.Namespace) -> None:
    if not getattr(args, "play", False):
        return
    try:
        play_audio_file(response.audio_path)
    except PlaybackError as exc:
        raise ProviderRunError(str(exc)) from exc


def _print_bench(results: list[dict[str, Any]], *, json_output: bool) -> int:
    if json_output:
        print(json.dumps({"runs": results}, indent=2, sort_keys=True))
        return 0
    for item in results:
        metrics = item["metrics"]
        emotion = item["expression"].get("emotion") or "none"
        print(
            f"{item['provider']} emotion={emotion} "
            f"first_audio_ms={metrics['first_audio_ms']} "
            f"done_ms={metrics['response_done_ms']} "
            f"audio={item['audio_path']}"
        )
    return 0


def _split_csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def _default_audio_path(provider_id: str) -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return Path("voice-lab-runs") / f"{stamp}-{_slug(provider_id)}.wav"


def _run_stem(*, index: int, provider_id: str, emotion: str | None) -> str:
    pieces = [f"{index:03d}", _slug(provider_id)]
    if emotion:
        pieces.append(_slug(emotion))
    return "-".join(pieces)


def _slug(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9_.-]+", "-", value.strip()).strip("-")
    return slug or "run"


def _format_metric(value: float | None) -> str:
    if value is None:
        return "n/a"
    return f"{value:.3f}"


def _run_visual_mode(args: argparse.Namespace) -> str:
    if getattr(args, "json", False):
        return "off"
    return getattr(args, "visual", "auto")


def _run_label(
    provider_id: str,
    expression: VoiceExpression,
    *,
    index: int | None = None,
    mode: str | None = None,
) -> str:
    pieces = []
    if index is not None:
        pieces.append(f"{index:03d}")
    pieces.append(provider_id)
    if mode:
        pieces.append(mode)
    if expression.emotion:
        pieces.append(expression.emotion)
    if expression.tone:
        pieces.append(expression.tone)
    return " ".join(pieces)


def _doctor_checks(
    output_dir: Path,
    registry: ProviderRegistry,
) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    provider_ids = registry.provider_ids()
    checks.append(
        {
            "name": "providers",
            "ok": bool(provider_ids),
            "detail": ", ".join(provider_ids) or "none registered",
        }
    )
    websocket_spec = importlib.util.find_spec("websocket")
    checks.append(
        {
            "name": "websocket-client",
            "ok": websocket_spec is not None,
            "detail": (
                "installed"
                if websocket_spec is not None
                else "pip install websocket-client"
            ),
        }
    )
    textual_spec = importlib.util.find_spec("textual")
    checks.append(
        {
            "name": "textual tui",
            "ok": textual_spec is not None,
            "detail": (
                "installed"
                if textual_spec is not None
                else "pip install -e .[voice-lab]"
            ),
        }
    )
    openai_key = _openai_key_source()
    checks.append(
        {
            "name": "openai key",
            "ok": openai_key is not None,
            "detail": openai_key or "set OPENAI_API_KEY or VOICE_TOOLS_OPENAI_KEY",
        }
    )
    elevenlabs_key = _elevenlabs_key_source()
    checks.append(
        {
            "name": "elevenlabs key",
            "ok": elevenlabs_key is not None,
            "detail": elevenlabs_key
            or "set ELEVENLABS_API_KEY or VOICE_TOOLS_ELEVENLABS_KEY",
        }
    )
    xai_key = _xai_key_source()
    checks.append(
        {
            "name": "xai auth",
            "ok": xai_key is not None,
            "detail": xai_key
            or (
                "set XAI_API_KEY/VOICE_TOOLS_XAI_KEY or run "
                "`python -m plugin.voice_lab auth --provider grok`"
            ),
        }
    )
    checks.append(_output_dir_check(output_dir))
    return checks


def _openai_key_source() -> str | None:
    return _env_key_source("OPENAI_API_KEY", "VOICE_TOOLS_OPENAI_KEY")


def _elevenlabs_key_source() -> str | None:
    return _env_key_source("ELEVENLABS_API_KEY", "VOICE_TOOLS_ELEVENLABS_KEY")


def _xai_key_source() -> str | None:
    return _env_key_source(
        "VOICE_TOOLS_XAI_KEY",
        "XAI_API_KEY",
        "GROK_API_KEY",
        "XAI_REALTIME_CLIENT_SECRET",
        "XAI_EPHEMERAL_TOKEN",
        "VOICE_LAB_XAI_OAUTH_ACCESS_TOKEN",
    ) or _voice_lab_xai_oauth_source()


def _env_key_source(*names: str) -> str | None:
    load_voice_lab_env_file()
    wanted = set(names)
    for name in names:
        if os.getenv(name, "").strip():
            return name

    env_path = voice_lab_env_path()
    if not env_path.is_file():
        return None
    try:
        text = env_path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = env_path.read_text(encoding="latin-1")
    except OSError:
        return None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key in wanted and value.strip():
            return f"{key} in {env_path}"
    return None


def _voice_lab_xai_oauth_source() -> str | None:
    token = read_xai_oauth_token(refresh=False)
    if token is None:
        return None
    return f"voice-lab xAI OAuth in {xai_oauth_path()}"


def _output_dir_check(output_dir: Path) -> dict[str, Any]:
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        return {
            "name": "output dir",
            "ok": False,
            "detail": f"{output_dir} ({exc})",
        }
    return {
        "name": "output dir",
        "ok": True,
        "detail": str(output_dir),
    }


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    registry = default_registry()
    handler = getattr(args, "func", None)
    if handler is None:
        parser.error("missing command")
    try:
        return int(handler(args, registry) or 0)
    except KeyError as exc:
        print(f"error: {exc.args[0]}", file=sys.stderr)
        return 2
    except ProviderUnavailable as exc:
        print(f"provider unavailable: {exc}", file=sys.stderr)
        return 3
    except (ProviderRunError, VoiceLabAuthError) as exc:
        print(f"provider failed: {exc}", file=sys.stderr)
        return 4


if __name__ == "__main__":
    raise SystemExit(main())
