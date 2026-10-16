import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { dirname, extname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const defaultRepositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')

export const desktopUiScreenshotSourceFiles = Object.freeze([
  'desktop/tray/ui/App.tsx',
  'desktop/tray/ui/computerActivity.ts',
  'desktop/tray/ui/main.tsx',
  'desktop/tray/ui/styles.css',
  'desktop/tray/ui/types.ts',
  'desktop/tray/index.html',
  'desktop/tray/icons/icon-256.png',
  'desktop/tray/package-lock.json',
  'desktop/src/endpoint.ts',
  'desktop/src/transportSecurity.ts',
  'desktop/tray/scripts/vite.screenshots.config.mjs',
  'desktop/tray/scripts/capture-desktop-ui.mjs',
  'desktop/tray/scripts/desktop-ui-source-fingerprint.mjs'
])

const binaryExtensions = new Set(['.png'])

function normalizedSource(relativePath, bytes) {
  if (binaryExtensions.has(extname(relativePath))) return bytes
  const text = bytes.toString('utf8').replace(/\r\n?/g, '\n')
  if (relativePath !== 'desktop/tray/package-lock.json') return Buffer.from(text, 'utf8')

  // The screenshot fixture has fixed display versions. A release-only bump of
  // the root package does not change it; dependency versions still affect rendering.
  const lockfile = JSON.parse(text)
  delete lockfile.version
  if (lockfile.packages?.['']) delete lockfile.packages[''].version
  return Buffer.from(JSON.stringify(lockfile), 'utf8')
}

export async function computeDesktopUiSourceFingerprint(repositoryRoot = defaultRepositoryRoot) {
  const hash = createHash('sha256')
  for (const relativePath of desktopUiScreenshotSourceFiles) {
    const bytes = await readFile(resolve(repositoryRoot, relativePath))
    const normalized = normalizedSource(relativePath, bytes)
    hash.update(relativePath)
    hash.update('\0')
    hash.update(normalized)
    hash.update('\0')
  }
  return {
    algorithm: 'sha256',
    normalization: 'text-lf-lockfile-root-version-v2',
    digest: hash.digest('hex'),
    files: [...desktopUiScreenshotSourceFiles]
  }
}
