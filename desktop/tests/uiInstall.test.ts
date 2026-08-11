import assert from 'node:assert/strict'
import { copyFile, mkdtemp, readFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import test from 'node:test'

import { uiExecutablePath } from '../src/commands/ui.js'
import { windowsInstallerLaunchPlan } from '../src/windowsInstaller.js'

test('UI executable follows an explicit install directory', () => {
  assert.equal(
    uiExecutablePath({ HERMES_RELAY_INSTALL_DIR: 'C:\\Hermes' }, 'C:\\runtime\\node.exe'),
    join('C:\\Hermes', 'hermes-relay-tray.exe')
  )
})

test('compiled CLI resolves a colocated UI executable', () => {
  assert.equal(
    uiExecutablePath({}, 'C:\\Hermes\\hermes-relay.exe'),
    join('C:\\Hermes', 'hermes-relay-tray.exe')
  )
})

test('installer launch is delayed until the running CLI can exit', () => {
  const plan = windowsInstallerLaunchPlan('C:\\Temp\\hermes setup.exe', {
    silent: true,
    installDir: 'C:\\Hermes custom'
  })
  assert.equal(plan.program, 'powershell.exe')
  assert.equal(plan.options.env?.HERMES_RELAY_SETUP_PATH, 'C:\\Temp\\hermes setup.exe')
  assert.equal(plan.options.env?.HERMES_RELAY_SETUP_SILENT, '1')
  assert.equal(plan.options.env?.HERMES_RELAY_SETUP_INSTALL_DIR, 'C:\\Hermes custom')
  assert.match(plan.args.join(' '), /Start-Sleep/)
  assert.match(plan.args.join(' '), /\/D=/)
  assert.match(plan.args.join(' '), /Remove-Item -LiteralPath \$installer/)
  assert.equal(plan.options.detached, true)
})

test('PowerShell launches an installer whose path contains spaces via environment transport', async () => {
  const scratch = await mkdtemp(join(tmpdir(), 'hermes ui installer '))
  const fakeInstaller = join(scratch, 'fake setup.exe')
  await copyFile(join(process.env.WINDIR ?? 'C:\\Windows', 'System32', 'whoami.exe'), fakeInstaller)
  try {
    const plan = windowsInstallerLaunchPlan(fakeInstaller, { silent: false, delayMs: 0 })
    const result = spawnSync(plan.program, plan.args, { ...plan.options, detached: false, stdio: 'pipe' })
    assert.equal(result.status, 0, result.stderr?.toString())
  } finally {
    await rm(scratch, { recursive: true, force: true })
  }
})

test('NSIS bundle installs and removes the stable UI shim', async () => {
  const script = await readFile(new URL('../tray/installer/hermes-relay.nsi', import.meta.url), 'utf8')
  assert.match(script, /File \/oname=hermes-relay-ui\.cmd "\$\{UI_SHIM\}"/)
  assert.match(script, /Hermes-Relay CLI UI\.lnk" "\$INSTDIR\\hermes-relay-ui\.cmd"/)
  assert.match(script, /Delete "\$INSTDIR\\hermes-relay-ui\.cmd"/)
})

test('NSIS bundle cleans cooperative-update and local-development backups', async () => {
  const script = await readFile(new URL('../tray/installer/hermes-relay.nsi', import.meta.url), 'utf8')
  for (const artifact of [
    'hermes-relay.new.exe',
    'hermes-relay.old.exe',
    'hermes-relay.exe.bak',
    'hermes-relay-tray.exe.bak'
  ]) {
    assert.ok(
      script.includes(`Delete "$INSTDIR\\${artifact}"`),
      `installer should remove ${artifact}`
    )
  }
})

test('local tray installation embeds production assets instead of loading devUrl', async () => {
  const script = await readFile(new URL('../scripts/dev-install-tray.mjs', import.meta.url), 'utf8')
  assert.match(script, /'--features', 'custom-protocol'/)
})

test('CLI opens the GUI process without forcing a hidden Windows startup state', async () => {
  const source = await readFile(new URL('../src/commands/ui.ts', import.meta.url), 'utf8')
  assert.match(source, /spawn\(path, \['--show'\]/)
  assert.doesNotMatch(source, /windowsHide:\s*true/)
})

test('POSIX installer only advertises artifacts produced by the release workflow', async () => {
  const script = await readFile(new URL('../scripts/install.sh', import.meta.url), 'utf8')
  assert.doesNotMatch(script, /hermes-relay-linux-arm64/)
  assert.match(script, /hermes-relay-linux-x64/)
})

test('PowerShell uninstaller delegates bundle cleanup to the NSIS uninstaller', async () => {
  const script = await readFile(new URL('../scripts/uninstall.ps1', import.meta.url), 'utf8')
  assert.match(script, /uninstall-hermes-relay\.exe/)
  assert.match(script, /Start-Process -FilePath \$bundleUninstaller -ArgumentList '\/S' -Wait/)
})

test('PowerShell tray bootstrap preserves the install directory and daemon lifecycle', async () => {
  const script = await readFile(new URL('../scripts/install.ps1', import.meta.url), 'utf8')
  assert.match(script, /HKCU:\\Software\\HermesRelay/)
  assert.match(script, /\$installerArgs \+= "\/D=\$dir"/)
  assert.match(script, /daemon status --json/)
  assert.match(script, /daemon', 'start/)
})
