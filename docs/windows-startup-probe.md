# Windows startup comparison for issue #496

The reporter reproduced `0xC0000409` before `--version` or `--help` on Windows
26200.8875 using the official `desktop-v0.4.0-beta.8` executable, SHA-256
`4f76f692f6e65a0231db1a77b4444a41e005a96528228c91232d1926b845bc57`.
This is not evidence of a Node/tsx development failure. The published executable
embeds Bun 1.3.14. A successful launch on a different Windows revision does not
resolve the report.

## Build the comparison bundle

For a shareable candidate, open a PR targeting `dev` and apply the
`windows-startup-probe` label. **Windows Startup Diagnostic Candidate** checks
out the exact PR head, runs the runner regression tests, builds the pinned
comparison below, and requires all 120 startup probes to pass on its Windows
runner before uploading. It uses read-only repository permissions, no signing
or deployment secrets, and does not create a tag or release.

The Actions artifact requests seven-day retention and requires GitHub sign-in
to download. Check its actual `expires_at` in GitHub artifact metadata before
sharing; repository policy may impose a shorter limit. Its summary contains
the download URL and archive digest. The ZIP
includes executable hashes, a manifest identifying both the fixed Relay source
and the builder SHA, `PROVENANCE.json`, an inspection copy of the builder and
minimal program, and a README. Maintainers must download that exact artifact,
verify its digest and payload hashes, and run it locally before sharing the link.
The existing Android/Plugin `review-candidate` lane is unchanged.

On Windows with Node 21+, Git, curl, and tar, from the repository root:

```powershell
node desktop/scripts/build-windows-startup-probe.mjs desktop/dist/startup-probe-496
```

The output directory must not already exist. The builder extracts immutable
release commit `3200f696cd51645c275f97635312294785894137`, verifies pinned upstream
archive hashes, and records each executable's SHA-256 in `manifest.json` and
`SHA256SUMS.txt`. It does not change the release runtime pin or installed CLI.

The ten executables cover:

| Artifact | Bun/runtime target | Purpose |
|---|---|---|
| Exact official beta.8 | 1.3.14 x64 | Reconfirm the reported artifact |
| Bare Bun, minimal compiled program, Relay | 1.3.14 x64 | Separate runtime startup, compilation, and Relay imports |
| Bare Bun, minimal compiled program, Relay | 1.4.0 x64 | Change runtime while holding Relay source and build flags fixed |
| Bare Bun, minimal compiled program, Relay | 1.4.0 x64 baseline | Compare CPU target with the same runtime version |

Both rebuilt Relay variants and minimal programs use `--compile --minify
--sourcemap`. Baseline is a diagnostic comparison, not an assumed remedy for
`0xC0000409`. The minimal program prints a marker and imports no Relay code.

For a local reporter handoff, package only files named in `SHA256SUMS.txt` plus
the checksum file itself. Do not include extracted build directories or local
run directories. The Actions job adds checksummed workflow provenance before
upload. Publishing or commenting remains an explicit maintainer action.

## Reporter test

Extract the approved bundle into a new directory, then run there:

```powershell
powershell.exe -NoProfile -File .\test-windows-startup-probe.ps1
```

No installation, administrator shell, daemon restart, pairing, or Windows
configuration change is required. The runner checks all binary hashes before
launching anything, then runs only `--version` and `--help`. Each case receives a
fresh temporary child profile. The default runs three attempts per case, first
without `NODE_USE_SYSTEM_CA` and then with it set to `1`, distinguishing the
Windows CA self-relaunch from a process that starts with system trust enabled.
The environment changes apply only to probe processes. Timed-out probes are
stopped by their own process-tree identity.

Return the generated `run-*/results.json` file after reviewing it. It contains
the Windows build revision, architecture, artifact hashes, exit codes in decimal
and hex, timings, output lengths, and expected-marker checks. It collects no
crash dumps, event logs, usernames, hostnames, profile contents, or environment
values. The runner performs no network requests.

## Interpret the result

- If bare Bun and the minimal executable fail on the affected OS, the comparison
  isolates startup below Relay command handling.
- If bare Bun succeeds but the minimal compiled executable fails, investigate
  Bun's standalone executable path.
- If minimal programs succeed but Relay fails, investigate Relay imports and
  its Windows entry point; compare the two CA settings before changing bootstrap.
- If a 1.4.0 candidate succeeds where the pinned control fails, validate that
  candidate on the affected OS and run the desktop regression and TLS/CA lanes
  before proposing a release-pin change.
- If every case succeeds on another Windows build, the original report remains
  unresolved. No generic release recommendation follows from that result.

Issue: <https://github.com/Codename-11/hermes-relay/issues/496>.
