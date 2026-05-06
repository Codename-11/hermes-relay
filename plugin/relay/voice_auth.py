"""Narrow auth helper for relay voice routes.

``/voice/*`` accepts two credential families:

* Relay session tokens minted through pairing, gated by explicit voice grants.
* Hermes API bearer tokens, validated against the configured API server and
  accepted only after the transport guard allows the request.

This module is intentionally voice-specific. Importing it from bridge,
terminal, session-management, media, clipboard, or profile-write routes would
expand the API bearer token's blast radius.
"""

from __future__ import annotations

import hashlib
import logging
import os
import time
from dataclasses import dataclass
from ipaddress import ip_address, ip_network
from typing import Literal

import aiohttp
from aiohttp import web

from .auth import Session

logger = logging.getLogger("hermes_relay.voice_auth")

VoiceCapability = Literal["voice:config", "voice:stt", "voice:tts"]
AuthPrincipalKind = Literal["relay_session", "hermes_api"]

_VALIDATION_CACHE_TTL_SECONDS = 60.0
_VALIDATION_TIMEOUT_SECONDS = 5.0
_VALIDATION_CACHE: dict[tuple[str, str], float] = {}
_TRUE_ENV_VALUES = {"1", "true", "yes", "on"}
_TAILSCALE_CGNAT = ip_network("100.64.0.0/10")


@dataclass(frozen=True)
class AuthPrincipal:
    """Resolved caller identity for voice handlers."""

    kind: AuthPrincipalKind
    session: Session | None = None


def _env_enabled(name: str) -> bool:
    return os.getenv(name, "").strip().lower() in _TRUE_ENV_VALUES


def _request_config_enabled(request: web.Request, attr: str, env_name: str) -> bool:
    server = request.app.get("server")
    config = getattr(server, "config", None)
    if bool(getattr(config, attr, False)):
        return True
    return _env_enabled(env_name)


def _bearer_from_request(request: web.Request) -> str:
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        raise web.HTTPUnauthorized(
            text="Authorization: Bearer <session_or_api_token> required",
        )
    bearer = auth_header[len("Bearer ") :].strip()
    if not bearer:
        raise web.HTTPUnauthorized(text="empty bearer token")
    return bearer


def _token_fingerprint(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()[:16]


def _is_loopback_remote(request: web.Request) -> bool:
    remote = (request.remote or "").strip().lower()
    if remote in {"localhost", "ip6-localhost"}:
        return True
    if not remote:
        return False

    # aiohttp usually exposes a bare peer host here. Keep a small amount of
    # normalization for IPv6 bracket notation and IPv4-mapped loopback.
    remote = remote.strip("[]")
    if remote.startswith("::ffff:"):
        remote = remote.removeprefix("::ffff:")
    try:
        return ip_address(remote).is_loopback
    except ValueError:
        return False


def _is_tailnet_remote(request: web.Request) -> bool:
    """Return true for direct Tailscale/WireGuard CGNAT peer addresses."""
    remote = (request.remote or "").strip().lower()
    if not remote:
        return False
    remote = remote.strip("[]")
    if remote.startswith("::ffff:"):
        remote = remote.removeprefix("::ffff:")
    try:
        return ip_address(remote) in _TAILSCALE_CGNAT
    except ValueError:
        return False


def _trusted_forwarded_https(request: web.Request) -> bool:
    if not _request_config_enabled(
        request,
        "trust_proxy_headers",
        "RELAY_TRUST_PROXY_HEADERS",
    ):
        return False
    forwarded_proto = request.headers.get("X-Forwarded-Proto", "")
    first_proto = forwarded_proto.split(",", 1)[0].strip().lower()
    return first_proto == "https"


def _request_is_secure_enough_for_api_bearer(request: web.Request) -> bool:
    if _is_loopback_remote(request):
        return True
    if _is_tailnet_remote(request):
        return True
    if _request_config_enabled(
        request,
        "allow_insecure_api_bearer",
        "RELAY_ALLOW_INSECURE_API_BEARER",
    ):
        return True
    if request.scheme == "https":
        return True
    transport = request.transport
    if transport is not None and transport.get_extra_info("ssl_object") is not None:
        return True
    return _trusted_forwarded_https(request)


def _validation_url(webapi_url: str) -> str:
    base = (webapi_url or "http://localhost:8642").rstrip("/")
    return f"{base}/v1/models"


async def _probe_hermes_api_token(webapi_url: str, token: str) -> bool:
    url = _validation_url(webapi_url)
    timeout = aiohttp.ClientTimeout(total=_VALIDATION_TIMEOUT_SECONDS)
    try:
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(
                url,
                headers={"Authorization": f"Bearer {token}"},
            ) as response:
                await response.read()
                return 200 <= response.status < 400
    except (aiohttp.ClientError, TimeoutError, OSError) as exc:
        logger.info(
            "Hermes API token validation failed against %s: %s",
            url,
            exc.__class__.__name__,
        )
        return False


async def _validate_hermes_api_token(webapi_url: str, token: str) -> bool:
    token_hash = _token_fingerprint(token)
    base = (webapi_url or "http://localhost:8642").rstrip("/")
    cache_key = (base, token_hash)
    now = time.monotonic()

    cached_until = _VALIDATION_CACHE.get(cache_key)
    if cached_until is not None and cached_until > now:
        return True

    ok = await _probe_hermes_api_token(base, token)
    if ok:
        _VALIDATION_CACHE[cache_key] = now + _VALIDATION_CACHE_TTL_SECONDS
    else:
        _VALIDATION_CACHE.pop(cache_key, None)
    return ok


async def require_voice_auth(
    request: web.Request,
    capability: VoiceCapability,
) -> AuthPrincipal:
    """Require a relay session grant or a valid Hermes API token."""
    bearer = _bearer_from_request(request)
    server = request.app["server"]

    session = server.sessions.get_session(bearer)
    if session is not None:
        if session.channel_is_expired(capability):
            raise web.HTTPForbidden(text=f"session lacks active {capability} grant")
        return AuthPrincipal(kind="relay_session", session=session)

    token_id = _token_fingerprint(bearer)
    if not _request_is_secure_enough_for_api_bearer(request):
        logger.warning(
            "Voice route rejected: insecure Hermes API bearer from %s token_id=%s",
            request.remote or "unknown",
            token_id,
        )
        raise web.HTTPForbidden(
            text=(
                "Hermes API bearer token requires HTTPS outside loopback "
                "or Tailscale, or RELAY_ALLOW_INSECURE_API_BEARER=1"
            ),
        )

    ok = await _validate_hermes_api_token(server.config.webapi_url, bearer)
    if not ok:
        logger.info(
            "Voice route rejected: invalid Hermes API bearer from %s token_id=%s",
            request.remote or "unknown",
            token_id,
        )
        raise web.HTTPUnauthorized(text="invalid or expired bearer token")

    return AuthPrincipal(kind="hermes_api")


__all__ = [
    "AuthPrincipal",
    "VoiceCapability",
    "require_voice_auth",
]
