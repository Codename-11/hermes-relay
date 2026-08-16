import assert from 'node:assert/strict'
import test from 'node:test'

import { CliRenderer } from '../src/renderer.js'

function captureWrites(fn: () => void): { stderr: string; stdout: string } {
  const originalStdout = process.stdout.write
  const originalStderr = process.stderr.write
  let stdout = ''
  let stderr = ''
  process.stdout.write = ((chunk: string | Uint8Array) => {
    stdout += chunk.toString()
    return true
  }) as typeof process.stdout.write
  process.stderr.write = ((chunk: string | Uint8Array) => {
    stderr += chunk.toString()
    return true
  }) as typeof process.stderr.write
  try {
    fn()
  } finally {
    process.stdout.write = originalStdout
    process.stderr.write = originalStderr
  }
  return { stderr, stdout }
}

test('message.interim renders as sealed assistant output', () => {
  const output = captureWrites(() => {
    new CliRenderer({ noColor: true }).handle({
      type: 'message.interim',
      payload: { text: 'Still working' },
      session_id: 'sess-1'
    })
  })

  assert.equal(output.stdout, '\nStill working\n')
  assert.equal(output.stderr, '')
})

test('message.interim can use rendered fallback text', () => {
  const output = captureWrites(() => {
    new CliRenderer({ noColor: true }).handle({
      type: 'message.interim',
      payload: { rendered: 'Rendered preview\n' },
      session_id: 'sess-1'
    })
  })

  assert.equal(output.stdout, '\nRendered preview\n')
  assert.equal(output.stderr, '')
})

test('message.interim already_streamed seals without replaying text', () => {
  const output = captureWrites(() => {
    const renderer = new CliRenderer({ noColor: true })
    renderer.handle({
      type: 'message.delta',
      payload: { text: 'Already streamed' },
      session_id: 'sess-1'
    })
    renderer.handle({
      type: 'message.interim',
      payload: { text: 'Already streamed', already_streamed: true },
      session_id: 'sess-1'
    })
  })

  assert.equal(output.stdout, '\nAlready streamed\n')
  assert.equal(output.stderr, '')
})
