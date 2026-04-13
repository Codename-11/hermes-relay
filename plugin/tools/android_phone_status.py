"""
hermes-relay plugin — Android phone status tool.

Registers ``android_phone_status`` into the hermes-agent tool registry.
The tool reads the live phone state the relay maintains about the
connected Hermes-Relay Android app — battery, screen, current
foreground app, which bridge permissions have been granted, and what
safety rails are currently configured.

The agent calls this mid-conversation to decide whether it can
meaningfully call other bridge tools (e.g. ``android_tap_text``,
``android_screenshot``) before making blind calls that would just fail
with "phone not connected" or "accessibility not granted".

Permission / trust model:
  * Read-only — the tool only observes state, it never mutates anything.
  * Loopback-only — calls ``http://127.0.0.1:<RELAY_PORT>/bridge/status``
    over loopback, no bearer auth required (same trust model as
    ``/media/register``, ``/pairing/register``, ``/notifications/recent``).
  * The relay endpoint itself returns 503 when no phone has ever
    connected; we translate that into a clean
    ``{"phone_connected": false}`` envelope so the LLM can render it
    as natural language instead of a stack trace.
"""

from __future__ import annotations

import json
import os

import urllib.error
import urllib.request


def _relay_url() -> str:
    """Base URL of the local relay. Honours ``RELAY_PORT`` if set."""
    port = os.getenv("RELAY_PORT", "8767")
    return f"http://127.0.0.1:{port}"


def _timeout() -> float:
    return float(os.getenv("RELAY_TOOL_TIMEOUT", "5"))


def android_phone_status() -> str:
    """Return the current state of the paired Hermes-Relay phone.

    Returns a JSON envelope:

    On success (phone connected or known-disconnected)::

        {"status": "ok", "phone_status": {...}, "error": null}

    On relay unreachable / network failure::

        {"status": "error", "phone_status": null,
         "error": "relay unreachable"}

    The ``phone_status`` payload when present mirrors the relay's
    ``GET /bridge/status`` response verbatim — see CLAUDE.md for the
    full JSON contract. Key fields: ``phone_connected`` (bool),
    ``last_seen_seconds_ago`` (int), ``device`` (name/battery/screen/
    current_app), ``bridge`` (permission flags), ``safety`` (blocklist
    counts + auto-disable timer).
    """
    url = f"{_relay_url()}/bridge/status"
    req = urllib.request.Request(url, method="GET")

    raw: str | None = None
    try:
        with urllib.request.urlopen(req, timeout=_timeout()) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        # 503 from the relay means "no phone has connected yet". Body
        # is a JSON envelope shaped like
        #   {"phone_connected": false, "error": "no phone connected"}
        # which is not an error from the agent's perspective — the
        # phone just isn't online. Surface it as status=ok so the LLM
        # renders "your phone isn't connected right now" in prose.
        if exc.code == 503:
            try:
                body = exc.read().decode("utf-8")
                data = json.loads(body) if body else {}
            except (OSError, ValueError, json.JSONDecodeError):
                data = {}
            if not isinstance(data, dict):
                data = {}
            data.setdefault("phone_connected", False)
            data.setdefault("error", "no phone connected")
            return json.dumps(
                {"status": "ok", "phone_status": data, "error": None}
            )
        return json.dumps(
            {
                "status": "error",
                "phone_status": None,
                "error": f"relay HTTP {exc.code}: {exc.reason}",
            }
        )
    except (urllib.error.URLError, OSError):
        # Connection refused, DNS fail, timeout — relay is not running
        # or not reachable on loopback.
        return json.dumps(
            {
                "status": "error",
                "phone_status": None,
                "error": "relay unreachable",
            }
        )

    if raw is None:
        return json.dumps(
            {
                "status": "error",
                "phone_status": None,
                "error": "relay returned empty body",
            }
        )

    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return json.dumps(
            {
                "status": "error",
                "phone_status": None,
                "error": "relay returned non-JSON body",
            }
        )

    if not isinstance(data, dict):
        return json.dumps(
            {
                "status": "error",
                "phone_status": None,
                "error": "relay returned non-object JSON",
            }
        )

    return json.dumps(
        {"status": "ok", "phone_status": data, "error": None}
    )


# ── Tool schema ─────────────────────────────────────────────────────────────

_SCHEMAS = {
    "android_phone_status": {
        "name": "android_phone_status",
        "description": (
            "Get the current state of the user's paired Hermes-Relay "
            "Android phone. Returns whether the phone is connected, "
            "how recently it was seen, device info (name, battery, "
            "screen on/off, foreground app), which bridge permissions "
            "have been granted (accessibility, screen capture, overlay, "
            "notification listener), and the current safety-rail "
            "configuration (blocklist size, destructive-verb count, "
            "auto-disable idle timer). Call this BEFORE attempting "
            "bridge operations like tapping, typing, or screenshots so "
            "you know whether the phone is reachable and has the "
            "permissions needed. If `phone_connected` is false or a "
            "required `bridge.*_granted` flag is false, explain the "
            "situation to the user instead of calling the bridge tool "
            "blindly."
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
}


# ── Tool handlers map ───────────────────────────────────────────────────────

_HANDLERS = {
    "android_phone_status": (
        lambda args, **kw: android_phone_status()
    ),
}


# ── Registry registration ──────────────────────────────────────────────────

try:
    from tools.registry import registry  # type: ignore[import-not-found]

    for tool_name, schema in _SCHEMAS.items():
        registry.register(
            name=tool_name,
            toolset="android",
            schema=schema,
            handler=_HANDLERS[tool_name],
            # No bridge connectivity required — this tool reads the
            # relay's live state directly via loopback HTTP. It's also
            # the tool the agent uses to CHECK whether bridge
            # connectivity is available, so gating it on that would be
            # circular.
            check_fn=lambda: True,
            requires_env=[],
        )
except ImportError:
    # Running outside hermes-agent context (e.g. tests).
    pass
