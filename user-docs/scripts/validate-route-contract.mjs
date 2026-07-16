import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const docsRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = resolve(docsRoot, '..')

const [serverSource, relayContract, relayOperations] = await Promise.all([
  readFile(resolve(repoRoot, 'plugin/relay/server.py'), 'utf8'),
  readFile(resolve(docsRoot, 'reference/relay-api.md'), 'utf8'),
  readFile(resolve(docsRoot, 'reference/relay-server.md'), 'utf8'),
])

const routePattern = /app\.router\.add_(?:get|post|put|patch|delete)\(\s*["']([^"']+)["']/g
const registeredRoutes = new Set()

for (const match of serverSource.matchAll(routePattern)) {
  registeredRoutes.add(match[1])
}

if (registeredRoutes.size === 0) {
  throw new Error('No Relay HTTP routes found in plugin/relay/server.py')
}

const publicReference = `${relayContract}\n${relayOperations}`
const undocumented = [...registeredRoutes]
  .filter((route) => !publicReference.includes(`\`${route}\``))
  .sort()

if (undocumented.length > 0) {
  throw new Error(
    `Relay route contract is missing ${undocumented.length} registered route(s):\n${undocumented.join('\n')}`,
  )
}

console.log(`Validated ${registeredRoutes.size} registered Relay HTTP paths against the public reference.`)
