// hermes-relay CLI — argv parser + subcommand dispatcher.
// Deliberately tiny: mirrors the TUI entry's stance ("anything more elaborate
// lands in hermes_cli/main.py — the proper home for a full CLI") but with
// subcommands because a thin client has actual verbs (pair, status, tools).

import { chatCommand } from './commands/chat.js'
import { daemonCommand } from './commands/daemon.js'
import { devicesCommand } from './commands/devices.js'
import { doctorCommand } from './commands/doctor.js'
import { pairCommand } from './commands/pair.js'
import { shellCommand } from './commands/shell.js'
import { statusCommand } from './commands/status.js'
import { toolsCommand } from './commands/tools.js'
import { VERSION } from './version.js'

/** Read the embedded build-time version. src/version.ts is regenerated
 * from package.json by `npm run gen:version` before every build, which
 * makes the version available to Bun-compiled binaries where reading
 * package.json at runtime via __dirname fails (the binary has no real
 * filesystem layout). */
function readVersion(): string {
  return VERSION
}

export interface ParsedArgs {
  command: string
  flags: Record<string, string | true>
  positional: string[]
}

const SHORT_FLAGS: Record<string, string> = {
  h: 'help',
  v: 'version',
  q: 'quiet'
}

const BOOLEAN_FLAGS = new Set([
  'help',
  'version',
  'quiet',
  'verbose',
  'json',
  'no-color',
  'non-interactive',
  'reveal-tokens',
  'raw',
  'no-tools',
  'log-human',
  'log-json',
  'allow-tools'
])

function parseArgs(argv: string[]): ParsedArgs {
  const args = argv.slice(2)
  const positional: string[] = []
  const flags: Record<string, string | true> = {}
  let command = ''

  for (let i = 0; i < args.length; i++) {
    const a = args[i]!

    if (a === '--') {
      // Rest is positional.
      for (let j = i + 1; j < args.length; j++) {
        positional.push(args[j]!)
      }
      break
    }

    if (a.startsWith('--')) {
      const eq = a.indexOf('=')
      if (eq >= 0) {
        flags[a.slice(2, eq)] = a.slice(eq + 1)
        continue
      }
      const name = a.slice(2)
      if (BOOLEAN_FLAGS.has(name)) {
        flags[name] = true
        continue
      }
      // Value-taking flag: consume next arg if present and not a flag.
      const next = args[i + 1]
      if (next !== undefined && !next.startsWith('-')) {
        flags[name] = next
        i++
      } else {
        flags[name] = true
      }
      continue
    }

    if (a.startsWith('-') && a.length === 2) {
      const k = a[1]!
      flags[SHORT_FLAGS[k] ?? k] = true
      continue
    }

    if (!command) {
      command = a
    } else {
      positional.push(a)
    }
  }

  return { command, flags, positional }
}

const KNOWN_COMMANDS = new Set([
  'chat',
  'daemon',
  'devices',
  'doctor',
  'pair',
  'shell',
  'status',
  'tools',
  'help'
])

