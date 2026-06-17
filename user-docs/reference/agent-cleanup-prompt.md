# Agent Cleanup Prompt

This prompt helps a local agent clean up older Hermes-Relay install artifacts
after moving to the upstream plugin-manager path.

Use it when you want to remove legacy service/shim/bootstrap pieces without
breaking standard Android chat, Manage, or dashboard voice.

```text
Goal: Audit and clean Hermes-Relay legacy install artifacts while preserving the
standard upstream Hermes path. Standard Android chat, Manage, and dashboard voice
must continue to work against unmodified hermes-agent. Relay power features
should remain available only through the Hermes-Relay plugin if the operator
wants them.

Repository or host context:
- Hermes-Relay plugin-manager install id: Codename-11/hermes-relay/plugin
- Legacy clone path: ~/.hermes/hermes-relay
- Shared Hermes state that must be preserved unless explicitly approved:
  ~/.hermes/.env, ~/.hermes/state.db, the hermes-agent install, and
  ~/.hermes/hermes-relay-qr-secret

Rules:
1. Do not delete shared Hermes state.
2. Do not patch or fork upstream hermes-agent for the standard path.
3. Treat the optional hermes_relay_bootstrap.pth hook as legacy compatibility
   only. It is not required for standard chat, Manage, or dashboard voice.
4. Prefer plugin-owned commands over manual file deletion when available.
5. Use dry-run output before removing legacy installer artifacts.

Inspect:
1. Run `hermes relay doctor --json` if the command exists.
2. Run `hermes relay compat status --json` if the command exists.
3. Check for legacy shell shims:
   `hermes-pair`, `hermes-status`, `hermes-relay`,
   `hermes-relay-update`, `hermes-relay-tailscale`.
4. Check for the legacy clone at `~/.hermes/hermes-relay`.
5. Check for a systemd user unit named `hermes-relay.service`.
6. Check the Hermes Python environment for `hermes_relay_bootstrap.pth`.
7. Check Hermes config for a `skills.external_dirs` entry pointing at the Relay
   clone.

Clean plugin-manager install only:
1. If the optional compat hook is installed, run:
   `hermes relay compat remove --all`
2. Remove the plugin-manager install only if the operator wants Relay removed:
   `hermes plugins remove hermes-relay`

Clean legacy installer artifacts:
1. If `~/.hermes/hermes-relay/uninstall.sh` exists, run:
   `bash ~/.hermes/hermes-relay/uninstall.sh --dry-run`
2. Review the planned removals. They should include the systemd user service,
   legacy shell shims, editable Python package, external skill path, optional
   compat hook, and clone unless `--keep-clone` is requested.
3. Run:
   `bash ~/.hermes/hermes-relay/uninstall.sh`
4. Use `--keep-clone` if the operator wants the git tree preserved.
5. Use `--remove-secret` only when the operator explicitly wants the QR signing
   identity removed.

If the clone is already gone:
1. Prefer `hermes relay compat remove --all` for compat hook cleanup.
2. Remove any remaining Relay shell shims listed above.
3. Remove only the Relay-owned `.pth` file, not unrelated site-packages files.
4. Remove only the Relay `skills.external_dirs` entry, preserving other entries.
5. Stop and remove only the `hermes-relay.service` user unit.

Reinstall Relay through the plugin manager when requested:
1. Run `hermes plugins install Codename-11/hermes-relay/plugin --enable`.
2. Run `hermes relay doctor`.
3. Start Relay only if power features are wanted:
   `hermes relay start --no-ssl`
4. Generate a QR or manual code:
   `hermes pair`
   or `hermes pair --register-code <code>`

Verify app flow:
1. Save a standard API/dashboard connection in Android.
2. Verify Chat works without Relay.
3. Verify Manage signs in through the dashboard.
4. Verify standard voice uses dashboard audio when available.
5. Pair Relay by QR only for Terminal, Bridge, media, relay sessions, desktop
   tools, notification companion, and Relay voice extras.
6. Confirm Terminal and Bridge stay gated when no Relay session is paired.

Report:
- What was installed.
- What legacy artifacts were removed.
- What was intentionally preserved.
- Whether the standard path was verified.
- Whether Relay power features were reinstalled and paired.
```
