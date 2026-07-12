import type { CliRenderer } from './renderer.js'
import type { RelayTransport } from './transport/RelayTransport.js'
import { TypedStreamRenderer } from './typedStream.js'

const RELAY_TURN_IDLE_TIMEOUT_MS = 10 * 60_000

export interface RelayChatTurnHandle {
  cancel: () => void
  promise: Promise<void>
}

export function runRelayChatTurn(
  relay: RelayTransport,
  prompt: string,
  renderer: CliRenderer,
  options: {
    onSessionId: (sessionId: string) => void
    profile?: string
    sessionId?: string
  }
): RelayChatTurnHandle {
  const typed = new TypedStreamRenderer()
  let settled = false
  let settleResolve: (() => void) | null = null
  let settleReject: ((error: Error) => void) | null = null
  let timer: ReturnType<typeof setTimeout> | undefined

  const detach = () => relay.onChannel('chat', null)
  const finish = (error?: Error) => {
    if (settled) {
      return
    }
    settled = true
    clearTimeout(timer)
    detach()
    if (error) {
      settleReject?.(error)
    } else {
      settleResolve?.()
    }
  }
  const armIdleTimer = () => {
    clearTimeout(timer)
    timer = setTimeout(() => {
      finish(new Error(`relay chat idle timeout after ${RELAY_TURN_IDLE_TIMEOUT_MS}ms`))
    }, RELAY_TURN_IDLE_TIMEOUT_MS)
    timer.unref?.()
  }

  const promise = new Promise<void>((resolve, reject) => {
    settleResolve = resolve
    settleReject = reject
    relay.onChannel('chat', (type, payload) => {
      if (settled) {
        return
      }
      armIdleTimer()

      if (type === 'chat.session') {
        const sessionId = payload.session_id
        if (typeof sessionId === 'string' && sessionId.length > 0) {
          options.onSessionId(sessionId)
        }
        return
      }
      if (type === 'chat.error') {
        finish(new Error(typeof payload.message === 'string' ? payload.message : 'relay chat error'))
        return
      }
      if (type !== 'stream.event') {
        return
      }

      const rendered = typed.accept(payload)
      if (!rendered) {
        finish(new Error('relay sent an invalid typed stream.event v1 payload'))
        return
      }
      options.onSessionId(rendered.sessionId)
      for (const event of rendered.events) {
        renderer.handle(event)
      }
      if (rendered.terminal) {
        const errorEvent = rendered.events.find(event => event.type === 'error')
        if (errorEvent?.type === 'error') {
          finish(new Error(errorEvent.payload?.message ?? 'relay chat error'))
        } else {
          finish()
        }
      }
    })

    armIdleTimer()
    const payload: Record<string, unknown> = { message: prompt }
    if (options.sessionId) {
      payload.session_id = options.sessionId
    }
    if (options.profile && options.profile !== 'default') {
      payload.profile = options.profile
    }
    relay.sendChannel('chat', 'chat.send', payload)
  })

  return {
    promise,
    cancel: () => {
      if (!settled) {
        // Chat v1 has no channel-level interrupt envelope. Closing the carrier
        // cancels the relay's tracked upstream task and prevents late events
        // from leaking into a later turn.
        relay.kill()
        finish()
      }
    }
  }
}
