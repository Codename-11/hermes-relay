// paste — stage the local clipboard image into the server's paste inbox so
// the upstream Hermes TUI's `/paste` (or Alt+V) attaches it on the next
// invocation. Required for users connecting to a headless Linux server via
// hermes-relay shell mode where the server has no native clipboard.
//
// Flow:
//   1. Read this machine's clipboard (Windows / macOS / Linux — same code
//      path the chat REPL's `/paste` uses).
//   2. POST base64 + format to `<relay>/clipboard/inbox` with bearer auth.
//   3. The relay validates magic bytes and writes to `~/.hermes/images/inbox/`.
//   4. The fork's `hermes_cli/clipboard.py` checks the inbox first when the
//      user types `/paste` in the TUI, consumes the file (move-and-unlink),
//      and the image rides into the next prompt.submit.
//
// One-shot, non-interactive. No REPL. Designed for keybinding-from-Windows
// or "type paste in another terminal then /paste in the TUI" workflows.

import type { ParsedArgs } from '../cli.js'
import { captureClipboardImage } from '../chatAttach.js'
import { getSession, listSessions } from '../remoteSessions.js'

function wsToHttp(url: string): string {
  const trimmed = url.trim()
  if (trimmed.startsWith('wss://')) return 'https://' + trimmed.slice(6)
  if (trimmed.startsWith('ws://')) return 'http://' + trimmed.slice(5)
  return trimmed
}

async function resolveRemoteAndToken(args: ParsedArgs): Promise<{ url: string; token: string }> {
  const argUrl = typeof args.flags.remote === 'string' ? args.flags.remote.trim() : null
  const envUrl = process.env.HERMES_RELAY_URL?.trim()
  const argToken = typeof args.flags.token === 'string' ? args.flags.token.trim() : null
  const envToken = process.env.HERMES_RELAY_TOKEN?.trim()

  if (argToken || envToken) {
    const url = argUrl ?? envUrl
    if (!url) {
      throw new Error('--token supplied without --remote. Pass both, or set HERMES_RELAY_URL.')
    }
    return { url, token: (argToken ?? envToken)! }
  }

  const stored = await listSessions()
  const urls = Object.keys(stored)
  let url: string
  if (argUrl || envUrl) {
    url = argUrl ?? envUrl!
  } else if (urls.length === 1) {
    url = urls[0]!
  } else if (urls.length === 0) {
    throw new Error('No paired relays. Run `hermes-relay pair --remote ws://host:port` first.')
  } else {
    throw new Error(`Multiple paired relays; pass --remote to pick one (${urls.join(', ')}).`)
  }
  const rec = await getSession(url)
  if (!rec) {
    throw new Error(`No stored session for ${url}. Run \`hermes-relay pair --remote ${url}\` first.`)
  }
  return { url, token: rec.token }
}

export async function pasteCommand(args: ParsedArgs): Promise<number> {
  const json = !!args.flags.json
  const quiet = !!args.flags.quiet

  const attach = await captureClipboardImage()
  if (!attach) {
    const msg = 'No image on clipboard.'
    if (json) {
      process.stdout.write(JSON.stringify({ ok: false, error: msg }) + '\n')
    } else {
      process.stderr.write(msg + '\n')
    }
    return 2
  }

  const { url, token } = await resolveRemoteAndToken(args)
  const endpoint = `${wsToHttp(url)}/clipboard/inbox`

  const body = {
    format: attach.format,
    bytes_base64: attach.bytes_base64,
    filename_hint: attach.filename_hint ?? 'clip'
  }

  let res: Response
  try {
    res = await fetch(endpoint, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(body)
    })
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    if (json) {
      process.stdout.write(JSON.stringify({ ok: false, error: msg, endpoint }) + '\n')
    } else {
      process.stderr.write(`paste failed: ${msg}\n  endpoint: ${endpoint}\n`)
    }
    return 1
  }

  const text = await res.text()
  let parsed: { ok?: boolean; path?: string; size_bytes?: number; error?: string } | null = null
  try {
    parsed = text ? JSON.parse(text) : null
  } catch {
    parsed = null
  }

  if (!res.ok) {
    const errMsg = parsed?.error ?? text.slice(0, 200) ?? `HTTP ${res.status}`
    if (json) {
      process.stdout.write(JSON.stringify({ ok: false, status: res.status, error: errMsg }) + '\n')
    } else {
      process.stderr.write(`paste failed (HTTP ${res.status}): ${errMsg}\n`)
      if (res.status === 404) {
        process.stderr.write(
          '  hint: server may need axiom rollout — /clipboard/inbox is alpha.9.\n'
        )
      }
    }
    return 1
  }

  const sizeKb = Math.round((attach.size_bytes / 1024) * 10) / 10
  const dims = attach.width && attach.height ? `${attach.width}×${attach.height}` : '?'

  if (json) {
    process.stdout.write(
      JSON.stringify({
        ok: true,
        format: attach.format,
        size_bytes: attach.size_bytes,
        width: attach.width ?? null,
        height: attach.height ?? null,
        path: parsed?.path ?? null
      }) + '\n'
    )
  } else if (!quiet) {
    process.stdout.write(`✓ Image queued for /paste in TUI (${dims}, ${sizeKb} KB)\n`)
    process.stdout.write(`  Type /paste (or Alt+V) in the TUI to attach to next message.\n`)
  }
  return 0
}
