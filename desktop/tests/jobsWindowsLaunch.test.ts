import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import test, { type TestContext } from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'

import { jobStartHandler, jobStatusHandler } from '../src/tools/handlers/jobs.js'
import type { ToolContext } from '../src/tools/router.js'

interface StartedJob {
  job_id: string
  log_paths: { stdout: string; stderr: string }
}

async function fixture(t: TestContext): Promise<{ root: string; context: ToolContext }> {
  const root = mkdtempSync(path.join(os.tmpdir(), 'relay job launch '))
  t.mock.method(os, 'homedir', () => root)
  syncBuiltinESMExports()
  t.after(() => {
    t.mock.restoreAll()
    syncBuiltinESMExports()
    rmSync(root, { recursive: true, force: true })
  })
  return { root, context: { cwd: root, interactive: false, abortSignal: new AbortController().signal } }
}

async function startAndFinish(command: string, context: ToolContext): Promise<StartedJob> {
  const started = await jobStartHandler({ command, cwd: context.cwd }, context) as StartedJob
  for (let i = 0; i < 500; i++) {
    const status = await jobStatusHandler({ job_id: started.job_id }, context) as { state: string; exit_code?: number }
    if (status.state !== 'running') {
      assert.equal(status.exit_code, 37)
      assert.equal(status.state, 'failed')
      assert.ok(existsSync(started.log_paths.stdout))
      assert.ok(existsSync(started.log_paths.stderr))
      return started
    }
    await delay(20)
  }
  throw new Error('Synthetic job did not finish')
}

test('Windows jobs execute a quoted PowerShell script path containing spaces', { skip: process.platform !== 'win32' }, async t => {
  const { root, context } = await fixture(t)
  const script = path.join(root, 'job script.ps1')
  const marker = path.join(root, 'executed.marker')
  writeFileSync(script, '\ufeff' + [
    "[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'executed.marker'),'executed')",
    'exit 37'
  ].join('\r\n'))

  await startAndFinish(`powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "${script}"`, context)

  assert.equal(readFileSync(marker, 'utf8'), 'executed')
})

test('Windows jobs preserve quoted arguments passed to a child program', { skip: process.platform !== 'win32' }, async t => {
  const { root, context } = await fixture(t)
  const script = path.join(root, 'child program.cjs')
  const marker = path.join(root, 'arguments.marker')
  writeFileSync(script, [
    "require('node:fs').writeFileSync(process.argv[2],process.argv[3])",
    "process.stdout.write('JOB_STDOUT')",
    "process.stderr.write('JOB_STDERR')",
    'process.exit(37)'
  ].join('\n'))
  // Use node rather than process.execPath: the latter can be the compiled
  // CLI, whose command parser cannot execute an arbitrary JavaScript file.
  await startAndFinish(`node "${script}" "${marker}" "quoted argument value"`, context)

  assert.equal(readFileSync(marker, 'utf8'), 'quoted argument value')
})

test('Windows jobs preserve cmd quotes and both log streams', { skip: process.platform !== 'win32' }, async t => {
  const { context } = await fixture(t)

  const started = await startAndFinish('echo "quoted argument value" & echo JOB_STDERR 1>&2 & exit /b 37', context)

  assert.equal(readFileSync(started.log_paths.stdout, 'utf8').trim(), '"quoted argument value"')
  assert.equal(readFileSync(started.log_paths.stderr, 'utf8').trim(), 'JOB_STDERR')
})

test('POSIX jobs retain bash quoting, exit codes and log streams', { skip: process.platform === 'win32' }, async t => {
  const { root, context } = await fixture(t)
  const marker = path.join(root, 'quoted marker')
  const command = `printf '%s' 'quoted argument value' > "${marker}"; printf 'JOB_STDOUT'; printf 'JOB_STDERR' >&2; exit 37`

  const started = await startAndFinish(command, context)

  assert.equal(readFileSync(marker, 'utf8'), 'quoted argument value')
  assert.equal(readFileSync(started.log_paths.stdout, 'utf8'), 'JOB_STDOUT')
  assert.equal(readFileSync(started.log_paths.stderr, 'utf8'), 'JOB_STDERR')
})
