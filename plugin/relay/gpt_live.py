"""GPT-Live WebRTC negotiation owned by Hermes Relay.

The ChatGPT subscription lane is intentionally separate from the public
OpenAI API-key lane. Relay borrows the operator's existing Codex/ChatGPT OAuth
bearer, negotiates the WebRTC call through ChatGPT's Codex realtime endpoint,
and returns only the SDP answer to Android. No credential reaches the phone and
there is no automatic metered API-key fallback.

The subscription call/header shape is adapted from Hermes Talk's proven Live
transport, which in turn credits OpenClaw's MIT-licensed quicksilver transport.
Keep this implementation deliberately small: Android owns media and the data
channel; Relay only performs the authenticated SDP exchange.
"""

from __future__ import annotations

import base64
import json
import os
import re
import uuid
from dataclasses import dataclass
from typing import Any, Mapping
from urllib.parse import urlparse

import httpx

from .realtime_agent.providers.openai import AuthToken, _resolve_codex_oauth_token

DEFAULT_MODEL = "gpt-live-1-codex"
DEFAULT_VOICE = "cove"
SUBSCRIPTION_CALL_URL = (
    "https://chatgpt.com/backend-api/codex/realtime/calls"
    "?intent=quicksilver&architecture=avas"
)
MAX_SDP_BYTES = 256 * 1024
TIMEOUT = httpx.Timeout(35.0, connect=10.0, read=30.0, write=30.0)

_CALL_ID = re.compile(
    r"^(?:rtc_[A-Za-z0-9_-]{1,128}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$"
)
_OPENAI_AUTH_CLAIM = "https://api.openai.com/auth"


class GptLiveUnavailable(RuntimeError):
    """GPT-Live cannot be started with the configured subscription auth lane."""


class GptLiveRejected(RuntimeError):
    """The subscription service rejected a bounded GPT-Live session request."""


@dataclass(frozen=True, slots=True)
class GptLiveReadiness:
    available: bool
    reason: str | None
    auth_source: str | None
    model: str
    voice: str


def _model() -> str:
    return (
        os.getenv("RELAY_GPT_LIVE_SUBSCRIPTION_MODEL", "").strip()
        or os.getenv("TALK_LIVE_SUBSCRIPTION_MODEL", "").strip()
        or DEFAULT_MODEL
    )


def _voice() -> str:
    return (
        os.getenv("RELAY_GPT_LIVE_SUBSCRIPTION_VOICE", "").strip()
        or os.getenv("TALK_LIVE_SUBSCRIPTION_VOICE", "").strip()
        or DEFAULT_VOICE
    )


def _jwt_payload(token: str) -> dict[str, Any] | None:
    parts = token.split(".")
    if len(parts) < 2:
        return None
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        decoded = json.loads(base64.urlsafe_b64decode(payload))
    except Exception:
        return None
    return decoded if isinstance(decoded, dict) else None


def _chatgpt_account_id(token: str) -> str | None:
    payload = _jwt_payload(token)
    if payload is None:
        return None
    claims = payload.get(_OPENAI_AUTH_CLAIM)
    if not isinstance(claims, dict):
        return None
    value = claims.get("chatgpt_account_id")
    return value.strip() if isinstance(value, str) and value.strip() else None


def _auth() -> tuple[AuthToken, str]:
    auth = _resolve_codex_oauth_token()
    if auth is None:
        raise GptLiveUnavailable(
            "GPT-Live subscription needs a usable Codex/ChatGPT login. Run `codex login` "
            "or `hermes auth login openai-codex`; API-key fallback was not attempted."
        )
    account_id = _chatgpt_account_id(auth.value)
    if account_id is None:
        raise GptLiveUnavailable(
            "The Codex/ChatGPT login does not contain a ChatGPT account id. Re-run `codex login`; "
            "API-key fallback was not attempted."
        )
    return auth, account_id


def readiness() -> GptLiveReadiness:
    try:
        auth, _account_id = _auth()
    except GptLiveUnavailable as exc:
        return GptLiveReadiness(
            available=False,
            reason=str(exc),
            auth_source=None,
            model=_model(),
            voice=_voice(),
        )
    return GptLiveReadiness(
        available=True,
        reason=None,
        auth_source=auth.source,
        model=_model(),
        voice=_voice(),
    )


