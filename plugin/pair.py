"""Hermes Android pairing — generate QR codes and connection info.

Replaces the standalone bash script `skills/hermes-pairing-qr/hermes-pair`.
Exposed as the `hermes pair` CLI sub-command via plugin/cli.py.

The payload format matches what QrPairingScanner.kt in the Android app expects.
v2 added optional TTL / per-channel grants / transport hint / HMAC signature.
v3 (ADR 24) adds an optional ordered ``endpoints`` array for multi-network
operators (LAN + Tailscale + public reverse-proxy in a single QR). v1/v2
remain valid — phones synthesize a priority-0 candidate from the top-level
fields when ``endpoints`` is absent, and the pair CLI only bumps the
version bit when it's actually emitting endpoints::

    v3 (multi-endpoint — ADR 24):
    {
      "hermes": 3,
      "host": "<ip>", "port": <port>, "key": "<token>", "tls": <bool>,
      "relay": { ... same as v2 ... },
      "endpoints": [
        { "role": "lan", "priority": 0,
          "api":   {"host": "<ip>", "port": <port>, "tls": <bool>},
          "relay": {"url": "ws://<ip>:<port>", "transport_hint": "ws"} },
        { "role": "tailscale", "priority": 1, "api": {...}, "relay": {...} },
        { "role": "public",    "priority": 2, "api": {...}, "relay": {...} }
      ],
      "sig": "<base64-hmac-sha256>"
    }

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
default) and mirror the priority-0 endpoint candidate for backward compat.
The optional ``relay`` block configures the Hermes-Relay WSS connection
used by the terminal and bridge channels — present only when a local
relay is running and we were able to pre-register a pairing code with it
via ``POST /pairing/register``.
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
from typing import Any, Optional
from urllib.parse import urlparse

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
    endpoints: Optional[list[dict]] = None,
) -> str:
    """Build compact JSON payload matching HermesPairingPayload.kt format.

    If ``relay`` is provided it's embedded as a nested ``"relay"`` object.
    When any v2-only field (``ttl_seconds``, ``grants``, ``transport_hint``)
    is present the top-level ``hermes`` field is bumped to ``2``; otherwise
    it stays at ``1`` so old phones can still parse.

    If ``endpoints`` is provided and non-empty it's embedded as a top-level
    ``"endpoints"`` array and the version bumps to ``3`` (ADR 24 —
    multi-endpoint pairing payload). List order is preserved verbatim
    through canonicalization + signing so strict priority semantics
    survive the HMAC. Callers are responsible for sorting by
    ``priority`` before passing the list in; ``build_endpoint_candidates``
    does this for the CLI path.

    The top-level ``host`` / ``port`` / ``key`` / ``tls`` / ``relay``
    fields are always emitted and should mirror the priority-0 candidate
    for backward compat — phones on v1 / v2 parsers synthesize a single
    candidate from those fields and ignore ``endpoints``.

    If ``sign`` is true the payload is signed with the host-local QR
    secret and the base64 HMAC is added as a top-level ``sig`` field.
    """
    if endpoints:
        version = 3
    elif _relay_has_v2_fields(relay):
        version = 2
    else:
        version = 1
    payload: dict = {
        "hermes": version,
        "host": host,
        "port": port,
        "key": key,
        "tls": tls,
    }
    if relay is not None:
        payload["relay"] = relay
    if endpoints:
        payload["endpoints"] = endpoints

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


# ── Endpoint candidate discovery (ADR 24) ────────────────────────────────────


_VALID_MODES = ("auto", "lan", "tailscale", "public")


def _lan_endpoint(
    api_host: str,
    api_port: int,
    api_tls: bool,
    relay_host: str,
    relay_port: int,
    relay_tls: bool,
    priority: int = 0,
) -> dict[str, Any]:
    """Build a ``role: lan`` endpoint candidate using the LAN-resolved host.

    Reuses the same :func:`_resolve_lan_ip` helper the single-endpoint
    flow uses, so a bind-all / loopback host surfaces as a routable LAN
    IP in the candidate.
    """
    lan_host = _resolve_lan_ip(api_host)
    relay_lan_host = _resolve_lan_ip(relay_host)
    relay_scheme = "wss" if relay_tls else "ws"
    return {
        "role": "lan",
        "priority": priority,
        "api": {"host": lan_host, "port": api_port, "tls": api_tls},
        "relay": {
            "url": f"{relay_scheme}://{relay_lan_host}:{relay_port}",
            "transport_hint": relay_scheme,
        },
    }


def _tailscale_status() -> Optional[dict[str, Any]]:
    """Probe the optional Tailscale helper. Returns None on any failure.

    The helper at ``plugin.relay.tailscale`` is owned by a sibling track
    (ADR 25). We import it lazily and treat both ``ImportError`` and any
    runtime exception as "no Tailscale endpoint available" so this code
    works today on a vanilla install where the helper hasn't landed
    yet. When the helper is present and returns a usable ``.ts.net``
    hostname we surface it as a ``role: tailscale`` candidate.
    """
    try:
        from plugin.relay import tailscale  # type: ignore
    except ImportError:
        return None
    try:
        status = tailscale.status()
    except Exception:
        return None
    if not isinstance(status, dict):
        return None
    return status


def _tailscale_endpoint(
    status: dict[str, Any],
    api_port: int,
    relay_port: int,
    priority: int,
) -> Optional[dict[str, Any]]:
    """Materialize a ``role: tailscale`` candidate from a helper status dict.

    The helper contract (ADR 25) hands back something shaped roughly
    like ``{"hostname": "hermes.tail-scale.ts.net", "https": True}``.
    We're defensive about shape — if the hostname is missing or doesn't
    look like a Tailscale magic-DNS record we skip the candidate rather
    than emit a broken one. Tailscale's ``serve --https`` terminates TLS
    so the api side is https by default; the relay is reached over wss
    on the same hostname.
    """
    hostname = status.get("hostname") or status.get("dns_name") or status.get("host")
    if not isinstance(hostname, str) or not hostname.strip():
        return None
    hostname = hostname.strip().rstrip(".")
    if not hostname.endswith(".ts.net"):
        # Could be ipv4/ipv6 CGNAT-only; that's still a valid Tailscale
        # endpoint but we only auto-detect .ts.net magic-DNS to avoid
        # accidentally emitting a candidate pointing at a non-routable
        # raw address. Operators wanting the ip path can pass a custom
        # endpoint via the dashboard's /pairing/mint body.
        return None
    # Tailscale serve defaults to https; the helper can override via
    # `tls: False` if the operator set up plain http (uncommon).
    tls = bool(status.get("tls", True))
    scheme = "wss" if tls else "ws"
    return {
        "role": "tailscale",
        "priority": priority,
        "api": {"host": hostname, "port": api_port, "tls": tls},
        "relay": {
            "url": f"{scheme}://{hostname}:{relay_port}",
            "transport_hint": scheme,
        },
    }


def _public_endpoint(
    public_url: str,
    relay_port: int,
    priority: int,
) -> dict[str, Any]:
    """Parse ``--public-url`` into a ``role: public`` candidate.

    Infers api host/port/tls from the URL. When the URL scheme is
    ``https`` and no port is present we default to 443 (the common
    reverse-proxy / Cloudflare Tunnel case). The relay URL mirrors the
    same host/scheme at the configured ``relay_port``; operators behind
    a path-rewriting proxy can override later via the dashboard
    ``/pairing/mint`` body.

    Raises :class:`ValueError` when ``public_url`` is empty or its
    scheme isn't ``http`` / ``https``.
    """
    if not public_url:
        raise ValueError("public_url is required for role=public")
    parsed = urlparse(public_url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError(
            f"--public-url must be http:// or https:// (got {public_url!r})"
        )
    host = parsed.hostname
    if not host:
        raise ValueError(f"--public-url has no host: {public_url!r}")
    tls = parsed.scheme == "https"
    api_port = parsed.port if parsed.port is not None else (443 if tls else 80)
    relay_scheme = "wss" if tls else "ws"
    # If the operator passed a relay-explicit URL (path present beyond
    # "/"), preserve it verbatim — path-rewriting proxies depend on it.
    relay_url: str
    path = parsed.path or ""
    if path and path != "/":
        relay_url = f"{relay_scheme}://{host}{path}"
    else:
        # No path → assume the relay is on the same host at its usual
        # port. TLS-terminating reverse proxies that forward WSS on
        # :443 are typical; the operator can override via --public-url
        # with an explicit path if the proxy rewrites.
        relay_url = f"{relay_scheme}://{host}:{relay_port}"
    return {
        "role": "public",
        "priority": priority,
        "api": {"host": host, "port": api_port, "tls": tls},
        "relay": {
            "url": relay_url,
            "transport_hint": relay_scheme,
        },
    }


def build_endpoint_candidates(
    mode: str,
    api_host: str,
    api_port: int,
    api_tls: bool,
    relay_host: str,
    relay_port: int,
    relay_tls: bool,
    public_url: Optional[str] = None,
    prefer: Optional[str] = None,
) -> list[dict[str, Any]]:
    """Build the ordered ``endpoints`` array for a v3 QR payload.

    ``mode`` is one of ``auto`` / ``lan`` / ``tailscale`` / ``public``
    (see ADR 24). Priority is strictly increasing by role, starting at
    0 for LAN and going up — matching DNS SRV semantics (lower number
    = higher priority). An empty list is returned when no candidates
    could be detected (e.g. ``--mode tailscale`` on a host without
    Tailscale installed); callers should treat that as "stay on the
    single-endpoint v2 payload".

    ``prefer`` (optional) names a role that should be promoted to
    priority 0, with all other roles shifted down by one. Useful for
    testing a specific path end-to-end ("force Tailscale even though
    I'm on LAN") without re-ordering defaults globally. When the named
    role is not present in the detected candidates a warning is printed
    to stderr and the list is emitted in its natural order — callers
    asking for something that isn't there should see it, not silently
    get the default.

    Raises :class:`ValueError` if ``mode`` is unknown or ``mode=public``
    is requested without a ``public_url``.
    """
    if mode not in _VALID_MODES:
        raise ValueError(
            f"invalid --mode {mode!r}; expected one of {_VALID_MODES}"
        )
    candidates: list[dict[str, Any]] = []
    next_priority = 0

    def _emit(candidate: Optional[dict[str, Any]]) -> None:
        nonlocal next_priority
        if candidate is None:
            return
        # Respect the priority we assigned above; _lan_endpoint /
        # _tailscale_endpoint / _public_endpoint honor it.
        candidates.append(candidate)
        next_priority += 1

    want_lan = mode in ("auto", "lan")
    want_tailscale = mode in ("auto", "tailscale")
    want_public = mode in ("auto", "public")

    if want_lan:
        _emit(
            _lan_endpoint(
                api_host,
                api_port,
                api_tls,
                relay_host,
                relay_port,
                relay_tls,
                priority=next_priority,
            )
        )

    if want_tailscale:
        status = _tailscale_status()
        if status is not None:
            _emit(
                _tailscale_endpoint(
                    status,
                    api_port=api_port,
                    relay_port=relay_port,
                    priority=next_priority,
                )
            )
        elif mode == "tailscale":
            # Explicit mode but no helper / no hostname — fail soft
            # rather than emit a half-formed QR. Caller falls back
            # to the single-endpoint path and logs.
            pass

    if want_public:
        if public_url:
            _emit(
                _public_endpoint(
                    public_url, relay_port=relay_port, priority=next_priority
                )
            )
        elif mode == "public":
            raise ValueError(
                "--mode public requires --public-url <url>"
            )

    # Priority override — promote the named role to priority 0 and
    # renumber the rest in their existing relative order. Role string
    # matches are case-insensitive + whitespace-trimmed for
    # operator-ergonomics, but the candidate's original ``role`` value
    # is preserved verbatim because the HMAC canonical form requires it.
    if prefer:
        wanted = prefer.strip().lower()
        idx = next(
            (i for i, c in enumerate(candidates) if str(c.get("role", "")).lower() == wanted),
            -1,
        )
        if idx < 0:
            print(
                f"  [warn] --prefer {prefer!r}: role not in candidates "
                f"{[c.get('role') for c in candidates]}; emitting natural order",
                file=sys.stderr,
            )
        elif idx > 0:
            promoted = candidates.pop(idx)
            candidates.insert(0, promoted)
            for new_priority, c in enumerate(candidates):
                c["priority"] = new_priority

    return candidates


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


class InvalidPairingCodeError(ValueError):
    """Raised when a user-supplied pairing code fails format validation."""


def _generate_relay_code() -> str:
    """Generate a fresh 6-char pairing code (A-Z / 0-9)."""
    rng = random.SystemRandom()
    return "".join(rng.choice(_RELAY_CODE_ALPHABET) for _ in range(_RELAY_CODE_LENGTH))


def normalize_pairing_code(code: str) -> str:
    """Validate a user-supplied pairing code and return its canonical form.

    The relay's :class:`PairingManager` upper-cases codes internally and
    accepts only characters from ``PAIRING_ALPHABET`` at exactly
    ``PAIRING_CODE_LENGTH`` chars (6). We mirror that here so the CLI can
    fail fast with a clear message instead of letting the operator find
    out via an HTTP 400.

    Raises :class:`InvalidPairingCodeError` on length or alphabet
    mismatches. Returns the upper-cased code on success.
    """
    if code is None:
        raise InvalidPairingCodeError("pairing code is required")
    normalized = code.strip().upper()
    if not normalized:
        raise InvalidPairingCodeError("pairing code is empty")
    if len(normalized) != _RELAY_CODE_LENGTH:
        raise InvalidPairingCodeError(
            f"pairing code must be exactly {_RELAY_CODE_LENGTH} characters "
            f"(got {len(normalized)}: {code!r})"
        )
    bad = [c for c in normalized if c not in _RELAY_CODE_ALPHABET]
    if bad:
        raise InvalidPairingCodeError(
            f"pairing code contains invalid characters {bad!r} — "
            f"only A-Z and 0-9 are allowed"
        )
    return normalized


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


def register_code_command(args) -> int:
    """CLI entry point for ``hermes-pair --register-code <code>``.

    Skips QR rendering entirely. Validates the user-supplied code against
    ``PAIRING_ALPHABET`` + length, probes the local relay, then calls the
    same loopback ``/pairing/register`` HTTP endpoint that the QR flow
    uses — including any ``--ttl`` / ``--grants`` / ``--transport-hint``
    options so a manual-paired session has the same gating as a QR-paired
    one.

    Use case: the operator can't render a QR or there's no second device
    to scan with (SSH-only / camera unavailable / phone displaying a
    locally-generated code in Settings → Connection → Manual pairing
    code). The phone displays a code, the operator types it into a host
    shell, this command pre-registers it with the relay, the phone taps
    Connect.

    Returns a process exit code (0 on success, non-zero on failure).
    """
    raw_code = getattr(args, "register_code", None)
    try:
        code = normalize_pairing_code(raw_code or "")
    except InvalidPairingCodeError as exc:
        print(f"  [error] --register-code: {exc}", file=sys.stderr)
        return 2

    # ── TTL + grants from CLI flags (same shape as the QR flow) ───────────
    ttl_spec = getattr(args, "ttl", None) or "30d"
    try:
        ttl_seconds = parse_duration(ttl_spec)
    except ValueError as exc:
        print(f"  [error] --ttl: {exc}", file=sys.stderr)
        return 2

    grants_spec = getattr(args, "grants", None)
    grants_dict: Optional[dict[str, int]] = None
    if grants_spec:
        try:
            grants_dict = parse_grants(grants_spec)
        except ValueError as exc:
            print(f"  [error] --grants: {exc}", file=sys.stderr)
            return 2

    # Resolve relay host/port + transport hint. The CLI flag overrides
    # the env-derived default so the operator can register against a
    # non-default relay or pretend the transport is wss (e.g. when
    # running behind an external reverse proxy that terminates TLS).
    relay_cfg = read_relay_config()
    relay_port = relay_cfg["port"]
    relay_tls = bool(relay_cfg.get("tls"))
    transport_hint = getattr(args, "transport_hint", None) or (
        "wss" if relay_tls else "ws"
    )

    # Probe the relay first so we can give a precise error message
    # instead of a generic "post failed".
    health = probe_relay(relay_port)
    if health is None:
        print(
            f"  [error] No relay reachable at http://127.0.0.1:{relay_port}.\n"
            f"          Start the relay first: hermes relay start "
            f"(or python -m plugin.relay --no-ssl)",
            file=sys.stderr,
        )
        return 1

    ok = register_relay_code(
        relay_port,
        code,
        ttl_seconds=ttl_seconds,
        grants=grants_dict,
        transport_hint=transport_hint,
    )
    if not ok:
        print(
            "  [error] Relay rejected the pairing code. The relay's "
            "/pairing/register endpoint is loopback-only — make sure "
            "you're running this command on the same host as the relay.",
            file=sys.stderr,
        )
        return 1

    # ── Success — tell the operator exactly what to do next ──────────────
    ttl_label = format_duration_label(ttl_seconds)
    print()
    print("  Hermes-Relay manual pairing")
    print("  " + "-" * 40)
    print(f"  Code         : {code}")
    print(f"  Relay        : http://127.0.0.1:{relay_port}")
    print(f"  Transport    : {transport_hint}")
    if ttl_seconds == 0:
        print(f"  Session TTL  : {ttl_label} (never expires)")
    else:
        print(f"  Session TTL  : {ttl_label}")
    if grants_dict:
        grant_parts = [
            f"{ch}={format_duration_label(int(d))}" for ch, d in grants_dict.items()
        ]
        print(f"  Grants       : {', '.join(grant_parts)}")
    print()
    print("  Code registered. The pairing code is single-use and expires")
    print("  in 10 minutes.")
    print()
    print("  In the Hermes-Relay app:")
    print("    1. Open Settings -> Connection -> Manual pairing code (fallback).")
    print(f"    2. Confirm the displayed code matches: {code}")
    print("    3. Tap Connect.")
    print()
    return 0


def pair_command(args) -> None:
    """CLI entry point for `hermes pair`. Called by argparse dispatch."""
    # Manual-fallback flow: skip QR entirely and just pre-register the code.
    if getattr(args, "register_code", None):
        sys.exit(register_code_command(args))

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

    # ── Multi-endpoint detection (ADR 24) ────────────────────────────────
    # Build an ``endpoints`` array whenever the operator asked for multi-
    # network support (non-default mode OR --public-url). ``mode=auto``
    # silently probes LAN + Tailscale + (if passed) --public-url; explicit
    # modes emit just that role (or empty list on detection failure —
    # caller falls back to the single-endpoint payload).
    mode = (getattr(args, "mode", None) or "auto").strip().lower()
    public_url = getattr(args, "public_url", None)
    prefer = getattr(args, "prefer", None)
    endpoints: list[dict] = []
    # Only emit endpoints when the relay is present — the phone needs a
    # relay URL per candidate to drive reconnect on network switch. If
    # the operator ran with --no-relay we stay on single-endpoint.
    if relay_block is not None:
        _relay_cfg = read_relay_config()
        try:
            endpoints = build_endpoint_candidates(
                mode=mode,
                api_host=host,
                api_port=port,
                api_tls=tls,
                relay_host=_relay_cfg["host"],
                relay_port=_relay_cfg["port"],
                relay_tls=bool(_relay_cfg.get("tls")),
                public_url=public_url,
                prefer=prefer,
            )
        except ValueError as exc:
            print(f"  [error] --mode/--public-url: {exc}", file=sys.stderr)
            sys.exit(2)

    payload = build_payload(
        host, port, key, tls, relay=relay_block, endpoints=endpoints or None
    )

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
    parser.add_argument(
        "--register-code",
        dest="register_code",
        default=None,
        help=(
            "Manual-fallback flow: pre-register a 6-char pairing code "
            "(A-Z / 0-9) supplied by the phone and exit. Skips QR "
            "rendering entirely. Composes with --ttl / --grants / "
            "--transport-hint. Use this when you can't scan a QR "
            "(camera unavailable, SSH-only access, second-device pair "
            "impossible) — the phone displays a code in Settings -> "
            "Connection -> Manual pairing code (fallback), you type it "
            "into this command, and then tap Connect in the app."
        ),
    )
    parser.add_argument(
        "--transport-hint",
        dest="transport_hint",
        default=None,
        choices=["ws", "wss"],
        help=(
            "Override the transport hint stored alongside the session "
            "(only meaningful with --register-code). Defaults to 'wss' "
            "when RELAY_SSL_CERT is set, otherwise 'ws'."
        ),
    )
    parser.add_argument(
        "--mode",
        dest="mode",
        default="auto",
        choices=list(_VALID_MODES),
        help=(
            "Endpoint discovery mode (ADR 24). 'auto' (default) probes "
            "LAN + Tailscale + --public-url (when passed) and emits an "
            "ordered ``endpoints`` array in the QR. 'lan' / 'tailscale' "
            "/ 'public' emit just that role. 'public' requires "
            "--public-url."
        ),
    )
    parser.add_argument(
        "--public-url",
        dest="public_url",
        default=None,
        help=(
            "Public hostname for a reverse proxy / Cloudflare Tunnel "
            "(e.g. https://hermes.example.com). Added as a role=public "
            "endpoint candidate in the QR. Must be http:// or https://."
        ),
    )
    parser.add_argument(
        "--prefer",
        dest="prefer",
        default=None,
        help=(
            "Promote a named role to priority 0 (highest). Any open-vocab "
            "role string accepted — commonly 'lan' / 'tailscale' / 'public'. "
            "Role matching is case-insensitive. Example: "
            "'--mode auto --prefer tailscale' emits all detected modes but "
            "with Tailscale as the first-probed endpoint. Warns to stderr "
            "if the named role isn't in the detected candidates."
        ),
    )
    pair_command(parser.parse_args())
