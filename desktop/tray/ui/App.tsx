import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { invoke } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import {
  Activity as ActivityIcon, AlertTriangle, Check, ChevronDown, ChevronRight,
  CircleHelp, Clock3, Download, Eye, FileText, Home, Laptop, Link2,
  LoaderCircle, LogOut, Monitor, MousePointer2, Power, Radio, RefreshCw, Server,
  Settings, ShieldCheck, TerminalSquare, Trash2, Unplug, UserRoundX, X
} from 'lucide-react'
import logo from '../icons/icon-256.png'
import type { AccessMode, Activity, AuthorizedClient, Host, PendingGrantRequest, Snapshot, UpdateReport } from './types'

type Page = 'overview' | 'hosts' | 'host-detail' | 'settings' | 'activity'
type PendingAction = { type: 'access'; mode: AccessMode } | { type: 'revoke'; client: AuthorizedClient } | { type: 'clear-activity' } | null

const isGrantWindow = '__TAURI_INTERNALS__' in window && getCurrentWindow().label === 'grant'

const demo: Snapshot = {
  hosts: [{ url: 'wss://home-hermes.local:8767', name: 'Home Hermes', server_version: '0.9.0', endpoint_role: 'tailscale', paired_at: 1786458000, is_active: true, access_mode: 'trusted' }],
  active_url: 'wss://home-hermes.local:8767',
  daemon: { state: 'connected', running: true, url: 'wss://home-hermes.local:8767', privilege: 'user', username: 'Local user' },
  startup_enabled: true,
  pending_grants: [],
  activity: [
    { ts: Date.now() - 110_000, tool: 'desktop.shell', ok: true, summary: 'PowerShell command completed' },
    { ts: Date.now() - 260_000, tool: 'desktop.connect', ok: true, summary: 'Home Hermes connected' },
    { ts: Date.now() - 480_000, tool: 'daemon.start', ok: true, summary: 'Relay daemon started' }
  ]
}

async function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (!('__TAURI_INTERNALS__' in window)) {
    if (command === 'get_snapshot') return demo as T
    if (command === 'list_authorized_clients') return [
      { token_prefix: 'f83a21c4', device_name: 'WORKSTATION', last_seen: Math.floor(Date.now() / 1000), transport_hint: 'desktop', is_current: true, grants: { chat: null, tools: null } },
      { token_prefix: '9d210b7e', device_name: 'Pixel 10 Pro', last_seen: Math.floor(Date.now() / 1000) - 420, transport_hint: 'android', grants: { chat: null } }
    ] as T
    if (command === 'check_desktop_update') return { current: '0.4.0-alpha.3', up_to_date: true, ahead_of_latest: true, latest_version: '0.4.0-alpha.2', installed: false, needs_restart: false } as T
    if (command === 'install_desktop_update') return { current: '0.4.0-alpha.3', up_to_date: true, ahead_of_latest: false, installed: true, needs_restart: true } as T
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
    'daemon.restart': 'Restarted daemon', 'startup.change': 'Changed startup setting'
  }
  return names[tool] ?? tool.replace(/^desktop[._]/, '').replaceAll('_', ' ').replaceAll('.', ' ').replace(/\b\w/g, value => value.toUpperCase())
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
    .filter(([, value]) => typeof value === 'string' && value.trim())
    .map(([key, value]) => `${key}: ${String(value)}`)
    .join(', ')
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
  ask: 'The connection stays ready, but this host cannot use desktop tools until you allow access.',
  trusted: 'This host may use command and file tools; screen and input still require a task grant.',
  'full-access': 'This host may use commands, screen, mouse, and keyboard without asking.'
}

export default function App() {
  return isGrantWindow ? <GrantWindow /> : <ManagementApp />
}

