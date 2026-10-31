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
//     every cited file present, every cited line in it, and each citation's first line as its
//     hash in the map records it.
//   node scripts/coverage.mjs hash <set folder> <checkout folder>
//     writes each line citation's hash into the set's map, for a citation added or moved on
//     purpose; check then flags any that drift.
//
// The set folder is testsets/<set>: its coverage.json maps each reference item to the facts it
// needs, each an extractor area and a citation `<repo>:<path>[:<start>[-<end>]]`, or marks it
// linking only. A set without a reference (project-0) lists its items in the map itself.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { ELEMENT_CATEGORIES, connectionsOf } from './score.mjs';

/**
 * The extractor area of a fact: the name its extractor records in its origin; for a fact of
 * linking's first step, which resolves the values extractors keep as written into the facts they
 * recorded before (ADR 0094), the area of the extractor whose kind of fact it is.
 */
const VALUE_AREAS = {
  'http-call': 'http-client',
  'gateway-route': 'gateway',
  'gateway-discovery-locator': 'gateway',
  'message-send': 'messaging',
  'message-listener': 'messaging',
  'message-binding': 'messaging',
  'message-destination': 'messaging',
  'stomp-send': 'websocket',
  datasource: 'persistence',
  'data-store': 'persistence',
  HAS_DATASOURCE: 'persistence',
  CONNECTS_TO: 'persistence',
  MAPS_TO: 'persistence',
  'redis-use': 'redis',
};

export function areaOf(fact) {
  const name = fact.origin?.name ?? '?';
  return name === 'values' ? (VALUE_AREAS[fact.predicate] ?? name) : name;
}

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
      if (need.hash !== undefined && !/^[0-9a-f]{16}$/.test(need.hash)) {
        errors.push(`${key}: bad hash '${need.hash}' for ${need.at}`);
      }
    }
  }
  return errors;
}

/** The services of a compose file: each one's first and last line, under the `services:` key. */
export function composeServices(text) {
  const lines = text.split('\n');
  const top = lines.findIndex((line) => /^services:\s*$/.test(line));
  if (top < 0) return [];
  const services = [];
  let indent = null;
  for (let i = top + 1; i < lines.length; i++) {
    const line = lines[i];
    // A blank line or a comment belongs to the service above it.
    if (line.trim() === '' || line.trimStart().startsWith('#')) {
      if (services.length > 0 && i < lines.length - 1) services.at(-1).end = i + 1;
      continue;
    }
    if (!line.startsWith(' ')) break;
    const depth = line.length - line.trimStart().length;
    indent ??= depth;
    if (depth === indent && line.trimEnd().endsWith(':')) services.push({ start: i + 1, end: i + 1 });
    else if (services.length > 0) services.at(-1).end = i + 1;
  }
  return services;
}

/**
 * The hash of a citation's first line, as GitHub's `primaryLocationLineHash` takes it: the line's
 * first 100 characters without whitespace (ADR 0089, amendment for #410), here the start of their
 * SHA-256.
 */
export function lineHash(line) {
  const text = line.replace(/\s+/g, '').slice(0, 100);
  return createHash('sha256').update(text).digest('hex').slice(0, 16);
}

/** The cited line's text in the checkout, or null when the file or the line is not there. */
function citedLine(checkoutDir, at) {
  const file = join(checkoutDir, at.repo, at.path);
  if (at.start === null || !existsSync(file)) return null;
  return readFileSync(file, 'utf8').split('\n')[at.start - 1] ?? null;
}

