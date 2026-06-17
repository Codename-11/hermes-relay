#!/usr/bin/env python3
"""Backward-compatible wrapper for scripts/check-plugin-version-sync.py."""

from __future__ import annotations

import pathlib
import runpy
import sys


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
TARGET = REPO_ROOT / "scripts" / "check-plugin-version-sync.py"


if __name__ == "__main__":
    print(
        "NOTE: scripts/check-server-version-sync.py is deprecated; "
        "use scripts/check-plugin-version-sync.py.",
        file=sys.stderr,
    )
    runpy.run_path(str(TARGET), run_name="__main__")
