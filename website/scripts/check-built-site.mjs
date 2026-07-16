import { access, readFile, readdir } from 'node:fs/promises';
import { extname, join, resolve } from 'node:path';

const distRoot = resolve('dist');
const failures = [];
const htmlFiles = [];

async function walk(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) await walk(path);
    else if (entry.name.endsWith('.html')) htmlFiles.push(path);
  }
}

function localTargetPath(reference) {
  const pathname = decodeURIComponent(reference.split(/[?#]/, 1)[0]);
  if (pathname === '/') return join(distRoot, 'index.html');
  const relative = pathname.replace(/^\//, '');
  if (extname(relative)) return join(distRoot, relative);
  return join(distRoot, relative, 'index.html');
}

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

await walk(distRoot);

for (const htmlPath of htmlFiles) {
  const html = await readFile(htmlPath, 'utf8');
  const ids = new Set([...html.matchAll(/\sid=["']([^"']+)["']/g)].map((match) => match[1]));
  const references = [...html.matchAll(/\s(?:href|src)=["']([^"']+)["']/g)].map((match) => match[1]);

  for (const reference of references) {
    if (!reference || reference.startsWith('data:') || reference.startsWith('mailto:') || reference.startsWith('tel:')) continue;

    if (reference.startsWith('#')) {
      const id = decodeURIComponent(reference.slice(1));
      if (id && !ids.has(id)) failures.push(`${htmlPath}: missing fragment target ${reference}`);
      continue;
    }

    if (/^https?:\/\//.test(reference)) {
      try {
        new URL(reference);
      } catch {
        failures.push(`${htmlPath}: invalid external URL ${reference}`);
      }
      continue;
    }

    if (reference.startsWith('/')) {
      const target = localTargetPath(reference);
      if (!(await exists(target))) failures.push(`${htmlPath}: missing local asset ${reference}`);
    }
  }

  for (const required of ['name="description"', 'property="og:title"', 'name="twitter:card"', 'application/ld+json']) {
    if (!html.includes(required)) failures.push(`${htmlPath}: missing production metadata ${required}`);
  }

  for (const required of ['rel="canonical"', 'property="og:url"', 'property="og:image"']) {
    if (!html.includes(required)) failures.push(`${htmlPath}: missing URL-bound metadata ${required}`);
  }
}

if (failures.length) {
  console.error(`Built-site validation failed:\n- ${failures.join('\n- ')}`);
  process.exit(1);
}

console.log(`Built-site validation passed for ${htmlFiles.length} HTML file(s).`);
