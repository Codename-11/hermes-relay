# Upstream Gateway Compatibility Certification

This runbook verifies upstream-only gateway behavior that Hermes-Relay inherits
without adding client-specific protocol branches. It covers concurrent
model/image routing (HRUI-043), opt-in turn isolation (HRUI-039), and durable
background-completion ownership (HRUI-032).

The static gate is safe to run against a separate upstream checkout. The live
gate uses real sessions and intentionally includes gateway restarts, so run it
only against a disposable or explicitly approved test gateway.

## Required upstream baseline

Before live certification, confirm the upstream checkout contains the relevant
model/image-routing and turn-isolation changes:

```bash
git merge-base --is-ancestor 73057ed16 HEAD
git merge-base --is-ancestor ef9e0c98f HEAD
git merge-base --is-ancestor 7d27a31ce HEAD
git merge-base --is-ancestor 54d0948d3 HEAD
```

An older baseline is not a Relay failure. Advance the test gateway first rather
than introducing client branching for behavior upstream has already corrected.

## Static upstream gate

The repository includes a non-mutating preflight runner. It requires a clean
upstream checkout, proves the required commits are ancestors of the exact
checkout, checks that every fixture still exists, and optionally executes the
fixture groups with that checkout's virtual environment:

```bash
python scripts/check-upstream-compatibility.py \
  --upstream /path/to/hermes-agent \
  --run-tests \
  --evidence-json /tmp/upstream-compatibility.json
```

The JSON is sanitized: it contains the upstream SHA, baseline/test outcomes,
and explicit `not_run` live gates, but no hostname, checkout path, profile,
token, or session content. A preflight-only invocation omits `--run-tests`.
Pass `--python` when the upstream environment is not at `.venv/bin/python` (or
`.venv/Scripts/python.exe` on Windows).

The equivalent manual commands are retained below for upstream maintainers who
do not have a Hermes-Relay checkout.

From the upstream `hermes-agent` checkout:

```bash
uv run pytest -q \
  tests/agent/test_auxiliary_runtime_cache_key.py \
  tests/agent/test_turn_context.py \
  tests/gateway/test_image_input_routing_runtime.py \
  tests/tui_gateway/test_image_routing_stale_model.py \
  tests/run_agent/test_compression_feasibility.py

uv run pytest -q \
  tests/test_tui_gateway_server.py \
  -k "turn_isolation or queued_prompt"

uv run pytest -q \
  tests/cli/test_cli_async_delegation_delivery.py \
  tests/test_tui_gateway_server.py::test_notification_poller_drops_orphaned_events \
  tests/test_tui_gateway_server.py::test_notification_poller_delivers_owned_events \
  tests/test_tui_gateway_server.py::test_run_prompt_submit_requeues_foreign_completion \
  tests/test_tui_gateway_server.py::test_run_prompt_submit_delivers_completion_observed_by_poll \
  tests/test_tui_gateway_server.py::test_run_prompt_submit_requeues_all_unstarted_notifications_with_real_threading \
  tests/test_tui_gateway_server.py::test_run_prompt_submit_delivers_completion_owned_through_compression_lineage \
  tests/test_tui_gateway_server.py::test_run_prompt_submit_prefers_origin_ui_session_id \
  tests/tools/test_process_registry.py::test_drain_notifications_can_deliver_poll_observed_for_gateway \
  tests/tools/test_process_registry.py::test_drain_notifications_routes_foreign_before_local_skip \
  tests/tools/test_process_registry.py::test_drain_notifications_filters_addressed_completion_by_owns_event \
  tests/tools/test_process_registry.py::test_drain_notifications_filters_addressed_completion_by_session_key \
  tests/tools/test_process_registry.py::test_drain_notifications_session_key_filter_requeues_origin_only_event \
  tests/tools/test_process_registry.py::test_drain_notifications_ownerless_completion_preserves_legacy_delivery \
  tests/tools/test_process_registry.py::test_drain_notifications_ownerless_async_delegation_still_requires_proof \
  tests/tools/test_process_registry.py::test_drain_notifications_completion_callback_exception_fails_closed
```

