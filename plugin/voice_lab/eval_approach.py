"""End-to-end voice approach evaluation for the standalone lab."""

from __future__ import annotations

import json
import math
import statistics
import struct
import time
import wave
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .expressions import VoiceExpression
from .metrics import MetricsRecorder
from .providers.base import (
    ProviderRunError,
    ProviderUnavailable,
    VoiceRequest,
    VoiceResponse,
)
from .registry import ProviderRegistry
from .terminal_ui import run_with_waveform


@dataclass(frozen=True, slots=True)
class ApproachScenario:
    id: str
    label: str
    text: str
    objective: str
    expected_text: str | None = None
    expression: VoiceExpression = field(default_factory=VoiceExpression)
    simulated_tool_delay_ms: int = 0
    provider_options: dict[str, str] = field(default_factory=dict)


def run_approach_eval(
    *,
    registry: ProviderRegistry,
    providers: list[str],
    output_dir: Path,
    event_dir: Path | None = None,
    provider_options: dict[str, Any] | None = None,
    visual_mode: str = "auto",
    tool_delay_ms: int = 750,
) -> dict[str, Any]:
    """Run a fixed prompt matrix and write comparable E2E artifacts."""

    output_dir.mkdir(parents=True, exist_ok=True)
    event_dir = event_dir or output_dir / "events"
    event_dir.mkdir(parents=True, exist_ok=True)
    provider_options = dict(provider_options or {})
    scenarios = default_scenarios(tool_delay_ms=tool_delay_ms)
    created_at = datetime.now(timezone.utc).isoformat()
    runs: list[dict[str, Any]] = []

    for provider_id in providers:
        provider_skip_reason: str | None = None
        for index, scenario in enumerate(scenarios, start=1):
            stem = f"{index:02d}-{_slug(provider_id)}-{_slug(scenario.id)}"
            audio_path = output_dir / f"{stem}.wav"
            event_path = event_dir / f"{stem}.jsonl"
            try:
                info = registry.info(provider_id).to_dict()
            except KeyError:
                info = {"id": provider_id, "supports_realtime": False}
            render_text = _scenario_text_for_provider(scenario, info)
            if provider_skip_reason:
                _write_event_log(
                    event_path,
                    [_synthetic_event("provider_skipped", reason=provider_skip_reason)],
                )
                item = _skipped_run(
                    provider_id=provider_id,
                    scenario=scenario,
                    reason=provider_skip_reason,
                    event_path=event_path,
                    audio_path=audio_path,
                )
                runs.append(item)
                continue

            if scenario.simulated_tool_delay_ms > 0:
                time.sleep(scenario.simulated_tool_delay_ms / 1000.0)

            recorder = MetricsRecorder()
            options = dict(provider_options)
            options.update(scenario.provider_options)
            started = time.perf_counter()
            events: list[dict[str, Any]] = []
            try:
                provider = registry.create(provider_id)
                response = _run_scenario(
                    provider=provider,
                    request=VoiceRequest(
                        text=render_text,
                        expression=scenario.expression,
                        output_path=audio_path,
                        provider_options=options,
                    ),
                    recorder=recorder,
                    visual_mode=visual_mode,
                    label=f"{provider_id} {scenario.id}",
                )
                events = recorder.events_as_dicts()
                _write_event_log(event_path, events)
                elapsed_ms = (time.perf_counter() - started) * 1000.0
                runs.append(
                    _completed_run(
                        provider_id=provider_id,
                        scenario=scenario,
                        render_text=render_text,
                        scripted_text_control=_uses_scripted_text_control(info),
                        response=response,
                        events=events,
                        elapsed_ms=elapsed_ms,
                        event_path=event_path,
                    )
                )
            except ProviderUnavailable as exc:
                provider_skip_reason = str(exc)
                events = recorder.events_as_dicts()
                if not events:
                    events = [
                        _synthetic_event(
                            "provider_skipped",
                            reason=provider_skip_reason,
                        )
                    ]
                _write_event_log(event_path, events)
                item = _skipped_run(
                    provider_id=provider_id,
                    scenario=scenario,
                    reason=provider_skip_reason,
                    event_path=event_path,
                    audio_path=audio_path,
                )
                runs.append(item)
            except ProviderRunError as exc:
                events = recorder.events_as_dicts()
                if not events:
                    events = [_synthetic_event("provider_failed", error=str(exc))]
                _write_event_log(event_path, events)
                runs.append(
                    _failed_run(
                        provider_id=provider_id,
                        scenario=scenario,
                        error=str(exc),
                        event_path=event_path,
                        audio_path=audio_path,
                    )
                )

    summary = {
        "created_at": created_at,
        "output_dir": str(output_dir),
        "event_dir": str(event_dir),
        "providers_requested": providers,
        "scenario_count": len(scenarios),
        "run_count": len(runs),
        "runs_jsonl": str(output_dir / "runs.jsonl"),
        "summary_json": str(output_dir / "summary.json"),
        "report_md": str(output_dir / "report.md"),
        "scenarios": [scenario_to_dict(item) for item in scenarios],
        "runs": runs,
        "providers": _summarize_providers(registry, providers, runs),
    }
    summary["recommendation"] = _recommend(summary["providers"])
    _write_jsonl(output_dir / "runs.jsonl", runs)
    (output_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True),
        encoding="utf-8",
        newline="\n",
    )
    (output_dir / "report.md").write_text(
        _render_report(summary),
        encoding="utf-8",
        newline="\n",
    )
    return summary


