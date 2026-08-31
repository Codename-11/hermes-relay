# Gateway Contract Testing

Hermes-Relay keeps a reusable, client-neutral fixture for the standard vanilla
Hermes Dashboard/Gateway chat contract under
`test-fixtures/vanilla-gateway/`. It exists to reproduce transport, session,
event-ordering, and history-reconciliation failures without provider calls,
real conversation content, credentials, or Relay-only routes.

All lanes are **on demand**. The repository does not schedule fixture,
instrumentation, upstream-conformance, or physical-device runs. Scheduled or
hosted device execution is a possible future enhancement and requires a
separate decision.

## Test layers

| Layer | What it proves | Expected use |
|---|---|---|
| JVM/state tests | Event mapping, identity fences, queue ownership, REST-directory refresh policy, and history merging | Every relevant implementation change |
| Vanilla Gateway fixture | Real HTTP/WebSocket JSON-RPC, deterministic event ordering, socket gaps, and persisted history | On demand during client changes and incidents |
| Android instrumentation | Production `GatewayChatClient`, `ChatViewModel`, `ChatHandler`, main-looper dispatch, lifecycle-aware Compose collection, and rendered transcript state | On emulator or attached sideload device |
| Upstream conformance | The scenario requirements still exist in an unmodified current upstream checkout | On demand before integration or release certification |
| ADB certification | Exact APK/test APK, fixture port reversal, instrumentation result, process lifecycle smoke, bounded logs, and fixture evidence | Physical-device certification |

The deterministic fixture is the normal regression authority. Physical-device
results are additional lifecycle/runtime evidence, not a replacement for
hermetic tests.

## Session directory boundary

The Gateway fixture proves live chat transport, activity, reconnect, and
authoritative post-turn history reconciliation. It is not the route authority
for the session drawer. On the standard path, profile-scoped session browsing
and stored transcript reads are authenticated Dashboard REST operations and may
run while the independent Gateway socket is not ready.

Android state tests for this boundary must prove that:

- a bound Dashboard route can refresh recents without `chatReady`;
- a normal profile switch does not issue `model.options` before the independent
  Dashboard recents read;
- automatic stored-session restoration cannot issue `session.resume` before a
  fresh exact-owner directory success, while an explicit row open remains
  immediate;
- the initial request uses a bounded, Desktop-like recent window;
- an existing exact-owner cache stays rendered while refresh is in flight;
- a timeout produces retryable **Unavailable**, not an empty list or another
  automatic long request; and
- a connection/profile switch or newer refresh generation rejects the old
  completion before it can publish rows.

Gateway startup/reconnect tests must also keep Dashboard availability separate
from socket truth: Chat is Ready only after `gateway.ready` from the current
connection and route. Timeout/DNS/reset/5xx/429 failures may retry within the
bounded cold-start budget; auth, unsupported, malformed-protocol, and access
policy failures may not. A delayed retry must retain the exact profile/session
generation that scheduled it.

Physical-device evidence is still required for claims about first-row latency
against a real large session database. A fixture or JVM pass proves ownership
and presentation invariants, not server-query performance.

## Scenario catalog

Scenarios are declarative JSON files bundled with the fixture. Each can declare
the upstream contract identifiers it depends on.

| Scenario | Contract exercised |
|---|---|
| `initial_history_bind` | Durable, profile-scoped history is already available when the client resumes and first binds its rendered transcript |
| `ordinary_turn` | Normal message start, deltas, completion, and persisted history |
| `rapid_tools_interims` | Rapid chunks, reasoning, tool activity, and interim assistant boundaries |
| `queued_follow_up` | Two explicitly owned turns and ordered queue drainage |
| `scope_rejection_inputs` | Exact, foreign, and unscoped event inputs |
| `terminal_gap_activate` | Socket closes after live output; replacement `session.activate` reports `running=false`; history is authoritative |
| `terminal_gap_session_info` | Scoped `session.info {running:false}` settles a turn without `message.complete` |
| `active_status_lifecycle` | `session.active_list` reports starting, working, waiting, and idle, then a complete empty process-wide snapshot permits removal of unambiguously owned prior rows |
| `active_status_profile_scope` | A row has no profile metadata and a caller profile hint has no effect; the client must use exact client-held ownership and reject invented attribution |
| `active_status_unsupported` | An older Gateway returns JSON-RPC method-not-found; the client retains Unknown rather than inventing Idle or Working |
| `cross_client_observation` | A second client observes a Desktop-owned working session through active status and history without resume, activate, submit, or interrupt; the producing client receives the terminal event |

