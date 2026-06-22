# Security Policy

Hermes-Relay can give a remote AI agent real control of a phone and, via the
CLI, of a paired desktop. We take security reports seriously and welcome
responsible disclosure.

For the architecture, threat model, and the `googlePlay` vs. `sideload`
capability boundary, see [`docs/security.md`](docs/security.md). This document
covers **how to report a problem**.

## Reporting a Vulnerability

**Please do not open a public issue, discussion, or pull request for a security
vulnerability.** Public reports expose users before a fix is available.

Use one of these private channels instead:

1. **GitHub Private Vulnerability Reporting (preferred).** Go to the
   repository's **Security** tab → **Report a vulnerability**, or
   [open a draft advisory directly](https://github.com/Codename-11/hermes-relay/security/advisories/new).
   This keeps the whole exchange private and threaded with the code.
2. **Email** — `security@codename-11.dev`. Use this if you can't use GitHub.
   If you'd like to encrypt the report, say so in a first contact message and
   we'll arrange a key.

### What to include

A good report lets us reproduce and assess impact quickly:

- The affected surface — **Android app** (and which flavor, `googlePlay` or
  `sideload`), **relay plugin / server**, **desktop CLI**, or the **docs site**.
- Affected version(s) — app version/code, plugin version, or CLI version.
- A clear description of the issue and its security impact.
- Step-by-step reproduction, a proof of concept, or a minimal example.
- Any suggested remediation, if you have one.

> ⚠️ **Scrub secrets before sending.** Remove API keys, relay session tokens,
> pairing codes, real hostnames/IPs, and personal data from logs, traces, and
> screenshots.

## What to Expect

This is an indie, open-source project, so timelines are best-effort rather than
contractual:

- **Acknowledgement** of your report — typically within **5 business days**.
- An initial **assessment and severity triage** after we can reproduce it.
- **Coordinated disclosure:** we'll work with you on a fix and a disclosure
  timeline, and credit you in the advisory and release notes if you'd like
  (or keep you anonymous if you prefer).
- A public GitHub Security Advisory and a `CHANGELOG.md` entry once a fix ships.

## Scope

**In scope** — vulnerabilities in code this project ships:

- The Android app (`app/`) on either flavor.
- The relay plugin and server (`plugin/`).
- The desktop CLI (`desktop/`).
- The pairing, auth, transport, media, and tool-routing surfaces.

**Out of scope** — please report these to the right place instead:

- **Your own Hermes server configuration** (missing TLS, an exposed dashboard,
  weak provider keys). The relay connects only to endpoints you configure; how
  you deploy and secure your Hermes host is outside this app. See
  [`docs/security.md`](docs/security.md) and the relay-server docs for hardening
  guidance.
- **Upstream [hermes-agent](https://github.com/NousResearch/hermes-agent)**
  issues — report those to the upstream project (a heads-up to us is welcome if
  it affects how Hermes-Relay should behave).
- **Third-party dependencies** — report upstream; if a dependency issue affects
  Hermes-Relay users, tell us so we can pin or patch.
- Findings that require a **rooted device, a physical-access attacker, or a
  malicious app already granted Accessibility/overlay permissions** — these are
  outside the model documented in `docs/security.md`, though we'll still read
  the report.

## Safe Harbor

We consider security research conducted in good faith under this policy to be
authorized. We will not pursue or support legal action against researchers who:

- Make a good-faith effort to avoid privacy violations, data destruction, and
  service disruption.
- Test only against **their own devices, installs, and Hermes servers** — never
  another person's data or infrastructure.
- Report promptly and give us a reasonable chance to remediate before any
  public disclosure.

Thank you for helping keep Hermes-Relay and its users safe.
