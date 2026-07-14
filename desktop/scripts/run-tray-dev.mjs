#!/usr/bin/env node

import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

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

run('cargo', ['run', '--manifest-path', 'tray/Cargo.toml'], {
  env: {
    ...process.env,
    HERMES_RELAY_CLI_PATH: join(desktopRoot, 'dist', 'bin', 'hermes-relay-win-x64.exe')
  }
})
