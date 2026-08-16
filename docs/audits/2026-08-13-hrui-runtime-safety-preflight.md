# Hermes Runtime Safety Preflight

Date: 2026-08-13

## Scope

This runbook coordinates the remaining controlled-runtime proof for HRUI-056,
HRUI-066, HRUI-159, HRUI-160, and HRUI-162. It combines source/restart
atomicity, SQLite/WAL integrity, bounded observability failure, shared-state
repair, and adapter failure classification into one evidence package.

The upstream contract inspected for this revision is `fa83af3f9`. The minimum
behavioral floors are:

- `24be384bb` for bounded native observability lifecycle and shutdown.
- `97c06dcfd` for shared `state.db` prevention and recovery.
- `6397776fe8` for terminal adapter classification and reconnect attention.

These revisions are evidence requirements, not Relay-side implementation
points. Do not backport a second watchdog, checkpoint owner, repairer, or retry
supervisor into Hermes-Relay.

## Safety boundary

The default preflight is read-only:

```bash
scripts/hrui-runtime-preflight.sh > preflight.txt
```

It inventories checkouts, lockfile drift, user services, service PIDs and
interpreters, active database paths and sidecars, open database descriptors,
health endpoints, and scoped log counts. It never stops a service, invokes a
backup, runs `PRAGMA integrity_check`, deletes WAL/SHM files, or edits a
checkout.

Do not proceed beyond preflight until every Relay code lane is integrated and
the maintenance coordinator explicitly authorizes the cutover. Never discard
an unexplained dirty checkout. Preserve the worktree or capture and review its
diff before choosing a resolution.

## Preflight acceptance

Record all of the following before stopping writers:

1. Exact Hermes and Relay checkout revisions and full `git status --short`.
2. `uv.lock` worktree and `HEAD` hashes, lock format revision, installed `uv`
   version, and file modification time.
3. Every enabled default/named gateway, dashboard, proxy, Relay, scheduler,
   watcher, and deployer unit with PID, start time, executable, and working
   directory.
4. Service Python version, SQLite version, and `sqlite_source_id()`.
5. Every active non-archive `.db` plus `-wal` and `-shm` size, and every scoped
   process holding one open.
6. Dashboard, Relay, proxy, and gateway health/status; current listeners; and
   recent WAL, repair, observability-timeout, and adapter-attention log counts.
7. Available disk space. The maintenance target must hold the completed
   published Hermes backup, integrity evidence, and temporary SQLite snapshot
   files without relying on `/tmp`.

A lockfile whose format is rewritten or whose dependency set is broadly
re-resolved during install is an unresolved deployment input. Preserve it and
repair the updater/runtime mismatch deliberately; do not overwrite it with
`git checkout` or accept the generated dependency graph as an incidental
change.

## Disposable certification matrix

Run these tests against the exact promoted upstream revision with a temporary
`HERMES_HOME`; they must never point at the live database tree:

```bash
python -m pytest -q \
  tests/agent/test_relay_runtime_bounded_scope_ops.py \
  tests/test_wal_checkpoint_strategy.py \
  tests/test_state_db_malformed_repair.py \
  tests/test_state_db_notadb_selfheal.py \
  tests/gateway/test_adapter_connect_classification.py
```

The matrix must prove:

- Healthy observability plus separately wedged scope push, scope pop,
  subscriber flush, and executor-refused shutdown return within their bound.
- Long-lived and transient `SessionDB` writers use PASSIVE checkpoints, retain
  readable history, and do not produce malformed pages.
- Two repairers perform one surgery/backup; refused or failed backup stops
  repair; transient EIO retries; not-a-database write-open recovery is bounded
  and fail-safe.
- Permanent credential, permission, privileged-intent, and sidecar failures
  become typed non-retryable states. An ambiguous transport failure keeps
  retrying, gains `needs_attention` only after the configured threshold, and
  clears its attention fields after recovery.

Upstream unit fixtures prove fault semantics. They do not replace the final
healthy multi-platform/profile smoke or Phone continuity check.

## Authorized maintenance sequence

Use one timestamped evidence directory on a filesystem with proven capacity.
Capture the preflight PID/unit/database manifest there. Then:

1. Drain active gateway/API/cron work using the supported Hermes shutdown
   path. Stop the complete scoped writer fleet, including default and named
   gateways, dashboard, proxy, Relay, schedulers, watcher/deployer sidecars,
   and any one-shot workers discovered in preflight.
2. Prove no captured PID survives and no remaining process holds an active
   database descriptor. A process absence check is mandatory before backup.
3. Run the supported full `hermes backup -o <evidence-path>.zip`. Require a
   completed final archive; `.partial` output, `BackupInProgressError`, a
   missing archive, or any SQLite safe-copy failure is a hard stop.
4. Produce a stable sorted manifest of every active non-archive database.
   Against the stopped source tree, run `PRAGMA integrity_check` and
   `PRAGMA foreign_key_check` on every manifest entry with the service
   interpreter. Store path, SHA-256, byte size, integrity result, and every
   foreign-key row. Any non-`ok` result or unreadable database is a hard stop.
5. Resolve the reviewed checkout/lockfile state and install from the exact
   intended dependency lock. Record the final Hermes revision, Relay revision,
   service Python/SQLite source id, and cutover start epoch.
6. Start the complete scoped fleet once. Do not delete or rename `-wal` or
   `-shm` files. Do not attempt an online WAL-to-DELETE transition.
7. Run strict postflight with the expected revisions, Relay version, unit
   allowlist, and cutover epoch:

   ```bash
   HRUI_EXPECTED_HERMES_REV=<sha> \
   HRUI_EXPECTED_RELAY_REV=<sha> \
   HRUI_EXPECTED_RELAY_VERSION=<version> \
   HRUI_CUTOVER_EPOCH=<epoch> \
   HRUI_UNITS="<complete scoped unit list>" \
     scripts/hrui-runtime-preflight.sh --strict > postflight.txt
   ```

8. Compare preflight and postflight. Every scoped long-lived PID must be new;
   all must resolve to the intended interpreter/checkout; no stale child or
   sidecar may remain.

## Live acceptance

The maintenance is not complete until all of these pass after the one scoped
restart:

- Dashboard, gateway, proxy, and Relay health/status are readable; expected
  listeners are owned by the new PIDs.
- `hermes doctor` reports no WAL-reset warning.
- Android can list, resume, and read history for representative root/named
  profile sessions without loss or duplication.
- One Phone message reaches a healthy gateway and its reply persists exactly
  once.
- One representative cron execution completes and delivers through its real
  target path.
- A healthy platform continues serving while the disposable adapter matrix is
  represented in runtime status; Phone remains connected.
- Recent scoped logs contain no malformed/corrupt database error, unsafe or
  failed WAL checkpoint warning, unbounded observability finalizer, stale
  process evidence, or unexplained permanent reconnect loop.
- Session counts/history, cron registry, paired Relay sessions, and operator
  configuration are preserved against the preflight evidence.

Keep all five HRUI items open if any backup, integrity, restart, history,
Phone, cron, status, or preservation proof is missing.
