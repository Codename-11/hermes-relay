// Actionable error hints — map a raw failure to a "here's what to do" line.
//
// Commands used to surface bare error strings ("auth error", "no session
// matches prefix") that leave the user stuck. suggestedFix() pattern-matches
// the common failure classes and returns a next-step command; formatError()
// renders the error + hint together so stderr is always actionable.

import type { Theme } from './theme.js'

export interface HintContext {
  /** The subcommand that failed (for tailoring the suggestion). */
  command?: string
  /** The relay URL in play — substituted into suggested commands. */
  url?: string
}

function errMessage(err: unknown): string {
  if (err instanceof Error) {
    return err.message
  }
  return String(err)
}

/**
 * Return a one-line actionable hint for a failure, or null if we have nothing
 * better to say than the raw error. Ordered most-specific first.
 */
export function suggestedFix(err: unknown, ctx: HintContext = {}): string | null {
  const msg = errMessage(err).toLowerCase()
  const url = ctx.url || 'ws://<host>:8767'
  const remote = ctx.url ? `--remote ${ctx.url}` : '--remote ws://<host>:8767'

  if (/consent|tools? (are )?disabled|--no-tools|not consented/.test(msg)) {
    return `Desktop tools need consent. Run: hermes-relay pair ${remote} --grant-tools`
  }
  if (/cert|spki|pin mismatch|self.signed|tls/.test(msg)) {
    return `TLS/cert-pin problem. If the relay's certificate rotated, re-pair to re-pin: hermes-relay pair ${remote}`
  }
  if (/no (stored )?(credentials|session|token)|not paired|missing (creds|credentials)/.test(msg)) {
    return `No stored session. Pair first: hermes-relay pair --remote ${url}`
  }
  if (/auth|unauthor|401|forbidden|403|token.*(expired|invalid|rejected)|pairing (failed|rejected)/.test(msg)) {
    return `Session may be expired or revoked. Re-pair: hermes-relay pair ${remote}`
  }
  if (/econnrefused|connection refused/.test(msg)) {
    return `Nothing is listening at ${url}. Is the relay running on the host?`
  }
  if (/enotfound|getaddrinfo|eai_again/.test(msg)) {
    return `Host not found. Check the URL includes the port, e.g. ws://host:8767`
  }
  if (/etimedout|timed out|timeout|ehostunreach|enetunreach/.test(msg)) {
    return `Timed out reaching the relay. Check the network/Tailscale, or try another endpoint with --remote.`
  }
  if (ctx.command && /unknown .* sub-?verb|usage/.test(msg)) {
    return `Run \`hermes-relay ${ctx.command} --help\` to see the available sub-commands.`
  }
  return null
}

/** Render "error: <msg>" plus an indented hint line when one applies. */
export function formatError(err: unknown, ctx: HintContext, t: Theme): string {
  const lines = [t.err(`error: ${errMessage(err)}`)]
  const hint = suggestedFix(err, ctx)
  if (hint) {
    lines.push(t.muted(`  ${hint}`))
  }
  return lines.join('\n')
}
