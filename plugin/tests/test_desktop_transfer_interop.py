"""Run the real Python orchestration against a built TypeScript peer.

Run `npm --prefix desktop run build` before this optional local lane.
"""
from __future__ import annotations
import asyncio
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from typing import Any
from plugin.relay.channels.desktop import DesktopHandler
from plugin.tests.test_desktop_multidevice import _FakeSession

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('node') and (ROOT / 'desktop/dist/tools/transferSession.js').exists(), 'requires built desktop')
class TransferInteropTests(unittest.IsolatedAsyncioTestCase):
    async def test_push_pull_real_peer(self) -> None:
        handler = DesktopHandler()
        process = await asyncio.create_subprocess_exec('node', str(ROOT / 'desktop/tests/fixtures/transferPeer.mjs'),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, limit=512 * 1024)
        class Socket:
            closed = False
            async def send_str(self, data: str) -> None:
                process.stdin.write((data + '\n').encode())
                await process.stdin.drain()
        ws = Socket()
        await handler.handle(ws, {'type': 'desktop.status', 'payload': {
            'advertised_tools': ['desktop_transfer_receive', 'desktop_transfer_send']}},
            session=_FakeSession('peer', 'peer'))
        async def read_frames() -> None:
            while line := await process.stdout.readline():
                await handler.handle_envelope(ws, json.loads(line))
        reader = asyncio.create_task(read_frames())
        try:
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                for size in (0, 29, 4 * 1024 * 1024 + 13):
                    source = root / f'source-{size}'
                    remote = root / f'remote-{size}'
                    source.write_bytes(bytes(range(256)) * (size // 256) + bytes(range(size % 256)))
                    pushed = await asyncio.wait_for(handler.push_file(str(source), str(remote), device='peer'), 30)
                    self.assertEqual(remote.read_bytes(), source.read_bytes())
                    pulled = await asyncio.wait_for(handler.pull_file(str(remote), str(root / 'pull'), device='peer'), 30)
                    self.assertEqual(Path(pulled['path']).read_bytes(), source.read_bytes())
                    self.assertEqual(pushed['sha256'], pulled['sha256'])
                    self.assertFalse(handler.transfers)
        finally:
            process.stdin.close()
            await asyncio.wait_for(process.wait(), 5)
            await reader
            error = (await process.stderr.read()).decode()
            self.assertEqual(process.returncode, 0, error)
            await handler.close()
