import assert from 'node:assert/strict'
import test from 'node:test'

import {
  sessionProjectName,
  sessionQueuedPromptPreview,
  sessionResumeActivity,
  type SessionResumeResponse
} from '../src/gatewayTypes.js'

function response(fields: Partial<SessionResumeResponse>): SessionResumeResponse {
  return { messages: [], session_id: 'live-1', ...fields }
}

test('session resume activity distinguishes queued-only state', () => {
  assert.equal(sessionResumeActivity(response({ queued: { user: 'next prompt' } })), 'queued')
})

test('session resume activity distinguishes inflight-only state', () => {
  assert.equal(
    sessionResumeActivity(response({ inflight: { streaming: true, user: 'current prompt' } })),
    'running'
  )
})

test('session resume activity distinguishes inflight and queued state', () => {
  assert.equal(
    sessionResumeActivity(
      response({
        inflight: { streaming: true, user: 'current prompt' },
        queued: { user: 'next prompt' }
      })
    ),
    'running-and-queued'
  )
})

test('session resume activity distinguishes settled state', () => {
  assert.equal(sessionResumeActivity(response({})), 'idle')
})

test('queued prompt preview is one line and bounded', () => {
  assert.equal(
    sessionQueuedPromptPreview(response({ queued: { user: '  first line\n\u001b[31msecond\u001b[0m   line  ' } })),
    'first line second line'
  )
  assert.equal(sessionQueuedPromptPreview(response({ queued: { user: 'abcdefgh' } }), 6), 'abcde…')
  assert.equal(sessionQueuedPromptPreview(response({ queued: { user: '   ' } })), null)
})

test('session project name accepts optional project metadata', () => {
  assert.equal(
    sessionProjectName({
      model: 'gpt-5.5',
      project: {
        id: 'project-17',
        slug: 'hermes-relay',
        name: ' Hermes Relay ',
        primary_path: '/workspace/hermes-relay'
      },
      skills: {},
      tools: {}
    }),
    'Hermes Relay'
  )
})

test('session project name remains absent for legacy metadata', () => {
  assert.equal(sessionProjectName({ model: 'gpt-5.5', skills: {}, tools: {} }), null)
})
