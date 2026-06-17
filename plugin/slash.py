"""In-session ``/relay`` slash command for the hermes-relay plugin.

Registered via ``ctx.register_command("relay", handler)`` so it is usable
mid-conversation from any platform (CLI, Telegram, Discord, TUI, …). The
host calls the handler with a single ``raw_args: str`` and expects a
``str | None`` reply suitable for a chat message.

Subcommands (parsed out of ``raw_args`` by :func:`relay_slash_handler`):

    /relay status       Relay reachability + connected-phone summary
    /relay devices      Paired-device list (loopback ``GET /sessions``)
    /relay pair         Mint a fresh 6-char pairing code on the running relay
    /relay help         This help text

All of the real work is delegated to the existing relay logic
(:mod:`plugin.status`, :mod:`plugin.pair`, and the relay's loopback HTTP
surface) so this module stays a thin chat-facing veneer. Every handler is
fully ``try/except``-guarded: a failure returns a friendly one-line message,
never an exception into the host dispatch loop.
"""

from __future__ import annotations

import json
import logging
import os
import urllib.error
import urllib.request
from typing import Any, Optional

logger = logging.getLogger(__name__)


# ── Shared helpers ────────────────────────────────────────────────────────────


def _relay_port() -> int:
    """Resolve the loopback relay port (``$RELAY_PORT`` or the 8767 default)."""
    raw = (os.environ.get("RELAY_PORT") or "").strip()
    if raw:
        try:
            return int(raw)
        except ValueError:
            logger.debug("Ignoring invalid RELAY_PORT=%r; using 8767", raw)
    return 8767


def _fmt_age(seconds: Optional[float]) -> str:
    """Render a seconds-ago value as a compact human string."""
    if not isinstance(seconds, (int, float)) or seconds < 0:
        return "?"
    s = int(seconds)
    if s < 60:
        return f"{s}s ago"
    if s < 3600:
        return f"{s // 60}m ago"
    if s < 86400:
        return f"{s // 3600}h ago"
    return f"{s // 86400}d ago"


# ── /relay status ─────────────────────────────────────────────────────────────


def _cmd_status() -> str:
    """Concise relay-reachability + phone-connection summary for chat."""
    # Reuse the canonical read-only fetcher from the status CLI so the
    # chat reply and `hermes-status` agree on parsing/exit semantics.
    from .status import (
        EXIT_NO_PHONE,
        EXIT_OK,
        EXIT_RELAY_UNREACHABLE,
        fetch_status,
    )

    port = _relay_port()
    code, data, err = fetch_status(port, timeout_s=2.0)

    if code == EXIT_RELAY_UNREACHABLE:
        return (
            f"Relay unreachable on 127.0.0.1:{port} "
            f"({err or 'not running'}). Start it with "
            "`systemctl --user start hermes-relay`."
        )

    data = data or {}
    if code == EXIT_NO_PHONE or not data.get("phone_connected"):
        reason = data.get("error") or "no phone connected"
        return f"Relay is up on :{port}, but no phone is connected ({reason})."

    # Connected — surface the device line + a couple of quick facts.
    parts = ["Relay up — phone connected."]
    device = data.get("device") if isinstance(data.get("device"), dict) else {}
    name = device.get("name")
    battery = device.get("battery_percent")
    last_seen = data.get("last_seen_seconds_ago")
    if name:
        parts.append(f"Device: {name}.")
    if isinstance(battery, (int, float)):
        parts.append(f"Battery: {int(battery)}%.")
    if isinstance(last_seen, (int, float)):
        parts.append(f"Last seen {_fmt_age(last_seen)}.")
    return " ".join(parts)


# ── /relay devices ────────────────────────────────────────────────────────────


