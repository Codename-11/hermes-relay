// Build a diagnostic comparison from one immutable source, without installing it.
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve, join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

if (process.platform !== 'win32' || !process.argv[2]) {
  throw new Error('Usage (Windows, Node 21+): node scripts/build-windows-startup-probe.mjs <new-output-directory>')
}
const scriptDir = dirname(fileURLToPath(import.meta.url))
const repo = resolve(scriptDir, '../..')
const output = resolve(process.argv[2])
await mkdir(dirname(output), { recursive: true })
await mkdir(output) // Refuse to overwrite an existing bundle.
const sourceCommit = '3200f696cd51645c275f97635312294785894137'
const sourceTag = 'desktop-v0.4.0-beta.8'
const builderCommit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim()
const source = join(output, 'source')
await mkdir(source)
execFileSync('git', ['archive', sourceCommit, 'desktop', '-o', join(output, 'source.tar')], { cwd: repo })
execFileSync('tar', ['-xf', join(output, 'source.tar'), '-C', source])
const hash = async path => createHash('sha256').update(await readFile(path)).digest('hex')
async function download(url, path, expected) {
  execFileSync('curl.exe', ['--fail', '--location', '--silent', '--show-error', '--output', path, url], { timeout: 180_000 })
  if (await hash(path) !== expected) throw new Error(`Checksum mismatch: ${url}`)
}
const variants = [
  { version: '1.3.14', target: 'bun-windows-x64', sha256: '0a0620930b6675d7ba440e81f4e0e00d3cfbe096c4b140d3fff02205e9e18922' },
  { version: '1.4.0', target: 'bun-windows-x64', sha256: 'e6f093d39da486b20262ca8cdd5ed6a9e8bc9c2f275b78e6d3a0c5b28cc95901' },
  { version: '1.4.0', target: 'bun-windows-x64-baseline', sha256: 'b929c54a9badb104a16dedd23aab6152c86793ae653d4e6b13983ffd0c882a66' }
]
const licenseHashes = {
  '1.3.14': '2c6160ec8fb853f7e8f97d9b249e756c9b0ac44860a68b6bf4f1b0bcbc5c3741',
  '1.4.0': 'b9caf52728691b4057e371232c221a132883198be2f3d2ddf92c90404c984b1a'
}
const licenseFiles = ['LICENSE-Relay.txt']
await writeFile(join(output, 'LICENSE-Relay.txt'), execFileSync('git', ['show', `${sourceCommit}:LICENSE`], { cwd: repo }))
for (const [version, sha256] of Object.entries(licenseHashes)) {
  const file = `LICENSE-Bun-${version}.md`
  await download(`https://raw.githubusercontent.com/oven-sh/bun/bun-v${version}/LICENSE.md`, join(output, file), sha256)
  licenseFiles.push(file)
}
const binaries = []
const addBinary = async (file, kind, version, target, sourceUrl) => {
  binaries.push({ file, kind, version, target, sourceUrl, sha256: await hash(join(output, file)) })
}
const releaseUrl = `https://github.com/Codename-11/hermes-relay/releases/download/${sourceTag}/hermes-relay-win-x64.exe`
await download(releaseUrl, join(output, 'official-beta8.exe'), '4f76f692f6e65a0231db1a77b4444a41e005a96528228c91232d1926b845bc57')
await addBinary('official-beta8.exe', 'relay', '1.3.14', 'bun-windows-x64', releaseUrl)
await writeFile(join(source, 'minimal.ts'), 'console.log("relay-startup-probe:" + process.versions.bun)\n')
for (const variant of variants) {
  const id = `${variant.version}-${variant.target}`
  const url = `https://github.com/oven-sh/bun/releases/download/bun-v${variant.version}/${variant.target}.zip`
  const archive = join(output, `${id}.zip`)
  await download(url, archive, variant.sha256)
  const unpack = join(output, `runtime-${id}`)
  execFileSync('tar', ['-xf', archive, '-C', await mkdir(unpack).then(() => unpack)])
  const runtime = join(unpack, variant.target, 'bun.exe')
  const actualVersion = execFileSync(runtime, ['--version'], { encoding: 'utf8', windowsHide: true }).trim()
  if (actualVersion !== variant.version) throw new Error(`Unexpected Bun version: ${actualVersion}`)
  const runtimeFile = `bun-${id}.exe`
  await copyFile(runtime, join(output, runtimeFile))
  await addBinary(runtimeFile, 'runtime', variant.version, variant.target, url)
  for (const [kind, entry] of [['minimal', join(source, 'minimal.ts')], ['relay', 'src/bunWindowsEntry.ts']]) {
    const file = `${kind}-${id}.exe`
    execFileSync(runtime, ['build', '--compile', '--minify', '--sourcemap', `--target=${variant.target}`, entry, '--outfile', join(output, file)], {
      cwd: join(source, 'desktop'), windowsHide: true, timeout: 180_000, stdio: 'inherit'
    })
    await addBinary(file, kind, variant.version, variant.target, url)
  }
}
await writeFile(join(output, 'manifest.json'), JSON.stringify({ schemaVersion: 1, sourceTag, sourceCommit, builderCommit, variants, binaries }, null, 2) + '\n')
await copyFile(join(scriptDir, 'test-windows-startup-probe.ps1'), join(output, 'test-windows-startup-probe.ps1'))
await copyFile(join(scriptDir, 'build-windows-startup-probe.mjs'), join(output, 'build-windows-startup-probe.mjs'))
await copyFile(join(scriptDir, 'windows-startup-probe-README.md'), join(output, 'README.md'))
await copyFile(join(source, 'minimal.ts'), join(output, 'minimal.ts'))
await writeFile(join(output, 'SHA256SUMS.txt'), (await Promise.all(
  [...binaries.map(item => item.file), 'manifest.json', 'test-windows-startup-probe.ps1',
    'build-windows-startup-probe.mjs', 'README.md', 'minimal.ts', ...licenseFiles].map(async file => `${await hash(join(output, file))}  ${file}`)
)).join('\n') + '\n')
console.log(`Prepared ${binaries.length} executables in ${output}. Run test-windows-startup-probe.ps1 there; nothing was installed.`)
