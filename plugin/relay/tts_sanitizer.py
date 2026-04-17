"""Sanitize assistant text before handing it to the TTS backend.

The upstream hermes-agent repo has a private helper
``tools.tts_tool._strip_markdown_for_tts`` (at ``tools/tts_tool.py`` line
~1053) that strips markdown prior to feeding a string to ElevenLabs /
OpenAI TTS. That helper, however, is only invoked from the CLI streaming
speaker path — the programmatic ``text_to_speech_tool`` entry point the
relay uses does NOT run it. That means ElevenLabs was reading
``` `\U0001F4BB terminal` ``` aloud as "backtick computer emoji terminal
backtick" and URLs character-by-character, producing the
"jumbled letters" voice-mode symptom Bailey reported on 2026-04-16.

We mirror the upstream regex set here rather than importing it:

1. The upstream helper is *private* — the name could change at any
   release.
2. We want to layer in a few Hermes-specific patterns (backtick-wrapped
   tool-annotation emoji tokens from ``ChatHandler.kt:50-74`` and a
   conservative standalone-emoji pass) that would not be appropriate
   upstream.
3. The sanitizer needs to be independently testable with no hermes-agent
   venv available (Claude-side Windows checkouts do not have it
   installed).

The regex set below is sourced from
``~/.hermes/hermes-agent/tools/tts_tool.py`` (see lines 1053+). Credit
upstream — if it evolves we should sync.
"""

from __future__ import annotations

import logging
import re
from typing import Final

logger = logging.getLogger(__name__)


# ── Upstream regex set (credit: hermes-agent tools/tts_tool.py:1053+) ─

# Code fences first — their inner contents must not be interpreted by the
# bold / italic / inline-code rules below.
_MD_CODE_BLOCK: Final[re.Pattern[str]] = re.compile(r"```[\s\S]*?```")
_MD_LINK: Final[re.Pattern[str]] = re.compile(r"\[([^\]]+)\]\([^)]+\)")
_MD_URL: Final[re.Pattern[str]] = re.compile(r"https?://\S+")
_MD_BOLD: Final[re.Pattern[str]] = re.compile(r"\*\*(.+?)\*\*")
_MD_ITALIC: Final[re.Pattern[str]] = re.compile(r"\*(.+?)\*")
_MD_INLINE_CODE: Final[re.Pattern[str]] = re.compile(r"`(.+?)`")
_MD_HEADER: Final[re.Pattern[str]] = re.compile(r"^#+\s*", flags=re.MULTILINE)
_MD_LIST_ITEM: Final[re.Pattern[str]] = re.compile(
    r"^\s*[-*]\s+", flags=re.MULTILINE
)
_MD_HR: Final[re.Pattern[str]] = re.compile(r"---+")
_MD_EXCESS_NL: Final[re.Pattern[str]] = re.compile(r"\n{3,}")


# ── Hermes-specific additions ──────────────────────────────────────────

# The conservative tool-annotation emoji set used by ``ChatHandler.kt`` to
# prefix backtick-wrapped label tokens. We match the whole
# ``\`<emoji> <label>\``` span (emoji+optional space+label) and strip it
# entirely — otherwise ``_MD_INLINE_CODE`` would leave "computer emoji
# terminal" behind after removing the backticks.
#
# Note on the warning sign glyph: U+26A0 frequently appears with the
# variation selector U+FE0F (``\u26A0\uFE0F``). We allow either.
_TOOL_ANNOTATION_EMOJI: Final[str] = (
    "\U0001F527"   # 🔧 wrench
    "\u2705"       # ✅ white heavy check mark
    "\u274C"       # ❌ cross mark
    "\U0001F4BB"   # 💻 laptop
    "\U0001F4F1"   # 📱 mobile phone
    "\U0001F3A4"   # 🎤 microphone
    "\U0001F50A"   # 🔊 speaker high volume
    "\u26A0"       # ⚠  warning sign (with or without U+FE0F)
)

# Backtick-wrapped token beginning with one of the tool-annotation
# emojis. Strip the entire match, not just the backticks.
_TOOL_ANNOTATION: Final[re.Pattern[str]] = re.compile(
    r"`[" + _TOOL_ANNOTATION_EMOJI + r"]\uFE0F?[^`]*`"
)

# Standalone (whitespace-bordered, or bordered by string edges) instances
# of the conservative emoji set. Deliberately NOT a broad Unicode category
# sweep — that would eat flag emoji, regional indicators, and CJK
# punctuation in mixed-script assistant replies. This list matches the
# tool-annotation emoji set.
_STANDALONE_EMOJI: Final[re.Pattern[str]] = re.compile(
    r"[" + _TOOL_ANNOTATION_EMOJI + r"]\uFE0F?"
)


# If the sanitized length differs from input by more than this many
# characters we log a DEBUG preview of what was stripped. The threshold is
# a rough "more than a handful" — a few chars of noise is normal.
_DELTA_LOG_THRESHOLD: Final[int] = 8


def sanitize_for_tts(text: str) -> str:
    """Strip markdown, tool annotations, URLs, and emoji from TTS input.

    Pure function. Applies the regex set in a fixed order: code blocks
    and tool annotations first (so their inner contents do not get
    processed by the bold / italic / inline-code rules), then the
    upstream markdown set, then standalone emoji, then whitespace
    normalization.

    Args:
        text: Raw assistant text as it would be fed to the TTS backend.

    Returns:
        A cleaned string safe to speak aloud. Empty input and
        whitespace-only input return an empty string.
    """
    if not text or not text.strip():
        return ""

    original = text

    # 1) Code fences before anything else — their contents must not be
    #    touched by later rules.
    text = _MD_CODE_BLOCK.sub(" ", text)

    # 2) Tool annotations BEFORE inline code — otherwise the inline-code
    #    rule would only strip the backticks and leave the emoji+label
    #    visible.
    text = _TOOL_ANNOTATION.sub("", text)

    # 3) Upstream markdown set.
    text = _MD_LINK.sub(r"\1", text)
    text = _MD_URL.sub("", text)
    text = _MD_BOLD.sub(r"\1", text)
    text = _MD_ITALIC.sub(r"\1", text)
    text = _MD_INLINE_CODE.sub(r"\1", text)
    text = _MD_HEADER.sub("", text)
    text = _MD_LIST_ITEM.sub("", text)
    text = _MD_HR.sub("", text)

    # 4) Standalone emoji — conservative set only.
    text = _STANDALONE_EMOJI.sub("", text)

    # 5) Whitespace normalization — collapse runs of 3+ newlines and
    #    trim leading/trailing whitespace.
    text = _MD_EXCESS_NL.sub("\n\n", text)
    text = text.strip()

    delta = len(original) - len(text)
    if delta > _DELTA_LOG_THRESHOLD:
        # Short preview of the input so operators can spot over-stripping
        # in relay logs without dumping the entire assistant turn.
        preview = original[:80].replace("\n", " ")
        if len(original) > 80:
            preview += "..."
        logger.debug(
            "sanitize_for_tts removed %d chars (in=%d out=%d) preview=%r",
            delta,
            len(original),
            len(text),
            preview,
        )

    return text
