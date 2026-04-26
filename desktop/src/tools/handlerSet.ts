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

/** Experimental computer-use tools are registered in the local handler map
 * but not heartbeat-advertised unless explicitly enabled. That lets the
 * Python plugin publish schemas while the relay still fails closed for
 * clients that have not opted into the observe-first surface. */
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
  desktop_open_in_editor: openInEditorHandler
}

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
}

function envEnablesComputerUse(): boolean {
  const raw = process.env.HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE
  if (!raw) {
    return false
  }
  return !['0', 'false', 'no', 'off'].includes(raw.trim().toLowerCase())
}

export function shouldAdvertiseComputerUse(
  flags?: Record<string, string | true>
): boolean {
  return flags?.['experimental-computer-use'] === true || envEnablesComputerUse()
}

/** Stable list of advertised tool names — what the heartbeat claims to
 * service. Computer-use tools are omitted unless an explicit experimental
 * opt-in is present. */
export function advertisedDesktopTools(
  opts: DesktopAdvertiseOptions = {}
): readonly string[] {
  if (opts.computerUse) {
    return Object.freeze(Object.keys(DESKTOP_HANDLERS))
  }
  const experimental = new Set(DESKTOP_COMPUTER_USE_TOOLS)
  return Object.freeze(Object.keys(DESKTOP_HANDLERS).filter(name => !experimental.has(name)))
}

export const DESKTOP_ADVERTISED_TOOLS: readonly string[] = advertisedDesktopTools()

/** Short summary line used by chat.ts / shell.ts when announcing to the
 * user that tools are wired. Centralizing the count avoids the "9 handlers"
 * literal getting out of sync with the actual map. */
export function describeAdvertisedTools(opts: DesktopAdvertiseOptions = {}): string {
  return `${advertisedDesktopTools(opts).length} desktop tools advertised`
}
