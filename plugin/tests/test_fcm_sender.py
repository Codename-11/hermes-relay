"""Unit tests for BYO FCM sender + token store (no network)."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from plugin.relay.fcm_sender import (
    FcmTokenStore,
    build_wake_message,
    fcm_enabled,
    load_service_account_from_env,
    send_wake,
)


class FcmSenderUnitTests(unittest.TestCase):
    def test_load_sa_from_inline_json(self) -> None:
        sa = {
            "type": "service_account",
            "project_id": "demo",
            "client_email": "a@b.iam.gserviceaccount.com",
            "private_key": "-----BEGIN PRIVATE KEY-----\nX\n-----END PRIVATE KEY-----\n",
        }
        env = {"RELAY_FCM_SERVICE_ACCOUNT_JSON": json.dumps(sa)}
        loaded = load_service_account_from_env(env)
        assert loaded is not None
        self.assertEqual(loaded["project_id"], "demo")

    def test_load_sa_from_path(self) -> None:
        sa = {
            "project_id": "p",
            "client_email": "c@x",
            "private_key": "k",
        }
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "sa.json"
            path.write_text(json.dumps(sa), encoding="utf-8")
            loaded = load_service_account_from_env(
                {"FCM_SERVICE_ACCOUNT_JSON": str(path)}
            )
        assert loaded is not None
        self.assertEqual(loaded["client_email"], "c@x")

    def test_fcm_enabled_respects_flag(self) -> None:
        self.assertFalse(
            fcm_enabled(
                {
                    "RELAY_FCM_ENABLED": "0",
                    "RELAY_FCM_SERVICE_ACCOUNT_JSON": "{}",
                }
            )
        )
        sa = json.dumps(
            {
                "project_id": "p",
                "client_email": "c@x",
                "private_key": "k",
            }
        )
        self.assertTrue(fcm_enabled({"RELAY_FCM_SERVICE_ACCOUNT_JSON": sa}))

    def test_build_wake_message_data_only(self) -> None:
        msg = build_wake_message(device_token="tok", message_id="m1", title="Hi")
        body = msg["message"]
        self.assertEqual(body["token"], "tok")
        self.assertEqual(body["data"]["type"], "hermes_wake")
        self.assertEqual(body["android"]["priority"], "HIGH")
        self.assertNotIn("notification", body)

    def test_token_store_roundtrip(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            store = FcmTokenStore(Path(td) / "tokens.json")
            store.upsert(device_id="dev1", token="abc", project_id="p")
            store2 = FcmTokenStore(Path(td) / "tokens.json")
            self.assertEqual(store2.tokens(), ["abc"])
            store2.remove("dev1")
            self.assertEqual(store2.tokens(), [])

    def test_send_wake_uses_http_v1(self) -> None:
        sa = {
            "project_id": "demo-proj",
            "client_email": "a@b",
            "private_key": "k",
        }
        captured: dict = {}

        class FakeResp:
            status = 200

            def read(self) -> bytes:
                return b'{"name":"projects/demo-proj/messages/1"}'

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def fake_open(req, timeout=15):
            captured["url"] = req.full_url
            captured["auth"] = req.headers.get("Authorization")
            captured["body"] = req.data
            return FakeResp()

        result = send_wake(
            device_token="device-token",
            message_id="mid",
            service_account=sa,
            access_token="ya29.test",
            opener=fake_open,
        )
        self.assertTrue(result.ok)
        self.assertIn("demo-proj", captured["url"])
        self.assertTrue(captured["auth"].startswith("Bearer ya29"))
        payload = json.loads(captured["body"].decode("utf-8"))
        self.assertEqual(payload["message"]["token"], "device-token")


if __name__ == "__main__":
    unittest.main()
