# Troubleshooting

Start with the symptom you can see. Each path begins with the smallest check
that separates an Android problem from a server or network problem.

<TroubleshootingNavigator />

## Can't connect to Dashboard/Gateway {#cannot-connect}

- **Red dot in chat header**: the primary Dashboard/Gateway route is unavailable
- Check that the dashboard is running: `hermes dashboard`
- Check firewall rules on the server
- Open the dashboard URL from the phone (conventionally `http://your-server:9119`)
- If auth is enabled, sign in again so Android can obtain a fresh `/api/ws` ticket

### "No reachable endpoint" in the diagnostics log

This diagnostic means the app probed every route saved on the connection (LAN,
Tailscale, public, custom) and none answered a health check. It is a network
result, not an app bug — the fix is making at least one saved route reachable
from the phone:

1. Open each saved Dashboard/Gateway route in the **phone's** browser. Whatever
   fails there will fail in the app too.
2. A LAN route (`192.168.x.x`, `10.x.x.x`) only works while the phone is on the
   same Wi-Fi network as the server — it goes dark on mobile data or a
   different network.
3. A Tailscale route (`100.x.y.z` or `*.ts.net`) needs Tailscale running on
   both the phone and the server (see the checklist below).
4. Confirm the port: the Dashboard/Gateway conventionally listens on `9119`.
   Android tests this as a Dashboard route; it does not require `:8642` or an
   API key.

::: warning Don't use `localhost` or `127.0.0.1` on the phone
On your phone, `localhost` points at the phone itself — not your server. A
server address like `http://localhost:9119` or `http://127.0.0.1:9119` can
never connect from the app, even though the same URL works in a browser on the
server machine. Use the server's LAN IP (e.g. `http://192.168.1.100:9119`) or
its Tailscale address instead.
:::

### Tailscale checklist {#tailscale-checklist}

If a Tailscale route fails its probe:

- Open the Tailscale app on the phone and make sure it is **connected** (it can
  silently sign out or be paused by battery savers).
- Verify the server appears in the phone's Tailscale device list and that its
  Dashboard/Gateway URL opens from the phone's browser.
- The relay's Tailscale helper (`hermes-relay-tailscale enable`) serves the
  relay and API ports over the tailnet, but **not** the dashboard on `:9119` —
  Manage sign-in needs the dashboard reachable separately (for example, run the
  dashboard on a host Tailscale can reach, or serve `:9119` yourself).

## Android Studio can't see a phone over Tailscale ADB

Use Android's **Wireless debugging** flow, but route it through the phone's
Tailscale IP (`100.x.y.z`) instead of the LAN IP. The pairing-code dialog and
the main Wireless debugging screen usually show different ports:

```bash
adb pair 100.x.y.z:<pairing-port> <pairing-code>
adb connect 100.x.y.z:<wireless-debugging-port>
adb devices -l
```

Use the port from **Pair device with pairing code** only for `adb pair`. For
`adb connect`, use the **IP address & port** shown on the main Wireless
debugging screen. Once `adb devices -l` lists the phone, Android Studio uses
that same ADB transport for Run, Logcat, and Device Explorer.

If `adb connect` is refused, the pairing succeeded but the wrong port was used,
or Wireless debugging rotated ports. Reopen **Developer options -> Wireless
debugging** on the phone and copy the current main port.

## Optional API fallback is unavailable

This does not block a healthy Dashboard/Gateway connection. If you intentionally
configured API fallback or an API-only headless connection, its
`API_SERVER_KEY` is a secret created by the server operator; Dashboard sign-in
does not create or reveal one:

- Check `API_SERVER_ENABLED=true` and the API server bind address.
- Open `http://your-server:8642/health` from the phone.
- Verify that Android has the same operator-created API key and check firewall rules.

## Messages not streaming {#messages-not-streaming}

- Confirm the dashboard session is signed in and `/api/ws` is available
- Look for error banners in the chat — tap **Retry** to resend
- Check the Hermes server logs for errors
- If optional API fallback is configured, check its status separately

## Long turns with local models

Local models (Ollama, llama.cpp, and similar) can take several minutes per
turn, and Android may drop the stream mid-turn — especially with the screen
off or the app in the background. The app recovers the finished answer
automatically: when it reconnects, the completed turn is fetched from the
server and appears in the chat.

To reduce drops in the first place:

- Keep the screen on or the phone plugged in during long turns — Android is
  far less aggressive about cutting network connections then.
- Enable the gateway keep-alive option in Settings if you background the app
  during long turns.

## "No internet connection" banner {#no-internet}

- The app detected network loss via Android's ConnectivityManager
- Check your WiFi/mobile data connection
- The banner disappears automatically when connectivity returns

## Session history not loading {#session-history}

- The server must be reachable when switching sessions
- Large sessions may take a moment to load — watch for the loading indicator

## App crashes on startup {#app-crashes}

- Clear app data: Settings > Apps > Hermes-Relay > Clear Data
- Re-enter your Dashboard/Gateway address and sign in during onboarding
