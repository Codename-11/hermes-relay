<template>
  <section
    class="pet-pack-builder"
    :class="{ dragging: isDragging }"
    @dragover.prevent="isDragging = true"
    @dragleave.prevent="isDragging = false"
    @drop.prevent="handleDrop"
  >
    <div class="ppb-header">
      <div>
        <p class="ppb-kicker">Pet pack builder</p>
        <h3>Make the import zip</h3>
      </div>
      <span class="ppb-privacy">Local only</span>
    </div>

    <p class="ppb-copy">
      Choose the folder that contains <code>pet.json</code>. The builder checks
      references, excludes unreferenced files, and downloads the zip the app can import.
    </p>

    <div class="ppb-actions">
      <button type="button" class="ppb-button primary" @click="folderInput?.click()">Choose folder</button>
      <button type="button" class="ppb-button" @click="fileInput?.click()">Choose files</button>
      <a
        v-if="downloadUrl"
        class="ppb-button success"
        :href="downloadUrl"
        :download="downloadName"
      >
        Download zip
      </a>
    </div>

    <input
      ref="folderInput"
      class="ppb-hidden-input"
      type="file"
      multiple
      webkitdirectory
      @change="handleInput"
    />
    <input
      ref="fileInput"
      class="ppb-hidden-input"
      type="file"
      multiple
      @change="handleInput"
    />

    <div class="ppb-drop">
      <strong>{{ dropTitle }}</strong>
      <span>{{ dropSubtitle }}</span>
    </div>

    <div v-if="summary" class="ppb-summary" aria-live="polite">
      <div>
        <span class="ppb-label">Pet</span>
        <strong>{{ summary.label }}</strong>
      </div>
      <div>
        <span class="ppb-label">Assets</span>
        <strong>{{ summary.assetCount }}</strong>
      </div>
      <div>
        <span class="ppb-label">Archive</span>
        <strong>{{ downloadName }}</strong>
      </div>
    </div>

    <ul v-if="messages.length" class="ppb-messages" aria-live="polite">
      <li v-for="message in messages" :key="message.text" :class="message.kind">
        {{ message.text }}
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';

type FileRecord = {
  path: string;
  file: File;
};

type Message = {
  kind: 'ok' | 'warn' | 'error';
  text: string;
};

type Clip = {
  frames?: unknown;
  sheet?: unknown;
  frameWidth?: unknown;
  frameHeight?: unknown;
  frameCount?: unknown;
};

type ValidationPlan = {
  id: string;
  label: string;
  manifestText: string;
  assets: FileRecord[];
  warnings: string[];
};

const folderInput = ref<HTMLInputElement | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const isDragging = ref(false);
const messages = ref<Message[]>([]);
const summary = ref<{ label: string; assetCount: number } | null>(null);
const downloadUrl = ref('');
const downloadName = ref('pet.zip');

const dropTitle = computed(() => (isDragging.value ? 'Drop to inspect the pack' : 'Drop pet files here'));
const dropSubtitle = computed(() => 'Folder selection keeps paths intact. Files never leave this browser.');

onBeforeUnmount(() => resetDownload());

function setMessages(next: Message[]) {
  messages.value = next;
}

function resetDownload() {
  if (downloadUrl.value) URL.revokeObjectURL(downloadUrl.value);
  downloadUrl.value = '';
  downloadName.value = 'pet.zip';
  summary.value = null;
}

function normalizePath(value: string): string {
  return value.replace(/\\/g, '/').replace(/^\/+/, '').split('/').filter(Boolean).join('/');
}

function pathParts(value: string): string[] {
  return normalizePath(value).split('/').filter(Boolean);
}

function basename(value: string): string {
  const parts = pathParts(value);
  return parts[parts.length - 1] || value;
}

function parentPrefix(value: string): string {
  const parts = pathParts(value);
  parts.pop();
  return parts.length ? `${parts.join('/')}/` : '';
}

function sanitizeId(value: unknown): string {
  const cleaned = String(value || 'pet')
    .trim()
    .replace(/[^A-Za-z0-9._-]+/g, '-')
    .replace(/^[.-]+|[.-]+$/g, '');
  return cleaned || 'pet';
}

