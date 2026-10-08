# Android native sign-in callback stall

## Symptom and first failing hop

Password, self-hosted OIDC, and Nous sign-in stalled in a Firefox Custom Tab.
The baseline was Android 1.18.0 sideload debug (58), from
`f7c0648b0228c45042515aa35b90e086a69bb830`, on a Samsung SM-S938U running API 36.

With the phone awake and Battery Saver confirmed off, Android reported the
app UID as `LAST`, with effective network blocking `APP_BACKGROUND` after the
browser opened. A code-free request to the active loopback listener timed out
after three seconds. Bringing Hermes-Relay forward, without replacing the
listener, made the identical request return HTTP 400 immediately. The app then
recorded `socket_accepted`, `request_read_completed`, and `callback_rejected`,
as expected for a request with no authorization state or code.

A separate real password attempt reached `callback_validated` but failed with
`token_transport_timeout` while the app was background-blocked. Protection is
therefore required through token exchange and session verification as well as
callback acceptance.

The browser launcher is unchanged from `android-v1.17.0`; that tag also owns
the loopback listener only through the sign-in coroutine, without foreground
protection. This investigation does not establish a new 1.18.0 regression or
certify 1.17.0 on the device.

Current upstream [Desktop sign-in](https://github.com/NousResearch/hermes-agent/blob/main/apps/desktop/electron/native-oauth-login.ts)
and [Gateway native auth](https://github.com/NousResearch/hermes-agent/blob/main/hermes_cli/dashboard_auth/native_flow.py)
retain the ephemeral IPv4 loopback, S256 PKCE, one-time exchange, and five-minute
client window. No upstream protocol change is needed.

## Change

The user-started sign-in binds a dedicated, non-exported foreground service.
It explicitly starts and promotes the service before opening the browser,
retains its binding through session verification, and releases it on every
exit. The last unbind stops the non-sticky service. Service loss cancels the
attempt, and a rejected or timed-out binding never opens the browser.

The service carries no authorization material and does not change persistent
connection preferences or battery settings. Existing PKCE, origin checks,
callback limits, and cancellation handling remain in place.

Callback completion and error pages display the selected provider's HTML-escaped
display name. Shared errors no longer assume Google or a hosted gateway.

## Verification

Final debug APK SHA-256:
`73106de23f695737f03894ae38638cde77181cbad0108cf5497e87c186986f5c`.
Its signing certificate matched the previous debug APK; installation used
`adb install -r` and preserved app data.

| Check | Result |
| --- | --- |
| Focused unit tests | 33 passed: service lifecycle on API 31/35, callback request parsing, coordinator behavior, and provider-label escaping |
| Sideload debug assembly | Passed |
| Final-source lint | Passed with zero errors in a fresh Gradle process with one worker |
| Diff whitespace check | Passed |
| Password, physical API 36 | Callback validated, session verified, provider-specific success page, service removed |
| Self-hosted OIDC, physical API 36 | Consent accepted, callback validated, session verified, provider-specific success page, service removed |
| Nous, physical API 36 | Existing browser session reused; callback validated, session verified, provider-specific success page, service removed |

While Firefox was foregrounded, the candidate held the app in `FGS` with
effective network blocking `NONE`; the code-free loopback probe returned
HTTP 400 without foregrounding the app. All three provider checks passed on
the final APK above. No fresh Google account-picker interaction was needed.

An earlier warm-process lint invocation crashed inside the Compose
`FrequentlyChangingValueDetector` with `Unexpected owner function: null`.
The fresh-process rerun passed without disabling checks or changing lint policy.

Local verification commands, through the shared Android lane:

```powershell
./scripts/android-lane.ps1 gradle :app:testSideloadDebugUnitTest `
  --tests '*NativeDashboardAuthServiceTest*' `
  --tests '*NativeDashboardSignInCoordinatorTest*' `
  --tests '*NativeDashboardCallback*Test*' :app:assembleSideloadDebug --console=plain
./scripts/android-lane.ps1 gradle :app:lintSideloadDebug --no-daemon --max-workers=1 --console=plain
```

## Limits

Runtime certification covers this Samsung device with Battery Saver off.
Other OEMs, Battery Saver-on behavior, and process death during provider UI
were not separately certified. The Play foreground-service declaration and
review recording must include browser sign-in before a Play release; see
[the release declaration guidance](../play-store-listing.md).
