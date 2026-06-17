# Hermes-Relay Plugin Installed

Enable the plugin in Hermes, restart the Hermes process, then verify the install:

```bash
hermes relay doctor
```

Use `hermes pair` to create a QR pairing payload. Use `hermes relay start` to run
the relay foreground server, or use the legacy installer if you still need the
systemd user service and shell shims.

## Two install paths

**Tools-only (this path — `hermes plugins install`):** discovers + enables the
`android_*` / `desktop_*` tools, prompts for any optional voice-provider keys,
and registers the plugin with the gateway. The plugin's `plugin.yaml` lives in
the repo's `plugin/` subdir, so install with the subdir identifier:

```bash
hermes plugins install Codename-11/hermes-relay/plugin
```

This is the right path if you only want the agent-side tools and will run the
WSS relay yourself (or don't need it). It does **not** set up the relay server,
a systemd unit, the `pip install -e`, the shell shims, or the legacy
compatibility bootstrap.

**Full relay (`install.sh` curl one-liner):** everything above **plus** the
relay server, systemd user unit, editable pip install, `hermes-pair` /
`hermes-relay` / `hermes-relay-tailscale` shims, the skills dir, and the
optional compatibility bootstrap. Use this for terminal, bridge/phone control,
relay voice, desktop tooling, and remote access:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

The optional legacy compatibility hook is managed separately:

```bash
hermes relay compat status
hermes relay compat install   # only for older Hermes builds or route gaps
hermes relay compat remove
```

Standard Android chat, Manage, and dashboard voice use vanilla upstream Hermes.
The Relay plugin is additive: phone control, terminal, remote desktop tooling,
dashboard Relay management, and optional compatibility diagnostics.

Legacy installs may also have a `hermes_relay_bootstrap.pth` monkeypatch in the
Hermes Python environment. `hermes relay doctor --json` and
`hermes relay compat status --json` report whether that bootstrap is present.
Keep it only for older Hermes builds or compatibility-only route gaps.
