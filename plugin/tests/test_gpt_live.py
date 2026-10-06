from __future__ import annotations

import asyncio
import base64
import json

import httpx
import pytest

from plugin.relay import gpt_live
from plugin.relay.realtime_agent.providers.openai import AuthToken


OFFER = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
ANSWER = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"


def _oauth_token(account_id: str = "acct_test") -> str:
    payload = {
        "https://api.openai.com/auth": {
            "chatgpt_account_id": account_id,
        }
    }
    encoded = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    return f"header.{encoded}.signature"


def test_readiness_uses_codex_subscription_without_api_key(monkeypatch):
    monkeypatch.delenv("RELAY_GPT_LIVE_SUBSCRIPTION_MODEL", raising=False)
    monkeypatch.delenv("RELAY_GPT_LIVE_SUBSCRIPTION_VOICE", raising=False)
    monkeypatch.delenv("TALK_LIVE_SUBSCRIPTION_MODEL", raising=False)
    monkeypatch.delenv("TALK_LIVE_SUBSCRIPTION_VOICE", raising=False)
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken(_oauth_token(), "codex-cli:chatgpt"),
    )
    state = gpt_live.readiness()
    assert state.available is True
    assert state.auth_source == "codex-cli:chatgpt"
    assert state.model == "gpt-live-1-codex"
    assert state.voice == "cove"


def test_create_session_posts_quicksilver_subscription_call(monkeypatch):
    token = _oauth_token("acct_123")
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken(token, "hermes:openai-codex"),
    )
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(
            201,
            text=ANSWER,
            headers={"openai-session-id": "rtc_test123"},
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await gpt_live.create_session(
                OFFER,
                [{"role": "user", "text": "history stays in Hermes"}],
                client=client,
            )

    result = asyncio.run(run())

    request = captured["request"]
    assert str(request.url) == gpt_live.SUBSCRIPTION_CALL_URL
    assert request.headers["authorization"] == f"Bearer {token}"
    assert request.headers["openai-alpha"] == "quicksilver=v2"
    assert request.headers["chatgpt-account-id"] == "acct_123"
    payload = json.loads(request.content)
    assert payload["sdp"] == OFFER
    assert payload["session"]["model"] == "gpt-live-1-codex"
    assert payload["session"]["delegation"] == {"type": "client"}
    assert "input" not in payload["session"]
    assert result["session"]["id"] == "rtc_test123"
    assert result["transport"]["sdp"] == ANSWER
    assert result["event_dialect"] == "subscription"
    assert result["auth_source"] == "hermes:openai-codex"


def test_create_session_fails_closed_without_codex(monkeypatch):
    monkeypatch.setattr(gpt_live, "_resolve_codex_oauth_token", lambda: None)
    with pytest.raises(gpt_live.GptLiveUnavailable, match="API-key fallback was not attempted"):
        asyncio.run(gpt_live.create_session(OFFER))


def test_create_session_fails_closed_without_account_id(monkeypatch):
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken("not-a-jwt", "codex-cli:chatgpt"),
    )
    with pytest.raises(gpt_live.GptLiveUnavailable, match="account id"):
        asyncio.run(gpt_live.create_session(OFFER))


def test_create_session_does_not_echo_provider_body(monkeypatch):
    token = _oauth_token()
    monkeypatch.setattr(
        gpt_live,
        "_resolve_codex_oauth_token",
        lambda: AuthToken(token, "codex-cli:chatgpt"),
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(403, text=f"sensitive upstream detail {token}")

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await gpt_live.create_session(OFFER, client=client)

    with pytest.raises(gpt_live.GptLiveRejected) as error:
        asyncio.run(run())
    assert token not in str(error.value)