def validate_sdp(sdp: Any) -> str:
    if not isinstance(sdp, str) or not sdp.strip():
        raise ValueError("An SDP offer is required")
    encoded = sdp.encode("utf-8")
    if len(encoded) > MAX_SDP_BYTES or "\x00" in sdp:
        raise ValueError("GPT-Live SDP offer is invalid or too large")
    if not re.match(r"^v=0\r?\n", sdp) or not re.search(r"(?:^|\n)m=audio ", sdp):
        raise ValueError("GPT-Live SDP offer must contain an audio media section")
    return sdp


def _session_config() -> dict[str, Any]:
    return {
        "model": _model(),
        "instructions": (
            "You are the live voice front end for Hermes. Speak naturally and briefly. "
            "Delegate questions, reasoning, tools, memory, current information, and actions "
            "to the client backend before giving a substantive answer. Small talk and brief "
            "clarifications may be handled directly. When a backend task is running, give one "
            "short acknowledgement and wait for the client result. Stop speaking when interrupted."
        ),
        "audio": {"output": {"voice": _voice()}},
        "delegation": {"type": "client"},
    }


def _headers(auth: AuthToken, account_id: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {auth.value}",
        "Content-Type": "application/json",
        "OpenAI-Alpha": "quicksilver=v2",
        "chatgpt-account-id": account_id,
        "session-id": str(uuid.uuid4()),
        "thread-id": str(uuid.uuid4()),
        "x-session-id": str(uuid.uuid4()),
    }


def _call_id(headers: Mapping[str, str]) -> str:
    session_id = str(headers.get("openai-session-id") or "").strip()
    location = str(headers.get("location") or "").strip()
    if location:
        if len(location) > 512:
            raise GptLiveRejected("GPT-Live returned an invalid call location")
        parsed = urlparse(location)
        if parsed.netloc and parsed.netloc not in {"api.openai.com", "chatgpt.com"}:
            raise GptLiveRejected("GPT-Live returned an unexpected call location")
        if parsed.query or parsed.fragment or parsed.scheme not in {"", "https"}:
            raise GptLiveRejected("GPT-Live returned an invalid call location")
        candidates = [part for part in parsed.path.split("/") if _CALL_ID.fullmatch(part)]
        if candidates:
            candidate = candidates[-1]
            if session_id and session_id != candidate:
                raise GptLiveRejected("GPT-Live returned conflicting call identifiers")
            return candidate
    if _CALL_ID.fullmatch(session_id):
        return session_id
    raise GptLiveRejected("GPT-Live response did not contain a valid call identifier")


async def create_session(
    sdp: str,
    history: Any = None,
    *,
    client: httpx.AsyncClient | None = None,
) -> dict[str, Any]:
    """Negotiate one subscription-backed GPT-Live WebRTC call.

    ``history`` is accepted for API compatibility with the Android request but
    intentionally not sent to the private subscription endpoint. Hermes owns
    durable conversation context; GPT-Live only needs the current voice turn.
    """
    del history
    offer = validate_sdp(sdp)
    auth, account_id = _auth()
    payload = {"sdp": offer, "session": _session_config()}
    owns_client = client is None
    http = client or httpx.AsyncClient(timeout=TIMEOUT)
    try:
        response = await http.post(
            SUBSCRIPTION_CALL_URL,
            headers=_headers(auth, account_id),
            json=payload,
            follow_redirects=False,
        )
    except Exception as exc:
        raise GptLiveRejected("GPT-Live subscription session negotiation failed") from exc
    finally:
        if owns_client:
            await http.aclose()
    if response.status_code not in {200, 201}:
        # Do not include the upstream body: authentication failures can echo
        # account metadata. The status code is enough for diagnostics.
        raise GptLiveRejected(
            f"ChatGPT rejected GPT-Live subscription session creation (HTTP {response.status_code})"
        )
    try:
        answer = response.content.decode("utf-8")
    except UnicodeError as exc:
        raise GptLiveRejected("GPT-Live returned an invalid SDP answer") from exc
    validate_sdp(answer)
    session_id = _call_id(response.headers)
    if auth.value in answer or account_id in answer:
        raise GptLiveRejected("GPT-Live SDP response reflected private credentials")
    return {
        "session": {"id": session_id},
        "transport": {"type": "webrtc", "sdp": answer},
        "protocol": "subscription",
        "event_dialect": "subscription",
        "auth_source": auth.source,
        "model": _model(),
        "voice": _voice(),
    }
