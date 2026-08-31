import {
  Badge,
  Button,
  Codicon,
  EmptyState,
  ErrorState,
  Input,
  PANES_AREA,
  PALETTE_AREA,
  SIDEBAR_NAV_AREA,
  STATUSBAR_AREAS,
  StatusDot,
  Tip,
  host,
  useMutation,
  usePluginI18n,
  useQuery,
  useQueryClient,
  useValue
} from '@hermes/plugin-sdk'
import { useState } from 'react'
import { Fragment, jsx, jsxs } from 'react/jsx-runtime'

const PLUGIN_ID = 'hermes-relay'

const messages = {
  en: {
    'action.cancel': 'Cancel',
    'action.confirm': 'Confirm',
    'action.copy': 'Copy invite',
    'action.open': 'Open Hermes Relay',
    'action.pair': 'Pair Android',
    'action.pairDesktop': 'Desktop compatibility invite',
    'action.refresh': 'Refresh',
    'activity.empty': 'No recent bridge activity.',
    'activity.title': 'Bridge activity',
    'management.empty': 'No paired devices.',
    'management.title': 'Relay management',
    'media.empty': 'No active media deliveries.',
    'media.title': 'Media',
    'nav.label': 'Relay',
    'pairing.copyFailed': 'The Desktop clipboard bridge is unavailable. Select and copy the invite manually.',
    'pairing.expires': 'One-time invite. Keep it private and use it before it expires.',
    'pairing.title': 'Pairing invite',
    'pane.description': 'Profile-scoped views of the existing Hermes-Relay service.',
    'pane.title': 'Hermes Relay',
    'remote.disable': 'Disable Tailscale serving',
    'remote.enable': 'Enable Tailscale serving',
    'remote.probe': 'Probe URL',
    'remote.save': 'Save public URL',
    'remote.title': 'Remote access',
    'session.revoke': 'Revoke',
    'session.revokeConfirm': 'Revoke this device session? The device must pair again.',
    'status.detail': 'Open the profile-scoped Relay management pane',
    'status.label': 'Relay'
  }
}

export function profileQueryKey(profile, resource) {
  return [PLUGIN_ID, profile || 'default', resource]
}

/**
 * Pairing defaults to Dashboard-origin routes discovered by the backend.
 * Current standalone Desktop clients cannot obtain Dashboard WebSocket
 * tickets, so their direct Relay fallback is an explicit, separate action.
 */
export function pairingMintBody({ desktopCompatibility = false } = {}) {
  return {
    mode: 'auto',
    ...(desktopCompatibility ? { legacy_direct_relay: true } : {})
  }
}

