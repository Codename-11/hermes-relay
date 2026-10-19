// Subprocess peer for Python/TypeScript protocol interoperability tests.
import { createInterface } from 'node:readline'
import { TransferSessions } from '../../dist/tools/transferSession.js'
const sessions = new TransferSessions()
const emit = (type, payload) => process.stdout.write(JSON.stringify({ type, payload }) + '\n')
for await (const line of createInterface({ input: process.stdin })) {
  const { type, payload } = JSON.parse(line)
  if (type === 'desktop.command') {
    try {
      const a = payload.args
      let metadata = {}
      if (payload.tool === 'desktop_transfer_receive') await sessions.beginReceive({ ...a, emit })
      else metadata = await sessions.prepareSend(a.transfer_id, a.path, emit)
      emit('desktop.response', { request_id: payload.request_id, ok: true,
        result: { status: 'ready', protocol: 1, transfer_id: a.transfer_id, ...metadata } })
    } catch (e) { emit('desktop.response', { request_id: payload.request_id, ok: false, error: e.message }) }
  } else await sessions.handle(type, payload)
}
await sessions.closeAll()
