# CUA hexadecimal snapshot generations (#680)

PR #635 admitted snapshot/index handles but restricted snapshot generations to
decimal digits. CUA Driver formats its process-global generation counter as
eight hexadecimal digits. `s00000009:3` passes the old validation;
`s0000000a:3` is rejected before the driver receives the action.

The upstream contract is explicit in
[Driver 0.20.0](https://github.com/trycua/cua/blob/cua-driver-rs-v0.20.0/libs/cua-driver/rust/crates/cua-driver-core/src/element_token.rs)
and [Driver 0.28.2](https://github.com/trycua/cua/blob/cua-driver-rs-v0.28.2/libs/cua-driver/rust/crates/cua-driver-core/src/element_token.rs).
The accepted modern form is now `s[0-9a-f]{8}:<decimal index>`. Legacy `e<hex>`
handles remain accepted, and all tokens are forwarded unchanged. Session,
grant, target, one-use, and driver-side stale-token checks remain intact.

## Reproduction and verification

On Windows 26200.9550 with the canonical CUA Driver 0.28.2 installation:

- Advancing the disposable native fixture through ten snapshots reproduces the
  token rejection on current dev before this follow-up.
- The same test passes after the correction: click, set-value, scroll, fresh
  verification snapshots, and replay rejection. The test owns its native window
  and direct driver child; it does not restart the shared driver daemon.
- Unit and adapter integration tests cover hexadecimal generations, decimal
  boundaries, the maximum eight-digit generation, malformed handles, and
  unchanged target/session forwarding.
- Full desktop suite: 201 passed, one opt-in native test skipped. The native lane
  passed separately. CLI type-check and build passed.

The initial short native test used only six snapshots and did not reach a
generation containing `a`–`f`. The extended test retains that boundary coverage.
These results do not certify the reporter's exact machine or Driver 0.34.0.

This follow-up addresses token validation only. #682's audit-label correction is
a separate change and should not be inferred from successful action dispatch.
