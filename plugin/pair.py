"""Hermes Android pairing — generate QR codes and connection info.

Replaces the standalone bash script `skills/hermes-pairing-qr/hermes-pair`.
Exposed as the `hermes pair` CLI sub-command via plugin/cli.py.

The payload format matches what QrPairingScanner.kt in the Android app expects.
v2 (current) adds optional TTL / per-channel grants / transport hint /
HMAC signature. v1 is still emitted when no v2-only field is present so
old phones can still parse the QR::

    v2 (extended):
    {
      "hermes": 2,
      "host": "<ip>", "port": <port>, "key": "<token>", "tls": <bool>,
      "relay": {
        "url": "ws://<ip>:<port>",
        "code": "<6-char>",
        "ttl_seconds": 2592000,           // 0 = never expire
        "grants": {"terminal": ..., "bridge": ...},
        "transport_hint": "ws"            // "wss" or "ws"
      },
      "sig": "<base64-hmac-sha256>"
    }

    v1 (legacy fallback):
    {
      "hermes": 1,
      "host": "<ip>", "port": <port>, "key": "<token>", "tls": <bool>,
      "relay": { "url": "ws://<ip>:<port>", "code": "<6-char>" }
    }

Top-level fields configure the direct-chat Hermes API server (port 8642 by
default). The optional ``relay`` block configures the Hermes-Relay WSS
connection used by the terminal and bridge channels — present only when a
local relay is running and we were able to pre-register a pairing code with
it via ``POST /pairing/register``.
"""

from __future__ import annotations

import io
import json
import os
import random
import socket
import string
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

from plugin.relay.qr_sign import load_or_create_secret, sign_payload


def _get_hermes_home() -> Path:
    return Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))


def _parse_env_file(path: Path) -> dict[str, str]:
    """Parse a simple KEY=value env file. Ignores comments and blank lines."""
    result: dict[str, str] = {}
    if not path.exists():
        return result
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            # Strip matching surrounding quotes
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            result[key.strip()] = value
    except OSError:
        pass
    return result


def read_server_config() -> dict:
    """Read API server config with fallback chain.

    Priority: hermes config.yaml → ~/.hermes/.env → environment vars → defaults.
    Returns dict with keys: host, port, key, tls.
    """
    host: Optional[str] = None
    port: Optional[int] = None
    key: Optional[str] = None
    tls = False

    # 1. Try hermes_cli.config.load_config()
    try:
        from hermes_cli.config import load_config  # type: ignore

        config = load_config()
        api = config.get("platforms", {}).get("api_server", {}) or {}
        extra = api.get("extra", {}) or {}
        key = extra.get("key") or api.get("api_key")
        port_val = extra.get("port") or api.get("port")
        if port_val is not None:
            port = int(port_val)
        host = extra.get("host") or api.get("host")
    except Exception:
        pass

    # 2. Fall back to ~/.hermes/.env
    if key is None or host is None or port is None:
        env_vals = _parse_env_file(_get_hermes_home() / ".env")
        if key is None:
            key = env_vals.get("API_SERVER_KEY")
        if host is None:
            host = env_vals.get("API_SERVER_HOST")
        if port is None and env_vals.get("API_SERVER_PORT"):
            try:
                port = int(env_vals["API_SERVER_PORT"])
            except ValueError:
                pass

    # 3. Fall back to process environment
    if key is None:
        key = os.getenv("API_SERVER_KEY", "")
    if host is None:
        host = os.getenv("API_SERVER_HOST", "127.0.0.1")
    if port is None:
        try:
            port = int(os.getenv("API_SERVER_PORT", "8642"))
        except ValueError:
            port = 8642

    return {"host": host, "port": port, "key": key or "", "tls": tls}


