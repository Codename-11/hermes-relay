"""Unit tests for ``plugin.relay.tts_sanitizer.sanitize_for_tts``.

Run with ``python -m unittest plugin.tests.test_tts_sanitizer`` from the
worktree root. Do NOT use pytest — the shared conftest imports the
``responses`` module which is not installed in the relay venv.

Covers one positive + one negative case per regex class, a realistic
combined Hermes assistant fixture, and empty / whitespace edge cases.
"""

from __future__ import annotations

import unittest

from plugin.relay.tts_sanitizer import sanitize_for_tts


class CodeBlockTests(unittest.TestCase):
    def test_strips_fenced_code_block(self) -> None:
        src = "Before\n```python\nprint('hi')\n```\nAfter"
        out = sanitize_for_tts(src)
        self.assertNotIn("```", out)
        self.assertNotIn("print", out)
        self.assertIn("Before", out)
        self.assertIn("After", out)

    def test_leaves_plain_prose_untouched(self) -> None:
        src = "This is a plain sentence with no code."
        self.assertEqual(sanitize_for_tts(src), src)


class MarkdownLinkTests(unittest.TestCase):
    def test_replaces_link_with_label(self) -> None:
        src = "See [the docs](https://example.com/docs) for details."
        out = sanitize_for_tts(src)
        self.assertIn("the docs", out)
        self.assertNotIn("https://", out)
        self.assertNotIn("[", out)
        self.assertNotIn("](", out)

    def test_no_link_no_change(self) -> None:
        src = "No link here at all."
        self.assertEqual(sanitize_for_tts(src), src)


class UrlTests(unittest.TestCase):
    def test_strips_bare_http_url(self) -> None:
        src = "Check https://github.com/foo/bar please."
        out = sanitize_for_tts(src)
        self.assertNotIn("github.com", out)
        self.assertNotIn("http", out)
        self.assertIn("Check", out)
        self.assertIn("please.", out)

    def test_non_url_colon_usage_unchanged(self) -> None:
        src = "The ratio is 3:1 overall."
        self.assertEqual(sanitize_for_tts(src), src)


class BoldItalicTests(unittest.TestCase):
    def test_strips_bold_markers(self) -> None:
        out = sanitize_for_tts("This is **very** important.")
        self.assertEqual(out, "This is very important.")

    def test_strips_italic_markers(self) -> None:
        out = sanitize_for_tts("This is *slightly* relevant.")
        self.assertEqual(out, "This is slightly relevant.")

    def test_prose_with_asterisk_free_text_unchanged(self) -> None:
        src = "Nothing to emphasize here."
        self.assertEqual(sanitize_for_tts(src), src)


class InlineCodeTests(unittest.TestCase):
    def test_strips_inline_code_backticks(self) -> None:
        out = sanitize_for_tts("Use the `sanitize_for_tts` function.")
        self.assertEqual(out, "Use the sanitize_for_tts function.")

    def test_plain_text_with_no_backticks(self) -> None:
        src = "Nothing to strip here."
        self.assertEqual(sanitize_for_tts(src), src)


class HeaderTests(unittest.TestCase):
    def test_strips_header_marker(self) -> None:
        out = sanitize_for_tts("# Heading\nBody text.")
        self.assertIn("Heading", out)
        self.assertNotIn("#", out)

    def test_hash_mid_line_is_preserved(self) -> None:
        # ^#+\s* is MULTILINE-anchored; a mid-sentence '#' must not go.
        src = "The tag is issue #42 here."
        self.assertEqual(sanitize_for_tts(src), src)


class ListItemTests(unittest.TestCase):
    def test_strips_dash_list_marker(self) -> None:
        out = sanitize_for_tts("- first\n- second\n- third")
        self.assertNotIn("- ", out)
        self.assertIn("first", out)
        self.assertIn("third", out)

    def test_strips_asterisk_list_marker(self) -> None:
        out = sanitize_for_tts("* alpha\n* beta")
        self.assertIn("alpha", out)
        self.assertIn("beta", out)
        # The line-start "* " must be gone; no orphan asterisks left.
        self.assertFalse(out.lstrip().startswith("*"))

    def test_hyphenated_word_is_not_a_list(self) -> None:
        src = "Well-formed prose stays intact."
        self.assertEqual(sanitize_for_tts(src), src)


class HorizontalRuleTests(unittest.TestCase):
    def test_strips_hr(self) -> None:
        out = sanitize_for_tts("Before\n---\nAfter")
        self.assertNotIn("---", out)
        self.assertIn("Before", out)
        self.assertIn("After", out)

    def test_two_dashes_preserved(self) -> None:
        # Only 3+ dashes count as HR.
        src = "A -- B"
        self.assertEqual(sanitize_for_tts(src), src)


class ExcessNewlineTests(unittest.TestCase):
    def test_collapses_excess_newlines(self) -> None:
        out = sanitize_for_tts("one\n\n\n\ntwo")
        self.assertEqual(out, "one\n\ntwo")

    def test_two_newlines_preserved(self) -> None:
        out = sanitize_for_tts("one\n\ntwo")
        self.assertEqual(out, "one\n\ntwo")


