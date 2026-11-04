import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFile, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

test('Windows startup probe verifies hashes and records success, native exit codes, and timeouts', {
  skip: process.platform !== 'win32', timeout: 40_000
}, async () => {
  const root = await mkdtemp(join(tmpdir(), 'relay-startup-runner-'))
  const runner = fileURLToPath(new URL('../scripts/test-windows-startup-probe.ps1', import.meta.url))
  try {
    // A small managed executable simulates exit status without crashing Windows
    // or generating a dump. No Bun download is needed for the runner's tests.
    execFileSync('powershell.exe', ['-NoProfile', '-Command', `
      Add-Type -OutputType ConsoleApplication -OutputAssembly $env:PROBE_TEST_OUTPUT -TypeDefinition @'
using System;
using System.IO;
using System.Diagnostics;
using System.Threading;
class Probe {
  static void Main() {
    string name = Path.GetFileName(Process.GetCurrentProcess().MainModule.FileName);
    if (name == "timeout.exe") Thread.Sleep(30000);
    if (name == "failure.exe") Environment.Exit(unchecked((int)0xC0000409));
    if (Directory.GetCurrentDirectory() != Environment.GetEnvironmentVariable("USERPROFILE")) Environment.Exit(2);
    if (Environment.GetEnvironmentVariable("HERMES_RELAY_TOKEN") != null) Environment.Exit(3);
    Console.WriteLine("relay-startup-probe:test");
  }
}
'@
    `], { windowsHide: true, env: { ...process.env, PROBE_TEST_OUTPUT: join(root, 'success.exe') } })
    await copyFile(join(root, 'success.exe'), join(root, 'failure.exe'))
    await copyFile(join(root, 'success.exe'), join(root, 'timeout.exe'))
    const binaries = await Promise.all(['success.exe', 'failure.exe', 'timeout.exe'].map(async file => ({
      file, kind: 'minimal', version: 'test', sha256: createHash('sha256').update(await readFile(join(root, file))).digest('hex')
    })))
    const manifest = { schemaVersion: 1, sourceCommit: 'test', sourceTag: 'test', binaries }
    await writeFile(join(root, 'manifest.json'), JSON.stringify(manifest))
    const run = () => execFileSync('powershell.exe', ['-NoProfile', '-File', runner,
      '-BundlePath', root, '-Repeats', '1', '-TimeoutSeconds', '2'], {
      windowsHide: true, encoding: 'utf8', timeout: 30_000,
      env: { ...process.env, HERMES_RELAY_TOKEN: 'test-sentinel-must-not-reach-child' }
    })
    run()
    const directories = (await readdir(root)).filter(file => file.startsWith('run-'))
    assert.equal(directories.length, 1)
    const report = JSON.parse((await readFile(join(root, directories[0]!, 'results.json'), 'utf8')).replace(/^\uFEFF/, ''))
    assert.equal(report.rows.length, 12)
    for (const row of report.rows) {
      if (row.file === 'success.exe') assert.equal(row.passed, true)
      if (row.file === 'failure.exe') {
        assert.equal(row.passed, false)
        assert.equal(row.exitHex, '0xC0000409')
        assert.equal(row.timedOut, false)
      }
      if (row.file === 'timeout.exe') {
        assert.equal(row.passed, false)
        assert.equal(row.timedOut, true)
      }
    }
    assert.equal(JSON.stringify(report).includes(root), false)
    binaries[0]!.sha256 = '0'.repeat(64)
    await writeFile(join(root, 'manifest.json'), JSON.stringify(manifest))
    assert.throws(run, /Checksum mismatch/)
    assert.deepEqual((await readdir(root)).filter(file => file.startsWith('run-')), directories)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
