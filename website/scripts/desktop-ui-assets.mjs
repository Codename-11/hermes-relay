import { createHash } from 'node:crypto'
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'
import { computeDesktopUiSourceFingerprint } from '../../desktop/tray/scripts/desktop-ui-source-fingerprint.mjs'

const websiteRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repositoryRoot = resolve(websiteRoot, '..')
const manifestPath = resolve(repositoryRoot, 'docs/media/desktop-ui-screenshots.json')
const destinationDir = resolve(websiteRoot, 'public/product/desktop-ui')
const mode = process.argv[2] ?? 'check'

if (!['check', 'sync'].includes(mode)) {
  console.error('Usage: node scripts/desktop-ui-assets.mjs <check|sync>')
  process.exit(2)
}

const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
const failures = []
await mkdir(destinationDir, { recursive: true })

const currentFingerprint = await computeDesktopUiSourceFingerprint(repositoryRoot)
const recordedFingerprint = manifest.sourceFingerprint
const fingerprintMatches = recordedFingerprint?.algorithm === currentFingerprint.algorithm
  && recordedFingerprint?.normalization === currentFingerprint.normalization
  && recordedFingerprint?.digest === currentFingerprint.digest
  && JSON.stringify(recordedFingerprint?.files) === JSON.stringify(currentFingerprint.files)

if (!fingerprintMatches) {
  console.error('Desktop UI canonical screenshots are stale relative to their capture sources.')
  console.error(`- recorded: ${recordedFingerprint?.digest ?? 'missing'}`)
  console.error(`- current:  ${currentFingerprint.digest}`)
  console.error('\nRun `node desktop/tray/scripts/capture-desktop-ui.mjs` from the repository root before syncing website assets.')
  process.exit(1)
}

const shortHash = bytes => createHash('sha256').update(bytes).digest('hex').slice(0, 12)

for (const scene of manifest.scenes) {
  const source = resolve(repositoryRoot, scene.source)
  const pngDestination = resolve(repositoryRoot, scene.website)
  const webpDestination = resolve(destinationDir, `${scene.id}-493.webp`)

  try {
    const sourceBytes = await readFile(source)
    const webpBytes = await sharp(sourceBytes)
      .resize({ width: 493, withoutEnlargement: true })
      .webp({ quality: 84, effort: 4 })
      .toBuffer()

    if (mode === 'sync') {
      await copyFile(source, pngDestination)
      await writeFile(webpDestination, webpBytes)
      console.log(`synced ${scene.id}.png and ${scene.id}-493.webp`)
      continue
    }

    const [actualPng, actualWebp] = await Promise.all([
      readFile(pngDestination),
      readFile(webpDestination)
    ])
    if (!actualPng.equals(sourceBytes)) failures.push(`${scene.id}.png: stale (${shortHash(actualPng)} != ${shortHash(sourceBytes)})`)
    if (!actualWebp.equals(webpBytes)) failures.push(`${scene.id}-493.webp: stale derivative`)
    if (actualPng.equals(sourceBytes) && actualWebp.equals(webpBytes)) console.log(`verified ${scene.id}`)
  } catch (error) {
    failures.push(`${scene.id}: ${error instanceof Error ? error.message : String(error)}`)
  }
}

if (failures.length) {
  console.error('\nDesktop UI screenshot drift detected:')
  failures.forEach(failure => console.error(`- ${failure}`))
  console.error('\nRun `node scripts/desktop-ui-assets.mjs sync` from website/.')
  process.exit(1)
}

if (mode === 'check') console.log('Desktop UI website assets match their canonical screenshots.')
