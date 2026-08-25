"""Tests for the write (POST) Git State endpoints in plugin/dashboard/git_api.py.

Write endpoints are POST and rely on the plugin's ``plugin.api.write`` grant
which the app enforces client-side (see PluginsViewModel.invokeAction — the
precedent gate). Server-side, destructive operations are additionally enforced
by a required confirmation string; missing/wrong confirmation maps to 403 and
dirty/conflict trees map to 409, so the UI can render a readable message.
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
from plugin import git_state


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


def _init_bare_remote(root: Path, name: str) -> Path:
    remote = root / name
    remote.mkdir(parents=True, exist_ok=True)
    _run(["git", "init", "-q", "--bare", "-b", "main"], remote)
    return remote


class GitWriteApiTests(unittest.TestCase):
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

    def _stage(self, path: str) -> None:
        (self.repo / path).write_text("x", encoding="utf-8")
        self.client.post("/git/stage", json={"repo": "alpha", "paths": [path]})

    def test_stage_returns_fresh_status(self) -> None:
        (self.repo / "new.txt").write_text("x", encoding="utf-8")
        response = self.client.post(
            "/git/stage", json={"repo": "alpha", "paths": ["new.txt"]}
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertEqual(1, body["status"]["counts"]["staged"])

    def test_unknown_repo_rejected(self) -> None:
        response = self.client.post(
            "/git/stage", json={"repo": "bogus", "paths": ["x"]}
        )
        self.assertEqual(400, response.status_code, response.text)

    def test_commit_creates_commit(self) -> None:
        self._stage("feature.txt")
        before = _git(self.repo, "rev-parse", "HEAD")
        response = self.client.post(
            "/git/commit", json={"repo": "alpha", "message": "add feature"}
        )
        self.assertEqual(200, response.status_code, response.text)
        after = _git(self.repo, "rev-parse", "HEAD")
        self.assertNotEqual(before, after)

    def test_commit_empty_message_rejected(self) -> None:
        self._stage("feature.txt")
        response = self.client.post(
            "/git/commit", json={"repo": "alpha", "message": "   "}
        )
        self.assertEqual(400, response.status_code, response.text)

    def test_discard_without_confirmation_is_403(self) -> None:
        (self.repo / "tracked.txt").write_text("v1", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        _git(self.repo, "commit", "-q", "-m", "add tracked")
        (self.repo / "tracked.txt").write_text("v2", encoding="utf-8")
        response = self.client.post(
            "/git/discard", json={"repo": "alpha", "paths": ["tracked.txt"]}
        )
        self.assertEqual(403, response.status_code, response.text)

    def test_discard_wrong_confirmation_is_403(self) -> None:
        (self.repo / "tracked.txt").write_text("v2", encoding="utf-8")
        response = self.client.post(
            "/git/discard",
            json={
                "repo": "alpha",
                "paths": ["tracked.txt"],
                "confirmation": "wrong",
            },
        )
        self.assertEqual(403, response.status_code, response.text)

    def test_discard_with_confirmation_succeeds(self) -> None:
        (self.repo / "tracked.txt").write_text("v1", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        _git(self.repo, "commit", "-q", "-m", "add tracked")
        (self.repo / "tracked.txt").write_text("v2", encoding="utf-8")
        response = self.client.post(
            "/git/discard",
            json={
                "repo": "alpha",
                "paths": ["tracked.txt"],
                "confirmation": git_state.CONFIRM_DISCARD,
            },
        )
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual("v1", (self.repo / "tracked.txt").read_text(encoding="utf-8"))

    def test_push_requires_confirmation(self) -> None:
        remote = _init_bare_remote(self.base, "origin-bare")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        self._stage("feature.txt")
        self.client.post("/git/commit", json={"repo": "alpha", "message": "f"})
        response = self.client.post(
            "/git/push", json={"repo": "alpha", "remote": "origin", "branch": "main"}
        )
        self.assertEqual(403, response.status_code, response.text)

    def test_push_with_confirmation_succeeds(self) -> None:
        remote = _init_bare_remote(self.base, "remote-bare")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        self._stage("feature.txt")
        self.client.post("/git/commit", json={"repo": "alpha", "message": "f"})
        response = self.client.post(
            "/git/push",
            json={
                "repo": "alpha",
                "remote": "origin",
                "branch": "main",
                "confirmation": git_state.CONFIRM_PUSH,
            },
        )
        self.assertEqual(200, response.status_code, response.text)

    def test_checkout_dirty_requires_confirmation(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        _git(self.repo, "checkout", "-q", "main")
        (self.repo / "tracked.txt").write_text("dirty", encoding="utf-8")
        response = self.client.post(
            "/git/checkout", json={"repo": "alpha", "ref": "feature"}
        )
        # Missing confirmation on a dirty-tree switch is the destructive gate.
        self.assertEqual(403, response.status_code, response.text)
        # Confirming proceeds (git still refuses to overwrite conflicting work).
        response = self.client.post(
            "/git/checkout",
            json={
                "repo": "alpha",
                "ref": "feature",
                "confirmation": git_state.CONFIRM_DIRTY_CHECKOUT,
            },
        )
        self.assertEqual(200, response.status_code, response.text)

    def test_pull_dirty_returns_409_never_clobbers(self) -> None:
        remote = _init_bare_remote(self.base, "remote-bare2")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        # Advance the remote from a descendant clone.
        other = self.base / "other"
        _run(["git", "clone", "-q", str(remote), str(other)], self.base)
        _git(other, "config", "user.email", "t@e.c")
        _git(other, "config", "user.name", "T")
        (other / "tracked.txt").write_text("remote", encoding="utf-8")
        _git(other, "add", "tracked.txt")
        _git(other, "commit", "-q", "-m", "remote")
        _git(other, "push", "-q", "origin", "main")
        (self.repo / "tracked.txt").write_text("local-uncommitted", encoding="utf-8")
        response = self.client.post(
            "/git/pull", json={"repo": "alpha", "remote": "origin", "branch": "main"}
        )
        self.assertEqual(409, response.status_code, response.text)
        self.assertEqual("local-uncommitted", (self.repo / "tracked.txt").read_text(encoding="utf-8"))

    def test_fetch_returns_branches(self) -> None:
        remote = _init_bare_remote(self.base, "remote-bare3")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        response = self.client.post(
            "/git/fetch", json={"repo": "alpha", "remote": "origin"}
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("branches", body)

    def test_structured_error_taxonomy_is_readable(self) -> None:
        # Unknown repo → 400 with a readable detail, never a stack trace.
        response = self.client.post(
            "/git/stage", json={"repo": "missing", "paths": ["x"]}
        )
        self.assertEqual(400, response.status_code, response.text)
        self.assertNotIn("Traceback", response.text)
        self.assertNotIn("subprocess", response.text.lower())


    def test_push_wrong_confirmation_is_403(self) -> None:
        remote = _init_bare_remote(self.base, "push-wrong-bare")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        self._stage("feature.txt")
        self.client.post("/git/commit", json={"repo": "alpha", "message": "f"})
        response = self.client.post(
            "/git/push",
            json={
                "repo": "alpha",
                "remote": "origin",
                "branch": "main",
                "confirmation": "nope",
            },
        )
        self.assertEqual(403, response.status_code, response.text)

    def test_push_with_confirmation_updates_remote_and_returns_branches(self) -> None:
        remote = _init_bare_remote(self.base, "push-ok-bare")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        _git(self.repo, "branch", "-q", "--set-upstream-to=origin/main", "main")
        self._stage("feature.txt")
        r = self.client.post("/git/commit", json={"repo": "alpha", "message": "f"})
        self.assertEqual(200, r.status_code, r.text)
        head = r.json()["head"]
        response = self.client.post(
            "/git/push",
            json={
                "repo": "alpha",
                "remote": "origin",
                "branch": "main",
                "confirmation": git_state.CONFIRM_PUSH,
            },
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("branches", body)
        self.assertIn("status", body)
        remote_head = _run(
            ["git", "ls-remote", str(remote), "refs/heads/main"], self.base
        )
        self.assertIn(head, remote_head)

    def test_checkout_dirty_tree_wrong_confirmation_is_403(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        _git(self.repo, "checkout", "-q", "main")
        (self.repo / "tracked.txt").write_text("dirty", encoding="utf-8")
        response = self.client.post(
            "/git/checkout",
            json={"repo": "alpha", "ref": "feature", "confirmation": "wrong"},
        )
        self.assertEqual(403, response.status_code, response.text)

    def test_checkout_clean_tree_works_without_confirmation(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        _git(self.repo, "checkout", "-q", "main")
        response = self.client.post(
            "/git/checkout", json={"repo": "alpha", "ref": "feature"}
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("branches", body)
        self.assertIn("status", body)
        self.assertEqual(
            "feature", _git(self.repo, "symbolic-ref", "--short", "HEAD")
        )

    def test_checkout_new_branch_with_track_sets_upstream(self) -> None:
        remote = _init_bare_remote(self.base, "newbranch-bare")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        _git(self.repo, "branch", "-q", "--set-upstream-to=origin/main", "main")
        response = self.client.post(
            "/git/checkout",
            json={
                "repo": "alpha",
                "ref": "main",
                "new_branch": "exp",
                "track": True,
            },
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("branches", body)
        self.assertIn("status", body)
        self.assertEqual("exp", _git(self.repo, "symbolic-ref", "--short", "HEAD"))
        # Upstream is set — rev-parse resolves to a commit, not an error.
        self.assertTrue(_git(self.repo, "rev-parse", "exp@{upstream}"))

    def test_fetch_updates_remote_tracking_ref(self) -> None:
        remote = _init_bare_remote(self.base, "fetch-adv-bare")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        other = self.base / "other"
        _run(["git", "clone", "-q", str(remote), str(other)], self.base)
        _git(other, "config", "user.email", "t@e.c")
        _git(other, "config", "user.name", "T")
        (other / "remote.txt").write_text("rc fetch", encoding="utf-8")
        _git(other, "add", "remote.txt")
        _git(other, "commit", "-q", "-m", "rc fetch")
        _git(other, "push", "-q", "origin", "main")
        response = self.client.post(
            "/git/fetch", json={"repo": "alpha", "remote": "origin"}
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("branches", body)
        self.assertIn("status", body)
        remote_main = _git(self.repo, "rev-parse", "origin/main")
        self.assertNotEqual(remote_main, _git(self.repo, "rev-parse", "HEAD"))

    def test_pull_returns_200_with_remote_commit(self) -> None:
        remote = _init_bare_remote(self.base, "pull-ok-bare")
        _git(self.repo, "remote", "add", "origin", str(remote))
        _git(self.repo, "push", "-q", "origin", "main")
        _git(self.repo, "branch", "-q", "--set-upstream-to=origin/main", "main")
        other = self.base / "other"
        _run(["git", "clone", "-q", str(remote), str(other)], self.base)
        _git(other, "config", "user.email", "t@e.c")
        _git(other, "config", "user.name", "T")
        (other / "remote.txt").write_text("rc pull", encoding="utf-8")
        _git(other, "add", "remote.txt")
        _git(other, "commit", "-q", "-m", "rc pull")
        _git(other, "push", "-q", "origin", "main")
        response = self.client.post(
            "/git/pull",
            json={"repo": "alpha", "remote": "origin", "branch": "main"},
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("status", body)
        head_files = _git(self.repo, "ls-tree", "-r", "--name-only", "HEAD")
        self.assertIn("remote.txt", head_files)

    def test_commit_selected_commits_only_given_paths(self) -> None:
        (self.repo / "kept.txt").write_text("keep", encoding="utf-8")
        (self.repo / "skip.txt").write_text("skip", encoding="utf-8")
        _git(self.repo, "add", "kept.txt", "skip.txt")
        response = self.client.post(
            "/git/commit_selected",
            json={"repo": "alpha", "message": "commit kept", "paths": ["kept.txt"]},
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("head", body)
        self.assertIn("status", body)
        head_files = _git(self.repo, "ls-tree", "-r", "--name-only", "HEAD")
        self.assertIn("kept.txt", head_files)
        self.assertNotIn("skip.txt", head_files)

    def test_commit_response_includes_head_and_fresh_status(self) -> None:
        self._stage("feature.txt")
        response = self.client.post(
            "/git/commit", json={"repo": "alpha", "message": "add feature"}
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("head", body)
        self.assertEqual(body["head"], _git(self.repo, "rev-parse", "HEAD"))
        self.assertIn("status", body)
        self.assertEqual(0, body["status"]["counts"]["staged"])
        self.assertEqual(0, body["status"]["counts"]["modified"])

    def test_unstage_returns_fresh_status(self) -> None:
        (self.repo / "tracked.txt").write_text("v1", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        _git(self.repo, "commit", "-q", "-m", "add tracked")
        (self.repo / "tracked.txt").write_text("v2", encoding="utf-8")
        r = self.client.post(
            "/git/stage", json={"repo": "alpha", "paths": ["tracked.txt"]}
        )
        self.assertEqual(200, r.status_code, r.text)
        response = self.client.post(
            "/git/unstage", json={"repo": "alpha", "paths": ["tracked.txt"]}
        )
        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertIn("status", body)
        status = body["status"]
        self.assertNotIn(
            "tracked.txt", [e["path"] for e in status["staged"]]
        )
        self.assertIn(
            "tracked.txt", [e["path"] for e in status["modified"]]
        )

    def test_stage_missing_repo_in_body_is_400(self) -> None:
        response = self.client.post("/git/stage", json={"paths": ["x"]})
        self.assertEqual(400, response.status_code, response.text)

    def test_stage_traversal_path_is_400(self) -> None:
        response = self.client.post(
            "/git/stage", json={"repo": "alpha", "paths": ["../escape"]}
        )
        self.assertEqual(400, response.status_code, response.text)

    def test_stage_too_many_paths_is_400(self) -> None:
        paths = [f"f{i}.txt" for i in range(201)]
        response = self.client.post(
            "/git/stage", json={"repo": "alpha", "paths": paths}
        )
        self.assertEqual(400, response.status_code, response.text)


if __name__ == "__main__":
    unittest.main()
