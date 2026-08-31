from __future__ import annotations

import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check-android-release-notes.py"
SPEC = importlib.util.spec_from_file_location("check_android_release_notes", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
release_notes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_notes)


def entry(*, highlight_count: int = 2) -> dict[str, object]:
    changes = [
        {
            "id": f"change-{index}",
            "kind": ("added", "improved", "fixed")[index % 3],
            "title": f"Change {index}",
            "summary": f"User-facing explanation {index}.",
            "highlight": index < highlight_count,
        }
        for index in range(5)
    ]
    return {
        "version": "1.2.3",
        "title": "A useful release",
        "date": "2026-08-26",
        "summary": "A short explanation in plain language.",
        "changes": changes,
        "compatibility": ["Works with existing connections."],
        "playNotes": "A concise Play Store summary.",
    }


class AndroidReleaseNotesTest(unittest.TestCase):
    def test_accepts_complete_change_inventory(self) -> None:
        self.assertEqual([], release_notes.validate_curated_entry(entry(), "1.2.3"))

    def test_rejects_more_than_four_highlights(self) -> None:
        errors = release_notes.validate_curated_entry(entry(highlight_count=5), "1.2.3")
        self.assertIn("latest changelog must select 1-4 highlighted changes", errors)

    def test_rejects_duplicate_change_ids(self) -> None:
        release = entry()
        release["changes"][1]["id"] = "change-0"  # type: ignore[index]
        errors = release_notes.validate_curated_entry(release, "1.2.3")
        self.assertIn("latest changelog change id 'change-0' is duplicated", errors)

    def test_rejects_unknown_change_kind(self) -> None:
        release = entry()
        release["changes"][2]["kind"] = "technical"  # type: ignore[index]
        errors = release_notes.validate_curated_entry(release, "1.2.3")
        self.assertIn("latest changelog change 3 kind must be one of added, improved, fixed", errors)

    def test_renders_complete_fallback_without_duplicate_highlights(self) -> None:
        rendered = release_notes.render_whats_new(entry())
        self.assertIn("Summary\n* A short explanation in plain language.", rendered)
        self.assertIn("Highlights\n* Change 0 — User-facing explanation 0.", rendered)
        self.assertIn("Fixed\n* Change 2 — User-facing explanation 2.", rendered)
        self.assertEqual(1, rendered.count("Change 0 — User-facing explanation 0."))
        self.assertIn("Compatibility\n* Works with existing connections.", rendered)

    def test_play_notes_include_version_and_stay_bounded(self) -> None:
        rendered = release_notes.render_play_notes(entry())
        self.assertTrue(rendered.startswith("v1.2.3 - A useful release"))
        self.assertLessEqual(len(rendered), 500)

    def test_allows_a_highlights_only_release(self) -> None:
        release = entry()
        release["changes"] = release["changes"][:2]  # type: ignore[index]

        self.assertEqual([], release_notes.validate_curated_entry(release, "1.2.3"))

    def test_rejects_legacy_manual_digest(self) -> None:
        release = entry()
        release["toastDigest"] = {
            "additionalFeatureCount": 1,
            "fixCount": 2,
            "preview": ["Duplicated summary"],
        }

        errors = release_notes.validate_curated_entry(release, "1.2.3")

        self.assertIn("latest changelog must derive presentation instead of defining toastDigest", errors)


if __name__ == "__main__":
    unittest.main()
