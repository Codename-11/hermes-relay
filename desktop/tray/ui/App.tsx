import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { invoke } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import {
  Activity as ActivityIcon, AlertTriangle, ArrowLeft, Bot, Check, ChevronDown, ChevronRight,
  CircleHelp, Clock3, Download, ExternalLink, Eye, FileText, FolderOpen, Home, Info, Laptop, Link2,
  Copy, LoaderCircle, LogOut, Monitor, MousePointer2, Power, Radio, RefreshCw, Server,
  Settings, ShieldCheck, TerminalSquare, Trash2, Unplug, UserRoundX, X, Usb,
  LockKeyhole, SlidersHorizontal, Mic, Video, MousePointerClick, Maximize2, RotateCcw
} from 'lucide-react'
import logo from '../icons/icon-256.png'
import type { AccessMode, Activity, AuthorizedClient, Capability, CapabilityMode, CuaHealthStatus, CuaManagementStatus, Host, PendingGrantRequest, Snapshot, UpdateReport } from './types'
import { describeTransportSecurity } from '../../src/transportSecurity'
import { displayLabel as displayRouteLabel, inferEndpointRole } from '../../src/endpoint'

type Page = 'overview' | 'access' | 'capabilities' | 'hosts' | 'pair-host' | 'host-detail' | 'settings' | 'help' | 'activity' | 'activity-detail'
type PendingAction = { type: 'access'; mode: AccessMode } | { type: 'capability'; capability: Capability; mode: CapabilityMode } | { type: 'revoke'; client: AuthorizedClient; remote: string } | { type: 'repair' | 'forget'; host: Host } | { type: 'clear-activity' } | null
type RouteTestResult = { label: string; url: string; reachable: boolean; elapsed_ms: number; encrypted: boolean; security: string; error?: string | null }
type RouteTestReport = { best?: RouteTestResult | null; routes?: RouteTestResult[] }
type PendingGrantContext = { grant: PendingGrantRequest | null; active_url?: string | null }

const windowLabel = '__TAURI_INTERNALS__' in window ? getCurrentWindow().label : 'main'
const isGrantWindow = windowLabel === 'grant'
const isNoticeWindow = windowLabel === 'notice'
const isEvidenceWindow = windowLabel === 'evidence'

const demo: Snapshot = {
  hosts: [{ url: 'wss://home-hermes.local:8767', name: 'Docker-Server', server_version: '1.6.3', endpoint_role: 'tailscale', paired_at: 1786458000, is_active: true, access_mode: 'full-access', capabilities: { commands: 'allow', files: 'allow', screen_input: 'allow', usb: 'allow', microphone: 'allow', camera: 'allow' } }],
  active_url: 'wss://home-hermes.local:8767',
  daemon: { state: 'connected', running: true, url: 'wss://home-hermes.local:8767', privilege: 'user', username: 'Local user' },
  startup_enabled: true,
  daemon_autostart_enabled: true,
  ui_version: '0.4.0-alpha.7',
  cli_version: '0.4.0-alpha.4',
  cli_path: 'C:\\Program Files\\Hermes-Relay CLI\\hermes-relay.exe',
  hardware_availability: { usb: true, adb: true, microphone: false, camera: false },
  activity_screenshot_retention: { enabled: true, days: 7, count: 2, bytes: 842_000 },
  computer_control_engine: {
    selected: 'legacy', effective: 'legacy', available: false, state: 'not_installed',
    foreground_escalation_enabled: false, message: 'CUA Driver is not installed. Legacy input remains active.'
  },
  pending_grants: [],
  activity: [
    { ts: Date.now() - 110_000, tool: 'desktop_powershell', ok: true, summary: 'exit 0', request_detail: '{\n  "script": "Get-Process | Select-Object -First 5"\n}', stdout: 'Handles  NPM(K)  PM(K)  WS(K)  CPU(s)  Id  ProcessName\n-------  ------  -----  -----  ------  --  -----------\n    412      31  74248  98312    4.18  812 powershell' },
    { ts: Date.now() - 260_000, tool: 'desktop.connect', ok: true, summary: 'Home Hermes connected' },
    { ts: Date.now() - 480_000, tool: 'daemon.start', ok: true, summary: 'Relay daemon started' }
  ]
}

async function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (!('__TAURI_INTERNALS__' in window)) {
    if (command === 'get_snapshot') return demo as T
    if (command === 'get_pending_grant_context') return { grant: demo.pending_grants[0] ?? null, active_url: demo.active_url } as T
    if (command === 'list_authorized_clients') return [
      { token_prefix: 'f83a21c4', device_name: 'WORKSTATION', last_seen: Math.floor(Date.now() / 1000), transport_hint: 'desktop', is_current: true, grants: { chat: null, tools: null } },
      { token_prefix: '9d210b7e', device_name: 'Pixel 10 Pro', last_seen: Math.floor(Date.now() / 1000) - 420, transport_hint: 'android', grants: { chat: null } }
    ] as T
    if (command === 'check_desktop_update') return { current: '0.4.0-alpha.3', up_to_date: true, ahead_of_latest: true, latest_version: '0.4.0-alpha.2', installed: false, needs_restart: false } as T
    if (command === 'install_desktop_update') return { current: '0.4.0-alpha.3', up_to_date: true, ahead_of_latest: false, installed: true, needs_restart: true } as T
    if (command === 'test_host_route') return { best: { label: 'LAN', url: 'ws://172.16.24.250:8767', reachable: true, elapsed_ms: 36, encrypted: false, security: 'Unencrypted relay connection' }, routes: [] } as T
    if (command === 'computer_cua_status') return { installed: false, stale_path_shim: false, compatible: false, compatibility_reason: 'CUA Driver is not installed', supported_range: { minimum: '0.20.0', maximum_exclusive: null } } as T
    if (command === 'computer_cua_health') return { state: 'degraded', checkedAt: new Date().toISOString(), overall: 'degraded', reason: 'UI Automation desktop enumeration exceeded 2000ms.', temporaryWindowsCompatibility: true } as T
    return undefined as T
  }
  return invoke<T>(command, args)
}

function displayHost(url: string): string {
  try {
    const host = new URL(url).hostname.replace(/\.local$/i, '')
    return host.split(/[.-]/).filter(Boolean).map(part => part[0]!.toUpperCase() + part.slice(1)).join(' ')
  } catch { return url }
}

function formatTime(ts: number): string {
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(ts))
}

function formatDateTime(ts: number): string {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit'
  }).format(new Date(ts))
}

function formatDuration(ms?: number): string {
  if (typeof ms !== 'number') return 'Not recorded'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} sec`
}

type ActivityCategory = NonNullable<Activity['category']>
type ActivityFilter = 'all' | ActivityCategory | 'attention' | 'warning'

function activityCategory(entry: Activity): ActivityCategory {
  if (entry.category) return entry.category
  const tool = entry.tool.toLowerCase()
  if (tool.includes('adb') || tool.includes('usb')) return 'devices'
  if (tool.includes('screenshot') || tool.includes('screen')) return 'screen'
  if (tool.includes('computer_') || tool.includes('mouse') || tool.includes('keyboard')) return 'input'
  if (tool.includes('file') || tool.includes('directory') || tool.includes('patch')) return 'files'
  if (tool.includes('shell') || tool.includes('terminal') || tool.includes('powershell') || tool.includes('job')) return 'command'
  if (tool.includes('daemon') || tool.includes('connect')) return 'system'
  return 'other'
}

function activityExitCode(entry: Activity): number | undefined {
  if (typeof entry.exit_code === 'number') return entry.exit_code
  const match = entry.summary?.match(/^exit\s+(-?\d+)$/i)
  return match ? Number(match[1]) : undefined
}

function needsAttention(entry: Activity): boolean {
  return !entry.ok || !!entry.aborted
}

function isNonZeroExit(entry: Activity): boolean {
  const exitCode = activityExitCode(entry)
  return entry.ok && !entry.aborted && exitCode !== undefined && exitCode !== 0
}

function activityName(tool: string): string {
  const names: Record<string, string> = {
    desktop_powershell: 'PowerShell command', desktop_terminal: 'Terminal command',
    desktop_job_start: 'Background job', desktop_read_file: 'Read file',
    desktop_write_file: 'Write file', desktop_apply_patch: 'Apply patch',
    desktop_computer_screenshot: 'Captured screen', desktop_computer_input: 'Desktop input',
    'desktop.update': 'Desktop update', 'host.select': 'Selected host',
    'host.access': 'Changed host access', 'host.pair': 'Pair host',
    'client.revoke': 'Deauthorized client', 'grant.resolve': 'Resolved access request',
    'daemon.start': 'Connected daemon', 'daemon.stop': 'Disconnected daemon',
    'daemon.reconnecting': 'Connection interrupted', 'daemon.reconnected': 'Tunnel restored',
    'daemon.disconnected': 'Tunnel disconnected', 'daemon.auth_failed': 'Connection failed',
    'daemon.restart': 'Restarted daemon', 'startup.change': 'Changed startup setting'
  }
  return names[tool] ?? tool.replace(/^desktop[._]/, '').replaceAll('_', ' ').replaceAll('.', ' ').replace(/\b\w/g, value => value.toUpperCase())
}

function isComputerControl(entry: Activity): boolean {
  return entry.backend === 'cua' || entry.backend === 'legacy_compat' || entry.tool.startsWith('desktop_computer_')
}

function controlActionLabel(entry: Activity): string {
  return (entry.action ?? activityName(entry.tool)).replaceAll('_', ' ').replace(/\b\w/g, value => value.toUpperCase())
}

function controlVerificationLabel(value?: string): string {
  return value === 'snapshot_captured' ? 'Post-action snapshot captured'
    : value === 'failed' ? 'Verification failed'
      : value ? value.replaceAll('_', ' ') : 'Not reported'
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

type ActivityStep = { title: string; detail: string; state: 'done' | 'failed' | 'pending' }

function activitySteps(entry: Activity): ActivityStep[] {
  if (isComputerControl(entry)) return [
    { title: 'Authorized session', detail: entry.control_session_id ? 'Authenticated control session' : 'Authenticated by Hermes', state: 'done' },
    { title: controlActionLabel(entry), detail: `${entry.backend === 'cua' ? 'CUA structured engine' : 'Windows input · Compatibility'} · ${entry.dispatch ?? 'background'}`, state: entry.ok ? 'done' : 'failed' },
    { title: 'Verification', detail: controlVerificationLabel(entry.verification), state: entry.verification === 'failed' ? 'failed' : entry.verification ? 'done' : 'pending' }
  ]
  const category = activityCategory(entry)
  if (category === 'system') {
    const reconnecting = entry.tool === 'daemon.reconnecting'
    return [
      { title: 'Connection state changed', detail: entry.summary ?? activityName(entry.tool), state: entry.ok ? 'done' : 'failed' },
      ...(reconnecting ? [{ title: 'Automatic retry', detail: 'Relay transport is retrying with backoff', state: 'pending' as const }] : [])
    ]
  }
  return [
    { title: 'Request received', detail: entry.args_preview ?? 'Validated local request', state: 'done' },
    { title: activityName(entry.tool), detail: entry.aborted ? 'Stopped before completion' : entry.error ?? entry.summary ?? 'Local execution', state: entry.ok ? 'done' : 'failed' },
    { title: 'Result recorded', detail: entry.ok ? (isNonZeroExit(entry) ? `Process exited ${activityExitCode(entry)}` : 'Evidence saved to local activity') : 'Failure details recorded', state: entry.ok ? 'done' : 'failed' }
  ]
}

function age(ts?: number): string {
  if (!ts) return 'Not seen yet'
  const seconds = Math.max(0, Math.floor(Date.now() / 1000) - ts)
  if (seconds < 60) return 'Active now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} hr ago`
  return `${Math.floor(seconds / 86400)} days ago`
}

