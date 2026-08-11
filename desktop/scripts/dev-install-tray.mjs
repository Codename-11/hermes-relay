#!/usr/bin/env node

import { copyFileSync, existsSync, renameSync, rmSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const desktopRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
if (process.platform !== 'win32') throw new Error('the optional systray is Windows-only')

const npmExecPath = process.env.npm_execpath
const npmCommand = npmExecPath ? process.execPath : (process.env.ComSpec ?? 'cmd.exe')
const npmArgs = npmExecPath
  ? [npmExecPath, '--prefix', 'tray', 'run', 'build']
  : ['/d', '/s', '/c', 'npm.cmd', '--prefix', 'tray', 'run', 'build']
const uiBuild = spawnSync(npmCommand, npmArgs, {
  cwd: desktopRoot,
  stdio: 'inherit'
})
if (uiBuild.error || uiBuild.status !== 0) process.exit(uiBuild.status ?? 1)

// Match the shipped build. Without custom-protocol Tauri follows devUrl and an
// installed tray tries to load 127.0.0.1:1420 instead of its embedded UI.
const build = spawnSync('cargo', ['build', '--release', '--features', 'custom-protocol', '--manifest-path', 'tray/Cargo.toml'], {
  cwd: desktopRoot,
  stdio: 'inherit'
})
if (build.error || build.status !== 0) process.exit(build.status ?? 1)

const source = join(desktopRoot, 'tray', 'target', 'release', 'hermes-relay-tray.exe')
const target = join(homedir(), '.hermes', 'bin', 'hermes-relay-tray.exe')
const backup = `${target}.bak`
if (!existsSync(source)) throw new Error(`expected tray build is missing: ${source}`)

if (existsSync(backup)) rmSync(backup, { force: true })
if (existsSync(target)) {
  try {
    renameSync(target, backup)
  } catch (error) {
    throw new Error(`could not replace ${target}; exit the running systray first (${error.code})`)
  }
}
try {
  copyFileSync(source, target)
} catch (error) {
  if (existsSync(backup)) renameSync(backup, target)
  throw error
}

const sizeMb = (statSync(target).size / (1024 * 1024)).toFixed(1)
console.log(`dev-install-tray: installed ${target} (${sizeMb} MB)`)
console.log(`dev-install-tray: previous systray saved as ${backup}`)
