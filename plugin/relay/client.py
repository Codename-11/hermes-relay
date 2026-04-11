"""Thin sync client helpers for in-process callers (tools, pair.py, etc).

Everything here talks to the local relay over the loopback interface using
``urllib.request`` (stdlib only — no httpx / requests dependency) to match
``plugin/pair.py``'s existing ``register_relay_code`` helper.

The public surface today is :func:`register_media`, used by the Android
screenshot tool to convert a temp-file path into an opaque token that the
phone can later fetch via a bearer-auth'd relay route.
"""

from __future__ import annotations

import json
import logging
import os
import urllib.error
import urllib.request

logger = logging.getLogger("hermes_relay.client")

_DEFAULT_PORT = 8767


def _default_port() -> int:
    """Honour ``RELAY_PORT`` if set, else fall back to 8767."""
    raw = os.environ.get("RELAY_PORT")
    if not raw:
        return _DEFAULT_PORT
    try:
        return int(raw)
    except ValueError:
        logger.warning(
            "Invalid RELAY_PORT=%r — using default %d", raw, _DEFAULT_PORT
        )
        return _DEFAULT_PORT


def _post_loopback(
    host: str,
    port: int,
    path: str,
    payload: dict,
    timeout: float,
) -> dict | None:
    """POST ``payload`` as JSON to ``http://host:port{path}`` on loopback.

    Returns the parsed JSON body on HTTP 200, otherwise ``None`` (after
    logging a warning). Never raises — all network errors collapse to a
    silent ``None`` so callers can fall through to a fallback path.
    """
    url = f"http://{host}:{port}{path}"
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                logger.warning(
                    "POST %s returned HTTP %d", url, resp.status
                )
                return None
            raw = resp.read().decode("utf-8")
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                logger.warning("POST %s returned non-JSON body: %r", url, raw[:200])
                return None
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError) as exc:
        logger.warning("POST %s failed: %s", url, exc)
        return None


def register_media(
    path: str,
    content_type: str,
    file_name: str | None = None,
    host: str = "127.0.0.1",
    port: int | None = None,
    timeout: float = 5.0,
) -> str | None:
    """Register ``path`` with the local relay and return an opaque token.

    Returns ``None`` on any failure (relay not running, HTTP error,
    validation rejected). Callers should treat ``None`` as "relay is
    unavailable, fall back to bare path form".
    """
    if port is None:
        port = _default_port()

    payload = {
        "path": path,
        "content_type": content_type,
        "file_name": file_name,
    }

    data = _post_loopback(host, port, "/media/register", payload, timeout)
    if data is None:
        return None

    if not data.get("ok"):
        logger.warning(
            "Relay rejected media registration: %s", data.get("error")
        )
        return None

    token = data.get("token")
    if not token or not isinstance(token, str):
        logger.warning("Relay returned no token in /media/register response")
        return None

    return token
