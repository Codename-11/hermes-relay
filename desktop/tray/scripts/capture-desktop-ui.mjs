#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process'
import { createConnection } from 'node:net'
import { existsSync } from 'node:fs'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { computeDesktopUiSourceFingerprint } from './desktop-ui-source-fingerprint.mjs'

const trayRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repositoryRoot = resolve(trayRoot, '..', '..')
const outputDir = resolve(repositoryRoot, 'assets', 'screenshots', 'desktop-ui')
const manifestPath = resolve(repositoryRoot, 'docs', 'media', 'desktop-ui-screenshots.json')
const session = `hermes-relay-desktop-screenshots-${process.pid}`
const baseUrl = 'http://127.0.0.1:1421/'
const playwrightCliVersion = '0.1.18'
const bundledNpx = resolve(dirname(process.execPath), 'node_modules', 'npm', 'bin', 'npx-cli.js')
const viteEntry = resolve(trayRoot, 'node_modules', 'vite', 'bin', 'vite.js')
const requireFromWebsite = createRequire(resolve(repositoryRoot, 'website', 'package.json'))
const sharp = requireFromWebsite('sharp')

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: trayRoot,
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    ...options
  })
  if (result.status !== 0 || result.error) {
    throw new Error([result.error?.message, result.stdout, result.stderr].filter(Boolean).join('\n'))
  }
  return `${result.stdout ?? ''}${result.stderr ?? ''}`
}

function cli(...args) {
  if (!existsSync(bundledNpx)) throw new Error(`npm/npx runtime not found at ${bundledNpx}`)
  return run(process.execPath, [bundledNpx, '--yes', '--package', `@playwright/cli@${playwrightCliVersion}`, 'playwright-cli', `-s=${session}`, ...args], {
    env: { ...process.env, TZ: 'UTC' }
  })
}

async function waitForServer() {
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const ready = await new Promise(resolveReady => {
      const socket = createConnection({ host: '127.0.0.1', port: 1421 })
      socket.once('connect', () => { socket.destroy(); resolveReady(true) })
      socket.once('error', () => resolveReady(false))
    })
    if (ready) return
    await new Promise(resolveWait => setTimeout(resolveWait, 100))
  }
  throw new Error(`screenshot Vite server did not start at ${baseUrl}`)
}

async function capture(filename) {
  const destination = resolve(outputDir, filename)
  const rawCapture = `${destination}.capture.png`
  cli('screenshot', '--filename', rawCapture)
  const normalized = await sharp(rawCapture)
    .png({ compressionLevel: 9, adaptiveFiltering: false, palette: false })
    .toBuffer()
  let preserveExisting = false
  try {
    const previous = await readFile(destination)
    const [before, after] = await Promise.all([
      sharp(previous).raw().toBuffer({ resolveWithObject: true }),
      sharp(normalized).raw().toBuffer({ resolveWithObject: true })
    ])
    if (before.info.width === after.info.width && before.info.height === after.info.height && before.info.channels === after.info.channels) {
      let differingChannels = 0
      let maximumDelta = 0
      for (let index = 0; index < before.data.length; index += 1) {
        const delta = Math.abs(before.data[index] - after.data[index])
        if (delta > 0) differingChannels += 1
        if (delta > maximumDelta) maximumDelta = delta
      }
      preserveExisting = maximumDelta <= 1 && differingChannels / before.data.length <= 0.001
    }
  } catch { /* A missing or unreadable canonical image is replaced. */ }
  if (!preserveExisting) await writeFile(destination, normalized)
  await rm(rawCapture, { force: true })
  console.log(`${preserveExisting ? 'retained' : 'captured'} ${destination}`)
}

await mkdir(outputDir, { recursive: true })

const vite = spawn(
  process.execPath,
  [viteEntry, '--config', 'scripts/vite.screenshots.config.mjs'],
  { cwd: trayRoot, stdio: ['ignore', 'pipe', 'pipe'] }
)
let viteOutput = ''
vite.stdout.on('data', chunk => { viteOutput += chunk })
vite.stderr.on('data', chunk => { viteOutput += chunk })

try {
  await waitForServer()
  cli('open', 'about:blank', '--browser', 'chrome')
  cli('resize', '493', '785')
  cli('run-code', "async page => { await page.context().addInitScript(() => { const fixed = Date.parse('2026-08-01T16:00:00Z'); Date.now = () => fixed }) }")
  cli('goto', baseUrl)
  cli('run-code', "async page => { await page.waitForSelector('.app-shell.window-visible'); await page.addStyleTag({ content: '*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}' }); await page.evaluate(() => document.fonts.ready) }")

  await capture('overview.png')

  cli('run-code', "async page => { await page.getByRole('button', { name: 'Desktop access' }).click(); await page.waitForSelector('h1:text-is(\"Host access\")') }")
  await capture('host-access.png')

  cli('run-code', "async page => { await page.getByRole('button', { name: 'Overview', exact: true }).click(); await page.getByRole('button', { name: /PowerShell command/ }).click(); await page.waitForSelector('h1:text-is(\"PowerShell command\")') }")
  await capture('activity-detail.png')

  cli('run-code', "async page => { await page.getByRole('button', { name: 'Settings', exact: true }).click(); await page.waitForSelector('h1:text-is(\"Settings\")'); await page.waitForFunction(() => document.body.innerText.includes('CUA Driver 0.21.0')); await page.evaluate(() => { const content = document.querySelector('.content'); const control = [...document.querySelectorAll('.settings-group')].find(node => node.querySelector('h2')?.textContent === 'Computer control'); if (content && control) content.scrollTop = control.offsetTop - 10 }) }")
  await capture('settings.png')

  cli('run-code', "async page => { await page.evaluate(() => { const content = document.querySelector('.content'); const updates = [...document.querySelectorAll('.settings-group')].find(node => node.querySelector('h2')?.textContent === 'Updates'); if (content && updates) content.scrollTop = updates.offsetTop - 10 }) }")
  await capture('settings-update.png')

  const consoleOutput = cli('console', 'warning')
  if (/\b(TypeError|ReferenceError|Uncaught)\b/i.test(consoleOutput)) {
    throw new Error(`browser console reported a failure:\n${consoleOutput}`)
  }

  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
  manifest.sourceFingerprint = await computeDesktopUiSourceFingerprint(repositoryRoot)
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`)
  console.log(`updated ${manifestPath} source fingerprint`)
} catch (error) {
  if (vite.exitCode !== null) console.error(viteOutput)
  throw error
} finally {
  try { cli('close') } catch { /* Best effort session cleanup. */ }
  if (vite.exitCode === null) vite.kill()
  await rm(resolve(trayRoot, '.playwright-cli'), { recursive: true, force: true })
}