def default_scenarios(*, tool_delay_ms: int) -> list[ApproachScenario]:
    """Fixed voice tasks that target the user's actual decision criteria."""

    return [
        ApproachScenario(
            id="ack",
            label="Tool Acknowledgement",
            objective="Measure time to first audible acknowledgement.",
            text="Say exactly: Let me check that.",
            expected_text="Let me check that.",
            expression=VoiceExpression(tone="calm", intensity=0.35, pace="quick"),
        ),
        ApproachScenario(
            id="tool_final",
            label="Post-Tool Final Answer",
            objective=(
                "Simulate a small tool wait, then measure how quickly a final "
                "answer can be spoken."
            ),
            text=(
                "Say exactly: I checked Hermes Relay. The relay is connected "
                "and ready to continue."
            ),
            expected_text=(
                "I checked Hermes Relay. The relay is connected and ready to continue."
            ),
            expression=VoiceExpression(tone="clear", intensity=0.45),
            simulated_tool_delay_ms=tool_delay_ms,
            provider_options={"tool_scaffold": "true"},
        ),
        ApproachScenario(
            id="pronunciation",
            label="Pronunciation",
            objective="Exercise provider handling of project and API terminology.",
            text=(
                "Read these terms clearly: Hermes Relay, Grok, OpenAI Realtime, "
                "ElevenLabs, Obsidian, MCP, WebSocket, QR pairing, API key, "
                "SuperGrok, gpt-realtime-2."
            ),
            expression=VoiceExpression(
                tone="precise",
                intensity=0.4,
                pronunciation_hints=[
                    "Hermes is pronounced her-meez.",
                    "MCP should be spoken as M C P.",
                    "gpt-realtime-2 should be spoken as G P T realtime two.",
                ],
            ),
        ),
        ApproachScenario(
            id="volume",
            label="Volume Consistency",
            objective="Measure rough loudness stability and clipping risk.",
            text=(
                "Use the same calm voice level for this whole sentence, without "
                "getting louder or quieter near the end."
            ),
            expression=VoiceExpression(tone="steady", intensity=0.4),
        ),
    ]


def scenario_to_dict(scenario: ApproachScenario) -> dict[str, Any]:
    return {
        "id": scenario.id,
        "label": scenario.label,
        "objective": scenario.objective,
        "text": scenario.text,
        "expected_text": scenario.expected_text,
        "expression": scenario.expression.to_dict(),
        "simulated_tool_delay_ms": scenario.simulated_tool_delay_ms,
        "provider_options": dict(scenario.provider_options),
    }


def _scenario_text_for_provider(
    scenario: ApproachScenario,
    info: dict[str, Any],
) -> str:
    if _uses_scripted_text_control(info) and scenario.expected_text:
        return scenario.expected_text
    return scenario.text


def _uses_scripted_text_control(info: dict[str, Any]) -> bool:
    return bool(info.get("supports_tts")) and not bool(info.get("supports_realtime"))


def _run_scenario(
    *,
    provider: Any,
    request: VoiceRequest,
    recorder: MetricsRecorder,
    visual_mode: str,
    label: str,
) -> VoiceResponse:
    return run_with_waveform(
        label=label,
        recorder=recorder,
        visual_mode=visual_mode,
        fn=lambda: provider.run_text(request, recorder),
    )