function formatGrantScope(scope: unknown): string {
  if (!scope || typeof scope !== 'object') return ''
  return Object.entries(scope as Record<string, unknown>)
    .filter(([key, value]) => !['action', 'preview'].includes(key) && typeof value === 'string' && value.trim())
    .map(([key, value]) => `${key}: ${String(value)}`)
    .join(', ')
}

function grantAction(scope: unknown): { label: string; preview: string } | null {
  if (!scope || typeof scope !== 'object') return null
  const record = scope as Record<string, unknown>
  const label = typeof record.action === 'string' ? record.action.trim() : ''
  const preview = typeof record.preview === 'string' ? record.preview.trim() : ''
  if (!label && !preview) return null
  return { label: label || 'Requested action', preview: preview || label }
}

function friendlyUpdateError(error: unknown): string {
  const message = String(error)
  const jsonStart = message.indexOf('{')
  if (jsonStart >= 0) {
    try {
      const report = JSON.parse(message.slice(jsonStart)) as { error?: unknown }
      if (typeof report.error === 'string' && report.error.trim()) return report.error
    } catch { /* Fall through to the original transport error. */ }
  }
  return message.replace(/^Error:\s*/i, '')
}

const accessCopy: Record<AccessMode, string> = {
  ask: 'All desktop capabilities are off. The relay connection stays ready.',
  'ask-every-time': 'Each available command, file, screen, input, or USB operation asks first.',
  structured: 'Files are allowed. Screen, input, and USB ask first. Raw commands stay off.',
  trusted: 'Individual capabilities use the settings migrated from the former Trusted preset.',
  'full-access': 'Every available capability is allowed without task grants.',
  custom: 'Individual capabilities use the settings you choose.'
}

function formatPairedAt(ts?: number | null): string {
  if (!ts) return 'Unknown'
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(new Date(ts * 1000))
}

const accessLabel: Record<AccessMode, string> = {
  ask: 'Restricted', 'ask-every-time': 'Ask Every Time', structured: 'Standard', trusted: 'Custom', 'full-access': 'Full Access', custom: 'Custom'
}

const capabilityLabel: Record<CapabilityMode, string> = {
  disabled: 'Off', ask: 'Ask', allow: 'Allow'
}

const presetCapabilities: Partial<Record<AccessMode, Host['capabilities']>> = {
  ask: { commands: 'disabled', files: 'disabled', screen_input: 'disabled', usb: 'disabled', microphone: 'disabled', camera: 'disabled' },
  'ask-every-time': { commands: 'ask', files: 'ask', screen_input: 'ask', usb: 'ask', microphone: 'disabled', camera: 'disabled' },
  structured: { commands: 'disabled', files: 'allow', screen_input: 'ask', usb: 'ask', microphone: 'disabled', camera: 'disabled' },
  trusted: { commands: 'allow', files: 'allow', screen_input: 'ask', usb: 'ask', microphone: 'disabled', camera: 'disabled' },
  'full-access': { commands: 'allow', files: 'allow', screen_input: 'allow', usb: 'allow', microphone: 'allow', camera: 'allow' }
}

function hostAccessLabel(host: Host): string {
  const expected = presetCapabilities[host.access_mode]
  if (!expected || Object.entries(expected).some(([capability, mode]) => host.capabilities[capability as Capability] !== mode)) return 'Custom'
  return accessLabel[host.access_mode]
}

export default function App() {
  return isGrantWindow ? <GrantWindow /> : isNoticeWindow ? <ConnectionNoticeWindow /> : isEvidenceWindow ? <EvidenceWindow /> : <ManagementApp />
}

type ConnectionNotice = { tone: 'connected' | 'warning' | 'offline'; title: string; detail: string }

function ConnectionNoticeWindow() {
  const [notice, setNotice] = useState<ConnectionNotice | null>(null)
  const hideTimer = useRef<number | null>(null)
  useEffect(() => {
    const receive = (event: Event) => {
      const detail = (event as CustomEvent<ConnectionNotice>).detail
      setNotice(detail)
      if (hideTimer.current) window.clearTimeout(hideTimer.current)
      hideTimer.current = window.setTimeout(() => void getCurrentWindow().hide(), detail.tone === 'warning' ? 6500 : 4200)
    }
    window.addEventListener('hermes-connection-notice', receive)
    return () => { window.removeEventListener('hermes-connection-notice', receive); if (hideTimer.current) window.clearTimeout(hideTimer.current) }
  }, [])
  if (!notice) return null
  return <div className={`connection-notice-shell ${notice.tone}`}>
    <section className="connection-notice-card" role="status" aria-live="polite">
      <span className="connection-notice-icon">{notice.tone === 'connected' ? <Check /> : notice.tone === 'warning' ? <RotateCcw /> : <Unplug />}</span>
      <span><small>Hermes-Relay tunnel</small><strong>{notice.title}</strong><p>{notice.detail}</p></span>
      <button className="connection-notice-open" onClick={() => call('open_management_from_notice')}>Open</button>
      <button className="connection-notice-close" aria-label="Dismiss" onClick={() => getCurrentWindow().hide()}><X /></button>
    </section>
  </div>
}

function EvidenceWindow() {
  const [evidenceId, setEvidenceId] = useState<string | null>(null)
  const [source, setSource] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const receive = (event: Event) => {
      const id = (event as CustomEvent<{ evidenceId: string }>).detail.evidenceId
      setEvidenceId(id); setSource(null); setError(null)
      void call<string>('get_activity_screenshot', { evidenceId: id }).then(setSource).catch(value => setError(String(value)))
    }
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape') void getCurrentWindow().hide() }
    window.addEventListener('hermes-screenshot-evidence', receive)
    window.addEventListener('keydown', close)
    return () => { window.removeEventListener('hermes-screenshot-evidence', receive); window.removeEventListener('keydown', close) }
  }, [])
  return <div className="evidence-shell">
    <header><span><Eye /><strong>Screenshot evidence</strong><small>Stored locally with this activity event</small></span><button aria-label="Close screenshot" onClick={() => getCurrentWindow().hide()}><X /></button></header>
    <main>{source ? <img src={source} alt="Retained desktop screenshot" /> : error ? <div className="evidence-error"><AlertTriangle /><strong>Screenshot unavailable</strong><small>{error}</small></div> : <div className="evidence-loading"><LoaderCircle className="spin" /><span>{evidenceId ? 'Loading screenshot…' : 'Preparing viewer…'}</span></div>}</main>
  </div>
}

