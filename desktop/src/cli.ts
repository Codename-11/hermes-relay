// hermes-relay CLI — argv parser + subcommand dispatcher.
// Deliberately tiny: mirrors the TUI entry's stance ("anything more elaborate
// lands in hermes_cli/main.py — the proper home for a full CLI") but with
// subcommands because a thin client has actual verbs (pair, status, tools).

import { auditCommand } from './commands/audit.js'
import { chatCommand } from './commands/chat.js'
import { computerUseCommand } from './commands/computerUse.js'
import { daemonCommand } from './commands/daemon.js'
import { devicesCommand } from './commands/devices.js'
import { doctorCommand } from './commands/doctor.js'
import { grantsCommand } from './commands/grants.js'
import { pairCommand } from './commands/pair.js'
import { pasteCommand } from './commands/paste.js'
import { pluginsCommand } from './commands/plugins.js'
import { relayCommand } from './commands/relay.js'
import { sessionsCommand } from './commands/sessions.js'
import { shellCommand } from './commands/shell.js'
import { statusCommand } from './commands/status.js'
import { toolsCommand } from './commands/tools.js'
import { updateCommand } from './commands/update.js'
import { voiceCommand } from './commands/voice.js'
import { workspaceCommand } from './commands/workspace.js'
import { renderLogo } from './lib/logo.js'
import { theme as makeTheme } from './lib/theme.js'
import { finalizePendingUpdate } from './updater.js'
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
  'relay-chat',
  'grant-tools',
  'auto-grant-tools',
  'log-human',
  'log-json',
  'status',
  'detach',
  'allow-tools',
  'allow-computer-use',
  'experimental-computer-use',
  'no-computer-use',
  'no-voice',
  'no-open',
  'check',
  'yes',
  'new',
  'watch-editor',
  'clear'
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
  'audit',
  'chat',
  'computer-use',
  'daemon',
  'devices',
  'doctor',
  'grants',
  'paste',
  'pair',
  'relay',
  'sessions',
  'shell',
  'plugins',
  'status',
  'tools',
  'update',
  'voice',
  'workspace',
  'help'
])

