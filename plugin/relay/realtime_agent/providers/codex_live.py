"""Relay-native GPT Live via an isolated Codex subscription WebRTC worker.

The worker never holds Hermes relay credentials and has no device tools.
Every delegation flows through the existing RealtimeAgentHandler broker.
"""
from __future__ import annotations

import asyncio
import base64
import contextlib
import json
import os
from pathlib import Path
from typing import AsyncIterator

from ....voice_lab.providers.base import ProviderRunError, ProviderUnavailable
from ..models import (
    ProviderEvent, ProviderEventKind, RealtimeAgentCapabilities,
    RealtimeAgentSessionConfig, ToolCallEvent,
)
from .openai import _resample_pcm16_mono

_WORKER_PYTHON = Path(os.getenv("CODEX_LIVE_WORKER_PYTHON") or
                      (Path.home() / ".hermes/tools/relay-codex-live11-venv/bin/python")).expanduser()
# The worker and its own Codex subprocess need only ordinary process settings,
# not the parent Hermes server's API keys, MCP credentials, or private tokens.
_WORKER_ENV_ALLOWLIST = frozenset({
    "HOME", "PATH", "USER", "LOGNAME", "LANG", "LC_ALL", "TERM", "TMPDIR",
    "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME", "XDG_RUNTIME_DIR",
    "DBUS_SESSION_BUS_ADDRESS", "CODEX_HOME",
    "CODEX_LIVE_CODEX_BIN", "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY",
    "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE",
})
_WORKER_FILE=Path(__file__).with_name('codex_live_worker.py')

class CodexLiveProvider:
    provider_id='codex_live'
    capabilities=RealtimeAgentCapabilities(provider_id=provider_id,supports_server_vad=True)

    async def connect(self,config:RealtimeAgentSessionConfig)->'CodexLiveConnection':
        if config.model != 'gpt-live-1.1' or config.sample_rate!=24000:
            raise ProviderUnavailable('Codex subscription Live supports only gpt-live-1.1 at 24000 Hz')
        if not _WORKER_PYTHON.is_file() or not _WORKER_FILE.is_file():
            raise ProviderUnavailable(
                "Codex Live WebRTC worker unavailable. Install its aiortc environment "
                "and set CODEX_LIVE_WORKER_PYTHON if installed in another location."
            )
        env = {key: value for key, value in os.environ.items()
               if key in _WORKER_ENV_ALLOWLIST}
        env.update(CODEX_LIVE_MODEL="gpt-live-1.1", CODEX_LIVE_VOICE=config.voice or "sol",
                   PYTHONUNBUFFERED="1")
        # Codex uses the logged-in subscription on disk; do not propagate
        # Hermes/API provider credentials to this isolated voice process.
        proc=await asyncio.create_subprocess_exec(
            str(_WORKER_PYTHON),str(_WORKER_FILE),
            stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,env=env,
        )
        conn=CodexLiveConnection(proc)
        try:await conn.wait_ready()
        except BaseException:
            await conn.close()
            raise
        return conn

class CodexLiveConnection:
    def __init__(self,proc):
        self.proc=proc;self._queue=asyncio.Queue();self._write_lock=asyncio.Lock();self._closed=False
        self._reader_task=asyncio.create_task(self._read())

    async def _read(self):
        try:
            while True:
                line=await self.proc.stdout.readline()
                if not line:break
                try:msg=json.loads(line)
                except (ValueError,TypeError):continue
                await self._queue.put(msg)
        finally:await self._queue.put(None)

    async def wait_ready(self):
        try:message=await asyncio.wait_for(self._queue.get(),timeout=90)
        except asyncio.TimeoutError as exc:raise ProviderUnavailable('Codex GPT Live connection timed out') from exc
        if not isinstance(message,dict) or message.get('kind')!='ready':
            detail=(message or {}).get('message','worker exited') if isinstance(message,dict) else 'worker exited'
            raise ProviderUnavailable(f'Codex GPT Live startup failed: {detail}')

    async def _send(self,op,**args):
        if self._closed or self.proc.returncode is not None:raise ProviderRunError('Codex voice connection is closed')
        payload=json.dumps({'op':op,**args},separators=(',',':'))+'\n'
        async with self._write_lock:
            self.proc.stdin.write(payload.encode())
            await self.proc.stdin.drain()

    async def send_audio(self,pcm:bytes,sample_rate:int)->None:
        pcm=_resample_pcm16_mono(pcm,sample_rate,24000)
        await self._send('send_audio',pcm=base64.b64encode(pcm).decode())
    async def commit_audio(self)->None:await self._send('commit_audio')
    async def send_text(self,text:str)->None:await self._send('send_text',text=text)
    async def clear_audio(self)->None:await self._send('clear_audio')
    async def cancel_response(self)->None:await self._send('cancel_response')
    async def send_tool_result(self,call_id:str,output:dict)->None:
        await self._send('send_tool_result',call_id=call_id,output=output)
    async def append_context_item(self,*,role:str,text:str)->None:
        await self._send('append_context_item',role=role,text=text)
    async def request_response(self,*,instructions:str|None=None,exact_text:str|None=None)->None:
        # Codex subscription Live V3 uses server-managed turn-taking over WebRTC.
        # Both normal mic turns and delegated Hermes results auto-generate the
        # next speech turn. The Realtime API response.create command is not
        # supported by this V3 data channel: sending it raises an error that
        # causes Android to terminate an otherwise healthy voice session.
        # The startup prompt and delegation.context.append provide instructions
        # and result text. Keep this method as an intentional no-op for V3.
        return

    async def events(self)->AsyncIterator[ProviderEvent]:
        kinds={
            'input_transcript_delta':ProviderEventKind.INPUT_TRANSCRIPT_DELTA,
            'input_transcript_final':ProviderEventKind.INPUT_TRANSCRIPT_FINAL,
            'output_text_delta':ProviderEventKind.OUTPUT_TEXT_DELTA,
            'audio_delta':ProviderEventKind.AUDIO_DELTA,
            'audio_done':ProviderEventKind.AUDIO_DONE,
            'response_started':ProviderEventKind.RESPONSE_STARTED,
            'response_done':ProviderEventKind.RESPONSE_DONE,
            'function_call_completed':ProviderEventKind.FUNCTION_CALL_COMPLETED,
            'error':ProviderEventKind.ERROR,
        }
        while True:
            msg=await self._queue.get()
            if msg is None:break
            kind=kinds.get(msg.get('kind'))
            if not kind:continue
            payload=dict(msg);payload.pop('kind',None)
            if kind==ProviderEventKind.AUDIO_DELTA:
                encoded=payload.get('audio_base64') or ''
                payload['audio']=base64.b64decode(encoded)
                payload['byte_count']=len(payload['audio'])
            elif kind==ProviderEventKind.FUNCTION_CALL_COMPLETED:
                args=payload.get('arguments') or {}
                payload['call']=ToolCallEvent(
                    call_id=str(payload.get('call_id') or ''),
                    name=str(payload.get('name') or 'hermes_run_task'),arguments=args)
            yield ProviderEvent(kind,payload=payload,response_id=str(msg.get('response_id') or ''))

    async def close(self)->None:
        if self._closed:return
        if self.proc.returncode is None:
            with contextlib.suppress(Exception):await self._send('close')
        self._closed=True
        try:await asyncio.wait_for(self.proc.wait(),8)
        except asyncio.TimeoutError:
            self.proc.kill();await self.proc.wait()
        self._reader_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):await self._reader_task
