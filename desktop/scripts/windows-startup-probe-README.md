# Windows startup comparison for #496

This is a diagnostic bundle, not an installer or a fixed release. It compares
the exact official beta.8 executable with the same beta.8 source rebuilt using
Bun 1.3.14 and 1.4.0. Bare Bun runtimes and a tiny program without Relay code
help distinguish runtime startup from Relay startup. A Bun 1.4.0 baseline
variant compares CPU targets; it is not an assumed fix for `0xC0000409`.

## Run

1. Extract the complete ZIP into a new folder.
2. Feel free to read `test-windows-startup-probe.ps1` before running it.
3. Open PowerShell in that folder and run:

   ```powershell
   powershell.exe -NoProfile -File .\test-windows-startup-probe.ps1
   ```

4. Review the generated `run-*/results.json` and attach that JSON file to
   <https://github.com/Codename-11/hermes-relay/issues/496>.

The default test makes 120 short launches. Crashes and failed cases are useful
results; they do not mean you ran the test incorrectly. If Windows blocks the
script or an executable, stop and report the message rather than changing
Windows security settings for this test.

## What it does

- Verifies all executable hashes before launching any of them.
- Runs only `--version` and `--help`, three times with system-CA startup unset
  and three times enabled. Each launch gets a fresh temporary child profile.
- Stops only a timed-out probe's own process tree.
- Writes Windows build/architecture, artifact hashes, exit codes, timings,
  output lengths, and expected-output checks to `results.json`.

It does not install or replace Relay, change PATH/registry/security settings,
restart services, pair to a host, or download anything. It collects no crash
dumps, event logs, usernames, hostnames, profile contents, or environment values.
Remove the extracted folder when finished; there is nothing to uninstall.

## Inspect the build

`manifest.json` records the fixed Relay source commit, builder commit, pinned
upstream download hashes, targets, and binary hashes. `SHA256SUMS.txt` covers
all payload files, including this README and both scripts. The builder and
`minimal.ts` are included for inspection; you only need to run the test script.

Relay source: <https://github.com/Codename-11/hermes-relay/tree/3200f696cd51645c275f97635312294785894137/desktop>

Relay's license and each Bun version's upstream license/dependency notices are
included as `LICENSE-*`. Bun sources and its build instructions are available at
<https://github.com/oven-sh/bun/tree/bun-v1.3.14> and
<https://github.com/oven-sh/bun/tree/bun-v1.4.0>.

The CI artifact includes `PROVENANCE.json` with its PR head and workflow run.
The workflow checks all 120 launches on its Windows runner before uploading.
That CI result does not establish compatibility with Windows 26200.8875.