const HELP = `hermes-relay — thin-client CLI for a remote Hermes agent over WSS

Usage:
  hermes-relay [shell]             Pipe the full Hermes CLI over a PTY (default — interactive)
  hermes-relay chat [<prompt>]     Structured-event chat (REPL or one-shot, scriptable)
  hermes-relay "<prompt>"          One-shot structured chat (shortcut for chat "...")
  hermes-relay pair [CODE]         Pair with the relay and store a session token
  hermes-relay status              Show stored sessions + grants + TTL
  hermes-relay tools               List tools available on the server
  hermes-relay devices             List / revoke / extend server-side paired devices
  hermes-relay daemon              Run headless — expose desktop tools even when no shell is open
  hermes-relay doctor              Diagnostic report: version, paths, sessions, daemon status
  hermes-relay help                Show this help
  hermes-relay --version           Print version and exit

Flags:
  --remote <url>         Relay WSS URL                 (env: HERMES_RELAY_URL)
  --code <code>          Pairing code (6 chars)        (env: HERMES_RELAY_CODE)
  --token <token>        Session token (skips pairing) (env: HERMES_RELAY_TOKEN)
  --pair-qr <payload>    Full QR payload (multi-endpoint pairing, ADR 24;
                         probes endpoints and picks highest-priority reachable)
                                                       (env: HERMES_RELAY_PAIR_QR)
  --session <id>         chat: resume session; shell: tmux session name
  --exec <cmd>           shell: command to exec inside tmux (default: hermes)
  --raw                  shell: skip auto-exec; drop into bare tmux/bash
  --no-tools             chat/shell: disable local tool handlers (fs, exec, search)
  --log-human            daemon: human-readable log lines (default: auto on TTY)
  --log-json             daemon: force JSON-line logs even on a TTY
  --allow-tools          daemon: skip stored-consent gate (use only with --token; implies trust)
  --json                 chat: emit events as JSON lines (scripting)
  --verbose              Include thinking/reasoning + transport stderr
  --quiet, -q            Suppress status lines and tool decorations
  --no-color             Disable ANSI colors           (env: NO_COLOR)
  --non-interactive      Never prompt; fail if creds missing
  --help, -h             Show this help
  --version, -v          Print version

Examples:
  # First time: pair with the relay (one-time code from \`hermes-pair\` on the server)
  hermes-relay pair --remote ws://172.16.24.250:8767
  # ...prompts for code, stores a token in ~/.hermes/remote-sessions.json

  # REPL — reuses the stored token
  hermes-relay --remote ws://172.16.24.250:8767

  # One-shot
  hermes-relay "what files are in ~/.hermes?" --remote ws://172.16.24.250:8767

  # Pipe JSON events for scripting
  hermes-relay --json "summarize the last commit" | jq -c '.type'

  # Inspect what tools the server will give the agent
  hermes-relay tools --verbose

  # Run the tool router headless so the agent can reach you without an open shell
  hermes-relay daemon --remote ws://172.16.24.250:8767
  # ...writes JSON-line lifecycle events to stderr; redirect or pipe to jq

Config files:
  ~/.hermes/remote-sessions.json   session tokens (mode 0600)
`

export async function main(argv = process.argv): Promise<number> {
  const args = parseArgs(argv)

  if (args.flags.version) {
    process.stdout.write(`hermes-relay ${readVersion()}\n`)
    return 0
  }

  if (args.flags.help || args.command === 'help') {
    process.stdout.write(HELP)
    return 0
  }

  // Bare `hermes-relay` (no verb, no positional) → drop into the PTY shell,
  // which gives the user the full local-Hermes experience (banner, Victor,
  // skin, all of it verbatim). That matches what a hermes user expects.
  //
  // `hermes-relay "prompt"` (positional but no verb) still falls through to
  // structured chat — one-shot scripting use doesn't want a PTY.
  if (!args.command) {
    if (args.positional.length === 0) {
      return shellCommand(args)
    }
    return chatCommand(args)
  }

  if (!KNOWN_COMMANDS.has(args.command)) {
    args.positional.unshift(args.command)
    args.command = 'chat'
    return chatCommand(args)
  }

  switch (args.command) {
    case 'chat':
      return chatCommand(args)
    case 'daemon':
      return daemonCommand(args)
    case 'devices':
      return devicesCommand(args)
    case 'doctor':
      return doctorCommand(args)
    case 'pair':
      return pairCommand(args)
    case 'shell':
      return shellCommand(args)
    case 'status':
      return statusCommand(args)
    case 'tools':
      return toolsCommand(args)
    default:
      process.stderr.write(`unknown command: ${args.command}\n`)
      process.stderr.write(HELP)
      return 2
  }
}

// Auto-invoke when this module is the process entry point — covers
// `tsx src/cli.ts`, `npm run dev`, `node dist/cli.js`, and `bun --compile`
// binaries. `import.meta.main` is truthy only in the entry module, which
// handles all four paths uniformly:
//   - Bun --compile binary: the compiled entry sets main=true.
//   - bin/hermes-relay.js shim: cli.js is imported, not the entry, so
//     main=false here and the shim's explicit `.main()` call is the only
//     invocation (no double-fire).
//   - tsx / node direct: cli is the entry, main=true.
//   - Tests importing { main }: cli is not the entry, main=false.
// The older `fileURLToPath(import.meta.url) === process.argv[1]` check
// failed specifically in Bun compiled binaries because their entry module
// has a synthetic URL that doesn't match the .exe path — the binary would
// exit 0 silently. See CHANGELOG alpha.4.
if (import.meta.main) {
  main()
    .then((code) => process.exit(code ?? 0))
    .catch((err) => {
      const msg = err instanceof Error ? err.message : String(err)
      process.stderr.write(`hermes-relay: ${msg}\n`)
      if (process.env.HERMES_DEBUG) {
        // eslint-disable-next-line no-console
        console.error(err)
      }
      process.exit(1)
    })
}
