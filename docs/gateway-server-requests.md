# Gateway server requests

Contract baseline: unmodified `NousResearch/hermes-agent` revision
`6f7a7991bb069db07ae74a479823ce8310f8c7e0` (2026-09-27 inspection).
The migration landed through upstream #110521; capability gating was added in
`f9d178f78e`. The authoritative sources are `tui_gateway/server_requests.py`,
`tui_gateway/contracts/server_requests.py`, `contracts/display.py`,
`methods_prompt.py`, and `methods_voice.py`.

## Supported methods

| Method | Android handling | Answer |
| --- | --- | --- |
| `clarify`, single | Existing choice/free-text card, including multi-select | Same frame id, `result:{answer}`; empty string skips |
| `clarify`, batch | Existing ordered native questions and partial progress | `clarify.lock {request_id,question_id,answer}`; the last lock completes the request |
| `approval` | Existing approval choices and scope restrictions | Same frame id, `result:{choice}` |
| `sudo` | Masked input with hold-to-confirm; decline available | Same frame id, `result:{value}`; empty declines |
| `secret` | Masked input and named environment-variable prompt | Same frame id, `result:{value}`; empty skips |
| `vault.unlock_prompt`, `vault.save_login`, `vault.code` | Unsupported: Android has no vault interaction contract or login form | Same frame id, error `-32601` |
| `preview.read`, `preview.act` | Unsupported: no Desktop preview renderer | Error `-32601` |
| `terminal.read` | Unsupported: no upstream Desktop terminal buffer; Relay terminal is a separate surface | Error `-32601` |
| `window.read`, `tour` | Unsupported: no Desktop window inspection or DOM tour renderer | Error `-32601` |
| `display.install.sudo` | Unsupported: host display installation is outside the native chat sudo flow | Error `-32601` |
| Unknown future method | Explicitly unsupported | Error `-32601` |

Unsupported errors settle the upstream wait immediately. Advertising
`server_requests:true` promises a response or error, not a renderer for every
method. No optional Relay capability is used to implement this contract.

## Ownership and settlement

- Enqueue `client.capabilities` after each socket's first `gateway.ready`, before
  releasing readiness to create/resume/activate. Legacy method-not-found replies
  do not block readiness. Duplicate readiness events do not advertise twice.
- String and numeric request ids retain their JSON type. The frame id owns the
  reply; approval's additional `params.request_id` belongs to its server queue.
- A native ask checkpoint records the protocol. Answers require the current
  socket generation, exact live session and pending card. Profile/session changes
  cannot redirect a response to the visible conversation.
- `session.activate` and recovery `session.resume` restore `open_requests` and
  accepted question locks. Previously answered questions remain locked. A lost
  lock acknowledgement is reconciled through replay before another answer.
- A raw JSON-RPC response has no acknowledgement. Successful local enqueue
  retires the card; a failed send leaves it retryable. Secrets are never retained
  for automatic retransmission. A later server replay remains server authority.
- Batch locks return `status:ok` and remaining question ids, or `status:expired`.
  Empty answers skip one question; a result with neither `answer` nor `answers`
  cancels the request. Supervised mode uses cancel-all for native Clarify.
- `request.cancel {id,method,reason}` requires the matching session, method and
  typed id. Timeout, interruption, shutdown, resolution and session close use
  the same withdrawal path. Upstream owns deadlines and partial-timeout results.
- Notification-era `clarify.request`, `approval.request`, `sudo.request` and
  `secret.request` keep their existing response methods and correlation rules.

## Verification

Use the local Android lane for `GatewayChatClientTest`, `GatewayEventMapperTest`,
`ClarifyBatchInteractionTest`, and `ClarifyBatchScreenshotTest`. API 36
`ClarifyBatchInstrumentedTest` covers the production socket, ViewModel and Compose
path. Run the native and legacy declarative fixture tests, source conformance and
`scripts/check-gateway-server-requests-runtime.py` against the pinned clean
upstream checkout. The latter imports the real request registry and schemas;
it starts no Gateway and uses no model provider.

Emulator, fixture and upstream registry results do not establish physical phone
or OEM behavior. Those remain separate evidence.
