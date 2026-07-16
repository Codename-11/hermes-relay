import { copyFile, mkdir, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const docsRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = resolve(docsRoot, '..')

const assets = [
  {
    source: resolve(repoRoot, 'assets/screenshots/02_chat.png'),
    destination: resolve(docsRoot, 'public/chat-demo.png'),
  },
  {
    source: resolve(repoRoot, 'assets/screenshots/07_connections.png'),
    destination: resolve(docsRoot, 'public/connections-demo.png'),
  },
]

for (const asset of assets) {
  await stat(asset.source)
  await mkdir(dirname(asset.destination), { recursive: true })
  await copyFile(asset.source, asset.destination)
}

console.log(`Synced ${assets.length} canonical docs asset${assets.length === 1 ? '' : 's'}.`)
