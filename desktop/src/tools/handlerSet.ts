// Single source of truth for the desktop tool handler map.
//
// chat.ts, shell.ts, and daemon.ts each construct a DesktopToolRouter, and
// before this file existed they each maintained their own copy of the
// handler list. With ~14+ handlers in flight that duplication was a
// drift trap — adding a new tool meant editing three nearly-identical
// blocks. This module owns the canonical map; call sites just import it.
//
// The router also exposes `advertisedTools` (default = Object.keys(handlers))
// so this file's order naturally drives what the heartbeat advertises.

import { clipboardReadHandler, clipboardWriteHandler } from './handlers/clipboard.js'
import { openInEditorHandler } from './handlers/editor.js'
import { readFileHandler, writeFileHandler, patchHandler } from './handlers/fs.js'
import {
  jobCancelHandler,
  jobListHandler,
  jobLogsHandler,
  jobStartHandler,
  jobStatusHandler
} from './handlers/jobs.js'
import { powershellHandler } from './handlers/powershell.js'
import {
  adbDevicesHandler,
  adbInstallHandler,
  adbLogcatHandler,
  adbPullHandler,
  adbPushHandler,
  adbShellHandler
} from './handlers/adb.js'
import { usbDevicesHandler, usbRunHandler } from './handlers/usb.js'
import {
  findPidByPortHandler,
  killProcessHandler,
  listProcessesHandler,
  spawnDetachedHandler
} from './handlers/process.js'
import { screenshotHandler } from './handlers/screenshot.js'
import { searchFilesHandler } from './handlers/search.js'
import { terminalHandler } from './handlers/terminal.js'
import {
  checksumHandler,
  copyDirectoryHandler,
  unzipHandler,
  zipHandler
} from './handlers/transfer.js'
import {
  computerActionHandler,
  computerCancelHandler,
  computerGrantRequestHandler,
  computerScreenshotHandler,
  computerStatusHandler
} from './handlers/computer.js'
import type { ToolHandler } from './router.js'
import { readDesktopUseSettingsSync } from '../lib/desktopUseSettings.js'
import { approveComputerGrant } from './computerActionApproval.js'
import {
  DEFAULT_CAPABILITY_POLICIES,
  type CapabilityAccessMode,
  type CapabilityPolicies,
  type HostCapability
} from '../lib/hostAccessPolicy.js'

/** Experimental computer-use tools are registered in the local handler map
 * but heartbeat-advertised only when persistently enabled or explicitly
 * overridden after normal desktop-tool consent. Host input still fails closed
 * unless a task-scoped grant exists and was approved locally. */
export const DESKTOP_COMPUTER_USE_TOOLS: readonly string[] = Object.freeze([
  'desktop_computer_status',
  'desktop_computer_screenshot',
  'desktop_computer_action',
  'desktop_computer_grant_request',
  'desktop_computer_cancel'
])

const BASE_DESKTOP_HANDLERS: Record<string, ToolHandler> = {
  // ── Filesystem ─────────────────────────────────────────────────────────
  desktop_read_file: readFileHandler,
  desktop_write_file: writeFileHandler,
  desktop_patch: patchHandler,
  desktop_search_files: searchFilesHandler,

  // ── Shell ──────────────────────────────────────────────────────────────
  desktop_terminal: terminalHandler,
  desktop_powershell: powershellHandler,

  // ── Process management ─────────────────────────────────────────────────
  desktop_spawn_detached: spawnDetachedHandler,
  desktop_list_processes: listProcessesHandler,
  desktop_kill_process: killProcessHandler,
  desktop_find_pid_by_port: findPidByPortHandler,

  // ── Job API (long-running tasks with persistent logs) ─────────────────
  desktop_job_start: jobStartHandler,
  desktop_job_status: jobStatusHandler,
  desktop_job_logs: jobLogsHandler,
  desktop_job_cancel: jobCancelHandler,
  desktop_job_list: jobListHandler,

  // ── File transfer ──────────────────────────────────────────────────────
  desktop_copy_directory: copyDirectoryHandler,
  desktop_zip: zipHandler,
  desktop_unzip: unzipHandler,
  desktop_checksum: checksumHandler,

  // ── User-context bridges ───────────────────────────────────────────────
  desktop_clipboard_read: clipboardReadHandler,
  desktop_clipboard_write: clipboardWriteHandler,
  desktop_screenshot: screenshotHandler,
  desktop_open_in_editor: openInEditorHandler,
  desktop_usb_devices: usbDevicesHandler,
  desktop_usb_run: usbRunHandler,
  desktop_adb_devices: adbDevicesHandler,
  desktop_adb_shell: adbShellHandler,
  desktop_adb_push: adbPushHandler,
  desktop_adb_pull: adbPullHandler,
  desktop_adb_install: adbInstallHandler,
  desktop_adb_logcat: adbLogcatHandler
}

export const RAW_EXECUTION_TOOLS = Object.freeze([
  'desktop_terminal',
  'desktop_powershell',
  'desktop_spawn_detached',
  'desktop_job_start'
])
export const FILE_TOOLS = Object.freeze([
  'desktop_read_file', 'desktop_write_file', 'desktop_patch', 'desktop_search_files',
  'desktop_copy_directory', 'desktop_zip', 'desktop_unzip', 'desktop_checksum', 'desktop_open_in_editor'
])
export const SCREEN_INPUT_TOOLS = Object.freeze([
  'desktop_clipboard_read', 'desktop_clipboard_write', 'desktop_screenshot', ...DESKTOP_COMPUTER_USE_TOOLS
])
export const RAW_USB_TOOLS = Object.freeze([
  'desktop_usb_devices',
  'desktop_usb_run'
])
export const ADB_TOOLS = Object.freeze([
  'desktop_adb_devices',
  'desktop_adb_shell',
  'desktop_adb_push',
  'desktop_adb_pull',
  'desktop_adb_install',
  'desktop_adb_logcat'
])
export const USB_TOOLS = Object.freeze([...RAW_USB_TOOLS, ...ADB_TOOLS])