function ManagementApp() {
  const [page, setPage] = useState<Page>('overview')
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [selectedUrl, setSelectedUrl] = useState<string | null>(null)
  const [detailUrl, setDetailUrl] = useState<string | null>(null)
  const [pairInitialUrl, setPairInitialUrl] = useState('')
  const [activityBack, setActivityBack] = useState<Page>('settings')
  const [policyBack, setPolicyBack] = useState<Page>('overview')
  const [activityDetailBack, setActivityDetailBack] = useState<Page>('activity')
  const [selectedActivity, setSelectedActivity] = useState<Activity | null>(null)
  const [selectorOpen, setSelectorOpen] = useState(false)
  const [routeDetailsOpen, setRouteDetailsOpen] = useState(false)
  const [routeTest, setRouteTest] = useState<RouteTestReport | null>(null)
  const [clients, setClients] = useState<AuthorizedClient[]>([])
  const [busy, setBusy] = useState<string | null>(null)
  const [connectionTransition, setConnectionTransition] = useState<'connecting' | 'disconnecting' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<PendingAction>(null)
  const [windowVisible, setWindowVisible] = useState(true)
  const [reviewGrantOpen, setReviewGrantOpen] = useState(false)
  const selectorRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLElement>(null)
  const hideTimer = useRef<number | null>(null)
  const refreshInFlight = useRef(false)

  const refresh = useCallback(async () => {
    if (refreshInFlight.current) return true
    refreshInFlight.current = true
    try {
      const next = await call<Snapshot>('get_snapshot')
      next.hosts = next.hosts.map(h => {
        const capabilities = h.capabilities as Partial<Host['capabilities']>
        return {
          ...h,
          name: h.name || displayHost(h.url),
          capabilities: {
            commands: capabilities.commands ?? (h.access_mode === 'full-access' ? 'allow' : h.access_mode === 'trusted' ? 'allow' : 'disabled'),
            files: capabilities.files ?? (h.access_mode === 'ask' ? 'disabled' : h.access_mode === 'ask-every-time' ? 'ask' : 'allow'),
            screen_input: capabilities.screen_input ?? (h.access_mode === 'full-access' ? 'allow' : h.access_mode === 'ask' ? 'disabled' : 'ask'),
            usb: capabilities.usb ?? (h.access_mode === 'ask-every-time' ? 'ask' : 'disabled'),
            microphone: capabilities.microphone ?? 'disabled',
            camera: capabilities.camera ?? 'disabled'
          }
        }
      })
      setSnapshot(next)
      setSelectedUrl(current => current && next.hosts.some(h => h.url === current) ? current : next.active_url ?? next.hosts[0]?.url ?? null)
      setError(null)
      return true
    } catch (e) { setError(String(e)); return false }
    finally { refreshInFlight.current = false }
  }, [])

  const daemonRetrying = Boolean(snapshot?.daemon.running && snapshot.daemon.state === 'reconnecting')

  useEffect(() => {
    let disposed = false
    let enabled = false
    let running = false
    let failures = 0
    let timer: number | null = null
    const stop = () => {
      enabled = false
      if (timer !== null) window.clearTimeout(timer)
      timer = null
    }
    const schedule = (delay: number) => {
      if (disposed || !enabled) return
      if (timer !== null) window.clearTimeout(timer)
      timer = window.setTimeout(poll, delay)
    }
    const poll = async () => {
      if (disposed || !enabled || running) return
      running = true
      timer = null
      const ok = await refresh()
      failures = ok ? 0 : Math.min(failures + 1, 4)
      running = false
      const baseDelay = connectionTransition ? 350 : daemonRetrying ? 1000 : 5000
      schedule(ok ? baseDelay : Math.min(30_000, baseDelay * (2 ** failures)))
    }
    const start = () => {
      if (disposed) return
      enabled = true
      if (timer === null && !running) void poll()
    }
    const visibilityChanged = () => {
      if (document.visibilityState === 'visible') start()
      else stop()
    }
    window.addEventListener('hermes-show', start)
    window.addEventListener('hermes-hide', stop)
    document.addEventListener('visibilitychange', visibilityChanged)
    void getCurrentWindow().isVisible().then(visible => { if (visible) start() })
    return () => {
      disposed = true
      stop()
      window.removeEventListener('hermes-show', start)
      window.removeEventListener('hermes-hide', stop)
      document.removeEventListener('visibilitychange', visibilityChanged)
    }
  }, [refresh, connectionTransition, daemonRetrying])

  useEffect(() => {
    const close = (event: MouseEvent) => {
      if (!selectorRef.current?.contains(event.target as Node)) setSelectorOpen(false)
    }
    window.addEventListener('mousedown', close)
    return () => window.removeEventListener('mousedown', close)
  }, [])

  const host = useMemo(() => snapshot?.hosts.find(item => item.url === selectedUrl) ?? null, [snapshot, selectedUrl])
  const daemonTargetsHost = Boolean(host && (snapshot?.daemon.configured_url ?? snapshot?.daemon.url) === host.url)
  const daemonActive = Boolean(snapshot?.daemon.running && daemonTargetsHost)
  const connected = Boolean(daemonActive && snapshot?.daemon.state === 'connected')
  const reconnecting = Boolean(daemonActive && snapshot?.daemon.state === 'reconnecting')
  const retrySeconds = reconnecting && snapshot?.daemon.retry_at ? Math.max(0, snapshot.daemon.retry_at - Math.floor(Date.now() / 1000)) : null

  useEffect(() => {
    if (page !== 'host-detail' || !detailUrl) return
    setClients([])
    call<AuthorizedClient[]>('list_authorized_clients', { remote: detailUrl }).then(setClients).catch(e => setError(String(e)))
  }, [page, detailUrl])

  useEffect(() => {
    contentRef.current?.scrollTo({ top: 0 })
  }, [page])

  async function action(name: string, args?: Record<string, unknown>) {
    setBusy(name)
    try { await call(name, args); await refresh(); setError(null); return true }
    catch (e) { setError(String(e)); return false }
    finally { setBusy(null) }
  }

  async function changeConnection() {
    if (busy) return
    const command = daemonActive ? 'disconnect_daemon' : 'connect_daemon'
    const transition = daemonActive ? 'disconnecting' : 'connecting'
    setBusy(command)
    setConnectionTransition(transition)
    setError(null)
    try {
      await call(command)
      await refresh()
    } catch (e) {
      setError(String(e))
    } finally {
      setConnectionTransition(null)
      setBusy(null)
    }
  }

  async function retryConnection() {
    if (busy) return
    setBusy('restart_daemon')
    setConnectionTransition('connecting')
    setError(null)
    try { await call('restart_daemon'); await refresh() }
    catch (e) { setError(String(e)) }
    finally { setConnectionTransition(null); setBusy(null) }
  }

  async function testRoute(remote: string) {
    setBusy('test_host_route')
    setRouteTest(null)
    try {
      const report = await call<RouteTestReport>('test_host_route', { remote })
      setRouteTest(report)
      setError(null)
    } catch (e) { setRouteTest(null); setError(String(e)) }
    finally { setBusy(null) }
  }

  function openPair(url = '') {
    setPairInitialUrl(url)
    setSelectorOpen(false)
    setPage('pair-host')
  }

  async function pairHost(remote: string, code: string) {
    const paired = await action('pair_host', { remote, code })
    if (paired) setPage('hosts')
    return paired
  }

  async function selectHost(url: string) {
    setSelectedUrl(url)
    setSelectorOpen(false)
    await action('select_host', { remote: url })
  }

  async function connectHost(url: string) {
    await selectHost(url)
    setPage('overview')
  }

  async function confirmPending() {
    const work = pending
    setPending(null)
    if (!work) return
    if (work.type === 'access' && host) {
      await action('set_host_access', { remote: host.url, mode: work.mode })
    } else if (work.type === 'capability' && host) {
      await action('set_host_capability', { remote: host.url, capability: work.capability, mode: work.mode })
    } else if (work.type === 'revoke') {
      await action('revoke_authorized_client', { remote: work.remote, prefix: work.client.token_prefix })
      setClients(await call<AuthorizedClient[]>('list_authorized_clients', { remote: work.remote }))
    } else if (work.type === 'repair') {
      openPair(work.host.url)
    } else if (work.type === 'forget') {
      await action('forget_host', { remote: work.host.url })
      setDetailUrl(null)
      setPage('hosts')
    } else if (work.type === 'clear-activity') {
      await action('clear_activity')
    }
  }

  const chooseAccess = (mode: AccessMode) => {
    if (!host || host.access_mode === mode) return
    if (mode === 'full-access') setPending({ type: 'access', mode })
    else action('set_host_access', { remote: host.url, mode })
  }

  const chooseCapability = (capability: Capability, mode: CapabilityMode) => {
    if (!host || host.capabilities[capability] === mode || capability === 'microphone' || capability === 'camera') return
    if (mode === 'allow') setPending({ type: 'capability', capability, mode })
    else action('set_host_capability', { remote: host.url, capability, mode })
  }

  const hideWindow = useCallback(() => {
    setWindowVisible(false)
    if (hideTimer.current) window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => {
      void getCurrentWindow().hide().finally(() => setWindowVisible(true))
    }, 145)
  }, [])

  const requestHide = useCallback(() => {
    window.dispatchEvent(new Event('hermes-hide'))
  }, [])

  useEffect(() => {
    const show = () => setWindowVisible(true)
    const visibilityChanged = () => {
      if (document.visibilityState === 'visible') setWindowVisible(true)
    }
    window.addEventListener('hermes-show', show)
    window.addEventListener('hermes-hide', hideWindow)
    document.addEventListener('visibilitychange', visibilityChanged)
    return () => {
      window.removeEventListener('hermes-show', show)
      window.removeEventListener('hermes-hide', hideWindow)
      document.removeEventListener('visibilitychange', visibilityChanged)
      if (hideTimer.current) window.clearTimeout(hideTimer.current)
    }
  }, [hideWindow])

  useEffect(() => {
    const review = () => { setReviewGrantOpen(true); void refresh() }
    window.addEventListener('hermes-review-grant', review)
    return () => window.removeEventListener('hermes-review-grant', review)
  }, [refresh])

  if (!snapshot) return <div className={`app-shell loading ${windowVisible ? 'window-visible' : ''}`}><LoaderCircle className="spin" /><span>Loading Hermes-Relay CLI UI…</span></div>
  const reviewGrant = snapshot.pending_grants[0] ?? null
  const reviewAction = grantAction(reviewGrant?.scope)
  const reviewScope = formatGrantScope(reviewGrant?.scope)
  const reviewHost = snapshot.hosts.find(item => item.url === (snapshot.daemon.configured_url ?? snapshot.daemon.url))?.name ?? 'Connected host'

  return <div className={`app-shell ${windowVisible ? 'window-visible' : ''}`}>
    <header className="titlebar">
      <div className="brand"><img src={logo} alt="" /><span>Hermes-Relay CLI UI</span></div>
      <div className="window-controls">
        <button aria-label="Help and About" title="Help and About" onClick={() => setPage('help')}><CircleHelp /></button>
        <button aria-label="Hide window" onClick={requestHide}><X /></button>
      </div>
    </header>

    <main className="content" ref={contentRef}>
      {page === 'overview' && <>
        <section className={`connection-route ${connected ? 'online' : reconnecting ? 'retrying' : 'offline'} ${connectionTransition ?? ''} ${snapshot.daemon.active_route === 'plugin_proxy' ? 'secure-link' : ''}`} aria-busy={connectionTransition !== null || reconnecting}>
          {snapshot.hosts.length === 0 ?
            <button className="empty-pair" onClick={() => openPair()}><Link2 /><span><strong>Pair host</strong><small>Connect this PC to a Hermes instance</small></span><ChevronRight /></button> :
            <div className="route-grid" ref={selectorRef}>
              <span className="route-endpoint"><Bot /><small>Agent</small></span>
              <i className="route-link left" />
              <div className="route-host">
                <button className="route-host-button" aria-expanded={selectorOpen} aria-label={`Connected host: ${host?.name}. Change host`} onClick={() => setSelectorOpen(value => !value)}>
                  <span className="route-host-icon"><Server /></span>
                  <span className="route-host-copy"><small>Connected host</small><strong>{host?.name}</strong></span>
                  <span className="route-host-action">Change</span><ChevronDown />
                </button>
              {selectorOpen && <div className="selector-menu">
                {snapshot.hosts.map(item => <button key={item.url} className={item.url === host?.url ? 'selected' : ''} onClick={() => selectHost(item.url)}><Monitor /><span><strong>{item.name}</strong><small>{item.url}</small></span>{item.url === host?.url && <Check />}</button>)}
                <button className="pair-option" onClick={() => openPair()}><Link2 /><span><strong>Pair host</strong><small>Connect another Hermes instance</small></span></button>
              </div>}
              </div>
              <i className="route-link right" />
              <span className="route-endpoint"><Monitor /><small>This PC</small></span>
              {(connected || connectionTransition === 'connecting') && <span className="route-traffic" aria-hidden="true">
                <i className="packet packet-outbound" /><i className="packet packet-outbound packet-late" />
                <i className="packet packet-inbound" /><i className="packet packet-inbound packet-late" />
              </span>}
              {(() => {
                const activeRole = snapshot.daemon.active_route ?? host?.endpoint_role ?? inferEndpointRole(snapshot.daemon.url ?? host?.url ?? '')
                const security = describeTransportSecurity(snapshot.daemon.url ?? host?.url ?? '', activeRole)
                return <div className={`route-status ${connected && !security.encrypted ? 'insecure' : ''}`} aria-live="polite" aria-atomic="true">
                  <strong>{connectionTransition === 'connecting' ? 'Connecting' : connectionTransition === 'disconnecting' ? 'Disconnecting' : reconnecting ? 'Reconnecting' : connected ? 'Connected' : 'Disconnected'}</strong>
                  {connected && <button className={`route-badge ${security.kind}`} aria-expanded={routeDetailsOpen} onClick={() => setRouteDetailsOpen(open => !open)}><ShieldCheck />{displayRouteLabel(activeRole ?? 'custom')}<ChevronDown /></button>}
                  {connectionTransition && <small>{connectionTransition === 'connecting' ? 'Starting daemon and opening relay tunnel' : 'Closing relay tunnel'}</small>}
                  {reconnecting && !connectionTransition && <><small>Attempt {snapshot.daemon.reconnect_attempt ?? 1}{retrySeconds !== null ? ` · retry in ${retrySeconds}s` : ' · retry scheduled'}</small><button className="retry-now" disabled={busy !== null} onClick={() => void retryConnection()}><RefreshCw /> Retry now</button></>}
                  {!connected && !reconnecting && !connectionTransition && <small>{snapshot.daemon.last_error ?? 'Relay connection offline'}</small>}
                  {connected && routeDetailsOpen && <aside className="route-detail-card"><div><ShieldCheck /><span><strong>{displayRouteLabel(activeRole ?? 'custom')}</strong><small>{security.detail}</small></span></div><dl><div><dt>Security</dt><dd>{security.label}</dd></div><div><dt>Endpoint</dt><dd title={snapshot.daemon.url ?? undefined}>{snapshot.daemon.url ?? 'Not reported'}</dd></div></dl><button className="route-test-button" disabled={busy === 'test_host_route'} onClick={() => host && testRoute(host.url)}>{busy === 'test_host_route' ? <LoaderCircle className="spin" /> : routeTest ? <RefreshCw /> : <ActivityIcon />}<span>{busy === 'test_host_route' ? <><strong>Testing connection…</strong><small>Checking every saved route</small></> : <><strong>{routeTest ? 'Test again' : 'Test connection'}</strong><small>Measure reachability and latency</small></>}</span></button>{routeTest && <div className={`route-test-result ${routeTest.best ? 'reachable' : 'unreachable'}`} aria-live="polite">{routeTest.best ? <><div className="route-test-summary"><span><Check /></span><strong>{routeTest.best.label} reachable</strong><em>{routeTest.best.elapsed_ms} ms</em></div><dl><div><dt>Protection</dt><dd className={routeTest.best.encrypted ? 'secure' : 'warning'}>{routeTest.best.security}</dd></div><div><dt>Tested endpoint</dt><dd title={routeTest.best.url}>{routeTest.best.url}</dd></div></dl><small>{Math.max(1, routeTest.routes?.length ?? 0)} saved route{(routeTest.routes?.length ?? 0) === 1 ? '' : 's'} checked</small></> : <div className="route-test-summary"><span><X /></span><strong>No route reachable</strong><em>Check host</em></div>}</div>}</aside>}
                </div>
              })()}
            </div>}
        </section>

        {host && <section className="policy-ledger" aria-label="Host access and capabilities">
          <button onClick={() => setPage('access')}>
            <span className="policy-icon"><LockKeyhole /></span>
            <span className="policy-name"><strong>Desktop access</strong><small>Preset for this host</small></span>
            <span className="policy-value">{hostAccessLabel(host)}</span>
            <ChevronRight />
          </button>
          <button onClick={() => setPage('capabilities')}>
            <span className="policy-icon"><SlidersHorizontal /></span>
            <span className="policy-name"><strong>Capabilities</strong><small>{host.access_mode === 'full-access' ? 'Included by Full Access' : 'Commands, files, screen and hardware'}</small></span>
            <span className="capability-summary"><em>{host.access_mode === 'full-access' ? 'All Allow' : `USB ${capabilityLabel[host.capabilities.usb]}`}</em></span>
            <ChevronRight />
          </button>
        </section>}

        <button className={`tunnel-button ${connectionTransition || reconnecting ? 'pending' : ''}`} disabled={busy !== null} aria-busy={connectionTransition !== null} onClick={() => void changeConnection()}>
          {connectionTransition ? <LoaderCircle className="spin" /> : reconnecting ? <Unplug /> : <Power />}
          <span><strong>{connectionTransition === 'connecting' ? 'Connecting…' : connectionTransition === 'disconnecting' ? 'Disconnecting…' : reconnecting ? 'Disconnect Tunnel' : connected ? 'Disconnect Tunnel' : 'Connect Tunnel'}</strong>{(connectionTransition || reconnecting) && <small>{connectionTransition === 'connecting' ? 'Waiting for the relay' : connectionTransition === 'disconnecting' ? 'Stopping the local daemon' : 'Automatic retry remains active'}</small>}</span>
        </button>

        <section className="activity-section">
          <div className="section-heading"><h2>Recent activity</h2><button onClick={() => { setActivityBack('overview'); setPage('activity') }}>View all <ChevronRight /></button></div>
          <ActivityList entries={snapshot.activity.slice(-3).reverse()} host={host} onOpen={entry => { setSelectedActivity(entry); setActivityDetailBack('overview'); setPage('activity-detail') }} />
        </section>

        {snapshot.daemon.privilege === 'administrator' && <aside className="admin-warning"><AlertTriangle /><span>Hermes-Relay CLI is running as Administrator.</span><button onClick={() => { setSelectedUrl(snapshot.active_url ?? null); setPage('settings') }}>Learn more</button></aside>}
      </>}

      {page === 'access' && <AccessPage host={host} busy={busy !== null} onBack={() => setPage(policyBack)} onChoose={chooseAccess} />}
      {page === 'capabilities' && <CapabilitiesPage host={host} availability={snapshot.hardware_availability} busy={busy !== null} onBack={() => setPage(policyBack)} onChoose={chooseCapability} />}

      {page === 'hosts' && <HostsPage hosts={snapshot.hosts} selected={host} onOpen={url => { setDetailUrl(url); setSelectedUrl(url); setPage('host-detail') }} onPair={() => openPair()} />}
      {page === 'pair-host' && <PairHostPage initialUrl={pairInitialUrl} busy={busy === 'pair_host'} onBack={() => setPage('hosts')} onPair={pairHost} />}
      {page === 'host-detail' && <HostDetailPage host={snapshot.hosts.find(item => item.url === detailUrl) ?? null} clients={clients} busy={busy !== null} onBack={() => setPage('hosts')} onConnect={connectHost} onRename={(remote, name) => action('rename_host', { remote, name })} onAccess={() => { setPolicyBack('host-detail'); setPage('access') }} onCapabilities={() => { setPolicyBack('host-detail'); setPage('capabilities') }} onRevoke={(remote, client) => setPending({ type: 'revoke', client, remote })} onRepair={host => setPending({ type: 'repair', host })} onForget={host => setPending({ type: 'forget', host })} />}
      {page === 'settings' && <SettingsPage daemon={snapshot.daemon} computerControl={snapshot.computer_control_engine ?? null} startup={snapshot.startup_enabled} daemonAutostart={snapshot.daemon_autostart_enabled ?? false} activity={snapshot.activity} screenshotRetention={snapshot.activity_screenshot_retention} onAction={action} onStartup={value => action('set_startup', { enabled: value })} onDaemonAutostart={value => action('set_daemon_autostart', { enabled: value })} onHelp={() => setPage('help')} onViewActivity={() => { setActivityBack('settings'); setPage('activity') }} onOpenActivity={entry => { setSelectedActivity(entry); setActivityDetailBack('settings'); setPage('activity-detail') }} />}
      {page === 'help' && <HelpPage snapshot={snapshot} host={host} onBack={() => { setSelectedUrl(snapshot.active_url ?? null); setPage('settings') }} onAction={action} />}
      {page === 'activity' && <ActivityPage entries={snapshot.activity} host={host} onBack={() => setPage(activityBack)} onClear={() => setPending({ type: 'clear-activity' })} onOpen={entry => { setSelectedActivity(entry); setActivityDetailBack('activity'); setPage('activity-detail') }} />}
      {page === 'activity-detail' && <ActivityDetailPage entry={selectedActivity} host={host} onBack={() => setPage(activityDetailBack)} />}
    </main>

    {error && <div className="error-toast" role="alert"><AlertTriangle /><span>{error}</span><button onClick={() => setError(null)}><X /></button></div>}

    {reviewGrantOpen && reviewGrant && <div className="modal-backdrop grant-review-backdrop" role="presentation"><div className="modal grant-review-modal" role="dialog" aria-modal="true" aria-labelledby="review-grant-title">
      <div className="modal-icon"><ShieldCheck /></div><h2 id="review-grant-title">Review remote request</h2>
      <p>{reviewHost} wants to perform an action on this PC.</p>
      {reviewAction && <div className="grant-action-full"><dt>Requested action · {reviewAction.label}</dt><dd><pre>{reviewAction.preview}</pre></dd></div>}
      <dl className="review-grant-facts"><div><dt>Reason</dt><dd>{reviewGrant.reason || 'No reason provided'}</dd></div>{reviewScope && <div><dt>Scope</dt><dd>{reviewScope}</dd></div>}</dl>
      <div className="modal-actions"><button className="secondary" onClick={async () => { await action('resolve_grant', { id: reviewGrant.id, approved: false }); setReviewGrantOpen(false) }}>Reject</button><button className="primary" onClick={async () => { await action('resolve_grant', { id: reviewGrant.id, approved: true }); setReviewGrantOpen(false) }}>Approve</button></div>
    </div></div>}

    <nav className="bottom-nav">
      <button className={page === 'overview' || ((page === 'access' || page === 'capabilities') && policyBack === 'overview') ? 'active' : ''} onClick={() => { setSelectedUrl(snapshot.active_url ?? null); setPolicyBack('overview'); setPage('overview') }}><Home /><span>Overview</span></button>
      <button className={page === 'hosts' || page === 'pair-host' || page === 'host-detail' || ((page === 'access' || page === 'capabilities') && policyBack === 'host-detail') ? 'active' : ''} onClick={() => setPage('hosts')}><Monitor /><span>Hosts</span></button>
      <button className={page === 'settings' || page === 'help' || page === 'activity' || page === 'activity-detail' ? 'active' : ''} onClick={() => { setSelectedUrl(snapshot.active_url ?? null); setPage('settings') }}><Settings /><span>Settings</span></button>
    </nav>

    {pending && <div className="modal-backdrop" role="presentation"><div className="modal" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
      <div className="modal-icon">{pending.type === 'revoke' ? <UserRoundX /> : pending.type === 'forget' || pending.type === 'clear-activity' ? <Trash2 /> : <AlertTriangle />}</div>
      <h2 id="confirm-title">{pending.type === 'access' ? `Give ${host?.name} full access?` : pending.type === 'capability' ? `Always allow ${pending.capability.replace('_', ' & ')} for ${host?.name}?` : pending.type === 'revoke' ? `Deauthorize ${pending.client.device_name ?? pending.client.token_prefix}?` : pending.type === 'repair' ? `Re-pair ${pending.host.name}?` : pending.type === 'forget' ? `Forget ${pending.host.name}?` : 'Clear local activity?'}</h2>
      <p>{pending.type === 'access' ? 'Every available capability will be allowed without task grants. Only use Full Access with a Hermes host you control.' : pending.type === 'capability' ? 'This allows the capability without per-operation approval. The matching preset is selected automatically; otherwise the policy becomes Custom.' : pending.type === 'revoke' ? (pending.client.is_current ? 'This is the current PC session. You will need to pair again.' : 'This client will immediately lose access to this Hermes host.') : pending.type === 'repair' ? 'A fresh pairing flow will replace this host session. Use this to recover an expired or invalid pairing.' : pending.type === 'forget' ? `This removes the local pairing, access policy, and display name. ${pending.host.is_active ? 'The active daemon will disconnect.' : 'The remote relay is not changed.'}` : 'This permanently removes the current and rotated desktop audit history from this PC. New activity will continue to be recorded.'}</p>
      <div className="modal-actions"><button className="secondary" onClick={() => setPending(null)}>Cancel</button><button className="danger" onClick={confirmPending}>{pending.type === 'access' ? 'Enable Full Access' : pending.type === 'capability' ? 'Allow capability' : pending.type === 'revoke' ? 'Deauthorize' : pending.type === 'repair' ? 'Start re-pairing' : pending.type === 'forget' ? 'Forget host' : 'Clear activity'}</button></div>
    </div></div>}
  </div>
}

