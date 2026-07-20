#!/usr/bin/env python3
"""Run the non-mutating upstream compatibility certification gate.

This script deliberately stops at source, ancestry, and fixture checks. It does
not connect to a gateway, create sessions, call providers, or restart services.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Sequence


REQUIRED_BASELINES = (
    "73057ed16",  # session-scoped auxiliary/model routing
    "ef9e0c98f",  # complete runtime tuple for compression/routing
    "7d27a31ce",  # flag-gated compute-host turn isolation
    "54d0948d3",  # compression-aware background-completion ownership
)

TEST_GROUPS: tuple[tuple[str, tuple[str, ...]], ...] = (
    (
        "routing_and_compression",
        (
            "tests/agent/test_auxiliary_runtime_cache_key.py",
            "tests/agent/test_turn_context.py",
            "tests/gateway/test_image_input_routing_runtime.py",
            "tests/tui_gateway/test_image_routing_stale_model.py",
            "tests/run_agent/test_compression_feasibility.py",
        ),
    ),
    (
        "isolation_queue_and_lineage",
        (
            "tests/test_tui_gateway_server.py",
            "-k",
            "turn_isolation or compression_lineage or queued_prompt",
        ),
    ),
    (
        "delegation_ownership",
        (
            "tests/cli/test_cli_async_delegation_delivery.py",
        ),
    ),
)


def run(
    command: Sequence[str],
    *,
    cwd: Path,
    capture: bool = False,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(command),
        cwd=cwd,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def git_output(upstream: Path, *args: str) -> str:
    return run(("git", *args), cwd=upstream, capture=True).stdout.strip()


def resolve_python(upstream: Path, requested: Path | None) -> Path:
    if requested is not None:
        # Do not resolve the final symlink: virtualenv launchers commonly point
        # at a base interpreter and rely on their original path for sys.prefix.
        candidate = requested.expanduser().absolute()
    else:
        relative = "Scripts/python.exe" if sys.platform == "win32" else "bin/python"
        candidate = upstream / ".venv" / relative
    if not candidate.is_file():
        raise SystemExit(
            f"upstream test interpreter not found: {candidate}; pass --python explicitly"
        )
    return candidate


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--upstream",
        type=Path,
        required=True,
        help="path to a hermes-agent Git checkout",
    )
    parser.add_argument(
        "--python",
        type=Path,
        help="upstream Python interpreter (default: <upstream>/.venv)",
    )
    parser.add_argument(
        "--run-tests",
        action="store_true",
        help="run the safe pytest fixture groups after preflight",
    )
    parser.add_argument(
        "--evidence-json",
        type=Path,
        help="write a sanitized machine-readable result",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    upstream = args.upstream.expanduser().resolve()
    if not (upstream / ".git").exists():
        raise SystemExit(f"not a Git checkout: {upstream}")

    upstream_sha = git_output(upstream, "rev-parse", "HEAD")
    dirty = bool(git_output(upstream, "status", "--porcelain"))
    if dirty:
        raise SystemExit("upstream checkout is dirty; certify an exact clean revision")

    missing_baselines: list[str] = []
    for baseline in REQUIRED_BASELINES:
        result = subprocess.run(
            ("git", "merge-base", "--is-ancestor", baseline, "HEAD"),
            cwd=upstream,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode != 0:
            missing_baselines.append(baseline)
    if missing_baselines:
        raise SystemExit("missing required upstream baseline(s): " + ", ".join(missing_baselines))

    test_paths = {
        item
        for _, arguments in TEST_GROUPS
        for item in arguments
        if item.startswith("tests/")
    }
    missing_tests = sorted(path for path in test_paths if not (upstream / path).is_file())
    if missing_tests:
        raise SystemExit("missing upstream fixture(s): " + ", ".join(missing_tests))

    groups: dict[str, str] = {name: "not_run" for name, _ in TEST_GROUPS}
    if args.run_tests:
        python = resolve_python(upstream, args.python)
        for name, arguments in TEST_GROUPS:
            print(f"== {name} ==", flush=True)
            run((str(python), "-m", "pytest", "-q", *arguments), cwd=upstream)
            groups[name] = "passed"

    evidence = {
        "schema": 1,
        "scope": "static_upstream_compatibility",
        "upstream_sha": upstream_sha,
        "upstream_clean": True,
        "required_baselines": {baseline: "present" for baseline in REQUIRED_BASELINES},
        "test_groups": groups,
        "live_gates": {
            "concurrent_model_image_routing": "not_run",
            "turn_isolation_off_on": "not_run",
            "background_completion_restart": "not_run",
            "android_device_reconnect": "not_run",
        },
    }
    rendered = json.dumps(evidence, indent=2, sort_keys=True) + "\n"
    if args.evidence_json:
        args.evidence_json.expanduser().resolve().write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
