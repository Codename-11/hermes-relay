"""Client-reported supervised-mode metadata is bounded and informational."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.auth import (
    SUPERVISED_CAPABILITY_MAX_COUNT,
    SUPERVISED_PROFILE_LABEL_MAX_LENGTH,
    SessionManager,
    SupervisedMode,
    parse_supervised_mode,
)
from plugin.relay.config import RelayConfig
from plugin.relay.server import RelayServer, _build_auth_ok_payload, create_app


class SupervisedModeParsingTests(unittest.TestCase):
    def test_valid_report_is_normalized_and_deduplicated(self) -> None:
        parsed = parse_supervised_mode(
            {
                "active": True,
                "profile_label": "  Learning  ",
                "capabilities": ["text_chat", "voice", "voice"],
                "enforcement_owner": "server",  # client cannot override it
            }
        )

        self.assertEqual(
            parsed,
            SupervisedMode(True, "Learning", ("text_chat", "voice")),
        )
        self.assertEqual(parsed.to_public_dict()["enforcement_owner"], "android_client")

    def test_missing_inactive_and_malformed_reports_are_ordinary(self) -> None:
        invalid = (
            None,
            [],
            {"active": False, "profile_label": "Learning"},
            {"active": "true", "profile_label": "Learning"},
            {"active": True, "profile_label": 7},
            {"active": True, "profile_label": "Learning", "capabilities": {}},
            {"active": True, "profile_label": "Learning\n", "capabilities": []},
            {
                "active": True,
                "profile_label": "Learning",
                "capabilities": ["model:gpt-private"],
            },
        )
        for value in invalid:
            with self.subTest(value=value):
                self.assertEqual(parse_supervised_mode(value), SupervisedMode())

    def test_oversized_report_is_ordinary(self) -> None:
        self.assertEqual(
            parse_supervised_mode(
                {
                    "active": True,
                    "profile_label": "x" * (SUPERVISED_PROFILE_LABEL_MAX_LENGTH + 1),
                    "capabilities": [],
                }
            ),
            SupervisedMode(),
        )
        self.assertEqual(
            parse_supervised_mode(
                {
                    "active": True,
                    "profile_label": "Learning",
                    "capabilities": ["voice"] * (SUPERVISED_CAPABILITY_MAX_COUNT + 1),
                }
            ),
            SupervisedMode(),
        )


class SupervisedModePersistenceTests(unittest.TestCase):
    def test_active_report_and_trusted_device_survive_restart_and_refresh(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sessions.json"
            mode = SupervisedMode(
                True,
                "Learning",
                ("text_chat", "attachments", "voice"),
            )
            manager = SessionManager(persistence_path=path)
            session = manager.create_session(
                "Managed phone",
                "managed-phone-id",
                supervised_mode=mode,
                issue_refresh_token=True,
            )
            refresh_token = session.refresh_token
            assert refresh_token is not None

            reloaded = SessionManager(persistence_path=path)
            restored = reloaded.get_session(session.token)
            self.assertIsNotNone(restored)
            assert restored is not None
            self.assertEqual(restored.supervised_mode, mode)

            reloaded._sessions.clear()
            replacement = reloaded.refresh_session(
                refresh_token,
                device_name="Managed phone",
                device_id="managed-phone-id",
            )
            self.assertIsNotNone(replacement)
            assert replacement is not None
            self.assertEqual(replacement.supervised_mode, mode)

    def test_legacy_and_invalid_disk_rows_load_as_ordinary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sessions.json"
            manager = SessionManager(persistence_path=path)
            session = manager.create_session("Legacy phone", "legacy-id")
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["sessions"][0]["supervised_mode"] = {
                "active": True,
                "profile_label": "Learning",
                "capabilities": ["unknown_future_value"],
            }
            path.write_text(json.dumps(payload), encoding="utf-8")

            reloaded = SessionManager(persistence_path=path)
            restored = reloaded.get_session(session.token)
            self.assertIsNotNone(restored)
            assert restored is not None
            self.assertEqual(restored.supervised_mode, SupervisedMode())

            payload["sessions"][0].pop("supervised_mode")
            path.write_text(json.dumps(payload), encoding="utf-8")
            legacy = SessionManager(persistence_path=path).get_session(session.token)
            self.assertIsNotNone(legacy)
            assert legacy is not None
            self.assertEqual(legacy.supervised_mode, SupervisedMode())

    def test_auth_ok_only_emits_active_client_report(self) -> None:
        server = RelayServer(RelayConfig())
        ordinary = server.sessions.create_session("Phone", "ordinary-id")
        self.assertNotIn("supervised_mode", _build_auth_ok_payload(ordinary, server))

        managed = server.sessions.create_session(
            "Managed phone",
            "managed-id",
            supervised_mode=SupervisedMode(True, "Learning", ("voice",)),
        )
        report = _build_auth_ok_payload(managed, server)["supervised_mode"]
        self.assertEqual(report["profile_label"], "Learning")
        self.assertEqual(report["capabilities"], ["voice"])
        self.assertEqual(report["enforcement_owner"], "android_client")


class SupervisedModeSessionRoutesTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        return create_app(RelayConfig())

    async def test_list_exposes_active_report_and_omits_ordinary_report(self) -> None:
        ordinary = self.app["server"].sessions.create_session("Phone", "ordinary-id")
        self.app["server"].sessions.create_session(
            "Managed phone",
            "managed-id",
            supervised_mode=SupervisedMode(
                True, "Learning", ("text_chat", "attachments")
            ),
        )

        response = await self.client.get(
            "/sessions", headers={"Authorization": f"Bearer {ordinary.token}"}
        )
        self.assertEqual(response.status, 200)
        rows = {row["device_name"]: row for row in (await response.json())["sessions"]}
        self.assertNotIn("supervised_mode", rows["Phone"])
        self.assertEqual(
            rows["Managed phone"]["supervised_mode"],
            {
                "active": True,
                "profile_label": "Learning",
                "capabilities": ["text_chat", "attachments"],
                "enforcement_owner": "android_client",
            },
        )

    async def test_pairing_auth_records_and_returns_client_report(self) -> None:
        response = await self.client.post(
            "/pairing/register", json={"code": "MODE01"}
        )
        self.assertEqual(response.status, 200, await response.text())

        socket = await self.client.ws_connect("/ws")
        await socket.send_json(
            {
                "channel": "system",
                "type": "auth",
                "payload": {
                    "pairing_code": "MODE01",
                    "device_name": "Managed phone",
                    "device_id": "managed-auth-id",
                    "client_surface": "android",
                    "supervised_mode": {
                        "active": True,
                        "profile_label": "Learning",
                        "capabilities": ["text_chat", "voice"],
                    },
                },
            }
        )
        envelope = await socket.receive_json()
        await socket.close()

        self.assertEqual(envelope["type"], "auth.ok")
        report = envelope["payload"]["supervised_mode"]
        self.assertEqual(report["profile_label"], "Learning")
        self.assertEqual(report["capabilities"], ["text_chat", "voice"])
        self.assertEqual(report["enforcement_owner"], "android_client")
        stored = self.app["server"].sessions.get_session(
            envelope["payload"]["session_token"]
        )
        self.assertIsNotNone(stored)
        assert stored is not None
        self.assertTrue(stored.supervised_mode.active)

        reconnect = await self.client.ws_connect("/ws")
        await reconnect.send_json(
            {
                "channel": "system",
                "type": "auth",
                "payload": {
                    "session_token": stored.token,
                    "device_id": "managed-auth-id",
                },
            }
        )
        ordinary_envelope = await reconnect.receive_json()
        await reconnect.close()
        self.assertEqual(ordinary_envelope["type"], "auth.ok")
        self.assertNotIn("supervised_mode", ordinary_envelope["payload"])
        refreshed = self.app["server"].sessions.get_session(stored.token)
        self.assertIsNotNone(refreshed)
        assert refreshed is not None
        self.assertFalse(refreshed.supervised_mode.active)

    async def test_authenticated_live_update_is_owned_acked_and_persisted(self) -> None:
        manager = self.app["server"].sessions
        original = SupervisedMode(True, "Learning", ("text_chat", "voice"))
        session = manager.create_session(
            "Managed phone",
            "managed-live-id",
            supervised_mode=original,
            issue_refresh_token=True,
        )
        other = manager.create_session(
            "Other phone",
            "other-id",
            supervised_mode=SupervisedMode(True, "Other", ("text_chat",)),
        )

        socket = await self.client.ws_connect("/ws")
        await socket.send_json(
            {
                "channel": "system",
                "type": "auth",
                "payload": {
                    "session_token": session.token,
                    "device_id": session.device_id,
                    "supervised_mode": original.to_public_dict(),
                },
            }
        )
        self.assertEqual((await socket.receive_json())["type"], "auth.ok")

        await socket.send_json(
            {
                "channel": "system",
                "type": "supervised.update",
                "id": "update-active",
                "payload": {
                    # Must be ignored: ownership comes from the authenticated
                    # socket, not any selector supplied in the update body.
                    "session_token": other.token,
                    "supervised_mode": {
                        "active": True,
                        "profile_label": "School",
                        "capabilities": ["text_chat", "attachments"],
                    },
                },
            }
        )
        ack = await socket.receive_json()
        self.assertEqual(ack["type"], "supervised.updated")
        self.assertEqual(ack["id"], "update-active")
        self.assertEqual(
            ack["payload"]["supervised_mode"],
            {
                "active": True,
                "profile_label": "School",
                "capabilities": ["text_chat", "attachments"],
                "enforcement_owner": "android_client",
            },
        )
        self.assertEqual(
            manager.get_session(session.token).supervised_mode,
            SupervisedMode(True, "School", ("text_chat", "attachments")),
        )
        self.assertEqual(manager.get_session(other.token).supervised_mode.profile_label, "Other")
        self.assertTrue(
            any(
                device.device_id == session.device_id
                and device.supervised_mode.profile_label == "School"
                for device in manager._trusted_devices.values()
            )
        )

        await socket.send_json(
            {
                "channel": "system",
                "type": "supervised.update",
                "id": "update-inactive",
                "payload": {"supervised_mode": {"active": False}},
            }
        )
        cleared = await socket.receive_json()
        await socket.close()
        self.assertEqual(cleared["type"], "supervised.updated")
        self.assertEqual(cleared["id"], "update-inactive")
        self.assertEqual(
            cleared["payload"]["supervised_mode"],
            {"active": False, "enforcement_owner": "android_client"},
        )
        self.assertFalse(manager.get_session(session.token).supervised_mode.active)
        self.assertTrue(
            all(
                not device.supervised_mode.active
                for device in manager._trusted_devices.values()
                if device.device_id == session.device_id
            )
        )

    async def test_revoke_closes_connected_relay_socket(self) -> None:
        caller = self.app["server"].sessions.create_session("Caller", "caller-id")
        target = self.app["server"].sessions.create_session(
            "Managed phone",
            "managed-id",
            supervised_mode=SupervisedMode(True, "Learning", ("text_chat",)),
        )
        socket = AsyncMock()
        socket.closed = False
        self.app["server"]._clients[socket] = target.token

        response = await self.client.delete(
            f"/sessions/{target.token[:8]}",
            headers={"Authorization": f"Bearer {caller.token}"},
        )

        self.assertEqual(response.status, 200)
        self.assertIsNone(self.app["server"].sessions.get_session(target.token))
        socket.close.assert_awaited_once()
        self.assertEqual(
            socket.close.await_args.kwargs["message"], b"Relay session revoked"
        )
