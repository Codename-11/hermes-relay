"""One-use connector invitations cannot acquire ordinary server authority."""
from __future__ import annotations

import math
import json
import asyncio
import uuid
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.auth import PairingManager, SessionManager
from plugin.relay.config import RelayConfig
from plugin.relay.server import _broadcast_profiles_updated, create_app


class ConnectorInviteTests(unittest.TestCase):
    def test_desktop_invite_allows_an_hour_but_ordinary_pairing_stays_ten_minutes(self) -> None:
        manager = PairingManager()
        invite = manager.generate_desktop_invite()
        ordinary = manager.generate_code()
        invite_entry = manager._codes[invite]
        ordinary_entry = manager._codes[ordinary]
        self.assertEqual(invite_entry.expires_at - invite_entry.created_at, 3600)
        self.assertEqual(ordinary_entry.expires_at - ordinary_entry.created_at, 600)
        with patch('plugin.relay.auth.time.time', return_value=ordinary_entry.expires_at + 1):
            self.assertTrue(manager.validate_code(invite))
            self.assertFalse(manager.validate_code(ordinary))
        with patch('plugin.relay.auth.time.time', return_value=invite_entry.expires_at + 1):
            self.assertIsNone(manager.consume_code(invite))

    def test_one_use_high_entropy_and_expiry(self) -> None:
        manager = PairingManager()
        code = manager.generate_desktop_invite()
        self.assertRegex(code, r"^[0-9A-F]{64}$")
        metadata = manager.consume_code(code)
        assert metadata is not None
        self.assertTrue(metadata.desktop_only)
        self.assertEqual(metadata.ttl_seconds, 0)
        self.assertIsNone(manager.consume_code(code))
        code = manager.generate_desktop_invite()
        with patch('plugin.relay.auth.time.time', return_value=manager._codes[code].expires_at + 1):
            self.assertIsNone(manager.consume_code(code))

    def test_scope_survives_restart_and_revoke_is_terminal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sessions.json'
            manager = SessionManager(persistence_path=path)
            device = manager.create_session('Desktop', 'installation', ttl_seconds=0, desktop_only=True,
                                            issue_refresh_token=True)
            self.assertIsNone(device.refresh_token)
            restored = SessionManager(persistence_path=path)
            session = restored.get_session(device.token)
            assert session is not None
            self.assertTrue(session.desktop_only)
            self.assertTrue(math.isinf(session.expires_at))
            self.assertEqual(set(session.grants), {'desktop'})
            for channel in ('chat', 'terminal', 'bridge', 'tui', 'voice:config', 'voice:realtime'):
                self.assertTrue(session.channel_is_expired(channel))
            with self.assertRaises(ValueError):
                restored.issue_refresh_token_for_session(session)
            restored.revoke_session(session.token)
            self.assertIsNone(SessionManager(persistence_path=path).get_session(session.token))

    def test_consumed_invite_does_not_appear_in_logs(self) -> None:
        manager = PairingManager()
        with self.assertLogs('hermes_relay.auth', level='INFO') as logs:
            code = manager.generate_desktop_invite()
            manager.consume_code(code)
        self.assertNotIn(code, '\n'.join(logs.output))

    def test_invalid_or_already_paired_device_identity_cannot_replace_authority(self) -> None:
        manager = SessionManager()
        ordinary = manager.create_session('Phone', 'phone-id', issue_refresh_token=True)
        desktop = manager.create_session('Desktop', 'desktop-id', desktop_only=True)
        for device_id in ('', 'unknown', 'null', 'none', ' phone ', '\n', 'x' * 257,
                          'phone-id', 'desktop-id'):
            with self.subTest(device_id=device_id), self.assertRaises(ValueError):
                manager.create_session('Impersonator', device_id, desktop_only=True)
        self.assertIs(manager.get_session(ordinary.token), ordinary)
        self.assertIs(manager.get_session(desktop.token), desktop)
        self.assertTrue(manager.has_trusted_device('phone-id'))

    def test_malformed_persisted_scope_cannot_gain_server_grants(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sessions.json'
            manager = SessionManager(persistence_path=path)
            original = manager.create_session('Desktop', 'installation', desktop_only=True)
            payload = json.loads(path.read_text())
            for invalid in (None, 0, '', 'false', [], {}):
                payload['sessions'][0]['desktop_only'] = invalid
                payload['sessions'][0]['grants'] = {'chat': 'never', 'voice:config': 'never'}
                path.write_text(json.dumps(payload))
                restored = SessionManager(persistence_path=path).get_session(original.token)
                assert restored is not None
                self.assertTrue(restored.desktop_only)
                self.assertEqual(set(restored.grants), {'desktop'})

    def test_operator_replacement_retires_only_exact_identity_after_success(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sessions.json'
            manager = SessionManager(persistence_path=path)
            target = manager.create_session('Old desktop', 'old-id', issue_refresh_token=True)
            other = manager.create_session('Other', 'other-id', issue_refresh_token=True)
            with self.assertRaises(ValueError):
                manager.create_session('Wrong target', 'other-id', desktop_only=True,
                                       operator_replace_device_id='old-id')
            self.assertIs(manager.get_session(target.token), target)
            self.assertTrue(manager.has_trusted_device('old-id'))
            replacement = manager.create_session('New desktop', 'old-id', desktop_only=True,
                                                 ttl_seconds=0, operator_replace_device_id='old-id')
            restored = SessionManager(persistence_path=path)
            self.assertIsNone(restored.get_session(target.token))
            self.assertFalse(restored.has_trusted_device('old-id'))
            self.assertTrue(restored.get_session(replacement.token).desktop_only)
            self.assertIsNotNone(restored.get_session(other.token))
            self.assertTrue(restored.has_trusted_device('other-id'))


class ConnectorWireTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        return create_app(RelayConfig(profile_discovery_enabled=False))

    async def authorize(self):
        self.app['server'].config.profiles = [{'name': 'private', 'system_message': 'private prompt'}]
        code = self.app['server'].pairing.generate_desktop_invite()
        ws = await self.client.ws_connect('/ws')
        await ws.send_json({'channel': 'system', 'type': 'auth', 'payload': {
            'pairing_code': code, 'device_name': 'Desktop', 'device_id': 'desktop-wire-test',
            'client_surface': 'desktop',
        }})
        message = await ws.receive_json(timeout=2)
        self.assertEqual(message['type'], 'auth.ok')
        self.assertIsNone(message['payload']['expires_at'])
        self.assertNotIn('refresh_token', message['payload'])
        self.assertTrue(message['payload']['desktop_only'])
        self.assertEqual(message['payload']['profiles'], [])
        self.assertEqual(set(message['payload']['grants']), {'desktop'})
        return ws, message['payload']['session_token']

    async def test_mint_denies_browser_and_forwarded_requests(self) -> None:
        for headers in [{'Host': 'relay.example.test'}, {'Host': '127.0.0.1:8767', 'Origin': 'http://evil.test'},
                        {'Host': '127.0.0.1:8767', 'CF-Ray': 'edge'},
                        {'Host': '127.0.0.1:8767', 'X-Forwarded-For': '203.0.113.1'}]:
            reply = await self.client.post('/connector/invite', headers=headers)
            self.assertEqual(reply.status, 403)
        reply = await self.client.post('/connector/invite', headers={'Host': '127.0.0.1:8767'})
        self.assertEqual(reply.status, 200)
        self.assertEqual(reply.headers['Cache-Control'], 'no-store')
        invitation = await reply.json()
        self.assertEqual(invitation['expires_in'], 3600)
        self.assertRegex(invitation['invite'], r'^[0-9A-F]{64}$')

    async def test_actual_desktop_session_cannot_open_other_channels_or_manage_sessions(self) -> None:
        ws, token = await self.authorize()
        session = self.app['server'].sessions.get_session(token)
        assert session is not None
        self.assertTrue(session.desktop_only)
        self.assertEqual(session.client_surface, 'desktop')
        for channel, kind in [('terminal', 'terminal.attach'), ('chat', 'chat.send'), ('tui', 'tui.attach'),
                              ('bridge', 'bridge.status'), ('system', 'supervised.update')]:
            await ws.send_json({'channel': channel, 'type': kind, 'payload': {}})
            reply = await ws.receive_json(timeout=2)
            self.assertEqual(reply['type'], 'error')
        for route in ['/sessions', '/relay/info', '/media/missing', '/media/by-path?path=/tmp/file',
                      '/voice/config', '/voice/realtime/config', '/voice/output/config',
                      '/voice/realtime-agent/config']:
            reply = await self.client.get(route, headers={'Authorization': 'Bearer '+token})
            self.assertEqual(reply.status, 403)
        await ws.send_json({'channel': 'system', 'type': 'ping', 'payload': {'ts': 'watchdog-probe'}})
        reply = await ws.receive_json(timeout=2)
        self.assertEqual(reply['type'], 'pong')
        self.assertEqual(reply['payload']['ts'], 'watchdog-probe')
        await ws.close()
        ws = await self.client.ws_connect('/ws')
        # Changing untrusted display metadata cannot remove the stored scope.
        await ws.send_json({'channel': 'system', 'type': 'auth', 'payload': {
            'session_token': token, 'device_id': 'desktop-wire-test', 'desktop_only': False,
            'client_surface': 'phone'}})
        reply = await ws.receive_json(timeout=2)
        self.assertEqual(reply['type'], 'auth.ok')
        self.assertNotIn('refresh_token', reply['payload'])
        self.assertTrue(session.desktop_only)
        await ws.close()

    async def test_configured_invite_port_is_used(self) -> None:
        self.app['server'].config.port = 8877
        reply = await self.client.post('/connector/invite', headers={'Host': '127.0.0.1:8767'})
        self.assertEqual(reply.status, 403)
        reply = await self.client.post('/connector/invite', headers={'Host': '127.0.0.1:8877'})
        self.assertEqual(reply.status, 200)

    async def test_pairing_collision_returns_auth_failure_without_touching_victim(self) -> None:
        victim = self.app['server'].sessions.create_session('Phone', 'victim', issue_refresh_token=True)
        code = self.app['server'].pairing.generate_desktop_invite()
        ws = await self.client.ws_connect('/ws')
        await ws.send_json({'channel': 'system', 'type': 'auth', 'payload': {
            'pairing_code': code, 'device_id': 'victim', 'client_surface': 'desktop',
            'operator_replace_device_id': 'victim',
        }})
        reply = await ws.receive_json(timeout=2)
        self.assertEqual(reply['type'], 'auth.fail')
        self.assertIs(self.app['server'].sessions.get_session(victim.token), victim)
        self.assertTrue(self.app['server'].sessions.has_trusted_device('victim'))
        self.assertIsNone(self.app['server'].pairing.consume_code(code))
        await ws.close()

    async def test_revocation_invalidates_live_socket_and_reconnect(self) -> None:
        ws, token = await self.authorize()
        self.app['server'].sessions.revoke_session(token)
        await ws.send_json({'channel': 'desktop', 'type': 'status', 'payload': {
            'advertised_tools': ['computer_screenshot'],
        }})
        reply = await ws.receive_json(timeout=2)
        self.assertEqual(reply['type'], 'error')
        self.assertFalse(self.app['server'].desktop.is_client_connected())
        await ws.close()
        ws = await self.client.ws_connect('/ws')
        await ws.send_json({'channel': 'system', 'type': 'auth', 'payload': {
            'session_token': token, 'device_id': 'desktop-wire-test',
        }})
        self.assertEqual((await ws.receive_json(timeout=2))['type'], 'auth.fail')
        await ws.close()

    async def test_operator_invite_is_uuid_bound_and_only_success_retires_old_socket(self) -> None:
        server = self.app['server']
        device_id = str(uuid.uuid4())
        original = server.sessions.create_session('Old desktop', device_id, issue_refresh_token=True)
        old_ws = await self.client.ws_connect('/ws')
        await old_ws.send_json({'channel': 'system', 'type': 'auth', 'payload': {
            'session_token': original.token, 'device_id': device_id,
        }})
        self.assertEqual((await old_ws.receive_json(timeout=2))['type'], 'auth.ok')

        async def operator_invite() -> str:
            response = await self.client.post('/connector/invite', json={'device_id': device_id},
                                              headers={'Host': '127.0.0.1:8767'})
            self.assertEqual(response.status, 200)
            return (await response.json())['invite']

        invite = await operator_invite()
        self.assertIsNotNone(server.sessions.get_session(original.token))
        wrong_ws = await self.client.ws_connect('/ws')
        await wrong_ws.send_json({'channel': 'system', 'type': 'auth', 'payload': {
            'pairing_code': invite, 'device_id': str(uuid.uuid4()),
        }})
        self.assertEqual((await wrong_ws.receive_json(timeout=2))['type'], 'auth.fail')
        self.assertIsNotNone(server.sessions.get_session(original.token))
        self.assertTrue(server.sessions.has_trusted_device(device_id))
        await wrong_ws.close()

        invite = await operator_invite()
        new_ws = await self.client.ws_connect('/ws')
        old_closed = asyncio.create_task(old_ws.receive(timeout=2))
        await new_ws.send_json({'channel': 'system', 'type': 'auth', 'payload': {
            'pairing_code': invite, 'device_id': device_id, 'client_surface': 'desktop',
        }})
        reply = await new_ws.receive_json(timeout=2)
        self.assertEqual(reply['type'], 'auth.ok')
        self.assertTrue(reply['payload']['desktop_only'])
        await old_closed
        self.assertTrue(old_ws.closed)
        self.assertIsNone(server.sessions.get_session(original.token))
        self.assertFalse(server.sessions.has_trusted_device(device_id))
        self.assertIsNone(server.sessions.refresh_session(original.refresh_token, device_name='Old desktop',
                                                          device_id=device_id))
        await new_ws.close()

    async def test_operator_invite_requires_an_existing_canonical_uuid(self) -> None:
        for body, status in [({'device_id': 'unknown'}, 400),
                             ({'device_id': str(uuid.uuid4())}, 404),
                             ({'operator_replace_device_id': str(uuid.uuid4())}, 400),
                             ([], 400)]:
            response = await self.client.post('/connector/invite', json=body,
                                              headers={'Host': '127.0.0.1:8767'})
            self.assertEqual(response.status, status)

    async def test_profile_broadcast_excludes_desktop_installations(self) -> None:
        server = self.app['server']
        ordinary = server.sessions.create_session('Phone', 'phone')
        desktop = server.sessions.create_session('Desktop', 'desktop', desktop_only=True)
        phone_ws, desktop_ws = MagicMock(), MagicMock()
        for ws in (phone_ws, desktop_ws):
            ws.closed = False
            ws.send_str = AsyncMock()
        server._clients.update({phone_ws: ordinary.token, desktop_ws: desktop.token})
        try:
            await _broadcast_profiles_updated(server, [{'name': 'private', 'system_message': 'private prompt'}])
            phone_ws.send_str.assert_awaited_once()
            desktop_ws.send_str.assert_not_awaited()
        finally:
            server._clients.pop(phone_ws)
            server._clients.pop(desktop_ws)
