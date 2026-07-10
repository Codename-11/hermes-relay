"""Delivery-outcome rollup over realtime-agent flight-recorder logs.

Answers "what's the delivery compliance rate?" in one command instead of
hand-grepping per-session JSONL files after every live round:

    python -m plugin.relay.realtime_agent.report [--run-dir PATH] [--days N] [--json]

Outcome taxonomy (one delivery may produce exactly one of the first four):

- ``provider_spoken_early_commit`` — the provider's spoken delivery passed
  prefix validation and streamed live (``forced_summary_streaming``).
- ``provider_spoken_end_validated`` — the buffered response passed
  end-of-response validation and was flushed (``forced_summary_delivered``).
- ``validator_fallback`` — the validator flagged the response and relay TTS
  spoke the authoritative answer (``forced_summary_fallback``; reasons
  tallied).
- ``provider_death_fallback`` — the response request itself failed and
  relay TTS spoke the answer (``delivery_fallback``; reasons tallied).

Plus per-session context counters: preempted deliveries (user talked over
an in-flight delivery; answer landed as text), unconfirmed alarms (forced
text emit), deferred results (phone detached), and background runs.
"""

from __future__ import annotations

import argparse
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

_EVENT_COUNTERS = {
    "voice.response.forced_summary_streaming": "provider_spoken_early_commit",
    "voice.response.forced_summary_delivered": "provider_spoken_end_validated",
    "voice.realtime_agent.delivery_preempted": "preempted",
    "voice.realtime_agent.delivery_unconfirmed": "alarm_text_emit",
    "voice.realtime_agent.result_deferred": "deferred_detached",
    "voice.realtime_agent.summary_send_failed": "summary_send_failed",
}

_REASON_COUNTERS = {
    "voice.response.forced_summary_fallback": "validator_fallback",
    "voice.response.delivery_fallback": "provider_death_fallback",
}


@dataclass
class SessionReport:
    path: str
    provider: str = ""
    model: str = ""
    background_runs: int = 0
    counts: dict[str, int] = field(default_factory=dict)
    fallback_reasons: dict[str, int] = field(default_factory=dict)

    def bump(self, key: str) -> None:
        self.counts[key] = self.counts.get(key, 0) + 1

    def bump_reason(self, key: str, reason: str) -> None:
        self.bump(key)
        label = f"{key}:{reason or 'unknown'}"
        self.fallback_reasons[label] = self.fallback_reasons.get(label, 0) + 1

    @property
    def provider_spoken(self) -> int:
        return self.counts.get("provider_spoken_early_commit", 0) + self.counts.get(
            "provider_spoken_end_validated", 0
        )

    @property
    def fallbacks(self) -> int:
        return self.counts.get("validator_fallback", 0) + self.counts.get(
            "provider_death_fallback", 0
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "path": self.path,
            "provider": self.provider,
            "model": self.model,
            "background_runs": self.background_runs,
            "provider_spoken": self.provider_spoken,
            "fallbacks": self.fallbacks,
            "counts": dict(self.counts),
            "fallback_reasons": dict(self.fallback_reasons),
        }


def scan_session_log(path: Path) -> SessionReport:
    report = SessionReport(path=path.name)
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return report
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            event = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            continue
        if not isinstance(event, dict):
            continue
        event_type = str(event.get("type") or "")
        if event_type == "voice.session.ready":
            report.provider = str(event.get("provider") or report.provider)
            report.model = str(event.get("model") or report.model)
        elif event_type == "voice.realtime_agent.provider_model_resolved":
            # The provider's echo of which model ACTUALLY served — more
            # truthful than the requested alias, so it wins.
            report.model = str(event.get("resolved_model") or report.model)
        elif event_type == "hermes.run.background_completed":
            report.background_runs += 1
        elif event_type in _EVENT_COUNTERS:
            report.bump(_EVENT_COUNTERS[event_type])
        elif event_type in _REASON_COUNTERS:
            report.bump_reason(
                _REASON_COUNTERS[event_type], str(event.get("reason") or "")
            )
    return report