const HELP = `hermes-relay — thin-client CLI for a remote Hermes agent over WSS

Usage:
  hermes-relay [shell]             Pipe the full Hermes CLI over a PTY (default — interactive)
  hermes-relay chat [<prompt>]     Structured-event chat (REPL or one-shot, scriptable)
  hermes-relay computer-use        Enable/disable desktop screenshots and task-scoped input
  hermes-relay "<prompt>"          One-shot structured chat (shortcut for chat "...")
  hermes-relay pair [CODE]         Pair with the relay and store a session token
  hermes-relay paste               Stage clipboard image for /paste in the TUI
  hermes-relay plugins             List/install/update/launch desktop surface plugins
  hermes-relay sessions            List / resume / create / kill TUI tmux sessions
  hermes-relay status              Show stored sessions + grants + TTL
  hermes-relay tools               List tools available on the server
  hermes-relay audit               Show what the agent ran on this machine (desktop tools)
  hermes-relay devices             List / revoke / extend server-side paired devices
  hermes-relay relay               Inspect the relay server (info / security / context / queue)
  hermes-relay daemon [start|stop|restart|status]   Headless tool router — 'start' runs it in the background
  hermes-relay doctor              Diagnostic report: version, paths, sessions, daemon status
  hermes-relay grants              Review pending local computer-use grants
  hermes-relay update              Check for and install the latest desktop-v* release
  hermes-relay voice               Show native Hermes voice config (STT/TTS/realtime providers)
  hermes-relay voice mode          Push-to-talk in a browser tab (proxied through this CLI)
  hermes-relay workspace           Print local workspace context (cwd, git, editor, shell) — --json for scripting
  hermes-relay logo                Print the Hermes Relay banner
  hermes-relay help                Show this help
  hermes-relay --version           Print version and exit

Flags:
  --remote <url>         Override saved active relay   (env: HERMES_RELAY_URL)
  --code <code>          Pairing code (6 chars)        (env: HERMES_RELAY_CODE)
  --token <token>        Session token (skips pairing) (env: HERMES_RELAY_TOKEN)
  --pair-qr <payload>    Full QR payload or hermes-relay://pair invite URL
                         (multi-endpoint pairing, ADR 24; probes endpoints
                         and picks highest-priority reachable)
                                                       (env: HERMES_RELAY_PAIR_QR)
  --session <id>         chat: resume session (legacy alias for --conversation);
                         shell: tmux session name (distinct — tmux, not hermes)
  --conversation <id>    chat/shell: resume a specific hermes conversation; bypasses picker
  --new                  chat/shell: force a fresh conversation; bypasses picker
  --model-once <model>   chat: use this model for the submitted turn only
  --model-provider <id>  chat: provider paired with --model-once
  --exec <cmd>           shell: command to exec inside tmux (default: hermes)
  --raw                  shell: skip auto-exec; drop into bare tmux/bash
  --watch-editor         shell/chat: poll tmux/$VSCODE and send active_editor hints every 5s
  --no-tools             chat/shell: disable local tool handlers (fs, exec, search)
  --relay-chat           chat: use Relay chat.send + typed stream.event over WSS
                         instead of the default TUI gateway session transport
  --experimental-computer-use
                         One-process override enabling experimental desktop_computer_*
                         tools. Prefer computer-use enable for the persistent setting.
                         Observe grants allow screenshots; assist/control requests need
                         local approval via a visible prompt or hermes-relay grants.
                         Input runs only while the task-scoped grant is live (15 min
                         default, 1 hour maximum) and can be canceled locally.
                         Env: HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1
  --no-computer-use      Disable computer-use advertisement for this invocation even
                         when the persistent preference or environment enables it.
  --grant-tools          pair: prompt for desktop-tool consent during pairing (TTY required;
                         lets you go straight from \`pair\` to \`daemon\` with no \`shell\` round-trip)
  --auto-grant-tools     pair: stamp tool consent without prompting — explicit non-interactive
                         opt-in (e.g. CI / provisioning scripts; implies trust in the relay)
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
  hermes-relay pair --remote ws://192.168.1.100:8767
  # ...prompts for code, stores a token in ~/.hermes/remote-sessions.json

  # REPL — reuses the tray-selected active relay or stored token
  hermes-relay

  # One-shot
  hermes-relay "what files are in ~/.hermes?"

  # Pipe JSON events for scripting
  hermes-relay --json "summarize the last commit" | jq -c '.type'

  # Inspect what tools the server will give the agent
  hermes-relay tools --verbose

  # Run the tool router headless so the agent can reach you without an open shell
  hermes-relay daemon
  # ...writes JSON-line lifecycle events to stderr; redirect or pipe to jq

  # Inspect and resume server-side tmux TUI sessions
  hermes-relay sessions list
  hermes-relay sessions resume default
  hermes-relay plugins install herm
  hermes-relay plugins launch herm

  # Two-command bring-up: pair with consent, then run headless. No \`shell\` round-trip.
  hermes-relay pair   --remote ws://192.168.1.100:8767 --grant-tools
  hermes-relay daemon

Config files:
  ~/.hermes/remote-sessions.json   session tokens (mode 0600)
  ~/.hermes/desktop-control.json   tray-selected active relay
  ~/.hermes/desktop-sessions.json  active TUI tmux session per relay
`

export async function main(argv = process.argv): Promise<number> {
  // Windows cooperative self-update swap: if a previous `update` run staged
  // a <target>.new.exe next to the running binary, rename it into place
  // before we dispatch. No-op on POSIX and when running via node/tsx.
  await finalizePendingUpdate()

  const args = parseArgs(argv)

  if (args.flags.version) {
    process.stdout.write(`hermes-relay ${readVersion()}\n`)
    return 0
  }

  const noColor = !!args.flags['no-color']

  // Global help only when there is no command (bare `--help` / `help`). When a
  // command is present, `--help` falls through to it so each subcommand can
  // print its own usage (e.g. `hermes-relay devices --help`).
  if ((args.flags.help && !args.command) || args.command === 'help') {
    process.stdout.write(renderLogo({ theme: makeTheme({ noColor }), subtitle: false }) + '\n' + HELP)
    return 0
  }

  // Explicit on-demand logo (handy for screenshots / docs).
  if (args.command === 'logo' || args.command === 'banner') {
    process.stdout.write(renderLogo({ theme: makeTheme({ noColor }) }))
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
    case 'audit':
      return auditCommand(args)
    case 'chat':
      return chatCommand(args)
    case 'computer-use':
      return computerUseCommand(args)
    case 'daemon':
      return daemonCommand(args)
    case 'devices':
      return devicesCommand(args)
    case 'doctor':
      return doctorCommand(args)
    case 'grants':
      return grantsCommand(args)
    case 'pair':
      return pairCommand(args)
    case 'paste':
      return pasteCommand(args)
    case 'plugins':
      return pluginsCommand(args)
    case 'relay':
      return relayCommand(args)
    case 'sessions':
      return sessionsCommand(args)
    case 'shell':
      return shellCommand(args)
    case 'status':
      return statusCommand(args)
    case 'tools':
      return toolsCommand(args)
    case 'update':
      return updateCommand(args)
    case 'voice':
      return voiceCommand(args)
    case 'workspace':
      return workspaceCommand(args)
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
