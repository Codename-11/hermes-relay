import assert from 'node:assert/strict'
import test from 'node:test'
import { promises as fs } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { TransferSessions, TRANSFER_CHUNK_SIZE } from '../src/tools/transferSession.js'
const hash = (b: Buffer): string => createHash('sha256').update(b).digest('hex')

for (const size of [0, 17, 4 * 1024 * 1024 + 71]) {
  test(`round trip ${size} bytes with acknowledged chunks`, async () => {
    const dir = await fs.mkdtemp(path.join(tmpdir(), 'relay-transfer-test-'))
    const sender = new TransferSessions(), receiver = new TransferSessions()
    const queue: Array<{ type: string; payload: Record<string, unknown>; to: TransferSessions }> = []
    const data = Buffer.alloc(size, 0xa7)
    const source = path.join(dir, 'source'), dest = path.join(dir, 'dest')
    let done = false
    try {
      await fs.writeFile(source, data)
      const meta = await sender.prepareSend('test', source, (type, payload) => queue.push({ type, payload, to: receiver }))
      await receiver.beginReceive({ transfer_id: 'test', path: dest, size: meta.size, sha256: hash(data), overwrite: false,
        emit: (type, payload) => {
          if (type === 'desktop.transfer_done') { done = true; return }
          assert.notEqual(type, 'desktop.transfer_error')
          queue.push({ type, payload, to: sender })
        } })
      await sender.handle('desktop.transfer_start', { transfer_id: 'test' })
      while (queue.length) {
        assert.equal(queue.length, 1, 'only one unacknowledged frame')
        const frame = queue.shift()!
        await frame.to.handle(frame.type, frame.payload)
      }
      assert.equal(done, true)
      assert.deepEqual(await fs.readFile(dest), data)
    } finally { await sender.closeAll(); await receiver.closeAll(); await fs.rm(dir, { recursive: true, force: true }) }
  })
}

test('invalid chunks, wrong hashes and destination races leave existing files intact', async () => {
  const dir = await fs.mkdtemp(path.join(tmpdir(), 'relay-transfer-test-'))
  const manager = new TransferSessions()
  try {
    for (const failure of ['sequence', 'encoding', 'size', 'hash', 'destination']) {
      const dest = path.join(dir, failure)
      const events: string[] = []
      await manager.beginReceive({ transfer_id: failure, path: dest, size: 1,
        sha256: hash(Buffer.from(failure === 'hash' ? 'b' : 'a')), overwrite: false,
        emit: type => events.push(type) })
      if (failure === 'destination') await fs.writeFile(dest, 'keep')
      await manager.handle('desktop.transfer_chunk', { transfer_id: failure,
        index: failure === 'sequence' ? 1 : 0, total: 1,
        data: failure === 'encoding' ? '!!' : Buffer.from(failure === 'size' ? 'ab' : 'a').toString('base64') })
      assert.deepEqual(events, ['desktop.transfer_error'])
      if (failure === 'destination') assert.equal(await fs.readFile(dest, 'utf8'), 'keep')
      else await assert.rejects(fs.access(dest))
    }
    assert.deepEqual(await fs.readdir(dir), ['destination'])
  } finally { await manager.closeAll(); await fs.rm(dir, { recursive: true, force: true }) }
})

test('sessions are router-scoped and teardown removes partial files', async () => {
  const dir = await fs.mkdtemp(path.join(tmpdir(), 'relay-transfer-test-'))
  const owner = new TransferSessions(), stranger = new TransferSessions()
  try {
    const dest = path.join(dir, 'dest')
    await owner.beginReceive({ transfer_id: 'same', path: dest, size: 1, sha256: hash(Buffer.from('a')), overwrite: false, emit: () => {} })
    await stranger.handle('desktop.transfer_chunk', { transfer_id: 'same', index: 0, total: 1, data: 'YQ==' })
    await assert.rejects(fs.access(dest))
    await owner.closeAll()
    assert.deepEqual(await fs.readdir(dir), [])
    await assert.rejects(owner.beginReceive({ transfer_id: '../bad', path: dest, size: 0, sha256: hash(Buffer.alloc(0)), overwrite: false, emit: () => {} }))
  } finally { await owner.closeAll(); await stranger.closeAll(); await fs.rm(dir, { recursive: true, force: true }) }
})

test('pull waits for start and each acknowledgement, then reports digest', async () => {
  const dir = await fs.mkdtemp(path.join(tmpdir(), 'relay-transfer-test-'))
  const manager = new TransferSessions()
  const events: Array<{ type: string; payload: Record<string, unknown> }> = []
  try {
    const source = path.join(dir, 'source'), data = Buffer.alloc(TRANSFER_CHUNK_SIZE + 1, 1)
    await fs.writeFile(source, data)
    await manager.prepareSend('pull', source, (type, payload) => events.push({ type, payload }))
    assert.equal(events.length, 0)
    await manager.handle('desktop.transfer_start', { transfer_id: 'pull' })
    assert.equal(events.length, 1)
    await manager.handle('desktop.transfer_progress', { transfer_id: 'pull', received: 1 })
    await manager.handle('desktop.transfer_progress', { transfer_id: 'pull', received: 2 })
    assert.equal(events[2].type, 'desktop.transfer_done')
    assert.equal(events[2].payload.sha256, hash(data))
  } finally { await manager.closeAll(); await fs.rm(dir, { recursive: true, force: true }) }
})