function text(value, fallback = '—') {
  if (value === null || value === undefined || value === '') return fallback
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function list(value, key) {
  if (Array.isArray(value)) return value
  if (value && Array.isArray(value[key])) return value[key]
  return []
}

function friendlyError(error) {
  if (!error) return 'Unknown error'
  if (error instanceof Error) return error.message
  return String(error)
}

function formatTime(value) {
  if (!value) return '—'
  const numeric = Number(value)
  const millis = Number.isFinite(numeric) && numeric < 10_000_000_000 ? numeric * 1000 : numeric
  const date = new Date(Number.isFinite(millis) ? millis : value)
  return Number.isNaN(date.getTime()) ? text(value) : date.toLocaleString()
}

function Field({ label, value }) {
  return jsxs('div', {
    className: 'min-w-0 rounded-md border border-(--ui-stroke-tertiary) px-3 py-2',
    children: [
      jsx('div', {
        className: 'text-[0.625rem] font-medium uppercase tracking-wide text-(--ui-text-quaternary)',
        children: label
      }),
      jsx('div', {
        className: 'mt-1 break-words text-xs text-(--ui-text-secondary)',
        children: text(value)
      })
    ]
  })
}

function SectionHeader({ action, description, title }) {
  return jsxs('header', {
    className: 'mb-4 flex flex-wrap items-start justify-between gap-3',
    children: [
      jsxs('div', {
        children: [
          jsx('h2', { className: 'text-sm font-semibold text-(--ui-text-primary)', children: title }),
          description
            ? jsx('p', { className: 'mt-1 text-xs leading-5 text-(--ui-text-tertiary)', children: description })
            : null
        ]
      }),
      action
    ]
  })
}

function QueryState({ empty, query, render }) {
  if (query.isPending) {
    return jsx('p', { className: 'py-8 text-xs text-(--ui-text-tertiary)', role: 'status', children: 'Loading…' })
  }
  if (query.error) {
    return jsx(ErrorState, { description: friendlyError(query.error), title: 'Relay unavailable' })
  }
  if (empty(query.data)) {
    return jsx(EmptyState, { description: '', title: 'Nothing to show' })
  }
  return render(query.data)
}

function createManagement(ctx) {
  return function Management() {
    const t = usePluginI18n(PLUGIN_ID)
    const profile = useValue(host.state.profile)
    const client = useQueryClient()
    const [confirmPrefix, setConfirmPrefix] = useState(null)
    const [pairing, setPairing] = useState(null)
    const [desktopCompatibility, setDesktopCompatibility] = useState(false)
    const [copyError, setCopyError] = useState(null)
    const overviewKey = profileQueryKey(profile, 'overview')
    const sessionsKey = profileQueryKey(profile, 'sessions')
    const overview = useQuery({ queryKey: overviewKey, queryFn: () => ctx.rest('/overview'), retry: false })
    const sessions = useQuery({ queryKey: sessionsKey, queryFn: () => ctx.rest('/sessions'), retry: false })
    const refresh = () => {
      void client.invalidateQueries({ queryKey: overviewKey })
      void client.invalidateQueries({ queryKey: sessionsKey })
    }
    const pair = useMutation({
      mutationFn: options => ctx.rest('/pairing', { method: 'POST', body: pairingMintBody(options) }),
      onSuccess: (result, options) => {
        setCopyError(null)
        setDesktopCompatibility(options?.desktopCompatibility === true)
        setPairing(result)
      }
    })
    const revoke = useMutation({
      mutationFn: prefix => ctx.rest(`/sessions/${encodeURIComponent(prefix)}`, { method: 'DELETE' }),
      onSuccess: () => {
        setConfirmPrefix(null)
        void client.invalidateQueries({ queryKey: sessionsKey })
      }
    })
    const rows = list(sessions.data, 'sessions')

    const copyInvite = async () => {
      const invite = pairing?.pairing_url || pairing?.qr_payload
      if (!invite) return
      if (!(await ctx.os.writeClipboard(invite))) setCopyError(t('pairing.copyFailed'))
    }

    return jsxs('section', {
      children: [
        jsx(SectionHeader, {
          title: t('management.title'),
          description: `Profile: ${profile || 'default'}`,
          action: jsxs('div', {
            className: 'flex gap-2',
            children: [
              jsx(Button, { size: 'sm', variant: 'outline', onClick: refresh, children: t('action.refresh') }),
              jsx(Button, {
                size: 'sm',
                disabled: pair.isPending,
                onClick: () => pair.mutate({ desktopCompatibility: false }),
                children: pair.isPending ? 'Creating…' : t('action.pair')
              }),
              jsx(Button, {
                size: 'sm',
                variant: 'outline',
                disabled: pair.isPending,
                onClick: () => pair.mutate({ desktopCompatibility: true }),
                children: t('action.pairDesktop')
              })
            ]
          })
        }),
        overview.error
          ? jsx(ErrorState, { title: 'Relay unavailable', description: friendlyError(overview.error) })
          : overview.data
            ? jsxs('div', {
                className: 'mb-5 grid gap-2 sm:grid-cols-2 lg:grid-cols-4',
                children: [
                  jsx(Field, { label: 'Health', value: overview.data.health || overview.data.status }),
                  jsx(Field, { label: 'Version', value: overview.data.version }),
                  jsx(Field, { label: 'Uptime (seconds)', value: overview.data.uptime_seconds }),
                  jsx(Field, { label: 'Connected', value: overview.data.connected_clients ?? overview.data.active_sessions })
                ]
              })
            : null,
        pair.error ? jsx(ErrorState, { title: 'Pairing failed', description: friendlyError(pair.error) }) : null,
        pairing
          ? jsxs('div', {
              className: 'mb-5 rounded-md border border-(--ui-accent) p-3',
              children: [
                jsxs('div', {
                  className: 'flex flex-wrap items-center justify-between gap-2',
                  children: [
                    jsxs('div', {
                      children: [
                        jsx('h3', { className: 'text-xs font-semibold text-(--ui-text-primary)', children: t('pairing.title') }),
                        jsx('p', { className: 'mt-1 text-xs text-(--ui-text-tertiary)', children: t('pairing.expires') })
                      ]
                    }),
                    jsx(Button, { size: 'sm', variant: 'outline', onClick: copyInvite, children: t('action.copy') })
                  ]
                }),
                pairing.code
                  ? jsx('div', { className: 'mt-3 font-mono text-xl tracking-widest', children: pairing.code })
                  : null,
                desktopCompatibility
                  ? jsx('p', {
                      className: 'mt-2 text-[0.6875rem] text-(--ui-text-tertiary)',
                      children: 'Includes an explicit direct Relay fallback for current Desktop clients. Keep that route deliberately reachable; Android pairing does not need it.'
                    })
                  : jsx('p', {
                      className: 'mt-2 text-[0.6875rem] text-(--ui-text-tertiary)',
                      children: 'Uses Dashboard-origin routes discovered by the server. Direct Relay port 8767 is not advertised.'
                    }),
                jsx('div', {
                  className: 'mt-2 select-all break-all font-mono text-[0.6875rem] text-(--ui-text-tertiary)',
                  children: pairing.pairing_url || pairing.qr_payload || 'Invite created'
                }),
                copyError ? jsx('p', { className: 'mt-2 text-xs text-destructive', children: copyError }) : null
              ]
            })
          : null,
        revoke.error ? jsx(ErrorState, { title: 'Revoke failed', description: friendlyError(revoke.error) }) : null,
        jsx(QueryState, {
          query: sessions,
          empty: data => list(data, 'sessions').length === 0,
          render: () => jsxs('div', {
            className: 'overflow-hidden rounded-md border border-(--ui-stroke-tertiary)',
            children: rows.map((session, index) => {
              const prefix = session.token_prefix || session.prefix || session.id || `session-${index}`
              const confirming = confirmPrefix === prefix
              return jsxs('div', {
                className: 'flex flex-wrap items-center justify-between gap-3 border-b border-(--ui-stroke-tertiary) px-3 py-3 last:border-b-0',
                children: [
                  jsxs('div', {
                    className: 'min-w-0',
                    children: [
                      jsxs('div', {
                        className: 'flex flex-wrap items-center gap-2',
                        children: [
                          jsx('span', { className: 'font-medium text-xs text-(--ui-text-primary)', children: session.device_name || session.name || prefix }),
                          jsx(Badge, { children: session.device_type || session.client_type || 'device' })
                        ]
                      }),
                      jsx('p', {
                        className: 'mt-1 text-[0.6875rem] text-(--ui-text-tertiary)',
                        children: `${prefix} · paired ${formatTime(session.paired_at)}`
                      })
                    ]
                  }),
                  confirming
                    ? jsxs('div', {
                        className: 'flex items-center gap-2',
                        children: [
                          jsx('span', { className: 'max-w-52 text-right text-[0.6875rem] text-(--ui-text-tertiary)', children: t('session.revokeConfirm') }),
                          jsx(Button, { size: 'sm', variant: 'ghost', onClick: () => setConfirmPrefix(null), children: t('action.cancel') }),
                          jsx(Button, { size: 'sm', disabled: revoke.isPending, onClick: () => revoke.mutate(prefix), children: t('action.confirm') })
                        ]
                      })
                    : jsx(Button, { size: 'sm', variant: 'outline', onClick: () => setConfirmPrefix(prefix), children: t('session.revoke') })
                ]
              }, prefix)
            })
          })
        })
      ]
    })
  }
}

function createActivity(ctx) {
  return function Activity() {
    const t = usePluginI18n(PLUGIN_ID)
    const profile = useValue(host.state.profile)
    const query = useQuery({
      queryKey: profileQueryKey(profile, 'bridge-activity'),
      queryFn: () => ctx.rest('/bridge-activity?limit=100'),
      retry: false
    })
    return jsxs('section', {
      children: [
        jsx(SectionHeader, {
          title: t('activity.title'),
          description: 'Recent Relay-owned bridge commands. No live subscription or background polling.'
        }),
        jsx(QueryState, {
          query,
          empty: data => list(data, 'activity').length === 0,
          render: data => jsx('div', {
            className: 'space-y-2',
            children: list(data, 'activity').map((item, index) => jsxs('div', {
              className: 'rounded-md border border-(--ui-stroke-tertiary) px-3 py-2',
              children: [
                jsxs('div', {
                  className: 'flex flex-wrap items-center gap-2',
                  children: [
                    jsx(StatusDot, { tone: item.ok === false || item.error ? 'bad' : 'good' }),
                    jsx('span', { className: 'text-xs font-medium text-(--ui-text-primary)', children: item.command || item.type || item.action || 'Bridge command' }),
                    jsx('span', { className: 'ml-auto text-[0.6875rem] text-(--ui-text-quaternary)', children: formatTime(item.timestamp || item.created_at || item.at) })
                  ]
                }),
                jsx('p', { className: 'mt-1 break-words text-[0.6875rem] text-(--ui-text-tertiary)', children: text(item.summary || item.result || item.error || item.status) })
              ]
            }, item.id || `${index}`))
          })
        })
      ]
    })
  }
}

function createMedia(ctx) {
  return function Media() {
    const t = usePluginI18n(PLUGIN_ID)
    const profile = useValue(host.state.profile)
    const query = useQuery({
      queryKey: profileQueryKey(profile, 'media'),
      queryFn: () => ctx.rest('/media'),
      retry: false
    })
    return jsxs('section', {
      children: [
        jsx(SectionHeader, {
          title: t('media.title'),
          description: 'Sanitized Relay media metadata. Filesystem paths and bearer tokens are not requested.'
        }),
        jsx(QueryState, {
          query,
          empty: data => list(data, 'media').length === 0,
          render: data => jsx('div', {
            className: 'grid gap-2 sm:grid-cols-2',
            children: list(data, 'media').map((item, index) => jsxs('div', {
              className: 'rounded-md border border-(--ui-stroke-tertiary) px-3 py-3',
              children: [
                jsx('div', { className: 'truncate text-xs font-medium text-(--ui-text-primary)', children: item.filename || item.name || `Media ${index + 1}` }),
                jsx('p', { className: 'mt-1 text-[0.6875rem] text-(--ui-text-tertiary)', children: `${text(item.content_type || item.mime_type)} · ${text(item.size_bytes || item.size, 'unknown size')}` }),
                jsx('p', { className: 'mt-1 text-[0.6875rem] text-(--ui-text-quaternary)', children: `expires ${formatTime(item.expires_at)}` })
              ]
            }, item.id || item.token_prefix || `${index}`))
          })
        })
      ]
    })
  }
}

function createRemoteAccess(ctx) {
  return function RemoteAccess() {
    const t = usePluginI18n(PLUGIN_ID)
    const profile = useValue(host.state.profile)
    const client = useQueryClient()
    const key = profileQueryKey(profile, 'remote-access')
    const status = useQuery({ queryKey: key, queryFn: () => ctx.rest('/remote-access/status'), retry: false })
    const [url, setUrl] = useState('')
    const [confirmAction, setConfirmAction] = useState(null)
    const [result, setResult] = useState(null)
    const refresh = () => void client.invalidateQueries({ queryKey: key })
    const mutate = useMutation({
      mutationFn: async action => {
        if (action === 'enable') return ctx.rest('/remote-access/tailscale/enable', { method: 'POST', body: { stack: true } })
        if (action === 'disable') return ctx.rest('/remote-access/tailscale/disable', { method: 'POST', body: { stack: true } })
        if (action === 'save') return ctx.rest('/remote-access/public-url', { method: 'PUT', body: { url: url.trim() || null } })
        if (action === 'probe') return ctx.rest('/remote-access/probe', { method: 'POST', body: { candidates: [url.trim()] } })
        throw new Error('Unknown remote access action')
      },
      onSuccess: value => {
        setResult(value)
        setConfirmAction(null)
        refresh()
      }
    })
    const actionButton = (action, label, destructive = false) => confirmAction === action
      ? jsxs('span', {
          className: 'inline-flex items-center gap-1',
          children: [
            jsx(Button, { size: 'sm', variant: 'ghost', onClick: () => setConfirmAction(null), children: t('action.cancel') }),
            jsx(Button, { size: 'sm', disabled: mutate.isPending, onClick: () => mutate.mutate(action), children: t('action.confirm') })
          ]
        })
      : jsx(Button, { size: 'sm', variant: destructive ? 'outline' : 'outline', onClick: () => setConfirmAction(action), children: label })

    return jsxs('section', {
      children: [
        jsx(SectionHeader, {
          title: t('remote.title'),
          description: 'All changes require a labeled action and a second confirmation.'
        }),
        jsx(QueryState, {
          query: status,
          empty: data => !data,
          render: data => jsxs(Fragment, {
            children: [
              jsxs('div', {
                className: 'grid gap-2 sm:grid-cols-2',
                children: [
                  jsx(Field, { label: 'Tailscale', value: data.tailscale?.state || (data.tailscale?.enabled ? 'enabled' : 'disabled') }),
                  jsx(Field, { label: 'Public URL', value: data.public?.url || data.public_url }),
                  jsx(Field, { label: 'Secure Link', value: data.secure_link?.state || (data.secure_link?.enabled ? 'enabled' : 'disabled') }),
                  jsx(Field, { label: 'Upstream helper', value: data.upstream_canonical ? 'available' : 'not detected' })
                ]
              }),
              jsxs('div', {
                className: 'mt-4 flex flex-wrap gap-2',
                children: [
                  actionButton('enable', t('remote.enable')),
                  actionButton('disable', t('remote.disable'), true),
                  jsx(Button, { size: 'sm', variant: 'ghost', onClick: refresh, children: t('action.refresh') })
                ]
              })
            ]
          })
        }),
        jsxs('div', {
          className: 'mt-5 rounded-md border border-(--ui-stroke-tertiary) p-3',
          children: [
            jsx('label', { className: 'text-xs font-medium text-(--ui-text-secondary)', htmlFor: 'hermes-relay-public-url', children: 'Public relay URL' }),
            jsx(Input, {
              id: 'hermes-relay-public-url',
              className: 'mt-2',
              placeholder: 'https://relay.example.com',
              value: url,
              onChange: event => setUrl(event.target.value)
            }),
            jsxs('div', {
              className: 'mt-2 flex flex-wrap gap-2',
              children: [
                actionButton('save', t('remote.save')),
                actionButton('probe', t('remote.probe'))
              ]
            })
          ]
        }),
        mutate.error ? jsx(ErrorState, { title: 'Remote access action failed', description: friendlyError(mutate.error) }) : null,
        result ? jsx('pre', { className: 'mt-3 max-h-48 overflow-auto whitespace-pre-wrap rounded-md border border-(--ui-stroke-tertiary) p-3 text-[0.6875rem] text-(--ui-text-tertiary)', children: JSON.stringify(result, null, 2) }) : null
      ]
    })
  }
}

function createRelayPane(ctx) {
  const Management = createManagement(ctx)
  const Activity = createActivity(ctx)
  const Media = createMedia(ctx)
  const RemoteAccess = createRemoteAccess(ctx)

  return function RelayPane() {
    const t = usePluginI18n(PLUGIN_ID)
    const [tab, setTab] = useState('management')
    const tabs = [
      ['management', 'server', t('management.title')],
      ['activity', 'pulse', t('activity.title')],
      ['media', 'file-media', t('media.title')],
      ['remote', 'remote-explorer', t('remote.title')]
    ]
    const content = tab === 'activity'
      ? jsx(Activity, {})
      : tab === 'media'
        ? jsx(Media, {})
        : tab === 'remote'
          ? jsx(RemoteAccess, {})
          : jsx(Management, {})

    return jsxs('div', {
      className: 'flex h-full min-h-0 flex-col',
      children: [
        jsxs('header', {
          className: 'border-b border-(--ui-stroke-tertiary) px-4 py-3',
          children: [
            jsxs('div', {
              className: 'flex items-center gap-2',
              children: [
                jsx(Codicon, { name: 'radio-tower', size: '1rem' }),
                jsx('h1', { className: 'text-sm font-semibold text-(--ui-text-primary)', children: t('pane.title') })
              ]
            }),
            jsx('p', { className: 'mt-1 text-xs text-(--ui-text-tertiary)', children: t('pane.description') }),
            jsx('nav', {
              'aria-label': 'Hermes Relay sections',
              className: 'mt-3 flex flex-wrap gap-1',
              children: tabs.map(([id, icon, label]) => jsx(Button, {
                size: 'sm',
                variant: tab === id ? 'secondary' : 'ghost',
                onClick: () => setTab(id),
                children: jsxs(Fragment, { children: [jsx(Codicon, { name: icon, size: '0.75rem' }), label] })
              }, id))
            })
          ]
        }),
        jsx('main', { className: 'min-h-0 flex-1 overflow-y-auto p-4', children: content })
      ]
    })
  }
}

function RelayStatus({ open }) {
  const t = usePluginI18n(PLUGIN_ID)
  return jsx(Tip, {
    label: t('status.detail'),
    children: jsxs('button', {
      type: 'button',
      className: 'inline-flex h-full items-center gap-1.5 px-1.5 text-[0.6875rem] text-(--ui-text-tertiary) hover:bg-(--chrome-action-hover) hover:text-(--ui-text-primary)',
      onClick: open,
      children: [jsx(StatusDot, { tone: 'muted' }), jsx('span', { children: t('status.label') })]
    })
  })
}

const plugin = {
  id: PLUGIN_ID,
  name: 'Hermes Relay',
  description: 'Profile-scoped Hermes-Relay management through the official Desktop Plugin SDK.',
  defaultEnabled: false,
  register(ctx) {
    ctx.i18n.register(messages)
    const RelayPane = createRelayPane(ctx)
    let paneRegistered = false

    const open = () => {
      if (!paneRegistered) {
        ctx.register({
          id: 'management',
          area: PANES_AREA,
          title: ctx.i18n.t('pane.title'),
          data: {
            closeBehavior: 'dismiss',
            placement: 'right',
            dock: { pane: 'workspace', pos: 'right' },
            width: 'min(420px, 42vw)'
          },
          render: () => jsx(RelayPane, {})
        })
        paneRegistered = true
      }
      ctx.panes.reveal('management')
    }

    ctx.onDispose(() => {
      paneRegistered = false
    })

    ctx.registerMany([
      {
        id: 'nav',
        area: SIDEBAR_NAV_AREA,
        order: 75,
        data: { codicon: 'radio-tower', label: ctx.i18n.t('nav.label'), onSelect: open }
      },
      {
        id: 'status',
        area: STATUSBAR_AREAS.right,
        order: 110,
        render: () => jsx(RelayStatus, { open })
      },
      {
        id: 'open',
        area: PALETTE_AREA,
        data: {
          id: 'hermes-relay.open',
          label: ctx.i18n.t('action.open'),
          keywords: ['relay', 'phone', 'paired devices', 'bridge', 'media', 'remote access'],
          run: open
        }
      }
    ])
  }
}

export default plugin
