"""
hermes-relay plugin — ``android_navigate`` vision-driven navigation tool.

Loop:
    1. screenshot the phone via the Android bridge relay
    2. ask a vision-capable LLM for the next single action toward ``intent``
    3. execute the action against the bridge relay
    4. repeat until the model says ``done`` or we hit ``max_iterations``

Hard constraint from the Phase 3 / Tier 4 plan: **never continuous
capture**. A screenshot is taken exactly once per iteration, only when
``android_navigate`` is invoked. Default iteration cap is 5.

Unlike the other android_* tools, this one does not map 1:1 onto a
bridge endpoint — it orchestrates several bridge calls per invocation.
It shares the same transport layer as ``android_tool.py`` (``_bridge_url``,
``_auth_headers``, ``ANDROID_BRIDGE_URL``) so env + pairing config stays
single-sourced.

LLM integration (see "known gap" below) currently stubs out to a
``HERMES_NAVIGATE_STUB_REPLY`` env var when no host-side client is
wired up. This keeps the loop testable and makes the tool safe to
register — the registration never blows up on missing LLM deps, it
just returns an informative error envelope on first call.
"""

from __future__ import annotations

import json
import logging
import os
import time
from dataclasses import asdict, dataclass, field
from typing import Any, Callable, Optional

import requests

from .android_navigate_prompt import ParsedAction, build_prompt, parse_response

logger = logging.getLogger("hermes_relay.tools.android_navigate")

# ── Config (shared with android_tool.py conventions) ─────────────────────────


def _bridge_url() -> str:
    return os.getenv("ANDROID_BRIDGE_URL", "http://localhost:8766")


def _bridge_token() -> Optional[str]:
    return os.getenv("ANDROID_BRIDGE_TOKEN")


def _timeout() -> float:
    return float(os.getenv("ANDROID_BRIDGE_TIMEOUT", "30"))


def _auth_headers() -> dict:
    token = _bridge_token()
    if token:
        return {"Authorization": f"Bearer {token}"}
    return {}


def _check_requirements() -> bool:
    try:
        r = requests.get(
            f"{_bridge_url()}/ping", headers=_auth_headers(), timeout=2
        )
        if r.status_code == 200:
            data = r.json()
            return data.get("phone_connected", False) or data.get(
                "accessibilityService", False
            )
        return False
    except Exception:
        return False


def _get(path: str) -> dict:
    r = requests.get(
        f"{_bridge_url()}{path}", headers=_auth_headers(), timeout=_timeout()
    )
    r.raise_for_status()
    return r.json()


def _post(path: str, payload: dict) -> dict:
    r = requests.post(
        f"{_bridge_url()}{path}",
        json=payload,
        headers=_auth_headers(),
        timeout=_timeout(),
    )
    r.raise_for_status()
    return r.json()


# ── Iteration cap + safety ────────────────────────────────────────────────────

#: Default and hard upper bound on the number of vision→action iterations.
#: The plan (Phase 3 / Tier 4) specifies default 5 with a hard cap so the
#: agent cannot accidentally burn battery / screen-capture budget.
DEFAULT_MAX_ITERATIONS = 5
ABSOLUTE_MAX_ITERATIONS = 20


@dataclass
class Step:
    """One iteration of the navigate loop, for the returned trace."""

    step: int
    action: str
    params: dict[str, Any]
    screenshot_token: str
    reasoning: str
    result: dict[str, Any] = field(default_factory=dict)


