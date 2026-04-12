"""
Prompt template + response parser for ``android_navigate``.

Kept separate from the tool module so the parser is unit-testable
without importing ``requests`` / the bridge transport layer.

The tool asks a vision-capable model to decide the next single action
toward a user intent, given the current screen (screenshot + optional
accessibility tree). The model must reply with one line of the form:

    ACTION: <verb>
    PARAMS: <json object>
    REASON: <short sentence>

where ``verb`` is one of:

    tap_text     — params {"text": str, "exact": bool?}
    tap          — params {"x": int, "y": int} or {"node_id": str}
    type         — params {"text": str, "clear_first": bool?}
    swipe        — params {"direction": "up|down|left|right",
                           "distance": "short|medium|long"?}
    press_key    — params {"key": str}  (back|home|enter|...)
    done         — params {}  (REASON carries the explanation)

The parser is deliberately lenient — it tolerates extra whitespace,
keys in any order, and a few alternate phrasings — but refuses on
missing verb or on an unknown verb. Malformed responses surface as a
``ParsedAction`` with ``action = "error"`` so the caller can log and
abort the loop cleanly.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any


# Superset of actions we accept. ``error`` is an internal sentinel — the
# model is never told about it and a real model reply should never name it.
VALID_ACTIONS: tuple[str, ...] = (
    "tap_text",
    "tap",
    "type",
    "swipe",
    "press_key",
    "done",
)


@dataclass
class ParsedAction:
    """Result of parsing a model reply.

    Attributes:
        action:    one of ``VALID_ACTIONS`` on success, or ``"error"`` on
                   malformed input.
        params:    the kwargs to pass to the matching bridge endpoint.
        reasoning: short model-provided explanation, or the parser error
                   message if ``action == "error"``.
        raw:       the original model reply, stored for the trace.
    """

    action: str
    params: dict[str, Any] = field(default_factory=dict)
    reasoning: str = ""
    raw: str = ""


# ── Prompt template ────────────────────────────────────────────────────────────

_PROMPT_TEMPLATE = """\
You are guiding an Android phone toward a user goal one step at a time.

USER INTENT:
{intent}

STEP {step} of at most {max_steps}.

You will be shown the current screen (screenshot attached). An
accessibility tree may also be provided below. Decide the *single next
action* that best advances toward the intent. Do not plan multiple
steps — you will be invoked again with a fresh screenshot after each
action.

Available actions (pick exactly ONE):
  - tap_text    params: {{"text": "<visible label>", "exact": false}}
  - tap         params: {{"x": <int>, "y": <int>}} or {{"node_id": "<id>"}}
  - type        params: {{"text": "<string to type>", "clear_first": false}}
  - swipe       params: {{"direction": "up|down|left|right", "distance": "short|medium|long"}}
  - press_key   params: {{"key": "back|home|recents|enter|delete|tab|escape|search|notifications"}}
  - done        params: {{}}   (only when the intent is clearly satisfied)

Prefer ``tap_text`` over coordinate ``tap`` whenever the target has a
visible label. Use ``done`` as soon as the goal is unambiguously
reached — do not add celebratory extra steps.

Reply in EXACTLY this format, no prose before or after:

ACTION: <verb>
PARAMS: <one-line JSON object>
REASON: <one short sentence explaining the choice>

ACCESSIBILITY TREE (may be empty):
{accessibility_tree}
"""


def build_prompt(
    intent: str,
    step: int,
    max_steps: int,
    accessibility_tree: str = "",
) -> str:
    """Render the vision-model prompt for the current iteration.

    ``accessibility_tree`` is embedded verbatim — callers should stringify
    the bridge ``/screen`` response into something the model can read
    (JSON-dump or a compact text rendering). Empty is fine.
    """

    tree_block = accessibility_tree.strip() or "(not provided)"
    return _PROMPT_TEMPLATE.format(
        intent=intent.strip() or "(empty intent)",
        step=step,
        max_steps=max_steps,
        accessibility_tree=tree_block,
    )


# ── Response parser ────────────────────────────────────────────────────────────

# Regexes are anchored on line-starts (multiline) so we tolerate stray
# leading whitespace from indented markdown blocks and CRLF endings.
_ACTION_RE = re.compile(r"^\s*ACTION\s*:\s*(\S+)", re.IGNORECASE | re.MULTILINE)
_PARAMS_RE = re.compile(r"^\s*PARAMS\s*:\s*(.+?)\s*$", re.IGNORECASE | re.MULTILINE)
_REASON_RE = re.compile(r"^\s*REASON\s*:\s*(.+?)\s*$", re.IGNORECASE | re.MULTILINE)


def _error(reason: str, raw: str) -> ParsedAction:
    return ParsedAction(action="error", params={}, reasoning=reason, raw=raw)


def parse_response(reply: str | None) -> ParsedAction:
    """Parse a model reply into a ``ParsedAction``.

    This is intentionally forgiving on whitespace + casing and strict
    on the verb whitelist. Any structural failure returns
    ``ParsedAction(action="error", reasoning=<why>)`` — callers should
    treat that as a terminal failure for the current iteration.
    """

    if reply is None:
        return _error("empty reply", "")

    raw = reply
    text = reply.strip()
    if not text:
        return _error("empty reply", raw)

    action_match = _ACTION_RE.search(text)
    if not action_match:
        return _error("missing ACTION: line", raw)

    verb = action_match.group(1).strip().lower().rstrip(",.;:")
    if verb not in VALID_ACTIONS:
        return _error(f"unknown action verb: {verb!r}", raw)

    # PARAMS is optional for `done` (model may omit an empty object).
    params: dict[str, Any] = {}
    params_match = _PARAMS_RE.search(text)
    if params_match:
        params_str = params_match.group(1).strip()
        if params_str and params_str not in ("{}", "None", "null"):
            try:
                parsed = json.loads(params_str)
            except json.JSONDecodeError as exc:
                return _error(f"PARAMS is not valid JSON: {exc.msg}", raw)
            if not isinstance(parsed, dict):
                return _error("PARAMS must be a JSON object", raw)
            params = parsed
    elif verb != "done":
        return _error("missing PARAMS: line", raw)

    # Per-verb shape validation. We check just enough to give the caller
    # a clean error before making an HTTP call with missing fields.
    shape_error = _validate_params(verb, params)
    if shape_error:
        return _error(shape_error, raw)

    reason = ""
    reason_match = _REASON_RE.search(text)
    if reason_match:
        reason = reason_match.group(1).strip()

    return ParsedAction(action=verb, params=params, reasoning=reason, raw=raw)


def _validate_params(verb: str, params: dict[str, Any]) -> str | None:
    """Return an error message if ``params`` is shaped wrong for ``verb``."""

    if verb == "tap_text":
        if "text" not in params or not isinstance(params["text"], str):
            return "tap_text requires string 'text'"
    elif verb == "tap":
        has_coords = (
            "x" in params
            and "y" in params
            and isinstance(params["x"], int)
            and isinstance(params["y"], int)
        )
        has_node = "node_id" in params and isinstance(params["node_id"], str)
        if not (has_coords or has_node):
            return "tap requires either (x, y) ints or node_id string"
    elif verb == "type":
        if "text" not in params or not isinstance(params["text"], str):
            return "type requires string 'text'"
    elif verb == "swipe":
        direction = params.get("direction")
        if direction not in ("up", "down", "left", "right"):
            return "swipe requires direction in up|down|left|right"
    elif verb == "press_key":
        key = params.get("key")
        if not isinstance(key, str) or not key:
            return "press_key requires non-empty 'key' string"
    # `done` accepts anything (including an empty dict).
    return None
