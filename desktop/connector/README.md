# Windows background connector

The connector runs the desktop tool router without a GUI, tray or Gateway/TUI
voice session. The authenticated download portal adds a fresh one-use invitation
to each installation ZIP. Installation exchanges it for a permanent,
individually revocable desktop-only session; that session cannot use chat,
terminal, bridge or server-management channels.

## Install and control

1. Sign in to the private download page and download your installation ZIP.
2. Extract the entire ZIP into one folder, then run `Install.cmd`.
3. Verify the displayed server and confirm the listed permissions once.

The confirmation approves file access, command execution, screen capture and
keyboard/mouse input, USB, microphone and camera access using the existing
`full_access` policy. This applies to a first installation and an explicitly
confirmed upgrade. Windows permission and session restrictions still apply.
The connector does not start recording or a voice conversation on installation.

Installation uses `%USERPROFILE%\.hermes`, saves the CLI under `bin`, and creates
one startup task for the current user. The new task runs with Limited rights
and explicitly grants that exact user permission to pause, resume and update
it, retaining the existing SYSTEM, Administrators and other task permissions.
Even an elevated migration starts the new daemon through that Limited task so
the normal user can stop it afterward.
Older tasks created with administrator-only management permissions may require
one elevated installation under the same Windows user to migrate. Their task
permissions are not broadened. The task runs at sign-in and checks once
per minute to restart a stopped process. The daemon also reconnects after network
interruptions. The connector requires that user to be signed in; it is not a
system service and does not run on the Windows sign-in screen.

Run the installed `%USERPROFILE%\.hermes\Status.cmd` to inspect the daemon and
startup task. Run `Stop.cmd` from that directory to stop the daemon and disable
automatic restart. Reinstall a downloaded package to resume. A matching,
permanent desktop-only session can be reused without redeeming another invite.
If the server explicitly rejects that saved credential, a current invitation
from a newly downloaded package is tried once. Network or TLS failures never
consume an invitation or trigger automatic re-pairing.
A legacy general-purpose session requires a fresh connector invitation; moving
an existing enrolled device may also require the operator's explicit migration
approval. Other servers' saved sessions remain in the same store.

The installer checks the executable's SHA-256 against `manifest.json` before
changing the installation. The private `setup.json` is consumed from a file,
never a command-line token, and removed after successful installation. Treat
the downloaded ZIP and extracted folder as private: the original ZIP retains
its one-use invitation even after the extracted setup file is removed.

## Upgrade and recovery

Before replacement, the installer disables only recognized tasks owned by the
current user and pointing into this installation, stops its daemon, and backs
up the binary, saved sessions, access policy, device identifier and task XML.
Before replacing the executable, it closes remaining CLI processes only when
their complete executable path matches this installation. Other installation
paths and isolated profiles are retained. Failure to close a matching process
aborts replacement and preserves the original program. Unrecognized tasks are
retained. Backups live in an owner-restricted
`connector-backup-<timestamp>-<id>` directory under `.hermes`.

Before new authorization is verified and saved, a failed installation attempts
to restore the original local files and startup configuration. If authorization
has already succeeded, a server-side migration may have revoked the old token.
The installer therefore keeps the verified new binary, permanent credentials,
device identifier and access policy, and attempts to restore the new daemon
without voice. It reports that installation is incomplete; automatic startup
still needs inspection or another install attempt. It does not report a
successful installation just because the recovery daemon connected.

The original backup remains intact, and a `failed-attempt` subdirectory retains
the attempted installation's files. Restoring local files cannot undo a
server-side invitation redemption or revocation. An older CLI is not guaranteed
to support the new desktop-only session. If authorization was issued but could
not be saved, or recovery cannot reconnect, use the preserved files with an
operator to finish recovery or issue a new invitation. Do not share session
files, setup files or backup contents in diagnostics.

## Noninteractive and isolated verification

An unattended installation must explicitly approve the same capabilities:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\install.ps1 -AcceptTools
```

Without an interactive confirmation or `-AcceptTools`, installation fails closed.
To verify a package without using the active profile's credentials, device ID,
daemon or startup task, supply a separate **profile root**:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\install.ps1 -AcceptTools -HomeDirectory C:\Connector-Test
```

That example installs into `C:\Connector-Test\.hermes`. Its generated shortcuts
and startup script retain that profile root. Stop its daemon using its own
`Stop.cmd` when finished. Startup task names are `HermesRelay-Connector-` followed
by the first twelve hexadecimal characters of SHA-256 over the lowercased full
`.hermes` path encoded as UTF-8. The task action points to that directory's
`run-connector.ps1`, which can also be used to locate the correct task.

## Build the portal template

Build a Windows CLI from the exact source revision being tested, then run this
command from the repository root (replace the SHA with the full build commit):

```sh
python3 scripts/package-desktop-connector.py \
  --binary desktop/dist/bin/hermes-relay-win-x64.exe \
  --output /tmp/relay-connector-template.zip \
  --source-sha 0123456789abcdef0123456789abcdef01234567
```

The script uses only Python's standard library. `--version` may override the
version read from `desktop/package.json`; it must match the supplied build.
The caller is responsible for building from the stated revision. Packaging does
not compile the binary, change release versions or publish an artifact.

The ZIP has this flat root layout:

```text
Install.cmd
install.ps1
Status.cmd
Stop.cmd
hermes-relay.exe
manifest.json
```

The manifest contains `sha256` for the exact archived executable, `source_sha`
and the desktop `version`. The script normalizes Windows script line endings
and the PowerShell UTF-8 BOM. Only the listed files enter the template; it never
copies `setup.json`, credentials or adjacent staging files. Existing output is
never overwritten, including when two packaging processes race. Output is
published with a same-directory hard link; unsupported filesystems fail without
replacing an existing artifact.

Configure the private portal's `base_zip_path` to reference this credential-free
ZIP. The portal appends `setup.json` only while producing an authenticated
per-download response. That private file has the form below; the displayed
invitation is an example, not a usable credential:

```json
{
  "version": 1,
  "relay_url": "wss://relay.example.test/relay/ws",
  "invite": "0000000000000000000000000000000000000000000000000000000000000000",
  "expires_at": 2000000000
}
```