def _completed_run(
    *,
    provider_id: str,
    scenario: ApproachScenario,
    render_text: str,
    scripted_text_control: bool,
    response: VoiceResponse,
    events: list[dict[str, Any]],
    elapsed_ms: float,
    event_path: Path,
) -> dict[str, Any]:
    metrics = response.metrics.to_dict()
    audio = analyze_wav(response.audio_path)
    transcript = response.metadata.get("transcript")
    text_adherence = _text_adherence(
        expected=scenario.expected_text,
        transcript=str(transcript) if transcript else None,
        rendered_text=render_text,
        scripted_text_control=scripted_text_control,
    )
    item = {
        "provider": provider_id,
        "scenario": scenario.id,
        "label": scenario.label,
        "status": "completed",
        "objective": scenario.objective,
        "render_text": render_text,
        "audio_path": str(response.audio_path),
        "event_log": str(event_path),
        "elapsed_ms": round(elapsed_ms, 3),
        "simulated_tool_delay_ms": scenario.simulated_tool_delay_ms,
        "total_user_wait_ms": _sum_optional(
            scenario.simulated_tool_delay_ms,
            metrics.get("first_audio_ms"),
        ),
        "model": response.model,
        "voice": response.voice,
        "metrics": metrics,
        "audio_metrics": audio,
        "event_metrics": _event_metrics(events),
        "text_adherence": text_adherence,
        "metadata": dict(response.metadata),
    }
    return item


def _skipped_run(
    *,
    provider_id: str,
    scenario: ApproachScenario,
    reason: str,
    event_path: Path | None,
    audio_path: Path,
) -> dict[str, Any]:
    return {
        "provider": provider_id,
        "scenario": scenario.id,
        "label": scenario.label,
        "status": "skipped",
        "objective": scenario.objective,
        "reason": reason,
        "audio_path": str(audio_path),
        "event_log": str(event_path) if event_path else None,
        "simulated_tool_delay_ms": scenario.simulated_tool_delay_ms,
    }


def _failed_run(
    *,
    provider_id: str,
    scenario: ApproachScenario,
    error: str,
    event_path: Path | None,
    audio_path: Path,
) -> dict[str, Any]:
    return {
        "provider": provider_id,
        "scenario": scenario.id,
        "label": scenario.label,
        "status": "failed",
        "objective": scenario.objective,
        "error": error,
        "audio_path": str(audio_path),
        "event_log": str(event_path) if event_path else None,
        "simulated_tool_delay_ms": scenario.simulated_tool_delay_ms,
    }