function GrantWindow() {
  const [activeUrl, setActiveUrl] = useState<string | null>(null)
  const [grant, setGrant] = useState<PendingGrantRequest | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const activeId = useRef<string | null>(null)

  const refreshGrant = useCallback(async () => {
    try {
      const next = await call<PendingGrantContext>('get_pending_grant_context')
      const incoming = next.grant
      setActiveUrl(next.active_url ?? null)
      if (!incoming) {
        if (activeId.current) {
          activeId.current = null
          setVisible(false)
          window.setTimeout(() => { void getCurrentWindow().hide() }, 145)
        }
        setGrant(null)
        return
      }
      setGrant(incoming)
      if (activeId.current !== incoming.id) {
        activeId.current = incoming.id
        setExpanded(false)
        setVisible(false)
        await call('present_grant_window', { expanded: false })
        window.requestAnimationFrame(() => setVisible(true))
      }
    } catch { /* The next poll retries without interrupting the operator. */ }
  }, [])

  useEffect(() => {
    let stopped = false
    let running = false
    let rerunRequested = false
    let timer: number | null = null

    const schedule = (delay: number) => {
      if (stopped) return
      if (timer !== null) window.clearTimeout(timer)
      timer = window.setTimeout(poll, delay)
    }
    const poll = async () => {
      if (stopped) return
      if (running) {
        rerunRequested = true
        return
      }
      running = true
      if (timer !== null) {
        window.clearTimeout(timer)
        timer = null
      }
      try { await refreshGrant() }
      finally {
        running = false
        if (stopped) return
        if (rerunRequested) {
          rerunRequested = false
          schedule(0)
        } else if (activeId.current || document.visibilityState === 'visible') {
          schedule(activeId.current ? 1000 : 5000)
        }
      }
    }
    const wake = () => {
      if (document.visibilityState === 'visible') void poll()
      else if (!activeId.current && timer !== null) {
        window.clearTimeout(timer)
        timer = null
      }
    }

    document.addEventListener('visibilitychange', wake)
    window.addEventListener('focus', wake)
    void poll()
    return () => {
      stopped = true
      if (timer !== null) window.clearTimeout(timer)
      document.removeEventListener('visibilitychange', wake)
      window.removeEventListener('focus', wake)
    }
  }, [refreshGrant])

  async function toggleExpanded() {
    const next = !expanded
    await call('present_grant_window', { expanded: next })
    setExpanded(next)
    setVisible(true)
  }

  async function resolve(approved: boolean) {
    if (!grant || busy) return
    setBusy(true)
    setVisible(false)
    await new Promise(resolveDelay => window.setTimeout(resolveDelay, 145))
    try { await call('resolve_grant', { id: grant.id, approved }) }
    finally {
      activeId.current = null
      setGrant(null)
      setBusy(false)
      await getCurrentWindow().hide()
    }
  }

  if (!grant) return <div className="grant-shell" />
  const hostName = activeUrl ? displayHost(activeUrl) : 'Connected host'
  const scope = formatGrantScope(grant.scope)
  const action = grantAction(grant.scope)
  const minutes = Math.max(1, Math.round(grant.duration_seconds / 60))
  const grantCategory = grant.mode.split('.')[0]
  const grantPresentation = grantCategory === 'usb'
    ? { eyebrow: 'USB access request', title: 'Raw USB access', summary: 'wants to run one operation on a connected USB device.' }
    : grantCategory === 'commands'
      ? { eyebrow: 'Command execution request', title: 'Run command', summary: 'wants to run one command on this PC.' }
      : grantCategory === 'files'
        ? { eyebrow: 'File access request', title: 'Access files', summary: 'wants to access files on this PC.' }
        : grantCategory === 'screen_input'
          ? { eyebrow: 'Screen & input request', title: 'Control access', summary: `wants to view or control this PC for up to ${minutes} min.` }
          : { eyebrow: 'Remote access request', title: `${grant.mode} access`, summary: `wants to use this PC for up to ${minutes} min.` }

  return <div className={`grant-shell ${visible ? 'window-visible' : ''} ${expanded ? 'expanded' : ''}`}>
    <section className="grant-card" role="dialog" aria-modal="true" aria-labelledby="grant-title">
      <div className="grant-head">
        <span className="grant-icon"><ShieldCheck /></span>
        <span><small>{grantPresentation.eyebrow}</small><strong id="grant-title">{grantPresentation.title}</strong></span>
        <button className="grant-expand" aria-expanded={expanded} aria-label={expanded ? 'Hide request details' : 'Show request details'} onClick={toggleExpanded}><ChevronDown /></button>
      </div>
      <p className="grant-summary"><strong>{hostName}</strong> {grantPresentation.summary}</p>
      {action && <div className="grant-action-preview"><small>Requested action · {action.label}</small><code>{action.preview}</code></div>}
      <div className="grant-details" aria-hidden={!expanded}>
        <dl>{action && <div className="grant-action-full"><dt>Action preview</dt><dd><pre>{action.preview}</pre></dd></div>}<div><dt>Reason</dt><dd>{grant.reason || 'No reason provided'}</dd></div>{scope && <div><dt>Scope</dt><dd>{scope}</dd></div>}</dl>
      </div>
      <div className="grant-open-ui"><button disabled={busy} onClick={() => call('open_management_from_grant')}><ExternalLink /> Open in UI</button></div>
      <div className="grant-actions"><button disabled={busy} onClick={() => resolve(false)}>Reject</button><button disabled={busy} onClick={() => resolve(true)}>Approve</button></div>
    </section>
  </div>
}