def _resolve_lan_ip(host: str) -> str:
    """Auto-detect LAN IP when host is loopback or bind-all.

    Cross-platform: uses a UDP socket connect() trick to find the outbound
    interface IP without sending any packets.
    """
    if host not in ("0.0.0.0", "127.0.0.1", "localhost", "::", "::1"):
        return host
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2)
        # Doesn't actually send; just picks the outbound interface
        s.connect(("1.1.1.1", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return host


def _relay_has_v2_fields(relay: Optional[dict]) -> bool:
    """True if the relay block uses any v2-only field."""
    if relay is None:
        return False
    return any(
        k in relay for k in ("ttl_seconds", "grants", "transport_hint")
    )


def build_payload(
    host: str,
    port: int,
    key: str,
    tls: bool,
    relay: Optional[dict] = None,
    sign: bool = True,
) -> str:
    """Build compact JSON payload matching HermesPairingPayload.kt format.

    If ``relay`` is provided it's embedded as a nested ``"relay"`` object.
    When any v2-only field (``ttl_seconds``, ``grants``, ``transport_hint``)
    is present the top-level ``hermes`` field is bumped to ``2``; otherwise
    it stays at ``1`` so old phones can still parse.

    If ``sign`` is true the payload is signed with the host-local QR
    secret and the base64 HMAC is added as a top-level ``sig`` field.
    """
    version = 2 if _relay_has_v2_fields(relay) else 1
    payload: dict = {
        "hermes": version,
        "host": host,
        "port": port,
        "key": key,
        "tls": tls,
    }
    if relay is not None:
        payload["relay"] = relay

    if sign:
        try:
            secret = load_or_create_secret()
            payload["sig"] = sign_payload(payload, secret)
        except Exception as exc:
            # Signing is best-effort: if we can't read/write the secret
            # file we still want to emit a usable QR rather than crashing
            # the `hermes pair` flow. Log to stderr so the operator sees
            # it but doesn't lose the QR.
            print(
                f"  [warn] QR signing failed ({exc}) — payload will be unsigned.",
                file=sys.stderr,
            )

    return json.dumps(payload, separators=(",", ":"))


# ── TTL / grants parsing ─────────────────────────────────────────────────────


_TTL_PRESETS: dict[str, int] = {
    # 0 => never expire
    "never": 0,
    "1d": 1 * 24 * 3600,
    "7d": 7 * 24 * 3600,
    "30d": 30 * 24 * 3600,
    "90d": 90 * 24 * 3600,
    # 1y ≈ 365 days. Not a leap-year-aware calendar year, just a round
    # duration; "never" covers the "really long" case.
    "1y": 365 * 24 * 3600,
}


def parse_duration(spec: str) -> int:
    """Parse a duration spec like ``"30d"`` / ``"1y"`` / ``"never"``.

    Returns the duration in seconds. ``"never"`` returns 0, which the
    relay interprets as ``math.inf`` (session never expires).

    Also accepts an explicit number of seconds (e.g. ``"3600"``) for
    power users.
    """
    normalized = spec.strip().lower()
    if not normalized:
        raise ValueError("empty duration")
    if normalized in _TTL_PRESETS:
        return _TTL_PRESETS[normalized]
    # Explicit numeric seconds.
    if normalized.isdigit():
        return int(normalized)
    # Loose suffix form: <int>[smhdwy]
    unit = normalized[-1]
    head = normalized[:-1]
    if not head.isdigit():
        raise ValueError(f"cannot parse duration {spec!r}")
    n = int(head)
    if unit == "s":
        return n
    if unit == "m":
        return n * 60
    if unit == "h":
        return n * 3600
    if unit == "d":
        return n * 24 * 3600
    if unit == "w":
        return n * 7 * 24 * 3600
    if unit == "y":
        return n * 365 * 24 * 3600
    raise ValueError(f"unknown duration unit {unit!r} in {spec!r}")


def parse_grants(spec: str) -> dict[str, int]:
    """Parse a ``--grants`` spec like ``"terminal=7d,bridge=1d"``.

    Returns a dict ``{channel: duration_seconds}``. Unknown channels are
    accepted — the server applies its own whitelist.
    """
    out: dict[str, int] = {}
    if not spec.strip():
        return out
    for pair_str in spec.split(","):
        pair_str = pair_str.strip()
        if not pair_str:
            continue
        if "=" not in pair_str:
            raise ValueError(
                f"invalid grant {pair_str!r} — expected channel=duration"
            )
        channel, _, duration = pair_str.partition("=")
        channel = channel.strip()
        if not channel:
            raise ValueError(f"empty channel in grant {pair_str!r}")
        out[channel] = parse_duration(duration)
    return out


def format_duration_label(ttl_seconds: int) -> str:
    """Return a human-readable label for a TTL like ``'30 days'`` or ``'indefinitely'``."""
    if ttl_seconds == 0:
        return "indefinitely"
    day = 24 * 3600
    if ttl_seconds % (365 * day) == 0:
        n = ttl_seconds // (365 * day)
        return f"{n} year{'s' if n != 1 else ''}"
    if ttl_seconds % day == 0:
        n = ttl_seconds // day
        return f"{n} day{'s' if n != 1 else ''}"
    if ttl_seconds % 3600 == 0:
        n = ttl_seconds // 3600
        return f"{n} hour{'s' if n != 1 else ''}"
    return f"{ttl_seconds} seconds"


# ── Relay pre-pairing ────────────────────────────────────────────────────────


# Mirrors the relay's PAIRING_ALPHABET and the app's AuthManager generator.
_RELAY_CODE_ALPHABET = string.ascii_uppercase + string.digits
_RELAY_CODE_LENGTH = 6


def _generate_relay_code() -> str:
    """Generate a fresh 6-char pairing code (A-Z / 0-9)."""
    rng = random.SystemRandom()
    return "".join(rng.choice(_RELAY_CODE_ALPHABET) for _ in range(_RELAY_CODE_LENGTH))


def _relay_lan_base_url(relay_host: str, relay_port: int, tls: bool = False) -> str:
    """Build the ws[s]://host:port URL the phone should connect to.

    Always resolves loopback/bind-all to a routable LAN IP so the QR payload
    contains a URL the phone can actually reach across the network.
    """
    lan_host = _resolve_lan_ip(relay_host)
    scheme = "wss" if tls else "ws"
    return f"{scheme}://{lan_host}:{relay_port}"


def register_relay_code(
    localhost_port: int,
    code: str,
    timeout_s: float = 2.0,
    ttl_seconds: int | None = None,
    grants: dict[str, int] | None = None,
    transport_hint: str | None = None,
) -> bool:
    """Pre-register ``code`` with the running relay via loopback HTTP.

    The relay's ``/pairing/register`` endpoint is gated to loopback callers,
    which matches the trust model: only a process running on the same host
    as the relay (operator shell) can inject pairing codes. A phone on the
    LAN cannot register codes.

    Optional ``ttl_seconds`` / ``grants`` / ``transport_hint`` are passed
    through verbatim so the operator's choices at QR generation time are
    applied to the freshly-minted session when the phone claims the code.

    Returns ``True`` on success, ``False`` on any failure (relay not running,
    timeout, HTTP error). Callers should treat failure as "relay pairing
    unavailable" and render an API-only QR.
    """
    url = f"http://127.0.0.1:{localhost_port}/pairing/register"
    body_dict: dict = {"code": code}
    if ttl_seconds is not None:
        body_dict["ttl_seconds"] = ttl_seconds
    if grants:
        body_dict["grants"] = grants
    if transport_hint:
        body_dict["transport_hint"] = transport_hint
    body = json.dumps(body_dict).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            if resp.status != 200:
                return False
            data = json.loads(resp.read().decode("utf-8"))
            return bool(data.get("ok"))
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError):
        return False


def probe_relay(localhost_port: int, timeout_s: float = 1.0) -> Optional[dict]:
    """Check if a relay is listening on ``localhost:<port>``.

    Returns the parsed /health JSON on success, or None if the relay isn't
    reachable. Used to decide whether to embed a relay block in the QR.
    """
    url = f"http://127.0.0.1:{localhost_port}/health"
    try:
        with urllib.request.urlopen(url, timeout=timeout_s) as resp:
            if resp.status != 200:
                return None
            return json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError):
        return None


