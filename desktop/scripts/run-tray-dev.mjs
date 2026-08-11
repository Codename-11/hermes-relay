#!/usr/bin/env node

import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn, spawnSync } from 'node:child_process'
import { createConnection } from 'node:net'

const desktopRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

if (process.platform !== 'win32') {
  throw new Error('the optional Hermes Relay systray is Windows-only')
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: desktopRoot, stdio: 'inherit', ...options })
  if (result.error || result.status !== 0) {
    process.exit(result.status ?? 1)
  }
}

const npmExecPath = process.env.npm_execpath
if (npmExecPath) {
  run(process.execPath, [npmExecPath, 'run', 'build:bin:win'])
} else {
  run('npm.cmd', ['run', 'build:bin:win'])
}

const npmCommand = npmExecPath ? process.execPath : 'npm.cmd'
const npmArgs = npmExecPath
  ? [npmExecPath, '--prefix', 'tray', 'run', 'dev']
  : ['--prefix', 'tray', 'run', 'dev']
const vite = spawn(npmCommand, npmArgs, { cwd: desktopRoot, stdio: 'inherit' })

async function waitForVite() {
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline) {
    const ready = await new Promise(resolve => {
      const socket = createConnection({ host: '127.0.0.1', port: 1420 })
      socket.once('connect', () => { socket.destroy(); resolve(true) })
      socket.once('error', () => resolve(false))
    })
    if (ready) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error('tray Vite server did not start on 127.0.0.1:1420')
}

try {
  await waitForVite()
  run('cargo', ['run', '--manifest-path', 'tray/Cargo.toml'], {
    env: {
      ...process.env,
      HERMES_RELAY_CLI_PATH: join(desktopRoot, 'dist', 'bin', 'hermes-relay-win-x64.exe')
    }
  })
} finally {
  vite.kill()
}
