"""File-transfer integrity, target binding and cleanup regressions."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, Mock, patch

from plugin.relay.channels.desktop import DesktopHandler, DesktopError, TRANSFER_CHUNK_SIZE
from plugin.tests.test_desktop_multidevice import _register_two
from plugin.tools import desktop_tool
from plugin.relay.server import handle_desktop_transfer_push, handle_desktop_transfer_pull
from aiohttp import web


class DesktopTransferTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.handler, self.ws, self.other = await _register_two()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    async def start_pull(self, size: int = 1) -> tuple[asyncio.Task[Any], str]:
        async def ready(method: str, args: dict[str, Any], **kw: Any) -> dict[str, Any]:
            return {"ok": True, "result": {"status": "ready", "protocol": 1,
                "transfer_id": args["transfer_id"], "size": size,
                "total_chunks": max(1, (size + TRANSFER_CHUNK_SIZE - 1) // TRANSFER_CHUNK_SIZE)}}
        self.handler.handle_command = ready
        task = asyncio.create_task(self.handler.pull_file(r'C:\source\data.bin', str(self.root), device='desktop-1'))
        for _ in range(100):
            await asyncio.sleep(0)
            if self.ws.sent:
                return task, self.ws.sent[-1]['payload']['transfer_id']
        self.fail('transfer did not start')

    async def frame(self, kind: str, id: str, ws: Any = None, **payload: Any) -> None:
        await self.handler.handle_envelope(self.ws if ws is None else ws, {
            'type': 'desktop.transfer_' + kind,
            'payload': {'transfer_id': id, **payload}})

    async def test_pull_empty_and_multichunk(self) -> None:
        for size in (0, TRANSFER_CHUNK_SIZE + 11):
            with self.subTest(size=size):
                self.ws.sent.clear()
                data = b'x' * size
                task, id = await self.start_pull(size)
                total = max(1, (size + TRANSFER_CHUNK_SIZE - 1) // TRANSFER_CHUNK_SIZE)
                for i in range(total):
                    chunk = data[i * TRANSFER_CHUNK_SIZE:(i + 1) * TRANSFER_CHUNK_SIZE]
                    await self.frame('chunk', id, index=i, total=total, data=base64.b64encode(chunk).decode())
                await self.frame('done', id, size=size, sha256=hashlib.sha256(data).hexdigest())
                result = await task
                self.assertEqual(Path(result['path']).read_bytes(), data)
                Path(result['path']).unlink()
                self.assertFalse(self.handler.transfers)
                self.assertEqual(list(self.root.iterdir()), [])

    async def test_wrong_device_cannot_inject_or_abort(self) -> None:
        task, id = await self.start_pull()
        for kind in ('chunk', 'done', 'error', 'abort', 'progress'):
            await self.frame(kind, id, ws=self.other, index=0, total=1, data='YQ==', size=1,
                             sha256=hashlib.sha256(b'a').hexdigest(), received=1)
        record = self.handler.transfers[id]
        self.assertEqual(record.received_bytes, 0)
        self.assertFalse(record.done_future.done())
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertFalse(self.handler.transfers)
        self.assertEqual(list(self.root.iterdir()), [])

    async def test_bad_chunks_fail_and_clean_up(self) -> None:
        for payload in ({'index': 1, 'total': 1, 'data': 'YQ=='},
                        {'index': 0, 'total': 1, 'data': '!!'},
                        {'index': 0, 'total': 1, 'data': 'YWI='}):
            self.ws.sent.clear()
            task, id = await self.start_pull()
            await self.frame('chunk', id, **payload)
            with self.assertRaises(DesktopError):
                await task
            self.assertEqual(list(self.root.iterdir()), [])

    async def test_incomplete_done_cannot_publish(self) -> None:
        task, id = await self.start_pull()
        await self.frame('done', id, size=1, sha256=hashlib.sha256(b'').hexdigest())
        with self.assertRaises(DesktopError):
            await task
        await self.frame('done', id, size=1, sha256='invalid')
        self.assertEqual(list(self.root.iterdir()), [])

    async def test_destination_race_preserves_file(self) -> None:
        task, id = await self.start_pull()
        dest = self.root / 'data.bin'
        dest.write_bytes(b'keep')
        await self.frame('chunk', id, index=0, total=1, data='YQ==')
        await self.frame('done', id, size=1, sha256=hashlib.sha256(b'a').hexdigest())
        with self.assertRaises(DesktopError):
            await task
        self.assertEqual(dest.read_bytes(), b'keep')
        self.assertEqual(list(self.root.iterdir()), [dest])

    async def test_disconnect_and_idle_timeout(self) -> None:
        task, id = await self.start_pull()
        await self.handler._fail_transfers_for_ws(self.ws, 'test')
        with self.assertRaises(DesktopError):
            await task
        self.ws.sent.clear()
        with patch('plugin.relay.channels.desktop.TRANSFER_IDLE_TIMEOUT', 0.01):
            task, id = await self.start_pull()
            with self.assertRaises(DesktopError):
                await task
        self.assertEqual(list(self.root.iterdir()), [])

    async def test_push_waits_for_ack_and_checks_done(self) -> None:
        data = b'a' * (TRANSFER_CHUNK_SIZE + 2)
        source = self.root / 'source'
        source.write_bytes(data)
        async def ready(method: str, args: dict[str, Any], **kw: Any) -> dict[str, Any]:
            return {'ok': True, 'result': {'status': 'ready', 'protocol': 1, 'transfer_id': args['transfer_id']}}
        self.handler.handle_command = ready
        task = asyncio.create_task(self.handler.push_file(str(source), 'dest', device='desktop-1'))
        for _ in range(100):
            await asyncio.sleep(0)
            if self.ws.sent: break
        id = self.ws.sent[0]['payload']['transfer_id']
        self.assertEqual(len(self.ws.sent), 1)
        await self.frame('progress', id, received=1)
        for _ in range(100):
            await asyncio.sleep(0.001)
            if len(self.ws.sent) == 2: break
        self.assertEqual(len(self.ws.sent), 2)
        await self.frame('done', id, size=len(data), sha256=hashlib.sha256(data).hexdigest())
        self.assertTrue((await task)['ok'])

    def test_availability_maps_public_tools_to_protocol_handlers(self) -> None:
        snapshot = desktop_tool._DesktopAvailability(True, True, True, (frozenset({'desktop_transfer_receive'}),))
        with patch.object(desktop_tool, '_availability', return_value=snapshot):
            self.assertTrue(desktop_tool._check_tool('desktop_push_file'))
            self.assertFalse(desktop_tool._check_tool('desktop_pull_file'))
        self.assertEqual(desktop_tool._SCHEMAS['desktop_push_file']['x-hermes-capability'], 'files.write')


class TransferRouteTests(unittest.IsolatedAsyncioTestCase):
    def request(self, body: Any, remote: str = '127.0.0.1') -> Any:
        desktop = Mock(push_file=AsyncMock(), pull_file=AsyncMock())
        return Mock(remote=remote, headers={}, json=AsyncMock(return_value=body),
                    app={'server': Mock(desktop=desktop)})

    async def test_remote_requests_are_rejected(self) -> None:
        for route in (handle_desktop_transfer_push, handle_desktop_transfer_pull):
            with self.assertRaises(web.HTTPForbidden):
                await route(self.request({}, '198.51.100.10'))

    async def test_invalid_input_does_not_dispatch(self) -> None:
        for body in ([], {'device': 123}, {'device': '   '}):
            request = self.request(body)
            with self.assertRaises(web.HTTPBadRequest):
                await handle_desktop_transfer_push(request)
            request.app['server'].desktop.push_file.assert_not_called()
        request = self.request({'local_path': 'a', 'remote_path': 'b', 'overwrite': 'false'})
        response = await handle_desktop_transfer_push(request)
        self.assertEqual(response.status, 400)
        request.app['server'].desktop.push_file.assert_not_called()
