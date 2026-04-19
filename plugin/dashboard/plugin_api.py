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

import os
from typing import Any, Optional

import httpx
from fastapi import APIRouter, Body, HTTPException, Path, Query

# Read once at import time — hermes-agent restarts pick up env changes.
RELAY_PORT: int = int(os.environ.get("HERMES_RELAY_PORT", "8767"))
_RELAY_BASE: str = f"http://127.0.0.1:{RELAY_PORT}"
_TIMEOUT: float = 5.0

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

    The returned ``qr_payload`` matches the schema in the Android app's
    ``QrPairingScanner.kt``: top-level host/port/key/tls configure the
    Hermes API server, and the nested ``relay`` block (which the relay
    fills in with its own URL + the minted pairing code) configures WSS.
    """
    return await _proxy("POST", "/pairing/mint", json=body)


@router.delete("/sessions/{token_prefix}")
async def revoke_session(
    token_prefix: str = Path(..., min_length=1, max_length=64),
) -> Any:
    """Revoke a paired device by token prefix (loopback branch on relay)."""
    return await _proxy("DELETE", f"/sessions/{token_prefix}")


__all__ = ["router", "RELAY_PORT"]