# ── LLM integration (known gap) ──────────────────────────────────────────────
#
# The Phase 3 plan specifies "vision-first navigation" but is intentionally
# silent on *which* model. The hermes-relay plugin has no published LLM
# client surface — upstream ``tools/*.py`` import Anthropic/OpenAI SDKs
# directly using keys from ``~/.hermes/.env``, and the gateway's own run
# machinery is not re-entrant from inside a tool (calling the agent from
# a tool call would deadlock the run loop).
#
# Rather than invent a new LLM client and ship an opinionated default,
# this module defines an injection point: ``call_vision_model``. Tests
# pass in a fake. Production wiring can either:
#
#   (a) Set ``HERMES_NAVIGATE_STUB_REPLY`` env var to a canned reply for
#       smoke-testing the loop end to end, OR
#   (b) Replace ``_default_vision_model`` with a real client (e.g.
#       Anthropic's ``messages.create`` with a base64 image block).
#
# Until (b) lands, calling ``android_navigate`` against a live phone
# returns an ``error`` envelope with ``llm_gap`` set so the agent sees
# exactly why the loop cannot run. This is better than silently
# faking actions or crashing on import.


def _default_vision_model(
    prompt: str, screenshot_path: str, screenshot_token: str | None
) -> str:
    """Stub LLM caller.

    Honours ``HERMES_NAVIGATE_STUB_REPLY`` if set (used by smoke tests +
    server-side dry runs). Otherwise raises ``NotImplementedError`` which
    the loop catches and converts into a structured error envelope.

    When we wire a real vision model, the replacement lives here and
    should:
      * Read ``prompt`` as the text input
      * Attach ``screenshot_path`` (local JPEG) as a vision content block
      * Return the raw model reply as a ``str`` suitable for
        ``parse_response``.
    """

    stub = os.getenv("HERMES_NAVIGATE_STUB_REPLY")
    if stub is not None:
        logger.info(
            "android_navigate: using HERMES_NAVIGATE_STUB_REPLY (len=%d)",
            len(stub),
        )
        return stub

    raise NotImplementedError(
        "android_navigate has no vision-model client wired up yet. "
        "Set HERMES_NAVIGATE_STUB_REPLY for dry-run testing or replace "
        "plugin.tools.android_navigate._default_vision_model with a real "
        "LLM call. See the module docstring for the integration contract."
    )


#: The loop calls this. Tests patch it; production replaces
#: ``_default_vision_model`` or sets the env var.
call_vision_model: Callable[[str, str, str | None], str] = _default_vision_model


# ── Screenshot handling ───────────────────────────────────────────────────────


@dataclass
class _Screenshot:
    token: str  # "hermes-relay://<token>" style marker, or "<fallback>"
    local_path: str  # absolute filesystem path to the JPEG, for the LLM


def _capture_screenshot() -> _Screenshot:
    """Call the bridge ``/screenshot`` endpoint and persist the bytes.

    We deliberately reimplement the relay-registration dance here rather
    than calling ``android_screenshot()`` from ``android_tool.py`` — that
    function returns a user-facing string which mixes MEDIA markers with
    an error-JSON envelope. The navigate loop needs structured data.
    """

    import base64
    import tempfile

    raw = _get("/screenshot")
    if "error" in raw:
        raise RuntimeError(f"bridge /screenshot error: {raw['error']}")

    # The bridge wraps its response in {"data": {...}} in some builds and
    # returns a flat object in others — match android_tool.py's tolerant
    # shape-check.
    result = raw.get("data", raw)
    img_b64 = result.get("image") or ""
    if not img_b64:
        raise RuntimeError("bridge /screenshot returned no image data")

    img_bytes = base64.b64decode(img_b64)
    tmp = tempfile.NamedTemporaryFile(
        suffix=".jpg", prefix="android_navigate_", delete=False
    )
    try:
        tmp.write(img_bytes)
    finally:
        tmp.close()

    # Try to register with the local relay for an opaque token. Fall
    # back to the bare path marker if the relay isn't reachable (same
    # graceful degradation as android_screenshot).
    token_marker = f"file://{tmp.name}"
    try:
        from plugin.relay.client import register_media  # type: ignore

        token = register_media(tmp.name, "image/jpeg", file_name="nav_step.jpg")
        if token:
            token_marker = f"hermes-relay://{token}"
    except Exception:
        logger.debug(
            "register_media unavailable — navigate trace will use file:// marker",
            exc_info=True,
        )

    return _Screenshot(token=token_marker, local_path=tmp.name)


