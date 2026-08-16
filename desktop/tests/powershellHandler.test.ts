import assert from 'node:assert/strict'
import test from 'node:test'

import { powershellHandler } from '../src/tools/handlers/powershell.js'

const windowsOnly = { skip: process.platform !== 'win32' }

async function run(script: string) {
  const controller = new AbortController()
  return (await powershellHandler(
    { script, prefer: 'powershell', timeout: 30 },
    { cwd: process.cwd(), abortSignal: controller.signal, interactive: false }
  )) as {
    stdout: string
    stderr: string
    exit_code: number
    truncated: boolean
    truncation_reason?: string
    output: {
      limit_bytes_per_stream: number
      stdout: { bytes: number; captured_bytes: number; truncated: boolean }
      stderr: { bytes: number; captured_bytes: number; truncated: boolean }
    }
  }
}

test('PowerShell captures scalar, Write-Output, pipeline, foreach, and JSON output', windowsOnly, async () => {
  const result = await run(`
'scalar'
Write-Output 'written'
foreach ($i in 1..3) { Write-Output "row-$i" }
[pscustomobject]@{ ok = $true; count = 3 } | ConvertTo-Json -Compress
`)

  assert.equal(result.exit_code, 0)
  assert.equal(result.stderr, '')
  assert.match(result.stdout, /scalar/)
  assert.match(result.stdout, /written/)
  assert.match(result.stdout, /row-1\r?\nrow-2\r?\nrow-3/)
  assert.match(result.stdout, /{"ok":true,"count":3}/)
  assert.equal(result.output.stdout.bytes, Buffer.byteLength(result.stdout))
  assert.equal(result.truncated, false)
})

test('PowerShell captures stdout and stderr while propagating native exit status', windowsOnly, async () => {
  const result = await run(`
Write-Output 'before-native'
cmd.exe /d /c "echo native-out& echo native-error 1>&2& exit /b 7"
`)

  assert.equal(result.exit_code, 7)
  assert.match(result.stdout, /before-native/)
  assert.match(result.stdout, /native-out/)
  assert.match(result.stderr, /native-error/)
})

test('PowerShell distinguishes an intentional zero-output success', windowsOnly, async () => {
  const result = await run('$value = 42')

  assert.equal(result.exit_code, 0)
  assert.equal(result.stdout, '')
  assert.equal(result.stderr, '')
  assert.equal(result.output.stdout.bytes, 0)
  assert.equal(result.output.stdout.truncated, false)
})

test('PowerShell reports per-stream truncation metadata without disguising it as empty success', windowsOnly, async () => {
  const result = await run("[Console]::Out.Write(('x' * 4300000))")

  assert.equal(result.exit_code, 0)
  assert.equal(result.truncated, true)
  assert.equal(result.truncation_reason, 'output_limit')
  assert.equal(result.output.stdout.truncated, true)
  assert.equal(result.output.stdout.captured_bytes, result.output.limit_bytes_per_stream)
  assert.ok(result.output.stdout.bytes > result.output.stdout.captured_bytes)
  assert.equal(Buffer.byteLength(result.stdout), result.output.stdout.captured_bytes)
})