function ActivityList({ entries, host, onOpen }: { entries: Activity[]; host: Host | null; onOpen?: (entry: Activity) => void }) {
  if (!entries.length) return <div className="empty-state"><Clock3 /><span>No remote activity yet</span></div>
  return <div className="activity-list">{entries.map((entry, i) => {
    const category = activityCategory(entry)
    const attention = needsAttention(entry)
    const warning = isNonZeroExit(entry)
    const key = entry.request_id ?? `${entry.ts}-${i}`
    const Icon = category === 'command' ? TerminalSquare : category === 'files' ? FileText : category === 'screen' ? Eye : category === 'input' ? MousePointer2 : category === 'devices' ? Usb : category === 'system' ? LogOut : ActivityIcon
    const computerControl = isComputerControl(entry)
    const target = entry.target_app ?? entry.target_title
    const detail = entry.error ?? (computerControl ? `${entry.backend === 'cua' ? 'CUA' : 'Compatibility'} · ${entry.dispatch ?? 'background'}${target ? ` · ${target}` : ''}` : entry.summary) ?? (entry.aborted ? 'Request aborted' : 'Completed')
    const eventHost = entry.host_url ? displayHost(entry.host_url) : host?.name ?? 'Local daemon'
    return <article className="activity-item" key={key}>
      <button className="activity-row" disabled={!onOpen} onClick={() => onOpen?.(entry)}>
        <span className={`activity-icon ${attention || warning ? 'amber' : category === 'system' ? 'green' : 'violet'}`}><Icon /></span>
        <span className="activity-copy"><strong>{computerControl ? controlActionLabel(entry) : activityName(entry.tool)}</strong><small className={attention ? 'attention' : warning ? 'warning' : ''}>{detail} · {eventHost}</small></span>
        <span className="activity-tail"><time>{formatTime(entry.ts)}</time>{onOpen && <ChevronRight />}</span>
      </button>
    </article>
  })}</div>
}

function ActivityPanel({ entries, host, onClear, onOpen }: { entries: Activity[]; host: Host | null; onClear: () => void; onOpen: (entry: Activity) => void }) {
  const [filter, setFilter] = useState<ActivityFilter>('all')
  const newest = entries.slice().reverse()
  const attention = newest.filter(needsAttention).length
  const warnings = newest.filter(isNonZeroExit).length
  const visible = newest.filter(entry => filter === 'all' || (filter === 'attention' ? needsAttention(entry) : filter === 'warning' ? isNonZeroExit(entry) : activityCategory(entry) === filter))
  const filters: Array<{ value: ActivityFilter; label: string }> = [
    { value: 'all', label: 'All' }, { value: 'command', label: 'Commands' },
    { value: 'files', label: 'Files' }, { value: 'screen', label: 'Screen' },
    { value: 'input', label: 'Input' }, { value: 'devices', label: 'Devices' }, { value: 'system', label: 'System' },
    { value: 'warning', label: 'Non-zero' }, { value: 'attention', label: 'Issues' }
  ]
  return <div className="activity-panel">
    <div className="activity-summary"><span><ActivityIcon /><strong>{entries.length}</strong><small>Recent events</small></span><span className={attention ? 'has-attention' : ''}><AlertTriangle /><strong>{attention}</strong><small>Issues</small></span><em><i /> Live</em></div>
    <div className="activity-toolbar"><div className="activity-filters" aria-label="Filter activity">{filters.map(item => <button key={item.value} className={filter === item.value ? 'active' : ''} onClick={() => setFilter(item.value)}>{item.label}{item.value === 'attention' && attention > 0 ? ` ${attention}` : item.value === 'warning' && warnings > 0 ? ` ${warnings}` : ''}</button>)}</div><button className="clear-activity" disabled={!entries.length} onClick={onClear}><Trash2 /> Clear</button></div>
    <div className="settings-card activity-card"><ActivityList entries={visible} host={host} onOpen={onOpen} /></div>
  </div>
}

function ActivityPage({ entries, host, onBack, onClear, onOpen }: { entries: Activity[]; host: Host | null; onBack: () => void; onClear: () => void; onOpen: (entry: Activity) => void }) {
  return <section className="page-panel activity-page">
    <div className="page-title activity-page-title"><button className="back-button" onClick={onBack}><ArrowLeft /> Back to Settings</button><div><p>Local audit</p><h1>Activity</h1></div></div>
    <p className="page-intro">Remote actions and management changes recorded on this PC.</p>
    <ActivityPanel entries={entries} host={host} onClear={onClear} onOpen={onOpen} />
  </section>
}

