// Extraction coverage: which items of a test set's reference the extracted facts support, before
// any linking (docs/design.md, Checking the quality bar; docs/adr/0089-extraction-coverage.md).
// Free: no LLM, no database; it reads the facts `analyze` writes beside its documents.
//
//   node scripts/coverage.mjs <extracted.jsonl.gz> <set folder> [--out <prefix>]
//     writes <prefix>.coverage.json and <prefix>.coverage.md; the prefix defaults to the facts
//     file's folder and the set's name.
//   node scripts/coverage.mjs check <set folder> [<checkout folder>]
//     checks the set's coverage map: every reference item mapped, every area an extractor, every
//     citation well formed, and with the checkout folder (where the set's setup.sh cloned it),
//     every cited file present and every cited line in it.
//
// The set folder is testsets/<set>: its coverage.json maps each reference item to the facts it
// needs, each an extractor area and a citation `<repo>:<path>[:<start>[-<end>]]`, or marks it
// linking only. A set without a reference (project-0) lists its items in the map itself.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { ELEMENT_CATEGORIES, connectionsOf } from './score.mjs';

/** The extractor areas: the name each static extractor records in its facts' origin. */
export const AREAS = [
  'maven',
  'java',
  'spring',
  'config',
  'sql',
  'persistence',
  'messaging',
  'http-client',
  'gateway',
  'websocket',
  'redis',
  'mail',
  'identity',
  'people',
  'frontend',
  'deployment',
  'test',
  'docs',
];

/** The item categories, in the order a report lists them: the scorecard's, and components. */
export const CATEGORIES = [
  ...ELEMENT_CATEGORIES,
  ['components', 'component'],
  ['connections', null],
];

const CATEGORY_LABELS = {
  containers: 'Containers',
  dataStores: 'Data stores',
  queues: 'Queues and exchanges',
  external: 'External systems',
  people: 'People',
  components: 'Components',
  connections: 'Container connections',
};

const AT = /^([^:\s]+):([^:\s]+)(?::(\d+)(?:-(\d+))?)?$/;

/** A citation `<repo>:<path>[:<start>[-<end>]]` as its parts; a missing range means the file. */
export function parseAt(at) {
  const match = String(at).match(AT);
  if (!match) return null;
  const [, repo, path, start, end] = match;
  if (start === undefined) return { repo, path, start: null, end: null };
  const from = Number(start);
  const to = end === undefined ? from : Number(end);
  if (from < 1 || to < from) return null;
  return { repo, path, start: from, end: to };
}

/** A set's coverage map, or null when it has none. */
export function readCoverageMap(setDir) {
  const file = join(setDir, 'coverage.json');
  return existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : null;
}

/** A set's reference, or null when it has none. */
export function readReference(setDir) {
  const file = join(setDir, 'reference', 'reference.c4.json');
  return existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : null;
}

/**
 * The items coverage is measured on: the reference's elements of the scored kinds and its
 * components, and its container connections (`<from> -> <to>`). A set without a reference names
 * them in its map, each with its `kind` (an element kind, or `connection`) and `name`.
 */
export function coverageItems(reference, map) {
  const items = [];
  if (reference) {
    for (const [category, kind] of CATEGORIES) {
      if (kind === null) continue;
      for (const [id, element] of Object.entries(reference.elements)) {
        if (element.kind === kind) items.push({ key: id, category, name: element.name });
      }
    }
    for (const line of connectionsOf(reference)) {
      const name = (id) => reference.elements[id]?.name ?? id;
      items.push({ key: line.key, category: 'connections', name: `${name(line.from)} → ${name(line.to)}` });
    }
    return items;
  }
  for (const [key, entry] of Object.entries(map?.items ?? {})) {
    const category =
      entry.kind === 'connection'
        ? 'connections'
        : CATEGORIES.find(([, kind]) => kind === entry.kind)?.[0];
    items.push({ key, category: category ?? null, name: entry.name ?? key });
  }
  return items;
}

/** What is wrong with a coverage map: one message each, none when it is sound. */
export function checkMap(items, map) {
  const errors = [];
  const entries = map?.items ?? {};
  const keys = new Set(items.map((item) => item.key));
  for (const item of items) {
    if (!(item.key in entries)) errors.push(`${item.key}: not mapped`);
    if (item.category === null) errors.push(`${item.key}: unknown kind`);
  }
  for (const [key, entry] of Object.entries(entries)) {
    if (!keys.has(key)) errors.push(`${key}: not an item of the reference`);
    const needs = entry.needs ?? [];
    if (needs.length === 0 && !entry.linking) errors.push(`${key}: neither needs nor linking`);
    if (needs.length > 0 && entry.linking) errors.push(`${key}: both needs and linking`);
    for (const need of needs) {
      if (!AREAS.includes(need.area)) errors.push(`${key}: unknown area '${need.area}'`);
      if (!parseAt(need.at)) errors.push(`${key}: bad citation '${need.at}'`);
    }
  }
  return errors;
}

