// Router-scoped, bounded file transfers. One acknowledged chunk is in flight.
import { createHash, randomUUID } from 'node:crypto'
import { promises as fs } from 'node:fs'
import type { FileHandle } from 'node:fs/promises'
import * as path from 'node:path'

export const TRANSFER_CHUNK_SIZE = 256 * 1024
export const TRANSFER_MAX_BYTES = 8 * 1024 ** 3
const IDLE_MS = 60_000
export type TransferEmit = (type: string, payload: Record<string, unknown>) => void
interface Session {
  id: string
  direction: 'receive' | 'send'
  file: FileHandle
  size: number
  total: number
  index: number
  bytes: number
  hash: ReturnType<typeof createHash>
  emit: TransferEmit
  timer?: ReturnType<typeof setTimeout>
  part?: string
  destination?: string
  overwrite?: boolean
  sha256?: string
  busy: boolean
  closed: boolean
}

export class TransferSessions {
  private sessions = new Map<string, Session>()
  private generation = 0

  private validate(id: string, size: number): void {
    if (!/^[a-zA-Z0-9-]{1,64}$/.test(id)) throw new Error('invalid transfer_id')
    if (!Number.isSafeInteger(size) || size < 0 || size > TRANSFER_MAX_BYTES) throw new Error('invalid transfer size (maximum 8 GiB)')
    if (this.sessions.has(id)) throw new Error('duplicate transfer_id')
    if (this.sessions.size >= 4) throw new Error('too many active transfers')
  }

  private touch(s: Session): void {
    clearTimeout(s.timer)
    s.timer = setTimeout(() => { void this.fail(s, 'transfer idle timeout') }, IDLE_MS)
    s.timer.unref()
  }

  async beginReceive(args: { transfer_id: string; path: string; size: number; sha256: string; overwrite: boolean; emit: TransferEmit }): Promise<void> {
    this.validate(args.transfer_id, args.size)
    if (!/^[a-f0-9]{64}$/.test(args.sha256)) throw new Error('invalid sha256')
    const generation = this.generation
    const destination = path.resolve(args.path)
    await fs.mkdir(path.dirname(destination), { recursive: true })
    if (!args.overwrite) {
      try { await fs.lstat(destination); throw new Error('destination exists (set overwrite to replace)') }
      catch (e) { if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e }
    }
    const part = path.join(path.dirname(destination), `.hermes-transfer-${randomUUID()}.part`)
    const file = await fs.open(part, 'wx', 0o600)
    try {
      this.validate(args.transfer_id, args.size)
      if (generation !== this.generation) throw new Error('router detached')
    } catch (e) { await file.close(); await fs.rm(part, { force: true }); throw e }
    const s: Session = { id: args.transfer_id, direction: 'receive', file, size: args.size,
      total: Math.max(1, Math.ceil(args.size / TRANSFER_CHUNK_SIZE)), index: 0, bytes: 0,
      hash: createHash('sha256'), emit: args.emit, destination, part, overwrite: args.overwrite,
      sha256: args.sha256, busy: false, closed: false }
    this.sessions.set(s.id, s)
    this.touch(s)
  }

  async prepareSend(id: string, filePath: string, emit: TransferEmit): Promise<{ size: number; total_chunks: number }> {
    this.validate(id, 0)
    const generation = this.generation
    const file = await fs.open(filePath, 'r')
    try {
      const stat = await file.stat()
      if (!stat.isFile()) throw new Error('not a regular file')
      this.validate(id, stat.size)
      if (generation !== this.generation) throw new Error('router detached')
      const s: Session = { id, direction: 'send', file, size: stat.size,
        total: Math.max(1, Math.ceil(stat.size / TRANSFER_CHUNK_SIZE)), index: 0, bytes: 0,
        hash: createHash('sha256'), emit, busy: false, closed: false }
      this.sessions.set(id, s)
      this.touch(s)
      return { size: s.size, total_chunks: s.total }
    } catch (e) { await file.close(); throw e }
  }

