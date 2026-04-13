"""Hermes-Relay phone status CLI — pretty-prints ``GET /bridge/status``.

The symmetric counterpart to ``plugin.pair``: that tool writes phone
configuration, this tool reads phone state. Consumes the relay's
``/bridge/status`` loopback endpoint and renders it as a human-friendly
text block for operators, or raw JSON when piped / scripted.

Exit codes:
  * ``0`` — relay reachable, phone connected.
  * ``1`` — relay unreachable (not running, wrong port, loopback blocked).
  * ``2`` — relay reachable but no phone has ever connected (the 503 case).

Usage::

    python -m plugin.status             # pretty ANSI output
    python -m plugin.status --json      # raw JSON pass-through
    python -m plugin.status --port 9000 # override RELAY_PORT
    hermes-status                       # shell shim (installs to ~/.local/bin)
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Optional


# ── Exit codes ──────────────────────────────────────────────────────────────

EXIT_OK = 0
EXIT_RELAY_UNREACHABLE = 1
EXIT_NO_PHONE = 2


# ── Low-level fetch ─────────────────────────────────────────────────────────


def fetch_status(port: int, timeout_s: float = 5.0) -> tuple[int, Optional[dict], Optional[str]]:
    """Fetch ``/bridge/status`` from the loopback relay.

    Returns a ``(exit_code, data, error_message)`` triple:

    * ``(EXIT_OK, data, None)``               — connected, normal success.
    * ``(EXIT_NO_PHONE, data, error)``        — relay alive, returned 503,
      body carries the phone-disconnected envelope.
    * ``(EXIT_RELAY_UNREACHABLE, None, msg)`` — relay can't be reached
      on loopback at all.
    """
    url = f"http://127.0.0.1:{port}/bridge/status"
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        if exc.code == 503:
            try:
                body = exc.read().decode("utf-8")
                data = json.loads(body) if body else {}
            except (OSError, ValueError, json.JSONDecodeError):
                data = {}
            if not isinstance(data, dict):
                data = {}
            data.setdefault("phone_connected", False)
            msg = data.get("error") or "no phone connected"
            return EXIT_NO_PHONE, data, str(msg)
        return (
            EXIT_RELAY_UNREACHABLE,
            None,
            f"relay HTTP {exc.code}: {exc.reason}",
        )
    except (urllib.error.URLError, OSError) as exc:
        return EXIT_RELAY_UNREACHABLE, None, f"relay unreachable: {exc}"

    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        return (
            EXIT_RELAY_UNREACHABLE,
            None,
            f"relay returned non-JSON body: {exc}",
        )
    if not isinstance(data, dict):
        return (
            EXIT_RELAY_UNREACHABLE,
            None,
            "relay returned non-object JSON",
        )
    return EXIT_OK, data, None


# ── Rendering ───────────────────────────────────────────────────────────────


class _Palette:
    """ANSI color codes. All fields are empty strings when disabled."""

    def __init__(self, enabled: bool) -> None:
        if enabled:
            self.reset = "\033[0m"
            self.bold = "\033[1m"
            self.dim = "\033[2m"
            self.green = "\033[32m"
            self.red = "\033[31m"
            self.yellow = "\033[33m"
            self.cyan = "\033[36m"
        else:
            self.reset = self.bold = self.dim = ""
            self.green = self.red = self.yellow = self.cyan = ""

    def ok(self, s: str) -> str:
        return f"{self.green}{s}{self.reset}"

    def bad(self, s: str) -> str:
        return f"{self.red}{s}{self.reset}"

    def warn(self, s: str) -> str:
        return f"{self.yellow}{s}{self.reset}"

    def label(self, s: str) -> str:
        return f"{self.bold}{s}{self.reset}"


def _yn(value: object, palette: _Palette) -> str:
    """Render a boolean as a colored yes/no."""
    if value is True:
        return palette.ok("yes")
    if value is False:
        return palette.bad("no")
    return palette.warn("unknown")


def _granted(value: object, palette: _Palette) -> str:
    if value is True:
        return palette.ok("granted")
    if value is False:
        return palette.bad("not granted")
    return palette.warn("unknown")


def _enabled(value: object, palette: _Palette) -> str:
    if value is True:
        return palette.ok("enabled")
    if value is False:
        return palette.bad("disabled")
    return palette.warn("unknown")


def _on_off(value: object, palette: _Palette) -> str:
    if value is True:
        return palette.ok("on")
    if value is False:
        return palette.dim + "off" + palette.reset
    return palette.warn("unknown")


def render_status_block(data: dict, palette: _Palette) -> str:
    """Render the full /bridge/status envelope as a pretty text block."""
    lines: list[str] = []
    lines.append("")
    lines.append("  " + palette.label("Hermes-Relay phone status"))
    lines.append("  " + "─" * 25)
    lines.append("")

    connected = data.get("phone_connected")
    last_seen = data.get("last_seen_seconds_ago")
    if connected is True:
        if isinstance(last_seen, (int, float)):
            lines.append(
                f"  Connected:  {_yn(True, palette)} (last seen {int(last_seen)}s ago)"
            )
        else:
            lines.append(f"  Connected:  {_yn(True, palette)}")
    elif connected is False:
        err = data.get("error") or "no phone connected"
        lines.append(f"  Connected:  {_yn(False, palette)} ({err})")
        lines.append("")
        return "\n".join(lines)
    else:
        lines.append(f"  Connected:  {_yn(None, palette)}")

    # Device
    device = data.get("device") or {}
    if isinstance(device, dict) and device:
        lines.append("")
        lines.append("  " + palette.label("Device"))
        name = device.get("name")
        battery = device.get("battery_percent")
        screen_on = device.get("screen_on")
        current_app = device.get("current_app")
        if name:
            lines.append(f"    Name:     {name}")
        if battery is not None:
            lines.append(f"    Battery:  {battery}%")
        lines.append(f"    Screen:   {_on_off(screen_on, palette)}")
        if current_app:
            lines.append(f"    App:      {current_app}")

    # Bridge permissions
    bridge = data.get("bridge") or {}
    if isinstance(bridge, dict) and bridge:
        lines.append("")
        lines.append("  " + palette.label("Bridge"))
        lines.append(
            f"    Master:           {_enabled(bridge.get('master_enabled'), palette)}"
        )
        lines.append(
            f"    Accessibility:    {_granted(bridge.get('accessibility_granted'), palette)}"
        )
        lines.append(
            f"    Screen capture:   {_granted(bridge.get('screen_capture_granted'), palette)}"
        )
        lines.append(
            f"    Overlay:          {_granted(bridge.get('overlay_granted'), palette)}"
        )
        lines.append(
            f"    Notifications:    {_granted(bridge.get('notification_listener_granted'), palette)}"
        )

    # Safety
    safety = data.get("safety") or {}
    if isinstance(safety, dict) and safety:
        lines.append("")
        lines.append("  " + palette.label("Safety"))
        blk = safety.get("blocklist_count")
        verbs = safety.get("destructive_verbs_count")
        auto = safety.get("auto_disable_minutes")
        at_ms = safety.get("auto_disable_at_ms")
        if blk is not None:
            lines.append(f"    Blocklist:        {blk} packages")
        if verbs is not None:
            lines.append(f"    Destructive verbs: {verbs}")
        if auto is not None:
            if at_ms:
                lines.append(
                    f"    Auto-disable:     {auto} min idle (armed)"
                )
            else:
                lines.append(f"    Auto-disable:     {auto} min idle")

    lines.append("")
    return "\n".join(lines)


# ── CLI entry point ─────────────────────────────────────────────────────────


def status_command(args: argparse.Namespace) -> int:
    """Core CLI handler. Returns the process exit code."""
    port = int(args.port) if args.port else int(os.getenv("RELAY_PORT", "8767"))
    code, data, err = fetch_status(port)

    if args.json:
        # Raw JSON pass-through, scripting-friendly. When the relay
        # isn't reachable we still emit a stable envelope so callers
        # can jq over it.
        if code == EXIT_OK and data is not None:
            sys.stdout.write(json.dumps(data, separators=(",", ":")) + "\n")
        elif code == EXIT_NO_PHONE and data is not None:
            sys.stdout.write(json.dumps(data, separators=(",", ":")) + "\n")
        else:
            sys.stdout.write(
                json.dumps(
                    {
                        "phone_connected": False,
                        "error": err or "relay unreachable",
                    },
                    separators=(",", ":"),
                )
                + "\n"
            )
        return code

    palette = _Palette(sys.stdout.isatty())

    if code == EXIT_RELAY_UNREACHABLE:
        msg = err or "relay unreachable"
        sys.stderr.write(
            f"\n  {palette.bad('[error]')} Cannot reach hermes-relay on "
            f"127.0.0.1:{port}\n"
        )
        sys.stderr.write(f"  {palette.dim}{msg}{palette.reset}\n")
        sys.stderr.write(
            f"  {palette.dim}Is the relay running? "
            f"Try: systemctl --user status hermes-relay{palette.reset}\n\n"
        )
        return code

    if data is None:
        # Shouldn't happen — fetch_status always returns data for
        # non-unreachable codes — but be defensive.
        sys.stderr.write(
            f"\n  {palette.bad('[error]')} Relay returned no data\n\n"
        )
        return EXIT_RELAY_UNREACHABLE

    sys.stdout.write(render_status_block(data, palette))
    return code


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="hermes-status",
        description=(
            "Print the current state of the paired Hermes-Relay Android "
            "phone — connection, device info, bridge permissions, safety "
            "rails. Reads from the relay's /bridge/status loopback endpoint."
        ),
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit raw JSON instead of the pretty text block",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help="Override the relay port (default: $RELAY_PORT or 8767)",
    )
    args = parser.parse_args(argv)
    return status_command(args)


if __name__ == "__main__":
    sys.exit(main())