def scan_run_dir(run_dir: Path, *, days: float = 0) -> list[SessionReport]:
    cutoff = time.time() - days * 86400 if days > 0 else None
    reports: list[SessionReport] = []
    for path in sorted(run_dir.glob("realtime-agent-*.jsonl")):
        try:
            if cutoff is not None and path.stat().st_mtime < cutoff:
                continue
        except OSError:
            continue
        reports.append(scan_session_log(path))
    return reports


def summarize(reports: list[SessionReport]) -> dict[str, Any]:
    totals: dict[str, int] = {}
    reasons: dict[str, int] = {}
    background_runs = 0
    for report in reports:
        background_runs += report.background_runs
        for key, value in report.counts.items():
            totals[key] = totals.get(key, 0) + value
        for key, value in report.fallback_reasons.items():
            reasons[key] = reasons.get(key, 0) + value
    provider_spoken = totals.get("provider_spoken_early_commit", 0) + totals.get(
        "provider_spoken_end_validated", 0
    )
    fallbacks = totals.get("validator_fallback", 0) + totals.get(
        "provider_death_fallback", 0
    )
    delivered = provider_spoken + fallbacks
    return {
        "sessions": len(reports),
        "background_runs": background_runs,
        "deliveries": delivered,
        "provider_spoken": provider_spoken,
        "fallbacks": fallbacks,
        "provider_spoken_rate": (
            round(provider_spoken / delivered, 3) if delivered else None
        ),
        "counts": totals,
        "fallback_reasons": reasons,
    }


def _default_run_dir() -> Path:
    import os

    configured = os.getenv("RELAY_REALTIME_VOICE_RUN_DIR")
    if configured:
        return Path(configured).expanduser()
    home = Path(os.getenv("HERMES_RELAY_HOME", str(Path.home() / ".hermes-relay")))
    return home / "realtime-agent-runs"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Summarize realtime-agent delivery outcomes from flight logs."
    )
    parser.add_argument("--run-dir", type=Path, default=None)
    parser.add_argument(
        "--days", type=float, default=0, help="Only sessions newer than N days (0 = all)"
    )
    parser.add_argument("--json", action="store_true", dest="as_json")
    args = parser.parse_args(argv)

    run_dir = args.run_dir or _default_run_dir()
    if not run_dir.is_dir():
        print(f"run dir not found: {run_dir}")
        return 1
    reports = scan_run_dir(run_dir, days=args.days)
    summary = summarize(reports)

    if args.as_json:
        print(
            json.dumps(
                {
                    "summary": summary,
                    "sessions": [r.to_dict() for r in reports if r.background_runs or r.counts],
                },
                indent=2,
                sort_keys=True,
            )
        )
        return 0

    print(f"run dir: {run_dir}")
    print(
        f"sessions: {summary['sessions']}  background runs: {summary['background_runs']}  "
        f"deliveries: {summary['deliveries']}"
    )
    rate = summary["provider_spoken_rate"]
    rate_text = f"{rate * 100:.0f}%" if rate is not None else "n/a"
    print(
        f"provider-voiced: {summary['provider_spoken']}/{summary['deliveries']} ({rate_text})  "
        f"fallbacks: {summary['fallbacks']}"
    )
    for key in (
        "provider_spoken_early_commit",
        "provider_spoken_end_validated",
        "validator_fallback",
        "provider_death_fallback",
        "preempted",
        "alarm_text_emit",
        "deferred_detached",
        "summary_send_failed",
    ):
        value = summary["counts"].get(key, 0)
        if value:
            print(f"  {key}: {value}")
    if summary["fallback_reasons"]:
        print("fallback reasons:")
        for label, value in sorted(summary["fallback_reasons"].items()):
            print(f"  {label}: {value}")
    interesting = [r for r in reports if r.fallbacks or r.counts.get("preempted")]
    if interesting:
        print("sessions with fallbacks/preemptions:")
        for report in interesting:
            print(
                f"  {report.path}  model={report.model or '?'}  "
                f"spoken={report.provider_spoken} fallbacks={report.fallbacks} "
                f"reasons={report.fallback_reasons or {}}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
