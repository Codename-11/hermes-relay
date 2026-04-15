#!/usr/bin/env node
// @subframe-version 0.15.1-beta
// @subframe-managed
/**
 * SubFrame STRUCTURE.json Updater
 * Called by .githooks/pre-commit when source files in src/ are staged.
 * Reads STAGED_FILES and DELETED_FILES from environment variables.
 */

const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const STRUCTURE_FILE = path.join(ROOT, '.subframe', 'STRUCTURE.json');
const SRC_DIR = path.join(ROOT, 'src');

// Strip any JS/TS extension for module key
function stripExt(p) {
  return p.replace(/\.(js|ts|tsx|jsx)$/, '');
}

// Load existing STRUCTURE.json
let structure;
try {
  structure = JSON.parse(fs.readFileSync(STRUCTURE_FILE, 'utf-8'));
} catch (e) {
  process.exit(0);
}

if (!structure.modules) {
  structure.modules = {};
}

const files = (process.env.STAGED_FILES || '').split('\n').filter(Boolean);
const deleted = (process.env.DELETED_FILES || '').split('\n').filter(Boolean);

// Remove deleted modules
for (const file of deleted) {
  const key = stripExt(path.relative(SRC_DIR, path.join(ROOT, file)))
    .replace(/\\/g, '/');
  if (structure.modules[key]) {
    delete structure.modules[key];
  }
}

// Parse each staged file
for (const file of files) {
  const fullPath = path.join(ROOT, file);
  if (!fs.existsSync(fullPath)) continue;

  let content;
  try {
    content = fs.readFileSync(fullPath, 'utf-8');
  } catch (e) {
    continue;
  }

  const key = stripExt(path.relative(SRC_DIR, fullPath))
    .replace(/\\/g, '/');

  // Extract description from top JSDoc comment
  let description = '';
  const docMatch = content.match(/^\/\*\*\s*\n\s*\*\s*([^\n]+)/);
  if (docMatch) description = docMatch[1].trim();

  // Extract exports — CJS (module.exports) and ESM (export { ... }, export function)
  const xports = [];
  const cjsMatch = content.match(/module\.exports\s*=\s*\{([^}]+)\}/);
  if (cjsMatch) {
    cjsMatch[1].split(',').forEach(function(s) {
      const name = s.trim().split(':')[0].trim();
      if (name && !name.startsWith('//')) xports.push(name);
    });
  }
  // ESM named exports: export { foo, bar } or export function foo
  const esmExportRe = /^export\s+(?:function|const|let|class|async\s+function)\s+(\w+)/gm;
  let em;
  while ((em = esmExportRe.exec(content)) !== null) {
    if (!xports.includes(em[1])) xports.push(em[1]);
  }

  // Extract dependencies — CJS require() and ESM import
  const deps = [];
  const reqRe = /require\s*\(\s*['"]([^'"]+)['"]\s*\)/g;
  let m;
  while ((m = reqRe.exec(content)) !== null) {
    const dep = m[1];
    if (dep.startsWith('./') || dep.startsWith('../')) {
      deps.push(stripExt(dep.replace(/^\.+\//, '')));
    } else {
      deps.push(dep);
    }
  }
  const importRe = /import\s+.*?from\s+['"]([^'"]+)['"]/g;
  while ((m = importRe.exec(content)) !== null) {
    const dep = m[1];
    if (dep.startsWith('./') || dep.startsWith('../')) {
      deps.push(stripExt(dep.replace(/^\.+\//, '')));
    } else {
      deps.push(dep);
    }
  }

  // Extract function names with line numbers
  const functions = {};
  const fnRe = /^(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\(/gm;
  while ((m = fnRe.exec(content)) !== null) {
    const lineNum = content.substring(0, m.index).split('\n').length;
    functions[m[1]] = { line: lineNum };
  }

  const existing = structure.modules[key] || {};
  structure.modules[key] = {
    file: file,
    description: description || existing.description || '',
    exports: xports,
    depends: deps.filter(function(v, i, a) { return a.indexOf(v) === i; }),
    functions: Object.keys(functions).length > 0 ? functions : (existing.functions || {})
  };
}

// Update timestamp and save
structure.lastUpdated = new Date().toISOString().split('T')[0];
if (structure._frame_metadata) {
  structure._frame_metadata.lastUpdated = structure.lastUpdated;
}
fs.writeFileSync(STRUCTURE_FILE, JSON.stringify(structure, null, 2) + '\n');
