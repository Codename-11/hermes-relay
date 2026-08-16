import type { GatewayEvent, SessionResumeActivity } from './gatewayTypes.js'

interface GatewayEventSource {
  on(event: 'event', listener: (event: GatewayEvent) => void): unknown
  off(event: 'event', listener: (event: GatewayEvent) => void): unknown
}

const DEFAULT_IDLE_TIMEOUT_MS = 300_000
const MAX_BUFFERED_EVENTS = 512

/**
 * Attach before `session.resume`, then drain every already-accepted turn to a
 * terminal boundary before a CLI surface is allowed to submit another prompt.
 */
export class SessionResumeDrain {
  private readonly buffered: GatewayEvent[] = []
  private readonly listener: (event: GatewayEvent) => void
  private readonly promise: Promise<void>
  private resolvePromise!: () => void
  private rejectPromise!: (error: Error) => void
  private targetSessionId: string | null = null
  private remainingTurns = 0
  private timer: ReturnType<typeof setTimeout> | null = null
  private settled = false

  constructor(
    private readonly source: GatewayEventSource,
    private readonly onEvent?: (event: GatewayEvent) => void,
    private readonly idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS
  ) {
    this.promise = new Promise<void>((resolve, reject) => {
      this.resolvePromise = resolve
      this.rejectPromise = reject
    })
    this.listener = (event) => this.observe(event)
    source.on('event', this.listener)
  }

  activate(sessionId: string, activity: SessionResumeActivity): Promise<void> {
    if (this.targetSessionId !== null) {
      throw new Error('resume activity drain already activated')
    }
    this.targetSessionId = sessionId
    this.remainingTurns = activity === 'running-and-queued' ? 2 : activity === 'idle' ? 0 : 1
    if (this.remainingTurns === 0) {
      this.finish()
      return this.promise
    }
    this.armTimer()
    const pending = this.buffered.splice(0)
    pending.forEach((event) => this.observe(event))
    return this.promise
  }

  cancel(): void {
    this.finish()
  }

  private observe(event: GatewayEvent): void {
    if (this.settled) return
    if (this.targetSessionId === null) {
      if (this.buffered.length < MAX_BUFFERED_EVENTS) this.buffered.push(event)
      return
    }
    if (event.session_id !== this.targetSessionId) return
    this.onEvent?.(event)
    this.armTimer()
    if (event.type === 'message.complete' || event.type === 'error') {
      this.remainingTurns -= 1
      if (this.remainingTurns <= 0) this.finish()
    }
  }

  private armTimer(): void {
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.finish(new Error(`resumed activity idle timeout after ${this.idleTimeoutMs}ms`))
    }, this.idleTimeoutMs)
    this.timer.unref?.()
  }

  private finish(error?: Error): void {
    if (this.settled) return
    this.settled = true
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    this.buffered.length = 0
    this.source.off('event', this.listener)
    if (error) this.rejectPromise(error)
    else this.resolvePromise()
  }
}
