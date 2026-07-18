# Legacy documentation redirect

This directory is a temporary compatibility shim for Android releases that
hardcoded `https://codename-11.github.io/hermes-relay/` before PR #210.
Documentation is hosted only at `https://hermes-relay.dev/docs/`; GitHub Pages
serves redirect HTML for old documentation paths. The historical
`privacy.html` URL serves the complete policy from the canonical
`website/public/privacy.html` source so store-review crawlers and installed
clients never depend on JavaScript redirects.

The deployment workflow copies `redirect.html` to the project root, the Pages
404 fallback, and the exact deep-link paths embedded in released clients. The
JavaScript preserves the path, query string, and fragment while moving the
request under `/docs/`. The meta refresh and visible link provide a no-script
fallback to the guide root. Privacy compatibility pages identify
`https://hermes-relay.dev/privacy.html` as canonical.

Removal is tracked in the repository root `TODO.md`. Do not delete this shim
solely because the first fixed release has shipped; honor the documented
compatibility window for older Play and sideload installations.