def _read_accessibility_tree() -> str:
    """Best-effort bridge ``/screen`` fetch for the prompt.

    Never raises — the vision model can work from the screenshot alone
    if the accessibility tree is unavailable, so we just log and return
    an empty string on any failure.
    """

    try:
        data = _get("/screen")
        return json.dumps(data)[:8000]  # hard clip to keep prompts compact
    except Exception as exc:
        logger.debug("accessibility /screen fetch failed: %s", exc)
        return ""


# ── Action execution ──────────────────────────────────────────────────────────


def _execute_action(action: str, params: dict[str, Any]) -> dict[str, Any]:
    """Dispatch a parsed action to the matching bridge endpoint.

    Returns the JSON body the bridge responded with. Raises on HTTP
    errors so the loop can capture them into the trace.
    """

    if action == "tap_text":
        payload = {"text": params["text"]}
        if "exact" in params:
            payload["exact"] = bool(params["exact"])
        return _post("/tap_text", payload)

    if action == "tap":
        payload: dict[str, Any] = {}
        if "node_id" in params:
            payload["nodeId"] = params["node_id"]
        else:
            payload["x"] = int(params["x"])
            payload["y"] = int(params["y"])
        return _post("/tap", payload)

    if action == "type":
        payload = {"text": params["text"]}
        if params.get("clear_first"):
            payload["clearFirst"] = True
        return _post("/type", payload)

    if action == "swipe":
        payload = {
            "direction": params["direction"],
            "distance": params.get("distance", "medium"),
        }
        return _post("/swipe", payload)

    if action == "press_key":
        return _post("/press_key", {"key": params["key"]})

    # ``done`` and ``error`` are handled upstream, not here.
    raise ValueError(f"_execute_action received non-executable verb: {action!r}")


# ── Main entry point ──────────────────────────────────────────────────────────


def android_navigate(intent: str, max_iterations: int = DEFAULT_MAX_ITERATIONS) -> str:
    """Close-the-loop vision navigation.

    Args:
        intent: natural-language description of the user's goal, e.g.
            ``"compose a tweet about rainy weather"``.
        max_iterations: hard cap on the number of screenshot → model →
            action cycles before giving up. Default 5, hard-clamped to
            ``ABSOLUTE_MAX_ITERATIONS``. Use this to bound battery /
            capture budget.

    Returns:
        JSON string. On success::

            {"status": "ok", "done": true, "iterations": 3, "trace": [...]}

        On iteration cap::

            {"status": "error", "reason": "iteration_cap",
             "iterations": N, "trace": [...]}

        On any other failure (bridge down, malformed model reply, LLM
        stub not wired)::

            {"status": "error", "reason": "<short>", "trace": [...]}
    """

    if not isinstance(intent, str) or not intent.strip():
        return json.dumps({"status": "error", "reason": "empty intent", "trace": []})

    if not isinstance(max_iterations, int) or max_iterations < 1:
        max_iterations = DEFAULT_MAX_ITERATIONS
    max_iterations = min(max_iterations, ABSOLUTE_MAX_ITERATIONS)

    trace: list[Step] = []

    for step_num in range(1, max_iterations + 1):
        # 1. screenshot
        try:
            shot = _capture_screenshot()
        except Exception as exc:
            logger.exception("android_navigate: screenshot failed on step %d", step_num)
            return _fail("screenshot_failed", str(exc), trace)

        tree = _read_accessibility_tree()
        prompt = build_prompt(
            intent=intent,
            step=step_num,
            max_steps=max_iterations,
            accessibility_tree=tree,
        )

        # 2. ask the model
        try:
            reply = call_vision_model(prompt, shot.local_path, shot.token)
        except NotImplementedError as exc:
            return _fail("llm_gap", str(exc), trace)
        except Exception as exc:
            logger.exception("android_navigate: vision model failed on step %d", step_num)
            return _fail("llm_error", str(exc), trace)

        parsed: ParsedAction = parse_response(reply)

        if parsed.action == "error":
            trace.append(
                Step(
                    step=step_num,
                    action="error",
                    params={},
                    screenshot_token=shot.token,
                    reasoning=parsed.reasoning,
                    result={"raw_reply": parsed.raw[:500]},
                )
            )
            return _fail("parse_error", parsed.reasoning, trace)

        # 3. done short-circuit
        if parsed.action == "done":
            trace.append(
                Step(
                    step=step_num,
                    action="done",
                    params=parsed.params,
                    screenshot_token=shot.token,
                    reasoning=parsed.reasoning or "intent reached",
                )
            )
            return json.dumps(
                {
                    "status": "ok",
                    "done": True,
                    "iterations": step_num,
                    "trace": [asdict(s) for s in trace],
                }
            )

        # 4. execute the action
        try:
            result = _execute_action(parsed.action, parsed.params)
        except Exception as exc:
            logger.exception(
                "android_navigate: executing %s on step %d failed",
                parsed.action,
                step_num,
            )
            trace.append(
                Step(
                    step=step_num,
                    action=parsed.action,
                    params=parsed.params,
                    screenshot_token=shot.token,
                    reasoning=parsed.reasoning,
                    result={"error": str(exc)},
                )
            )
            return _fail("action_failed", str(exc), trace)

        trace.append(
            Step(
                step=step_num,
                action=parsed.action,
                params=parsed.params,
                screenshot_token=shot.token,
                reasoning=parsed.reasoning,
                result=result,
            )
        )

        # Tiny settle delay so the next screenshot reflects the UI change.
        # Kept small (200 ms) — operators who need a longer wait should
        # use the explicit `android_wait` tool between navigate calls.
        time.sleep(0.2)

    # Fell through the loop without `done`.
    return _fail("iteration_cap", f"exceeded {max_iterations} steps", trace)