/**
 * What is wrong with the map's citations in the set's checkout: missing files or lines, a line
 * citation whose first line is blank or no longer has the hash the map records (or has none), a
 * hash on a whole-file citation, and a compose
 * citation that is not inside one service from its first line (ADR 0089), since a range across
 * services is met by any of their facts.
 */
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
      const text = readFileSync(file, 'utf8');
      const lines = text.replace(/\n$/, '').split('\n').length;
      if (at.end !== null && at.end > lines) errors.push(`${key}: ${need.at}: the file has ${lines} lines`);
      if (at.start === null && need.hash !== undefined) errors.push(`${key}: ${need.at}: a hash without a line`);
      if (at.start !== null && at.start <= lines) {
        const line = text.split('\n')[at.start - 1];
        const hash = lineHash(line);
        // A blank first line hashes the same whatever replaces it, so it could not show drift.
        if (line.trim() === '') errors.push(`${key}: ${need.at}: the first cited line is blank`);
        else if (need.hash === undefined) errors.push(`${key}: ${need.at}: no hash (coverage.mjs hash)`);
        else if (need.hash !== hash) errors.push(`${key}: ${need.at}: the line changed since it was cited`);
      }
      if (at.start !== null && /compose[^/]*\.ya?ml$/.test(at.path)) {
        const service = composeServices(text).find((s) => s.start <= at.start && at.start <= s.end);
        if (!service || service.start !== at.start || at.end > service.end) {
          errors.push(`${key}: ${need.at}: not one compose service from its first line`);
        }
      }
    }
  }
  return errors;
}