class ToolAnnotationTests(unittest.TestCase):
    def test_strips_terminal_annotation(self) -> None:
        out = sanitize_for_tts("Running `\U0001F4BB terminal` now.")
        self.assertNotIn("terminal", out)
        self.assertNotIn("\U0001F4BB", out)
        self.assertIn("Running", out)
        self.assertIn("now.", out)

    def test_strips_wrench_annotation(self) -> None:
        out = sanitize_for_tts("Calling `\U0001F527 some_tool` next.")
        self.assertNotIn("some_tool", out)
        self.assertNotIn("\U0001F527", out)

    def test_strips_check_annotation(self) -> None:
        out = sanitize_for_tts("Task `\u2705 done` complete.")
        self.assertNotIn("done", out)
        self.assertNotIn("\u2705", out)

    def test_strips_cross_annotation(self) -> None:
        out = sanitize_for_tts("Error `\u274C error` encountered.")
        self.assertNotIn("\u274C", out)
        # "error" as a bare word may appear elsewhere but the
        # annotation's "error" token is gone because we matched the whole
        # backtick span.
        self.assertNotIn("`", out)

    def test_strips_android_annotation(self) -> None:
        out = sanitize_for_tts("Invoking `\U0001F4F1 android_tap` now.")
        self.assertNotIn("android_tap", out)
        self.assertNotIn("\U0001F4F1", out)

    def test_strips_warning_with_variation_selector(self) -> None:
        out = sanitize_for_tts(
            "Caution `\u26A0\uFE0F warning` ahead."
        )
        self.assertNotIn("warning", out)
        self.assertNotIn("\u26A0", out)

    def test_non_annotation_inline_code_is_just_inline_code(self) -> None:
        # A bare inline-code span without an emoji must fall through to
        # the inline-code rule — backticks gone, content preserved.
        out = sanitize_for_tts("Import `json` for this.")
        self.assertEqual(out, "Import json for this.")


class StandaloneEmojiTests(unittest.TestCase):
    def test_strips_standalone_check(self) -> None:
        out = sanitize_for_tts("Done \u2705 moving on.")
        self.assertNotIn("\u2705", out)
        self.assertIn("Done", out)
        self.assertIn("moving on.", out)

    def test_does_not_strip_flag_emoji(self) -> None:
        # Regional-indicator sequence for 🇺🇸 — must survive.
        src = "Greetings from \U0001F1FA\U0001F1F8 today."
        out = sanitize_for_tts(src)
        self.assertIn("\U0001F1FA\U0001F1F8", out)

    def test_does_not_strip_unrelated_symbols(self) -> None:
        # Math / currency symbols outside the conservative set stay.
        src = "Cost is $5 and ratio is 3:1."
        self.assertEqual(sanitize_for_tts(src), src)


class EmptyInputTests(unittest.TestCase):
    def test_empty_string(self) -> None:
        self.assertEqual(sanitize_for_tts(""), "")

    def test_whitespace_only(self) -> None:
        self.assertEqual(sanitize_for_tts("   \n\t  "), "")


class CombinedFixtureTest(unittest.TestCase):
    """Realistic Hermes assistant reply exercising every rule at once."""

    def test_realistic_message_reads_cleanly(self) -> None:
        src = (
            "# Summary\n"
            "\n"
            "Running `\U0001F4BB terminal` to check the repo. "
            "See [the plan](https://example.com/plan) for details — "
            "it uses **ElevenLabs** and the *flash* streaming model.\n"
            "\n"
            "Steps:\n"
            "- Pull https://github.com/foo/bar\n"
            "- Run the `sanitize_for_tts` helper\n"
            "- Confirm `\u2705 done`\n"
            "\n"
            "---\n"
            "\n"
            "```python\n"
            "print('this should not be spoken')\n"
            "```\n"
            "\n"
            "All good \u2705."
        )
        out = sanitize_for_tts(src)

        # Nothing the voice should stumble over.
        self.assertNotIn("```", out)
        self.assertNotIn("**", out)
        self.assertNotIn("http", out)
        self.assertNotIn("github.com", out)
        self.assertNotIn("example.com", out)
        self.assertNotIn("print(", out)
        self.assertNotIn("\U0001F4BB", out)
        self.assertNotIn("\u2705", out)
        self.assertNotIn("---", out)
        # Header marker gone but heading text preserved.
        self.assertNotIn("#", out)
        self.assertIn("Summary", out)
        # Tool-annotation spans gone entirely.
        self.assertNotIn("terminal", out)
        self.assertNotIn("done", out)
        # Link label preserved, URL gone.
        self.assertIn("the plan", out)
        # Bold/italic markers gone, words kept.
        self.assertIn("ElevenLabs", out)
        self.assertIn("flash", out)
        # List content preserved, markers gone.
        self.assertIn("Pull", out)
        self.assertIn("sanitize_for_tts", out)
        # No line in the output starts with "- " or "* ".
        for line in out.splitlines():
            stripped = line.lstrip()
            self.assertFalse(stripped.startswith("- "))
            self.assertFalse(stripped.startswith("* "))
        # Excess blank runs collapsed — no 3+ consecutive newlines.
        self.assertNotIn("\n\n\n", out)


if __name__ == "__main__":
    unittest.main()