Do not start the live matrix if these tests fail. First determine whether the
failure is an upstream regression, a stale checkout, or a local test-environment
problem.

## Live matrix

Record the exact upstream SHA, Relay SHA, profile, gateway mode, and
`dashboard.turn_isolation` value with every run. Use two distinct durable
sessions under the same profile.

### 1. Concurrent model and image routing

1. Start sessions A and B with different providers/models.
2. Submit turns concurrently and confirm each transcript reports its own model.
3. Switch only session A's model in place.
4. Send a native-image turn through A and a vision-preanalysis turn through B.
5. Trigger compression in A after the switch.
6. Repeat while forcing one provider fallback and restoration.

Pass only if every auxiliary/image/compression operation uses that session's
current runtime, the other session remains unchanged, and neither transcript
receives duplicate or foreign events.

### 2. Turn isolation off/on parity

Run the same sequence once with `dashboard.turn_isolation=false` and once with
it enabled:

1. Create or resume a session and submit a turn that emits text, reasoning, and
   at least one tool lifecycle.
2. Confirm exactly one terminal completion and a settled `session.info` state.
3. Interrupt a second turn while it is streaming.
4. Submit a queued follow-up, disconnect, and resume.
5. Force compute-host dispatch failure and confirm inline fallback produces one
   turn with no duplicate completion or stuck busy state.

The public RPC/event transcript must remain equivalent. A difference is evidence
for an upstream issue first; add Relay branching only for a demonstrated,
unavoidable contract split.

### 3. Background completion ownership

1. In session A, dispatch `delegate_task(background=true)`; keep same-profile
   session B active.
2. Confirm the completion appears exactly once in A and never in B.
3. Repeat with completion pending across an approved gateway restart.
4. Repeat after context compression changes A's continuation key.
5. Restart with a child still in flight and confirm the recovered outcome is
   `unknown`, not falsely described as resumed execution.

This proves restart-safe **completion delivery**, not durable child-process
execution. Stateless `/v1/runs` and `/v1/chat/completions` have no asynchronous
callback channel and must not be documented as equivalent.

## Relay client gate

After the live upstream matrix passes, run the Relay client suites that cover
gateway recovery, event mapping, checkpoints, and desktop gateway parsing. The
contract workflow also runs the provider-free desktop gateway baseline:
`scripts/check-desktop-upstream-baseline.py` against an isolated vanilla
upstream checkout, then the desktop mock-event renderer tests. That gate proves
the required gateway event surface and Relay Desktop rendering assumptions, not
real provider behavior. Then perform one Android device reconnect smoke. Do not
claim device behavior from JVM fixtures alone.

## Evidence record

For each row, retain:

- upstream and Relay SHAs;
- sanitized gateway mode/profile/capabilities;
- pass/fail and the first divergent RPC/event, if any;
- whether a real restart or Android device was exercised;
- links to upstream or Relay issues created from failures.

Never attach tokens, session contents, private hostnames, or raw environment
configuration to the public evidence.

### Claim boundaries

| Evidence | Proves | Does not prove |
|---|---|---|
| Baseline ancestry | Required fixes are in the tested source tree | Deployed processes run that tree |
| Static fixture groups | Deterministic routing, isolation, queue, compression, and delegation contracts | Provider behavior, restart recovery, or event delivery over a real socket |
| Desktop baseline contract | Vanilla upstream still declares the dashboard gateway and required event names consumed by Relay Desktop | Provider output, auth, or a live desktop session |
| Live gateway matrix | Session/provider behavior for the recorded runtime and profile | Android lifecycle/reconnect behavior |
| Android reconnect smoke | Client mapping and recovery on the exercised device | Other providers, profiles, gateway modes, or OS versions |

Do not close HRUI-032, HRUI-039, or HRUI-043 from the static JSON alone. Each
item remains gated on the corresponding live row above, with the restart and
provider-cost steps requiring explicit operator authorization.