def _fail(reason: str, detail: str, trace: list[Step]) -> str:
    return json.dumps(
        {
            "status": "error",
            "reason": reason,
            "detail": detail,
            "iterations": len(trace),
            "trace": [asdict(s) for s in trace],
        }
    )


# ── Schema + registry registration ────────────────────────────────────────────

_SCHEMAS = {
    "android_navigate": {
        "name": "android_navigate",
        "description": (
            "Vision-driven Android navigation. Give a high-level natural-"
            "language intent (e.g. 'compose a tweet about rainy weather') "
            "and this tool will screenshot the current screen, ask a vision "
            "model for the next single action, execute it, and repeat until "
            "the model says the goal is reached or the iteration cap is hit. "
            "Never continuously captures — one screenshot per iteration, only "
            "when you call this tool. Default cap is 5 iterations. Returns a "
            "JSON trace of each step so you can debug failures."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "intent": {
                    "type": "string",
                    "description": (
                        "Natural-language description of the goal, e.g. "
                        "'open Spotify and play Discovery Weekly'."
                    ),
                },
                "max_iterations": {
                    "type": "integer",
                    "description": (
                        "Max number of vision→action cycles before giving up. "
                        f"Default {DEFAULT_MAX_ITERATIONS}, hard-clamped to "
                        f"{ABSOLUTE_MAX_ITERATIONS}."
                    ),
                    "default": DEFAULT_MAX_ITERATIONS,
                    "minimum": 1,
                    "maximum": ABSOLUTE_MAX_ITERATIONS,
                },
            },
            "required": ["intent"],
        },
    },
}

_HANDLERS = {
    "android_navigate": lambda args, **kw: android_navigate(**args),
}


try:
    from tools.registry import registry  # type: ignore

    for tool_name, schema in _SCHEMAS.items():
        registry.register(
            name=tool_name,
            toolset="android",
            schema=schema,
            handler=_HANDLERS[tool_name],
            check_fn=_check_requirements,
            requires_env=[],
        )
except ImportError:
    # Running outside hermes-agent context (e.g. plugin tests)
    pass
