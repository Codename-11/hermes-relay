import { defineConfig } from 'astro/config';

const publicSiteUrl = process.env.PUBLIC_SITE_URL?.trim() || 'https://hermes-relay.dev';

export default defineConfig({
  output: 'static',
  site: publicSiteUrl,
  server: {
    host: '0.0.0.0',
    allowedHosts: ['terminal.local'],
  },
});