def analyze_wav(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {"exists": False}
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        sample_width = wav.getsampwidth()
        sample_rate = wav.getframerate()
        frames = wav.getnframes()
        payload = wav.readframes(frames)
    duration = frames / float(sample_rate) if sample_rate else 0.0
    base = {
        "exists": True,
        "channels": channels,
        "sample_width": sample_width,
        "sample_rate": sample_rate,
        "frames": frames,
        "duration_seconds": round(duration, 3),
        "byte_count": len(payload),
    }
    if sample_width != 2 or len(payload) < 2:
        base.update(
            {
                "peak_level": 0.0,
                "rms_mean": 0.0,
                "rms_cv": None,
                "clipped_ratio": 0.0,
                "silence_ratio": None,
            }
        )
        return base

    usable = len(payload) - (len(payload) % 2)
    samples = struct.unpack(f"<{usable // 2}h", payload[:usable])
    if not samples:
        return base
    max_sample = 32767.0
    peak = max(abs(item) for item in samples) / max_sample
    rms = math.sqrt(sum(float(item) * float(item) for item in samples) / len(samples))
    clipped = sum(1 for item in samples if abs(item) >= 32760)
    window_frames = max(1, sample_rate // 20)
    window_samples = max(1, window_frames * max(channels, 1))
    window_rms: list[float] = []
    for start in range(0, len(samples), window_samples):
        chunk = samples[start : start + window_samples]
        if not chunk:
            continue
        chunk_rms = math.sqrt(
            sum(float(item) * float(item) for item in chunk) / len(chunk)
        ) / max_sample
        window_rms.append(chunk_rms)
    voiced = [item for item in window_rms if item >= 0.005]
    rms_cv = None
    if len(voiced) >= 2:
        mean = statistics.fmean(voiced)
        if mean > 0:
            rms_cv = statistics.pstdev(voiced) / mean
    silence_ratio = None
    if window_rms:
        silence_ratio = sum(1 for item in window_rms if item < 0.005) / len(window_rms)
    base.update(
        {
            "peak_level": round(min(1.0, peak), 4),
            "rms_mean": round(min(1.0, rms / max_sample), 4),
            "rms_cv": _round_optional(rms_cv),
            "clipped_ratio": round(clipped / len(samples), 6),
            "silence_ratio": _round_optional(silence_ratio),
        }
    )
    return base


def _event_metrics(events: list[dict[str, Any]]) -> dict[str, Any]:
    audio_chunks = [item for item in events if item.get("type") == "audio_chunk"]
    server_events = [
        str(item.get("data", {}).get("server_event_type"))
        for item in events
        if item.get("type") == "server_event"
        and item.get("data", {}).get("server_event_type") is not None
    ]
    return {
        "event_count": len(events),
        "audio_chunk_count": len(audio_chunks),
        "audio_event_bytes": sum(
            int(item.get("data", {}).get("byte_count") or 0)
            for item in audio_chunks
        ),
        "server_event_types": sorted(set(server_events)),
    }


def _summarize_providers(
    registry: ProviderRegistry,
    providers: list[str],
    runs: list[dict[str, Any]],
) -> dict[str, Any]:
    summaries: dict[str, Any] = {}
    for provider_id in providers:
        provider_runs = [item for item in runs if item["provider"] == provider_id]
        completed = [item for item in provider_runs if item["status"] == "completed"]
        failed = [item for item in provider_runs if item["status"] == "failed"]
        skipped = [item for item in provider_runs if item["status"] == "skipped"]
        try:
            info = registry.info(provider_id).to_dict()
        except KeyError:
            info = {"id": provider_id, "name": provider_id}
        status = "completed" if completed else "failed" if failed else "skipped"
        summary = {
            "status": status,
            "info": info,
            "runs": len(provider_runs),
            "completed": len(completed),
            "failed": len(failed),
            "skipped": len(skipped),
            "notes": _provider_notes(info, completed, skipped, failed),
        }
        if completed:
            first_audio_values = _metric_values(completed, "first_audio_ms")
            done_values = _metric_values(completed, "response_done_ms")
            gap_values = _metric_values(completed, "audio_chunk_gap_ms")
            rms_cv_values = _audio_values(completed, "rms_cv")
            silence_values = _audio_values(completed, "silence_ratio")
            clipped_values = _audio_values(completed, "clipped_ratio")
            adherence_values = _adherence_values(completed, "score")
            exact_values = _adherence_values(completed, "exact_match")
            contains_values = _adherence_values(completed, "contains_expected")
            ack_run = next(
                (item for item in completed if item["scenario"] == "ack"),
                completed[0],
            )
            summary.update(
                {
                    "first_audio_ms_avg": _avg(first_audio_values),
                    "response_done_ms_avg": _avg(done_values),
                    "audio_chunk_gap_ms_max": _max(gap_values),
                    "ack_first_audio_ms": ack_run["metrics"].get("first_audio_ms"),
                    "ack_total_user_wait_ms": ack_run.get("total_user_wait_ms"),
                    "rms_cv_avg": _avg(rms_cv_values),
                    "silence_ratio_avg": _avg(silence_values),
                    "clipped_ratio_max": _max(clipped_values),
                    "text_adherence_score_avg": _avg(adherence_values),
                    "text_adherence_exact_rate": _avg(exact_values),
                    "text_adherence_contains_rate": _avg(contains_values),
                }
            )
            summary["scores"] = _score_provider(info, summary)
            summary["overall_score"] = summary["scores"]["overall"]
        if not completed:
            reason = None
            if skipped:
                reason = skipped[0].get("reason")
            elif failed:
                reason = failed[0].get("error")
            summary["reason"] = reason
        summaries[provider_id] = summary
    return summaries


def _provider_notes(
    info: dict[str, Any],
    completed: list[dict[str, Any]],
    skipped: list[dict[str, Any]],
    failed: list[dict[str, Any]],
) -> list[str]:
    notes = []
    if info.get("supports_realtime") and info.get("supports_tool_use"):
        notes.append("native realtime voice path with tool-use support")
    elif info.get("supports_realtime"):
        notes.append("native realtime voice path")
    elif info.get("supports_tts"):
        notes.append("cascaded TTS path; pair with STT plus an LLM for conversation")
    if skipped:
        notes.append(f"skipped: {skipped[0].get('reason')}")
    if failed:
        notes.append(f"failed: {failed[0].get('error')}")
    if completed and info.get("id") == "stub":
        notes.append("local control provider; exclude from product recommendation")
    return notes


def _score_provider(info: dict[str, Any], summary: dict[str, Any]) -> dict[str, float]:
    latency = (
        _score_lower(summary.get("ack_first_audio_ms"), good=250.0, bad=2500.0) * 0.55
        + _score_lower(summary.get("response_done_ms_avg"), good=1000.0, bad=9000.0)
        * 0.45
    )
    continuity = (
        _score_lower(summary.get("audio_chunk_gap_ms_max"), good=120.0, bad=1200.0)
        * 0.65
        + _score_lower(summary.get("silence_ratio_avg"), good=0.1, bad=0.65) * 0.35
    )
    volume = (
        _score_lower(summary.get("rms_cv_avg"), good=0.18, bad=1.0) * 0.75
        + _score_lower(summary.get("clipped_ratio_max"), good=0.0, bad=0.02) * 0.25
    )
    control = summary.get("text_adherence_score_avg")
    if control is None:
        control = 50.0
    capability = 35.0
    if info.get("supports_realtime"):
        capability = 75.0
    if info.get("supports_realtime") and info.get("supports_tool_use"):
        capability = 100.0
    if info.get("id") == "stub":
        capability = 10.0
    overall = (
        latency * 0.34
        + continuity * 0.2
        + volume * 0.16
        + float(control) * 0.1
        + capability * 0.2
    )
    return {
        "latency": round(latency, 2),
        "continuity": round(continuity, 2),
        "volume": round(volume, 2),
        "instruction_control": round(float(control), 2),
        "capability": round(capability, 2),
        "overall": round(overall, 2),
    }


def _recommend(provider_summaries: dict[str, Any]) -> dict[str, Any]:
    completed = {
        provider_id: summary
        for provider_id, summary in provider_summaries.items()
        if summary.get("completed") and provider_id != "stub"
    }
    skipped_or_failed = {
        provider_id: summary
        for provider_id, summary in provider_summaries.items()
        if not summary.get("completed") and provider_id != "stub"
    }
    if not completed:
        return {
            "primary": None,
            "approach": None,
            "confidence": "low",
            "reason": (
                "No live non-stub provider completed. The harness is working, "
                "but provider credentials or API errors blocked comparison."
            ),
            "missing_or_failed": skipped_or_failed,
        }

    winner_id, winner = max(
        completed.items(),
        key=lambda item: float(item[1].get("overall_score") or 0.0),
    )
    info = winner.get("info", {})
    if info.get("supports_realtime") and info.get("supports_tool_use"):
        approach = "native realtime voice agent"
    elif info.get("supports_realtime"):
        approach = "native realtime voice, add explicit tool scaffolding next"
    else:
        approach = "cascaded STT plus LLM plus streaming TTS"
    confidence = "medium" if len(completed) == 1 else "medium-high"
    reason = (
        f"{winner_id} had the strongest completed score in this run "
        f"({winner.get('overall_score')}/100) with ack first audio around "
        f"{winner.get('ack_first_audio_ms')} ms."
    )
    if len(completed) == 1 and skipped_or_failed:
        reason += (
            " This is an incomplete provider comparison because other live "
            "providers did not complete."
        )
    return {
        "primary": winner_id,
        "approach": approach,
        "confidence": confidence,
        "reason": reason,
        "missing_or_failed": skipped_or_failed,
    }


def _render_report(summary: dict[str, Any]) -> str:
    lines = [
        "# Hermes Voice Lab E2E Approach Eval",
        "",
        f"Created: {summary['created_at']}",
        f"Output: `{summary['output_dir']}`",
        "",
        "## Provider Summary",
        "",
        "| Provider | Status | Score | Ack First Audio | Avg Done | Max Gap | RMS CV | Notes |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for provider_id, item in summary["providers"].items():
        lines.append(
            "| "
            + " | ".join(
                [
                    provider_id,
                    str(item.get("status")),
                    _table_value(item.get("overall_score")),
                    _table_value(item.get("ack_first_audio_ms")),
                    _table_value(item.get("response_done_ms_avg")),
                    _table_value(item.get("audio_chunk_gap_ms_max")),
                    (
                        f"{_table_value(item.get('rms_cv_avg'))}; "
                        f"ctrl={_table_value(item.get('text_adherence_score_avg'))}"
                    ),
                    "; ".join(item.get("notes") or []),
                ]
            )
            + " |"
        )
    recommendation = summary["recommendation"]
    lines.extend(
        [
            "",
            "## Recommendation",
            "",
            f"Primary: `{recommendation.get('primary')}`",
            f"Approach: {recommendation.get('approach')}",
            f"Confidence: {recommendation.get('confidence')}",
            "",
            str(recommendation.get("reason")),
            "",
            "## Scenario Runs",
            "",
            "| Provider | Scenario | Status | Audio | Event Log |",
            "| --- | --- | --- | --- | --- |",
        ]
    )
    for run in summary["runs"]:
        lines.append(
            "| "
            + " | ".join(
                [
                    str(run.get("provider")),
                    str(run.get("scenario")),
                    str(run.get("status")),
                    f"`{run.get('audio_path')}`",
                    f"`{run.get('event_log')}`",
                ]
            )
            + " |"
        )
    return "\n".join(lines) + "\n"


def _write_event_log(path: Path, events: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        for event in events:
            fh.write(json.dumps(event, sort_keys=True) + "\n")


def _synthetic_event(event_type: str, **data: Any) -> dict[str, Any]:
    return {"type": event_type, "at_ms": 0.0, "data": data}


def _write_jsonl(path: Path, runs: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        for item in runs:
            fh.write(json.dumps(item, sort_keys=True) + "\n")


def _metric_values(runs: list[dict[str, Any]], key: str) -> list[float]:
    return [
        float(value)
        for item in runs
        if (value := item.get("metrics", {}).get(key)) is not None
    ]


def _audio_values(runs: list[dict[str, Any]], key: str) -> list[float]:
    return [
        float(value)
        for item in runs
        if (value := item.get("audio_metrics", {}).get(key)) is not None
    ]


def _adherence_values(runs: list[dict[str, Any]], key: str) -> list[float]:
    values: list[float] = []
    for item in runs:
        adherence = item.get("text_adherence") or {}
        value = adherence.get(key)
        if value is None:
            continue
        if isinstance(value, bool):
            values.append(1.0 if value else 0.0)
        else:
            values.append(float(value))
    return values


def _text_adherence(
    *,
    expected: str | None,
    transcript: str | None,
    rendered_text: str | None,
    scripted_text_control: bool,
) -> dict[str, Any] | None:
    if not expected:
        return None
    source = "transcript"
    comparison_text = transcript
    if not comparison_text and scripted_text_control:
        comparison_text = rendered_text
        source = "scripted_text"
    if not comparison_text:
        return None
    expected_norm = _normalize_text(expected)
    comparison_norm = _normalize_text(comparison_text)
    exact = expected_norm == comparison_norm
    contains = expected_norm in comparison_norm
    score = 100.0 if exact else 60.0 if contains else 0.0
    return {
        "expected_text": expected,
        "transcript": transcript,
        "source": source,
        "exact_match": exact,
        "contains_expected": contains,
        "score": score,
    }


def _normalize_text(value: str) -> str:
    return " ".join(
        "".join(char.lower() if char.isalnum() else " " for char in value).split()
    )


def _avg(values: list[float]) -> float | None:
    if not values:
        return None
    return round(statistics.fmean(values), 3)


def _max(values: list[float]) -> float | None:
    if not values:
        return None
    return round(max(values), 3)


def _round_optional(value: float | None) -> float | None:
    if value is None:
        return None
    return round(value, 4)


def _sum_optional(base: int | float, value: Any) -> float | None:
    if value is None:
        return None
    return round(float(base) + float(value), 3)


def _score_lower(value: Any, *, good: float, bad: float) -> float:
    if value is None:
        return 50.0
    numeric = float(value)
    if numeric <= good:
        return 100.0
    if numeric >= bad:
        return 0.0
    return max(0.0, min(100.0, 100.0 * (bad - numeric) / (bad - good)))


def _table_value(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value)


def _slug(value: str) -> str:
    return "".join(char if char.isalnum() or char in {"-", "_"} else "-" for char in value).strip("-") or "run"
