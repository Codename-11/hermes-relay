import { spawn, spawnSync } from 'node:child_process'

export interface SurfacePluginCommand {
  id: string
  label: string
  description: string
}

export interface SurfacePluginDescriptor {
  id: string
  name: string
  description: string
  sourceUrl: string
  packageName: string
  binaryName: string
  tabs: string[]
  commands: SurfacePluginCommand[]
  keybindings: SurfacePluginCommand[]
  statusCards: SurfacePluginCommand[]
  sessionActions: SurfacePluginCommand[]
}

export interface PluginCommandPlan {
  program: string
  args: string[]
  display: string
  mode: string
}

export interface SurfacePluginStatus {
  descriptor: SurfacePluginDescriptor
  installed: boolean
  available: boolean
  version: string | null
  command: string
  installer: PluginCommandPlan | null
  update: PluginCommandPlan | null
  fallback: PluginCommandPlan | null
  launch: PluginCommandPlan | null
  resume: PluginCommandPlan | null
  setupHint: string
}

export const BUILTIN_SURFACE_PLUGINS: SurfacePluginDescriptor[] = [
  {
    id: 'herm',
    name: 'Herm',
    description: 'OpenTUI dashboard for Hermes Agent, packaged as herm-tui.',
    sourceUrl: 'https://github.com/liftaris/herm',
    packageName: 'herm-tui',
    binaryName: 'herm',
    tabs: [
      'chat',
      'sessions',
      'context',
      'agents',
      'analytics',
      'skills',
      'cron',
      'toolsets',
      'config',
      'env',
      'memory',
      'kanban'
    ],
    commands: [
      {
        id: 'install',
        label: 'Install',
        description: 'Install herm-tui globally with Bun when available, otherwise npm.'
      },
      {
        id: 'update',
        label: 'Update',
        description: 'Re-run the package manager install to refresh herm-tui.'
      },
      {
        id: 'launch',
        label: 'Launch',
        description: 'Open a fresh Herm dashboard session.'
      },
      {
        id: 'resume',
        label: 'Resume',
        description: 'Open Herm with -c to continue the last dashboard session.'
      }
    ],
    keybindings: [
      {
        id: 'palette',
        label: 'Ctrl+K',
        description: 'Open the Herm command palette.'
      },
      {
        id: 'keys',
        label: '/keys',
        description: 'Show or edit Herm keybindings.'
      }
    ],
    statusCards: [
      {
        id: 'install',
        label: 'Install state',
        description: 'Reports whether the herm binary is on PATH.'
      },
      {
        id: 'runtime',
        label: 'Runtime',
        description: 'Reports Bun/npm fallback availability.'
      },
      {
        id: 'source',
        label: 'Source',
        description: 'Links the built-in plugin to liftaris/herm.'
      }
    ],
    sessionActions: [
      {
        id: 'fresh',
        label: 'Fresh session',
        description: 'Run herm without resume flags.'
      },
      {
        id: 'resume',
        label: 'Resume last',
        description: 'Run herm -c.'
      }
    ]
  }
]

function quoteArg(arg: string): string {
  if (/^[A-Za-z0-9_./:@=-]+$/.test(arg)) {
    return arg
  }
  return `"${arg.replaceAll('"', '\\"')}"`
}

function makePlan(program: string, args: string[], mode: string): PluginCommandPlan {
  return {
    program,
    args,
    mode,
    display: [program, ...args].map(quoteArg).join(' ')
  }
}

function commandOk(program: string, args: string[] = ['--version']): boolean {
  const result = spawnSync(program, args, {
    stdio: 'ignore',
    shell: process.platform === 'win32'
  })
  return !result.error && result.status === 0
}

function commandOutput(program: string, args: string[] = ['--version']): string | null {
  const result = spawnSync(program, args, {
    encoding: 'utf8',
    shell: process.platform === 'win32'
  })
  if (result.error || result.status !== 0) {
    return null
  }
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find(Boolean)
  return output ?? null
}

function installPlan(plugin: SurfacePluginDescriptor): PluginCommandPlan | null {
  if (commandOk('bun')) {
    return makePlan('bun', ['add', '-g', plugin.packageName], 'bun')
  }
  if (commandOk('npm')) {
    return makePlan('npm', ['install', '-g', plugin.packageName], 'npm')
  }
  return null
}

function fallbackPlan(plugin: SurfacePluginDescriptor, resume = false): PluginCommandPlan | null {
  const args = resume ? [plugin.packageName, '-c'] : [plugin.packageName]
  if (commandOk('bunx')) {
    return makePlan('bunx', args, 'bunx')
  }
  if (commandOk('npx')) {
    return makePlan('npx', ['--yes', ...args], 'npx')
  }
  return null
}

function launchPlan(plugin: SurfacePluginDescriptor, resume = false): PluginCommandPlan | null {
  const args = resume ? ['-c'] : []
  if (commandOk(plugin.binaryName)) {
    return makePlan(plugin.binaryName, args, 'installed')
  }
  return fallbackPlan(plugin, resume)
}

export function getSurfacePlugin(id: string): SurfacePluginDescriptor | null {
  return BUILTIN_SURFACE_PLUGINS.find((plugin) => plugin.id === id) ?? null
}

export function listSurfacePluginStatuses(): SurfacePluginStatus[] {
  return BUILTIN_SURFACE_PLUGINS.map(surfacePluginStatus)
}

export function surfacePluginStatus(plugin: SurfacePluginDescriptor): SurfacePluginStatus {
  const installed = commandOk(plugin.binaryName)
  const installer = installPlan(plugin)
  const launch = launchPlan(plugin, false)
  const resume = launchPlan(plugin, true)
  const fallback = fallbackPlan(plugin, false)
  const version = installed ? commandOutput(plugin.binaryName) : null
  const setupHint = installed
    ? `${plugin.binaryName} is available on PATH.`
    : installer
      ? `Install with ${installer.display}.`
      : fallback
        ? `Use fallback launch with ${fallback.display}.`
        : 'Install Bun or npm, then install herm-tui.'

  return {
    descriptor: plugin,
    installed,
    available: launch !== null,
    version,
    command: plugin.binaryName,
    installer,
    update: installer,
    fallback,
    launch,
    resume,
    setupHint
  }
}

export function resolvePluginPlan(
  plugin: SurfacePluginDescriptor,
  action: 'install' | 'update' | 'launch' | 'resume'
): PluginCommandPlan | null {
  if (action === 'install' || action === 'update') {
    return installPlan(plugin)
  }
  return launchPlan(plugin, action === 'resume')
}

export async function runPluginPlan(plan: PluginCommandPlan): Promise<number> {
  return new Promise((resolve, reject) => {
    const child = spawn(plan.program, plan.args, {
      stdio: 'inherit',
      shell: process.platform === 'win32'
    })
    child.on('error', reject)
    child.on('close', (code) => resolve(code ?? 0))
  })
}
