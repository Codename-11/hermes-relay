"""Tests for the phone platform adapter's pure helpers + standalone sender.

The adapter's :class:`PhoneAdapter` binds to ``gateway.platforms.base`` which
is only importable inside the live hermes-agent process, so these tests cover
the gateway-independent surface:

  * env-driven gating (``check_requirements`` / ``validate_config`` /
    ``is_connected`` / ``_phone_enabled``)
  * relay URL resolution precedence (``_relay_base_url``)
  * payload construction (``_build_message_payload`` — truncation,
    surfacing/title lifting, home-channel defaults)
  * ``_env_enablement`` seeding
  * ``_standalone_send`` (the out-of-process cron / send_message path) over a
    fake httpx client — success, no-phone (503), disabled, and error shapes.

Runs under plain ``unittest`` (no pytest, no ``responses``) to skip the
repo's ``conftest.py``. Run with::

    python -m unittest plugin.tests.test_phone_platform
"""

from __future__ import annotations

import asyncio
import os
import unittest
from typing import Any, Optional

from plugin import phone_platform as pp


def _run(coro):
    """Drive a coroutine to completion on a fresh event loop."""
    return asyncio.new_event_loop().run_until_complete(coro)


# ── Fake httpx surface ────────────────────────────────────────────────────


class _FakeResponse:
    def __init__(self, status_code: int, payload: Optional[dict] = None, text: str = ""):
        self.status_code = status_code
        self._payload = payload
        self.text = text

    def json(self) -> Any:
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


class _FakeAsyncClient:
    """Records the single POST and returns a pre-seeded response.

    Supports both the ``async with httpx.AsyncClient(...) as c`` form used by
    ``_standalone_send`` and direct construction.
    """

    last_call: dict[str, Any] = {}

    def __init__(self, response: _FakeResponse, *, raises: Optional[Exception] = None):
        self._response = response
        self._raises = raises

    async def __aenter__(self) -> "_FakeAsyncClient":
        return self

    async def __aexit__(self, *exc) -> None:
        return None

    async def post(self, url, json=None, headers=None, timeout=None) -> _FakeResponse:
        type(self).last_call = {
            "url": url,
            "json": json,
            "headers": headers,
            "timeout": timeout,
        }
        if self._raises is not None:
            raise self._raises
        return self._response


class _FakeHttpx:
    """Stand-in for the ``httpx`` module exposing ``AsyncClient``."""

    def __init__(self, response: _FakeResponse, *, raises: Optional[Exception] = None):
        self._response = response
        self._raises = raises

    def AsyncClient(self, *args, **kwargs) -> _FakeAsyncClient:  # noqa: N802 - mimic httpx
        return _FakeAsyncClient(self._response, raises=self._raises)


_ENV_KEYS = (
    "PHONE_ENABLED",
    "PHONE_RELAY_URL",
    "PHONE_RELAY_TOKEN",
    "PHONE_HOME_CHANNEL",
    "PHONE_HOME_CHANNEL_NAME",
    "ANDROID_BRIDGE_URL",
    "ANDROID_RELAY_PORT",
    "RELAY_PORT",
)


class _EnvIsolated(unittest.TestCase):
    """Base that snapshots + restores the env keys the adapter reads."""

    def setUp(self) -> None:
        self._saved = {k: os.environ.get(k) for k in _ENV_KEYS}
        for k in _ENV_KEYS:
            os.environ.pop(k, None)
        self._saved_httpx = pp.httpx
        self._saved_avail = pp.HTTPX_AVAILABLE

    def tearDown(self) -> None:
        for k, v in self._saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        pp.httpx = self._saved_httpx
        pp.HTTPX_AVAILABLE = self._saved_avail


# ── Gating ────────────────────────────────────────────────────────────────


class GatingTests(_EnvIsolated):
    def test_disabled_by_default(self) -> None:
        self.assertFalse(pp._phone_enabled())
        self.assertFalse(pp.is_connected(object()))

    def test_truthy_spellings_enable(self) -> None:
        for val in ("1", "true", "TRUE", "yes", "on"):
            os.environ["PHONE_ENABLED"] = val
            self.assertTrue(pp._phone_enabled(), val)

    def test_falsey_spellings_stay_off(self) -> None:
        for val in ("0", "false", "no", "off", "", "bogus"):
            os.environ["PHONE_ENABLED"] = val
            self.assertFalse(pp._phone_enabled(), val)

    def test_check_requirements_needs_httpx_and_enabled(self) -> None:
        os.environ["PHONE_ENABLED"] = "1"
        pp.HTTPX_AVAILABLE = True
        self.assertTrue(pp.check_requirements())
        pp.HTTPX_AVAILABLE = False
        self.assertFalse(pp.check_requirements())

    def test_validate_config_env_or_extra(self) -> None:
        class _Cfg:
            extra = {"enabled": True}

        self.assertTrue(pp.validate_config(_Cfg()))  # via extra
        self.assertFalse(pp.validate_config(object()))  # neither
        os.environ["PHONE_ENABLED"] = "1"
        self.assertTrue(pp.validate_config(object()))  # via env


# ── URL resolution ────────────────────────────────────────────────────────


