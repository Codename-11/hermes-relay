import assert from 'node:assert/strict'
import test from 'node:test'

import { categorizeTool, resultExitCode, summarizeResult } from '../src/lib/auditLog.js'

test('audit events classify the activity surfaces used by the tray', () => {
  assert.equal(categorizeTool('desktop_powershell'), 'command')
  assert.equal(categorizeTool('desktop_read_file'), 'files')
  assert.equal(categorizeTool('desktop_computer_screenshot'), 'screen')
  assert.equal(categorizeTool('desktop_computer_input'), 'input')
  assert.equal(categorizeTool('daemon.connect'), 'system')
  assert.equal(categorizeTool('desktop_unknown'), 'other')
})

test('audit events preserve process exit outcome separately from dispatch success', () => {
  const result = { exit_code: 17, stdout: '', stderr: 'failed' }
  assert.equal(resultExitCode(result), 17)
  assert.equal(summarizeResult(result), 'exit 17')
  assert.equal(resultExitCode({ path: 'C:\\temp\\file.txt' }), undefined)
})