function scopedPath(pathValue: string, prefix: string): string {
  const normalized = normalizePath(pathValue);
  return prefix && normalized.startsWith(prefix) ? normalized.slice(prefix.length) : normalized;
}

function normalizeRef(raw: unknown, context: string): string {
  if (typeof raw !== 'string' || !raw.trim()) throw new Error(`${context} references a blank path.`);
  const ref = normalizePath(raw);
  const parts = pathParts(ref);
  if (/^[A-Za-z]:/.test(raw) || parts.some((part) => part === '..')) {
    throw new Error(`${context} references an unsafe path: ${raw}`);
  }
  return parts.join('/');
}

function isPositive(value: unknown): boolean {
  return Number.isFinite(Number(value)) && Number(value) > 0;
}

function collectClipAssets(clip: Clip, name: string, refs: Set<string>, warnings: string[]) {
  if (!clip || typeof clip !== 'object' || Array.isArray(clip)) throw new Error(`${name} must be an object clip.`);
  const frames = Array.isArray(clip.frames) ? clip.frames : [];
  const hasFrames = frames.length > 0;
  const hasSheet = typeof clip.sheet === 'string' && clip.sheet.trim() !== '';

  if (!hasFrames && !hasSheet) throw new Error(`${name} needs either a non-empty frames list or a sheet.`);
  if (hasFrames && hasSheet) warnings.push(`${name} declares both frames and sheet; the app uses frames first.`);

  if (hasFrames) {
    frames.forEach((frame, index) => refs.add(normalizeRef(frame, `${name}.frames[${index}]`)));
    return;
  }

  refs.add(normalizeRef(clip.sheet, `${name}.sheet`));
  for (const field of ['frameWidth', 'frameHeight', 'frameCount'] as const) {
    if (!isPositive(clip[field])) throw new Error(`${name}.${field} must be positive for a sheet clip.`);
  }
}

async function planPack(records: FileRecord[]): Promise<ValidationPlan> {
  if (!records.length) throw new Error('Choose pet.json and the referenced image files.');

  const manifests = records
    .filter((record) => basename(record.path).toLowerCase() === 'pet.json')
    .sort((a, b) => pathParts(a.path).length - pathParts(b.path).length);
  const manifest = manifests[0];
  if (!manifest) throw new Error('No pet.json found.');

  const prefix = parentPrefix(manifest.path);
  const scoped = records.map((record) => ({
    path: scopedPath(record.path, prefix),
    file: record.file,
  }));
  const lookup = new Map(scoped.map((record) => [record.path, record]));
  const manifestRecord = lookup.get('pet.json');
  if (!manifestRecord) throw new Error('pet.json must be inside the chosen pack folder.');

  const manifestText = await manifestRecord.file.text();
  let spec: any;
  try {
    spec = JSON.parse(manifestText);
  } catch (error: any) {
    throw new Error(`pet.json is not valid JSON: ${error.message}`);
  }

  if (!spec.states || typeof spec.states !== 'object' || Array.isArray(spec.states)) {
    throw new Error('pet.json needs a states object.');
  }
  if (!spec.states.idle) throw new Error("pet.json needs a usable 'idle' clip.");

  const warnings: string[] = [];
  const refs = new Set<string>();
  Object.entries(spec.states).forEach(([name, clip]) => collectClipAssets(clip as Clip, `states.${name}`, refs, warnings));
  if (spec.defaults) collectClipAssets(spec.defaults, 'defaults', refs, warnings);

  const assets: FileRecord[] = [];
  refs.forEach((refPath) => {
    const asset = lookup.get(refPath);
    if (!asset) throw new Error(`Referenced asset is missing: ${refPath}`);
    assets.push(asset);
  });

  const referenced = new Set(['pet.json', ...refs]);
  const extras = scoped.filter((record) => !referenced.has(record.path));
  if (extras.length) warnings.push(`${extras.length} unreferenced file(s) will be left out of the zip.`);

  const id = sanitizeId(spec.id || prefix.split('/').filter(Boolean).pop() || 'pet');
  const label = typeof spec.label === 'string' && spec.label.trim() ? spec.label.trim() : id;
  return { id, label, manifestText, assets: assets.sort((a, b) => a.path.localeCompare(b.path)), warnings };
}

