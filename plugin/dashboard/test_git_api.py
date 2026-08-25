"""Tests for the read-only Git state endpoints (plugin/dashboard/git_api.py).

Uses real throwaway git repos in tmp_path and a FastAPI TestClient, mirroring
the mobile_plugin_api.py test pattern.
"""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from plugin.dashboard import git_api


def _run(cmd: list[str], cwd: Path) -> str:
    return subprocess.run(
        cmd, cwd=cwd, capture_output=True, text=True, check=True
    ).stdout.strip()


def _git(repo: Path, *args: str) -> str:
    return _run(["git", "-C", str(repo), *args], repo)


def _init_repo(root: Path, name: str) -> Path:
    repo = root / name
    repo.mkdir(parents=True)
    _run(["git", "init", "-q", "-b", "main"], repo)
    _run(["git", "config", "user.email", "test@example.com"], repo)
    _run(["git", "config", "user.name", "Test User"], repo)
    (repo / "README.md").write_text("# Hello\n", encoding="utf-8")
    _git(repo, "add", "README.md")
    _git(repo, "commit", "-q", "-m", "initial commit")
    return repo


class GitApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name) / "projects"
        self.base.mkdir(parents=True)
        self.repo = _init_repo(self.base, "alpha")
        self.env = patch.dict(
            os.environ,
            {"HERMES_HOME": self.temp.name, "GIT_STATE_BASE_PATH": str(self.base)},
        )
        self.env.start()
        self.addCleanup(self.env.stop)
        app = FastAPI()
        app.include_router(git_api.router)
        self.client = TestClient(app)

    def test_repos_lists_scanned_repositories(self) -> None:
        response = self.client.get("/git/repos")
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertEqual("alpha", body["repos"][0]["name"])
        self.assertIsNone(body["notice"])

    def test_repos_missing_base_path_returns_empty_with_notice(self) -> None:
        with patch.dict(os.environ, {"GIT_STATE_BASE_PATH": str(self.base / "nope")}):
            response = self.client.get("/git/repos")
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertEqual([], body["repos"])
        self.assertIsNotNone(body["notice"])

    def test_status_returns_grouped_working_tree(self) -> None:
        (self.repo / "new.txt").write_text("untracked", encoding="utf-8")
        response = self.client.get("/git/status", params={"repo": "alpha"})
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertEqual(1, body["counts"]["untracked"])
        self.assertEqual("new.txt", body["untracked"][0]["path"])

    def test_unknown_repo_is_rejected(self) -> None:
        response = self.client.get("/git/status", params={"repo": "bogus"})
        self.assertEqual(400, response.status_code, response.text)

    def test_branches_returns_current_branch(self) -> None:
        response = self.client.get("/git/branches", params={"repo": "alpha"})
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertEqual("main", body["branches"][0]["name"])
        self.assertTrue(body["branches"][0]["is_current"])

    def test_diff_returns_unstaged_diff(self) -> None:
        (self.repo / "README.md").write_text("# Changed\n", encoding="utf-8")
        response = self.client.get(
            "/git/diff",
            params={"repo": "alpha", "path": "README.md", "kind": "unstaged"},
        )
        self.assertEqual(200, response.status_code, response.text)
        self.assertIn("Changed", response.json()["diff"])

    def test_diff_invalid_kind_returns_400(self) -> None:
        response = self.client.get(
            "/git/diff",
            params={"repo": "alpha", "path": "README.md", "kind": "bogus"},
        )
        self.assertEqual(400, response.status_code, response.text)

    def test_diff_path_traversal_rejected(self) -> None:
        response = self.client.get(
            "/git/diff",
            params={"repo": "alpha", "path": "../outside", "kind": "unstaged"},
        )
        self.assertEqual(400, response.status_code, response.text)

    def test_file_reads_tracked_file(self) -> None:
        response = self.client.get("/git/file", params={"repo": "alpha", "path": "README.md"})
        self.assertEqual(200, response.status_code, response.text)
        self.assertIn("Hello", response.json()["content"])

    def test_file_untracked_returns_400(self) -> None:
        (self.repo / "untracked.txt").write_text("x", encoding="utf-8")
        response = self.client.get(
            "/git/file", params={"repo": "alpha", "path": "untracked.txt"}
        )
        self.assertEqual(400, response.status_code, response.text)


if __name__ == "__main__":
    unittest.main()
