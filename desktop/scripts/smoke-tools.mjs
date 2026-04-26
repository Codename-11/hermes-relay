// Smoke-test the new desktop tool handlers without any router/transport
// plumbing — just imports the compiled modules and exercises each handler
// in-process. Run after `npm run build` from the desktop directory:
//
//   node scripts/smoke-tools.mjs
//
// Exits non-zero on any failure. Good enough as a CI gate for the new
// surface; the full integration story still goes through a paired phone
// + relay smoke run on hermes-host.

import * as path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const distRoot = path.resolve(here, '..', 'dist', 'tools', 'handlers')

const ctx = {
  cwd: process.cwd(),
  abortSignal: new AbortController().signal,
  interactive: false
}

async function main() {
  // ── PowerShell ─────────────────────────────────────────────────────────
  const ps = await import(pathToFileURL(path.join(distRoot, 'powershell.js')).href)
  const psScript = `Write-Output 'hello with "quotes" and $dollar'`
  const psResult = await ps.powershellHandler({ script: psScript }, ctx)
  console.log(`PS exit=${psResult.exit_code} shell=${psResult.shell} stdout=${JSON.stringify(psResult.stdout.trim())}`)
  if (psResult.exit_code !== 0) {
    throw new Error(`PowerShell exit nonzero: ${psResult.stderr}`)
  }
  if (!psResult.stdout.includes('hello with')) {
    throw new Error(`PowerShell stdout missing expected text: ${psResult.stdout}`)
  }

  // ── Process listing ────────────────────────────────────────────────────
  const proc = await import(pathToFileURL(path.join(distRoot, 'process.js')).href)
  const list = await proc.listProcessesHandler({ filter: 'node', limit: 5 }, ctx)
  console.log(`PROCS found=${list.count} of total=${list.total}`)
  if (list.count === 0) {
    throw new Error('expected at least 1 node process')
  }

  // ── Checksum ───────────────────────────────────────────────────────────
  const xfer = await import(pathToFileURL(path.join(distRoot, 'transfer.js')).href)
  const sum = await xfer.checksumHandler({ path: 'package.json' }, ctx)
  console.log(`SHA256 ${sum.hash.slice(0, 16)}... ${sum.size_bytes} bytes`)
  if (sum.algorithm !== 'sha256') {
    throw new Error(`algo mismatch: ${sum.algorithm}`)
  }

  // ── Experimental computer-use advertisement and fail-closed action ───
  const handlerSet = await import(pathToFileURL(path.join(distRoot, '..', 'handlerSet.js')).href)
  const defaultAdvertised = handlerSet.advertisedDesktopTools()
  const experimentalAdvertised = handlerSet.advertisedDesktopTools({ computerUse: true })
  if (defaultAdvertised.includes('desktop_computer_action')) {
    throw new Error('computer-use tools should not advertise by default')
  }
  if (!experimentalAdvertised.includes('desktop_computer_action')) {
    throw new Error('computer-use tools should advertise with explicit opt-in')
  }
  const computer = await import(pathToFileURL(path.join(distRoot, 'computer.js')).href)
  const invalidAction = await computer.computerActionHandler({ action: 'left_click' }, ctx)
  console.log(`COMPUTER invalid action code=${invalidAction.code}`)
  if (invalidAction.ok !== false || invalidAction.code !== 'invalid_request') {
    throw new Error(`expected invalid_request before grant, got ${JSON.stringify(invalidAction)}`)
  }
  const action = await computer.computerActionHandler({ action: 'wait', duration_ms: 1 }, ctx)
  console.log(`COMPUTER action code=${action.code}`)
  if (action.ok !== false || action.code !== 'grant_required') {
    throw new Error(`expected grant_required fail-closed action, got ${JSON.stringify(action)}`)
  }
  const grants = await import(pathToFileURL(path.join(distRoot, '..', 'computerGrants.js')).href)
  grants.configureComputerUseRuntime({
    url: 'smoke',
    computerUseConsented: true,
    consentSource: 'override'
  })
  const grant = await computer.computerGrantRequestHandler({
    mode: 'assist',
    duration_seconds: 30,
    reason: 'smoke test'
  }, ctx)
  if (grant.ok !== true) {
    throw new Error(`expected assist grant with runtime consent, got ${JSON.stringify(grant)}`)
  }
  const nonInteractiveAction = await computer.computerActionHandler(
    { action: 'wait', duration_ms: 1 },
    ctx
  )
  console.log(`COMPUTER granted action code=${nonInteractiveAction.code}`)
  if (nonInteractiveAction.ok !== false || nonInteractiveAction.code !== 'not_interactive') {
    throw new Error(`expected not_interactive fail-closed action, got ${JSON.stringify(nonInteractiveAction)}`)
  }
  await computer.computerCancelHandler({ reason: 'smoke cleanup' }, ctx)

  // ── Job lifecycle ──────────────────────────────────────────────────────
  const jobs = await import(pathToFileURL(path.join(distRoot, 'jobs.js')).href)
  const cmd =
    process.platform === 'win32'
      ? 'echo hello && timeout /t 1 /nobreak >NUL && echo world'
      : 'echo hello; sleep 0.3; echo world'
  const start = await jobs.jobStartHandler({ command: cmd }, ctx)
  console.log(`JOB started job_id=${start.job_id} pid=${start.pid}`)
  await new Promise(r => setTimeout(r, 1500))
  const status = await jobs.jobStatusHandler({ job_id: start.job_id }, ctx)
  console.log(`JOB status state=${status.state} exit_code=${status.exit_code}`)
  const logs = await jobs.jobLogsHandler(
    { job_id: start.job_id, stream: 'stdout' },
    ctx
  )
  console.log(`JOB logs stdout=${JSON.stringify(logs.stdout.content.trim().slice(0, 80))}`)
  if (status.state === 'running') {
    throw new Error('expected job to have finished')
  }
  if (!logs.stdout.content.toLowerCase().includes('hello')) {
    throw new Error(`expected 'hello' in stdout, got: ${logs.stdout.content}`)
  }

  console.log('SMOKE OK')
}

main().catch(e => {
  console.error('SMOKE FAIL', e)
  process.exit(1)
})