async function packageRecords(records: FileRecord[]) {
  resetDownload();
  setMessages([{ kind: 'ok', text: 'Inspecting pack...' }]);

  try {
    const plan = await planPack(records);
    const entries: ZipEntryInput[] = [
      { name: `${plan.id}/pet.json`, data: new TextEncoder().encode(plan.manifestText) },
    ];
    for (const asset of plan.assets) {
      entries.push({ name: `${plan.id}/${asset.path}`, data: new Uint8Array(await asset.file.arrayBuffer()) });
    }

    const zip = buildZip(entries);
    downloadName.value = `${plan.id}.zip`;
    downloadUrl.value = URL.createObjectURL(new Blob([zip], { type: 'application/zip' }));
    summary.value = { label: `${plan.label} (${plan.id})`, assetCount: plan.assets.length };
    setMessages([
      { kind: 'ok', text: `Ready: ${entries.length} file(s) packaged with a single top-level folder.` },
      ...plan.warnings.map((text) => ({ kind: 'warn' as const, text })),
    ]);
  } catch (error: any) {
    setMessages([{ kind: 'error', text: error.message || 'Could not package those files.' }]);
  }
}

function recordsFromFileList(fileList: FileList | null): FileRecord[] {
  return Array.from(fileList || []).map((file) => ({
    path: normalizePath((file as any).webkitRelativePath || file.name),
    file,
  }));
}

function handleInput(event: Event) {
  const input = event.target as HTMLInputElement;
  packageRecords(recordsFromFileList(input.files));
  input.value = '';
}

async function handleDrop(event: DragEvent) {
  isDragging.value = false;
  const records = await recordsFromDrop(event);
  packageRecords(records);
}

async function recordsFromDrop(event: DragEvent): Promise<FileRecord[]> {
  const items = Array.from(event.dataTransfer?.items || []);
  const entryItems = items
    .map((item: any) => (typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null))
    .filter(Boolean);

  if (!entryItems.length) return recordsFromFileList(event.dataTransfer?.files || null);

  const groups = await Promise.all(entryItems.map((entry: any) => readEntry(entry, '')));
  return groups.flat();
}

function readEntry(entry: any, prefix: string): Promise<FileRecord[]> {
  if (entry.isFile) {
    return new Promise((resolve, reject) => {
      entry.file(
        (file: File) => resolve([{ path: normalizePath(`${prefix}${entry.name}`), file }]),
        reject,
      );
    });
  }

  if (entry.isDirectory) {
    const reader = entry.createReader();
    return new Promise((resolve, reject) => {
      const all: any[] = [];
      const readBatch = () => {
        reader.readEntries(
          async (entries: any[]) => {
            if (!entries.length) {
              try {
                const children = await Promise.all(all.map((child) => readEntry(child, `${prefix}${entry.name}/`)));
                resolve(children.flat());
              } catch (error) {
                reject(error);
              }
              return;
            }
            all.push(...entries);
            readBatch();
          },
          reject,
        );
      };
      readBatch();
    });
  }

  return Promise.resolve([]);
}

type ZipEntryInput = {
  name: string;
  data: Uint8Array;
};

const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n += 1) {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  crcTable[n] = c >>> 0;
}

function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  data.forEach((byte) => {
    c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8);
  });
  return (c ^ 0xffffffff) >>> 0;
}

function dosDateTime(date = new Date()) {
  const year = Math.max(1980, date.getFullYear());
  return {
    time: (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2),
    date: ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate(),
  };
}

function u16(value: number): number[] {
  return [value & 0xff, (value >>> 8) & 0xff];
}

function u32(value: number): number[] {
  return [value & 0xff, (value >>> 8) & 0xff, (value >>> 16) & 0xff, (value >>> 24) & 0xff];
}

function bytes(...chunks: number[][]): Uint8Array {
  return Uint8Array.from(chunks.flat());
}

