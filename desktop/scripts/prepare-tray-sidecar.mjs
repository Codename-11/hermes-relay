#!/usr/bin/env node
import { copyFileSync, existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const desktopRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const targets = {
  'win32:x64': {
    buildScript: 'build:bin:win',
    source: join('dist', 'bin', 'hermes-relay-win-x64.exe'),
    sidecar: join('tray', 'src-tauri', 'bin', 'hermes-relay-x86_64-pc-windows-msvc.exe')
  },
  'linux:x64': {
    buildScript: 'build:bin:linux',
    source: join('dist', 'bin', 'hermes-relay-linux-x64'),
    sidecar: join('tray', 'src-tauri', 'bin', 'hermes-relay-x86_64-unknown-linux-gnu')
  },
  'darwin:x64': {
    buildScript: 'build:bin:mac-x64',
    source: join('dist', 'bin', 'hermes-relay-darwin-x64'),
    sidecar: join('tray', 'src-tauri', 'bin', 'hermes-relay-x86_64-apple-darwin')
  },
  'darwin:arm64': {
    buildScript: 'build:bin:mac-arm',
    source: join('dist', 'bin', 'hermes-relay-darwin-arm64'),
    sidecar: join('tray', 'src-tauri', 'bin', 'hermes-relay-aarch64-apple-darwin')
  }
};

const target = targets[`${process.platform}:${process.arch}`];
if (!target) {
  throw new Error(`unsupported tray sidecar platform: ${process.platform}/${process.arch}`);
}

const stubOnly = process.argv.includes('--stub');
const sidecar = join(desktopRoot, target.sidecar);
mkdirSync(dirname(sidecar), { recursive: true });

if (stubOnly) {
  if (!existsSync(sidecar)) {
    writeFileSync(sidecar, '');
    console.log(`tray sidecar check stub ready: ${sidecar}`);
  } else {
    console.log(`tray sidecar already present: ${sidecar}`);
  }
  process.exit(0);
}

const npmExecPath = process.env.npm_execpath;
const npmCommand = npmExecPath ? process.execPath : (process.platform === 'win32' ? 'npm.cmd' : 'npm');
const npmArgs = npmExecPath ? [npmExecPath, 'run', target.buildScript] : ['run', target.buildScript];
const build = spawnSync(npmCommand, npmArgs, {
  cwd: desktopRoot,
  stdio: 'inherit'
});
if (build.error || build.status !== 0) {
  const detail = build.error ? `: ${build.error.message}` : '';
  throw new Error(`failed to build tray sidecar with npm run ${target.buildScript}${detail}`);
}

const source = join(desktopRoot, target.source);
if (!existsSync(source)) {
  throw new Error(`expected sidecar source was not produced: ${source}`);
}

copyFileSync(source, sidecar);

if (process.platform !== 'win32') {
  const mode = statSync(sidecar).mode | 0o755;
  await import('node:fs').then(({ chmodSync }) => chmodSync(sidecar, mode));
}

console.log(`tray sidecar ready: ${sidecar}`);