function ActivityDetailPage({ entry, host, onBack }: { entry: Activity | null; host: Host | null; onBack: () => void }) {
  const [evidenceError, setEvidenceError] = useState<string | null>(null)
  if (!entry) return <section className="page-panel"><button className="back-button" onClick={onBack}><ArrowLeft /> Back to Activity</button><div className="large-empty"><ActivityIcon /><h2>Event unavailable</h2></div></section>
  const attention = needsAttention(entry)
  const warning = isNonZeroExit(entry)
  const status = entry.aborted ? 'Aborted' : !entry.ok ? 'Failed' : warning ? `Exit ${activityExitCode(entry)}` : 'Completed'
  const eventHost = entry.host_url ? displayHost(entry.host_url) : host?.name ?? 'Local daemon'
  const computerControl = isComputerControl(entry)
  const steps = activitySteps(entry)
  const blocks = [
    ['Request', entry.request_detail ?? entry.args_preview, entry.request_truncated],
    ['Standard output', entry.stdout, entry.stdout_truncated],
    ['Standard error', entry.stderr, entry.stderr_truncated],
    ['Result', entry.result_detail, entry.result_truncated]
  ] as const
  return <section className="page-panel activity-detail-page">
    <button className="back-button" onClick={onBack}><ArrowLeft /> Back to Activity</button>
    <div className="activity-detail-title"><span className={`activity-icon ${attention || warning ? 'amber' : 'violet'}`}>{computerControl ? <MousePointerClick /> : <TerminalSquare />}</span><span><p>{computerControl ? 'Computer control' : activityCategory(entry)}</p><h1>{computerControl ? controlActionLabel(entry) : activityName(entry.tool)}</h1><small>{eventHost}</small></span></div>
    <dl className="activity-detail-meta"><div><dt>Status</dt><dd className={attention ? 'attention' : warning ? 'warning' : 'success'}>{status}</dd></div><div><dt>When</dt><dd>{formatDateTime(entry.ts)}</dd></div><div><dt>Duration</dt><dd>{formatDuration(entry.duration_ms)}</dd></div></dl>
    <section className="control-timeline" aria-label="Event timeline">{steps.map((step, index) => <div className={step.state} key={`${step.title}-${index}`}><i /><span><strong>{step.title}</strong><small>{step.detail}</small></span></div>)}</section>
    {computerControl && (entry.target_app || entry.target_title || entry.target_pid || entry.target_window_id) && <dl className="control-target"><div><dt>Application</dt><dd>{entry.target_app ?? 'Not reported'}</dd></div><div><dt>Window</dt><dd title={entry.target_title}>{entry.target_title ?? 'Not reported'}</dd></div><div><dt>Target</dt><dd>{entry.target_pid ? `PID ${entry.target_pid}` : 'PID —'} · {entry.target_window_id ? `Window ${entry.target_window_id}` : 'Window —'}</dd></div></dl>}
    {entry.error && <aside className="activity-error-callout" role="alert"><AlertTriangle /><span><strong>{entry.aborted ? 'Action stopped' : 'Action failed'}</strong><small>{entry.error}</small></span></aside>}
    {entry.screenshot_evidence_id && <button className="screenshot-evidence-card" onClick={() => { setEvidenceError(null); void call('present_activity_screenshot', { evidenceId: entry.screenshot_evidence_id }).catch(error => setEvidenceError(String(error))) }}><span><Eye /><strong>Screenshot captured</strong><small>{entry.screenshot_width && entry.screenshot_height ? `${entry.screenshot_width} × ${entry.screenshot_height} PNG` : 'Retained local evidence'}</small></span><em><Maximize2 /> View larger</em></button>}
    {evidenceError && <aside className="activity-error-callout"><AlertTriangle /><span><strong>Screenshot unavailable</strong><small>{evidenceError}</small></span></aside>}
    <div className="activity-output-list">{blocks.filter(([, value]) => value).map(([label, value, truncated]) => <section key={label}><header><strong>{label}</strong>{truncated && <em>Truncated</em>}</header><pre>{value}</pre></section>)}</div>
    {entry.request_id && <div className="activity-request-id">Request ID {entry.request_id}</div>}
  </section>
}

function AccessPage({ host, busy, onBack, onChoose }: { host: Host | null; busy: boolean; onBack: () => void; onChoose: (mode: AccessMode) => void }) {
  if (!host) return <div className="page-panel large-empty"><LockKeyhole /><h2>No host selected</h2><button onClick={onBack}>Back to Overview</button></div>
  const customPolicy = host.access_mode === 'custom' || host.access_mode === 'trusted'
  const options: Array<{ mode: AccessMode; Icon: typeof CircleHelp }> = [
    { mode: 'ask', Icon: LockKeyhole },
    { mode: 'ask-every-time', Icon: CircleHelp },
    { mode: 'structured', Icon: ShieldCheck },
    { mode: 'full-access', Icon: Monitor }
  ]
  return <section className="page-panel policy-detail-page">
    <div className="page-title policy-page-title"><div><button className="back-button" onClick={onBack}><ArrowLeft /> Back to Overview</button><p>{host.name}</p><h1>Host access</h1></div></div>
    <p className="page-intro">Choose the broadest kind of work this Hermes host may perform on this PC.</p>
    {customPolicy && <div className="custom-policy-banner"><SlidersHorizontal /><span><strong>Custom policy</strong><small>Individual capabilities differ from the standard presets.</small></span></div>}
    <div className="access-options" role="radiogroup" aria-label="Host access">
      {options.map(({ mode, Icon }) => <button key={mode} role="radio" aria-checked={host.access_mode === mode} className={host.access_mode === mode ? 'active' : ''} disabled={busy} onClick={() => onChoose(mode)}>
        <span className="option-icon"><Icon /></span><span><strong>{accessLabel[mode]}</strong><small>{accessCopy[mode]}</small></span>{host.access_mode === mode ? <span className="selected-check"><Check /></span> : <ChevronRight />}
      </button>)}
    </div>
    {customPolicy && <p className="policy-footnote"><SlidersHorizontal />Custom reflects individual capability choices. Selecting a preset replaces them.</p>}
  </section>
}

function CapabilitiesPage({ host, availability, busy, onBack, onChoose }: { host: Host | null; availability: Snapshot['hardware_availability']; busy: boolean; onBack: () => void; onChoose: (capability: Capability, mode: CapabilityMode) => void }) {
  if (!host) return <div className="page-panel large-empty"><SlidersHorizontal /><h2>No host selected</h2><button onClick={onBack}>Back to Overview</button></div>
  return <section className="page-panel policy-detail-page">
    <div className="page-title policy-page-title"><div><button className="back-button" onClick={onBack}><ArrowLeft /> Back to Overview</button><p>{host.name}</p><h1>Capabilities</h1></div></div>
    <p className="page-intro">Presets set these together. Capability changes select a matching preset automatically; other combinations become Custom.</p>
    <div className="capability-list">
      <CapabilityRow capability="commands" title="Command execution" copy="PowerShell, terminal and process launch" icon={<TerminalSquare />} modes={['disabled', 'ask', 'allow']} host={host} busy={busy} onChoose={onChoose} />
      <CapabilityRow capability="files" title="Files" copy="Read, write, search and transfer" icon={<FileText />} modes={['disabled', 'ask', 'allow']} host={host} busy={busy} onChoose={onChoose} />
      <CapabilityRow capability="screen_input" title="Screen & input" copy="Screenshots, clipboard, mouse and keyboard" icon={<MousePointer2 />} modes={['disabled', 'ask', 'allow']} host={host} busy={busy} onChoose={onChoose} />
      <CapabilityRow capability="usb" title="Raw USB" copy="Native utilities and enabled USB services" icon={<Usb />} modes={['disabled', 'ask', 'allow']} host={host} busy={busy || !availability.usb} onChoose={onChoose} />
    </div>
    <div className="supported-broker"><span><i className={availability.adb ? '' : 'offline'} /><strong>Android Debug Bridge</strong></span><small>Secondary USB service</small><em>{availability.adb ? 'Available' : 'Unavailable'}</em></div>
    <div className="unavailable-capabilities">
      <div><span className="option-icon"><Mic /></span><span><strong>Microphone</strong><small>A bounded broker is not available yet</small></span><em>Unavailable</em></div>
      <div><span className="option-icon"><Video /></span><span><strong>Camera</strong><small>A bounded broker is not available yet</small></span><em>Unavailable</em></div>
    </div>
    <p className="policy-footnote"><LockKeyhole />Full Access includes every available capability. Unavailable brokers still cannot be advertised.</p>
  </section>
}

function CapabilityRow({ capability, title, copy, icon, modes, host, busy, onChoose }: { capability: Capability; title: string; copy: string; icon: ReactNode; modes: CapabilityMode[]; host: Host; busy: boolean; onChoose: (capability: Capability, mode: CapabilityMode) => void }) {
  return <section className="capability-row"><div className="capability-row-head"><span className="option-icon">{icon}</span><span><strong>{title}</strong><small>{copy}</small></span>{host.access_mode === 'full-access' && <em>Included</em>}</div><div className="capability-modes" role="radiogroup" aria-label={`${title} access`}>{modes.map(mode => <button key={mode} disabled={busy} role="radio" aria-checked={host.capabilities[capability] === mode} className={host.capabilities[capability] === mode ? 'active' : ''} onClick={() => onChoose(capability, mode)}>{capabilityLabel[mode]}</button>)}</div></section>
}

function PairHostPage({ initialUrl, busy, onBack, onPair }: { initialUrl: string; busy: boolean; onBack: () => void; onPair: (remote: string, code: string) => Promise<boolean> }) {
  const [remote, setRemote] = useState(initialUrl)
  const [code, setCode] = useState('')
  const normalizedCode = code.replace(/[^a-z0-9]/gi, '').toUpperCase().slice(0, 6)
  const validUrl = (() => { try { const url = new URL(remote.trim()); return ['ws:', 'wss:'].includes(url.protocol) && !url.username && !url.password } catch { return false } })()
  const security = describeTransportSecurity(remote)
  const canSubmit = validUrl && normalizedCode.length === 6 && !busy

  return <section className="page-panel pair-host-page">
    <button className="back-button" onClick={onBack}><ArrowLeft /> Back to Hosts</button>
    <div className="page-title"><div><p>New connection</p><h1>{initialUrl ? 'Re-pair host' : 'Pair host'}</h1></div></div>
    <p className="page-intro">Enter the relay address and the six-character code shown by Hermes.</p>
    <div className="secure-link-setup"><ShieldCheck /><span><strong>Hermes Secure Link ready</strong><small>When the pairing invite advertises Secure Link, the CLI prefers its pinned, encrypted route automatically and keeps private-network routes as fallback.</small></span></div>
    <form className="pair-form" onSubmit={async event => { event.preventDefault(); if (canSubmit) await onPair(remote.trim(), normalizedCode) }}>
      <label><span>Relay URL</span><div className="pair-input-action"><input aria-label="Relay URL" autoCapitalize="none" autoCorrect="off" spellCheck={false} placeholder="wss://relay.example.com" value={remote} onChange={event => setRemote(event.target.value)} /><button type="button" title={remote ? 'Copy relay URL' : 'Paste relay URL'} aria-label={remote ? 'Copy relay URL' : 'Paste relay URL'} onClick={async () => { if (remote) await navigator.clipboard.writeText(remote.trim()); else setRemote(await navigator.clipboard.readText()) }}><Copy /></button></div></label>
      {validUrl && <div className={`transport-notice ${security.encrypted ? 'secure' : 'insecure'}`}>{security.encrypted ? <ShieldCheck /> : <AlertTriangle />}<span><strong>{security.label}</strong><small>{security.detail}</small></span></div>}
      <label><span>Pairing code</span><input className="pair-code" aria-label="Pairing code" autoComplete="one-time-code" inputMode="text" maxLength={6} placeholder="ABC123" value={normalizedCode} onChange={event => setCode(event.target.value)} /></label>
      <p className="pair-privacy"><LockKeyhole />The code is passed directly to the local CLI and is not stored by the UI.</p>
      <button className="primary-host-action" type="submit" disabled={!canSubmit}>{busy ? <LoaderCircle className="spin" /> : <Link2 />}{busy ? 'Pairing…' : initialUrl ? 'Re-pair host' : 'Pair host'}</button>
    </form>
  </section>
}

