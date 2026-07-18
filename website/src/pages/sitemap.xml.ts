import type { APIRoute } from 'astro';
import { localeInfo, locales } from '../i18n';

export const prerender = true;

export const GET: APIRoute = ({ site }) => {
  const locations = site
    ? locales.map((locale) => `<url><loc>${new URL(localeInfo[locale].path, site).href}</loc></url>`).join('')
    : '';
  const body = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${locations}</urlset>\n`;

  return new Response(body, {
    headers: { 'Content-Type': 'application/xml; charset=utf-8' },
  });
};
