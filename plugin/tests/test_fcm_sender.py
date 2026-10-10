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
    truncate_preview,
)


class FcmSenderUnitTests(unittest.TestCase):
    def test_load_sa_from_inline_json(self) -> None:
        sa = {
            "type": "service_account",
            "project_id": "demo",
            "client_email": "a@b.iam.gserviceaccount.com",
            "private_key": "k",
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

    def test_build_wake_message_includes_preview(self) -> None:
        msg = build_wake_message(
            device_token="tok",
            message_id="m1",
            title="Hi",
            preview="  hello   world  " + ("x" * 200),
        )
        data = msg["message"]["data"]
        self.assertEqual(data["type"], "hermes_wake")
        self.assertIn("preview", data)
        self.assertTrue(data["preview"].startswith("hello world"))
        self.assertLessEqual(len(data["preview"]), 160)
        self.assertTrue(data["preview"].endswith("…"))
        self.assertEqual(truncate_preview(""), "")
        self.assertEqual(truncate_preview("  a  b "), "a b")

    def test_build_wake_message_omits_empty_preview(self) -> None:
        msg = build_wake_message(device_token="tok", message_id="m1", preview="   ")
        self.assertNotIn("preview", msg["message"]["data"])

    def test_token_store_roundtrip(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            store = FcmTokenStore(Path(td) / "tokens.json")
            store.upsert(device_id="dev1", token="abc", project_id="p")
            store2 = FcmTokenStore(Path(td) / "tokens.json")
            self.assertEqual(store2.tokens(), ["abc"])
            store2.remove("dev1")
            self.assertEqual(store2.tokens(), [])

    def test_token_store_include_preview_default(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            store = FcmTokenStore(Path(td) / "tokens.json")
            store.upsert(device_id="dev1", token="abc", project_id="p")
            regs = store.registrations()
            self.assertEqual(len(regs), 1)
            self.assertTrue(regs[0]["include_preview"])
            store.upsert(
                device_id="dev1",
                token="abc",
                project_id="p",
                include_preview=False,
            )
            self.assertFalse(store.registrations()[0]["include_preview"])


    def test_load_sa_from_default_path(self) -> None:
        sa = {
            "project_id": "p",
            "client_email": "c@x",
            "private_key": "k",
        }
        with tempfile.TemporaryDirectory() as td:
            home = Path(td)
            dest = home / "secrets" / "fcm-sa.json"
            dest.parent.mkdir(parents=True)
            dest.write_text(json.dumps(sa), encoding="utf-8")
            loaded = load_service_account_from_env({}, home=home)
        assert loaded is not None
        self.assertEqual(loaded["project_id"], "p")

    def test_install_and_status(self) -> None:
        from plugin.relay.fcm_sender import (
            clear_service_account,
            fcm_host_status,
            install_service_account,
            set_fcm_enabled_flag,
        )

        sa = {
            "project_id": "demo",
            "client_email": "a@b.iam.gserviceaccount.com",
            "private_key": "k",
        }
        with tempfile.TemporaryDirectory() as td:
            home = Path(td)
            env: dict = {}
            status = install_service_account(sa, home=home, env=env)
            self.assertTrue(status["configured"])
            self.assertTrue(status["enabled"])
            self.assertEqual(status["project_id"], "demo")
            dest = home / "secrets" / "fcm-sa.json"
            self.assertTrue(dest.is_file())
            env_text = (home / ".env").read_text(encoding="utf-8")
            self.assertIn("RELAY_FCM_SERVICE_ACCOUNT_JSON=", env_text)
            self.assertIn("RELAY_FCM_ENABLED=1", env_text)
            # default path works even with empty env override of path key
            env2 = {"RELAY_FCM_ENABLED": "1"}
            self.assertTrue(load_service_account_from_env(env2, home=home) is not None)
            off = set_fcm_enabled_flag(False, home=home, env=env)
            self.assertFalse(off["enabled"])
            cleared = clear_service_account(home=home, env=env)
            self.assertFalse(cleared["configured"])
            self.assertFalse(dest.is_file())

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
            preview="agent says hello",
        )
        self.assertTrue(result.ok)
        self.assertIn("demo-proj", captured["url"])
        self.assertTrue(captured["auth"].startswith("Bearer ya29"))
        payload = json.loads(captured["body"].decode("utf-8"))
        self.assertEqual(payload["message"]["token"], "device-token")
        self.assertEqual(payload["message"]["data"]["preview"], "agent says hello")


if __name__ == "__main__":
    unittest.main()