function concat(parts: Uint8Array[]): Uint8Array {
  const size = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(size);
  let offset = 0;
  parts.forEach((part) => {
    out.set(part, offset);
    offset += part.length;
  });
  return out;
}

function buildZip(entries: ZipEntryInput[]): Uint8Array {
  const encoder = new TextEncoder();
  const { time, date } = dosDateTime();
  const fileParts: Uint8Array[] = [];
  const centralParts: Uint8Array[] = [];
  let offset = 0;

  entries.forEach((entry) => {
    const name = encoder.encode(entry.name);
    const data = entry.data;
    const crc = crc32(data);

    const local = concat([
      bytes(
        u32(0x04034b50), u16(20), u16(0x0800), u16(0), u16(time), u16(date),
        u32(crc), u32(data.length), u32(data.length), u16(name.length), u16(0),
      ),
      name,
    ]);
    const central = concat([
      bytes(
        u32(0x02014b50), u16(20), u16(20), u16(0x0800), u16(0), u16(time), u16(date),
        u32(crc), u32(data.length), u32(data.length), u16(name.length), u16(0), u16(0),
        u16(0), u16(0), u32(0), u32(offset),
      ),
      name,
    ]);

    fileParts.push(local, data);
    centralParts.push(central);
    offset += local.length + data.length;
  });

  const centralOffset = offset;
  const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
  const end = bytes(
    u32(0x06054b50), u16(0), u16(0), u16(entries.length), u16(entries.length),
    u32(centralSize), u32(centralOffset), u16(0),
  );

  return concat([...fileParts, ...centralParts, end]);
}
</script>

<style scoped>
.pet-pack-builder {
  margin: 28px 0;
  padding: 18px;
  border: 1px solid var(--hr-line);
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(110, 124, 255, 0.08), transparent 42%),
    var(--vp-c-bg-alt);
}

.pet-pack-builder.dragging {
  border-color: var(--vp-c-brand-1);
}

.ppb-header,
.ppb-actions,
.ppb-summary {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ppb-header {
  justify-content: space-between;
}

.ppb-kicker,
.ppb-label,
.ppb-privacy {
  margin: 0;
  font-family: var(--vp-font-family-mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--vp-c-text-3);
}

.ppb-header h3 {
  margin: 2px 0 0;
  padding: 0;
  border: 0;
}

.ppb-privacy {
  padding: 3px 8px;
  border: 1px solid var(--hr-line);
  border-radius: 999px;
}

.ppb-copy {
  max-width: 680px;
  margin: 14px 0;
  color: var(--vp-c-text-2);
}

.ppb-actions {
  flex-wrap: wrap;
}

.ppb-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  padding: 0 13px;
  border: 1px solid var(--hr-line-strong);
  border-radius: 999px;
  background: transparent;
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  cursor: pointer;
}

.ppb-button:hover,
.ppb-button:focus-visible {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
  text-decoration: none;
}

.ppb-button.primary {
  border-color: var(--vp-c-brand-2);
  background: var(--vp-c-brand-2);
  color: #fff;
}

.ppb-button.success {
  border-color: var(--hr-green);
  color: var(--hr-green);
}

.ppb-hidden-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.ppb-drop {
  display: grid;
  gap: 4px;
  margin-top: 14px;
  padding: 18px;
  border: 1px dashed var(--hr-line-strong);
  border-radius: 6px;
  color: var(--vp-c-text-2);
}

.ppb-drop strong {
  color: var(--vp-c-text-1);
}

.ppb-summary {
  flex-wrap: wrap;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--hr-line);
}

.ppb-summary > div {
  min-width: 140px;
}

.ppb-summary strong {
  display: block;
  font-size: 14px;
}

.ppb-messages {
  display: grid;
  gap: 6px;
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
}

.ppb-messages li {
  margin: 0;
  padding: 8px 10px;
  border-left: 3px solid var(--vp-c-brand-1);
  background: var(--vp-c-bg);
  font-size: 13px;
}

.ppb-messages li.warn {
  border-left-color: var(--hr-amber);
}

.ppb-messages li.error {
  border-left-color: var(--hr-danger);
}

@media (max-width: 640px) {
  .ppb-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .ppb-button {
    width: 100%;
  }
}
</style>