Fixture evidence is a bounded metadata-only ring. It records sequence,
connection number, RPC method, event type, scope classification, and outcome.
It never records prompts, responses, RPC parameters, credentials, URLs,
hostnames, headers, or filesystem paths.

## Local fixture checks

From the repository root:

```powershell
python -W error::ResourceWarning -m unittest discover -s test-fixtures/vanilla-gateway/tests -v
python -m unittest discover -s scripts/tests -p "*gateway*test.py" -v
```

Start the issue #365 fixture on loopback:

```powershell
$env:PYTHONPATH = "test-fixtures/vanilla-gateway"
python -m vanilla_gateway.cli terminal_gap_activate --host 127.0.0.1 --port 8765
```

Loopback plus `adb reverse` may use HTTP/WS for the isolated test process.
Non-loopback fixture exposure must supply `--tls-cert` and `--tls-key` so the
surface runs as HTTPS/WSS.

## Android instrumentation

The standalone on-device regression embeds a real loopback ticket/WebSocket
peer and deterministically exercises Activity `STARTED` to `RESUMED` while the
turn is live. The external lane consumes the shared Python fixture and reads
its authoritative history over HTTP.

```powershell
.\scripts\android-lane.ps1 gradle :app:compileSideloadDebugAndroidTestKotlin
.\scripts\android-lane.ps1 gradle :app:assembleSideloadDebug :app:assembleSideloadDebugAndroidTest
```

The external test is opt-in through the instrumentation argument
`gatewayFixtureBaseUrl`; without it, that test skips rather than contacting an
unreviewed endpoint.

## Physical-device certification

Inspect the exact plan first. The runner requires an explicit serial or
transport ID, targets only `com.axiomlabs.hermesrelay.sideload`, and performs no
installation unless its corresponding install flag is supplied.

```powershell
.\scripts\android-lane.ps1 exec python scripts/android-gateway-certify.py `
  --transport-id <adb-transport-id> `
  --apk app/build/outputs/apk/sideload/debug/<sideload-apk> `
  --test-apk app/build/outputs/apk/androidTest/sideload/debug/<test-apk> `
  --fixture-url http://127.0.0.1:8765 `
  --fixture-host-port 8765 `
  --fixture-device-port 8765 `
  --scenario terminal_gap_activate `
  --output-dir build/gateway-certification `
  --dry-run
```

After reviewing the dry run, repeat without `--dry-run`. Add `--install` or
`--install-test` only when replacing the corresponding sideload package is
intended. Screenshots require both `--app-lifecycle-smoke` and
`--confirm-synthetic-fixture`. Device-wide Wi-Fi/data cycling is disabled by
default and requires the exact confirmation plus matching receipt produced by
a prior radio-enabled dry run.

A successful certification requires the instrumentation result and fixture
proof of `prompt.submit`, controlled socket gap, exact `session.activate`,
history read, and authoritative settled state. Evidence output is bounded and
redacted.

## Current-upstream conformance

Run against a clean checkout of `NousResearch/hermes-agent`:

```powershell
python scripts/check-gateway-scenario-conformance.py `
  <clean-upstream-checkout> `
  --scenario-manifest test-fixtures/vanilla-gateway/vanilla_gateway/scenarios/terminal_gap_activate.json
```

The check is source-only and non-mutating. It starts no runtime, creates no
sessions, and uses no provider or authentication credentials. It fails closed
for dirty, fork-marked, or non-vanilla checkouts.

For activity scenarios, the adapter confirms that current upstream owns
`session.active_list`, emits `starting`, `working`, `waiting`, and `idle`, lets
pending input outrank running work, accepts only `current_session_id` as its
optional selector, and returns both the live runtime id and durable session key
from the process-local registry. The runtime fixture then
tests client reconciliation, including successful disappearance and explicit
method-not-found behavior. Because rows normally carry no profile, partial
ownership resolution may update exact matches but cannot infer absence for an
unresolved scope. Source inspection alone does not claim a client pass.

## Planned extensions

The scenario format is intentionally usable by future official Desktop and TUI
client adapters. Potential later work includes hosted emulator/device lanes,
performance scenarios, and explicitly approved scheduled execution. None of
those are configured today.
