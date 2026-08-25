"""Tests for the Phase 3 extras surface of git_state.

Covers:
- ``commit_message`` / ``commit_message_selected``: staged-diff → LLM → a
  conventional-style message suggestion. The model client is MONKEYPATCHED
  (never a real API call); empty staged diff must skip the LLM entirely; a
  missing/failing model degrades to an empty message + notice, never a 500.
- ``stash_checkout``: dirty tree → auto-stash + switch (stash list entry
  present); clean tree → plain checkout; unknown ref → error.

Fixtures build REAL throwaway git repos in tmp_path. git itself is never
mocked.
"""

from __future__ import annotations

import asyncio
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

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


class _ExtrasBase(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name) / "projects"
        self.base.mkdir(parents=True)
        self.repo = _init_repo(self.base, "extras-repo")

    def _staged_change(self, path: str, content: str) -> None:
        (self.repo / path).write_text(content, encoding="utf-8")
        _git(self.repo, "add", path)

    def _branch(self, name: str) -> None:
        _git(self.repo, "checkout", "-q", "-b", name)
        _git(self.repo, "checkout", "-q", "main")

    def _patch_llm(self, *, text: str = "", exc: Exception | None = None):
        """Inject a fake agent.auxiliary_client chain into git_state.

        ``_llm_call`` is the async client; ``_llm_extract`` turns its return
        into text. The failure path raises ``exc`` from the client.
        """
        client = AsyncMock(side_effect=exc) if exc else AsyncMock(return_value="resp")
        extractor = MagicMock(return_value=text)
        return patch.object(git_state, "_llm_call", client), client, extractor, patch.object(
            git_state, "_llm_extract", extractor
        )


class CommitMessageTests(_ExtrasBase):
    def test_commit_message_returns_suggestion_from_staged_diff(self) -> None:
        self._staged_change("feature.txt", "new feature body\n")
        llm_patch, client, extractor, extract_patch = self._patch_llm(
            text="feat: add new feature\n\nBrings a new feature."
        )
        with llm_patch, extract_patch:
            result = asyncio.run(git_state.commit_message(self.repo))
        # The generated message is the LLM text (subject + optional body) so it
        # can flow into the commit dialog and be edited.
        self.assertEqual(
            {"message": "feat: add new feature\n\nBrings a new feature.", "notice": ""},
            result,
        )
        # The client was called with a messages list containing the staged diff.
        _, kwargs = client.call_args
        self.assertIn("messages", kwargs)
        payload = " ".join(str(m) for m in kwargs["messages"])
        self.assertIn("new feature", payload)
        extractor.assert_called_once_with("resp")

    def test_commit_message_empty_staged_diff_skips_llm(self) -> None:
        # Clean tree: nothing staged → no LLM call.
        p = patch.object(git_state, "_llm_call", AsyncMock())
        with p as client:
            result = asyncio.run(git_state.commit_message(self.repo))
        self.assertEqual({"message": "", "notice": "nothing staged"}, result)
        client.assert_not_called()

    def test_commit_message_skips_llm_when_staged_diff_is_empty(self) -> None:
        # A tracked file modified but NOT staged must never be sent.
        (self.repo / "README.md").write_text("# Hello v2\n", encoding="utf-8")
        p = patch.object(git_state, "_llm_call", AsyncMock())
        with p as client:
            result = asyncio.run(git_state.commit_message(self.repo))
        self.assertEqual({"message": "", "notice": "nothing staged"}, result)
        client.assert_not_called()

    def test_commit_message_degrades_when_model_fails(self) -> None:
        self._staged_change("feature.txt", "boom\n")
        p, _, _, _ = self._patch_llm(exc=RuntimeError("no provider configured"))
        with p:
            result = asyncio.run(git_state.commit_message(self.repo))
        self.assertEqual("", result["message"])
        self.assertIn("model", result["notice"].lower())

    def test_commit_message_degrades_when_model_unavailable(self) -> None:
        self._staged_change("feature.txt", "x\n")
        # Resolver itself fails (e.g. `agent` not installed).
        with patch.object(git_state, "_resolve_llm", side_effect=ImportError("no agent")):
            result = asyncio.run(git_state.commit_message(self.repo))
        self.assertEqual("", result["message"])
        self.assertIn("model", result["notice"].lower())

    def test_commit_message_bounds_staged_diff(self) -> None:
        # A large staged diff is truncated to MAX_DIFF_BYTES and flagged.
        big = "x" * (git_state.MAX_DIFF_BYTES + 10)
        self._staged_change("big.txt", big)
        p, client, extractor, ep = self._patch_llm(text="add big")
        with p, ep:
            result = asyncio.run(git_state.commit_message(self.repo))
        self.assertEqual("add big", result["message"])
        _, kwargs = client.call_args
        payload = "".join(str(m) for m in kwargs["messages"])
        self.assertLessEqual(len(payload), git_state.MAX_DIFF_BYTES + 2048)
        self.assertNotIn("nothing staged", result.get("notice", ""))


