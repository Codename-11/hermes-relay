#!/usr/bin/env python3
"""Provider-free upstream Desktop/gateway baseline contract.

This is a deliberately small HRUI-055 gate. It does not launch Electron, call a
provider, or prove live auth. It verifies that the vanilla upstream checkout
still exposes the gateway/event fields Relay Desktop's typed stream/rendering
path consumes before deeper desktop E2E work runs.

Usage:
    python scripts/check-desktop-upstream-baseline.py <upstream_repo_root>
    UPSTREAM_DIR=/path/to/hermes-agent python scripts/check-desktop-upstream-baseline.py
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

REQUIRED_FILES = (
    "tui_gateway/server.py",
    "ui-tui/src/gatewayTypes.ts",
)

REQUIRED_MARKERS = {
    "tui_gateway/server.py": (
        "message.interim",
        "interim_assistant_callback",
        "response_previewed",
    ),
    "ui-tui/src/gatewayTypes.ts": (
        "message.interim",
        "already_streamed",
        "response_previewed",
    ),
}

FORK_MARKERS = (
    "hermes_relay",
    "hermes-relay",
    "RelayPlugin",
)


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("UPSTREAM_DIR", ".")).resolve()
    problems: list[str] = []

    for relative in REQUIRED_FILES:
        path = root / relative
        if not path.is_file():
            problems.append(f"MISSING SOURCE FILE: {relative}")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        fork_hits = [marker for marker in FORK_MARKERS if marker in text]
        if fork_hits:
            problems.append(f"FORK MARKERS in {relative}: {fork_hits}")
        for marker in REQUIRED_MARKERS.get(relative, ()):
            if marker not in text:
                problems.append(f"MISSING MARKER in {relative}: {marker}")

    print(f"upstream root : {root}")
    print("desktop gateway baseline markers:")
    for relative, markers in REQUIRED_MARKERS.items():
        for marker in markers:
            status = "ok"
            path = root / relative
            if not path.is_file() or marker not in path.read_text(encoding="utf-8", errors="replace"):
                status = "MISSING"
            print(f"  [{status}] {relative} :: {marker}")

    if problems:
        print()
        print("FAIL: upstream Desktop/gateway baseline is missing required Relay Desktop contracts:")
        for problem in problems:
            print(f"  x {problem}")
        return 1

    print()
    print("PASS: provider-free Desktop/gateway baseline markers are present on vanilla upstream.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