const COMPUTER_USE_HANDLERS: Record<string, ToolHandler> = {
  desktop_computer_status: computerStatusHandler,
  desktop_computer_screenshot: computerScreenshotHandler,
  desktop_computer_action: computerActionHandler,
  desktop_computer_grant_request: computerGrantRequestHandler,
  desktop_computer_cancel: computerCancelHandler
}

/** The canonical desktop tool name → handler map. Keys must match the
 * wire schema names in `plugin/tools/desktop_tool.py`. */
export const DESKTOP_HANDLERS: Record<string, ToolHandler> = {
  ...BASE_DESKTOP_HANDLERS,
  ...COMPUTER_USE_HANDLERS
}

export interface DesktopAdvertiseOptions {
  computerUse?: boolean
  structuredOnly?: boolean
  usb?: boolean
  adb?: boolean
  capabilities?: CapabilityPolicies
}

function envEnabled(value: string | undefined): boolean {
  if (!value) {
    return false
  }
  return ['1', 'true', 'yes', 'on'].includes(value.trim().toLowerCase())
}

export function shouldAdvertiseComputerUse(
  flags: Record<string, string | true> = {},
  env: NodeJS.ProcessEnv = process.env,
  persistedEnabled = readDesktopUseSettingsSync().computer_use_enabled
): boolean {
  if (flags['no-computer-use'] === true) {
    return false
  }
  if (flags['experimental-computer-use'] === true || flags['allow-computer-use'] === true) {
    return true
  }
  return envEnabled(env.HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE) ||
    envEnabled(env.HERMES_RELAY_COMPUTER_USE) ||
    persistedEnabled
}

export function desktopHandlers(
  opts: DesktopAdvertiseOptions = {}
): Record<string, ToolHandler> {
  const policies: CapabilityPolicies = opts.capabilities ?? {
    ...DEFAULT_CAPABILITY_POLICIES,
    commands: opts.structuredOnly === true ? 'disabled' : 'allow',
    files: 'allow',
    screen_input: opts.computerUse === true ? 'ask' : 'disabled',
    usb: opts.usb === true ? 'allow' : 'disabled'
  }
  const handlers = opts.computerUse === true && policies.screen_input !== 'disabled'
    ? DESKTOP_HANDLERS
    : BASE_DESKTOP_HANDLERS
  const raw = new Set(RAW_EXECUTION_TOOLS)
  const files = new Set(FILE_TOOLS)
  const screenInput = new Set(SCREEN_INPUT_TOOLS)
  const rawUsb = new Set(RAW_USB_TOOLS)
  const adb = new Set(ADB_TOOLS)
  const capabilityFor = (name: string): HostCapability | null =>
    raw.has(name) ? 'commands'
      : files.has(name) ? 'files'
        : screenInput.has(name) ? 'screen_input'
          : rawUsb.has(name) || adb.has(name) ? 'usb'
            : null
  const guarded = Object.entries(handlers).flatMap(([name, handler]) => {
    const capability = capabilityFor(name)
    const mode = capability ? policies[capability] : 'allow'
    if (mode === 'disabled') return []
    if (adb.has(name) && opts.adb !== true) return []
    if (mode !== 'ask' || name.startsWith('desktop_computer_') || name === 'desktop_patch') {
      return [[name, handler] as const]
    }
    return [[name, guardCapabilityHandler(name, capability!, mode, handler)] as const]
  })
  return Object.fromEntries(guarded)
}

function guardCapabilityHandler(
  tool: string,
  capability: HostCapability,
  mode: CapabilityAccessMode,
  handler: ToolHandler
): ToolHandler {
  if (mode !== 'ask') return handler
  return async (args, ctx) => {
    const approval = await approveComputerGrant({
      mode: `${capability}.${tool.replace(/^desktop_/, '')}`,
      durationSeconds: 120,
      reason: typeof args.reason === 'string' && args.reason.trim()
        ? args.reason.trim()
        : `Run ${tool.replaceAll('_', ' ')}`,
      scope: { capability, tool },
      interactive: ctx.interactive
    })
    if (!approval.approved) throw new Error(approval.reason || `${capability} request rejected locally`)
    return handler(args, ctx)
  }
}

/** Stable list of advertised tool names — what the heartbeat claims to
 * service. Computer-use tools are separately enabled so the regular desktop
 * CLI/daemon surface stays primary and backward-compatible. */
export function advertisedDesktopTools(
  opts: DesktopAdvertiseOptions = {}
): readonly string[] {
  return Object.freeze(Object.keys(desktopHandlers(opts)))
}

export const DESKTOP_ADVERTISED_TOOLS: readonly string[] = advertisedDesktopTools({
  computerUse: shouldAdvertiseComputerUse()
})

/** Short summary line used by chat.ts / shell.ts when announcing to the
 * user that tools are wired. Centralizing the count avoids the "9 handlers"
 * literal getting out of sync with the actual map. */
export function describeAdvertisedTools(opts: DesktopAdvertiseOptions = {}): string {
  return `${advertisedDesktopTools(opts).length} desktop tools advertised`
}
