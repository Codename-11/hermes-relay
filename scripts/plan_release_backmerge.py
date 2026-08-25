#!/usr/bin/env python3
"""Classify whether a stable release commit needs reconciliation into dev."""

from __future__ import annotations

import argparse
import subprocess
from collections.abc import Callable, Sequence


def classify_release(
    release_commit: str,
    dev_commit: str,
    parents: Sequence[str],
    is_ancestor: Callable[[str, str], bool],
) -> str:
    """Return already-contained, normal-release, or hotfix."""
    if is_ancestor(release_commit, dev_commit):
        return "already-contained"
    if len(parents) < 2:
        raise ValueError("stable release commit is not a release/hotfix merge commit")
    if is_ancestor(parents[1], dev_commit):
        return "normal-release"
    return "hotfix"


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def git_is_ancestor(older: str, newer: str) -> bool:
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", older, newer],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode not in {0, 1}:
        raise RuntimeError(result.stderr.strip() or "git merge-base failed")
    return result.returncode == 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--release-commit", required=True)
    parser.add_argument("--dev-commit", required=True)
    args = parser.parse_args()

    release_commit = git("rev-parse", f"{args.release_commit}^{{commit}}")
    dev_commit = git("rev-parse", f"{args.dev_commit}^{{commit}}")
    parents = git("show", "-s", "--format=%P", release_commit).split()
    print(
        classify_release(
            release_commit,
            dev_commit,
            parents,
            git_is_ancestor,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
