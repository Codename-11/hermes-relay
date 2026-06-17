"""Lifecycle hooks for the hermes-relay plugin.

Registered via ``ctx.register_hook(...)``. There is exactly one hook here:
``on_session_start``, which performs a single fast, fully-guarded loopback
probe of the relay's ``/health`` endpoint and caches the result.

IMPORTANT — runs in the gateway process
---------------------------------------
``on_session_start`` is fired *synchronously* inside the agent core
(``agent/conversation_loop.py``) on the first turn of every new session.
The host already wraps each callback in its own ``try/except``, but this
handler MUST independently stay cheap and non-throwing:

  * No blocking work, no heavy I/O — only a single ``GET /health`` with a
    tight (0.5s) timeout against loopback.
  * Everything wrapped in ``try/except``; any failure is swallowed and
    logged at DEBUG so it can NEVER slow down or crash a session.
  * No network calls off-host — loopback only (127.0.0.1).

The cached snapshot is intentionally lightweight: it lets other code (or a
future context contribution) know whether the relay was reachable at
session start without re-probing. We deliberately do NOT inject context
into the turn here — keeping the hook a pure, side-effect-light observer.
"""

from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)


# Last relay-health snapshot taken at session start. Read-only for the rest
# of the process; updated only by the hook below. Shape:
#   {"reachable": bool, "checked_at": float, "version": str|None,
#    "clients": int|None, "sessions": int|None, "error": str|None}
_LAST_HEALTH: Optional[Dict[str, Any]] = None

# Tight timeout — this runs in the gateway hot path. A relay that doesn't
# answer within half a second is treated as "not reachable" and we move on.
_HEALTH_TIMEOUT_S = 0.5


def _relay_port() -> int:
    """Resolve the loopback relay port (``$RELAY_PORT`` or 8767 default)."""
    import os

    raw = (os.environ.get("RELAY_PORT") or "").strip()
    if raw:
        try:
            return int(raw)
        except ValueError:
            return 8767
    return 8767


def get_last_health() -> Optional[Dict[str, Any]]:
    """Return the most recent session-start health snapshot, if any."""
    return _LAST_HEALTH


def on_session_start(**kwargs: Any) -> None:
    """Cheap, non-throwing relay-availability probe at session start.

    Accepts ``**kwargs`` only — the host passes ``session_id``, ``model``,
    ``platform``, and a framework-injected ``telemetry_schema_version`` (and
    may add more later). We read none of them; ``**kwargs`` keeps this
    forward-compatible and prevents a signature mismatch from ever raising.

    Returns ``None`` (no context injection) so this stays a pure observer.
    """
    global _LAST_HEALTH
    port = _relay_port()
    url = f"http://127.0.0.1:{port}/health"
    snapshot: Dict[str, Any] = {
        "reachable": False,
        "checked_at": time.time(),
        "version": None,
        "clients": None,
        "sessions": None,
        "error": None,
    }
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=_HEALTH_TIMEOUT_S) as resp:
            body = resp.read().decode("utf-8")
        data = json.loads(body)
        if isinstance(data, dict):
            snapshot["reachable"] = True
            snapshot["version"] = data.get("version")
            snapshot["clients"] = data.get("clients")
            snapshot["sessions"] = data.get("sessions")
            logger.debug(
                "hermes-relay reachable at session start "
                "(v%s, clients=%s, sessions=%s)",
                snapshot["version"], snapshot["clients"], snapshot["sessions"],
            )
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError) as exc:
        # Relay not running / not reachable / bad body — all expected and
        # benign. Record it and move on; never propagate.
        snapshot["error"] = str(exc)
        logger.debug("hermes-relay not reachable at session start: %s", exc)
    except Exception as exc:  # noqa: BLE001 — belt-and-suspenders, never throw
        snapshot["error"] = str(exc)
        logger.debug("on_session_start relay probe failed: %s", exc)

    _LAST_HEALTH = snapshot
    return None


def register_hooks(ctx) -> None:
    """Register the ``on_session_start`` hook with the plugin host.

    Guarded so an older hermes-agent build without ``register_hook`` cannot
    break tool/CLI/slash registration in :func:`plugin.register`.
    """
    try:
        ctx.register_hook("on_session_start", on_session_start)
    except (AttributeError, TypeError) as exc:
        logger.debug("register_hook unavailable; skipping on_session_start: %s", exc)
