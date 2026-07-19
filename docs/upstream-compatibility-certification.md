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
```

An older baseline is not a Relay failure. Advance the test gateway first rather
than introducing client branching for behavior upstream has already corrected.

## Static upstream gate

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
  -k "turn_isolation or compression_lineage or queued_prompt" \
  tests/cli/test_cli_async_delegation_delivery.py
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
gateway recovery, event mapping, checkpoints, and desktop gateway parsing. Then
perform one Android device reconnect smoke. Do not claim device behavior from
JVM fixtures alone.

## Evidence record

For each row, retain:

- upstream and Relay SHAs;
- sanitized gateway mode/profile/capabilities;
- pass/fail and the first divergent RPC/event, if any;
- whether a real restart or Android device was exercised;
- links to upstream or Relay issues created from failures.

Never attach tokens, session contents, private hostnames, or raw environment
configuration to the public evidence.
