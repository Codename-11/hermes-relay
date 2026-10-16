import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Browser-only, sanitized fixtures for both integrated activity surfaces.
const activity = [
  { tool: 'desktop_computer_status' },
  { tool: 'desktop_computer_grant_request' },
  { tool: 'desktop_computer_cancel' },
  { tool: 'desktop_computer_screenshot', backend: 'cua_driver' },
  { tool: 'desktop_computer_action', backend: 'cua', dispatch: 'background', action: 'click_element', verification: 'snapshot_captured' },
  { tool: 'desktop_computer_screenshot', backend: 'system_capture' },
  { tool: 'desktop_computer_action', backend: 'legacy_compat', dispatch: 'foreground_compatibility', action: 'left_click' },
  { tool: 'desktop_computer_action', backend: 'future_backend' }
].map((entry, index) => ({ ts: Date.UTC(2026, 9, 8, 12, index), ok: true, request_id: `fixture-${index}`, ...entry }))

export default defineConfig({
  plugins: [{
    name: 'computer-activity-fixtures', enforce: 'pre',
    transform(source, id) {
      if (!/[\\/]ui[\\/]App\.tsx$/.test(id)) return null
      return { code: source
        .replace('void getCurrentWindow().isVisible().then(visible => { if (visible) start() })', 'start()')
        .replace("if (command === 'get_snapshot') return demo as T", `if (command === 'get_snapshot') return { ...demo, activity: ${JSON.stringify(activity)}, computer_control_engine: { ...demo.computer_control_engine, selected: 'cua', effective: 'cua', available: true, state: 'ready', active_backend: 'cua' } } as T`)
        .replace("useState<Page>('overview')", "useState<Page>('activity')"), map: null }
    }
  }, react()],
  server: { host: '127.0.0.1', port: 1422, strictPort: true },
  clearScreen: false
})
