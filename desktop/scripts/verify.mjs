#!/usr/bin/env node

import { spawnSync } from 'node:child_process'

const scripts = ['check:version-sync', 'type-check', 'test', 'build']
if (process.platform === 'win32') {
  scripts.push('smoke', 'tray:fmt', 'tray:lint', 'tray:check', 'tray:test')
} else {
  console.log('verify: tray and compiled-binary smoke gates run on Windows CI/release hosts')
}

for (const script of scripts) {
  const npmExecPath = process.env.npm_execpath
  const command = npmExecPath ? process.execPath : (process.platform === 'win32' ? 'npm.cmd' : 'npm')
  const args = npmExecPath ? [npmExecPath, 'run', script] : ['run', script]
  const result = spawnSync(command, args, { stdio: 'inherit' })
  if (result.error || result.status !== 0) {
    if (result.error) {
      console.error(`verify: failed to run ${script}: ${result.error.message}`)
    }
    process.exit(result.status ?? 1)
  }
}

console.log('verify: all local gates passed')
