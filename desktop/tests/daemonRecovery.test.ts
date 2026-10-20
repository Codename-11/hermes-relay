import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { once } from 'node:events'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { Socket } from 'node:net'
import { fileURLToPath } from 'node:url'
import test, { type TestContext } from 'node:test'

import type { DaemonStatus } from '../src/lib/daemonStatus.js'

async function waitFor<T>(read: () => Promise<T | null>, timeout = 10_000): Promise<T> {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const value = await read()
    if (value !== null) return value
    await new Promise(resolve => setTimeout(resolve, 25))
  }
  throw new Error('daemon did not reach the expected state')
}

function frame(type: string, payload: Record<string, unknown>): Buffer {
  const body = Buffer.from(JSON.stringify({ channel: 'system', type, payload }))
  assert.ok(body.length < 126)
  return Buffer.concat([Buffer.from([0x81, body.length]), body])
}

async function daemonFixture(t: TestContext, rejectAuth = false) {
  const home = await mkdtemp(join(tmpdir(), 'relay-daemon-recovery-'))
  const sockets = new Set<Socket>()
  const server = createServer()
  let upgrades = 0
  let authenticated: Socket | undefined
  server.on('upgrade', (request, raw) => {
    const socket = raw as Socket
    sockets.add(socket)
    socket.on('error', () => {})
    socket.on('close', () => sockets.delete(socket))
    if (++upgrades === 1 && !rejectAuth) {
      socket.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
      return
    }
    const accept = createHash('sha1').update(`${request.headers['sec-websocket-key']}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest('base64')
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`)
    socket.once('data', () => {
      authenticated = socket
      socket.write(rejectAuth
        ? frame('auth.fail', { reason: 'invalid test credentials' })
        : frame('auth.ok', { session_token: 'test-token', server_version: 'test' }))
    })
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const port = (server.address() as import('node:net').AddressInfo).port
  const url = `ws://127.0.0.1:${port}`
  await mkdir(join(home, '.hermes'))
  await writeFile(join(home, '.hermes', 'remote-sessions.json'), JSON.stringify({
    version: 1, sessions: { [url]: { token: 'test-token', paired_at: 1, tools_consented: false } }
  }))
  const child = spawn(process.execPath, ['--import', 'tsx', fileURLToPath(new URL('../src/cli.ts', import.meta.url)),
    'daemon', '--remote', url, '--no-voice', '--log-json'], {
    cwd: fileURLToPath(new URL('..', import.meta.url)),
    env: { ...process.env, HOME: home, USERPROFILE: home }, stdio: ['ignore', 'pipe', 'pipe']
  })
  let log = ''
  child.stderr.on('data', data => { log += String(data) })
  child.stdout.resume()
  const exited = once(child, 'exit')
  t.after(async () => {
    if (child.exitCode === null) child.kill('SIGKILL')
    await exited
    for (const socket of sockets) socket.destroy()
    await new Promise<void>(resolve => server.close(() => resolve()))
    await rm(home, { recursive: true, force: true })
  })
  const status = async (): Promise<DaemonStatus | null> => {
    try { return JSON.parse(await readFile(join(home, '.hermes', 'daemon-status.json'), 'utf8')) as DaemonStatus }
    catch { return null }
  }
  return { child, exited, status, log: () => log, upgrades: () => upgrades,
    rejectSession: () => authenticated!.write(frame('auth.fail', { reason: 'test session revoked' })) }
}

test('headless daemon survives cold-start backoff and persists a later terminal failure without voice', { timeout: 15_000 }, async t => {
  const fixture = await daemonFixture(t)
  await waitFor(async () => {
    const status = await fixture.status()
    return status?.state === 'connected' && status.last_event === 'ready_locked' ? status : null
  })
  assert.equal(fixture.upgrades(), 2)
  assert.match(fixture.log(), /"event":"reconnecting"/)
  fixture.rejectSession()
  assert.equal((await fixture.exited)[0], 1)
  const stopped = await fixture.status()
  assert.equal(stopped?.state, 'stopped')
  assert.equal(stopped?.last_event, 'transport_exited')
  assert.match(stopped?.last_error ?? '', /test session revoked/)
})

test('initial auth rejection is flushed to disk before the daemon exits', { timeout: 15_000 }, async t => {
  const fixture = await daemonFixture(t, true)
  assert.equal((await fixture.exited)[0], 1)
  const stopped = await fixture.status()
  assert.equal(stopped?.state, 'stopped')
  assert.equal(stopped?.last_event, 'auth_failed')
  assert.equal(stopped?.last_error, 'invalid test credentials')
  assert.equal(fixture.upgrades(), 1)
})
