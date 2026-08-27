from __future__ import annotations

import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check-android-release-notes.py"
SPEC = importlib.util.spec_from_file_location("check_android_release_notes", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
release_notes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_notes)


def entry(*, bullets: int = 3, improvements: int = 2) -> dict[str, object]:
    return {
        "version": "1.2.3",
        "title": "A useful release",
        "date": "2026-08-26",
        "highlight": {
            "title": "The main reason to care",
            "summary": "A short explanation in plain language.",
            "bullets": [f"Benefit {index}" for index in range(bullets)],
        },
        "improvements": [f"Improvement {index}" for index in range(improvements)],
        "toastDigest": {
            "additionalFeatureCount": 1,
            "fixCount": 2,
            "preview": ["Secondary feature", "Important fix"],
        },
        "playNotes": "A concise Play Store summary.",
    }


class AndroidReleaseNotesTest(unittest.TestCase):
    def test_allows_three_highlights_and_two_improvements(self) -> None:
        self.assertEqual([], release_notes.validate_curated_entry(entry(), "1.2.3"))

    def test_rejects_more_than_three_highlights(self) -> None:
        errors = release_notes.validate_curated_entry(entry(bullets=4, improvements=0), "1.2.3")
        self.assertIn("latest changelog highlight must contain 1-3 bullets", errors)

    def test_rejects_more_than_five_visible_items(self) -> None:
        errors = release_notes.validate_curated_entry(entry(bullets=3, improvements=3), "1.2.3")
        self.assertIn("latest changelog may contain at most 2 improvements", errors)
        self.assertIn("latest changelog may expose at most 5 total list items", errors)

    def test_renders_fallback_with_curated_groups(self) -> None:
        rendered = release_notes.render_whats_new(entry(bullets=1, improvements=1))
        self.assertIn("The main reason to care\n* Benefit 0", rendered)
        self.assertIn("Also improved\n* Improvement 0", rendered)

    def test_play_notes_include_version_and_stay_bounded(self) -> None:
        rendered = release_notes.render_play_notes(entry())
        self.assertTrue(rendered.startswith("v1.2.3 - A useful release"))
        self.assertLessEqual(len(rendered), 500)

    def test_allows_a_hero_only_release_without_secondary_filler(self) -> None:
        release = entry()
        release["toastDigest"] = {
            "additionalFeatureCount": 0,
            "fixCount": 0,
            "preview": [],
        }

        self.assertEqual([], release_notes.validate_curated_entry(release, "1.2.3"))

    def test_rejects_preview_when_secondary_counts_are_zero(self) -> None:
        release = entry()
        release["toastDigest"] = {
            "additionalFeatureCount": 0,
            "fixCount": 0,
            "preview": ["Invented filler"],
        }

        errors = release_notes.validate_curated_entry(release, "1.2.3")

        self.assertIn("latest changelog toastDigest preview must be empty when counts are zero", errors)

    def test_requires_a_bounded_preview_for_secondary_items(self) -> None:
        release = entry()
        release["toastDigest"] = {
            "additionalFeatureCount": 1,
            "fixCount": 2,
            "preview": ["one", "two", "three"],
        }

        errors = release_notes.validate_curated_entry(release, "1.2.3")

        self.assertIn(
            "latest changelog toastDigest preview must contain 1-2 items when secondary items exist",
            errors,
        )


if __name__ == "__main__":
    unittest.main()