class RelayUrlTests(_EnvIsolated):
    def test_default(self) -> None:
        self.assertEqual(pp._relay_base_url(), "http://localhost:8767")

    def test_explicit_phone_relay_url_wins(self) -> None:
        os.environ["PHONE_RELAY_URL"] = "https://relay.example:9000/"
        os.environ["ANDROID_BRIDGE_URL"] = "http://other:1"
        self.assertEqual(pp._relay_base_url(), "https://relay.example:9000")

    def test_falls_back_to_android_bridge_url(self) -> None:
        os.environ["ANDROID_BRIDGE_URL"] = "http://192.168.1.5:8767/"
        self.assertEqual(pp._relay_base_url(), "http://192.168.1.5:8767")

    def test_port_override(self) -> None:
        os.environ["ANDROID_RELAY_PORT"] = "8888"
        self.assertEqual(pp._relay_base_url(), "http://localhost:8888")
        os.environ.pop("ANDROID_RELAY_PORT")
        os.environ["RELAY_PORT"] = "7777"
        self.assertEqual(pp._relay_base_url(), "http://localhost:7777")

    def test_token_header_only_when_set(self) -> None:
        url, headers = pp._relay_url_and_headers()
        self.assertTrue(url.endswith("/phone/message"))
        self.assertNotIn("Authorization", headers)
        os.environ["PHONE_RELAY_TOKEN"] = "abc"
        _, headers = pp._relay_url_and_headers()
        self.assertEqual(headers["Authorization"], "Bearer abc")


# ── Payload building ───────────────────────────────────────────────────────


class PayloadTests(_EnvIsolated):
    def test_defaults(self) -> None:
        p = pp._build_message_payload(None, "hello")
        self.assertEqual(p["chat_id"], "phone")
        self.assertEqual(p["text"], "hello")
        self.assertEqual(p["title"], "Phone")
        self.assertIsNone(p["surfacing"])
        self.assertEqual(p["metadata"], {})

    def test_home_channel_env(self) -> None:
        os.environ["PHONE_HOME_CHANNEL"] = "dev-phone"
        os.environ["PHONE_HOME_CHANNEL_NAME"] = "Dev Phone"
        p = pp._build_message_payload(None, "hi")
        self.assertEqual(p["chat_id"], "dev-phone")
        self.assertEqual(p["title"], "Dev Phone")

    def test_truncation(self) -> None:
        long = "x" * (pp.MAX_MESSAGE_LENGTH + 50)
        p = pp._build_message_payload("c", long)
        self.assertEqual(len(p["text"]), pp.MAX_MESSAGE_LENGTH)

    def test_surfacing_and_title_lifted_from_metadata(self) -> None:
        p = pp._build_message_payload(
            "c", "hi", metadata={"surfacing": "inbox", "title": "Alert", "k": "v"}
        )
        self.assertEqual(p["surfacing"], "inbox")
        self.assertEqual(p["title"], "Alert")
        # Lifted keys removed; other metadata preserved.
        self.assertEqual(p["metadata"], {"k": "v"})

    def test_explicit_surfacing_arg_wins(self) -> None:
        p = pp._build_message_payload(
            "c", "hi", surfacing="session", metadata={"surfacing": "inbox"}
        )
        self.assertEqual(p["surfacing"], "session")


# ── env_enablement ─────────────────────────────────────────────────────────


class EnvEnablementTests(_EnvIsolated):
    def test_none_when_disabled(self) -> None:
        self.assertIsNone(pp._env_enablement())

    def test_seed_when_enabled(self) -> None:
        os.environ["PHONE_ENABLED"] = "1"
        os.environ["PHONE_HOME_CHANNEL"] = "myphone"
        seed = pp._env_enablement()
        self.assertIsNotNone(seed)
        assert seed is not None
        self.assertTrue(seed["enabled"])
        self.assertEqual(seed["home_channel"], {"chat_id": "myphone", "name": "Phone"})
        self.assertEqual(seed["relay_url"], "http://localhost:8767")


# ── standalone sender ──────────────────────────────────────────────────────


class StandaloneSendTests(_EnvIsolated):
    def test_disabled_short_circuits(self) -> None:
        pp.HTTPX_AVAILABLE = True
        out = _run(pp._standalone_send(object(), "phone", "hi"))
        self.assertIn("disabled", out["error"])

    def test_httpx_missing(self) -> None:
        os.environ["PHONE_ENABLED"] = "1"
        pp.HTTPX_AVAILABLE = False
        out = _run(pp._standalone_send(object(), "phone", "hi"))
        self.assertIn("httpx", out["error"])

    def test_success(self) -> None:
        os.environ["PHONE_ENABLED"] = "1"
        pp.HTTPX_AVAILABLE = True
        pp.httpx = _FakeHttpx(_FakeResponse(200, {"delivered": True, "message_id": "m1"}))
        out = _run(pp._standalone_send(object(), "phone", "hello"))
        self.assertEqual(out["success"], True)
        self.assertEqual(out["platform"], "phone")
        self.assertEqual(out["chat_id"], "phone")
        self.assertEqual(out["message_id"], "m1")
        # The POST hit the right URL with the right body.
        call = _FakeAsyncClient.last_call
        self.assertTrue(call["url"].endswith("/phone/message"))
        self.assertEqual(call["json"]["text"], "hello")

    def test_no_phone_503(self) -> None:
        os.environ["PHONE_ENABLED"] = "1"
        pp.HTTPX_AVAILABLE = True
        pp.httpx = _FakeHttpx(_FakeResponse(503))
        out = _run(pp._standalone_send(object(), "phone", "hello"))
        self.assertIn("no phone connected", out["error"].lower())

    def test_relay_unreachable(self) -> None:
        os.environ["PHONE_ENABLED"] = "1"
        pp.HTTPX_AVAILABLE = True
        pp.httpx = _FakeHttpx(_FakeResponse(200), raises=OSError("conn refused"))
        out = _run(pp._standalone_send(object(), "phone", "hello"))
        self.assertIn("unreachable", out["error"])


if __name__ == "__main__":
    unittest.main()
