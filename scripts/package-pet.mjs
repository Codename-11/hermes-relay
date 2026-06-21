#!/usr/bin/env node
import { readFile, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const USAGE = `Usage:
  node scripts/package-pet.mjs <pet-folder> [--out <file.zip>] [--flat] [--dry-run]

Build an app-importable Hermes-Relay pet ZIP from a folder containing pet.json.
Only pet.json and assets referenced by the manifest are included.
`;

function parseArgs(argv) {
  const args = { input: '', out: '', flat: false, dryRun: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      console.log(USAGE);
      process.exit(0);
    }
    if (arg === '--out' || arg === '-o') {
      args.out = argv[++i] || '';
      continue;
    }
    if (arg === '--flat') {
      args.flat = true;
      continue;
    }
    if (arg === '--dry-run') {
      args.dryRun = true;
      continue;
    }
    if (!args.input) {
      args.input = arg;
      continue;
    }
    throw new Error(`Unexpected argument: ${arg}`);
  }
  if (!args.input) throw new Error('Missing pet folder.');
  return args;
}

function sanitizeId(value) {
  return String(value || 'pet')
    .trim()
    .replace(/[^A-Za-z0-9._-]+/g, '-')
    .replace(/^[.-]+|[.-]+$/g, '') || 'pet';
}

function normalizeRef(raw, context) {
  if (typeof raw !== 'string' || raw.trim() === '') {
    throw new Error(`${context} references a blank path.`);
  }
  const ref = raw.replace(/\\/g, '/').replace(/^\/+/, '');
  const parts = ref.split('/').filter(Boolean);
  if (path.isAbsolute(raw) || /^[A-Za-z]:/.test(raw) || parts.some((part) => part === '..')) {
    throw new Error(`${context} references an unsafe path: ${raw}`);
  }
  return parts.join('/');
}

function isPositiveNumber(value) {
  return Number.isFinite(Number(value)) && Number(value) > 0;
}

function collectClipAssets(clip, name, assetRefs, warnings) {
  if (!clip || typeof clip !== 'object' || Array.isArray(clip)) {
    throw new Error(`${name} must be an object clip.`);
  }

  const hasFrames = Array.isArray(clip.frames) && clip.frames.length > 0;
  const hasSheet = typeof clip.sheet === 'string' && clip.sheet.trim() !== '';

  if (!hasFrames && !hasSheet) {
    throw new Error(`${name} needs either a non-empty frames list or a sheet.`);
  }
  if (hasFrames && hasSheet) {
    warnings.push(`${name} declares both frames and sheet; the app uses frames first.`);
  }

  if (hasFrames) {
    clip.frames.forEach((frame, index) => {
      assetRefs.add(normalizeRef(frame, `${name}.frames[${index}]`));
    });
  } else {
    assetRefs.add(normalizeRef(clip.sheet, `${name}.sheet`));
    for (const field of ['frameWidth', 'frameHeight', 'frameCount']) {
      if (!isPositiveNumber(clip[field])) throw new Error(`${name}.${field} must be positive for a sheet clip.`);
    }
  }
}

async function existsFile(file) {
  try {
    return (await stat(file)).isFile();
  } catch {
    return false;
  }
}

async function validatePack(packDir) {
  const manifestPath = path.join(packDir, 'pet.json');
  if (!(await existsFile(manifestPath))) throw new Error(`No pet.json found at ${manifestPath}`);

  const manifestText = await readFile(manifestPath, 'utf8');
  let spec;
  try {
    spec = JSON.parse(manifestText);
  } catch (error) {
    throw new Error(`pet.json is not valid JSON: ${error.message}`);
  }

  if (!spec.states || typeof spec.states !== 'object' || Array.isArray(spec.states)) {
    throw new Error('pet.json needs a states object.');
  }
  if (!spec.states.idle) throw new Error("pet.json needs a usable 'idle' clip.");

  const warnings = [];
  const assetRefs = new Set();
  for (const [name, clip] of Object.entries(spec.states)) {
    collectClipAssets(clip, `states.${name}`, assetRefs, warnings);
  }
  if (spec.defaults) collectClipAssets(spec.defaults, 'defaults', assetRefs, warnings);

  const assets = [];
  for (const ref of [...assetRefs].sort()) {
    const resolved = path.resolve(packDir, ref);
    const base = path.resolve(packDir) + path.sep;
    if (!resolved.startsWith(base)) throw new Error(`Asset escapes the pack folder: ${ref}`);
    if (!(await existsFile(resolved))) throw new Error(`Referenced asset is missing: ${ref}`);
    assets.push({ ref, file: resolved });
  }

  const id = sanitizeId(spec.id || path.basename(packDir));
  const label = typeof spec.label === 'string' && spec.label.trim() ? spec.label.trim() : id;
  return { id, label, manifestText, assets, warnings };
}

