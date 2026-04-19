"""FastAPI proxy router for the Hermes-Relay dashboard plugin.

Loopback-only; mounted by hermes-agent at ``/api/plugins/hermes-relay/*``.

Each route is a thin pass-through to the already-running relay HTTP server
on ``127.0.0.1:{HERMES_RELAY_PORT}``. No business logic lives here — the
relay stays the source of truth.

Route map
---------
- ``GET /overview``         → relay ``GET /relay/info``
- ``GET /sessions``         → relay ``GET /sessions`` (loopback-exempt since R3)
- ``GET /bridge-activity``  → relay ``GET /bridge/activity`` (forwards ``limit``)
- ``GET /media``            → relay ``GET /media/inspect`` (forwards ``include_expired``)
- ``GET /push``             → static stub (no network call) until FCM is wired

Error translation
-----------------
- Relay connect-error / timeout / 5xx → ``502 Bad Gateway`` with a human-readable
  ``detail`` pointing at ``127.0.0.1:{RELAY_PORT}``.
- Relay 4xx → status + body passed through verbatim.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path as FsPath
from typing import Any, Optional
from urllib.parse import urlparse

import httpx
from fastapi import APIRouter, Body, HTTPException, Path, Query

# Read once at import time — hermes-agent restarts pick up env changes.
RELAY_PORT: int = int(os.environ.get("HERMES_RELAY_PORT", "8767"))
_RELAY_BASE: str = f"http://127.0.0.1:{RELAY_PORT}"
_TIMEOUT: float = 5.0

# Per-host state file for the "public URL" the operator pinned into the
# Remote Access tab. Lives alongside the other ``~/.hermes/`` state so a
# hermes-agent restart preserves it. Deliberately small + human-readable
# so operators can clear it with a plain editor if needed.
_REMOTE_STATE_FILENAME = "relay-remote.json"


def _hermes_home() -> FsPath:
    return FsPath(os.environ.get("HERMES_HOME", FsPath.home() / ".hermes"))


def _remote_state_path() -> FsPath:
    return _hermes_home() / _REMOTE_STATE_FILENAME


def _read_remote_state() -> dict[str, Any]:
    """Read the pinned-endpoint state file. Missing/malformed → empty dict."""
    path = _remote_state_path()
    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return {}
    except OSError:
        return {}
    try:
        data = json.loads(raw)
    except (ValueError, json.JSONDecodeError):
        return {}
    if not isinstance(data, dict):
        return {}
    return data


def _write_remote_state(state: dict[str, Any]) -> None:
    """Atomically persist the remote state. Best-effort — raises on OSError."""
    path = _remote_state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(state, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)


def _validate_public_url(url: str) -> str:
    """Validate & normalize a public URL. Empty string → '' (clears)."""
    trimmed = url.strip()
    if not trimmed:
        return ""
    parsed = urlparse(trimmed)
    if parsed.scheme not in ("http", "https"):
        raise ValueError(
            f"public url must start with http:// or https:// (got {trimmed!r})"
        )
    if not parsed.netloc:
        raise ValueError(f"public url missing host: {trimmed!r}")
    return trimmed


router = APIRouter()


def _relay_unreachable(err: Exception) -> HTTPException:
    """Build the canonical 502 for connect-errors / timeouts / 5xx."""
    return HTTPException(
        status_code=502,
        detail=f"relay unreachable at 127.0.0.1:{RELAY_PORT}: {err}",
    )


async def _proxy_get(
    path: str,
    *,
    params: Optional[dict[str, Any]] = None,
) -> Any:
    """Forward a GET to the relay, translating errors per this module's contract.

    - Network failures (connect/timeout) and 5xx responses → ``HTTPException(502)``.
    - 4xx responses → ``HTTPException(status, detail=<relay body>)`` passthrough.
    - 2xx → returns the parsed JSON body (or raw text if not JSON).
    """
    url = f"{_RELAY_BASE}{path}"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(url, params=params)
    except (httpx.TimeoutException, httpx.ConnectError, httpx.TransportError) as err:
        raise _relay_unreachable(err) from err
    except httpx.HTTPError as err:
        # Any other httpx-level error → treat as unreachable.
        raise _relay_unreachable(err) from err

    if 500 <= resp.status_code < 600:
        raise _relay_unreachable(
            RuntimeError(f"relay returned {resp.status_code}: {resp.text[:200]}")
        )

    if 400 <= resp.status_code < 500:
        # Pass 4xx through. Prefer JSON body if possible, else text.
        try:
            detail: Any = resp.json()
        except ValueError:
            detail = resp.text
        raise HTTPException(status_code=resp.status_code, detail=detail)

    # 2xx — return parsed JSON (or raw text as a fallback).
    try:
        return resp.json()
    except ValueError:
        return resp.text


@router.get("/overview")
async def get_overview() -> Any:
    """Aggregate relay status for the management tab."""
    return await _proxy_get("/relay/info")


@router.get("/sessions")
async def get_sessions() -> Any:
    """Paired-device session list (loopback branch on relay — no bearer needed)."""
    return await _proxy_get("/sessions")


@router.get("/bridge-activity")
async def get_bridge_activity(limit: Optional[int] = Query(default=None)) -> Any:
    """Recent bridge commands ring buffer."""
    params: dict[str, Any] = {}
    if limit is not None:
        params["limit"] = limit
    return await _proxy_get("/bridge/activity", params=params or None)


@router.get("/media")
async def get_media(include_expired: Optional[bool] = Query(default=None)) -> Any:
    """Active MediaRegistry tokens (basename-only, no absolute paths)."""
    params: dict[str, Any] = {}
    if include_expired is not None:
        # httpx serializes bool as "True"/"False"; relay expects lower-case.
        params["include_expired"] = "true" if include_expired else "false"
    return await _proxy_get("/media/inspect", params=params or None)


@router.get("/push")
async def get_push() -> dict[str, Any]:
    """Push console stub — no network call until FCM lands."""
    return {
        "configured": False,
        "reason": (
            "FCM not yet wired; see docs/plans/2026-04-18-dashboard-plugin.md "
            "and the 'Deferred Features' memory entry."
        ),
    }


async def _proxy(
    method: str,
    path: str,
    *,
    json: Optional[dict[str, Any]] = None,
) -> Any:
    """Forward an arbitrary method to the relay, translating errors."""
    url = f"{_RELAY_BASE}{path}"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.request(method, url, json=json)
    except (httpx.TimeoutException, httpx.ConnectError, httpx.TransportError) as err:
        raise _relay_unreachable(err) from err
    except httpx.HTTPError as err:
        raise _relay_unreachable(err) from err

    if 500 <= resp.status_code < 600:
        raise _relay_unreachable(
            RuntimeError(f"relay returned {resp.status_code}: {resp.text[:200]}")
        )
    if 400 <= resp.status_code < 500:
        try:
            detail: Any = resp.json()
        except ValueError:
            detail = resp.text
        raise HTTPException(status_code=resp.status_code, detail=detail)
    try:
        return resp.json()
    except ValueError:
        return resp.text


@router.post("/pairing")
async def mint_pairing(body: dict[str, Any] = Body(default_factory=dict)) -> Any:
    """Mint a fresh pairing code + return a signed QR payload.

    Body (all fields optional — relay fills them from its config):
      - host: "172.16.24.250"     API server host the phone will hit
                                  (defaults to RelayConfig.webapi_url host,
                                  resolved to a LAN-routable IP)
      - port: 8642                API server port
      - tls: false                API server TLS
      - api_key: "<token>"        Optional API bearer token
      - ttl_seconds: <int>        Session TTL
      - grants: {...}             Per-channel TTL map
      - transport_hint: "wss"|"ws"

    Multi-endpoint (ADR 24 — optional, additive):
      - mode: "auto"|"lan"|"tailscale"|"public"
                                  Triggers ``build_endpoint_candidates``
                                  from ``plugin.pair``. When present, the
                                  resulting endpoints array is forwarded
                                  to the relay so the signed payload
                                  bumps to v3.
      - public_url: "https://relay.example.com"
                                  Required when ``mode == "public"``.
                                  Optional (but consumed) in ``auto`` —
                                  when absent and the caller has pinned
                                  one via ``PUT /remote-access/public-url``
                                  we fall back to that stored value.

    The returned ``qr_payload`` matches the schema in the Android app's
    ``QrPairingScanner.kt``: top-level host/port/key/tls configure the
    Hermes API server, and the nested ``relay`` block (which the relay
    fills in with its own URL + the minted pairing code) configures WSS.
    """
    mode_raw = body.pop("mode", None)
    public_url_raw = body.pop("public_url", None)

    if mode_raw is not None:
        mode = str(mode_raw).strip().lower()
        # Lazy import so a bare dashboard install without plugin/pair.py
        # on sys.path (smoke tests, docs render, etc.) still loads the
        # module. Any failure here becomes a 500 via HTTPException below.
        try:
            from plugin.pair import build_endpoint_candidates, read_relay_config
        except ImportError as exc:
            raise HTTPException(
                status_code=500,
                detail=f"endpoint builder unavailable: {exc}",
            ) from exc

        # Operator may have stored the public URL via the Remote Access
        # tab — use that when the request body omits it. Empty string
        # after validation counts as "cleared" → treat as absent.
        effective_public_url: Optional[str] = None
        if isinstance(public_url_raw, str) and public_url_raw.strip():
            effective_public_url = public_url_raw.strip()
        else:
            pinned = _read_remote_state().get("public_url")
            if isinstance(pinned, str) and pinned.strip():
                effective_public_url = pinned.strip()

        # API defaults come from the same config chain ``pair.py`` uses so
        # the dashboard-minted QR matches what ``hermes-pair --mode auto``
        # would emit from the CLI.
        from plugin.pair import read_server_config  # local import: see above

        api_cfg = read_server_config()
        relay_cfg = read_relay_config()
        api_host = str(body.get("host") or api_cfg.get("host") or "127.0.0.1")
        api_port = int(body.get("port") or api_cfg.get("port") or 8642)
        api_tls = bool(body.get("tls") if body.get("tls") is not None else api_cfg.get("tls"))

        try:
            endpoints = build_endpoint_candidates(
                mode=mode,
                api_host=api_host,
                api_port=api_port,
                api_tls=api_tls,
                relay_host=relay_cfg["host"],
                relay_port=relay_cfg["port"],
                relay_tls=bool(relay_cfg.get("tls")),
                public_url=effective_public_url,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

        if endpoints:
            # The relay's ``/pairing/mint`` accepts an opaque ``endpoints``
            # list and mirrors it through HMAC signing verbatim. Drop
            # ``mode`` / ``public_url`` on the way out so the relay never
            # tries to interpret them.
            body["endpoints"] = endpoints

    return await _proxy("POST", "/pairing/mint", json=body)


# ── Remote Access tab ────────────────────────────────────────────────────────
#
# Routes that expose the Tailscale helper + a persisted "public URL"
# alongside an aggregate status endpoint. These sit at
# ``/api/plugins/hermes-relay/remote-access/*`` and are consumed by the
# dashboard's Remote Access React tab. Same loopback-trust model as the
# other routes in this file — the dashboard is loopback-only and we
# never accept callers from elsewhere.


def _tailscale_status_dict() -> dict[str, Any]:
    """Flatten ``tailscale.status()`` into a JSON-safe dict.

    The helper returns ``None`` when the CLI is missing or the daemon
    is sulking. We prefer to surface that explicitly so the UI can
    render a "not installed" state without a second round-trip.
    """
    try:
        from plugin.relay import tailscale
    except ImportError:
        return {"available": False, "reason": "helper not importable"}
    try:
        status = tailscale.status()
    except Exception as exc:  # defensive — helper promises not to raise
        return {"available": False, "reason": f"helper raised: {exc}"}
    if status is None:
        return {"available": False, "reason": "tailscale daemon not reachable"}
    return status


def _canonical_upstream_present() -> bool:
    try:
        from plugin.relay import tailscale
    except ImportError:
        return False
    try:
        return bool(tailscale.canonical_upstream_present())
    except Exception:
        return False


@router.get("/remote-access/status")
async def get_remote_access_status() -> dict[str, Any]:
    """Aggregate status for the Remote Access tab.

    Returns ``{tailscale, public, upstream_canonical}`` where ``public``
    reflects whatever URL the operator has pinned via
    ``PUT /remote-access/public-url``. ``reachable`` is reported as
    ``None`` here so the tab can call ``POST /remote-access/probe`` for
    a live check — doing it inline would make this endpoint's latency
    unpredictable.
    """
    state = _read_remote_state()
    pinned = state.get("public_url")
    if not isinstance(pinned, str) or not pinned.strip():
        pinned = None

    return {
        "tailscale": _tailscale_status_dict(),
        "public": {"url": pinned, "reachable": None},
        "upstream_canonical": _canonical_upstream_present(),
    }


@router.post("/remote-access/tailscale/enable")
async def tailscale_enable(
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    """Call ``tailscale.enable(port)`` and return its verbatim result."""
    try:
        from plugin.relay import tailscale
    except ImportError as exc:
        raise HTTPException(
            status_code=500,
            detail=f"tailscale helper unavailable: {exc}",
        ) from exc

    port_raw = body.get("port", RELAY_PORT)
    try:
        port = int(port_raw)
    except (TypeError, ValueError) as exc:
        raise HTTPException(
            status_code=400, detail=f"port must be an integer (got {port_raw!r})"
        ) from exc
    return tailscale.enable(port=port)


@router.post("/remote-access/tailscale/disable")
async def tailscale_disable(
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    """Call ``tailscale.disable(port)`` and return its verbatim result."""
    try:
        from plugin.relay import tailscale
    except ImportError as exc:
        raise HTTPException(
            status_code=500,
            detail=f"tailscale helper unavailable: {exc}",
        ) from exc

    port_raw = body.get("port", RELAY_PORT)
    try:
        port = int(port_raw)
    except (TypeError, ValueError) as exc:
        raise HTTPException(
            status_code=400, detail=f"port must be an integer (got {port_raw!r})"
        ) from exc
    return tailscale.disable(port=port)


@router.get("/remote-access/public-url")
async def get_public_url() -> dict[str, Any]:
    """Return the currently pinned public URL (or ``null`` when unset)."""
    state = _read_remote_state()
    pinned = state.get("public_url")
    if not isinstance(pinned, str) or not pinned.strip():
        return {"url": None}
    return {"url": pinned}


@router.put("/remote-access/public-url")
async def put_public_url(
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    """Pin / clear the public URL used by the next pairing QR.

    Empty string or ``null`` clears the pin. Validation limits the scheme
    to ``http`` / ``https`` — anything fancier (custom schemes, paths
    that would break ``urlparse``) is rejected with 400 rather than
    silently persisted.
    """
    raw = body.get("url")
    if raw is None:
        normalized = ""
    elif isinstance(raw, str):
        try:
            normalized = _validate_public_url(raw)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
    else:
        raise HTTPException(
            status_code=400, detail=f"'url' must be a string or null (got {type(raw).__name__})"
        )

    state = _read_remote_state()
    now = int(time.time())
    if normalized:
        state["public_url"] = normalized
        state["updated_at"] = now
    else:
        state.pop("public_url", None)
        state["cleared_at"] = now

    try:
        _write_remote_state(state)
    except OSError as exc:
        raise HTTPException(
            status_code=500,
            detail=f"could not persist {_remote_state_path()}: {exc}",
        ) from exc

    return {"url": normalized or None, "updated_at": state.get("updated_at")}


@router.post("/remote-access/probe")
async def probe_endpoints(
    body: dict[str, Any] = Body(default_factory=dict),
) -> dict[str, Any]:
    """Probe ``<candidate>/health`` for each URL in the request body.

    Body shape::

        { "candidates": ["https://relay.example.com:8767", ...] }

    Returns::

        { "results": [{ "url": "...", "reachable": bool, "status": int|null,
                        "latency_ms": int|null, "error": str|null }] }

    2s per-probe timeout; errors are captured per-entry so one flaky
    endpoint doesn't poison the whole response. This runs from the
    relay host's network perspective — useful for confirming the
    public URL is actually externally reachable without bouncing
    through a phone.
    """
    raw = body.get("candidates")
    if raw is None:
        raw = []
    if not isinstance(raw, list):
        raise HTTPException(
            status_code=400, detail="'candidates' must be an array of URLs"
        )

    results: list[dict[str, Any]] = []
    async with httpx.AsyncClient(timeout=2.0) as client:
        for entry in raw:
            if not isinstance(entry, str) or not entry.strip():
                results.append(
                    {
                        "url": entry,
                        "reachable": False,
                        "status": None,
                        "latency_ms": None,
                        "error": "empty url",
                    }
                )
                continue
            url = entry.rstrip("/") + "/health"
            t0 = time.perf_counter()
            try:
                resp = await client.get(url)
            except httpx.HTTPError as exc:
                results.append(
                    {
                        "url": entry,
                        "reachable": False,
                        "status": None,
                        "latency_ms": None,
                        "error": str(exc),
                    }
                )
                continue
            latency_ms = int((time.perf_counter() - t0) * 1000)
            results.append(
                {
                    "url": entry,
                    "reachable": 200 <= resp.status_code < 300,
                    "status": resp.status_code,
                    "latency_ms": latency_ms,
                    "error": None,
                }
            )
    return {"results": results}


@router.delete("/sessions/{token_prefix}")
async def revoke_session(
    token_prefix: str = Path(..., min_length=1, max_length=64),
) -> Any:
    """Revoke a paired device by token prefix (loopback branch on relay)."""
    return await _proxy("DELETE", f"/sessions/{token_prefix}")


__all__ = ["router", "RELAY_PORT"]
