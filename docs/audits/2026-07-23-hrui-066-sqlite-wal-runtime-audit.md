# HRUI-066 SQLite/WAL Runtime Audit

Date: 2026-07-23

## Scope

HRUI-066 tracks the operational risk that existing Hermes SQLite databases remain
in WAL mode while the live service interpreter is linked against a SQLite runtime
with the multi-process WAL-reset corruption bug. This report extends the
HRUI-056 update/restart audit with the runtime and database-integrity gate that
must be satisfied before a Hermes source update is considered app/Relay-safe.

No service was restarted or modified during this audit.

## Read-only live-host evidence

Read-only SSH inspection of the live operator host found:

- System `python3`: Python 3.12.3, SQLite 3.45.1.
- Hermes service interpreter: `<hermes-agent-venv>/bin/python`,
  Python 3.11.13, SQLite 3.47.1.
- Active Hermes process classes using that interpreter included Relay, profile
  gateways, dashboard, proxy, MCP watchdog children, and slash worker processes.
- Thirty active non-archive Hermes `.db` files under the Hermes home
  were sampled with read-only `PRAGMA journal_mode`; every sampled database
  reported `wal`.

The sampled active WAL databases included root stores (`state.db`,
`projects.db`, `response_store.db`, `kanban.db`, `cron/executions.db`,
`verification_evidence.db`) plus multiple profile-scoped stores.

## Risk statement

Upstream now refuses to enable WAL for new databases when the linked SQLite is
known vulnerable, but it intentionally does not flip existing live WAL databases
back to DELETE mode. That is the correct upstream safety boundary: changing WAL
mode or deleting `-wal` / `-shm` files while writers may be active can itself
lose data.

The local risk therefore remains operational rather than code-only:

1. Upgrade/rebuild the service interpreter onto a fixed SQLite line.
2. Stop all writers as one controlled maintenance window.
3. Back up the databases while writers are stopped.
4. Run integrity checks.
5. Restart the complete Hermes service fleet on the upgraded interpreter.

Advancing Hermes source without changing the runtime only preserves the current
exposure and adds warnings.

## Required maintenance gate

Before closing HRUI-066, prove all of the following:

- The service interpreter used by every Hermes process reports a fixed SQLite
  version/source id, not just the shell `python3`.
- Dashboard, Relay, every configured profile gateway, proxy, cron/scheduler
  workers, MCP watchdog children, and deployer/watcher sidecars have been
  stopped or drained before the database backup/integrity pass.
- Every active non-archive database under the Hermes home has a fresh backup
  taken with writers stopped.
- `PRAGMA integrity_check` returns `ok` for every active non-archive database.
- All Hermes service classes restart after the interpreter upgrade.
- A phone-reachable dashboard status check succeeds.
- A representative gateway chat turn succeeds on at least one default/named
  profile.
- A representative cron execution or scheduler-backed task runs after restart.
- `hermes doctor` no longer reports the WAL-reset warning.
- No pre-upgrade Hermes process remains alive.

## Operator notes

- Do not attempt an online WAL-to-DELETE migration.
- Do not delete `*.db-wal` or `*.db-shm` files manually.
- Do not treat a clean source checkout as proof that the running interpreter was
  upgraded.
- Tie this maintenance to the HRUI-056 atomic update/restart workflow so source,
  interpreter, and long-running process state advance together.
- Use `docs/audits/2026-08-13-hrui-runtime-safety-preflight.md` for the combined
  HRUI-056/066/159/160/162 evidence sequence and
  `scripts/hrui-runtime-preflight.sh` for read-only preflight/postflight capture.

## HRUI-078 offline session recovery escalation

Current Hermes provides `hermes sessions recover` for a damaged root or
profile `state.db`. This is an incident escalation, not a routine integrity
step and not an online repair. The command copies the supplied database plus
its WAL, SHM, or rollback-journal sidecars into disposable work storage before
SQLite opens the copy, rebuilds canonical rows and FTS into a new database, and
never installs the result over the active store.

Use it only after all writers for the affected Hermes home or profile are
stopped and a consistent source bundle is preserved. Start with inspection:

```bash
hermes sessions recover \
  --source /path/to/preserved/state.db \
  --inspect-only
```

If inspection reports the canonical data recoverable, create a separate output
and JSON report. The command refuses to overwrite an existing output, sidecar,
or report:

```bash
hermes sessions recover \
  --source /path/to/preserved/state.db \
  --output /path/to/recovery/recovered-state.db \
  --report /path/to/recovery/recovered-state.db.recovery.json
```

Accept complete recovery for further review only when the report says
`complete: true` and its integrity, foreign-key, FTS, and table-count checks
pass. If complete recovery fails, `--allow-partial` may salvage readable rowid
ranges into another new output. Review every skipped range, removed or retained
message count, reconstructed placeholder session, metadata-loss warning, and
orphan count; require `verified: true` and `partial: true`. A verified partial
result is still incomplete.

When canonical schemas are unreadable, the same partial mode may use a
page-level `lost_and_found` lane. That last-resort path requires a `sqlite3`
command-line shell whose `.recover` command includes `sqlite_dbpage`; Python's
`sqlite3` module is not sufficient, and Hermes probes the actual shell
capability. Treat `best_effort: true` as a mandatory manual-review signal
because row mappings and reconstructed metadata are heuristic.

Installation remains a separate, explicitly approved maintenance action. Test
an accepted output under an isolated matching Hermes home first: verify session
inventory and history, resume one gateway session, complete one turn, and check
Relay session inventory and image activity. Never swap or exercise recovery
against the live database during this gate.
