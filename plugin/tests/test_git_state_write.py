"""Tests for the write (mutation) surface of git_state.

Security denials are tested FIRST: destructive mutations (discard, push, dirty
checkout) reject when their confirmation string is missing or wrong. Then happy
paths, then error branches. Fixtures build REAL throwaway git repos and bare
remotes in tmp_path — git itself is never mocked.
"""

from __future__ import annotations

import os
import subprocess
import unittest
from pathlib import Path
from threading import Event, Thread
from unittest.mock import patch

from plugin import git_state


def _run(cmd: list[str], cwd: Path) -> str:
    result = subprocess.run(
        cmd,
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


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


def _link_directory(link: Path, target: Path) -> None:
    try:
        link.symlink_to(target, target_is_directory=True)
    except OSError:
        if os.name != "nt":
            raise
        _run(["cmd", "/c", "mklink", "/J", str(link), str(target)], link.parent)


class _MutationBase(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name) / "projects"
        self.base.mkdir(parents=True)
        self.repo = _init_repo(self.base, "write-repo")

    def _make_change(self, path: str = "feature.txt", content: str = "hello") -> None:
        (self.repo / path).write_text(content, encoding="utf-8")
        _git(self.repo, "add", path)

    def _head(self) -> str:
        return _git(self.repo, "rev-parse", "HEAD")


class StageUnstageTests(_MutationBase):
    def test_stage_moves_untracked_to_staged(self) -> None:
        (self.repo / "new.txt").write_text("x", encoding="utf-8")
        git_state.stage(self.repo, ["new.txt"])
        status = git_state.repo_status(self.repo)
        self.assertIn("new.txt", [e["path"] for e in status["staged"]])
        self.assertNotIn("new.txt", [e["path"] for e in status["untracked"]])

    def test_stage_accepts_multiple_paths(self) -> None:
        (self.repo / "a.txt").write_text("a", encoding="utf-8")
        (self.repo / "b.txt").write_text("b", encoding="utf-8")
        git_state.stage(self.repo, ["a.txt", "b.txt"])
        status = git_state.repo_status(self.repo)
        staged = {e["path"] for e in status["staged"]}
        self.assertTrue({"a.txt", "b.txt"} <= staged)

    def test_unstage_returns_to_modified(self) -> None:
        (self.repo / "tracked.txt").write_text("v1", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        _git(self.repo, "commit", "-q", "-m", "add tracked")
        (self.repo / "tracked.txt").write_text("v2", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        git_state.unstage(self.repo, ["tracked.txt"])
        status = git_state.repo_status(self.repo)
        self.assertNotIn("tracked.txt", [e["path"] for e in status["staged"]])
        self.assertIn("tracked.txt", [e["path"] for e in status["modified"]])

    def test_stage_rejects_traversal_path(self) -> None:
        with self.assertRaises(git_state.GitStateError):
            git_state.stage(self.repo, ["../outside"])

    def test_stage_treats_wildcard_as_literal_path(self) -> None:
        (self.repo / "first.txt").write_text("first", encoding="utf-8")
        (self.repo / "second.txt").write_text("second", encoding="utf-8")

        with self.assertRaises(git_state.GitError):
            git_state.stage(self.repo, ["*.txt"])

        status = git_state.repo_status(self.repo)
        self.assertEqual([], status["staged"])
        self.assertEqual(
            {"first.txt", "second.txt"},
            {entry["path"] for entry in status["untracked"]},
        )

    def test_stage_treats_pathspec_magic_as_literal_path(self) -> None:
        literal = "name[1].txt"
        expanded = "name1.txt"
        (self.repo / literal).write_text("literal", encoding="utf-8")
        (self.repo / expanded).write_text("expanded", encoding="utf-8")

        git_state.stage(self.repo, [literal])

        status = git_state.repo_status(self.repo)
        self.assertEqual({literal}, {entry["path"] for entry in status["staged"]})
        self.assertEqual({expanded}, {entry["path"] for entry in status["untracked"]})

    def test_unstage_treats_wildcard_as_literal_path(self) -> None:
        for name in ("first.txt", "second.txt"):
            (self.repo / name).write_text(name, encoding="utf-8")
            _git(self.repo, "add", name)

        with self.assertRaises(git_state.GitError):
            git_state.unstage(self.repo, ["*.txt"])

        status = git_state.repo_status(self.repo)
        self.assertEqual(
            {"first.txt", "second.txt"},
            {entry["path"] for entry in status["staged"]},
        )


class CommitTests(_MutationBase):
    def test_commit_creates_a_real_commit(self) -> None:
        before = self._head()
        (self.repo / "feature.txt").write_text("feature", encoding="utf-8")
        git_state.stage(self.repo, ["feature.txt"])
        git_state.commit(self.repo, "add feature")
        after = self._head()
        self.assertNotEqual(before, after)
        message = _git(self.repo, "log", "-1", "--format=%s")
        self.assertEqual("add feature", message)

    def test_commit_rejects_empty_message(self) -> None:
        (self.repo / "feature.txt").write_text("feature", encoding="utf-8")
        git_state.stage(self.repo, ["feature.txt"])
        with self.assertRaises(git_state.GitStateError):
            git_state.commit(self.repo, "   ")

    def test_commit_returns_fresh_status(self) -> None:
        (self.repo / "feature.txt").write_text("feature", encoding="utf-8")
        git_state.stage(self.repo, ["feature.txt"])
        result = git_state.commit(self.repo, "add feature")
        self.assertEqual(result["head"], self._head())
        self.assertEqual(result["status"]["counts"]["staged"], 0)
        self.assertEqual(result["status"]["counts"]["modified"], 0)

    def test_commit_selected_commits_only_given_paths(self) -> None:
        (self.repo / "kept.txt").write_text("keep", encoding="utf-8")
        (self.repo / "skip.txt").write_text("skip", encoding="utf-8")
        _git(self.repo, "add", "kept.txt", "skip.txt")
        git_state.commit_selected(self.repo, "commit kept only", ["kept.txt"])
        head_files = _git(self.repo, "ls-tree", "-r", "--name-only", "HEAD")
        self.assertIn("kept.txt", head_files)
        self.assertNotIn("skip.txt", head_files)

    def test_commit_selected_returns_fresh_status(self) -> None:
        (self.repo / "a.txt").write_text("a", encoding="utf-8")
        git_state.stage(self.repo, ["a.txt"])
        result = git_state.commit_selected(self.repo, "commit a", ["a.txt"])
        self.assertEqual(result["head"], self._head())
        self.assertEqual(result["status"]["counts"]["staged"], 0)

    def test_commit_selected_rejects_empty_message(self) -> None:
        (self.repo / "a.txt").write_text("a", encoding="utf-8")
        git_state.stage(self.repo, ["a.txt"])
        with self.assertRaises(git_state.GitError):
            git_state.commit_selected(self.repo, "", ["a.txt"])

    def test_commit_selected_treats_wildcard_as_literal_path(self) -> None:
        for name in ("first.txt", "second.txt"):
            (self.repo / name).write_text(name, encoding="utf-8")
            _git(self.repo, "add", name)

        with self.assertRaises(git_state.GitError):
            git_state.commit_selected(self.repo, "must stay scoped", ["*.txt"])

        self.assertNotIn("first.txt", _git(self.repo, "ls-tree", "-r", "--name-only", "HEAD"))
        self.assertNotIn("second.txt", _git(self.repo, "ls-tree", "-r", "--name-only", "HEAD"))


class DiscardConfirmationTests(_MutationBase):
    def test_discard_requires_confirmation_string(self) -> None:
        (self.repo / "tracked.txt").write_text("dirty", encoding="utf-8")
        with self.assertRaises(git_state.GitError) as ctx:
            git_state.discard(self.repo, ["tracked.txt"], confirmation="")
        self.assertIn("confirmation", str(ctx.exception).lower())

    def test_discard_rejects_wrong_confirmation(self) -> None:
        (self.repo / "tracked.txt").write_text("dirty", encoding="utf-8")
        with self.assertRaises(git_state.GitError):
            git_state.discard(self.repo, ["tracked.txt"], confirmation="wrong")

    def test_discard_with_confirmation_reverts_tracked_changes(self) -> None:
        (self.repo / "tracked.txt").write_text("v1", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        _git(self.repo, "commit", "-q", "-m", "add tracked")
        (self.repo / "tracked.txt").write_text("v2", encoding="utf-8")
        git_state.discard(self.repo, ["tracked.txt"], confirmation=git_state.CONFIRM_DISCARD)
        content = (self.repo / "tracked.txt").read_text(encoding="utf-8")
        self.assertEqual("v1", content)

    def test_discard_delete_untracked_removes_untracked(self) -> None:
        (self.repo / "untracked.txt").write_text("new", encoding="utf-8")
        git_state.discard(
            self.repo,
            ["untracked.txt"],
            confirmation=git_state.CONFIRM_DISCARD,
            delete_untracked=True,
        )
        self.assertFalse((self.repo / "untracked.txt").exists())

    def test_discard_delete_untracked_rejects_link_outside_repo(self) -> None:
        outside = self.base / "outside.txt"
        outside.write_text("keep", encoding="utf-8")
        link = self.repo / "untracked-link.txt"
        try:
            link.symlink_to(outside)
        except OSError as exc:
            self.skipTest(f"symlink creation unavailable: {exc}")

        with self.assertRaisesRegex(git_state.GitStateError, "link or junction"):
            git_state.discard(
                self.repo,
                ["untracked-link.txt"],
                confirmation=git_state.CONFIRM_DISCARD,
                delete_untracked=True,
            )

        self.assertEqual("keep", outside.read_text(encoding="utf-8"))
        self.assertTrue(link.is_symlink())

    @unittest.skipUnless(os.name == "nt", "Windows junction fallback")
    def test_discard_rejects_junction_on_python_311_fallback(self) -> None:
        target = self.repo / "target"
        target.mkdir()
        (target / "keep.txt").write_text("keep", encoding="utf-8")
        junction = self.repo / "junction"
        _run(["cmd", "/c", "mklink", "/J", str(junction), str(target)], self.repo)

        with patch.object(Path, "is_junction", return_value=False, create=True):
            with self.assertRaisesRegex(git_state.GitStateError, "link or junction"):
                git_state.discard(
                    self.repo,
                    ["junction/keep.txt"],
                    confirmation=git_state.CONFIRM_DISCARD,
                    delete_untracked=True,
                )

        self.assertEqual("keep", (target / "keep.txt").read_text(encoding="utf-8"))

    def test_discard_delete_untracked_treats_wildcard_as_literal_path(self) -> None:
        for name in ("first.txt", "second.txt"):
            (self.repo / name).write_text(name, encoding="utf-8")

        git_state.discard(
            self.repo,
            ["*.txt"],
            confirmation=git_state.CONFIRM_DISCARD,
            delete_untracked=True,
        )

        self.assertTrue((self.repo / "first.txt").exists())
        self.assertTrue((self.repo / "second.txt").exists())

    def test_discard_does_not_delete_when_tracking_check_fails(self) -> None:
        target = self.repo / "keep.txt"
        target.write_text("keep", encoding="utf-8")

        with patch.object(
            git_state,
            "_run_git_bounded",
            return_value=(128, "", "fatal: repository unavailable"),
        ):
            with self.assertRaisesRegex(git_state.GitStateError, "ls-files failed"):
                git_state.discard(
                    self.repo,
                    ["keep.txt"],
                    confirmation=git_state.CONFIRM_DISCARD,
                    delete_untracked=True,
                )

        self.assertEqual("keep", target.read_text(encoding="utf-8"))

    def test_discard_delete_untracked_does_not_follow_swapped_parent(self) -> None:
        nested = self.repo / "nested"
        nested.mkdir()
        (nested / "delete.txt").write_text("repo", encoding="utf-8")
        parked = self.repo / "nested-original"
        outside = self.base / "outside"
        outside.mkdir()
        outside_file = outside / "delete.txt"
        outside_file.write_text("keep", encoding="utf-8")
        original_validate = git_state._validate_untracked_delete_path

        def swap_before_delete(repo: Path, path: str) -> str:
            if nested.exists() and not nested.is_symlink():
                nested.rename(parked)
                _link_directory(nested, outside)
            return original_validate(repo, path)

        with patch.object(
            git_state,
            "_validate_untracked_delete_path",
            side_effect=swap_before_delete,
        ):
            with self.assertRaisesRegex(git_state.GitStateError, "link or junction"):
                git_state.discard(
                    self.repo,
                    ["nested/delete.txt"],
                    confirmation=git_state.CONFIRM_DISCARD,
                    delete_untracked=True,
                )

        self.assertEqual("keep", outside_file.read_text(encoding="utf-8"))
        self.assertTrue((parked / "delete.txt").exists())

    def test_discard_delete_untracked_rejects_swapped_repo_root(self) -> None:
        (self.repo / "delete.txt").write_text("repo", encoding="utf-8")
        parked = self.base / "write-repo-original"
        outside = self.base / "outside-repo"
        outside.mkdir()
        outside_file = outside / "delete.txt"
        outside_file.write_text("keep", encoding="utf-8")
        original_validate = git_state._validate_untracked_delete_path

        def swap_before_delete(repo: Path, path: str) -> str:
            self.repo.rename(parked)
            _link_directory(self.repo, outside)
            return original_validate(repo, path)

        with patch.object(
            git_state,
            "_validate_untracked_delete_path",
            side_effect=swap_before_delete,
        ):
            with self.assertRaisesRegex(git_state.GitStateError, "repository root changed"):
                git_state.discard(
                    self.repo,
                    ["delete.txt"],
                    confirmation=git_state.CONFIRM_DISCARD,
                    delete_untracked=True,
                )

        self.assertEqual("keep", outside_file.read_text(encoding="utf-8"))
        self.assertTrue((parked / "delete.txt").exists())

    def test_discard_tracked_treats_wildcard_as_literal_path(self) -> None:
        for name in ("first.txt", "second.txt"):
            (self.repo / name).write_text("v1", encoding="utf-8")
            _git(self.repo, "add", name)
        _git(self.repo, "commit", "-q", "-m", "add tracked files")
        for name in ("first.txt", "second.txt"):
            (self.repo / name).write_text("v2", encoding="utf-8")

        git_state.discard(
            self.repo,
            ["*.txt"],
            confirmation=git_state.CONFIRM_DISCARD,
        )

        self.assertEqual("v2", (self.repo / "first.txt").read_text(encoding="utf-8"))
        self.assertEqual("v2", (self.repo / "second.txt").read_text(encoding="utf-8"))

    def test_discard_serializes_against_checkout_on_same_repo(self) -> None:
        target = self.repo / "delete.txt"
        target.write_text("delete", encoding="utf-8")
        _git(self.repo, "branch", "other")
        validation_reached = Event()
        allow_discard = Event()
        checkout_started = Event()
        checkout_completed = Event()
        errors: list[BaseException] = []
        original_validate = git_state._validate_untracked_delete_path

        def blocked_validate(repo: Path, path: str) -> str:
            result = original_validate(repo, path)
            validation_reached.set()
            if not allow_discard.wait(5):
                raise AssertionError("test timed out waiting to continue discard")
            return result

        def run_discard() -> None:
            try:
                git_state.discard(
                    self.repo,
                    ["delete.txt"],
                    confirmation=git_state.CONFIRM_DISCARD,
                    delete_untracked=True,
                )
            except BaseException as exc:  # pragma: no cover - reported below
                errors.append(exc)

        def run_checkout() -> None:
            checkout_started.set()
            try:
                git_state.checkout(
                    self.repo,
                    "other",
                    confirmation=git_state.CONFIRM_DIRTY_CHECKOUT,
                )
            except BaseException as exc:  # pragma: no cover - reported below
                errors.append(exc)
            finally:
                checkout_completed.set()

        with patch.object(
            git_state,
            "_validate_untracked_delete_path",
            side_effect=blocked_validate,
        ):
            discard_thread = Thread(target=run_discard)
            checkout_thread = Thread(target=run_checkout)
            discard_thread.start()
            self.assertTrue(validation_reached.wait(5))
            checkout_thread.start()
            self.assertTrue(checkout_started.wait(5))
            self.assertFalse(checkout_completed.wait(0.2))
            allow_discard.set()
            discard_thread.join(5)
            checkout_thread.join(5)

        self.assertFalse(discard_thread.is_alive())
        self.assertFalse(checkout_thread.is_alive())
        self.assertEqual([], errors)
        self.assertFalse(target.exists())
        self.assertEqual("other", _git(self.repo, "branch", "--show-current"))

    def test_discard_returns_fresh_status(self) -> None:
        (self.repo / "tracked.txt").write_text("v1", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        _git(self.repo, "commit", "-q", "-m", "add tracked")
        (self.repo / "tracked.txt").write_text("v2", encoding="utf-8")
        result = git_state.discard(
            self.repo,
            ["tracked.txt"],
            confirmation=git_state.CONFIRM_DISCARD,
        )
        self.assertEqual(result["status"]["counts"]["modified"], 0)


class FetchPullPushTests(_MutationBase):
    def setUp(self) -> None:
        super().setUp()
        self.remote = _init_bare_remote(self.base, "origin-bare")
        _git(self.repo, "remote", "add", "origin", str(self.remote))
        _git(self.repo, "push", "-q", "origin", "main")
        _git(self.repo, "branch", "-q", "--set-upstream-to=origin/main", "main")

    def test_fetch_rejects_unknown_and_option_like_remote(self) -> None:
        for remote in ("https://example.invalid/repo.git", "--all", "missing"):
            with self.subTest(remote=remote):
                with self.assertRaisesRegex(git_state.GitError, "remote"):
                    git_state.fetch(self.repo, remote)

    def test_pull_and_push_reject_option_like_branch(self) -> None:
        with self.assertRaisesRegex(git_state.GitError, "branch"):
            git_state.pull(self.repo, "origin", "--all")
        with self.assertRaisesRegex(git_state.GitError, "branch"):
            git_state.push(self.repo, "origin", "--mirror", git_state.CONFIRM_PUSH)

    def test_fetch_updates_remote_refs(self) -> None:
        # Advance the remote from a descendant clone (not an independent repo:
        # an independent root has its own "initial commit" SHA, and when it
        # lands in a different second than the remote's initial commit the push
        # is rejected as non-fast-forward, flaking the test).
        other = self.base / "other"
        _run(["git", "clone", "-q", str(self.remote), str(other)], self.base)
        _git(other, "config", "user.email", "test@example.com")
        _git(other, "config", "user.name", "Test User")
        (other / "remote.txt").write_text("remote change fetch", encoding="utf-8")
        _git(other, "add", "remote.txt")
        _git(other, "commit", "-q", "-m", "remote change fetch")
        _git(other, "push", "-q", "origin", "main")

        git_state.fetch(self.repo, "origin")
        remote_main = _git(self.repo, "rev-parse", "origin/main")
        self.assertNotEqual(remote_main, self._head())

    def test_pull_brings_remote_commits(self) -> None:
        other = self.base / "other"
        _run(["git", "clone", "-q", str(self.remote), str(other)], self.base)
        _git(other, "config", "user.email", "test@example.com")
        _git(other, "config", "user.name", "Test User")
        (other / "remote.txt").write_text("remote change pull", encoding="utf-8")
        _git(other, "add", "remote.txt")
        _git(other, "commit", "-q", "-m", "remote change pull")
        _git(other, "push", "-q", "origin", "main")

        git_state.pull(self.repo, "origin", "main")
        self.assertIn("remote.txt", _git(self.repo, "ls-tree", "-r", "--name-only", "HEAD"))

    def test_push_requires_confirmation(self) -> None:
        (self.repo / "feature.txt").write_text("f", encoding="utf-8")
        git_state.stage(self.repo, ["feature.txt"])
        git_state.commit(self.repo, "feature")
        with self.assertRaises(git_state.GitError) as ctx:
            git_state.push(self.repo, "origin", "main", confirmation="")
        self.assertIn("confirmation", str(ctx.exception).lower())

    def test_push_rejects_wrong_confirmation(self) -> None:
        (self.repo / "feature.txt").write_text("f", encoding="utf-8")
        git_state.stage(self.repo, ["feature.txt"])
        git_state.commit(self.repo, "feature")
        with self.assertRaises(git_state.GitError):
            git_state.push(self.repo, "origin", "main", confirmation="nope")

    def test_push_with_confirmation_updates_remote(self) -> None:
        (self.repo / "feature.txt").write_text("f", encoding="utf-8")
        git_state.stage(self.repo, ["feature.txt"])
        git_state.commit(self.repo, "feature")
        git_state.push(self.repo, "origin", "main", confirmation=git_state.CONFIRM_PUSH)
        remote_head = _run(["git", "ls-remote", str(self.remote), "refs/heads/main"], self.remote)
        self.assertIn(self._head(), remote_head)

    def test_pull_with_local_changes_yields_structured_dirty_error(self) -> None:
        # Repo tracks a file, then the remote advances it. A local uncommitted
        # edit to that file must never be clobbered by pull → structured dirty.
        (self.repo / "tracked.txt").write_text("base", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        _git(self.repo, "commit", "-q", "-m", "add tracked")
        _git(self.repo, "push", "-q", "origin", "main")

        # Advance the remote from a clean clone whose history descends from it.
        other = self.base / "other"
        _run(["git", "clone", "-q", str(self.remote), str(other)], self.base)
        _git(other, "config", "user.email", "test@example.com")
        _git(other, "config", "user.name", "Test User")
        (other / "tracked.txt").write_text("remote", encoding="utf-8")
        _git(other, "add", "tracked.txt")
        _git(other, "commit", "-q", "-m", "remote tracked")
        _git(other, "push", "-q", "origin", "main")

        # Local uncommitted change to tracked.txt → git refuses to clobber.
        (self.repo / "tracked.txt").write_text("uncommitted", encoding="utf-8")
        with self.assertRaises(git_state.GitError) as ctx:
            git_state.pull(self.repo, "origin", "main")
        self.assertEqual("dirty", ctx.exception.code)

    def test_push_auth_failure_maps_to_auth_error(self) -> None:
        (self.repo / "feature.txt").write_text("f", encoding="utf-8")
        git_state.stage(self.repo, ["feature.txt"])
        git_state.commit(self.repo, "feature")
        # Point origin at a host that will reject credentials.
        _git(self.repo, "remote", "set-url", "origin", "https://invalid.invalid/x.git")
        with self.assertRaises(git_state.GitError) as ctx:
            git_state.push(self.repo, "origin", "main", confirmation=git_state.CONFIRM_PUSH)
        self.assertIn(ctx.exception.code, ("auth", "network"))


class CheckoutTests(_MutationBase):
    def test_checkout_rejects_option_like_ref(self) -> None:
        with self.assertRaisesRegex(git_state.GitError, "git option"):
            git_state.checkout(self.repo, "--detach")

    def test_checkout_switches_branch(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        _git(self.repo, "checkout", "-q", "main")
        git_state.checkout(self.repo, "feature", confirmation="")
        self.assertEqual("feature", _git(self.repo, "symbolic-ref", "--short", "HEAD"))

    def test_checkout_new_branch_creates_branch(self) -> None:
        git_state.checkout(
            self.repo,
            "main",
            new_branch="exp",
            confirmation="",
        )
        self.assertEqual("exp", _git(self.repo, "symbolic-ref", "--short", "HEAD"))

    def test_checkout_new_branch_uses_requested_start_point(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        (self.repo / "feature-only.txt").write_text("feature", encoding="utf-8")
        _git(self.repo, "add", "feature-only.txt")
        _git(self.repo, "commit", "-q", "-m", "feature")
        _git(self.repo, "checkout", "-q", "main")

        git_state.checkout(self.repo, "feature", new_branch="from-feature")

        self.assertTrue((self.repo / "feature-only.txt").exists())

    def test_checkout_clean_tree_needs_no_confirmation(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        _git(self.repo, "checkout", "-q", "main")
        git_state.checkout(self.repo, "feature", confirmation="")
        self.assertEqual("feature", _git(self.repo, "symbolic-ref", "--short", "HEAD"))

    def test_checkout_dirty_tree_requires_confirmation(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        _git(self.repo, "checkout", "-q", "main")
        (self.repo / "tracked.txt").write_text("dirty", encoding="utf-8")
        with self.assertRaises(git_state.GitError) as ctx:
            git_state.checkout(self.repo, "feature", confirmation="")
        self.assertIn("confirmation", str(ctx.exception).lower())

    def test_checkout_dirty_tree_wrong_confirmation_rejected(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        _git(self.repo, "checkout", "-q", "main")
        (self.repo / "tracked.txt").write_text("dirty", encoding="utf-8")
        with self.assertRaises(git_state.GitError):
            git_state.checkout(self.repo, "feature", confirmation="wrong")

    def test_checkout_dirty_tree_with_confirmation_switches(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        _git(self.repo, "checkout", "-q", "main")
        (self.repo / "tracked.txt").write_text("dirty", encoding="utf-8")
        git_state.checkout(
            self.repo,
            "feature",
            confirmation=git_state.CONFIRM_DIRTY_CHECKOUT,
        )
        self.assertEqual("feature", _git(self.repo, "symbolic-ref", "--short", "HEAD"))

    def test_checkout_track_sets_upstream(self) -> None:
        self.remote = _init_bare_remote(self.base, "remote-bare.git")
        _git(self.repo, "remote", "add", "origin", str(self.remote))
        _git(self.repo, "push", "-q", "origin", "main")
        # Create origin/feature remotely via a second clone.
        other = _init_repo(self.base, "other")
        _git(other, "remote", "add", "origin", str(self.remote))
        _git(other, "checkout", "-q", "-b", "feature")
        (other / "feature.txt").write_text("f", encoding="utf-8")
        _git(other, "add", "feature.txt")
        _git(other, "commit", "-q", "-m", "feature")
        _git(other, "push", "-q", "origin", "feature")
        # Bring the remote-tracking ref into this repo, then track it.
        git_state.fetch(self.repo, "origin")
        git_state.checkout(
            self.repo,
            "origin/feature",
            confirmation="",
            track=True,
        )
        current = _git(self.repo, "symbolic-ref", "--short", "HEAD")
        self.assertEqual("feature", current)
        upstream = _git(self.repo, "rev-parse", "--abbrev-ref", "feature@{upstream}")
        self.assertEqual("origin/feature", upstream)


class AllowlistTests(_MutationBase):
    def test_mutations_reject_unknown_repo(self) -> None:
        with self.assertRaises(git_state.GitStateError):
            git_state.stage(Path("/nonexistent"), ["x.txt"])
        with self.assertRaises(git_state.GitStateError):
            git_state.discard(
                Path("/nonexistent"),
                ["x.txt"],
                confirmation=git_state.CONFIRM_DISCARD,
            )


if __name__ == "__main__":
    unittest.main()
