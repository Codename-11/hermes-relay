"""Tests for the read-only Git state surface (plugin/git_state.py).

Fixtures create REAL throwaway git repositories in tmp_path — init, config
user, commits, branches, and bare remotes. Git itself is never mocked.
"""

from __future__ import annotations

import os
import subprocess
import unittest
from pathlib import Path

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


def _add_remote(repo: Path, remote_url: str, name: str = "origin") -> None:
    _git(repo, "remote", "add", name, remote_url)


class GitStateScanTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(self.tempdir())

    def tempdir(self) -> str:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        return self._td.name

    def test_scan_finds_nested_repos_and_ignores_non_repos(self) -> None:
        base = self.tmp / "projects"
        base.mkdir(parents=True)
        _init_repo(base, "alpha")
        _init_repo(base / "nested", "beta")
        # A plain directory with no .git must be ignored.
        (base / "plain").mkdir()
        (base / "plain" / "file.txt").write_text("x", encoding="utf-8")

        repos = git_state.scan_repos(base)
        names = {r["name"] for r in repos}
        self.assertEqual({"alpha", "beta"}, names)
        for repo in repos:
            self.assertEqual("main", repo["current_branch"])
            self.assertFalse(repo["dirty"])

    def test_scan_missing_base_path_returns_empty(self) -> None:
        missing = self.tmp / "does-not-exist"
        self.assertEqual([], git_state.scan_repos(missing))

    def test_scan_excludes_git_internals(self) -> None:
        base = self.tmp / "projects"
        base.mkdir(parents=True)
        _init_repo(base, "alpha")
        # A .git directory itself must never be reported as a repo.
        repos = git_state.scan_repos(base)
        self.assertTrue(all(".git" not in r["name"] for r in repos))

    def test_scan_marks_dirty_repo(self) -> None:
        base = self.tmp / "projects"
        base.mkdir(parents=True)
        repo = _init_repo(base, "dirty")
        (repo / "new.txt").write_text("untracked", encoding="utf-8")
        repos = git_state.scan_repos(base)
        dirty = next(r for r in repos if r["name"] == "dirty")
        self.assertTrue(dirty["dirty"])


class GitStateStatusTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name) / "projects"
        self.base.mkdir(parents=True)
        self.repo = _init_repo(self.base, "status-repo")

    def test_status_groups_modified_untracked_and_staged(self) -> None:
        (self.repo / "tracked.txt").write_text("v1", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        _git(self.repo, "commit", "-q", "-m", "add tracked")
        # staged change
        (self.repo / "tracked.txt").write_text("v2", encoding="utf-8")
        _git(self.repo, "add", "tracked.txt")
        # unstaged change
        (self.repo / "README.md").write_text("# Changed\n", encoding="utf-8")
        # untracked
        (self.repo / "untracked.txt").write_text("new", encoding="utf-8")

        status = git_state.repo_status(self.repo)
        self.assertEqual(1, status["counts"]["staged"])
        self.assertEqual(1, status["counts"]["modified"])
        self.assertEqual(1, status["counts"]["untracked"])
        self.assertEqual("tracked.txt", status["staged"][0]["path"])
        self.assertEqual("README.md", status["modified"][0]["path"])
        self.assertEqual("untracked.txt", status["untracked"][0]["path"])
        self.assertFalse(status["truncated"])

    def test_status_truncates_when_over_cap(self) -> None:
        for i in range(git_state.MAX_STATUS_ENTRIES + 5):
            (self.repo / f"file-{i}.txt").write_text("x", encoding="utf-8")
        status = git_state.repo_status(self.repo)
        self.assertTrue(status["truncated"])
        self.assertLessEqual(
            len(status["untracked"]),
            git_state.MAX_STATUS_ENTRIES,
        )

    def test_status_lists_staged_and_modified_same_file(self) -> None:
        # A file staged AND then modified again ("MM" in porcelain) must appear
        # in BOTH the staged and modified groups (independent checks, not elif).
        (self.repo / "mm.txt").write_text("v1", encoding="utf-8")
        _git(self.repo, "add", "mm.txt")
        _git(self.repo, "commit", "-q", "-m", "add mm")
        (self.repo / "mm.txt").write_text("v2", encoding="utf-8")
        _git(self.repo, "add", "mm.txt")
        (self.repo / "mm.txt").write_text("v3", encoding="utf-8")

        status = git_state.repo_status(self.repo)
        staged_paths = {e["path"] for e in status["staged"]}
        modified_paths = {e["path"] for e in status["modified"]}
        self.assertIn("mm.txt", staged_paths)
        self.assertIn("mm.txt", modified_paths)

    def test_status_rename_emits_single_staged_entry(self) -> None:
        # `git mv` produces two NUL-separated porcelain records ("R  new\0old\0");
        # the bare source-path record must be skipped, not misparsed as an XY
        # record. Use a source name starting with "M" so a naive parser would
        # misclassify the bare source record as staged with a truncated path.
        (self.repo / "Moved.txt").write_text("content", encoding="utf-8")
        _git(self.repo, "add", "Moved.txt")
        _git(self.repo, "commit", "-q", "-m", "add Moved")
        _git(self.repo, "mv", "Moved.txt", "new.txt")

        status = git_state.repo_status(self.repo)
        self.assertEqual(["new.txt"], [e["path"] for e in status["staged"]])
        self.assertEqual([], status["modified"])
        self.assertEqual([], status["untracked"])

    def test_status_unstaged_rename_shows_delete_and_untracked(self) -> None:
        # Rename on disk only (no `git mv`): delete + create → D + ??.
        (self.repo / "old.txt").write_text("content", encoding="utf-8")
        _git(self.repo, "add", "old.txt")
        _git(self.repo, "commit", "-q", "-m", "add old")
        (self.repo / "old.txt").unlink()
        (self.repo / "new.txt").write_text("content", encoding="utf-8")

        status = git_state.repo_status(self.repo)
        self.assertEqual([], status["staged"])
        self.assertEqual(["old.txt"], [e["path"] for e in status["modified"]])
        self.assertEqual(["new.txt"], [e["path"] for e in status["untracked"]])


class GitStateBranchesTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name) / "projects"
        self.base.mkdir(parents=True)
        self.repo = _init_repo(self.base, "branch-repo")

    def test_branches_reports_current_upstream_ahead_behind(self) -> None:
        _git(self.repo, "checkout", "-q", "-b", "feature")
        (self.repo / "feature.txt").write_text("f", encoding="utf-8")
        _git(self.repo, "add", "feature.txt")
        _git(self.repo, "commit", "-q", "-m", "feature work")

        branches = git_state.repo_branches(self.repo)
        by_name = {b["name"]: b for b in branches}
        self.assertIn("main", by_name)
        self.assertIn("feature", by_name)
        self.assertTrue(by_name["feature"]["is_current"])
        self.assertFalse(by_name["main"]["is_current"])


class GitStateDiffTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name) / "projects"
        self.base.mkdir(parents=True)
        self.repo = _init_repo(self.base, "diff-repo")

    def test_diff_unstaged_and_staged_kinds(self) -> None:
        (self.repo / "a.txt").write_text("one\n", encoding="utf-8")
        _git(self.repo, "add", "a.txt")
        _git(self.repo, "commit", "-q", "-m", "add a")
        # staged change
        (self.repo / "a.txt").write_text("two\n", encoding="utf-8")
        _git(self.repo, "add", "a.txt")
        # unstaged change
        (self.repo / "a.txt").write_text("three\n", encoding="utf-8")

        staged = git_state.repo_diff(self.repo, "a.txt", kind="staged")
        self.assertIn("+two", staged["diff"])
        unstaged = git_state.repo_diff(self.repo, "a.txt", kind="unstaged")
        self.assertIn("+three", unstaged["diff"])

    def test_diff_invalid_kind_raises(self) -> None:
        with self.assertRaises(ValueError):
            git_state.repo_diff(self.repo, "a.txt", kind="bogus")

    def test_diff_truncates_large_output(self) -> None:
        big = "x" * 200_000
        (self.repo / "big.txt").write_text(big + "\n", encoding="utf-8")
        _git(self.repo, "add", "big.txt")
        _git(self.repo, "commit", "-q", "-m", "add big")
        (self.repo / "big.txt").write_text(big + "y\n", encoding="utf-8")
        result = git_state.repo_diff(self.repo, "big.txt", kind="unstaged")
        self.assertTrue(result["truncated"])
        self.assertLessEqual(len(result["diff"]), git_state.MAX_DIFF_BYTES)


class GitStateFileTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name) / "projects"
        self.base.mkdir(parents=True)
        self.repo = _init_repo(self.base, "file-repo")

    def test_read_tracked_file(self) -> None:
        content = git_state.read_file(self.repo, "README.md")
        self.assertIn("Hello", content["content"])

    def test_read_tracked_file_returns_working_tree_not_committed(self) -> None:
        # A modified-but-uncommitted tracked file must return the on-disk
        # (working-tree) content, not the last committed version.
        (self.repo / "README.md").write_text("# Working Tree\n", encoding="utf-8")
        content = git_state.read_file(self.repo, "README.md")
        self.assertIn("Working Tree", content["content"])
        self.assertNotIn("Hello", content["content"])

    def test_read_untracked_file_raises(self) -> None:
        (self.repo / "untracked.txt").write_text("new", encoding="utf-8")
        with self.assertRaises(ValueError):
            git_state.read_file(self.repo, "untracked.txt")

    def test_read_missing_file_raises(self) -> None:
        with self.assertRaises(ValueError):
            git_state.read_file(self.repo, "nope.txt")

    def test_read_tracked_binary_file_raises_gitstateerror(self) -> None:
        # A TRACKED binary file (e.g. a committed PNG) must raise GitStateError
        # ("binary file is not supported" / UTF-8), never an unhandled
        # UnicodeDecodeError that escapes as a 500.
        (self.repo / "image.png").write_bytes(b"\x89PNG\r\n\x1a\n\x00binary\xff\xfe")
        _git(self.repo, "add", "image.png")
        _git(self.repo, "commit", "-q", "-m", "add binary")
        with self.assertRaises(git_state.GitStateError) as ctx:
            git_state.read_file(self.repo, "image.png")
        message = str(ctx.exception)
        self.assertTrue("binary" in message or "UTF-8" in message)

    def test_read_tracked_non_utf8_text_raises(self) -> None:
        # A tracked, NUL-free but non-UTF-8 text file (Latin-1) must raise a
        # clear GitStateError, not an unhandled UnicodeDecodeError.
        (self.repo / "latin1.txt").write_bytes(b"caf\xe9 latin1")
        _git(self.repo, "add", "latin1.txt")
        _git(self.repo, "commit", "-q", "-m", "add latin1")
        with self.assertRaises(git_state.GitStateError) as ctx:
            git_state.read_file(self.repo, "latin1.txt")
        self.assertIn("not valid UTF-8 text", str(ctx.exception))


class GitStateDocumentTests(unittest.TestCase):
    def test_document_missing_base_notice_leaks_no_path(self) -> None:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        missing = Path(self._td.name) / "does-not-exist"

        doc = git_state.build_git_document(missing)
        notice = doc["pages"][0]["content"]["children"][0]
        self.assertEqual("notice", notice["id"])
        value = notice["text"]["value"]
        # The document contract forbids filesystem paths: no "/" and no
        # path-like substring (e.g. the tmp dir name).
        self.assertNotIn("/", value)
        self.assertNotIn(self._td.name, value)


class GitStateSecurityTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self.base = Path(self._td.name) / "projects"
        self.base.mkdir(parents=True)
        self.repo = _init_repo(self.base, "sec-repo")

    def test_path_traversal_rejected(self) -> None:
        for bad in ("../outside", "/etc/passwd", "a/../../b", "..%2Fescape"):
            with self.subTest(path=bad):
                with self.assertRaises(ValueError):
                    git_state.resolve_repo_path(self.repo, bad)

    def test_remote_urls_scrubbed_of_userinfo(self) -> None:
        _add_remote(self.repo, "https://user:secret@example.com/org/repo.git", "https")
        _add_remote(self.repo, "ssh://git@example.com:2222/org/repo.git", "ssh")
        _add_remote(self.repo, "git@example.com:org/repo.git", "scp")
        remotes = git_state.repo_remotes(self.repo)
        self.assertEqual(3, len(remotes))
        for remote in remotes:
            self.assertNotIn("secret", remote["url"])
            self.assertNotIn("user:", remote["url"])
            self.assertNotIn("git@", remote["url"])

    def test_allowlist_accepts_only_scanned_repos(self) -> None:
        scanned = git_state.scan_repos(self.base)
        ids = {r["id"] for r in scanned}
        self.assertIn(git_state.repo_id(self.repo), ids)
        self.assertNotIn("bogus-id", ids)


if __name__ == "__main__":
    unittest.main()