const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n += 1) {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  crcTable[n] = c >>> 0;
}

function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function dosDateTime(date = new Date()) {
  const year = Math.max(1980, date.getFullYear());
  const dosTime = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2);
  const dosDate = ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { dosTime, dosDate };
}

function buildZip(entries) {
  const fileParts = [];
  const centralParts = [];
  let offset = 0;
  const { dosTime, dosDate } = dosDateTime();

  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8');
    const data = Buffer.from(entry.data);
    const crc = crc32(data);

    const local = Buffer.alloc(30 + name.length);
    let p = 0;
    local.writeUInt32LE(0x04034b50, p); p += 4;
    local.writeUInt16LE(20, p); p += 2;
    local.writeUInt16LE(0x0800, p); p += 2;
    local.writeUInt16LE(0, p); p += 2;
    local.writeUInt16LE(dosTime, p); p += 2;
    local.writeUInt16LE(dosDate, p); p += 2;
    local.writeUInt32LE(crc, p); p += 4;
    local.writeUInt32LE(data.length, p); p += 4;
    local.writeUInt32LE(data.length, p); p += 4;
    local.writeUInt16LE(name.length, p); p += 2;
    local.writeUInt16LE(0, p); p += 2;
    name.copy(local, p);

    const central = Buffer.alloc(46 + name.length);
    p = 0;
    central.writeUInt32LE(0x02014b50, p); p += 4;
    central.writeUInt16LE(20, p); p += 2;
    central.writeUInt16LE(20, p); p += 2;
    central.writeUInt16LE(0x0800, p); p += 2;
    central.writeUInt16LE(0, p); p += 2;
    central.writeUInt16LE(dosTime, p); p += 2;
    central.writeUInt16LE(dosDate, p); p += 2;
    central.writeUInt32LE(crc, p); p += 4;
    central.writeUInt32LE(data.length, p); p += 4;
    central.writeUInt32LE(data.length, p); p += 4;
    central.writeUInt16LE(name.length, p); p += 2;
    central.writeUInt16LE(0, p); p += 2;
    central.writeUInt16LE(0, p); p += 2;
    central.writeUInt16LE(0, p); p += 2;
    central.writeUInt16LE(0, p); p += 2;
    central.writeUInt32LE(0, p); p += 4;
    central.writeUInt32LE(offset, p); p += 4;
    name.copy(central, p);

    fileParts.push(local, data);
    centralParts.push(central);
    offset += local.length + data.length;
  }

  const centralOffset = offset;
  const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
  const end = Buffer.alloc(22);
  let p = 0;
  end.writeUInt32LE(0x06054b50, p); p += 4;
  end.writeUInt16LE(0, p); p += 2;
  end.writeUInt16LE(0, p); p += 2;
  end.writeUInt16LE(entries.length, p); p += 2;
  end.writeUInt16LE(entries.length, p); p += 2;
  end.writeUInt32LE(centralSize, p); p += 4;
  end.writeUInt32LE(centralOffset, p); p += 4;
  end.writeUInt16LE(0, p);

  return Buffer.concat([...fileParts, ...centralParts, end]);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const packDir = path.resolve(args.input);
  const pack = await validatePack(packDir);
  const top = args.flat ? '' : `${pack.id}/`;
  const entries = [
    { name: `${top}pet.json`, data: Buffer.from(pack.manifestText, 'utf8') },
  ];
  for (const asset of pack.assets) {
    entries.push({ name: `${top}${asset.ref}`, data: await readFile(asset.file) });
  }

  const out = path.resolve(args.out || path.join(path.dirname(packDir), `${pack.id}.zip`));
  const zip = buildZip(entries);

  console.log(`Pet: ${pack.label} (${pack.id})`);
  console.log(`Source: ${packDir}`);
  console.log(`Entries: ${entries.length} (${entries.map((entry) => entry.name).join(', ')})`);
  for (const warning of pack.warnings) console.warn(`Warning: ${warning}`);

  if (args.dryRun) {
    console.log(`Dry run: would write ${out} (${zip.length} bytes)`);
    return;
  }

  await writeFile(out, zip);
  console.log(`Wrote ${out} (${zip.length} bytes)`);
}

main().catch((error) => {
  console.error(`Error: ${error.message}`);
  process.exit(1);
});
