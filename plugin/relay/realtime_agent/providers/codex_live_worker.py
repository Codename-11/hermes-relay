"""Isolated, subscription-backed Codex GPT Live WebRTC voice worker.

Stdio JSON protocol. Never handles Hermes device tools or API tokens; Relay
owns authorization and tool decisions. Runs in a dedicated aiortc venv.
"""
from __future__ import annotations
import asyncio, base64, json, os, shutil, sys, time, traceback
from pathlib import Path
from fractions import Fraction
from aiortc import RTCPeerConnection, RTCSessionDescription, AudioStreamTrack
from av import AudioFrame, AudioResampler

FRAME=960
RATE=24000

def emit(kind, **payload):
    sys.stdout.write(json.dumps({'kind':kind, **payload}, separators=(',',':'))+'\n')
    sys.stdout.flush()

class PCMTrack(AudioStreamTrack):
    def __init__(self):
        super().__init__(); self.buffer=bytearray(); self.sent=0; self.next=None
    async def recv(self):
        now=asyncio.get_running_loop().time()
        if self.next is None:self.next=now
        await asyncio.sleep(max(0,self.next-now))
        self.next+=.02
        frame=AudioFrame(format='s16',layout='mono',samples=480)
        data=bytes(self.buffer[:FRAME]);del self.buffer[:FRAME]
        frame.planes[0].update(data.ljust(FRAME,b'\0'))
        frame.sample_rate=RATE;frame.pts=self.sent;frame.time_base=Fraction(1,RATE)
        self.sent+=480
        return frame
    def append(self,raw):
        if len(self.buffer) > 24000*2*4: self.buffer.clear() # drop stale buffered mic
        self.buffer.extend(raw)

class CodexRPC:
    def __init__(self):self.proc=None;self.pending={};self.notifs=asyncio.Queue();self.seq=0;self.reader=None
    async def open(self):
        binary=(os.getenv("CODEX_LIVE_CODEX_BIN") or shutil.which("codex") or
                str(Path.home()/".local/bin/codex"))
        if not Path(binary).is_file():
            raise RuntimeError("Codex CLI not found; install it and sign in first")
        env=os.environ.copy();env.pop('OPENAI_API_KEY',None);env.pop('CODEX_API_KEY',None)
        args=[binary,'app-server','--listen','stdio://','--enable','realtime_conversation',
              '-c','model_provider=openai','-c','features.apps=false','-c','features.shell_tool=false',
              '-c','features.multi_agent=false','-c','features.plugins=false','-c','features.browser_use=false',
              '-c','features.computer_use=false','-c','web_search="disabled"',
              '-c','tools.view_image=false','-c','suppress_unstable_features_warning=true']
        self.proc=await asyncio.create_subprocess_exec(*args,stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL,env=env)
        self.reader=asyncio.create_task(self._read())
        await self.request('initialize',{'clientInfo':{'name':'hermes-relay-codex-live','version':'0.1.0'},'capabilities':{'experimentalApi':True}})
    async def _read(self):
        try:
            while True:
                line=await self.proc.stdout.readline()
                if not line:break
                try:item=json.loads(line)
                except Exception:continue
                if 'id' in item and item['id'] in self.pending:
                    future=self.pending.pop(item['id'])
                    if 'error' in item:future.set_exception(RuntimeError(str(item['error'])[:500]))
                    else:future.set_result(item.get('result'))
                elif item.get('method'):
                    await self.notifs.put(item)
        except asyncio.CancelledError:pass
        except Exception as e:emit('error',message='Codex reader: '+str(e)[:300])
    async def request(self,method,params,timeout=25):
        self.seq+=1;idx=self.seq;f=asyncio.get_running_loop().create_future();self.pending[idx]=f
        self.proc.stdin.write((json.dumps({'jsonrpc':'2.0','id':idx,'method':method,'params':params})+'\n').encode())
        await self.proc.stdin.drain()
        try:return await asyncio.wait_for(f,timeout)
        finally:self.pending.pop(idx,None)
    async def close(self):
        if self.proc and self.proc.returncode is None:
            self.proc.terminate()
            try:await asyncio.wait_for(self.proc.wait(),3)
            except asyncio.TimeoutError:self.proc.kill();await self.proc.wait()
        if self.reader:self.reader.cancel()

