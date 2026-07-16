# Hermes-Relay website

The public product and marketing site for Hermes-Relay. Technical documentation
continues to live in `user-docs/` and is linked from this site as a separate
destination.

## Local development

```bash
npm install
npm run dev
```

The production build is fully static:

```bash
npm run check
npm run build
```

Astro writes the deployable site to `dist/`.

The canonical production origin is `https://hermes-relay.dev`. Canonical, Open
Graph, robots, sitemap, and structured-data URLs are generated from that one
source of truth. Preview or staging builds can override it when needed:

```bash
PUBLIC_SITE_URL=https://preview.hermes-relay.dev npm run build:production
```

No analytics or third-party tracking scripts are included.

## Product screenshots

Chat, Voice, and Manage imagery comes from the repository's canonical,
deterministically rendered Android scenes in `../assets/screenshots/`. The
mapping is read from `../docs/media/screenshots.json`; files under
`public/product/` are deployment copies, not an independent source of truth.

Refresh the website copies after regenerating canonical screenshots:

```bash
npm run assets:sync
```

Check for drift without changing files:

```bash
npm run assets:check
```

`npm run dev`, `npm run check`, and `npm run build` all run the drift check.
Website CI and the Coolify build therefore fail with a direct remediation
message when a canonical screenshot changes without its website copy.

The same scripts generate and verify smaller WebP derivatives used by the
responsive `srcset` declarations. The full-resolution PNG files remain the
canonical fallback; do not hand-edit the WebP variants.

## Coolify

Deploy the website with its repository-owned Dockerfile. The build context must
remain the repository root because the production asset check compares website
copies against canonical screenshots under `docs/media/`.

- Build pack: Dockerfile
- Base directory: `/`
- Dockerfile location: `/website/Dockerfile`
- Exposed port: `80`
- Health check: `GET /` expecting `200`
- Domain: `https://hermes-relay.dev`
- Optional environment override: `PUBLIC_SITE_URL=https://<preview-domain>`
- Force HTTPS: enabled

The Dockerfile builds with Node 22, runs `npm run build:production` from the
website workspace, and serves the resulting static `/website/dist` tree with
Nginx. Do not isolate `/website` as the Coolify base directory: doing so omits
the canonical screenshot sources and correctly causes the asset-integrity gate
to fail.

Assign `https://hermes-relay.dev` in Coolify and redeploy. If
`PUBLIC_SITE_URL` is supplied for a preview environment, the production build
fails early when it is not HTTPS or contains a path/query/fragment.

## Launch checks

```bash
npm run check
npm run build
npm run links:check
```

The build validates local assets, same-page fragments, external URL syntax,
description/Open Graph/Twitter metadata, and JSON-LD. A production build also
requires absolute canonical and social-image metadata.
