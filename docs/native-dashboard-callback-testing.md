# Native Dashboard callback verification

## Confirmed boundary and evidence

Issue [#632](https://github.com/Codename-11/hermes-relay/issues/632) reports an
Android 16 password sign-in stopping at `browser_launched`. No socket diagnostic
was available in that build. This does not prove the browser reached the app.

The production coordinator had a separate reproducible defect: it accepted one
socket at a time and read bytes with a five-second *inactivity* timeout. A peer
that kept sending an incomplete request defeated both the attempt timeout and
cancellation, holding a later complete callback behind it. The regression test
uses the real coordinator and loopback TCP sockets, with a controlled token
endpoint. On the old implementation a 350 ms attempt was still incomplete at
1.5 seconds; the test failed before the transport change. Fragmentation itself
was already supported by the byte reader; unbounded blocking and serialization
were the defects.

## Lifecycle and security audit

| Boundary | Ownership and behavior |
| --- | --- |
| Discovery/provider | `/api/status.auth_flows` selects native PKCE for every interactive provider. Explicit providers are retained; the single-provider Nous compatibility selector remains omitted. |
| Preparation | Bind an ephemeral literal IPv4 loopback socket before browser launch. A bind failure closes the descriptor without changing saved credentials. Canonical-origin discovery retains existing HTTPS/consent policy. |
| Browser | AndroidX Custom Tab, with ACTION_VIEW fallback if unavailable. The upstream password form and OIDC redirects produce the same client callback contract. |
| Read | Four owned accepted sockets maximum. Five seconds absolute per request, 8 KiB request line, 16 KiB headers. Buffered reads preserve CRLF across arbitrary TCP fragments. EOF, incomplete, oversized and ambiguous input are rejected. |
| Address | Only `127.0.0.1:<owned-port>` Host is accepted, once. `localhost`, IPv6 `[::1]`, foreign hosts and other ports are rejected because this attempt owns an IPv4 listener only. No wildcard/DNS binding or port fallback. |
| Callback | Exact origin-form GET `/callback`, HTTP/1.0 or HTTP/1.1, unique matching state and unique code. Path normalization, fragments, wrong state and duplicate parameters cannot claim the attempt. |
| Exchange | One consumer and one-use authorization. S256 verifier stays in memory. No automatic replay of the code. Cancellation cancels the active OkHttp call and the generation/active checks still prevent late storage. |
| Completion | Private CSP/no-store HTML with a fixed app return link. Response writes bounded to two seconds. Page write failure is diagnostic; it cannot invalidate successfully saved credentials. The existing app flow separately verifies `/api/auth/me` and Gateway ticket readiness before reporting authentication complete. |
| Ownership | Tokens remain tied to connection and Dashboard origin. No session, history, draft or profile deletion. Profile selection does not broaden the credential origin. |
| Lifecycle | The Compose attempt is cancelled on screen disposal or connection/origin change; Continue awaits cancellation before retry. Backgrounding for the system browser alone does not cancel it. Process death closes OS sockets and discards ephemeral state/verifier; a new attempt is required. |
| Siblings | Native password and native OIDC already share this listener. There is no second Android ServerSocket callback parser. Embedded sign-in uses the existing WebView/cookie policy and same-origin import; it does not use this listener. |

Diagnostics contain only fixed stages and existing bounded attempt metadata.
`callback_timeout` describes waiting for the callback; a deadline after validation
remains a token-transport timeout. No URLs, Host headers, provider responses,
codes, state, verifier, cookies or tokens are added to diagnostics.

## Upstream contract

Inspected unmodified NousResearch/hermes-agent at
`6f7a7991bb069db07ae74a479823ce8310f8c7e0`:

- `hermes_cli/dashboard_auth/routes.py`: native authorize validates literal
  loopback redirect, selects a session provider and routes password providers
  through `/login`; `_finish_native_login` returns code and original client state.
- `hermes_cli/dashboard_auth/native_flow.py`: short-lived one-use codes and S256
  verification. `/auth/native/token` returns bearer JSON without cookie import.
- `hermes_cli/dashboard_auth/login_page.py`: password success navigates to JSON
  `next`. Browser navigation failure is a separate upstream/browser boundary.
- `apps/desktop/electron/native-oauth.ts`: system-browser loopback native flow.

The native-flow and password suites passed **26 tests** against that checkout.
The repository's broad source-only route checker currently reads two files and
reports routes moved into upstream router modules as missing. Inspection confirms
the audio/status/WebSocket routes in `hermes_cli/web_routers/` and run routes in
`gateway/platforms/api_server_runs.py`; this scanner result is not a missing auth
endpoint or a passing broad contract check.

Android 16 local-network protections are
[opt-in](https://developer.android.com/privacy-and-security/local-network-permission).
That policy alone does not establish the reporter's cause. Do not relax callback
validation or add permissions based only on the reported Android version.

## Reproducible lanes

Run narrow JVM feedback through the shared Windows lane:

```powershell
.\scripts\android-lane.ps1 gradle :app:testSideloadDebugUnitTest `
  --tests '*NativeDashboard*Test' --tests '*DashboardSignInPolicyTest' `
  --tests '*DashboardWebViewAuthPolicyTest' --console=plain
```

The smallest Android runtime lane uses device-local MockWebServer tokens and the
production callback coordinator, parser, bearer interceptor and `/api/auth/me`
client. It makes no live-provider requests:

```powershell
.\scripts\android-lane.ps1 gradle :app:standardPhoneApi36SideloadDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.hermesandroid.relay.network.upstream.NativeDashboardCallbackInstrumentedTest'
```

This controlled Android socket lane cannot certify Samsung firmware, browser
navigation from a real HTTPS password provider, or survival of OEM process
termination. Record those as separate evidence. Outstanding work belongs in
[TODO](project/TODO.md).
