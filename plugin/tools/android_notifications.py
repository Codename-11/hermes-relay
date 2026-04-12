"""
hermes-relay plugin — Android notifications tool.

Registers ``android_notifications_recent`` into the hermes-agent tool
registry. The tool reads the bounded in-memory deque maintained by the
relay's :class:`NotificationsChannel`, populated when the user has
explicitly granted Android's notification-listener permission to the
companion phone app.

Permission model:
  * Off by default. The user must explicitly enable Hermes-Relay in
    Android Settings → Notification access. This is the same opt-in
    Wear OS, Android Auto, and Tasker have used for over a decade.
  * The agent only sees what the phone has chosen to share — there is
    no remote-trigger path that can re-enable the listener if the user
    revokes it.
  * The cache is in-memory only on the relay; restarting the relay
    drops everything.

Calls the relay over loopback (127.0.0.1) and falls through to a clean
JSON error envelope on any failure so the LLM can render "I don't have
notifications enabled" as natural language instead of crashing.
"""

import json
import os
from typing import Optional

import urllib.error
import urllib.request


def _relay_url() -> str:
    """Base URL of the local relay. Honours ``RELAY_PORT`` if set."""
    port = os.getenv("RELAY_PORT", "8767")
    return f"http://127.0.0.1:{port}"


def _timeout() -> float:
    return float(os.getenv("RELAY_TOOL_TIMEOUT", "5"))


def android_notifications_recent(limit: int = 20) -> str:
    """List recent notifications the user has shared with this assistant.

    Returns the most-recent ``limit`` notifications cached on the
    relay (capped at 100). Each entry has package_name, title, text,
    sub_text, posted_at (epoch ms), and key. Returns a structured
    error JSON envelope if the relay is unreachable or the user has
    not granted notification access on their phone.
    """
    # Clamp the limit defensively — the LLM may try silly values.
    if not isinstance(limit, int) or limit < 1:
        limit = 20
    if limit > 100:
        limit = 100

    url = f"{_relay_url()}/notifications/recent?limit={limit}"
    req = urllib.request.Request(url, method="GET")

    try:
        with urllib.request.urlopen(req, timeout=_timeout()) as resp:
            if resp.status != 200:
                return json.dumps(
                    {
                        "status": "error",
                        "message": f"Relay returned HTTP {resp.status}",
                    }
                )
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return json.dumps(
            {
                "status": "error",
                "message": f"Relay HTTP {exc.code}: {exc.reason}",
            }
        )
    except (urllib.error.URLError, OSError) as exc:
        return json.dumps(
            {
                "status": "error",
                "message": (
                    "Cannot reach hermes-relay on loopback. Is the relay "
                    f"running? ({exc})"
                ),
            }
        )

    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return json.dumps(
            {"status": "error", "message": "Relay returned non-JSON body"}
        )

    notifications = data.get("notifications") or []
    return json.dumps(
        {
            "status": "ok",
            "count": len(notifications),
            "notifications": notifications,
        }
    )


# ── Tool schema ─────────────────────────────────────────────────────────────

_SCHEMAS = {
    "android_notifications_recent": {
        "name": "android_notifications_recent",
        "description": (
            "List recent notifications the user has shared with this "
            "assistant. Returns up to `limit` of the most recent "
            "notifications the user's phone has forwarded after they "
            "opted in via Android's notification-access permission. "
            "Use this when the user asks 'what came in while I was "
            "away?' or 'summarize my unread messages'. Returns "
            "package_name, title, text, sub_text, posted_at (epoch "
            "ms), and key for each entry. Empty list means either no "
            "recent notifications or the user has not granted "
            "notification access yet."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": (
                        "Maximum number of notifications to return "
                        "(1-100, default 20, newest first)"
                    ),
                    "default": 20,
                },
            },
            "required": [],
        },
    },
}


# ── Tool handlers map ───────────────────────────────────────────────────────

_HANDLERS = {
    "android_notifications_recent": (
        lambda args, **kw: android_notifications_recent(**args)
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
            # relay's in-memory cache directly via loopback HTTP.
            check_fn=lambda: True,
            requires_env=[],
        )
except ImportError:
    # Running outside hermes-agent context (e.g. tests).
    pass
