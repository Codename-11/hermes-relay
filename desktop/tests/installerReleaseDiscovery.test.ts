import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import test from 'node:test'

const installer = readFileSync(new URL('../scripts/install.ps1', import.meta.url), 'utf8')
const resolver = installer.slice(installer.indexOf('$resolvedVersion = $version'), installer.indexOf('$base = '))
const release = (tag_name: string) => ({ tag_name, prerelease: tag_name.includes('-beta.') })

for (const shell of ['powershell.exe', 'pwsh.exe']) {
  const available = spawnSync(shell, ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'], { timeout: 30_000 }).status === 0
  const cases = [
    { name: 'mixed tracks', pages: [[release('android-v1.18.1'), release('server-v1.12.0'), release('desktop-v0.4.0-beta.7')]], expected: 'desktop-v0.4.0-beta.7' },
    { name: 'numeric prerelease ordering', pages: [[release('desktop-v0.4.0-beta.10'), release('desktop-v0.4.0-beta.9')]], expected: 'desktop-v0.4.0-beta.10' },
    { name: 'stable outranks prerelease', pages: [[release('desktop-v0.4.0'), release('desktop-v0.4.0-beta.10')]], expected: 'desktop-v0.4.0' },
    { name: 'historical fallback', pages: [[release('android-v1.18.1'), release('cli-v0.3.0-alpha.18')]], expected: 'cli-v0.3.0-alpha.18' },
    { name: 'desktop on later page', pages: [Array.from({ length: 100 }, (_, i) => release(`android-v1.0.${i}`)), [release('desktop-v0.4.0-beta.7')]], expected: 'desktop-v0.4.0-beta.7' },
  ]
  for (const scenario of cases) {
    test(`${shell}: installer resolves ${scenario.name}`, { skip: !available }, () => {
      const pages = scenario.pages.map(page => {
        const payload = Buffer.from(JSON.stringify(page)).toString('base64')
        return `$response = ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${payload}')))\n$script:pages += ,$response`
      }).join('\n')
      const script = `
$ErrorActionPreference = 'Stop'
$repo = 'example/relay'
$version = 'latest'
$script:pages = @()
${pages}
$script:calls = 0
function Say($msg) {}
function Die($msg) { throw $msg }
function Invoke-RestMethod {
  param([switch]$UseBasicParsing, [string]$Uri)
  if ($Uri -notmatch ('page=' + ($script:calls + 1) + '$')) { throw 'Wrong API page' }
  $response = $script:pages[$script:calls]
  $script:calls += 1
  # Match Invoke-RestMethod: emit a JSON array as one pipeline object.
  Write-Output -NoEnumerate $response
}
${resolver}
if ($script:calls -ne ${scenario.pages.length}) { throw 'Wrong API request count' }
Write-Output $resolvedVersion
`
      const result = spawnSync(shell, ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], { encoding: 'utf8', timeout: 30_000 })
      assert.equal(result.status, 0, result.stderr || result.error?.message)
      assert.equal(result.stdout.trim(), scenario.expected)
    })
  }
}