function ManagementApp() {
  const [page, setPage] = useState<Page>('overview')
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [selectedUrl, setSelectedUrl] = useState<string | null>(null)
  const [detailUrl, setDetailUrl] = useState<string | null>(null)
  const [selectorOpen, setSelectorOpen] = useState(false)
  const [clients, setClients] = useState<AuthorizedClient[]>([])
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<PendingAction>(null)
  const [windowVisible, setWindowVisible] = useState(true)
  const selectorRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLElement>(null)
  const hideTimer = useRef<number | null>(null)

  const refresh = useCallback(async () => {
    try {
      const next = await call<Snapshot>('get_snapshot')
      next.hosts = next.hosts.map(h => ({ ...h, name: h.name || displayHost(h.url) }))
      setSnapshot(next)
      setSelectedUrl(current => current && next.hosts.some(h => h.url === current) ? current : next.active_url ?? next.hosts[0]?.url ?? null)
      setError(null)
    } catch (e) { setError(String(e)) }
  }, [])

  useEffect(() => {
    refresh()
    const timer = window.setInterval(refresh, 5000)
    return () => window.clearInterval(timer)
  }, [refresh])

  useEffect(() => {
    const close = (event: MouseEvent) => {
      if (!selectorRef.current?.contains(event.target as Node)) setSelectorOpen(false)
    }
    window.addEventListener('mousedown', close)
    return () => window.removeEventListener('mousedown', close)
  }, [])

  const host = useMemo(() => snapshot?.hosts.find(item => item.url === selectedUrl) ?? null, [snapshot, selectedUrl])
  const connected = Boolean(snapshot?.daemon.running && snapshot.daemon.state === 'connected' && host && snapshot.daemon.url === host.url)

  useEffect(() => {
    if (page !== 'settings' || !host) return
    call<AuthorizedClient[]>('list_authorized_clients', { remote: host.url }).then(setClients).catch(e => setError(String(e)))
  }, [page, host])

  useEffect(() => {
    contentRef.current?.scrollTo({ top: 0 })
  }, [page])

  async function action(name: string, args?: Record<string, unknown>) {
    setBusy(name)
    try { await call(name, args); await refresh(); setError(null) }
    catch (e) { setError(String(e)) }
    finally { setBusy(null) }
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
    } else if (work.type === 'revoke' && host) {
      await action('revoke_authorized_client', { remote: host.url, prefix: work.client.token_prefix })
      setClients(await call<AuthorizedClient[]>('list_authorized_clients', { remote: host.url }))
    } else if (work.type === 'clear-activity') {
      await action('clear_activity')
    }
  }

  const chooseAccess = (mode: AccessMode) => {
    if (!host || host.access_mode === mode) return
    if (mode === 'full-access') setPending({ type: 'access', mode })
    else action('set_host_access', { remote: host.url, mode })
  }

  const hideWindow = useCallback(() => {
    setWindowVisible(false)
    if (hideTimer.current) window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => {
      void getCurrentWindow().hide().finally(() => setWindowVisible(true))
    }, 145)
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

  if (!snapshot) return <div className={`app-shell loading ${windowVisible ? 'window-visible' : ''}`}><LoaderCircle className="spin" /><span>Loading Hermes-Relay CLI UI…</span></div>

  return <div className={`app-shell ${windowVisible ? 'window-visible' : ''}`}>
    <header className="titlebar">
      <div className="brand"><img src={logo} alt="" /><span>Hermes-Relay CLI UI</span></div>
      <div className="window-controls">
        <button aria-label="Hide window" onClick={hideWindow}><X /></button>
      </div>
    </header>

    <main className="content" ref={contentRef}>
      {page === 'overview' && <>
        <section className="connection-hero">
          <div className={`status-ring ${connected ? 'online' : 'offline'}`}>{connected ? <Check /> : <Unplug />}</div>
          <div><h1>{connected ? 'Connected' : 'Disconnected'}</h1><p>{connected ? 'Remote control tunnel active' : 'The remote control tunnel is offline'}</p></div>
        </section>

        <section className="section host-section">
          <label>Connected host</label>
          {snapshot.hosts.length === 0 ?
            <button className="empty-pair" onClick={() => action('pair_host')}><Link2 /><span><strong>Pair host</strong><small>Connect this PC to a Hermes instance</small></span><ChevronRight /></button> :
            <div className="host-selector" ref={selectorRef}>
              <button className="selector-button" aria-expanded={selectorOpen} onClick={() => setSelectorOpen(value => !value)}>
                <span className="host-icon"><Monitor /></span><span className="host-main"><strong>{host?.name}</strong><small>{host?.endpoint_role ? `${host.endpoint_role.replace(/^./, x => x.toUpperCase())} relay` : 'Hermes relay'} <i>• {connected ? 'Online' : 'Offline'}</i></small></span><ChevronDown className={selectorOpen ? 'rotated' : ''} />
              </button>
              {selectorOpen && <div className="selector-menu">
                {snapshot.hosts.map(item => <button key={item.url} className={item.url === host?.url ? 'selected' : ''} onClick={() => selectHost(item.url)}><Monitor /><span><strong>{item.name}</strong><small>{item.url}</small></span>{item.url === host?.url && <Check />}</button>)}
                <button className="pair-option" onClick={() => { setSelectorOpen(false); action('pair_host') }}><Link2 /><span><strong>Pair host</strong><small>Connect another Hermes instance</small></span></button>
              </div>}
            </div>}
        </section>

        {host && <section className="section access-section">
          <div className="label-row"><label>Access for this host</label><span title="Access applies only to the selected Hermes host."><CircleHelp /></span></div>
          <div className="access-control" role="radiogroup" aria-label="Host access">
            <button role="radio" aria-checked={host.access_mode === 'ask'} className={host.access_mode === 'ask' ? 'active' : ''} onClick={() => chooseAccess('ask')}><CircleHelp /><span>Ask</span></button>
            <button role="radio" aria-checked={host.access_mode === 'trusted'} className={host.access_mode === 'trusted' ? 'active' : ''} onClick={() => chooseAccess('trusted')}><ShieldCheck /><span>Trusted</span></button>
            <button role="radio" aria-checked={host.access_mode === 'full-access'} className={host.access_mode === 'full-access' ? 'active' : ''} onClick={() => chooseAccess('full-access')}><Monitor /><span>Full Access</span></button>
          </div>
          <p className="access-copy">{accessCopy[host.access_mode]}</p>
        </section>}

        <button className="tunnel-button" disabled={busy !== null} onClick={() => action(connected ? 'disconnect_daemon' : 'connect_daemon')}><Power />{connected ? 'Disconnect Tunnel' : 'Connect Tunnel'}</button>

        <section className="activity-section">
          <div className="section-heading"><h2>Recent activity</h2><button onClick={() => setPage('settings')}>View all <ChevronRight /></button></div>
          <ActivityList entries={snapshot.activity.slice(-3).reverse()} host={host} />
        </section>

        {snapshot.daemon.privilege === 'administrator' && <aside className="admin-warning"><AlertTriangle /><span>Hermes-Relay CLI is running as Administrator.</span><button onClick={() => setPage('settings')}>Learn more</button></aside>}
      </>}

      {page === 'hosts' && <HostsPage hosts={snapshot.hosts} selected={host} onOpen={url => { setDetailUrl(url); setPage('host-detail') }} onPair={() => action('pair_host')} />}
      {page === 'host-detail' && <HostDetailPage host={snapshot.hosts.find(item => item.url === detailUrl) ?? null} busy={busy !== null} onBack={() => setPage('hosts')} onConnect={connectHost} onRename={(remote, name) => action('rename_host', { remote, name })} />}
      {page === 'settings' && <SettingsPage host={host} daemon={snapshot.daemon} startup={snapshot.startup_enabled} clients={clients} activity={snapshot.activity} onRestart={() => action('restart_daemon')} onStartup={value => action('set_startup', { enabled: value })} onRevoke={client => setPending({ type: 'revoke', client })} onViewActivity={() => setPage('activity')} />}
      {page === 'activity' && <ActivityPage entries={snapshot.activity} host={host} onBack={() => setPage('settings')} onClear={() => setPending({ type: 'clear-activity' })} />}
    </main>

    {error && <div className="error-toast" role="alert"><AlertTriangle /><span>{error}</span><button onClick={() => setError(null)}><X /></button></div>}

    <nav className="bottom-nav">
      <button className={page === 'overview' ? 'active' : ''} onClick={() => setPage('overview')}><Home /><span>Overview</span></button>
      <button className={page === 'hosts' || page === 'host-detail' ? 'active' : ''} onClick={() => setPage('hosts')}><Monitor /><span>Hosts</span></button>
      <button className={page === 'settings' || page === 'activity' ? 'active' : ''} onClick={() => setPage('settings')}><Settings /><span>Settings</span></button>
    </nav>

    {pending && <div className="modal-backdrop" role="presentation"><div className="modal" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
      <div className="modal-icon">{pending.type === 'revoke' ? <UserRoundX /> : pending.type === 'clear-activity' ? <Trash2 /> : <AlertTriangle />}</div>
      <h2 id="confirm-title">{pending.type === 'access' ? `Give ${host?.name} full access?` : pending.type === 'revoke' ? `Deauthorize ${pending.client.device_name ?? pending.client.token_prefix}?` : 'Clear local activity?'}</h2>
      <p>{pending.type === 'access' ? 'This host will be able to run commands and control this PC without asking. Only use Full Access with a Hermes host you control.' : pending.type === 'revoke' ? (pending.client.is_current ? 'This is the current PC session. You will need to pair again.' : 'This client will immediately lose access to this Hermes host.') : 'This permanently removes the current and rotated desktop audit history from this PC. New activity will continue to be recorded.'}</p>
      <div className="modal-actions"><button className="secondary" onClick={() => setPending(null)}>Cancel</button><button className="danger" onClick={confirmPending}>{pending.type === 'access' ? 'Enable Full Access' : pending.type === 'revoke' ? 'Deauthorize' : 'Clear activity'}</button></div>
    </div></div>}
  </div>
}