def _cmd_devices() -> str:
    """List paired devices via the relay's loopback ``GET /sessions``.

    Loopback callers without a bearer get the full list (see
    ``handle_sessions_list`` in the relay server); the response only ever
    carries token *prefixes*, never full tokens.
    """
    port = _relay_port()
    url = f"http://127.0.0.1:{port}/sessions"
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=2.0) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError) as exc:
        return (
            f"Could not list devices — relay unreachable on 127.0.0.1:{port} "
            f"({exc})."
        )

    sessions = payload.get("sessions") if isinstance(payload, dict) else None
    if not isinstance(sessions, list) or not sessions:
        return "No paired devices. Run `/relay pair` to mint a pairing code."

    lines = [f"Paired devices ({len(sessions)}):"]
    for s in sessions:
        if not isinstance(s, dict):
            continue
        name = s.get("device_name") or "unknown device"
        prefix = s.get("token_prefix") or "????????"
        surface = s.get("client_surface") or ""
        last = _fmt_age(_seconds_since(s.get("last_seen")))
        suffix = f" [{surface}]" if surface else ""
        lines.append(f"  • {name}{suffix} — {prefix}… (seen {last})")
    return "\n".join(lines)


def _seconds_since(epoch_ts: Any) -> Optional[float]:
    """Convert an absolute epoch ``last_seen`` to seconds-ago (best effort)."""
    if not isinstance(epoch_ts, (int, float)):
        return None
    import time

    delta = time.time() - float(epoch_ts)
    return delta if delta >= 0 else 0.0


# ── /relay pair ───────────────────────────────────────────────────────────────


def _cmd_pair() -> str:
    """Mint a fresh pairing code on the running relay (loopback only).

    Uses the exact same path as ``hermes pair --register-code``: generate a
    6-char code locally, then pre-register it with the relay via the
    loopback-only ``POST /pairing/register`` endpoint. The phone claims the
    code to complete pairing.
    """
    from .pair import _generate_relay_code, register_relay_code

    port = _relay_port()
    code = _generate_relay_code()
    ok = register_relay_code(port, code, timeout_s=2.0)
    if not ok:
        return (
            f"Could not mint a pairing code — relay not reachable on "
            f"127.0.0.1:{port}, or pairing registration failed. Is "
            "`hermes-relay` running on this host?"
        )
    return (
        f"Pairing code: {code}\n"
        "Enter it in the Hermes-Relay app (Pair → enter code) within the "
        "pairing window. For a scannable QR, run `hermes pair` in a terminal "
        "on the relay host."
    )


# ── Help + dispatch ───────────────────────────────────────────────────────────

_HELP = (
    "/relay — Hermes-Relay control\n"
    "  status   Relay reachability + connected-phone summary\n"
    "  devices  List paired devices\n"
    "  pair     Mint a fresh 6-char pairing code\n"
    "  help     Show this help"
)


def relay_slash_handler(raw_args: str) -> str:
    """Dispatch ``/relay <subcommand>`` to the matching handler.

    Signature matches the upstream ``register_command`` contract:
    ``fn(raw_args: str) -> str | None``. Always returns a string; never
    raises — each subcommand is independently guarded so a transient relay
    failure becomes a friendly chat reply instead of a host-loop exception.
    """
    argv = (raw_args or "").strip().split()
    sub = argv[0].lower() if argv else "status"

    try:
        if sub in ("help", "-h", "--help", "?"):
            return _HELP
        if sub == "status":
            return _cmd_status()
        if sub == "devices":
            return _cmd_devices()
        if sub == "pair":
            return _cmd_pair()
        return f"Unknown subcommand '{sub}'.\n\n{_HELP}"
    except Exception as exc:  # noqa: BLE001 — never throw into host dispatch
        logger.warning("/relay %s failed: %s", sub, exc, exc_info=True)
        return f"`/relay {sub}` failed: {exc}"


def register_slash_commands(ctx) -> None:
    """Register the ``/relay`` slash command with the plugin host.

    Guarded so older hermes-agent builds without ``register_command`` (or a
    transient registration failure) cannot break tool/CLI registration in
    :func:`plugin.register`.
    """
    try:
        ctx.register_command(
            "relay",
            handler=relay_slash_handler,
            description="Hermes-Relay status, paired devices, and pairing.",
            args_hint="status|devices|pair",
        )
    except (AttributeError, TypeError) as exc:
        # Older hermes-agent without register_command, or a signature
        # mismatch — degrade silently; the `hermes relay` CLI still works.
        logger.debug("register_command unavailable; skipping /relay: %s", exc)
