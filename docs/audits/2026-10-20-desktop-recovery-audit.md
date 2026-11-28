# Desktop connection recovery audit

Scope: direct Relay desktop transport, daemon startup/exit, reconnect ownership,
optional TUI channel failure, and the associated status file. This is not a
whole-repository security certification or Android/Gateway lifecycle validation.

## Corrected failures

| Trigger | Previous behavior | Corrected behavior |
| --- | --- | --- |
| Stored-token startup hits a TLS timeout or failed WebSocket upgrade | Authentication settled as failed and the daemon exited | Retry with bounded exponential delays; settle initial authentication on the first success |
| Runtime emits a WebSocket error without a close event | Startup waited for the auth deadline; an authenticated connection could remain stuck | Explicitly retire the errored socket and enter retry policy |
| Retry limit is one | The scheduled retry was rejected before it ran | Execute exactly the configured number of additional attempts |
| Old socket close/auth events arrive after replacement | Old callbacks could mutate or close the current socket | Ignore events from replaced sockets and completions from invalidated attempts |
| Pairing succeeds and the connection later drops | Reused the consumed pairing code | Authenticate with the minted session token |
| Optional TUI startup fails | Closed the shared Relay connection and lost desktop tools | Reject TUI RPCs while retaining independent channels; skip TUI for `daemon --no-voice` |
| Daemon exits during initial authentication | Process could exit before the terminal status write completed | Await terminal status and audit persistence |
| No-voice daemon receives a terminal failure after startup | Buffered exit could remain undelivered without a Gateway consumer | Drain transport lifecycle events in no-voice mode |

Reconnect timers remain referenced so a headless process survives its first
backoff. Cancellation invalidates pending asynchronous connection completion;
stopped or reconnecting transports no longer advertise authenticated state.
Retry logs and status now retain the connection failure reason. Explicit auth
rejection and certificate pin mismatch remain terminal. One-shot commands do
not opt into retries, and unconsumed pairing codes are not automatically replayed.

## Verification

- Transport regressions cover cold start, retry budgets, stale events,
  cancellation, credential gates, token reuse, and TUI isolation.
- Loopback TLS tests reproduce a stalled handshake followed by recovery and
  prove that an incorrect certificate pin never reaches the WebSocket factory.
- Real CLI child-process tests use isolated homes and a synthetic Relay peer:
  an initial rejected upgrade recovers, no-voice terminal failure exits, and
  initial auth rejection leaves a persisted stopped status.
- Test-only localhost certificate/key fixtures are public synthetic credentials
  and must never be used by a deployed service.

The vanilla Dashboard/Gateway protocol, session/history semantics, and Android
code are unchanged. Its fixture/instrumentation lanes are not evidence for this
optional Relay transport. No upstream fork patch or synthetic chat-ready event
is introduced. Remaining broker, blackhole, localization, and window-drag work
is tracked in [TODO](../project/TODO.md).
