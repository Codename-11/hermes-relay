import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { auditDetails, categorizeTool, persistAuditScreenshot, resultExitCode, summarizeResult } from '../src/lib/auditLog.js'
import { toolResultSucceeded } from '../src/tools/router.js'

test('audit events classify the activity surfaces used by the tray', () => {
  assert.equal(categorizeTool('desktop_powershell'), 'command')
  assert.equal(categorizeTool('desktop_read_file'), 'files')
  assert.equal(categorizeTool('desktop_computer_screenshot'), 'screen')
  assert.equal(categorizeTool('desktop_computer_input'), 'input')
  assert.equal(categorizeTool('desktop_adb_shell'), 'devices')
  assert.equal(categorizeTool('daemon.connect'), 'system')
  assert.equal(categorizeTool('desktop_unknown'), 'other')
})

test('activity drilldown retains bounded command and stream evidence without sensitive inputs', () => {
  const details = auditDetails(
    { script: 'Write-Output "hello"', cwd: 'C:\\work', env: { SECRET: 'hidden' }, content: 'private file body' },
    { exit_code: 0, stdout: 'hello\n', stderr: '', output: { stdout: { truncated: false } } }
  )
  assert.match(details.request_detail ?? '', /Write-Output/)
  assert.doesNotMatch(details.request_detail ?? '', /SECRET|private file body/)
  assert.equal(details.stdout, 'hello\n')
  assert.equal(details.stderr, '')
  assert.match(details.result_detail ?? '', /"exit_code": 0/)
})

test('audit events preserve process exit outcome separately from dispatch success', () => {
  const result = { exit_code: 17, stdout: '', stderr: 'failed' }
  assert.equal(resultExitCode(result), 17)
  assert.equal(summarizeResult(result), 'exit 17')
  assert.equal(resultExitCode({ path: 'C:\\temp\\file.txt' }), undefined)
})

test('computer-use audit redacts screenshots, UI trees, labels, and entered values', () => {
  const details = auditDetails(
    { pid: 42, window_id: 7, value: 'top-secret-password' },
    {
      backend: 'cua_driver',
      target: { pid: 42, windowId: 7, title: 'Password Manager' },
      tree_markdown: 'Password: hunter2',
      screenshot_base64: 'sensitive-pixels',
      elements: [{ label: 'API token sk-live-secret' }]
    },
    { redactComputerContent: true }
  )
  const serialized = JSON.stringify(details)
  assert.doesNotMatch(serialized, /hunter2|sensitive-pixels|sk-live-secret|top-secret-password|Password Manager/)
  assert.match(details.result_detail ?? '', /"redacted": true/)
  assert.match(details.result_detail ?? '', /"pid": 42/)
})

test('semantic computer-control rejection is audited as failed without changing transport semantics', () => {
  assert.equal(toolResultSucceeded('desktop_computer_action', { ok: false, code: 'sensitive_target_blocked' }), false)
  assert.equal(toolResultSucceeded('desktop_computer_action', { ok: true }), true)
  assert.equal(toolResultSucceeded('desktop_read_file', { ok: false }), true)
})

test('screenshot evidence is retained as a private opaque PNG instead of JSON content', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'hermes-activity-evidence-'))
  const previous = process.env.HERMES_RELAY_ACTIVITY_EVIDENCE_DIR
  process.env.HERMES_RELAY_ACTIVITY_EVIDENCE_DIR = directory
  try {
    const png = Buffer.from('89504e470d0a1a0a00000000', 'hex').toString('base64')
    const evidence = await persistAuditScreenshot({ screenshot_base64: png, screenshot_width: 640, screenshot_height: 480 }, 'request-1', 7)
    assert.match(evidence.screenshot_evidence_id ?? '', /^[a-f0-9]{32}$/)
    assert.equal(evidence.screenshot_width, 640)
    assert.equal((await readFile(join(directory, `${evidence.screenshot_evidence_id}.png`))).subarray(0, 8).toString('hex'), '89504e470d0a1a0a')
    assert.equal(JSON.stringify(evidence).includes(png), false)
  } finally {
    if (previous === undefined) delete process.env.HERMES_RELAY_ACTIVITY_EVIDENCE_DIR
    else process.env.HERMES_RELAY_ACTIVITY_EVIDENCE_DIR = previous
    await rm(directory, { recursive: true, force: true })
  }
})