/** What is wrong with the map's citations in the set's checkout: missing files or lines. */
export function checkCitations(map, checkoutDir) {
  const errors = [];
  for (const [key, entry] of Object.entries(map?.items ?? {})) {
    for (const need of entry.needs ?? []) {
      const at = parseAt(need.at);
      if (!at) continue;
      const file = join(checkoutDir, at.repo, at.path);
      if (!existsSync(file)) {
        errors.push(`${key}: ${need.at}: no such file`);
        continue;
      }
      const lines = readFileSync(file, 'utf8').split('\n').length;
      if (at.end !== null && at.end > lines) errors.push(`${key}: ${need.at}: the file has ${lines} lines`);
    }
  }
  return errors;
}

/** The facts of an `extracted.jsonl.gz` file (or a plain `.jsonl`), in the core envelope. */
export function readFacts(path) {
  const bytes = readFileSync(path);
  const text = path.endsWith('.gz') ? gunzipSync(bytes).toString('utf8') : bytes.toString('utf8');
  return text
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => JSON.parse(line));
}

/** Whether a fact's evidence lies in the cited file and, when both have lines, overlaps them. */
function covers(fact, at) {
  const evidence = fact.evidence ?? {};
  if (evidence.repo !== at.repo || evidence.path !== at.path) return false;
  if (at.start === null || !evidence.lines) return true;
  return evidence.lines.start <= at.end && evidence.lines.end >= at.start;
}

/**
 * Which items the facts support. A need is met when a fact of its area has evidence in the cited
 * file overlapping the cited lines (a fact or a citation without lines covers the whole file); an
 * unmet need lists the areas of the facts that are there instead. An
 * item is backed when all its needs are met, partly backed when some are, missing when none are,
 * and linking only when its map entry says no extractor should make a fact for it.
 */
export function coverage(facts, items, map) {
  const byFile = new Map();
  const extractors = {};
  for (const fact of facts) {
    const name = fact.origin?.name ?? '?';
    extractors[name] ??= {};
    extractors[name][fact.predicate] = (extractors[name][fact.predicate] ?? 0) + 1;
    const key = `${fact.evidence?.repo}:${fact.evidence?.path}`;
    if (!byFile.has(key)) byFile.set(key, []);
    byFile.get(key).push(fact);
  }
  // The areas of the facts on a need's cited lines: its own area meets it, another one is a fact
  // of the wrong area, which points at a missing rule rather than a file nobody reads.
  const areasAt = (need) => {
    const at = parseAt(need.at);
    const found = (byFile.get(`${at.repo}:${at.path}`) ?? [])
      .filter((fact) => covers(fact, at))
      .map((fact) => fact.origin?.name ?? '?');
    return [...new Set(found)].sort();
  };

  const results = items.map((item) => {
    const entry = map.items[item.key] ?? {};
    if (entry.linking) return { ...item, status: 'linking', linking: entry.linking, needs: [] };
    const needs = (entry.needs ?? []).map((need) => {
      const found = areasAt(need);
      return found.includes(need.area) ? { ...need, met: true } : { ...need, met: false, otherAreas: found };
    });
    const met = needs.filter((need) => need.met).length;
    const status = met === needs.length ? 'backed' : met === 0 ? 'missing' : 'partial';
    return { ...item, status, needs };
  });

  const categories = {};
  for (const [category] of CATEGORIES) {
    const of = results.filter((r) => r.category === category);
    categories[category] = {
      items: of.length,
      linking: of.filter((r) => r.status === 'linking').length,
      backed: of.filter((r) => r.status === 'backed').length,
      partial: of.filter((r) => r.status === 'partial').length,
      missing: of.filter((r) => r.status === 'missing').length,
    };
  }
  const areas = {};
  for (const area of AREAS) {
    const needs = results.flatMap((r) => r.needs).filter((need) => need.area === area);
    if (needs.length > 0) areas[area] = { needs: needs.length, met: needs.filter((n) => n.met).length };
  }
  return { facts: facts.length, extractors, categories, areas, items: results };
}

const fraction = (a, b) => (b === 0 ? '–' : `${a}/${b} (${Math.round((100 * a) / b)}%)`);

