# GPT-Live-1.1 through Codex subscription (experimental)

This opt-in Hermes-Relay realtime-agent provider is called `codex_live`. It uses
an authenticated **Codex app-server** subscription session for GPT-Live-1.1 and
WebRTC transport. It is distinct from the public OpenAI Realtime API provider
(`openai_realtime`), and does **not** use an OpenAI API key. Hermes-Relay remains
responsible for tools, device permissions, session ownership and approvals.

> **Compatibility caveat:** The Codex app-server `thread/realtime/*` and V3
> WebRTC/delegation event protocol are not a stable, documented third-party API.
> OpenAI may change availability or behavior, and account entitlements and
> subscription usage limits are not guaranteed. The integration was validated
> on one Codex CLI 0.164.0 installation, not all releases or accounts. Do not
> depend on this experiment for a production voice service.

## Setup (opt-in)

1. Install Codex CLI and log in with a supported ChatGPT/Codex subscription.
   Confirm `codex --version` and that the same operating-system account running
   Relay can access its Codex login.
2. Install the **optional** WebRTC dependencies in an isolated environment:

   ```bash
   python3 -m venv "$HOME/.hermes/tools/relay-codex-live11-venv"
   "$HOME/.hermes/tools/relay-codex-live11-venv/bin/python" -m pip install \
      -r plugin/requirements-codex-live.txt
   ```

   This environment is separate from the Hermes-Relay server and is only used
   to run the spawned `codex_live_worker.py`. The plugin itself does not import
   `aiortc` or `av` on ordinary startup.
3. Select the **Realtime Agent** voice engine and `codex_live` in Relay voice
   settings (or configure the corresponding `realtime_voice` provider), with
   model `gpt-live-1.1`, voice `sol`, and sample rate **24000 Hz**.
4. Restart Relay if required by the plugin update, then start a fresh voice
   session. Existing OpenAI/xAI realtime voice providers remain available.

The default helper interpreter path is
`$HOME/.hermes/tools/relay-codex-live11-venv/bin/python`. Override it with
`CODEX_LIVE_WORKER_PYTHON=/absolute/path/to/venv/bin/python` if needed.
`CODEX_LIVE_CODEX_BIN=/absolute/path/to/codex` can override Codex CLI discovery
(the default is `codex` on `PATH`, then `$HOME/.local/bin/codex`).

## Trust boundaries and limitations

- The Relay server brokers every delegated task using its existing Hermes
  tool/approval contract. The isolated worker is an audio-and-delegation
  transport, not a new device-action executor.
- The child process receives only a narrowly allowed set of environment
  variables (including `HOME`, `PATH` and optional `CODEX_HOME`); Hermes and
  external API credential environment variables are not forwarded.
- Subscription login is read by the Codex CLI from its existing local auth
  setup; no credentials are committed or configured in this plugin.
- The V3 data channel handles conversation turn taking. `response.create` is
  deliberately not used because that command was rejected in the observed
  implementation.
- Audio output suppresses the very-low-level idle PCM/noise stream to avoid
  holding Android playback/microphone interruption open.
- Earlier live testing validated WebRTC connection, speech and one Hermes task
  delegation. Tap-and-hold on Android was reported working; **continuous mode
  has not been verified as fixed** and may need follow-up work.
- If the helper is missing, the model is not provisioned, or the Codex CLI
  protocol changes, voice startup should fail without running Hermes actions.

## Validation

Unit tests in `plugin/tests/test_codex_live_provider.py` exercise model/rate
validation, provider registry, delegated event normalization and subprocess
credential isolation without contacting the subscription endpoint. Regression
tests cover other voice providers. On-device end-to-end checks require a
compatible Codex subscription and an Android voice session; never use real
private tokens in CI or in public PR evidence.

To roll back, select a previously working voice provider in Relay settings.
Uninstall the optional WebRTC worker only if it is no longer needed. Do not
remove unrelated Hermes providers or app data.
