import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const websiteRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const productDir = resolve(websiteRoot, 'public/product');
const mode = process.argv[2] ?? 'check';
const variants = [
  { source: 'chat.png', destination: 'chat-260.webp', width: 260 },
  { source: 'chat.png', destination: 'chat-520.webp', width: 520 },
  { source: 'manage.png', destination: 'manage-360.webp', width: 360 },
  { source: 'manage.png', destination: 'manage-720.webp', width: 720 },
];

if (!['check', 'sync'].includes(mode)) {
  console.error('Usage: node scripts/responsive-assets.mjs <check|sync>');
  process.exit(2);
}

await mkdir(productDir, { recursive: true });
const failures = [];

for (const variant of variants) {
  const source = resolve(productDir, variant.source);
  const destination = resolve(productDir, variant.destination);
  const expected = await sharp(source)
    .resize({ width: variant.width, withoutEnlargement: true })
    .webp({ quality: 82, effort: 4 })
    .toBuffer();

  if (mode === 'sync') {
    await writeFile(destination, expected);
    console.log(`generated ${variant.destination} from ${variant.source}`);
    continue;
  }

  try {
    const actual = await readFile(destination);
    if (!actual.equals(expected)) failures.push(`${variant.destination}: stale responsive derivative`);
    else console.log(`verified ${variant.destination}`);
  } catch (error) {
    failures.push(`${variant.destination}: ${error instanceof Error ? error.message : String(error)}`);
  }
}

if (failures.length) {
  console.error('\nResponsive screenshot drift detected:');
  failures.forEach((failure) => console.error(`- ${failure}`));
  console.error('\nRun `npm run assets:sync` from website/ and commit the refreshed derivatives.');
  process.exit(1);
}

if (mode === 'check') console.log('Responsive screenshot derivatives match their canonical sources.');
