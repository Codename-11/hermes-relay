from __future__ import annotations

import unittest

from scripts.plan_release_backmerge import classify_release


class ReleaseBackmergePlanTest(unittest.TestCase):
    def test_already_contained_release_is_a_noop(self) -> None:
        ancestry = {("release", "dev")}
        self.assertEqual(
            classify_release(
                "release",
                "dev",
                ["main", "topic"],
                lambda older, newer: (older, newer) in ancestry,
            ),
            "already-contained",
        )

    def test_normal_release_with_dev_parent_is_a_noop(self) -> None:
        ancestry = {("released-dev", "dev")}
        self.assertEqual(
            classify_release(
                "release",
                "dev",
                ["previous-main", "released-dev"],
                lambda older, newer: (older, newer) in ancestry,
            ),
            "normal-release",
        )

    def test_selective_hotfix_requires_backmerge(self) -> None:
        self.assertEqual(
            classify_release(
                "release",
                "dev",
                ["previous-main", "hotfix-topic"],
                lambda _older, _newer: False,
            ),
            "hotfix",
        )

    def test_non_merge_release_commit_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "not a release/hotfix merge commit"):
            classify_release("release", "dev", ["parent"], lambda _older, _newer: False)


if __name__ == "__main__":
    unittest.main()