def read_relay_config() -> dict:
    """Resolve relay host/port from env vars + defaults.

    Mirrors ``plugin/relay/config.py`` — uses ``RELAY_HOST`` / ``RELAY_PORT``
    if set, otherwise falls back to ``0.0.0.0:8767`` which the LAN resolver
    will turn into a routable address. The ``tls`` flag is True whenever
    ``RELAY_SSL_CERT`` is set in the environment; ``transport_hint`` gets
    set accordingly for the QR payload.
    """
    host = os.getenv("RELAY_HOST") or "0.0.0.0"
    try:
        port = int(os.getenv("RELAY_PORT") or "8767")
    except ValueError:
        port = 8767
    tls = bool(os.getenv("RELAY_SSL_CERT"))
    return {"host": host, "port": port, "tls": tls}


def _mask_key(key: str) -> str:
    """Return a redacted preview of the API key."""
    if not key:
        return "(none - open access)"
    if len(key) <= 8:
        return "*" * len(key) + f" ({len(key)} chars)"
    return f"{key[:4]}...{key[-3:]} ({len(key)} chars)"


def render_text_block(
    host: str,
    port: int,
    key: str,
    tls: bool,
    relay: Optional[dict] = None,
) -> str:
    """Return formatted connection details — always shown (works in any terminal).

    When a ``relay`` block is provided, adds a second section showing the
    WebSocket URL and the pre-registered pairing code so the operator can
    enter them manually if QR scanning fails.
    """
    scheme = "https" if tls else "http"
    url = f"{scheme}://{host}:{port}"
    auth_status = "Bearer token configured" if key else "NO AUTH (open access)"

    lines = [
        "",
        "  Hermes Android Pairing",
        "  " + "-" * 40,
        "",
        f"  Server : {url}",
        f"  API Key: {_mask_key(key)}",
        f"  Auth   : {auth_status}",
        "",
        "  Enter manually in the app if QR won't scan:",
        f"    URL: {url}",
    ]
    if key:
        lines.append(f"    Key: {key}")

    if relay is not None:
        lines.extend([
            "",
            "  Relay (terminal + bridge)",
            "  " + "-" * 40,
            f"  URL  : {relay['url']}",
            f"  Code : {relay['code']}  (expires in 10 min, one-shot)",
        ])
        ttl = relay.get("ttl_seconds")
        if ttl is not None:
            label = format_duration_label(int(ttl))
            if ttl == 0:
                lines.append(f"  Pair : {label} (never expires)")
            else:
                lines.append(f"  Pair : for {label}")
        grants = relay.get("grants")
        if grants:
            grant_parts = []
            for channel, duration in grants.items():
                grant_parts.append(
                    f"{channel}={format_duration_label(int(duration))}"
                )
            lines.append(f"  Grants: {', '.join(grant_parts)}")

    lines.append("")
    return "\n".join(lines)


