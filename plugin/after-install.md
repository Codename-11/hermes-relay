# Hermes-Relay Plugin Installed

Enable the plugin in Hermes, restart the Hermes process, then verify the install:

```bash
hermes relay doctor
```

Use `hermes pair` to create a QR pairing payload. Use `hermes relay start` to run
the relay foreground server, or use the legacy installer if you still need the
systemd user service and shell shims.

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
