# HRUI-056 Update/Restart Audit

Date: 2026-07-22

## Scope

HRUI-056 tracks the operational risk that a Hermes source update can leave long-running backend processes serving stale Python objects after the source checkout changes. This audit is read-only and covers the Relay/app-relevant service classes that must be refreshed together after an upstream Hermes update.

Desktop-specific packaging and release automation are out of scope for this pass.

## Read-only evidence

The live operator host had these Hermes-related process classes active during inspection:

- Hermes-Relay server process.
- Hermes dashboard process.
- Hermes profile gateway processes for multiple profiles.
- Hermes proxy process.
- Deployment sidecar.
- Session-graph watcher.
- MCP watchdog child processes under dashboard/profile gateways.

The same host exposed matching user-service units for the relay, dashboard, proxy, profile gateways, deployment sidecar, and watcher. The Hermes agent checkout and Hermes-Relay checkout were both clean at their inspected heads.

The important topology finding is that Hermes updates are not consumed by one process. Gateway profile processes, dashboard, proxy, relay, and watcher/deployer sidecars can each hold imported modules or runtime objects across a source checkout update. Updating the checkout without refreshing the owning process class can therefore produce mixed old/new behavior even when the working tree itself is clean.

## Required operator contract

A Hermes source update that changes gateway, dashboard, proxy, platform, MCP, or shared agent modules should be treated as one atomic operation:

1. Stop or drain affected Hermes backend services.
2. Update the Hermes source checkout.
3. Refresh the editable install or environment as required by that checkout.
4. Restart every affected long-running service class.
5. Verify each service reports the expected source revision or at least a fresh process start after the update.

Relay-specific updates should similarly refresh the Relay service after updating the Relay checkout. If a Relay update depends on an upstream Hermes contract, update and verify Hermes first, then restart Relay against the refreshed backend.

## Minimum post-update verification

For an app/Relay-safe update, verify all of the following before declaring the host current:

- Hermes-Relay service is active and was restarted after the Relay checkout update.
- Dashboard service is active and was restarted after the Hermes checkout update.
- Each configured profile gateway service is active and was restarted after the Hermes checkout update.
- Proxy service is active and was restarted when provider, gateway, auth, or shared agent code changed.
- Watcher/deployer sidecars are either restarted or explicitly proven not to import the changed modules.
- A phone-reachable dashboard status check succeeds.
- A gateway chat smoke check runs against the refreshed profile runtime, not only against a stale existing socket.

## Gaps and follow-up

- HRUI-066 adds a concrete database/runtime gate to this workflow: the service
  interpreter must move to a fixed SQLite line, all writers must be stopped for
  backup/integrity checks, and the full Hermes process fleet must restart on the
  upgraded interpreter. See
  `docs/audits/2026-07-23-hrui-066-sqlite-wal-runtime-audit.md`.
- Add a repo-local operator checklist or script that prints checkout revisions, service uptimes, and restart freshness in one place.
- Prefer a revision marker endpoint in upstream Hermes so clients can compare the dashboard/gateway runtime revision to the checkout revision after updates.
- Keep HRUI-056 open until the update command/path enforces this as an atomic restart-and-verify operation, or until the operator checklist is wired into the live deployment workflow.
