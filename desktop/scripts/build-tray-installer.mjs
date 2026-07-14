#!/usr/bin/env node

import { existsSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const desktopRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const packageJson = await import('../package.json', { with: { type: 'json' } })
const version = packageJson.default.version

if (process.platform !== 'win32') {
  throw new Error('the optional Hermes Relay systray installer is Windows-only')
}

function run(command, args, label) {
  const result = spawnSync(command, args, { cwd: desktopRoot, stdio: 'inherit' })
  if (result.error || result.status !== 0) {
    const detail = result.error ? `: ${result.error.message}` : ''
    throw new Error(`${label} failed${detail}`)
  }
}

function npmRun(script) {
  const npmExecPath = process.env.npm_execpath
  if (npmExecPath) {
    run(process.execPath, [npmExecPath, 'run', script], `npm run ${script}`)
  } else {
    run('npm.cmd', ['run', script], `npm run ${script}`)
  }
}

function findMakensis() {
  const candidates = [
    process.env.MAKENSIS,
    join(process.env.ProgramFiles ?? '', 'NSIS', 'makensis.exe'),
    join(process.env['ProgramFiles(x86)'] ?? '', 'NSIS', 'makensis.exe')
  ].filter(Boolean)
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate
  }
  const probe = spawnSync('where.exe', ['makensis.exe'], { encoding: 'utf8' })
  const fromPath = probe.status === 0 ? probe.stdout.split(/\r?\n/).find(Boolean)?.trim() : undefined
  if (fromPath && existsSync(fromPath)) return fromPath
  throw new Error('makensis.exe was not found; install NSIS or set MAKENSIS to its full path')
}

npmRun('build:bin:win')
run('cargo', ['build', '--release', '--manifest-path', 'tray/Cargo.toml'], 'tray release build')

const cliExe = join(desktopRoot, 'dist', 'bin', 'hermes-relay-win-x64.exe')
const trayExe = join(desktopRoot, 'tray', 'target', 'release', 'hermes-relay-tray.exe')
for (const expected of [cliExe, trayExe]) {
  if (!existsSync(expected)) throw new Error(`expected build artifact is missing: ${expected}`)
}

const outputDir = join(desktopRoot, 'dist', 'tray')
const output = join(outputDir, 'hermes-relay-windows-x64-setup.exe')
mkdirSync(outputDir, { recursive: true })
const numeric = version.split(/[.-]/).slice(0, 3).concat('0').slice(0, 4).join('.')
const makensis = findMakensis()
run(
  makensis,
  [
    `/DVERSION=${version}`,
    `/DVERSION_NUM=${numeric}`,
    `/DCLI_EXE=${cliExe}`,
    `/DTRAY_EXE=${trayExe}`,
    `/DOUT_FILE=${output}`,
    `/DPATH_HELPER=${join(desktopRoot, 'tray', 'installer', 'path.ps1')}`,
    `/DICON_FILE=${join(desktopRoot, 'tray', 'icons', 'icon.ico')}`,
    join(desktopRoot, 'tray', 'installer', 'hermes-relay.nsi')
  ],
  'NSIS installer build'
)

if (!existsSync(output)) throw new Error(`NSIS did not produce ${output}`)
console.log(`tray installer ready: ${output}`)
