import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const websiteRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = resolve(websiteRoot, '..');
const manifestPath = resolve(repositoryRoot, 'docs/media/screenshots.json');

const productAssets = [
  { sceneId: '02_chat', destination: 'chat.png' },
  { sceneId: '03_voice', destination: 'voice.png' },
  { sceneId: '06_manage', destination: 'manage.png' },
];

const mode = process.argv[2] ?? 'check';
if (!['check', 'sync'].includes(mode)) {
  console.error('Usage: node scripts/product-assets.mjs <check|sync>');
  process.exitCode = 2;
} else {
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  const scenes = new Map(manifest.scenes.map((scene) => [scene.id, scene]));
  const destinationDir = resolve(websiteRoot, 'public/product');
  const failures = [];

  await mkdir(destinationDir, { recursive: true });

  for (const asset of productAssets) {
    const scene = scenes.get(asset.sceneId);
    if (!scene?.source) {
      failures.push(`${asset.sceneId}: missing from docs/media/screenshots.json`);
      continue;
    }

    const source = resolve(repositoryRoot, scene.source);
    const destination = resolve(destinationDir, asset.destination);

    if (mode === 'sync') {
      await copyFile(source, destination);
      console.log(`synced ${asset.destination} <- ${scene.source}`);
      continue;
    }

    try {
      const [sourceBytes, destinationBytes] = await Promise.all([
        readFile(source),
        readFile(destination),
      ]);

      if (!sourceBytes.equals(destinationBytes)) {
        const shortHash = (bytes) => createHash('sha256').update(bytes).digest('hex').slice(0, 12);
        failures.push(
          `${asset.destination}: stale (website ${shortHash(destinationBytes)}, canonical ${shortHash(sourceBytes)})`,
        );
      } else {
        console.log(`verified ${asset.destination} == ${scene.source}`);
      }
    } catch (error) {
      failures.push(`${asset.destination}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  if (failures.length > 0) {
    console.error('\nProduct screenshot drift detected:');
    failures.forEach((failure) => console.error(`- ${failure}`));
    console.error('\nRun `npm run assets:sync` from website/ and commit the refreshed assets.');
    process.exitCode = 1;
  } else if (mode === 'check') {
    console.log('Product screenshots match their canonical repository sources.');
  }
}