class CommitMessageSelectedTests(_ExtrasBase):
    def test_commit_message_selected_honors_only_given_paths(self) -> None:
        self._staged_change("kept.txt", "kept content\n")
        self._staged_change("skip.txt", "skip content\n")
        p, client, extractor, extract_p = self._patch_llm(text="add kept")
        with p, extract_p:
            result = asyncio.run(
                git_state.commit_message_selected(self.repo, ["kept.txt"])
            )
        self.assertEqual("add kept", result["message"])
        _, kwargs = client.call_args
        payload = "".join(str(m) for m in kwargs["messages"])
        self.assertIn("kept content", payload)
        self.assertNotIn("skip content", payload)

    def test_commit_message_selected_skips_llm_when_none_selected_staged(self) -> None:
        self._staged_change("kept.txt", "kept\n")
        # A path with no staged content → nothing staged → no call.
        (self.repo / "other.txt").write_text("other\n", encoding="utf-8")
        _git(self.repo, "add", "other.txt")
        # Stage only kept.txt, ask for other.txt's diff (not staged) → nothing.
        p = patch.object(git_state, "_llm_call", AsyncMock())
        with p as client:
            # Ask for a path that has no STAGED diff: unstage other first.
            _git(self.repo, "restore", "--staged", "other.txt")
            result = asyncio.run(
                git_state.commit_message_selected(self.repo, ["other.txt"])
            )
        self.assertEqual({"message": "", "notice": "nothing staged"}, result)
        client.assert_not_called()

    def test_commit_message_selected_rejects_traversal_path(self) -> None:
        self._staged_change("kept.txt", "kept\n")
        with self.assertRaises(git_state.GitStateError):
            asyncio.run(
                git_state.commit_message_selected(self.repo, ["../escape"])
            )


class StashCheckoutTests(_ExtrasBase):
    def test_dirty_tree_stashes_then_switches(self) -> None:
        self._branch("feature")
        (self.repo / "README.md").write_text("dirty change\n", encoding="utf-8")
        result = git_state.stash_checkout(self.repo, "feature")
        self.assertTrue(result["stashed"])
        self.assertEqual("git-state: feature", result["stash_message"])
        self.assertEqual("feature", _git(self.repo, "symbolic-ref", "--short", "HEAD"))
        stash_list = _git(self.repo, "stash", "list")
        self.assertIn("git-state: feature", stash_list)

    def test_clean_tree_plain_checkout_no_stash(self) -> None:
        self._branch("feature")
        result = git_state.stash_checkout(self.repo, "feature")
        self.assertFalse(result["stashed"])
        self.assertEqual("", result["stash_message"])
        self.assertEqual("feature", _git(self.repo, "symbolic-ref", "--short", "HEAD"))
        self.assertEqual("", _git(self.repo, "stash", "list"))

    def test_clean_tree_new_branch_switch(self) -> None:
        result = git_state.stash_checkout(self.repo, "main", new_branch="exp")
        self.assertFalse(result["stashed"])
        self.assertEqual("exp", _git(self.repo, "symbolic-ref", "--short", "HEAD"))

    def test_stash_returns_fresh_status_and_branches(self) -> None:
        self._branch("feature")
        (self.repo / "README.md").write_text("dirty\n", encoding="utf-8")
        result = git_state.stash_checkout(self.repo, "feature")
        self.assertIn("status", result)
        self.assertIn("branches", result)
        self.assertIn("head", result)

    def test_bad_ref_raises(self) -> None:
        with self.assertRaises(git_state.GitStateError):
            git_state.stash_checkout(self.repo, "no-such-branch")


if __name__ == "__main__":
    unittest.main()
