// Usage: node scripts/generate-emoji-catalog.mjs work/emoji-test-17.0.txt
// Source: https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const source = 'https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt';
const sourceSha256 = '1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda';
const bytes = readFileSync(process.argv[2]);
assert.equal(createHash('sha256').update(bytes).digest('hex'), sourceSha256);
const text = bytes.toString('utf8');
assert.match(text, /^# Version: 17\.0$/m);
const categories = [];
const entries = [];
let group;
for (const line of text.split(/\r?\n/)) {
  const heading = line.match(/^# group: (.+)$/);
  if (heading) {
    group = heading[1] === 'Component' ? null : { name: heading[1], emojis: [] };
    if (group) categories.push(group);
    continue;
  }
  const entry = line.match(/^([0-9A-F ]+);\s*fully-qualified\s*#\s*\S+\s+E[\d.]+\s+(.+)$/u);
  if (!group || !entry) continue;
  const points = entry[1].trim().split(/\s+/).map(hex => parseInt(hex, 16));
  const emoji = String.fromCodePoint(...points);
  const toned = points.some(point => point >= 0x1F3FB && point <= 0x1F3FF);
  entries.push({ emoji, name: entry[2].split(':')[0], toned });
  if (!toned) group.emojis.push(emoji);
}
const key = emoji => [...emoji].filter(char => {
  const point = char.codePointAt(0);
  return point !== 0xFE0F && !(point >= 0x1F3FB && point <= 0x1F3FF);
}).join('');
const canonical = new Map(entries.filter(entry => !entry.toned).map(entry => [key(entry.emoji), entry.emoji]));
// Legacy neutral kiss/couple emoji precede the gendered variants sharing a name.
const yellowByName = new Map();
for (const entry of entries) {
  if (!entry.toned && !yellowByName.has(entry.name)) yellowByName.set(entry.name, entry.emoji);
}
const defaultYellowAliases = {};
for (const entry of entries) {
  if (canonical.has(key(entry.emoji))) continue;
  const target = yellowByName.get(entry.name);
  assert.ok(target, `Missing yellow base for ${entry.name}`);
  defaultYellowAliases[key(entry.emoji)] = target;
}
assert.equal(categories.length, 9);
assert.equal(categories.reduce((count, category) => count + category.emojis.length, 0), 1914);
assert.equal(Object.keys(defaultYellowAliases).length, 12);
const catalog = {
  unicodeVersion: '17.0', source, sourceSha256,
  copyright: '© 2025 Unicode®, Inc.',
  license: 'https://www.unicode.org/license.txt',
  selection: 'fully-qualified; excluding Component group and U+1F3FB..U+1F3FF skin-tone modifiers; Unicode CLDR order',
  defaultYellowAliases, categories,
};
writeFileSync(new URL('../app/src/main/resources/emoji-reactions.json', import.meta.url),
  (JSON.stringify(catalog, null, 2) + '\n').replace(/\n/g, '\r\n'));
console.log(`Unicode 17.0: 1914 emoji, ${Object.keys(defaultYellowAliases).length} yellow aliases; source SHA-256 verified`);
