import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import test from 'node:test'

import { powershellHandler } from '../src/tools/handlers/powershell.js'
import { terminalHandler } from '../src/tools/handlers/terminal.js'

test('an already canceled terminal request cannot create its owned witness', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'hermes-canceled-terminal-'))
  const witness = join(directory, 'terminal-witness.txt')
  const controller = new AbortController()
  controller.abort()
  // The only possible process action is writing this test's own temporary file.
  const command = process.platform === 'win32'
    ? 'echo executed>terminal-witness.txt'
    : 'printf executed > terminal-witness.txt'
  try {
    await assert.rejects(
      terminalHandler({ command }, { cwd: directory, abortSignal: controller.signal, interactive: false }),
      error => error instanceof Error && error.name === 'AbortError'
    )
    assert.equal(existsSync(witness), false)
  } finally {
    await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 10 })
  }
})

test('an already canceled PowerShell request cannot create its owned witness or require PowerShell', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'hermes-canceled-powershell-'))
  const witness = join(directory, 'powershell-witness.txt')
  const controller = new AbortController()
  controller.abort()
  try {
    // This runs on every platform: cancellation must precede shell discovery.
    await assert.rejects(
      powershellHandler(
        { script: "[IO.File]::WriteAllText('powershell-witness.txt', 'executed')", prefer: 'powershell' },
        { cwd: directory, abortSignal: controller.signal, interactive: false }
      ),
      error => error instanceof Error && error.name === 'AbortError'
    )
    assert.equal(existsSync(witness), false)
  } finally {
    await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 10 })
  }
})
