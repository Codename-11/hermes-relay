#!/usr/bin/env python3
"""Run the bounded upstream half of the HRUI-070/071/081/161 matrix.

The harness deliberately executes upstream's own fixtures instead of copying
its registries, cron stores, or session database logic into Hermes-Relay.
Run it from an exact detached hermes-agent checkout; live delivery and physical
client checks remain a separate certification step.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


MINIMUM_COMMITS = {
    "HRUI-070": "bb597e1c02",
    "HRUI-071": "a8ec50f68",
    "HRUI-081": "48e12a06f",
    "HRUI-161": "8be9c76f8",
}

MATRIX: dict[str, tuple[tuple[tuple[str, ...], str | None], ...]] = {
    "HRUI-070": (
        (("tests/cron/test_scheduler_provider.py",), "multiplex_ticker_ticks_each_profile_once"),
        (("tests/cron/test_run_one_job.py",), "installs_secret_scope_under_multiplex"),
        (("tests/cron/test_scheduler.py",), "parallel_jobs_isolated_contextvars or live_adapter_timeout_assumes_delivered_no_duplicate"),
        (("tests/cron/test_relay_fronted_delivery.py",), None),
    ),
    "HRUI-071": (
        (("tests/tui_gateway/test_session_resume_db_ownership.py",), None),
        (("tests/tui_gateway/test_session_db_ownership_teardown.py",), None),
    ),
    "HRUI-081": (
        (("tests/tools/test_tool_search.py",), "empty_search_keeps_connected_sources_discoverable or search_catalog_is_scoped_to_session_toolsets or default_listing_cap_bounds_fixed_catalog_overhead"),
    ),
    "HRUI-161": (
        (("tests/hermes_cli/test_plugins.py",), "plugin_manager_scoped_by_hermes_home_override or force_reload_re_registers_shell_hooks or unload_all_sweeps_preledger_tool_names"),
        (("tests/hermes_cli/test_plugin_prompt_sections.py",), None),
    ),
}


def _run(repo: Path, *args: str, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=repo,
        check=False,
        text=True,
        capture_output=capture,
    )


def _git_text(repo: Path, *args: str) -> str:
    result = _run(repo, "git", *args, capture=True)
    if result.returncode:
        raise SystemExit(result.stderr.strip() or "git command failed")
    return result.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--hermes-source", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument(
        "--lane",
        action="append",
        choices=tuple(MATRIX),
        help="Run one lane (repeatable); defaults to all four",
    )
    args = parser.parse_args()

    repo = args.hermes_source.resolve()
    expected = _git_text(repo, "rev-parse", f"{args.revision}^{{commit}}")
    head = _git_text(repo, "rev-parse", "HEAD")
    if head != expected:
        raise SystemExit(
            f"refusing source drift: checkout HEAD is {head}, expected {expected}"
        )
    if _run(repo, "git", "symbolic-ref", "-q", "HEAD").returncode == 0:
        raise SystemExit("refusing mutable branch checkout: detach HEAD at the revision")
    status = _git_text(repo, "status", "--porcelain", "--untracked-files=all")
    if status:
        raise SystemExit("refusing dirty source: tracked or untracked files would affect pytest")

    lanes = args.lane or list(MATRIX)
    for lane in lanes:
        minimum = MINIMUM_COMMITS[lane]
        ancestry = _run(repo, "git", "merge-base", "--is-ancestor", minimum, "HEAD")
        if ancestry.returncode:
            raise SystemExit(f"{lane}: HEAD does not contain required {minimum}")

    failures = 0
    for lane in lanes:
        print(f"\n== {lane} ==", flush=True)
        for paths, expression in MATRIX[lane]:
            command = [
                sys.executable,
                "-m",
                "pytest",
                "-q",
                "-p",
                "no:cacheprovider",
                *paths,
            ]
            if expression:
                command.extend(("-k", expression))
            result = _run(repo, *command)
            failures += int(result.returncode != 0)

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