function HostsPage({ hosts, selected, onOpen, onPair }: { hosts: Host[]; selected: Host | null; onOpen: (url: string) => void; onPair: () => void }) {
  return <section className="page-panel"><div className="page-title"><div><p>Connections</p><h1>Hosts</h1></div><button className="icon-button" onClick={onPair} aria-label="Pair host"><Link2 /></button></div>
    <p className="page-intro">Hermes instances and relays this PC trusts. Access settings apply independently to each host.</p>
    <div className="host-list">{hosts.map(host => <button key={host.url} className={`host-card ${selected?.url === host.url ? 'selected' : ''}`} onClick={() => onOpen(host.url)}><span className="host-icon"><Server /></span><span><strong>{host.name}</strong><small>{host.url}</small><em><i />{host.is_active ? 'Active host' : 'Paired'} · {host.access_mode === 'full-access' ? 'Full Access' : host.access_mode[0]!.toUpperCase() + host.access_mode.slice(1)}</em></span><ChevronRight /></button>)}</div>
    {!hosts.length && <div className="large-empty"><Link2 /><h2>No hosts paired</h2><p>Pair this PC with a Hermes instance to begin.</p><button onClick={onPair}>Pair host</button></div>}
  </section>
}

function HostDetailPage({ host, clients, busy, onBack, onConnect, onRename, onAccess, onCapabilities, onRevoke, onRepair, onForget }: { host: Host | null; clients: AuthorizedClient[]; busy: boolean; onBack: () => void; onConnect: (url: string) => void; onRename: (url: string, name: string) => Promise<unknown>; onAccess: () => void; onCapabilities: () => void; onRevoke: (remote: string, client: AuthorizedClient) => void; onRepair: (host: Host) => void; onForget: (host: Host) => void }) {
  const [name, setName] = useState(host?.name ?? '')
  useEffect(() => setName(host?.name ?? ''), [host?.url, host?.name])
  if (!host) return <section className="page-panel"><button className="back-button" onClick={onBack}><ArrowLeft /> Back to Hosts</button><div className="large-empty"><Server /><h2>Host unavailable</h2><p>This pairing may have been removed.</p></div></section>
  const changed = name.trim() !== host.name && name.trim().length > 0
  return <section className="page-panel host-detail-page">
    <button className="back-button" onClick={onBack}><ArrowLeft /> Back to Hosts</button>
    <div className="host-detail-hero"><span className="host-icon"><Server /></span><span><p>{host.is_active ? 'Active host' : 'Paired host'}</p><h1>{host.name}</h1><small>{host.url}</small></span><button className="copy-host-url" aria-label="Copy relay URL" title="Copy relay URL" onClick={() => navigator.clipboard.writeText(host.url)}><Copy /></button></div>
    <div className="settings-group"><h2>Display name</h2><div className="rename-host"><input value={name} maxLength={64} aria-label="Host display name" onChange={event => setName(event.target.value)} /><button disabled={!changed || busy} onClick={() => onRename(host.url, name.trim())}>Save</button></div><p className="group-help host-name-help">Stored locally on this PC. It does not rename the Hermes server.</p></div>
    <div className="settings-group"><h2>Connection</h2><div className="settings-card host-detail"><dl><div><dt>Active route</dt><dd>{displayRouteLabel(host.endpoint_role ?? inferEndpointRole(host.url))}</dd></div><div><dt>Relay</dt><dd>{host.server_version ?? 'Unknown'}</dd></div><div><dt>Paired</dt><dd>{formatPairedAt(host.paired_at)}</dd></div></dl></div><p className="group-help">Tailscale is recommended for remote access. A public TLS domain or Direct Secure Link can provide a fully self-hosted alternative.</p></div>
    <div className="settings-group"><h2>Recommended remote access</h2><div className="settings-card broker-summary configured"><span className="setting-icon"><Radio /></span><span><strong>Tailscale</strong><small>Private reachability, managed TLS, and no inbound port forwarding. Install Tailscale on this PC and the Hermes host, then re-pair.</small></span><em>Recommended</em></div><p className="group-help">For a fully self-hosted public route, point a domain at the host and use a trusted TLS reverse proxy or Direct Secure Link.</p></div>
    {host.broker_configured && <div className="settings-group experimental-group"><h2>Experimental</h2><div className="settings-card broker-summary"><span className="setting-icon"><Radio /></span><span><strong>Hermes Reach</strong><small>Brokered fallback saved for evaluation. It is tried after supported direct, Tailscale, public TLS, and Secure Link routes.</small></span><em>Experimental</em></div><p className="group-help">Reach is not recommended for normal setup and may change before release.</p></div>}
    <div className="settings-group"><h2>Remote access</h2><div className="settings-card management-links">
      <button onClick={onAccess}><span className="setting-icon"><LockKeyhole /></span><span><strong>Desktop access</strong><small>Choose how this host requests control.</small></span><em>{hostAccessLabel(host)}</em><ChevronRight /></button>
      <button onClick={onCapabilities}><span className="setting-icon"><SlidersHorizontal /></span><span><strong>Capabilities</strong><small>Commands, files, screen, input and hardware.</small></span><em>{host.access_mode === 'full-access' ? 'All allow' : 'Review'}</em><ChevronRight /></button>
    </div></div>
    <div className="settings-group"><h2>Authorized clients <span>{clients.length}</span></h2><p className="group-help">Sessions authenticated to this relay.</p><div className="settings-card client-list">{clients.length ? clients.map(client => { const identity = [client.device_model, client.device_platform, client.client_surface].filter(Boolean).join(' · ') || client.transport_hint || 'Client'; return <div className="client-row" key={client.token_prefix}><span className="client-icon"><Laptop /></span><span><strong>{client.device_name ?? 'Unnamed client'} {client.is_current && <em>This PC</em>}</strong><small>{identity} · {age(client.last_seen)}</small></span><button onClick={() => onRevoke(host.url, client)} aria-label={`Deauthorize ${client.device_name ?? client.token_prefix}`}><UserRoundX /></button></div> }) : <div className="empty-state"><Radio /><span>No authorized clients reported</span></div>}</div></div>
    <button className="primary-host-action" disabled={host.is_active || busy} onClick={() => onConnect(host.url)}><Power />{host.is_active ? 'Currently connected host' : 'Connect to this host'}</button>
    <div className="host-danger-actions"><button disabled={busy} onClick={() => onRepair(host)}><RefreshCw /> Re-pair host</button><button disabled={busy} onClick={() => onForget(host)}><Trash2 /> Forget host</button></div>
  </section>
}

