import assert from 'node:assert/strict'
import test from 'node:test'

import { auditDetails, categorizeTool, resultExitCode, summarizeResult } from '../src/lib/auditLog.js'

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
