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
import type { ToolHandler } from './router.js'

/** The canonical desktop tool name → handler map. Keys must match the
 * wire schema names in `plugin/tools/desktop_tool.py`. */
export const DESKTOP_HANDLERS: Record<string, ToolHandler> = {
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

/** Stable list of advertised tool names — what the heartbeat claims to
 * service. Matches `Object.keys(DESKTOP_HANDLERS)` but exported separately
 * so tests can assert on it without depending on object-iteration order. */
export const DESKTOP_ADVERTISED_TOOLS: readonly string[] = Object.freeze(
  Object.keys(DESKTOP_HANDLERS)
)

/** Short summary line used by chat.ts / shell.ts when announcing to the
 * user that tools are wired. Centralizing the count avoids the "9 handlers"
 * literal getting out of sync with the actual map. */
export function describeAdvertisedTools(): string {
  return `${DESKTOP_ADVERTISED_TOOLS.length} desktop tools advertised`
}
