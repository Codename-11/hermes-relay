#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const desktopRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
const write = args.includes('--write')
const expectIndex = args.indexOf('--expect')
const expected = expectIndex >= 0 ? args[expectIndex + 1] : undefined
const knownArgs = new Set(['--write', '--expect', expected])
const unknown = args.filter(arg => !knownArgs.has(arg))

if (expectIndex >= 0 && !expected) {
  throw new Error('--expect requires a version')
}
if (unknown.length > 0) {
  throw new Error(`unknown argument(s): ${unknown.join(', ')}`)
}

const paths = {
  packageJson: join(desktopRoot, 'package.json'),
  packageLock: join(desktopRoot, 'package-lock.json'),
  generatedVersion: join(desktopRoot, 'src', 'version.ts'),
  cargoToml: join(desktopRoot, 'tray', 'Cargo.toml'),
  cargoLock: join(desktopRoot, 'tray', 'Cargo.lock')
}

const packageJson = JSON.parse(readFileSync(paths.packageJson, 'utf8'))
const version = packageJson.version
if (typeof version !== 'string' || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.test(version)) {
  throw new Error(`desktop/package.json has an invalid SemVer version: ${String(version)}`)
}
if (expected && expected !== version) {
  throw new Error(`tag version ${expected} does not match desktop/package.json ${version}`)
}

function replaceChecked(path, label, pattern, replacement) {
  const current = readFileSync(path, 'utf8')
  if (!pattern.test(current)) {
    throw new Error(`could not locate ${label} in ${path}`)
  }
  pattern.lastIndex = 0
  const next = current.replace(pattern, replacement)
  if (next !== current) {
    writeFileSync(path, next)
  }
}

if (write) {
  let packageLockVersionCount = 0
  replaceChecked(
    paths.packageLock,
    'root package-lock versions',
    /("version"\s*:\s*")[^"]+(")/g,
    (match, prefix, suffix) => {
      packageLockVersionCount += 1
      return packageLockVersionCount <= 2 ? `${prefix}${version}${suffix}` : match
    }
  )
  if (packageLockVersionCount < 2) {
    throw new Error('desktop/package-lock.json did not contain both root version fields')
  }
  replaceChecked(
    paths.generatedVersion,
    'generated CLI version',
    /(export const VERSION\s*=\s*)["'][^"']+["'](\s+as const)/,
    `$1${JSON.stringify(version)}$2`
  )
  replaceChecked(
    paths.cargoToml,
    'Cargo package version',
    /(\[package\][\s\S]*?^version\s*=\s*")[^"]+("\s*$)/m,
    `$1${version}$2`
  )
  replaceChecked(
    paths.cargoLock,
    'Cargo lock package version',
    /(\[\[package\]\]\s*\r?\nname\s*=\s*"hermes-relay-tray"\s*\r?\nversion\s*=\s*")[^"]+("\s*$)/m,
    `$1${version}$2`
  )
}

const packageLock = JSON.parse(readFileSync(paths.packageLock, 'utf8'))
const generatedVersionText = readFileSync(paths.generatedVersion, 'utf8')
const cargoTomlText = readFileSync(paths.cargoToml, 'utf8')
const cargoLockText = readFileSync(paths.cargoLock, 'utf8')
const generatedVersion = generatedVersionText.match(/export const VERSION\s*=\s*["']([^"']+)["']/)?.[1]
const cargoVersion = cargoTomlText.match(/\[package\][\s\S]*?^version\s*=\s*"([^"]+)"/m)?.[1]
const cargoLockVersion = cargoLockText.match(
  /\[\[package\]\]\s*\r?\nname\s*=\s*"hermes-relay-tray"\s*\r?\nversion\s*=\s*"([^"]+)"/m
)?.[1]

const locations = [
  ['package-lock root', packageLock.version],
  ['package-lock workspace root', packageLock.packages?.['']?.version],
  ['generated src/version.ts', generatedVersion],
  ['Cargo package', cargoVersion],
  ['Cargo lock package', cargoLockVersion]
]
const mismatches = locations.filter(([, value]) => value !== version)

if (mismatches.length > 0) {
  for (const [label, value] of mismatches) {
    console.error(`version mismatch: ${label} is ${String(value)}, expected ${version}`)
  }
  console.error('Run `npm run sync:version` from desktop/ to repair generated metadata.')
  process.exit(1)
}

console.log(`CLI version sync OK: ${version}`)
