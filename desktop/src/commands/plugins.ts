import type { ParsedArgs } from '../cli.js'
import {
  getSurfacePlugin,
  listSurfacePluginStatuses,
  resolvePluginPlan,
  runPluginPlan,
  surfacePluginStatus,
  type SurfacePluginStatus
} from '../surfacePlugins.js'

function renderStatus(status: SurfacePluginStatus): string {
  const plugin = status.descriptor
  const lines = [
    `${plugin.id} - ${plugin.name}`,
    `  state:    ${status.installed ? 'installed' : status.available ? 'fallback available' : 'missing'}`,
    `  package:  ${plugin.packageName}`,
    `  command:  ${status.installed ? status.command : (status.launch?.display ?? status.command)}`,
    `  source:   ${plugin.sourceUrl}`,
    `  setup:    ${status.setupHint}`
  ]
  if (status.version) {
    lines.push(`  version:  ${status.version}`)
  }
  if (status.installer) {
    lines.push(`  install:  ${status.installer.display}`)
  }
  if (status.fallback && !status.installed) {
    lines.push(`  fallback: ${status.fallback.display}`)
  }
  lines.push(`  tabs:     ${plugin.tabs.join(', ')}`)
  lines.push(`  actions:  ${plugin.sessionActions.map((action) => action.label).join(', ')}`)
  return lines.join('\n')
}

function resolvePluginOrPrint(id: string): ReturnType<typeof getSurfacePlugin> {
  const plugin = getSurfacePlugin(id)
  if (!plugin) {
    process.stderr.write(`plugins: unknown plugin "${id}". Try: herm\n`)
  }
  return plugin
}

export async function pluginsCommand(args: ParsedArgs): Promise<number> {
  const sub = args.positional[0] ?? 'list'
  const id = args.positional[1] ?? 'herm'
  const wantJson = !!args.flags.json

  if (sub === 'list' || sub === 'status') {
    const statuses =
      sub === 'status' && args.positional[1]
        ? (() => {
            const plugin = resolvePluginOrPrint(id)
            return plugin ? [surfacePluginStatus(plugin)] : null
          })()
        : listSurfacePluginStatuses()
    if (!statuses) {
      return 2
    }
    if (wantJson) {
      process.stdout.write(JSON.stringify(statuses, null, 2) + '\n')
      return 0
    }
    process.stdout.write('Desktop surface plugins:\n\n')
    process.stdout.write(statuses.map(renderStatus).join('\n\n') + '\n')
    return 0
  }

  if (sub === 'install' || sub === 'update' || sub === 'launch' || sub === 'resume') {
    const plugin = resolvePluginOrPrint(id)
    if (!plugin) {
      return 2
    }
    const plan = resolvePluginPlan(plugin, sub)
    if (!plan) {
      const message =
        sub === 'install' || sub === 'update'
          ? `plugins: install Bun or npm first, then rerun \`hermes-relay plugins ${sub} ${plugin.id}\`.\n`
          : `plugins: ${plugin.name} is not installed and no bunx/npx fallback is available.\n`
      if (wantJson) {
        process.stdout.write(
          JSON.stringify({ plugin: plugin.id, action: sub, ok: false, error: message.trim() }, null, 2) +
            '\n'
        )
      } else {
        process.stderr.write(message)
      }
      return 1
    }
    if (wantJson && (sub === 'launch' || sub === 'resume')) {
      process.stdout.write(
        JSON.stringify({ plugin: plugin.id, action: sub, ok: true, command: plan.display }, null, 2) +
          '\n'
      )
      return 0
    }
    if (!wantJson) {
      process.stderr.write(`plugins: ${sub} ${plugin.name} via ${plan.display}\n`)
    }
    const code = await runPluginPlan(plan)
    if (wantJson) {
      process.stdout.write(
        JSON.stringify({ plugin: plugin.id, action: sub, ok: code === 0, code, command: plan.display }, null, 2) +
          '\n'
      )
    }
    return code
  }

  process.stderr.write('unknown plugins sub-verb. Try: list | status [id] | install <id> | update <id> | launch <id> | resume <id>\n')
  return 2
}
