#!/usr/bin/env node
// Local dev install — build the bun binary for THIS platform and drop it over
// the curl-installed binary at ~/.hermes/bin/, so `hermes-relay` on your PATH
// runs your working tree. This is the "test my changes as the REAL binary"
// loop; it is NOT the release path (that's .github/workflows/release-desktop.yml).
//
//   npm run dev:install        # build + replace the installed binary
//
// The previous binary is saved next to it as `.bak` (reversible). On Windows a
// running daemon locks the .exe — this surfaces that clearly instead of EBUSY.

import { execSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, renameSync, rmSync, statSync } from 'node:fs'
import { arch, homedir, platform } from 'node:os'
import { join } from 'node:path'

const plat = platform()
const isWin = plat === 'win32'

// Map the current platform to the package.json build target + its artifact name.
const target = {
  win32: { script: 'build:bin:win', out: 'hermes-relay-win-x64.exe' },
  linux: { script: 'build:bin:linux', out: 'hermes-relay-linux-x64' },
  darwin:
    arch() === 'arm64'
      ? { script: 'build:bin:mac-arm', out: 'hermes-relay-darwin-arm64' }
      : { script: 'build:bin:mac-x64', out: 'hermes-relay-darwin-x64' }
}[plat]

if (!target) {
  console.error(`dev-install: unsupported platform ${plat}`)
  process.exit(1)
}

const binDir = join(homedir(), '.hermes', 'bin')
const installed = join(binDir, isWin ? 'hermes-relay.exe' : 'hermes-relay')
const backup = installed + '.bak'
const built = join('dist', 'bin', target.out)

console.log(`dev-install: building ${target.script} (bun --compile)…`)
execSync(`npm run ${target.script}`, { stdio: 'inherit' })

if (!existsSync(built)) {
  console.error(`dev-install: expected build artifact is missing: ${built}`)
  process.exit(1)
}

mkdirSync(binDir, { recursive: true })

// Move the current binary aside (reversible). On Windows you can't overwrite a
// running .exe, so a held lock means a live daemon — say so plainly.
if (existsSync(installed)) {
  if (existsSync(backup)) {
    rmSync(backup, { force: true })
  }
  try {
    renameSync(installed, backup)
  } catch (e) {
    console.error(`dev-install: couldn't move the current binary aside (${e.code}).`)
    if (e.code === 'EBUSY' || e.code === 'EPERM') {
      console.error('  A daemon is probably running from it. Check + stop it first:')
      console.error('    hermes-relay daemon --status')
    }
    process.exit(1)
  }
}

try {
  copyFileSync(built, installed)
  if (!isWin) {
    execSync(`chmod +x "${installed}"`)
  }
} catch (e) {
  console.error(`dev-install: copy failed (${e.code}); restoring the previous binary.`)
  if (existsSync(backup)) {
    renameSync(backup, installed)
  }
  process.exit(1)
}

const sizeMb = (statSync(installed).size / (1024 * 1024)).toFixed(0)
console.log(`dev-install: installed ${installed} (${sizeMb} MB)`)
try {
  const ver = execSync(`"${installed}" --version`, { encoding: 'utf8' }).trim()
  console.log(`dev-install: ${ver}`)
} catch {
  /* version readback is best-effort */
}
console.log(`dev-install: done — previous binary saved as ${backup} (delete when happy).`)