def render_qr_terminal(payload: str) -> str:
    """Render QR as Unicode half-block string for terminal output.

    Returns a helpful message if segno is not installed (graceful fallback).
    """
    try:
        import segno  # type: ignore
    except ImportError:
        return (
            "  (QR rendering unavailable — install segno: pip install segno)\n"
            "  Use the text details above to enter connection info manually.\n"
        )

    try:
        # error="l" (low ~7% redundancy) keeps the QR version as small as
        # possible given the signed payload length. border=1 is the minimum
        # scannable quiet zone. compact=True packs two modules per character
        # vertically via ▀ / ▄ half-blocks, halving the visual height vs the
        # full-block renderer. Together these produce the smallest terminal
        # QR segno can emit without dropping features.
        qr = segno.make(payload, error="l")
        buf = io.StringIO()
        qr.terminal(out=buf, compact=True, border=1)
        return buf.getvalue()
    except Exception as e:
        return f"  (QR render failed: {e})\n"


def render_qr_png(payload: str, path: Optional[str] = None) -> Optional[str]:
    """Save QR as PNG file. Returns the file path, or None on failure."""
    try:
        import segno  # type: ignore
    except ImportError:
        return None

    if path is None:
        path = str(Path(tempfile.gettempdir()) / "hermes-pairing-qr.png")

    try:
        qr = segno.make(payload, error="l")
        qr.save(path, scale=8, border=2)
        return path
    except Exception:
        return None