function GrantWindow() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [grant, setGrant] = useState<PendingGrantRequest | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const activeId = useRef<string | null>(null)

  const refreshGrant = useCallback(async () => {
    try {
      const next = await call<Snapshot>('get_snapshot')
      const incoming = next.pending_grants[0] ?? null
      setSnapshot(next)
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
    void refreshGrant()
    const timer = window.setInterval(refreshGrant, 1000)
    return () => window.clearInterval(timer)
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

  if (!grant || !snapshot) return <div className="grant-shell" />
  const hostName = snapshot.hosts.find(item => item.url === snapshot.daemon.url)?.name
    ?? (snapshot.daemon.url ? displayHost(snapshot.daemon.url) : 'Connected host')
  const scope = formatGrantScope(grant.scope)
  const minutes = Math.max(1, Math.round(grant.duration_seconds / 60))

  return <div className={`grant-shell ${visible ? 'window-visible' : ''} ${expanded ? 'expanded' : ''}`}>
    <section className="grant-card" role="dialog" aria-modal="true" aria-labelledby="grant-title">
      <div className="grant-head">
        <span className="grant-icon"><ShieldCheck /></span>
        <span><small>Remote access request</small><strong id="grant-title">{grant.mode} access</strong></span>
        <button className="grant-expand" aria-expanded={expanded} aria-label={expanded ? 'Hide request details' : 'Show request details'} onClick={toggleExpanded}><ChevronDown /></button>
      </div>
      <p className="grant-summary"><strong>{hostName}</strong> wants to control this PC for up to {minutes} min.</p>
      <div className="grant-details" aria-hidden={!expanded}>
        <dl><div><dt>Reason</dt><dd>{grant.reason || 'No reason provided'}</dd></div>{scope && <div><dt>Scope</dt><dd>{scope}</dd></div>}</dl>
      </div>
      <div className="grant-actions"><button disabled={busy} onClick={() => resolve(false)}>Reject</button><button disabled={busy} onClick={() => resolve(true)}>Approve</button></div>
    </section>
  </div>
}

function ActivityList({ entries, host, detailed = false }: { entries: Activity[]; host: Host | null; detailed?: boolean }) {
  const [expanded, setExpanded] = useState<string | null>(null)
  if (!entries.length) return <div className="empty-state"><Clock3 /><span>No remote activity yet</span></div>
  return <div className="activity-list">{entries.map((entry, i) => {
    const category = activityCategory(entry)
    const attention = needsAttention(entry)
    const warning = isNonZeroExit(entry)
    const key = entry.request_id ?? `${entry.ts}-${i}`
    const Icon = category === 'command' ? TerminalSquare : category === 'files' ? FileText : category === 'screen' ? Eye : category === 'input' ? MousePointer2 : category === 'system' ? LogOut : ActivityIcon
    const open = detailed && expanded === key
    const detail = entry.error ?? entry.summary ?? (entry.aborted ? 'Request aborted' : 'Completed')
    const eventHost = entry.host_url ? displayHost(entry.host_url) : host?.name ?? 'Local daemon'
    return <article className={`activity-item ${open ? 'expanded' : ''}`} key={key}>
      <button className="activity-row" aria-expanded={detailed ? open : undefined} onClick={() => detailed && setExpanded(open ? null : key)}>
        <span className={`activity-icon ${attention || warning ? 'amber' : category === 'system' ? 'green' : 'violet'}`}><Icon /></span>
        <span className="activity-copy"><strong>{activityName(entry.tool)}</strong><small className={attention ? 'attention' : warning ? 'warning' : ''}>{detail} · {eventHost}</small></span>
        <span className="activity-tail"><time>{formatTime(entry.ts)}</time>{detailed && <ChevronDown />}</span>
      </button>
      {detailed && <div className="activity-details" aria-hidden={!open}>
        <dl>
          <div><dt>When</dt><dd>{formatDateTime(entry.ts)}</dd></div>
          <div><dt>Duration</dt><dd>{formatDuration(entry.duration_ms)}</dd></div>
          <div><dt>Status</dt><dd className={attention ? 'attention' : warning ? 'warning' : 'success'}>{entry.aborted ? 'Aborted' : !entry.ok ? 'Failed' : warning ? `Exit ${activityExitCode(entry)}` : 'Completed'}</dd></div>
          <div><dt>Category</dt><dd>{category}</dd></div>
        </dl>
        {entry.args_preview && <div className="activity-context"><span>Request</span><code>{entry.args_preview}</code></div>}
        {entry.request_id && <div className="activity-request-id">ID {entry.request_id}</div>}
      </div>}
    </article>
  })}</div>
}

function ActivityPanel({ entries, host, onClear }: { entries: Activity[]; host: Host | null; onClear: () => void }) {
  const [filter, setFilter] = useState<ActivityFilter>('all')
  const newest = entries.slice().reverse()
  const attention = newest.filter(needsAttention).length
  const warnings = newest.filter(isNonZeroExit).length
  const visible = newest.filter(entry => filter === 'all' || (filter === 'attention' ? needsAttention(entry) : filter === 'warning' ? isNonZeroExit(entry) : activityCategory(entry) === filter))
  const filters: Array<{ value: ActivityFilter; label: string }> = [
    { value: 'all', label: 'All' }, { value: 'command', label: 'Commands' },
    { value: 'files', label: 'Files' }, { value: 'screen', label: 'Screen' },
    { value: 'input', label: 'Input' }, { value: 'system', label: 'System' },
    { value: 'warning', label: 'Non-zero' }, { value: 'attention', label: 'Issues' }
  ]
  return <div className="activity-panel">
    <div className="activity-summary"><span><ActivityIcon /><strong>{entries.length}</strong><small>Recent events</small></span><span className={attention ? 'has-attention' : ''}><AlertTriangle /><strong>{attention}</strong><small>Issues</small></span><em><i /> Live</em></div>
    <div className="activity-toolbar"><div className="activity-filters" aria-label="Filter activity">{filters.map(item => <button key={item.value} className={filter === item.value ? 'active' : ''} onClick={() => setFilter(item.value)}>{item.label}{item.value === 'attention' && attention > 0 ? ` ${attention}` : item.value === 'warning' && warnings > 0 ? ` ${warnings}` : ''}</button>)}</div><button className="clear-activity" disabled={!entries.length} onClick={onClear}><Trash2 /> Clear</button></div>
    <div className="settings-card activity-card"><ActivityList entries={visible} host={host} detailed /></div>
  </div>
}

function ActivityPage({ entries, host, onBack, onClear }: { entries: Activity[]; host: Host | null; onBack: () => void; onClear: () => void }) {
  return <section className="page-panel activity-page">
    <div className="page-title activity-page-title"><button className="back-button" onClick={onBack}><ChevronRight /> Settings</button><div><p>Local audit</p><h1>Activity</h1></div></div>
    <p className="page-intro">Remote actions and management changes recorded on this PC.</p>
    <ActivityPanel entries={entries} host={host} onClear={onClear} />
  </section>
}

function HostsPage({ hosts, selected, onOpen, onPair }: { hosts: Host[]; selected: Host | null; onOpen: (url: string) => void; onPair: () => void }) {
  return <section className="page-panel"><div className="page-title"><div><p>Connections</p><h1>Hosts</h1></div><button className="icon-button" onClick={onPair} aria-label="Pair host"><Link2 /></button></div>
    <p className="page-intro">Hermes instances and relays this PC trusts. Access settings apply independently to each host.</p>
    <div className="host-list">{hosts.map(host => <button key={host.url} className={`host-card ${selected?.url === host.url ? 'selected' : ''}`} onClick={() => onOpen(host.url)}><span className="host-icon"><Server /></span><span><strong>{host.name}</strong><small>{host.url}</small><em><i />{host.is_active ? 'Active host' : 'Paired'} · {host.access_mode === 'full-access' ? 'Full Access' : host.access_mode[0]!.toUpperCase() + host.access_mode.slice(1)}</em></span><ChevronRight /></button>)}</div>
    {!hosts.length && <div className="large-empty"><Link2 /><h2>No hosts paired</h2><p>Pair this PC with a Hermes instance to begin.</p><button onClick={onPair}>Pair host</button></div>}
  </section>
}

function HostDetailPage({ host, busy, onBack, onConnect, onRename }: { host: Host | null; busy: boolean; onBack: () => void; onConnect: (url: string) => void; onRename: (url: string, name: string) => Promise<void> }) {
  const [name, setName] = useState(host?.name ?? '')
  useEffect(() => setName(host?.name ?? ''), [host?.url, host?.name])
  if (!host) return <section className="page-panel"><button className="back-button" onClick={onBack}><ChevronRight /> Hosts</button><div className="large-empty"><Server /><h2>Host unavailable</h2><p>This pairing may have been removed.</p></div></section>
  const changed = name.trim() !== host.name && name.trim().length > 0
  return <section className="page-panel host-detail-page">
    <button className="back-button" onClick={onBack}><ChevronRight /> Hosts</button>
    <div className="host-detail-hero"><span className="host-icon"><Server /></span><span><p>{host.is_active ? 'Active host' : 'Paired host'}</p><h1>{host.name}</h1><small>{host.url}</small></span></div>
    <div className="settings-group"><h2>Display name</h2><div className="rename-host"><input value={name} maxLength={64} aria-label="Host display name" onChange={event => setName(event.target.value)} /><button disabled={!changed || busy} onClick={() => onRename(host.url, name.trim())}>Save</button></div><p className="group-help host-name-help">Stored locally on this PC. It does not rename the Hermes server.</p></div>
    <div className="settings-group"><h2>Connection details</h2><div className="settings-card host-detail"><dl><div><dt>Route</dt><dd>{host.endpoint_role ?? 'Custom'}</dd></div><div><dt>Version</dt><dd>{host.server_version ?? 'Unknown'}</dd></div><div><dt>Access</dt><dd>{host.access_mode === 'full-access' ? 'Full Access' : host.access_mode[0]!.toUpperCase() + host.access_mode.slice(1)}</dd></div></dl></div></div>
    <button className="primary-host-action" disabled={host.is_active || busy} onClick={() => onConnect(host.url)}><Power />{host.is_active ? 'Currently connected host' : 'Connect to this host'}</button>
  </section>
}

function SettingsPage({ host, daemon, startup, clients, activity, onRestart, onStartup, onRevoke, onViewActivity }: { host: Host | null; daemon: Snapshot['daemon']; startup: boolean; clients: AuthorizedClient[]; activity: Activity[]; onRestart: () => void; onStartup: (value: boolean) => void; onRevoke: (client: AuthorizedClient) => void; onViewActivity: () => void }) {
  const [update, setUpdate] = useState<UpdateReport | null>(null)
  const [updateBusy, setUpdateBusy] = useState<'check' | 'install' | null>(null)
  const [updateError, setUpdateError] = useState<string | null>(null)

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

  return <section className="page-panel settings-page"><div className="page-title"><div><p>Local management</p><h1>Settings</h1></div></div>
    <div className="settings-group"><h2>Relay daemon</h2><div className="settings-card"><div className="setting-row"><span><strong>Daemon status</strong><small>{daemon.running ? `${daemon.state} · ${daemon.privilege ?? 'user'}` : 'Stopped'}</small></span><button className="compact-button" onClick={onRestart}><RefreshCw /> Restart</button></div><label className="setting-row toggle-row"><span><strong>Start at sign-in</strong><small>Keep remote access ready after you sign in.</small></span><input type="checkbox" checked={startup} onChange={e => onStartup(e.target.checked)} /><i /></label></div></div>
    <div className="settings-group"><h2>Updates</h2><div className={`settings-card update-card ${updateError ? 'error' : update?.ahead_of_latest ? 'ahead' : update?.up_to_date ? 'current' : ''}`}><div className="setting-row update-row"><span><strong>Hermes-Relay CLI UI</strong><small>{updateSummary}</small></span>{update && !update.up_to_date && !update.ahead_of_latest && !update.installed ? <button className="compact-button update-button" disabled={updateBusy !== null} onClick={installUpdate}>{updateBusy === 'install' ? <LoaderCircle className="spin" /> : <Download />} Install</button> : <button className="compact-button" disabled={updateBusy !== null} onClick={checkUpdate}>{updateBusy === 'check' ? <LoaderCircle className="spin" /> : <RefreshCw />} Check</button>}</div></div><p className="group-help update-help">Updates the management UI and CLI together, then restarts the tray automatically.</p></div>
    {host && <div className="settings-group"><h2>Host details</h2><div className="settings-card host-detail"><div className="detail-head"><span className="host-icon"><Server /></span><span><strong>{host.name}</strong><small>{host.url}</small></span></div><dl><div><dt>Route</dt><dd>{host.endpoint_role ?? 'Custom'}</dd></div><div><dt>Version</dt><dd>{host.server_version ?? 'Unknown'}</dd></div><div><dt>Access</dt><dd>{host.access_mode === 'full-access' ? 'Full Access' : host.access_mode[0]!.toUpperCase() + host.access_mode.slice(1)}</dd></div></dl></div></div>}
    {host && <div className="settings-group"><h2>Authorized clients <span>{clients.length}</span></h2><p className="group-help">Clients authenticated to {host.name}. Deauthorizing a client removes its relay session.</p><div className="settings-card client-list">{clients.length ? clients.map(client => { const identity = [client.device_model, client.device_platform, client.client_surface].filter(Boolean).join(' · ') || client.transport_hint || 'Client'; return <div className="client-row" key={client.token_prefix}><span className="client-icon"><Laptop /></span><span><strong>{client.device_name ?? 'Unnamed client'} {client.is_current && <em>This PC</em>}</strong><small>{identity} · {age(client.last_seen)}</small></span><button onClick={() => onRevoke(client)} aria-label={`Deauthorize ${client.device_name ?? client.token_prefix}`}><UserRoundX /></button></div> }) : <div className="empty-state"><Radio /><span>No authorized clients reported</span></div>}</div></div>}
    <div className="settings-group"><div className="settings-group-heading"><h2>Activity</h2><button onClick={onViewActivity}>View all <ChevronRight /></button></div><p className="group-help">Recent remote actions recorded on this PC.</p><div className="settings-card padded"><ActivityList entries={activity.slice(-3).reverse()} host={host} /></div></div>
  </section>
}