function SettingsPage({ daemon, computerControl, startup, daemonAutostart, activity, screenshotRetention, onAction, onStartup, onDaemonAutostart, onHelp, onViewActivity, onOpenActivity }: { daemon: Snapshot['daemon']; computerControl: Snapshot['computer_control_engine']; startup: boolean; daemonAutostart: boolean; activity: Activity[]; screenshotRetention: Snapshot['activity_screenshot_retention']; onAction: (name: string, args?: Record<string, unknown>) => Promise<unknown>; onStartup: (value: boolean) => void; onDaemonAutostart: (value: boolean) => void; onHelp: () => void; onViewActivity: () => void; onOpenActivity: (entry: Activity) => void }) {
  const [update, setUpdate] = useState<UpdateReport | null>(null)
  const [updateBusy, setUpdateBusy] = useState<'check' | 'install' | null>(null)
  const [updateError, setUpdateError] = useState<string | null>(null)
  const [cuaManagement, setCuaManagement] = useState<CuaManagementStatus | null>(null)
  const [cuaHealth, setCuaHealth] = useState<CuaHealthStatus | null>(null)
  const [cuaBusy, setCuaBusy] = useState<'status' | 'health' | 'install' | 'check' | 'update' | null>(null)
  const [cuaError, setCuaError] = useState<string | null>(null)

  const cuaOperation = useCallback(async (operation: 'status' | 'install' | 'check' | 'update') => {
    setCuaBusy(operation)
    try {
      setCuaManagement(await call<CuaManagementStatus>(operation === 'status' ? 'computer_cua_status' : operation === 'install' ? 'computer_cua_install' : operation === 'check' ? 'computer_cua_check_update' : 'computer_cua_update'))
      setCuaError(null)
    } catch (error) { setCuaError(String(error).replace(/^Error:\s*/i, '')) }
    finally { setCuaBusy(null) }
  }, [])

  const recheckCuaHealth = useCallback(async () => {
    setCuaBusy('health')
    try {
      setCuaHealth(await call<CuaHealthStatus>('computer_cua_health'))
      setCuaError(null)
    } catch (error) { setCuaError(String(error).replace(/^Error:\s*/i, '')) }
    finally { setCuaBusy(null) }
  }, [])

  const checkUpdate = useCallback(async () => {
    setUpdateBusy('check')
    try { setUpdate(await call<UpdateReport>('check_desktop_update')); setUpdateError(null) }
    catch (error) { setUpdateError(friendlyUpdateError(error)) }
    finally { setUpdateBusy(null) }
  }, [])

  const installUpdate = useCallback(async () => {
    setUpdateBusy('install')
    try { setUpdate(await call<UpdateReport>('install_desktop_update')); setUpdateError(null) }
    catch (error) { setUpdateError(friendlyUpdateError(error)) }
    finally { setUpdateBusy(null) }
  }, [])

  useEffect(() => { void checkUpdate() }, [checkUpdate])
  useEffect(() => { void cuaOperation('status') }, [cuaOperation])

  const updateSummary = updateError
    ? updateError
    : update?.installed
      ? `Installing ${update.latest_version ?? 'the latest release'}…`
      : update?.ahead_of_latest
        ? `Version ${update.current} · ahead of published ${update.latest_version ?? 'release'}`
      : update?.up_to_date
        ? `Version ${update.current} · up to date`
        : update?.latest_version
          ? `${update.current} → ${update.latest_version} available`
          : 'Check the desktop release channel.'

  const cuaReady = computerControl?.available === true && computerControl.state === 'ready'
  const engineState = computerControl?.state ?? 'not_installed'
  const engineLabel = engineState === 'not_installed' ? 'Not installed' : engineState === 'incompatible' ? 'Incompatible' : engineState === 'degraded' ? 'Degraded' : engineState === 'ready' ? 'Ready' : 'Unavailable'
  const engineDetail = computerControl?.message
    ?? (engineState === 'not_installed' ? 'CUA Driver is not installed. Windows input remains available for compatibility.'
      : engineState === 'incompatible' ? 'The installed CUA Driver version is not compatible with this CLI.'
        : engineState === 'degraded' ? 'CUA Driver reported a health problem. Windows compatibility input remains active.'
          : engineState === 'ready' ? `CUA Driver ${computerControl?.version ?? ''} is compatible and healthy.`.trim()
            : 'CUA Driver status could not be verified. Hermes uses the safe fallback.')
  const effectiveEngine = computerControl?.effective === 'cua' ? 'CUA Driver' : 'Windows input'
  const engineRole = computerControl?.effective === 'cua' ? 'Preferred structured engine' : 'Compatibility'
  const activeSessions = computerControl?.active_sessions ?? 0
  const activeBackend = computerControl?.active_backend === 'cua' ? 'CUA active'
    : computerControl?.active_backend === 'legacy_compat' ? 'Compatibility active'
      : computerControl?.active_backend === 'mixed' ? 'Mixed backends' : 'Idle'
  const healthLabel = cuaHealth?.state === 'healthy' ? 'Healthy' : cuaHealth?.state === 'degraded' ? 'Degraded' : cuaHealth?.state === 'error' ? 'Check failed' : 'Not checked'
  const healthDetail = cuaHealth?.reason
    ?? (cuaHealth?.state === 'healthy'
      ? 'The latest explicit accessibility check passed.'
      : 'Optional diagnostic; it does not disable the runtime while the temporary Windows workaround is active.')

  return <section className="page-panel settings-page"><div className="page-title"><div><p>Local management</p><h1>Settings</h1></div></div>
    <div className="settings-group"><h2>Relay daemon</h2><div className="settings-card"><div className="setting-row"><span><strong>Daemon status</strong><small>{daemon.running ? `${daemon.state} · ${daemon.privilege ?? 'user'}` : 'Stopped'}</small></span><button className="compact-button" onClick={() => onAction('restart_daemon')}><RefreshCw /> Restart</button></div><div className="setting-row"><span><strong>{daemon.privilege === 'administrator' ? 'Administrator mode' : 'User mode'}</strong><small>{daemon.privilege === 'administrator' ? 'Remote actions inherit elevated rights.' : 'Recommended for normal operation.'}</small></span><button className={`compact-button privilege-action ${daemon.privilege === 'administrator' ? '' : 'admin-action'}`} onClick={() => onAction(daemon.privilege === 'administrator' ? 'restart_daemon_as_user' : 'restart_daemon_as_administrator')}>{daemon.privilege === 'administrator' ? 'Return to user mode' : 'Restart as Administrator…'}</button></div><label className="setting-row toggle-row"><span><strong>Start UI at sign-in</strong><small>Launch the tray after you sign in.</small></span><input type="checkbox" checked={startup} onChange={e => onStartup(e.target.checked)} /><i /></label><label className="setting-row toggle-row"><span><strong>Start daemon with UI</strong><small>Connect remote access when the tray starts.</small></span><input type="checkbox" checked={daemonAutostart} onChange={e => onDaemonAutostart(e.target.checked)} /><i /></label></div></div>
    <div className="settings-group"><h2>Computer control</h2><div className={`settings-card engine-card engine-${engineState}`}>
      <div className="engine-status"><span className="setting-icon"><MousePointerClick /></span><span><strong>{effectiveEngine}</strong><small>{engineRole} · {engineDetail}</small></span><em>{engineLabel}</em></div>
      {cuaReady && <div className="engine-options">
        <div className="engine-choice"><span><strong>Control engine</strong><small>CUA is preferred; Windows input is the compatibility fallback.</small></span><div role="radiogroup" aria-label="Computer control engine"><button role="radio" aria-checked={computerControl?.selected === 'cua'} className={computerControl?.selected === 'cua' ? 'active' : ''} onClick={() => onAction('set_computer_control_engine', { engine: 'cua' })}>CUA</button><button role="radio" aria-checked={computerControl?.selected !== 'cua'} className={computerControl?.selected !== 'cua' ? 'active' : ''} onClick={() => onAction('set_computer_control_engine', { engine: 'legacy' })}>Compatibility</button></div></div>
        <div className="engine-live"><span><strong>{activeSessions}</strong><small>Active session{activeSessions === 1 ? '' : 's'}</small></span><em className={activeSessions ? 'active' : ''}><i /> {activeBackend}</em></div>
        <label className="setting-row toggle-row"><span><strong>Animated agent cursor</strong><small>Labeled · smooth glide · click pulse. It does not move your physical mouse.</small></span><input type="checkbox" disabled={computerControl?.selected !== 'cua'} checked={computerControl?.selected === 'cua' && computerControl.cursor_enabled === true} onChange={e => onAction('set_cua_cursor_enabled', { enabled: e.target.checked })} /><i /></label>
        <div className="setting-row background-only"><span><strong>Window interaction</strong><small>CUA actions stay in the background and never bring an app forward.</small></span><em>Background only</em></div>
        <div className="setting-row cua-health"><span><strong>Accessibility health</strong><small>{healthDetail}</small></span><div><em className={`health-${cuaHealth?.state ?? 'unchecked'}`}>{healthLabel}</em><button className="compact-button" disabled={cuaBusy !== null} onClick={() => void recheckCuaHealth()}>{cuaBusy === 'health' ? <LoaderCircle className="spin" /> : <RefreshCw />} Recheck</button></div></div>
      </div>}
      <div className="cua-maintenance"><span><strong>{cuaManagement?.installed ? `CUA Driver ${cuaManagement.current_version ?? ''}`.trim() : 'CUA Driver'}</strong><small>{cuaError ?? cuaManagement?.update?.error ?? cuaManagement?.compatibility_reason ?? (cuaManagement?.update?.update_available ? `${cuaManagement.update.latest_version} available` : cuaManagement?.installed ? 'Installed from the verified upstream release.' : 'Install the verified compatible driver explicitly.')}</small></span><div>{!cuaManagement?.installed ? <button disabled={cuaBusy !== null} onClick={() => void cuaOperation('install')}>{cuaBusy === 'install' ? <LoaderCircle className="spin" /> : <Download />} Install</button> : cuaManagement.update?.update_available && cuaManagement.update.compatible ? <button disabled={cuaBusy !== null} onClick={() => void cuaOperation('update')}>{cuaBusy === 'update' ? <LoaderCircle className="spin" /> : <Download />} Update</button> : <button disabled={cuaBusy !== null} onClick={() => void cuaOperation('check')}>{cuaBusy === 'check' || cuaBusy === 'status' ? <LoaderCircle className="spin" /> : <RefreshCw />} Check</button>}</div></div>
    </div><p className="group-help engine-help"><ShieldCheck /> CUA is the preferred structured engine. Hermes permissions, grants, audit, and emergency stop remain in control.</p></div>
    <div className="settings-group"><h2>CLI & diagnostics</h2><div className="settings-card quick-action-grid"><button onClick={() => onAction('open_terminal')}><TerminalSquare /><span>Open terminal</span></button><button onClick={() => onAction('open_cli_terminal')}><Bot /><span>Open Hermes CLI</span></button><button onClick={() => onAction('open_logs')}><FolderOpen /><span>View daemon log</span></button><button onClick={() => onAction('open_tray_logs')}><FolderOpen /><span>View UI log</span></button><button onClick={() => onAction('run_diagnostics')}><ActivityIcon /><span>Run diagnostics</span></button></div></div>
    <div className="settings-group"><h2>Updates</h2><div className={`settings-card update-card ${updateError ? 'error' : update?.ahead_of_latest ? 'ahead' : update?.up_to_date ? 'current' : ''}`}><div className="setting-row update-row"><span><strong>Hermes-Relay CLI UI</strong><small>{updateSummary}</small></span>{update && !update.up_to_date && !update.ahead_of_latest && !update.installed ? <button className="compact-button update-button" disabled={updateBusy !== null} onClick={installUpdate}>{updateBusy === 'install' ? <LoaderCircle className="spin" /> : <Download />} Install</button> : <button className="compact-button" disabled={updateBusy !== null} onClick={checkUpdate}>{updateBusy === 'check' ? <LoaderCircle className="spin" /> : <RefreshCw />} Check</button>}</div></div><p className="group-help update-help">Updates the management UI and CLI together, then restarts the tray automatically.</p></div>
    <button className="about-link" onClick={onHelp}><span className="setting-icon"><Info /></span><span><strong>Help & About</strong><small>Versions, documentation and troubleshooting.</small></span><ChevronRight /></button>
    <div className="settings-group"><div className="settings-group-heading"><h2>Activity</h2><button onClick={onViewActivity}>View all <ChevronRight /></button></div><p className="group-help">Recent remote actions recorded on this PC.</p><div className="settings-card activity-retention-card"><div className="setting-row"><span><strong>Screenshot evidence</strong><small>{screenshotRetention.count} retained · {formatBytes(screenshotRetention.bytes)} · stored only on this PC</small></span><div className="retention-options" role="radiogroup" aria-label="Screenshot evidence retention">{([{ label: 'Off', enabled: false, days: 7 }, { label: '1d', enabled: true, days: 1 }, { label: '7d', enabled: true, days: 7 }, { label: '30d', enabled: true, days: 30 }] as const).map(option => <button key={option.label} role="radio" aria-checked={screenshotRetention.enabled === option.enabled && (!option.enabled || screenshotRetention.days === option.days)} className={screenshotRetention.enabled === option.enabled && (!option.enabled || screenshotRetention.days === option.days) ? 'active' : ''} onClick={() => onAction('set_activity_screenshot_retention', { enabled: option.enabled, days: option.days })}>{option.label}</button>)}</div></div></div><div className="settings-card padded"><ActivityList entries={activity.slice(-3).reverse()} host={null} onOpen={onOpenActivity} /></div></div>
  </section>
}

function HelpPage({ snapshot, host, onBack, onAction }: { snapshot: Snapshot; host: Host | null; onBack: () => void; onAction: (name: string, args?: Record<string, unknown>) => Promise<unknown> }) {
  const links = [
    ['Documentation', 'https://hermes-relay.dev/docs/desktop/'],
    ['Troubleshooting', 'https://hermes-relay.dev/docs/desktop/troubleshooting/'],
    ['Release notes', 'https://github.com/Codename-11/hermes-relay/releases']
  ] as const
  return <section className="page-panel help-page"><button className="back-button" onClick={onBack}><ArrowLeft /> Back to Settings</button><div className="about-hero"><img src={logo} alt="" /><span><p>Desktop companion</p><h1>Hermes-Relay CLI UI</h1><small>Compact control for this PC</small></span></div>
    <div className="settings-group"><h2>About</h2><div className="settings-card about-facts"><dl><div><dt>UI version</dt><dd>{snapshot.ui_version ?? 'Unknown'}</dd></div><div><dt>CLI version</dt><dd>{snapshot.cli_version ?? 'Unknown'}</dd></div><div><dt>CLI path</dt><dd title={snapshot.cli_path ?? undefined}>{snapshot.cli_path ?? 'Not reported'}</dd></div><div><dt>Relay server</dt><dd>{host?.server_version ?? 'Not connected'}</dd></div></dl></div></div>
    <div className="settings-group"><h2>Get help</h2><div className="settings-card management-links">{links.map(([label, url]) => <button key={url} onClick={() => onAction('open_external_url', { url })}><span><strong>{label}</strong></span><ExternalLink /></button>)}</div></div>
    <div className="settings-group"><h2>Computer control</h2><div className="settings-card help-note"><ShieldCheck /><span><strong>CUA Driver is an optional control engine</strong><small>When compatible and healthy, it can use app-aware background control and separate animated agent cursors. These are visual overlays—not additional Windows hardware pointers. Foreground switching is not available; control remains background only. Full Access never bypasses targeting, sensitive-surface rules, audit, or emergency stop.</small></span></div></div>
    <button className="diagnostic-action" onClick={() => onAction('run_diagnostics')}><ActivityIcon /><span><strong>Run diagnostics</strong><small>Check the daemon, installation and active relay.</small></span><ChevronRight /></button>
  </section>
}
