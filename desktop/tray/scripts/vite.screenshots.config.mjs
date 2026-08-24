import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const replacements = new Map([
  ["server_version: '1.6.3'", "server_version: '1.9.0'"],
  ["ui_version: '0.4.0-alpha.7'", "ui_version: '0.4.0-beta.4'"],
  ["cli_version: '0.4.0-alpha.4'", "cli_version: '0.4.0-beta.4'"],
  ["void getCurrentWindow().isVisible().then(visible => { if (visible) start() })", "start()"],
  [
    "selected: 'legacy', effective: 'legacy', available: false, state: 'not_installed',\n    foreground_escalation_enabled: false, message: 'CUA Driver is not installed. Legacy input remains active.'",
    "selected: 'cua', effective: 'cua', available: true, state: 'ready', version: '0.21.0', cursor_enabled: true, active_sessions: 0, active_backend: 'idle',\n    foreground_escalation_enabled: false, message: 'CUA Driver 0.21.0 is compatible and healthy.'"
  ],
  [
    "return { current: '0.4.0-alpha.3', up_to_date: true, ahead_of_latest: true, latest_version: '0.4.0-alpha.2', installed: false, needs_restart: false } as T",
    "return { current: '0.4.0-beta.4', up_to_date: true, ahead_of_latest: false, latest_version: '0.4.0-beta.4', installed: false, needs_restart: false } as T"
  ],
  [
    "return { installed: false, stale_path_shim: false, compatible: false, compatibility_reason: 'CUA Driver is not installed', supported_range: { minimum: '0.20.0', maximum_exclusive: null } } as T",
    "return { installed: true, stale_path_shim: false, current_version: '0.21.0', compatible: true, compatibility_reason: 'Compatible with Hermes-Relay CLI UI', supported_range: { minimum: '0.20.0', maximum_exclusive: null }, update: { latest_version: '0.21.0', update_available: false, compatible: true } } as T"
  ],
  [
    "return { state: 'degraded', checkedAt: new Date().toISOString(), overall: 'degraded', reason: 'UI Automation desktop enumeration exceeded 2000ms.', temporaryWindowsCompatibility: true } as T",
    "return { state: 'healthy', checkedAt: new Date().toISOString(), overall: 'healthy', reason: 'Accessibility and window discovery checks passed.', temporaryWindowsCompatibility: true } as T"
  ]
])

function publicScreenshotFixtures() {
  return {
    name: 'public-screenshot-fixtures',
    enforce: 'pre',
    transform(source, id) {
      if (!id.endsWith('/ui/App.tsx') && !id.endsWith('\\ui\\App.tsx')) return null
      let transformed = source
      for (const [from, to] of replacements) transformed = transformed.replaceAll(from, to)
      transformed = transformed.replace(
        /selected: 'legacy', effective: 'legacy', available: false, state: 'not_installed',\s+foreground_escalation_enabled: false, message: 'CUA Driver is not installed\. Legacy input remains active\.'/,
        "selected: 'cua', effective: 'cua', available: true, state: 'ready', version: '0.21.0', cursor_enabled: true, active_sessions: 0, active_backend: 'idle',\n    foreground_escalation_enabled: false, message: 'CUA Driver 0.21.0 is compatible and healthy.'"
      )
      return { code: transformed, map: null }
    }
  }
}

export default defineConfig({
  plugins: [publicScreenshotFixtures(), react()],
  clearScreen: false,
  server: { host: '127.0.0.1', port: 1421, strictPort: true },
  envPrefix: ['VITE_', 'TAURI_'],
  build: { target: ['es2021', 'chrome100', 'safari13'] }
})
