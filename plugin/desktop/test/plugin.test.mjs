import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import plugin, { profileQueryKey } from '../plugin.js'

const here = dirname(fileURLToPath(import.meta.url))

function harness() {
  const contributions = []
  const disposed = []
  const revealed = []
  const cleanups = []
  const ctx = {
    i18n: {
      register() {
        const dispose = () => disposed.push('i18n')
        cleanups.push(dispose)
        return dispose
      },
      t: key => key
    },
    onDispose(fn) {
      cleanups.push(fn)
    },
    os: { writeClipboard: async () => true },
    panes: { reveal: id => revealed.push(id) },
    register(contribution) {
      contributions.push(contribution)
      const dispose = () => disposed.push(contribution.id)
      cleanups.push(dispose)
      return dispose
    },
    registerMany(batch) {
      contributions.push(...batch)
      const dispose = () => disposed.push(...batch.map(item => item.id))
      cleanups.push(dispose)
      return dispose
    },
    rest: async () => ({})
  }
  return { cleanups, contributions, ctx, disposed, revealed }
}

test('ships opt-in and registers no pane or network work at load', () => {
  const state = harness()
  let restCalls = 0
  state.ctx.rest = async () => {
    restCalls += 1
    return {}
  }

  plugin.register(state.ctx)

  assert.equal(plugin.defaultEnabled, false)
  assert.deepEqual(state.contributions.map(item => item.id), ['nav', 'status', 'open'])
  assert.equal(state.contributions.some(item => item.area === 'panes'), false)
  assert.equal(state.revealed.length, 0)
  assert.equal(restCalls, 0)
})

test('startup, reconnect, profile-change, and reload registration never reveal the pane', () => {
  for (const lifecycle of ['startup', 'reconnect', 'profile-change', 'reload']) {
    const state = harness()
    plugin.register(state.ctx)
    assert.equal(state.contributions.some(item => item.area === 'panes'), false, lifecycle)
    assert.deepEqual(state.revealed, [], lifecycle)
  }
})

test('every labeled entry point opens lazily and repeated opens reuse the pane', () => {
  const state = harness()
  plugin.register(state.ctx)
  const nav = state.contributions.find(item => item.id === 'nav')
  const status = state.contributions.find(item => item.id === 'status')
  const command = state.contributions.find(item => item.id === 'open')

  nav.data.onSelect()
  const statusElement = status.render()
  const renderedStatus = statusElement.type(statusElement.props)
  renderedStatus.props.children.props.onClick()
  command.data.run()

  const panes = state.contributions.filter(item => item.area === 'panes')
  assert.equal(panes.length, 1)
  assert.equal(panes[0].data.closeBehavior, 'dismiss')
  assert.equal(panes[0].data.placement, 'right')
  assert.deepEqual(state.revealed, ['management', 'management', 'management'])
})

test('unload disposes entry points, lazy pane, locale bundle, and module state', () => {
  const state = harness()
  plugin.register(state.ctx)
  state.contributions.find(item => item.id === 'nav').data.onSelect()

  for (const cleanup of [...state.cleanups].reverse()) cleanup()

  assert.ok(state.disposed.includes('management'))
  assert.ok(state.disposed.includes('nav'))
  assert.ok(state.disposed.includes('i18n'))
})

test('query keys isolate cached backend state by active profile', () => {
  assert.deepEqual(profileQueryKey('default', 'overview'), ['hermes-relay', 'default', 'overview'])
  assert.notDeepEqual(profileQueryKey('work', 'sessions'), profileQueryKey('personal', 'sessions'))
})

test('unified package uses only runtime-plugin imports and contains no auto-open primitive', async () => {
  const source = await readFile(resolve(here, '..', 'plugin.js'), 'utf8')
  const imports = [...source.matchAll(/from\s+['\"]([^'\"]+)['\"]/g)].map(match => match[1])
  assert.deepEqual([...new Set(imports)].sort(), ['@hermes/plugin-sdk', 'react', 'react/jsx-runtime'])
  assert.equal(/setInterval|setTimeout|host\.navigate|window\.location|focus\s*\(/.test(source), false)
  assert.match(source, /const open = \(\) => \{/)
  assert.match(source, /paneRegistered = true/)
})

test('desktop half is bundled beside the existing dashboard half', async () => {
  const pluginRoot = resolve(here, '..', '..')
  const manifest = await readFile(resolve(pluginRoot, 'plugin.yaml'), 'utf8')
  const dashboard = await readFile(resolve(pluginRoot, 'dashboard', 'manifest.json'), 'utf8')
  const desktop = await readFile(resolve(pluginRoot, 'desktop', 'plugin.js'), 'utf8')
  assert.match(manifest, /^name:\s+hermes-relay/m)
  assert.equal(JSON.parse(dashboard).name, 'hermes-relay')
  assert.match(desktop, /id:\s*PLUGIN_ID/)
})
