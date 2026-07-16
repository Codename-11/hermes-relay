const rawSiteUrl = process.env.PUBLIC_SITE_URL?.trim() || 'https://hermes-relay.dev';

let siteUrl;
try {
  siteUrl = new URL(rawSiteUrl);
} catch {
  console.error(`PUBLIC_SITE_URL is not a valid absolute URL: ${rawSiteUrl}`);
  process.exit(1);
}

if (siteUrl.protocol !== 'https:') {
  console.error('PUBLIC_SITE_URL must use HTTPS for a production build.');
  process.exit(1);
}

if (siteUrl.pathname !== '/' || siteUrl.search || siteUrl.hash) {
  console.error('PUBLIC_SITE_URL must be an origin without a path, query, or fragment.');
  process.exit(1);
}

console.log(`Production site URL: ${siteUrl.origin}`);
