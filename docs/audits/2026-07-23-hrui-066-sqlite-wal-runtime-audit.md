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
