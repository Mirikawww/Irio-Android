import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Tokenize Kotlin without mistaking strings, comments or nested argument colors for Text.color.
export function tokens(source) {
  const result = [];
  const re = /\/\*[\s\S]*?\*\/|\/\/[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z_$][\w$]*|\S/g;
  for (const match of source.matchAll(re)) {
    if (match[0].startsWith('//') || match[0].startsWith('/*')) continue;
    result.push({ value: match[0], offset: match.index });
  }
  return result;
}

export function auditSource(source, file = '<source>') {
  const imported = new Map();
  const importPattern = /import\s+(?:com\.composables\.ui\.components|com\.composeunstyled|androidx\.compose\.material3|androidx\.compose\.foundation\.text)\.(Text|BasicText|ClickableText|\*)(?:[ \t]+as[ \t]+(\w+))?/g;
  for (const match of source.matchAll(importPattern)) {
    if (match[1] === '*') ['Text', 'BasicText', 'ClickableText'].forEach(name => imported.set(name, name));
    else imported.set(match[2] ?? match[1], match[1]);
  }
  const ts = tokens(source);
  const pairs = new Map();
  const stack = [];
  for (let i = 0; i < ts.length; i++) {
    if (['(', '[', '{'].includes(ts[i].value)) stack.push(i);
    else if ([')', ']', '}'].includes(ts[i].value)) { const open = stack.pop(); pairs.set(open, i); }
  }
  const missing = [];
  let calls = 0;
  for (let i = 0; i < ts.length; i++) {
    const qualified = ts.slice(Math.max(0, i - 13), i).map(t => t.value).join('');
    const isQualifiedUi = /(?:com\.composables\.ui\.components|com\.composeunstyled|androidx\.compose\.material3|androidx\.compose\.foundation\.text)\.$/.test(qualified);
    const kind = imported.get(ts[i].value) ?? (isQualifiedUi ? ts[i].value : null);
    if (!['Text', 'BasicText', 'ClickableText'].includes(kind) || ts[i + 1]?.value !== '(') continue;
    if (ts[i - 1]?.value === '.' && !isQualifiedUi) continue; // Domain constructors, not UI.
    const end = pairs.get(i + 1);
    if (end === undefined) continue;
    calls++;
    let color = false;
    for (let j = i + 2; j < end; j++) {
      if (kind !== 'Text' && ts[j].value === 'style' && ts[j + 1]?.value === '=') {
        // BasicText takes its foreground from TextStyle, not a color parameter.
        let styleEnd = j + 2;
        while (styleEnd < end && ts[styleEnd].value !== ',') {
          if (['(', '[', '{'].includes(ts[styleEnd].value)) {
            const close = pairs.get(styleEnd);
            if (ts.slice(styleEnd, close).some((token, k, part) => token.value === 'color' && part[k + 1]?.value === '=')) color = true;
            styleEnd = close;
          }
          styleEnd++;
        }
      }
      if (['(', '[', '{'].includes(ts[j].value)) j = pairs.get(j);
      else if (ts[j].value === 'color' && ts[j + 1]?.value === '=') color = true;
    }
    if (color) continue;
    const line = source.slice(0, ts[i].offset).split('\n').length;
    const preview = source.slice(ts[i].offset, ts[end].offset + 1).replace(/\s+/g, ' ').slice(0, 180);
    missing.push({ file, line, kind, preview });
  }
  return { missing, calls };
}

function files(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? files(file) : file.endsWith('.kt') ? [file] : [];
  });
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const roots = ['app/src/main', 'app/src/debug', 'app/src/release', 'markdown-renderer-code/src/main'];
  const sources = roots.flatMap(dir => fs.existsSync(path.join(root, dir)) ? files(path.join(root, dir)) : []);
  const results = sources.map(file => auditSource(fs.readFileSync(file, 'utf8'), path.relative(root, file)));
  const all = results.flatMap(r => r.missing);
  for (const entry of all) console.log(`${entry.file}:${entry.line} ${entry.kind} ${entry.preview}`);
  console.log(`Scanned ${sources.length} Kotlin files, ${results.reduce((sum, r) => sum + r.calls, 0)} UI text calls; missing explicit foreground: ${all.length}`);
  // This checks source-level foreground ownership, not every rendered theme/contrast combination.
  if (process.argv.includes('--check') && all.length) process.exitCode = 1;
}