/** The map with each line citation's hash set from the checkout; whole-file citations get none. */
export function withHashes(map, checkoutDir) {
  const items = {};
  for (const [key, entry] of Object.entries(map.items ?? {})) {
    if (!entry.needs) {
      items[key] = entry;
      continue;
    }
    const needs = entry.needs.map((need) => {
      const at = parseAt(need.at);
      const line = at ? citedLine(checkoutDir, at) : null;
      const { hash, ...rest } = need;
      return line === null ? rest : { ...rest, hash: lineHash(line) };
    });
    items[key] = { ...entry, needs };
  }
  return { ...map, items };
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

/** A fact's evidence locations: a list since ADR 0091, a single object in older files. */
function locations(fact) {
  const evidence = fact.evidence ?? [];
  return Array.isArray(evidence) ? evidence : [evidence];
}

/** Whether an evidence location lies in the cited file and, when both have lines, overlaps them. */
function covers(location, at) {
  if (location.repo !== at.repo || location.path !== at.path) return false;
  if (at.start === null || !location.lines) return true;
  return location.lines.start <= at.end && location.lines.end >= at.start;
}

/** A path's kind of file, for which areas record lines: its extension, else its name. */
function fileKind(path) {
  const name = path.slice(path.lastIndexOf('/') + 1);
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(dot) : name;
}

/**
 * Which items the facts support. A need is met when a fact of its area has evidence in the cited
 * file overlapping the cited lines; an unmet need lists the areas of the facts that are there
 * instead. A need met only by a fact without lines, or cited without lines, is weak (ADR 0089,
 * amendment for #410): it is reported apart, and it does not count as met where the area records
 * lines for that kind of file elsewhere in the facts. An item is backed when all its needs are
 * met (weakly backed when one of them is weak), partly backed when some are, missing when none
 * are, and linking only when its map entry says no extractor should make a fact for it.
 */
export function coverage(facts, items, map) {
  const byFile = new Map();
  const extractors = {};
  // The kinds of file each area records lines for, `<area> <kind>`.
  const withLines = new Set();
  for (const fact of facts) {
    const name = areaOf(fact);
    extractors[name] ??= {};
    extractors[name][fact.predicate] = (extractors[name][fact.predicate] ?? 0) + 1;
    for (const location of locations(fact)) {
      if (location.lines && location.path) withLines.add(`${name} ${fileKind(location.path)}`);
      const key = `${location.repo}:${location.path}`;
      if (!byFile.has(key)) byFile.set(key, []);
      byFile.get(key).push({ fact, location });
    }
  }
  // The areas of the facts on a need's cited lines: its own area meets it, another one is a fact
  // of the wrong area, which points at a missing rule rather than a file nobody reads.
  const factsAt = (at) => (byFile.get(`${at.repo}:${at.path}`) ?? []).filter((seen) => covers(seen.location, at));

  const results = items.map((item) => {
    const entry = map.items[item.key] ?? {};
    if (entry.linking) return { ...item, status: 'linking', linking: entry.linking, needs: [] };
    const needs = (entry.needs ?? []).map((need) => {
      const at = parseAt(need.at);
      const found = factsAt(at);
      const own = found.filter((seen) => areaOf(seen.fact) === need.area);
      const { hash, ...shown } = need;
      if (own.length === 0) {
        const otherAreas = [...new Set(found.map((seen) => areaOf(seen.fact)))].sort();
        return { ...shown, met: false, otherAreas };
      }
      if (at.start !== null && own.some((seen) => seen.location.lines)) return { ...shown, met: true };
      // Weak: no lines on one side. It counts only where the area records no lines for the file.
      const counts = !withLines.has(`${need.area} ${fileKind(at.path)}`);
      return { ...shown, met: counts, weak: true };
    });
    const met = needs.filter((need) => need.met).length;
    const weak = needs.some((need) => need.weak && need.met);
    const status =
      met === needs.length ? (weak ? 'weak' : 'backed') : met === 0 ? 'missing' : 'partial';
    return { ...item, status, needs };
  });

  const categories = {};
  for (const [category] of CATEGORIES) {
    const of = results.filter((r) => r.category === category);
    categories[category] = {
      items: of.length,
      linking: of.filter((r) => r.status === 'linking').length,
      backed: of.filter((r) => r.status === 'backed').length,
      weak: of.filter((r) => r.status === 'weak').length,
      partial: of.filter((r) => r.status === 'partial').length,
      missing: of.filter((r) => r.status === 'missing').length,
    };
  }
  const areas = {};
  for (const area of AREAS) {
    const needs = results.flatMap((r) => r.needs).filter((need) => need.area === area);
    if (needs.length > 0) {
      areas[area] = {
        needs: needs.length,
        met: needs.filter((n) => n.met && !n.weak).length,
        weak: needs.filter((n) => n.weak).length,
      };
    }
  }
  return { facts: facts.length, extractors, categories, areas, items: results };
}

const fraction = (a, b) => (b === 0 ? '–' : `${a}/${b} (${Math.round((100 * a) / b)}%)`);

/** The coverage report as Markdown: the totals, then each item not fully backed by facts. */
export function coverageMarkdown(card) {
  const lines = [`# Extraction coverage: ${card.set}`, ''];
  lines.push(`Facts: ${card.facts}${card.source ? ` (${card.source})` : ''}.`, '');
  lines.push(
    '| Items | Backed | Weakly | Partly | Missing | Linking only |',
    '| --- | --- | --- | --- | --- | --- |',
  );
  for (const [category] of CATEGORIES) {
    const c = card.categories[category];
    if (c.items === 0) continue;
    const needed = c.items - c.linking;
    lines.push(
      `| ${CATEGORY_LABELS[category]} | ${fraction(c.backed, needed)} | ${c.weak} | ${c.partial} | ${c.missing} | ${c.linking} |`,
    );
  }
  lines.push('', '| Area | Needs met | Weak |', '| --- | --- | --- |');
  for (const [area, a] of Object.entries(card.areas)) {
    lines.push(`| ${area} | ${fraction(a.met, a.needs)} | ${a.weak} |`);
  }
  lines.push('', '## Weak', '');
  const weak = card.items.flatMap((r) => r.needs.filter((need) => need.weak).map((need) => [r, need]));
  if (weak.length === 0) lines.push('None.');
  for (const [r, need] of weak) {
    const why = parseAt(need.at).start === null ? 'cited without lines' : 'only facts without lines';
    const counts = need.met ? 'counted' : `not counted: ${need.area} records lines for such files`;
    lines.push(`- ${CATEGORY_LABELS[r.category]}: **${r.name}**: ${need.area} at ${need.at}, ${why} (${counts})`);
  }
  lines.push('', '## Not backed', '');
  const unmet = card.items.filter((r) => r.status === 'missing' || r.status === 'partial');
  if (unmet.length === 0) lines.push('None.');
  for (const r of unmet) {
    const missing = r.needs
      .filter((need) => !need.met)
      .map((need) => {
        if (need.weak) return `${need.area} at ${need.at} with lines`;
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
    if (args[0] === 'hash') {
      const [, setDir, checkoutDir] = args;
      if (!setDir || !checkoutDir) throw new Error('usage: coverage.mjs hash <set folder> <checkout folder>');
      const map = readCoverageMap(setDir);
      if (!map) throw new Error(`${setDir} has no coverage.json`);
      writeFileSync(join(setDir, 'coverage.json'), `${JSON.stringify(withHashes(map, checkoutDir), null, 2)}\n`);
      console.log(`${setDir}/coverage.json: hashes written`);
    } else if (args[0] === 'check') {
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
    process.exitCode = 1;
  }
}