def pair_command(args) -> None:
    """CLI entry point for `hermes pair`. Called by argparse dispatch."""
    config = read_server_config()

    # Apply CLI overrides
    if getattr(args, "host", None):
        config["host"] = args.host
    if getattr(args, "port", None):
        config["port"] = int(args.port)

    host = _resolve_lan_ip(config["host"])
    port = config["port"]
    key = config["key"]
    tls = config["tls"]

    # ── Relay pre-pairing ────────────────────────────────────────────────
    #
    # If a relay is running locally, mint a fresh pairing code, register it
    # with the relay via the loopback-only /pairing/register endpoint, and
    # embed both the relay URL and the code in the QR payload. The phone
    # gets everything it needs to connect to chat + terminal in a single
    # scan. If the relay isn't running (or we can't register), we render an
    # API-only QR and print a warning pointing at `hermes relay start`.

    # ── TTL + grants from CLI flags ──────────────────────────────────────
    ttl_spec = getattr(args, "ttl", None) or "30d"
    try:
        ttl_seconds = parse_duration(ttl_spec)
    except ValueError as exc:
        print(f"  [error] --ttl: {exc}", file=sys.stderr)
        sys.exit(2)

    grants_spec = getattr(args, "grants", None)
    grants_dict: Optional[dict[str, int]] = None
    if grants_spec:
        try:
            grants_dict = parse_grants(grants_spec)
        except ValueError as exc:
            print(f"  [error] --grants: {exc}", file=sys.stderr)
            sys.exit(2)

    relay_block: Optional[dict] = None
    skip_relay = getattr(args, "no_relay", False)
    if not skip_relay:
        relay_cfg = read_relay_config()
        relay_port = relay_cfg["port"]
        relay_tls = bool(relay_cfg.get("tls"))
        transport_hint = "wss" if relay_tls else "ws"

        health = probe_relay(relay_port)
        if health is None:
            print(
                "  [info] Relay not running at localhost:"
                f"{relay_port} — QR will configure chat only."
            )
            print(
                "         Start the relay with: hermes relay start   "
                "(or: python -m plugin.relay --no-ssl)\n"
            )
        else:
            relay_code = _generate_relay_code()
            if register_relay_code(
                relay_port,
                relay_code,
                ttl_seconds=ttl_seconds,
                grants=grants_dict,
                transport_hint=transport_hint,
            ):
                relay_block = {
                    "url": _relay_lan_base_url(
                        relay_cfg["host"], relay_port, tls=relay_tls
                    ),
                    "code": relay_code,
                    "ttl_seconds": ttl_seconds,
                    "transport_hint": transport_hint,
                }
                if grants_dict:
                    relay_block["grants"] = grants_dict
            else:
                print(
                    "  [warn] Relay is running but /pairing/register was "
                    "rejected — QR will configure chat only.\n"
                )

    payload = build_payload(host, port, key, tls, relay=relay_block)

    # Always show text block — works in any terminal including Hermes TUI
    print(render_text_block(host, port, key, tls, relay=relay_block))

    png_only = getattr(args, "png", False)
    no_qr = getattr(args, "no_qr", False)

    if png_only:
        path = render_qr_png(payload)
        if path:
            print(f"  PNG saved: {path}")
        else:
            print("  PNG render failed — install segno: pip install segno")
    elif not no_qr and sys.stdout.isatty():
        print(render_qr_terminal(payload))
        png_path = render_qr_png(payload)
        if png_path:
            print(f"  PNG: {png_path}")
        print("  Scan with the Hermes-Relay Android app.")

    if key or relay_block is not None:
        print(
            "  WARNING: This QR contains credentials "
            "(API key and/or relay pairing code). Do not share screenshots.\n"
        )
    else:
        print()


if __name__ == "__main__":
    # Allow running as `python -m plugin.pair` for manual testing
    import argparse

    parser = argparse.ArgumentParser(description="Hermes Android pairing")
    parser.add_argument("--png", action="store_true", help="Save PNG only")
    parser.add_argument("--no-qr", action="store_true", dest="no_qr", help="Text only")
    parser.add_argument(
        "--no-relay",
        action="store_true",
        dest="no_relay",
        help="Skip relay pre-pairing (render API-only QR)",
    )
    parser.add_argument("--host", help="Override API server host")
    parser.add_argument("--port", type=int, help="Override API server port")
    parser.add_argument(
        "--ttl",
        default="30d",
        help=(
            "Session TTL — one of 1d/7d/30d/90d/1y/never, or an explicit "
            "<N><unit> like 12h/4w. Default: 30d."
        ),
    )
    parser.add_argument(
        "--grants",
        default=None,
        help=(
            "Per-channel grants, comma-separated channel=duration pairs, "
            "e.g. 'terminal=7d,bridge=1d'. Unspecified channels get server "
            "defaults."
        ),
    )
    pair_command(parser.parse_args())