  async handle(type: string, payload: Record<string, unknown>): Promise<void> {
    const s = typeof payload.transfer_id === 'string' ? this.sessions.get(payload.transfer_id) : undefined
    if (!s || s.closed) return
    if (type === 'desktop.transfer_abort' || type === 'desktop.transfer_error') { await this.close(s); return }
    // A peer must wait for an acknowledgement before sending another frame.
    if (s.busy) { await this.fail(s, 'overlapping transfer frames'); return }
    s.busy = true
    try {
      if (s.direction === 'receive' && type === 'desktop.transfer_chunk') {
        if (payload.index !== s.index || payload.total !== s.total) throw new Error('invalid chunk sequence')
        const data = payload.data
        if (typeof data !== 'string' || data.length > Math.ceil(TRANSFER_CHUNK_SIZE / 3) * 4) throw new Error('invalid chunk data')
        const chunk = Buffer.from(data, 'base64')
        if (chunk.toString('base64') !== data) throw new Error('invalid base64')
        const expected = Math.min(TRANSFER_CHUNK_SIZE, s.size - s.bytes)
        if (chunk.length !== expected) throw new Error('chunk size mismatch')
        let offset = 0
        while (offset < chunk.length) {
          const written = await s.file.write(chunk, offset, chunk.length - offset, s.bytes + offset)
          if (!written.bytesWritten) throw new Error('short write')
          offset += written.bytesWritten
        }
        if (s.closed) return
        s.hash.update(chunk); s.bytes += chunk.length; s.index++
        if (s.index === s.total) {
          const sha256 = s.hash.digest('hex')
          if (sha256 !== s.sha256) throw new Error('sha256 mismatch')
          await s.file.sync()
          await s.file.close()
          if (s.closed) return
          if (s.overwrite) await fs.rename(s.part!, s.destination!)
          else { await fs.link(s.part!, s.destination!); await fs.unlink(s.part!) }
          s.emit('desktop.transfer_done', { transfer_id: s.id, size: s.bytes, sha256 })
          await this.close(s)
        } else {
          this.touch(s)
          s.emit('desktop.transfer_progress', { transfer_id: s.id, received: s.index })
        }
      } else if (s.direction === 'send' && (type === 'desktop.transfer_start' || type === 'desktop.transfer_progress')) {
        if (type === 'desktop.transfer_start' ? s.index !== 0 : payload.received !== s.index || s.index === 0) throw new Error('invalid transfer acknowledgement')
        if (s.index === s.total) {
          s.emit('desktop.transfer_done', { transfer_id: s.id, size: s.bytes, sha256: s.hash.digest('hex') })
          await this.close(s)
        } else {
          const chunk = Buffer.alloc(Math.min(TRANSFER_CHUNK_SIZE, s.size - s.bytes))
          let offset = 0
          while (offset < chunk.length) {
            const read = await s.file.read(chunk, offset, chunk.length - offset, s.bytes + offset)
            if (!read.bytesRead) throw new Error('source changed during transfer')
            offset += read.bytesRead
          }
          if (s.closed) return
          s.hash.update(chunk); s.bytes += chunk.length
          const index = s.index++
          this.touch(s)
          s.emit('desktop.transfer_chunk', { transfer_id: s.id, index, total: s.total, data: chunk.toString('base64') })
        }
      } else throw new Error('unexpected transfer frame')
    } catch (e) { await this.fail(s, e instanceof Error ? e.message : String(e)) }
    finally { s.busy = false }
  }

  private async fail(s: Session, message: string): Promise<void> {
    try { s.emit('desktop.transfer_error', { transfer_id: s.id, message }) }
    catch { /* The transport may already be closed. */ }
    await this.close(s)
  }

  private async close(s: Session): Promise<void> {
    if (s.closed) return
    s.closed = true
    clearTimeout(s.timer)
    this.sessions.delete(s.id)
    await s.file.close().catch(() => {})
    if (s.part) await fs.rm(s.part, { force: true }).catch(() => {})
  }

  async closeAll(): Promise<void> {
    this.generation++
    await Promise.all([...this.sessions.values()].map(s => this.close(s)))
  }
}
