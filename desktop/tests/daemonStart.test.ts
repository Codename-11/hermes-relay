import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import test from 'node:test'

import type { DaemonStatus } from '../src/lib/daemonStatus.js'
import {
  __buildDaemonChildArgsForTests as buildDaemonChildArgs,
  __buildElevationLaunchPlanForTests as buildElevationLaunchPlan,
  __daemonStatusIsReadyForTests as daemonStatusIsReady,
  __quoteWindowsArgumentForTests as quoteWindowsArgument,
  __readDetachedStartupFailureForTests as readDetachedStartupFailure,
  __waitForDetachedStartupForTests as waitForDetachedStartup
} from '../src/commands/daemon.js'
import type { ParsedArgs } from '../src/cli.js'

function status(pid: number, state: DaemonStatus['state']): DaemonStatus {
  return {
    pid,
    url: 'wss://relay.example.test',
    state,
    started_at: 1,
    updated_at: 1
  }
}

test('detached startup ignores stale status and succeeds only for the child pid', async () => {
  let now = 0
  const statuses = [status(41, 'connected'), status(42, 'starting'), status(42, 'connected')]
  let read = 0

  const result = await waitForDetachedStartup(42, 1_000, {
    readStatus: async () => statuses[Math.min(read++, statuses.length - 1)] ?? null,
    isAlive: () => true,
    readFailure: async () => null,
    now: () => now,
    sleep: async ms => {
      now += ms
    }
  })

  assert.equal(result.outcome, 'ready')
  assert.equal(result.outcome === 'ready' ? result.status.pid : null, 42)
})

test('detached startup reports log evidence before generic child-exit failure', async () => {
  let now = 0
  const result = await waitForDetachedStartup(42, 1_000, {
    readStatus: async () => null,
    isAlive: () => false,
    readFailure: async () => 'consent_missing: pair with --grant-tools',
    now: () => now,
    sleep: async ms => {
      now += ms
    }
  })

  assert.deepEqual(result, {
    outcome: 'failed',
    detail: 'consent_missing: pair with --grant-tools'
  })
})

test('detached startup times out with the matching in-progress status', async () => {
  let now = 0
  const starting = status(42, 'reconnecting')
  const result = await waitForDetachedStartup(42, 200, {
    readStatus: async () => starting,
    isAlive: () => true,
    readFailure: async () => null,
    now: () => now,
    sleep: async ms => {
      now += ms
    }
  })

  assert.equal(result.outcome, 'timeout')
  assert.equal(result.outcome === 'timeout' ? result.status?.state : null, 'reconnecting')
})

test('connected status is not ready when it belongs to another process', () => {
  assert.equal(daemonStatusIsReady(status(41, 'connected'), 42), false)
  assert.equal(daemonStatusIsReady(status(42, 'starting'), 42), false)
  assert.equal(daemonStatusIsReady(status(42, 'connected'), 42), true)
})

test('startup log diagnostics only inspect bytes appended for this attempt', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'hermes-daemon-start-'))
  t.after(() => fs.rm(dir, { recursive: true, force: true }))
  const logPath = path.join(dir, 'daemon.log')
  const oldLine = JSON.stringify({ level: 'error', event: 'old_failure', message: 'ignore me' }) + '\n'
  await fs.writeFile(logPath, oldLine)
  const offset = Buffer.byteLength(oldLine)
  await fs.appendFile(
    logPath,
    JSON.stringify({ level: 'error', event: 'consent_missing', message: 'pair first' }) + '\n'
  )

  assert.equal(
    await readDetachedStartupFailure(logPath, offset),
    'consent_missing: pair first'
  )
  assert.equal(await readDetachedStartupFailure(logPath, (await fs.stat(logPath)).size), null)
})

test('daemon child argv forwards runtime options without lifecycle privilege flags', () => {
  const args: ParsedArgs = {
    command: 'daemon',
    positional: ['restart'],
    flags: {
      remote: 'wss://relay.example.test/path with space',
      'no-voice': true,
      administrator: true,
      user: true
    }
  }

  assert.deepEqual(buildDaemonChildArgs(args), [
    'daemon',
    '--remote',
    'wss://relay.example.test/path with space',
    '--no-voice'
  ])
})

test('Windows argument quoting preserves spaces, quotes, and trailing slashes', () => {
  assert.equal(quoteWindowsArgument('plain'), 'plain')
  assert.equal(quoteWindowsArgument('two words'), '"two words"')
  assert.equal(quoteWindowsArgument('say"hello'), '"say\\"hello"')
  assert.equal(quoteWindowsArgument('C:\\path with space\\'), '"C:\\path with space\\\\"')
})

test('elevation launch plan targets one explicit lifecycle action and prevents recursion', () => {
  const args: ParsedArgs = {
    command: 'daemon',
    positional: ['restart'],
    flags: {
      remote: 'wss://relay.example.test/path with space',
      'log-json': true,
      administrator: true
    }
  }
  const plan = buildElevationLaunchPlan(args, 'restart')

  assert.equal(plan.program, 'powershell.exe')
  assert.match(plan.args.at(-1) ?? '', /Start-Process.+-Verb RunAs.+-Wait/s)
  assert.deepEqual(plan.targetArgs.slice(-6), [
    'daemon',
    'restart',
    '--remote',
    'wss://relay.example.test/path with space',
    '--log-json',
    '--elevation-child'
  ])
  assert.equal(plan.targetArgs.includes('--administrator'), false)

  const encoded = plan.env.HERMES_RELAY_ELEVATE_ARGS
  assert.equal(typeof encoded, 'string')
  const commandLine = Buffer.from(encoded as string, 'base64').toString('utf8')
  assert.match(commandLine, /daemon restart/)
  assert.match(commandLine, /"wss:\/\/relay\.example\.test\/path with space"/)
})