class LiveWorker:
    def __init__(self,model='gpt-live-1.1',voice='sol'):
        self.model=model;self.voice=voice;self.codex=CodexRPC();self.pc=RTCPeerConnection();self.track=PCMTrack();self.pc.addTrack(self.track)
        self.dc=self.pc.createDataChannel('oai-events');self.thread=None;self.task=None;self.closed=False;self.delegations=set();self.track_task=None;self.last_user_text="";self.assistant_turn_id=""
        @self.dc.on('message')
        def on_message(raw):
            try:self.handle_event(json.loads(raw))
            except Exception as exc:emit('error',message='data channel: '+str(exc)[:220])
        @self.pc.on('track')
        def got_track(track):
            if track.kind=='audio':self.track_task=asyncio.create_task(self.receive_audio(track))
        @self.pc.on('connectionstatechange')
        async def changed():
            if self.pc.connectionState in ('failed','closed') and not self.closed:
                emit('error',message='WebRTC '+self.pc.connectionState)
    async def connect(self):
        await self.codex.open()
        th=await self.codex.request('thread/start',{
            'cwd':os.path.expanduser('~'),'modelProvider':'openai','ephemeral':True,'sandbox':'read-only',
            'approvalPolicy':'untrusted','environments':[],
            'developerInstructions':'Silent voice transport. Do not run tools, browse, execute commands or read files. Hermes handles all actual tasks; reply only skip.'})
        self.thread=((th or {}).get('thread') or {}).get('id')
        if not self.thread:raise RuntimeError('Codex thread/start returned no thread ID')
        await self.pc.setLocalDescription(await self.pc.createOffer())
        prompt=('You are speaking live to the user as the voice interface for Hermes Main. Speak naturally, concisely and in English. '
                'For tasks, device operations, factual queries requiring verification, and all actions, delegate to Hermes. '
                'Hermes exclusively owns tools, device access, memory and approvals. Do not claim an action has completed until Hermes confirms it. '
                'VOICE DELEGATION POLICY: Delegate whenever the user asks you to check, find, run, change, send, remember, research, report current facts, interact with Android, or use any tool. '
                'You must delegate such requests even if you already know a plausible answer. Never invent tool results. '
                'Do not delegate greetings, joking, or small talk. For task requests say one brief acknowledgement and delegate to the client; wait for Hermes results.')
        params={'threadId':self.thread,'model':self.model,'transport':{'type':'webrtc','sdp':self.pc.localDescription.sdp},
                'outputModality':'audio','version':'v3','voice':self.voice,'prompt':prompt,
                'clientManagedHandoffs':True,'delegationAckFiller':True,
                'realtimeStartInstructions':'Do not directly execute tasks. Delegate requests to Hermes; the client executes all work.'}
        await self.codex.request('thread/realtime/start',params,timeout=35)
        answer=None
        for _ in range(250):
            try:note=await asyncio.wait_for(self.codex.notifs.get(),.2)
            except asyncio.TimeoutError:continue
            if note.get('method')=='thread/realtime/sdp':
                answer=(note.get('params') or {}).get('sdp')
                if answer:break
            if note.get('method')=='thread/realtime/error':raise RuntimeError(str((note.get('params') or {}).get('message'))[:200])
        if not answer:raise TimeoutError('No Codex WebRTC SDP answer')
        await self.pc.setRemoteDescription(RTCSessionDescription(sdp=answer,type='answer'))
        for _ in range(200):
            if self.pc.connectionState=='connected' and self.dc.readyState=='open':break
            await asyncio.sleep(.1)
        if self.pc.connectionState!='connected' or self.dc.readyState!='open':raise TimeoutError('WebRTC media/data channel did not open')
        emit('ready',requested_model=self.model,voice=self.voice,transport='webrtc',auth='codex_subscription')
    async def receive_audio(self,track):
        resampler=AudioResampler(format='s16',layout='mono',rate=RATE)
        # Codex WebRTC outputs all-zero PCM continuously, even while idle.
        # Forwarding it made Android enter/hold Speaking and unnecessarily run
        # its microphone interruption path, leading to competing AudioRecords.
        # Preserve brief genuine speech pauses, but never stream idle silence.
        silence_tail_frames=0
        try:
            while True:
                frame=await track.recv()
                for out in resampler.resample(frame):
                    raw=bytes(out.planes[0]);raw=raw[:out.samples*2]
                    if not raw:continue
                    # WebRTC V3 emits persistent 1-8/32768-amplitude noise even
                    # after the assistant finishes. `any(raw)` was wrong: that
                    # near-zero stream continually reset the Android audio tail
                    # watchdog and could block the next microphone capture.
                    # Use a very low PCM16 peak floor (16/32768) instead.
                    samples=memoryview(raw).cast('h')
                    audible=max((abs(x) for x in samples),default=0)>16
                    if audible:
                        silence_tail_frames=35  # up to 700 ms natural speech pause
                    elif silence_tail_frames:
                        silence_tail_frames-=1
                    else:
                        continue
                    emit('audio_delta',audio_base64=base64.b64encode(raw).decode(),byte_count=len(raw))
        except asyncio.CancelledError:pass
        except Exception as e:
            if not self.closed:emit('error',message='audio track: '+str(e)[:150])
    def dc_send(self,msg):
        if self.dc.readyState!='open':raise RuntimeError('WebRTC data channel closed')
        self.dc.send(json.dumps(msg,separators=(',',':')))
    def handle_event(self,msg):
        typ=str(msg.get('type') or '')
        item=msg.get('item') or {}
        turn=msg.get('turn') or {}
        if typ=='delegation.created':
            if item.get('target') not in ('client',None):return
            did=str(item.get('id') or msg.get('delegation_item_id') or '')
            content=item.get('content') or []
            if isinstance(content,list):
                detail=''.join(str(c.get('text') or '') for c in content if isinstance(c,dict) and c.get('type')=='input_text')
            else:detail=''
            detail=detail.strip() or self.last_user_text
            if not did or did in self.delegations:return
            self.delegations.add(did)
            if not detail:
                self.dc_send({'type':'delegation.context.append','delegation_item_id':did,'content':[{'type':'input_text','text':'No task text received; please ask the user to repeat it.'}]})
                return
            emit('function_call_completed',call_id=did,name='hermes_run_task',arguments={'text':detail})
        elif typ=='input_transcript.added':
            val=str(item.get('text') or '')
            if val:emit('input_transcript_delta',delta=val)
        elif typ=='output_transcript.added':
            val=str(item.get('text') or '')
            if val:emit('output_text_delta',delta=val)
        elif typ=='turn.started':
            if turn.get('role')=='assistant':
                self.assistant_turn_id=str(turn.get('id') or '')
                emit('response_started',response_id=self.assistant_turn_id)
        elif typ=='turn.done':
            if turn.get('role')=='user':
                final=str(turn.get('transcript') or '').strip()
                if final:
                    self.last_user_text=final
                    emit('input_transcript_final',text=final)
            elif turn.get('role')=='assistant':
                self.assistant_turn_id=''
                emit('audio_done')
                emit('response_done',response_id=str(turn.get('id') or ''))
        elif typ=='error':emit('error',message='GPT-Live provider error')
        elif os.getenv('CODEX_LIVE_DEBUG_EVENTS')=='1': emit('debug',event_type=typ,keys=sorted(msg.keys()))
    async def receive_commands(self):
        while True:
            raw=await asyncio.to_thread(sys.stdin.readline)
            if not raw:break
            try:msg=json.loads(raw); op=msg.get('op')
            except Exception:continue
            try:
                if op=='send_audio':self.track.append(base64.b64decode(msg.get('pcm') or ''))
                elif op=='commit_audio':pass # Codex V3 uses live VAD on the RTP audio track.
                elif op=='send_text':self.dc_send({'type':'conversation.item.create','item':{'type':'message','role':'user','content':[{'type':'input_text','text':msg.get('text','')}]}});self.dc_send({'type':'response.create'})
                elif op=='clear_audio':
                    # Microphone audio is a WebRTC RTP track, not a Realtime API
                    # input_audio_buffer. This unsupported data-channel event
                    # triggers a fatal provider error during Android cancels.
                    self.track.buffer.clear()
                elif op=='cancel_response':
                    if self.assistant_turn_id:
                        try:await self.codex.request('turn/interrupt',{'threadId':self.thread,'turnId':self.assistant_turn_id},timeout=7)
                        except Exception:pass
                elif op=='send_tool_result':
                    # Codex V3 custom delegation follow-up, NOT an OpenAI API function tool.
                    self.dc_send({'type':'delegation.context.append','delegation_item_id':str(msg.get('call_id') or ''),'content':[{'type':'input_text','text':('Task result (answer briefly and naturally): '+json.dumps(msg.get('output') or {},ensure_ascii=False))[:3500]}]})
                elif op=='append_context_item':
                    self.dc_send({'type':'conversation.item.create','item':{'type':'message','role':msg.get('role') or 'assistant','content':[{'type':'text','text':msg.get('text') or ''}]}})
                elif op=='request_response':
                    payload={'type':'response.create'}
                    if msg.get('instructions'):payload['response']={'instructions':msg['instructions']}
                    self.dc_send(payload)
                elif op=='close':return
            except Exception as exc:emit('error',message='command '+str(op)+': '+str(exc)[:250])
    async def close(self):
        self.closed=True
        if self.thread:
            try:await asyncio.wait_for(self.codex.request('thread/realtime/stop',{'threadId':self.thread},timeout=5),6)
            except Exception:pass
        if self.track_task:self.track_task.cancel()
        await self.pc.close();await self.codex.close()

async def main():
    model=os.getenv('CODEX_LIVE_MODEL','gpt-live-1.1')
    voice=os.getenv('CODEX_LIVE_VOICE','sol')
    if model!='gpt-live-1.1':raise RuntimeError('Unexpected model; refusing downgrade')
    worker=LiveWorker(model,voice)
    try:
        await worker.connect()
        await worker.receive_commands()
    finally:await worker.close()

if __name__=='__main__':
    try:asyncio.run(main())
    except BaseException as exc:
        emit('error',message=type(exc).__name__+': '+str(exc)[:300])
        sys.exit(1)