/** The coverage report as Markdown: the totals, then each item not fully backed by facts. */
export function coverageMarkdown(card) {
  const lines = [`# Extraction coverage: ${card.set}`, ''];
  lines.push(`Facts: ${card.facts}${card.source ? ` (${card.source})` : ''}.`, '');
  lines.push('| Items | Backed | Partly | Missing | Linking only |', '| --- | --- | --- | --- | --- |');
  for (const [category] of CATEGORIES) {
    const c = card.categories[category];
    if (c.items === 0) continue;
    const needed = c.items - c.linking;
    lines.push(
      `| ${CATEGORY_LABELS[category]} | ${fraction(c.backed, needed)} | ${c.partial} | ${c.missing} | ${c.linking} |`,
    );
  }
  lines.push('', '| Area | Needs met |', '| --- | --- |');
  for (const [area, a] of Object.entries(card.areas)) lines.push(`| ${area} | ${fraction(a.met, a.needs)} |`);
  lines.push('', '## Not backed', '');
  const unmet = card.items.filter((r) => r.status === 'missing' || r.status === 'partial');
  if (unmet.length === 0) lines.push('None.');
  for (const r of unmet) {
    const missing = r.needs
      .filter((need) => !need.met)
      .map((need) => {
        const others = need.otherAreas.length > 0 ? `, only ${need.otherAreas.join(', ')} there` : ', no fact there';
        return `${need.area} at ${need.at}${others}`;
      });
    lines.push(`- ${CATEGORY_LABELS[r.category]}: **${r.name}** (${r.status}): no ${missing.join('; no ')}`);
  }
  lines.push('', '## Linking only', '');
  const linking = card.items.filter((r) => r.status === 'linking');
  if (linking.length === 0) lines.push('None.');
  for (const r of linking) lines.push(`- ${CATEGORY_LABELS[r.category]}: **${r.name}**: ${r.linking}`);
  lines.push('', '## Facts by extractor and predicate', '', '| Extractor | Predicate | Facts |', '| --- | --- | --- |');
  for (const name of Object.keys(card.extractors).sort()) {
    for (const predicate of Object.keys(card.extractors[name]).sort()) {
      lines.push(`| ${name} | ${predicate} | ${card.extractors[name][predicate]} |`);
    }
  }
  return `${lines.join('\n')}\n`;
}

/** The coverage of a set from a facts file, checked against the set's map first. */
export function coverageFiles(factsPath, setDir) {
  const map = readCoverageMap(setDir);
  if (!map) throw new Error(`${setDir} has no coverage.json`);
  const items = coverageItems(readReference(setDir), map);
  const errors = checkMap(items, map);
  if (errors.length > 0) throw new Error(`${setDir}/coverage.json:\n  ${errors.join('\n  ')}`);
  const card = coverage(readFacts(factsPath), items, map);
  return { set: basename(resolve(setDir)), source: factsPath, ...card };
}

function option(args, name) {
  const i = args.indexOf(name);
  if (i < 0) return null;
  const value = args[i + 1];
  args.splice(i, 2);
  if (value === undefined) throw new Error(`${name} needs a value`);
  return value;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const args = process.argv.slice(2);
  try {
    const out = option(args, '--out');
    if (args[0] === 'check') {
      const [, setDir, checkoutDir] = args;
      if (!setDir) throw new Error('usage: coverage.mjs check <set folder> [<checkout folder>]');
      const map = readCoverageMap(setDir);
      if (!map) throw new Error(`${setDir} has no coverage.json`);
      const items = coverageItems(readReference(setDir), map);
      const errors = [...checkMap(items, map), ...(checkoutDir ? checkCitations(map, checkoutDir) : [])];
      if (errors.length > 0) throw new Error(`${errors.length} problem(s):\n  ${errors.join('\n  ')}`);
      console.log(`${setDir}/coverage.json: ${items.length} items, sound`);
    } else {
      const [factsPath, setDir] = args;
      if (!factsPath || !setDir) {
        throw new Error('usage: coverage.mjs <extracted.jsonl.gz> <set folder> [--out <prefix>]');
      }
      const card = coverageFiles(factsPath, setDir);
      const prefix = out ?? join(dirname(factsPath), card.set);
      writeFileSync(`${prefix}.coverage.json`, `${JSON.stringify(card, null, 2)}\n`);
      writeFileSync(`${prefix}.coverage.md`, coverageMarkdown(card));
      console.log(`wrote ${prefix}.coverage.json and ${prefix}.coverage.md`);
    }
  } catch (e) {
    console.error(`coverage.mjs: ${e.message}`);
    process.exit(1);
  }
}
