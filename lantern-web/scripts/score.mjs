// Scores a Lantern system report against a test set's hand-made reference (docs/design.md, Quality
// bar, "Measured against references"; docs/adr/0072-scoring-harness.md). Both are data for the C4
// report spec (report-specs/c4-model-sequence-diagrams). Free: no LLM, only names and structure.
//
//   node scripts/score.mjs <report .html or .json> <set folder> [--run <run.json>] [--out <prefix>]
//       [--answer-key <file>] [--warm <run.json>] [--change <run.json>] [--labels <folder>]
//     writes <prefix>.scorecard.json and <prefix>.scorecard.md; the prefix defaults to the report's
//     path without .html or .json. It also scores each LLM role (roles.mjs) from the run's facts beside run.json (linked.jsonl.gz,
//     llm.jsonl.gz) against the set's answer key (default <set folder>/answer-key.json), and the wording
//     judge's verdicts (wording.jsonl.gz, wording.mjs) against the owner's labels (default
//     testsets/wording-labels).
//   node scripts/score.mjs compare <a.scorecard.json> <b.scorecard.json> [--labels <a>,<b>]
//       [--out <file.md>]
//     prints (or writes) the two scorecards side by side; the labels head their columns; with the
//     paired difference of each role's item scores.
//   node scripts/score.mjs check-key <answer-key.json> <reference.c4.json> <checkout>
//     checks a set's answer key: its form, and that every citation holds at its commit.
//   node scripts/score.mjs paired <scores folder a> <scores folder b> [--labels <a>,<b>] [--out <file.md>]
//     the paired difference per role over every set both folders scored, with its direction per
//     set and whether it is an improvement (docs/epics/001-knowledge-build.md, section 13).
//
// The set folder is testsets/<set>: its reference/reference.c4.json is the reference and its
// reference/aliases.json, if there is one, the set's alias table.
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { judgedCell, judgedRows, readLabels, readLines } from './wording.mjs';
import { contractLines, qualityRows, readContracts, readQuality, sizeRows } from './contracts.mjs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  SCORED_ROWS,
  answerKeyCodeIssues,
  answerKeyIssues,
  describeRow,
  flowSelectionRow,
  flowsRow,
  harnessRow,
  investigateRow,
  isGenericLabel,
  itemPairs,
  nameGroupsRow,
  notMeasured,
  pairedDifference,
  readAnswerKey,
  readFacts,
  reportId,
  resolveRow,
} from './roles.mjs';

/** Element kinds scored with recall and precision, as the scorecard names them. */
export const ELEMENT_CATEGORIES = [
  ['containers', 'container'],
  ['dataStores', 'database'],
  ['queues', 'queue'],
  ['external', 'external'],
  ['people', 'person'],
];

const CATEGORY_LABELS = {
  containers: 'Containers',
  dataStores: 'Data stores',
  queues: 'Queues and exchanges',
  external: 'External systems',
  people: 'People',
  connections: 'Container connections',
  flows: 'Flows',
  risks: 'Risks',
  decisions: 'Decisions',
  glossary: 'Glossary terms',
};

const HTTP_METHOD = '(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)';
const STOP_WORDS = new Set(
  (
    'about above after again against also because been before being below between both but ' +
    'cannot could does doing down during each from further have having here into itself just ' +
    'more most never only other ought over same should some such than that their theirs them ' +
    'then there these they this those through under until very were what when where which while ' +
    'whom with would your probably every any all and are not the its for can one two who how ' +
    'without within'
  ).split(' '),
);

/** Lower case, with every run of characters other than letters and digits turned into one dash. */
export function normalizeName(name) {
  return String(name ?? '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-|-$/g, '');
}

/**
 * A flow trigger in one spelling: the HTTP method in upper case, the path in lower case with every
 * {placeholder} as {} and no trailing slash or punctuation; anything after the path (such as "[JSON/HTTP]") is
 * dropped. A trigger without a method keeps its first word, such as "/ws-notifications".
 */
export function normalizeTrigger(trigger) {
  const text = String(trigger ?? '').trim();
  const match = text.match(new RegExp(`^(${HTTP_METHOD})\\s+(\\S+)`, 'i'));
  const path = (match ? match[2] : text.split(/\s+/)[0] ?? '')
    .replace(/[.,;:)]+$/, '')
    .replace(/\{[^}]*\}/g, '{}')
    .replace(/\?.*$/, '')
    .replace(/(.)\/$/, '$1')
    .toLowerCase();
  return match ? `${match[1].toUpperCase()} ${path}` : path;
}

/** The words that say what a finding or decision is about: identifiers and longer words. */
export function subjectWords(text) {
  const words = new Set();
  for (const code of String(text ?? '').matchAll(/`([^`]+)`/g)) {
    words.add(code[1].toLowerCase());
  }
  for (const word of String(text ?? '')
    .replace(/`[^`]*`/g, ' ')
    .toLowerCase()
    .split(/[^\p{L}\p{N}_./{}-]+/u)) {
    const trimmed = word.replace(/^[.\-/]+|[.\-/]+$/g, '');
    if (trimmed.length >= 4 && !STOP_WORDS.has(trimmed)) words.add(trimmed);
  }
  return words;
}

/** How much two subjects share: the common words over the smaller set's size, 0 to 1. */
export function subjectOverlap(a, b) {
  if (a.size === 0 || b.size === 0) return 0;
  let common = 0;
  for (const word of a) if (b.has(word)) common++;
  return common / Math.min(a.size, b.size);
}

/** The share of subject words two items must have in common to be the same risk or decision. */
export const SUBJECT_THRESHOLD = 0.5;

/** The report-spec data in an HTML report (its arc-data element) or a JSON file. */
export function readReport(path) {
  const text = readFileSync(path, 'utf8');
  if (!path.endsWith('.html')) return JSON.parse(text);
  const match = text.match(/<script type="application\/json" id="arc-data">([\s\S]*?)<\/script>/);
  if (!match) throw new Error(`${path} holds no report data (no arc-data element)`);
  return JSON.parse(match[1]);
}

/** A set's alias table: candidate names and triggers written the reference's way. */
export function readAliases(setDir) {
  const file = `${setDir}/reference/aliases.json`;
  return existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
}

/** Every diagram section of the data, with the level of its page. */
function diagrams(data) {
  return data.pages.flatMap((page) =>
    page.sections
      .filter((section) => section.type === 'diagram')
      .map((section) => ({ page, section })),
  );
}

/**
 * The readability thresholds the composer splits or folds views by, before layout
 * (docs/design.md, Readable diagrams; ADR 0117); provisional until #366 measures them after layout.
 */
const MAX_ELEMENTS = 50;
const MAX_LINES_PER_ELEMENT = 2;

/** The element ids a diagram shows: those its lines use and the extra ones it lists. */
function diagramElements(section) {
  const ids = new Set(section.elements ?? []);
  for (const r of section.relationships) {
    ids.add(r.from);
    ids.add(r.to);
  }
  return ids;
}

/** The text of a section's rows or items, one string each. */
function rowsOf(section) {
  if (section.type === 'table') return section.rows;
  if (section.type === 'list') return section.items.map((item) => [item]);
  if (section.type === 'decisions') return section.items.map((item) => [item.title, item.body ?? '']);
  return [];
}

/** Sections whose id or title says they hold the given kind of finding. */
function sectionsAbout(data, pattern) {
  return data.pages.flatMap((page) =>
    page.sections.filter(
      (section) =>
        ['table', 'list'].includes(section.type) &&
        (pattern.test(section.id) || pattern.test(section.title ?? '')),
    ),
  );
}

/** The risks a report states: rows of a risks or findings table (its Finding column), or items. */
export function risksOf(data) {
  return sectionsAbout(data, /risk|finding/i).flatMap((section) => {
    const column = (section.columns ?? []).findIndex((c) => /finding|risk/i.test(c));
    return rowsOf(section).map((row) => row[column >= 0 ? column : row.length > 1 ? 1 : 0]);
  });
}

/** The decisions a report states: the items of its decisions sections. */
export function decisionsOf(data) {
  return data.pages.flatMap((page) =>
    page.sections
      .filter((section) => section.type === 'decisions')
      .flatMap((section) => section.items),
  );
}

/** The terms of a report's glossary tables. */
export function glossaryOf(data) {
  return sectionsAbout(data, /glossary/i).flatMap((section) => rowsOf(section).map((row) => row[0]));
}

/**
 * The flows of a report: each sequence section with its title, trigger and participants. The
 * trigger is the request the flow starts with: a step note saying the flow "starts when X receives
 * METHOD /path", or else the code of its first message that has one.
 */
export function flowsOf(data) {
  return data.pages.flatMap((page) =>
    page.sections
      .filter((section) => section.type === 'sequence')
      .map((section) => {
        let trigger = null;
        for (const step of section.steps) {
          if (step.note !== undefined) {
            const match = step.note.match(new RegExp(`receives\\s+(${HTTP_METHOD}\\s+\\S+)`));
            if (match) {
              trigger = match[1];
              break;
            }
          } else if (step.from !== undefined && step.code) {
            trigger = step.code;
            break;
          }
        }
        return {
          title: page.sections.filter((s) => s.type === 'sequence').length > 1
            ? `${page.title}: ${section.title ?? section.id}`
            : page.title,
          trigger: trigger === null ? null : normalizeTrigger(trigger),
          participants: section.participants,
        };
      }),
  );
}

/** A protocol group of a connection, from its ends' kinds and its technology. */
export function protocolOf(fromKind, toKind, technology) {
  const kinds = [fromKind, toKind];
  const tech = String(technology ?? '');
  if (kinds.includes('person')) return 'person';
  if (/smtp/i.test(tech)) return 'email';
  if (kinds.includes('queue') || /amqp|kafka|jms|rabbit/i.test(tech)) return 'messaging';
  if (kinds.includes('database') || /jdbc|redis|sql/i.test(tech)) return 'data store';
  if (/http|feign|rest|websocket|stomp|grpc/i.test(tech)) return 'HTTP';
  return 'other';
}

/**
 * Matches the report's elements with the reference's, by kind and by normalized name, the
 * reference's id, or the alias table's name for it. Returns the reference id of each report
 * element that matches one.
 */
function matchElements(report, reference, aliases) {
  const byName = new Map();
  for (const [id, element] of Object.entries(reference.elements)) {
    byName.set(`${element.kind}:${normalizeName(element.name)}`, id);
    byName.set(`${element.kind}:${normalizeName(id)}`, id);
    // A reference element the alias table allows in other kinds too matches by name there as well.
    for (const kind of aliases.eitherKind?.[id] ?? []) {
      for (const key of [`${kind}:${normalizeName(element.name)}`, `${kind}:${normalizeName(id)}`]) {
        if (!byName.has(key)) byName.set(key, id);
      }
    }
  }
  for (const id of Object.keys(aliases.eitherKind ?? {})) {
    if (!reference.elements[id]) {
      throw new Error(`aliases.json lists eitherKind for unknown reference element ${id}`);
    }
  }
  const elementAliases = new Map(
    Object.entries(aliases.elements ?? {}).map(([name, id]) => [normalizeName(name), id]),
  );
  // RabbitMQ lets a queue and an exchange share a name, and both are of kind queue: where the
  // reference has such a pair, the word "exchange" in the technology tells them apart.
  const exchange = (element) => /\bexchange\b/i.test(element.technology ?? '');
  const sameName = new Map();
  for (const [id, element] of Object.entries(reference.elements)) {
    const key = `${element.kind}:${normalizeName(element.name)}`;
    sameName.set(key, [...(sameName.get(key) ?? []), id]);
  }
  const matched = new Map();
  for (const [id, element] of Object.entries(report.elements)) {
    const name = normalizeName(element.name);
    const pair = sameName.get(`${element.kind}:${name}`) ?? [];
    if (pair.length > 1) {
      const ref = pair.find((r) => exchange(reference.elements[r]) === exchange(element));
      if (ref !== undefined) {
        matched.set(id, ref);
        continue;
      }
    }
    const aliased = elementAliases.get(name);
    if (aliased !== undefined) {
      if (!reference.elements[aliased]) {
        throw new Error(`aliases.json maps "${element.name}" to unknown reference element ${aliased}`);
      }
      const kinds = [reference.elements[aliased].kind, ...(aliases.eitherKind?.[aliased] ?? [])];
      if (kinds.includes(element.kind)) matched.set(id, aliased);
      continue;
    }
    const ref = byName.get(`${element.kind}:${name}`);
    if (ref !== undefined) matched.set(id, ref);
  }
  return matched;
}

/**
 * The report's elements that are about the reference's system: those connected, by a line of any
 * diagram, to an element that matches the reference's, and the components inside them. Precision
 * is over the whole report; for a report that holds another system too (as before #253), the
 * precision of this connected part is given as well, as a secondary figure.
 */
function inScope(report, matched) {
  const neighbours = new Map();
  const link = (a, b) => {
    if (!neighbours.has(a)) neighbours.set(a, new Set());
    neighbours.get(a).add(b);
  };
  for (const { section } of diagrams(report)) {
    for (const r of section.relationships) {
      // The system box links everything it holds; it says nothing about which part is whose.
      if (report.elements[r.from]?.kind === 'system' || report.elements[r.to]?.kind === 'system') {
        continue;
      }
      link(r.from, r.to);
      link(r.to, r.from);
    }
  }
  const scope = new Set();
  const queue = [...matched.keys()];
  while (queue.length > 0) {
    const id = queue.pop();
    if (scope.has(id)) continue;
    scope.add(id);
    for (const next of neighbours.get(id) ?? []) queue.push(next);
  }
  for (const [id, element] of Object.entries(report.elements)) {
    if (element.kind === 'component' && scope.has(element.parent)) scope.add(id);
  }
  return scope;
}

function ratio(numerator, denominator) {
  return denominator === 0 ? null : Math.round((numerator / denominator) * 1000) / 1000;
}

/** Recall and precision of a category, with what is missing and extra, by name. */
/**
 * Recall and precision of a category, with what is missing and extra, by name. Precision is over
 * the whole report, so a wrong item counts wherever it is. When the report also holds items not
 * connected to the reference's system (a report from before #253), `connected` gives the count of
 * the connected part and its precision as a secondary figure.
 */
function category(reference, report, matched, missing, extra, connected) {
  return {
    reference,
    report,
    matched,
    recall: ratio(matched, reference),
    precision: ratio(matched, report),
    ...(connected !== undefined && connected !== report
      ? { reportConnected: connected, precisionConnected: ratio(matched, connected) }
      : {}),
    missing,
    extra,
  };
}

/** Container-level connections, one per ordered pair of elements, with their protocol group. */
function connections(data, toKey) {
  const lines = new Map();
  for (const { page, section } of diagrams(data)) {
    if (page.level !== 'containers') continue;
    for (const r of section.relationships) {
      const key = `${toKey(r.from)} -> ${toKey(r.to)}`;
      if (lines.has(key)) continue;
      lines.set(key, {
        key,
        from: r.from,
        to: r.to,
        protocol: protocolOf(
          data.elements[r.from]?.kind,
          data.elements[r.to]?.kind,
          r.technology,
        ),
      });
    }
  }
  return [...lines.values()];
}

/** A report's container connections, keyed by its own element ids: `<from> -> <to>`. */
export function connectionsOf(data) {
  return connections(data, (id) => id);
}

/** Greedy one-to-one matching of texts by subject, best pairs first. */
function matchSubjects(referenceTexts, reportTexts) {
  const ref = referenceTexts.map(subjectWords);
  const rep = reportTexts.map(subjectWords);
  const pairs = [];
  ref.forEach((a, i) =>
    rep.forEach((b, j) => {
      const overlap = subjectOverlap(a, b);
      if (overlap >= SUBJECT_THRESHOLD) pairs.push({ i, j, overlap });
    }),
  );
  pairs.sort((x, y) => y.overlap - x.overlap || x.i - y.i || x.j - y.j);
  const usedRef = new Set();
  const usedRep = new Set();
  for (const { i, j } of pairs) {
    if (usedRef.has(i) || usedRep.has(j)) continue;
    usedRef.add(i);
    usedRep.add(j);
  }
  return { usedRef, usedRep };
}

/** The first words of a text, for naming an item in a list of missing or extra ones. */
function headline(text) {
  const plain = String(text ?? '')
    .replace(/\*\*/g, '')
    .trim();
  const first = plain.split(/(?<=\.)\s/)[0];
  return first.length > 100 ? `${first.slice(0, 97)}...` : first;
}

/** Items matched by subject; each has the text matched on and the name it is listed by. */
function subjectCategory(referenceItems, reportItems) {
  const { usedRef, usedRep } = matchSubjects(
    referenceItems.map((item) => item.text),
    reportItems.map((item) => item.text),
  );
  return category(
    referenceItems.length,
    reportItems.length,
    usedRef.size,
    referenceItems.filter((_, i) => !usedRef.has(i)).map((item) => item.name),
    reportItems.filter((_, j) => !usedRep.has(j)).map((item) => item.name),
  );
}

const asRisks = (texts) => texts.map((text) => ({ text, name: headline(text) }));
const asDecisions = (items) =>
  items.map((item) => ({ text: `${item.title} ${item.body ?? ''}`, name: headline(item.title) }));

/**
 * The quality-bar checks that can be read from the data alone (docs/design.md, Quality bar). The
 * ones marked (review) there need a reader or the judge (#277) and are not here.
 */
export function qualityChecks(data) {
  const failures = {
    'diagram-size': [],
    'element-descriptions': [],
    technology: [],
    'line-labels': [],
    'line-protocols': [],
    'no-repeated-diagram': [],
  };
  const shown = new Set();
  const seen = new Map();
  for (const { page, section } of diagrams(data)) {
    const where = `${page.title} / ${section.title ?? section.id}`;
    const ids = diagramElements(section);
    ids.forEach((id) => shown.add(id));
    // Readable by the signals measured before layout (docs/design.md, Readable diagrams; ADR 0117).
    // Boundaries, the system and what others are drawn in, are not counted, as the composer does.
    const inside = new Set([...ids].map((id) => data.elements[id]?.parent).filter(Boolean));
    const drawn = [...ids].filter((id) => data.elements[id]?.kind !== 'system' && !inside.has(id));
    if (drawn.length > MAX_ELEMENTS) {
      failures['diagram-size'].push(`${where} (${drawn.length} elements)`);
    } else if (section.relationships.length > MAX_LINES_PER_ELEMENT * drawn.length) {
      failures['diagram-size'].push(`${where} (${section.relationships.length} lines)`);
    }
    for (const r of section.relationships) {
      const label = String(r.label ?? '').trim();
      const name = `${data.elements[r.from]?.name} -> ${data.elements[r.to]?.name}`;
      // Empty, or opening only with a generic verb, such as the fallback "Calls write" (#742).
      if (isGenericLabel(label)) {
        failures['line-labels'].push(`${where}: ${name}`);
      }
      if (page.level === 'containers' && !String(r.technology ?? '').trim()) {
        failures['line-protocols'].push(`${where}: ${name}`);
      }
    }
    const shape = section.relationships
      .map((r) => `${r.from}>${r.to}`)
      .sort()
      .join(',');
    if (shape !== '' && seen.has(shape)) {
      failures['no-repeated-diagram'].push(`${where} repeats ${seen.get(shape)}`);
    } else if (shape !== '') {
      seen.set(shape, where);
    }
  }
  for (const id of shown) {
    const element = data.elements[id];
    if (!element) continue;
    if (!String(element.description ?? '').trim() && element.kind !== 'system') {
      failures['element-descriptions'].push(element.name);
    }
    if (['container', 'component'].includes(element.kind) && !String(element.technology ?? '').trim()) {
      failures.technology.push(element.name);
    }
  }
  // Commits are pinned where the document says what it describes: its subtitle and revisions.
  const metadata = [
    data.document.subtitle ?? '',
    ...(data.document.revisions ?? []).map((r) => r.summary ?? ''),
  ].join('\n');
  const has = (type) => data.pages.some((p) => p.sections.some((s) => s.type === type));
  const checks = [
    ['diagram-size', `Every diagram has at most ${MAX_ELEMENTS} elements and ${MAX_LINES_PER_ELEMENT} lines per element`],
    ['element-descriptions', 'Every element shown has a description'],
    ['technology', 'Every container and component shown has a technology'],
    ['line-labels', 'Every line has an intent phrase, not one that opens with "uses" or "calls"'],
    ['line-protocols', 'Every line between containers names a protocol'],
    ['no-repeated-diagram', 'No diagram repeated across sections'],
  ].map(([id, description]) => ({
    id,
    description,
    pass: failures[id].length === 0,
    failures: [...new Set(failures[id])],
  }));
  const present = (id, description, pass) => checks.push({ id, description, pass, failures: [] });
  present(
    'pins-snapshot',
    'The document pins its commits and date',
    Boolean(data.document.updated) && /\b[0-9a-f]{7,40}\b/.test(metadata),
  );
  present('sequences', 'The main flows are sequence diagrams', has('sequence'));
  present('decisions', 'Observed decisions are stated', decisionsOf(data).length > 0);
  present('risks', 'Risks are stated', risksOf(data).length > 0);
  present('glossary', 'Acronyms are explained in a glossary', glossaryOf(data).length > 0);
  return checks;
}

/** The gaps the report lists in its own quality report sections. */
function selfReportedGaps(data) {
  return data.pages.reduce(
    (count, page) =>
      count +
      page.sections
        .filter((s) => s.type === 'list' && /^quality-report/.test(s.id))
        .reduce((n, s) => n + s.items.length, 0),
    0,
  );
}

/**
 * Scores report data against reference data.
 *
 * @param report the report's data for the C4 report spec
 * @param reference the reference's data for the same spec
 * @param aliases the set's alias table: { elements: { "<report name>": "<reference id>" },
 *     eitherKind: { "<reference id>": ["<other kind it may also be matched in>"] },
 *     triggers: { "<trigger>": "<trigger written the reference's way>" } }
 */
/**
 * @param inputs what the rows per role read beside the report (roles.mjs): `answerKey`, `linked`
 *     and `llm` (the run's facts), `run`, `warm` and `change` (run records); each may be missing
 */
export function score(report, reference, aliases = {}, inputs = {}) {
  const matched = matchElements(report, reference, aliases);
  const scope = inScope(report, matched);
  const refName = (id) => reference.elements[id]?.name ?? id;
  const repName = (id) => report.elements[id]?.name ?? id;

  const result = { elements: {} };
  for (const [name, kind] of ELEMENT_CATEGORIES) {
    const refIds = Object.keys(reference.elements).filter((id) => reference.elements[id].kind === kind);
    // A report element that matches a reference element counts in the reference element's kind.
    const kindOf = (id) =>
      matched.has(id) ? reference.elements[matched.get(id)].kind : report.elements[id].kind;
    const all = Object.keys(report.elements).filter((id) => kindOf(id) === kind);
    // The first report element that matches a reference element counts; later ones are extra.
    const first = new Map();
    const extra = [];
    for (const id of all) {
      if (!matched.has(id)) {
        extra.push(repName(id));
      } else if (first.has(matched.get(id))) {
        extra.push(`${repName(id)} (duplicate of ${repName(first.get(matched.get(id)))})`);
      } else {
        first.set(matched.get(id), id);
      }
    }
    result.elements[name] = category(
      refIds.length,
      all.length,
      first.size,
      refIds.filter((id) => !first.has(id)).map(refName),
      extra,
      all.filter((id) => scope.has(id)).length,
    );
  }

  // Components: counted per container; names rarely agree, so matches are only by name.
  const componentsOf = (data, parent) =>
    Object.entries(data.elements).filter(([, e]) => e.kind === 'component' && e.parent === parent);
  result.components = {
    reference: Object.values(reference.elements).filter((e) => e.kind === 'component').length,
    report: Object.values(report.elements).filter((e) => e.kind === 'component').length,
    reportConnected: Object.entries(report.elements).filter(
      ([id, e]) => e.kind === 'component' && scope.has(id),
    ).length,
    byContainer: Object.entries(reference.elements)
      .filter(([, e]) => e.kind === 'container')
      .map(([refId, container]) => {
        const repContainer = [...matched].find(([, r]) => r === refId)?.[0];
        const refComponents = componentsOf(reference, refId);
        const repComponents = repContainer ? componentsOf(report, repContainer) : [];
        const refNames = new Set(refComponents.map(([, e]) => normalizeName(e.name)));
        return {
          container: container.name,
          reference: refComponents.length,
          report: repComponents.length,
          matchedByName: repComponents.filter(([, e]) => refNames.has(normalizeName(e.name))).length,
        };
      }),
  };

  // Connections between containers (and the stores, queues, people and external systems on
  // container diagrams), one per ordered pair.
  const refLines = connections(reference, (id) => id);
  const repKey = (id) => (matched.has(id) ? matched.get(id) : `report:${id}`);
  const allRepLines = connections(report, repKey);
  const repLines = allRepLines;
  const refKeys = new Set(refLines.map((l) => l.key));
  const repKeys = new Set(repLines.map((l) => l.key));
  const lineName = (line, name) => `${name(line.from)} -> ${name(line.to)}`;
  const matchedLines = repLines.filter((l) => refKeys.has(l.key));
  result.connections = category(
    refLines.length,
    repLines.length,
    matchedLines.length,
    refLines.filter((l) => !repKeys.has(l.key)).map((l) => lineName(l, refName)),
    repLines.filter((l) => !refKeys.has(l.key)).map((l) => lineName(l, repName)),
    repLines.filter((l) => scope.has(l.from) || scope.has(l.to)).length,
  );
  result.connections.byProtocol = {};
  for (const line of refLines) {
    const group = (result.connections.byProtocol[line.protocol] ??= { reference: 0, matched: 0, report: 0 });
    group.reference++;
    if (repKeys.has(line.key)) group.matched++;
  }
  for (const line of repLines) {
    const ref = refLines.find((l) => l.key === line.key);
    const protocol = ref ? ref.protocol : line.protocol;
    (result.connections.byProtocol[protocol] ??= { reference: 0, matched: 0, report: 0 }).report++;
  }

  // Flows: matched by trigger, then compared by participants. The alias table writes a report's
  // trigger the reference's way; the reference's own triggers stay as they are.
  const triggerAliases = new Map(
    Object.entries(aliases.triggers ?? {}).map(([from, to]) => [
      normalizeTrigger(from),
      normalizeTrigger(to),
    ]),
  );
  const canonical = (trigger) => (trigger === null ? null : triggerAliases.get(trigger) ?? trigger);
  const refFlows = flowsOf(reference);
  const repFlows = flowsOf(report).map((f) => ({ ...f, trigger: canonical(f.trigger) }));
  const usedRep = new Set();
  const flowMatches = [];
  for (const ref of refFlows) {
    const j = repFlows.findIndex((f, k) => !usedRep.has(k) && f.trigger !== null && f.trigger === ref.trigger);
    if (j < 0) continue;
    usedRep.add(j);
    const rep = repFlows[j];
    const theirs = new Set(rep.participants.map((p) => matched.get(p)).filter(Boolean));
    const shared = ref.participants.filter((p) => theirs.has(p));
    flowMatches.push({
      reference: ref.title,
      report: rep.title,
      trigger: ref.trigger,
      participants: `${shared.length}/${ref.participants.length}`,
      missingParticipants: ref.participants.filter((p) => !theirs.has(p)).map(refName),
      complete: shared.length === ref.participants.length,
    });
  }
  const matchedRefTitles = new Set(flowMatches.map((m) => m.reference));
  result.flows = category(
    refFlows.length,
    repFlows.length,
    flowMatches.length,
    refFlows.filter((f) => !matchedRefTitles.has(f.title)).map((f) => `${f.title} (${f.trigger ?? 'no trigger'})`),
    repFlows.filter((_, j) => !usedRep.has(j)).map((f) => f.title),
    repFlows.filter((f) => f.participants.some((p) => scope.has(p))).length,
  );
  result.flows.matches = flowMatches;

  result.risks = subjectCategory(asRisks(risksOf(reference)), asRisks(risksOf(report)));
  result.decisions = subjectCategory(
    asDecisions(decisionsOf(reference)),
    asDecisions(decisionsOf(report)),
  );
  const refTerms = glossaryOf(reference);
  const repTerms = new Set(glossaryOf(report).map(normalizeName));
  const refTermSet = new Set(refTerms.map(normalizeName));
  result.glossary = category(
    refTerms.length,
    repTerms.size,
    refTerms.filter((t) => repTerms.has(normalizeName(t))).length,
    refTerms.filter((t) => !repTerms.has(normalizeName(t))),
    glossaryOf(report).filter((t) => !refTermSet.has(normalizeName(t))),
  );

  result.outOfScope = Object.entries(report.elements)
    .filter(([id, e]) => !scope.has(id) && e.kind !== 'system' && e.kind !== 'component')
    .map(([, e]) => e.name);
  result.checks = qualityChecks(report);
  result.referenceChecks = qualityChecks(reference)
    .filter((c) => !c.pass)
    .map((c) => c.id);
  result.selfReportedGaps = selfReportedGaps(report);
  result.roles = roleRows(report, reference, matched, repFlows, inputs, (t) => canonical(normalizeTrigger(t)));
  result.contracts = contractRows(inputs);
  return result;
}

/** Each role's size rules three ways and its quality score (contracts.mjs; ADR 0118). */
function contractRows(inputs) {
  const contracts = readContracts();
  const quality = readQuality();
  return {
    sizes: sizeRows(contracts, inputs.llm ?? null, inputs.rejections ?? null),
    quality: qualityRows(contracts, quality, inputs.wording ?? null),
    tolerancePoints: quality?.tolerancePoints ?? 7,
  };
}

/** The rows per LLM role (roles.mjs; ADR 0072, amendment for #667). */
function roleRows(report, reference, matched, repFlows, inputs, spell = normalizeTrigger) {
  const { answerKey = null, linked = null, llm = null, run = null, warm = null, change = null } = inputs;
  // The wording judge's rows (wording.mjs; ADR 0111): "not validated" until the owner's labels are.
  const judged = judgedRows(inputs.wording ?? null, inputs.labels ?? {});
  const withJudge = (row, judge) => (row.measured === false ? row : { ...row, judge });
  const subjectOf = (entity) => {
    const id = reportId(entity);
    if (matched.has(id)) return matched.get(id);
    return report.elements[id] ? `report:${id}` : null;
  };
  const roleFlows = repFlows.map((f) => ({
    ...f,
    participants: f.participants.map((p) => matched.get(p) ?? `report:${p}`),
  }));
  return {
    resolve: resolveRow(answerKey, linked, llm, run),
    describe: withJudge(describeRow(report, linked, llm, run), judged.describe),
    flows: flowsRow(flowsOf(reference), roleFlows),
    selection: flowSelectionRow(
      flowsOf(reference),
      repFlows.map((f) => f.trigger),
      llm,
      linked,
      spell,
    ),
    investigate: investigateRow(
      answerKey,
      llm,
      subjectOf,
      (a, b) => subjectOverlap(subjectWords(a), subjectWords(b)),
      SUBJECT_THRESHOLD,
    ),
    nameGroups: withJudge(nameGroupsRow(run), judged.nameGroups),
    purpose: judged.purpose,
    harness: harnessRow(run, warm, change),
  };
}

/** "6/7" with the percentage, or "-" when there is nothing to count. */
function fraction(numerator, denominator) {
  if (denominator === 0) return `${numerator}/0`;
  return `${numerator}/${denominator} (${Math.round((numerator / denominator) * 100)}%)`;
}

const nameList = (names) => (names.length === 0 ? 'none' : names.join('; '));

/**
 * A role's conversations with their tool loops (ADR 0081), as run.json counts them: how many, their
 * turns, tool calls by tool and endings; empty for a role without any.
 */
function conversations(c) {
  if (!c) return '';
  const counts = (o) =>
    Object.entries(o ?? {})
      .map(([k, n]) => `${k} ${n}`)
      .join(', ');
  const tools = counts(c.toolCalls);
  return `; ${c.count} conversations, ${c.turns} turns (at most ${c.mostTurns}), tool calls: ${tools || 'none'}; ended: ${counts(c.endings)}`;
}

/** A share as a percentage, or "-" when there was nothing to count. */
const percent = (x) => (x === null || x === undefined ? '-' : `${Math.round(x * 100)}%`);

/** Describe's model coverage as one phrase (#742). */
function describeModelCell(m) {
  if (!m || m.measured === false) return `model: not measured (${m?.why ?? 'no run files'})`;
  return `model: ${m.own}/${m.interactions} interactions with their own intent (${percent(m.coverage)}), ${m.generic} generic, ${m.unknown} don't know, penalized ${percent(m.penalized)} (p = ${m.penalty})${m.notAsked ? `; ${m.notAsked} not asked` : ''}`;
}

/** Each row per role as one line of text: what it measured, or why it could not. */
export function roleCells(roles) {
  if (!roles) return {};
  const why = (r) => `not measured (${r.why})`;
  const cell = {};
  const r = roles.resolve;
  cell.Resolve = !r.measured
    ? why(r)
    : r.items === 0
      ? `no items${r.notAsked ? `; ${r.notAsked} not asked` : ''}${r.note ? ` (${r.note})` : ''}`
      : `${r.items} links: coverage ${percent(r.coverage)}, accuracy ${percent(r.accuracy)}, penalized ${percent(r.penalized)} (p = ${r.penalty}), over-abstention ${percent(r.overAbstention)}; ${r.notAsked} not asked`;
  const d = roles.describe;
  cell.Describe = !d.measured
    ? why(d)
    : `elements ${d.elementsDescribed}/${d.elements} (${percent(d.elementCoverage)}); ${describeModelCell(d.model)}; drawn: ${d.drawn.own}/${d.drawn.lines} lines with their own intent (${percent(d.drawn.coverage)}), ${d.drawn.generic} generic; judge ${judgedCell(d.judge)}`;
  const f = roles.flows;
  cell.Flows = !f.measured
    ? why(f)
    : `${f.matched}/${f.reference} recall (${percent(f.recall)}), ${f.matched}/${f.report} precision (${percent(f.precision)})`;
  const sel = roles.selection;
  if (sel) {
    cell['Flow selection'] = !sel.measured
      ? why(sel)
      : `${sel.mode === 'judged' ? 'judged' : 'rank fallback'}: ${sel.right}/${sel.items} reference flows chosen (${percent(sel.recall)}), ${sel.chosenMatching}/${sel.chosen} chosen flows in the reference (${percent(sel.precision)}), penalized ${percent(sel.penalized)} (p = ${sel.penalty}); ${sel.candidates} candidates, ${sel.notCandidates.length} reference flows not among them`;
  }
  const i = roles.investigate;
  cell.Investigate = !i.measured
    ? why(i)
    : `${i.matched}/${i.reference} recall (${percent(i.recall)}), precision ${percent(i.precision)} over judged, decoys flagged ${i.decoysFlagged}/${i.decoys}, TPR − FPR ${percent(i.tprMinusFpr)}; ${i.unjudged.length} unjudged of ${i.found} found`;
  const n = roles.nameGroups;
  cell['Name groups'] = !n.measured ? why(n) : `${n.named}/${n.groups} named (${percent(n.coverage)}); judge ${judgedCell(n.judge)}`;
  cell.Purpose = `key fact judge ${judgedCell(roles.purpose)}`;
  const h = roles.harness;
  cell.Harness = !h.measured
    ? why(h)
    : `$${h.costUsd}, ${h.calls} calls, tokens in ${h.tokens.input} / out ${h.tokens.output} / cache read ${h.tokens.cacheRead} / cache write ${h.tokens.cacheWrite}; cache hit rate ${percent(h.cacheHitRate)} of ${h.items} items; warm ${
        h.warm.measured === false ? why(h.warm) : `${h.warm.calls} calls${h.warm.gate ? ' (gate passed)' : ' (gate FAILED: not zero)'}`
      }; one-file change ${h.oneFileChange.measured === false ? why(h.oneFileChange) : `${h.oneFileChange.calls} calls`}`;
  return cell;
}

/** The scorecard as Markdown: counts first, then what is missing and extra, then the checks. */
export function scorecardMarkdown(card) {
  const lines = [];
  const s = card.scores;
  lines.push(`# Scorecard: ${card.set}`, '');
  lines.push(`- Report: \`${card.report}\` (${card.reportTitle})`);
  lines.push(`- Reference: \`${card.reference}\``);
  if (card.run) {
    lines.push(`- Run: ${card.run.calls} LLM calls, ${card.run.tokens} tokens, $${card.run.costUsd}`);
    for (const role of card.run.roles) {
      lines.push(
        `  - ${role.role}: ${role.provider}/${role.model} (${role.calls} calls, $${role.costUsd})${conversations(role.conversations)}`,
      );
    }
  } else {
    lines.push('- Run: not recorded (no run.json given)');
  }
  lines.push('', '## Counts', '');
  lines.push(
    '| What | Recall | Precision | Precision, part connected to the reference |',
    '| --- | --- | --- | --- |',
  );
  const row = (key, c) =>
    lines.push(
      `| ${CATEGORY_LABELS[key]} | ${fraction(c.matched, c.reference)} | ${fraction(c.matched, c.report)} | ${
        c.reportConnected === undefined ? 'same' : fraction(c.matched, c.reportConnected)
      } |`,
    );
  for (const [key] of ELEMENT_CATEGORIES) row(key, s.elements[key]);
  for (const key of ['connections', 'flows', 'risks', 'decisions', 'glossary']) row(key, s[key]);
  lines.push('');
  lines.push(
    `Connections by protocol (matched/reference, report): ${Object.entries(s.connections.byProtocol)
      .map(([p, g]) => `${p} ${g.matched}/${g.reference}, ${g.report}`)
      .join('; ')}.`,
  );
  lines.push(
    `Components (report/reference): ${s.components.report}/${s.components.reference}; ${s.components.byContainer
      .map((c) => `${c.container} ${c.report}/${c.reference}`)
      .join(', ')}.`,
  );
  if (s.roles) {
    lines.push('', '## Rows per role', '', '| Role | Result |', '| --- | --- |');
    for (const [role, text] of Object.entries(roleCells(s.roles))) lines.push(`| ${role} | ${text} |`);
    if (card.answerKey) lines.push('', `Answer key: \`${card.answerKey}\` (prepared from the code and the references; see its header).`);
    const inv = s.roles.investigate;
    if (inv.measured && inv.unjudged.length > 0) {
      lines.push('', `Unjudged risks (${inv.unjudged.length}, matching no reference risk or decoy): ${inv.unjudged.join('; ')}.`);
    }
    if (s.roles.resolve.wrongItems?.length > 0) {
      lines.push('', `Resolve's wrong picks: ${s.roles.resolve.wrongItems.join('; ')}.`);
    }
  }
  if (s.flows.matches.length > 0) {
    lines.push('', '## Flows found', '');
    for (const m of s.flows.matches) {
      lines.push(
        `- ${m.reference} = ${m.report}: trigger \`${m.trigger}\`, participants ${m.participants}${
          m.complete ? '' : ` (missing ${m.missingParticipants.join(', ')})`
        }`,
      );
    }
  }
  lines.push('', '## Missing (in the reference, not in the report)', '');
  for (const key of [...ELEMENT_CATEGORIES.map(([k]) => k), 'connections', 'flows', 'risks', 'decisions', 'glossary']) {
    const c = key in s.elements ? s.elements[key] : s[key];
    lines.push(`- ${CATEGORY_LABELS[key]}: ${nameList(c.missing)}`);
  }
  lines.push('', '## Extra (in the report, not in the reference)', '');
  for (const key of [...ELEMENT_CATEGORIES.map(([k]) => k), 'connections', 'flows', 'risks', 'decisions', 'glossary']) {
    const c = key in s.elements ? s.elements[key] : s[key];
    lines.push(`- ${CATEGORY_LABELS[key]}: ${nameList(c.extra)}`);
  }
  if (s.outOfScope.length > 0) {
    lines.push(
      '',
      `Not connected to anything the reference has (counted as extra; left out of the connected part's precision): ${s.outOfScope.join('; ')}.`,
    );
  }
  if (s.contracts) lines.push(...contractLines(s.contracts.sizes, s.contracts.quality, s.contracts.tolerancePoints));
  lines.push('', '## Quality-bar checks', '');
  for (const check of s.checks) {
    const shown = check.failures.slice(0, 5).join('; ');
    const more = check.failures.length > 5 ? ` and ${check.failures.length - 5} more` : '';
    lines.push(`- ${check.pass ? 'pass' : 'FAIL'}: ${check.description}${check.pass ? '' : shown ? ` (${shown}${more})` : ''}`);
  }
  lines.push(
    '',
    `The report lists ${s.selfReportedGaps} quality gaps of its own. Checks the reference itself fails: ${
      s.referenceChecks.length === 0 ? 'none' : s.referenceChecks.join(', ')
    }.`,
  );
  return `${lines.join('\n')}\n`;
}

/**
 * The run record that `analyze` writes next to its documents (run.json): each role's provider,
 * model, calls and cost, and the run's totals. Kept as it is in the scorecard.
 */
export function readRun(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

/** Scores the report file against the set's reference and returns the scorecard. */
/**
 * Scores the report file against the set's reference and returns the scorecard.
 *
 * @param options `answerKey` (default `<set>/answer-key.json`), `warm` and `change` (run records
 *     of a warm rerun and of a one-file change), `labels` (the owner's wording labels, default
 *     testsets/wording-labels); the run's facts (linked.jsonl.gz, llm.jsonl.gz) and the wording
 *     judge's verdicts (wording.jsonl.gz) are read beside run.json, or beside the report without one
 */
export function scoreFiles(reportPath, setDir, runPath = null, options = {}) {
  const referencePath = `${setDir}/reference/reference.c4.json`;
  const report = readReport(reportPath);
  const reference = JSON.parse(readFileSync(referencePath, 'utf8'));
  const beside = dirname(runPath ?? reportPath);
  const keyPath = options.answerKey ?? `${setDir}/answer-key.json`;
  const inputs = {
    answerKey: readAnswerKey(keyPath),
    linked: readFacts(join(beside, 'linked.jsonl.gz')),
    llm: readFacts(join(beside, 'llm.jsonl.gz')),
    run: runPath ? readRun(runPath) : null,
    warm: options.warm ? readRun(options.warm) : null,
    change: options.change ? readRun(options.change) : null,
    wording: readLines(join(beside, 'wording.jsonl.gz')),
    rejections: readLines(join(beside, 'rejections.jsonl.gz')),
    labels: readLabels(options.labels),
  };
  return {
    answerKey: inputs.answerKey ? keyPath : null,
    // A synthetic set's expected answers sit in <checkout>/expected: the set is the checkout.
    set: basename(resolve(setDir)) === 'expected' ? basename(resolve(setDir, '..')) : basename(resolve(setDir)),
    report: reportPath,
    reportTitle: report.document.title,
    reference: referencePath,
    run: runPath ? readRun(runPath) : null,
    scores: score(report, reference, readAliases(setDir), inputs),
  };
}

/** Two scorecards side by side, as Markdown: the models of each role, the cost and every count. */
export function compareMarkdown(a, b, labelA = 'A', labelB = 'B') {
  const sets = a.set === b.set ? a.set : `${a.set} and ${b.set}`;
  const lines = [`# ${sets}: ${labelA} vs ${labelB}`, ''];
  lines.push(`| | ${labelA} | ${labelB} |`, '| --- | --- | --- |');
  const roles = new Set([...(a.run?.roles ?? []), ...(b.run?.roles ?? [])].map((r) => r.role));
  const model = (card, role) => {
    const r = card.run?.roles.find((x) => x.role === role);
    return r ? `${r.provider}/${r.model} (${r.calls} calls, $${r.costUsd})` : '-';
  };
  for (const role of [...roles].sort()) lines.push(`| ${role} | ${model(a, role)} | ${model(b, role)} |`);
  const cost = (card) => (card.run ? `$${card.run.costUsd} (${card.run.calls} calls)` : 'not recorded');
  lines.push(`| Run cost | ${cost(a)} | ${cost(b)} |`);
  const cell = (c) => `${c.matched}/${c.reference} recall, ${c.matched}/${c.report} precision`;
  for (const [key] of ELEMENT_CATEGORIES) {
    lines.push(`| ${CATEGORY_LABELS[key]} | ${cell(a.scores.elements[key])} | ${cell(b.scores.elements[key])} |`);
  }
  for (const key of ['connections', 'flows', 'risks', 'decisions', 'glossary']) {
    lines.push(`| ${CATEGORY_LABELS[key]} | ${cell(a.scores[key])} | ${cell(b.scores[key])} |`);
  }
  const passed = (card) => `${card.scores.checks.filter((c) => c.pass).length}/${card.scores.checks.length}`;
  lines.push(`| Checks passed | ${passed(a)} | ${passed(b)} |`);
  const cellsA = roleCells(a.scores.roles);
  const cellsB = roleCells(b.scores.roles);
  for (const role of new Set([...Object.keys(cellsA), ...Object.keys(cellsB)])) {
    lines.push(`| ${role} (role) | ${cellsA[role] ?? '-'} | ${cellsB[role] ?? '-'} |`);
  }
  const paired = pairedRows([[a, b]]);
  if (paired.length > 0) {
    lines.push('', `## Paired difference (${labelB} − ${labelA}, on the same items)`, '');
    lines.push(...pairedLines(paired));
  }
  return `${lines.join('\n')}\n`;
}

/** Per scored row, the paired difference of item scores over every pair of scorecards given. */
export function pairedRows(cards) {
  const rows = [];
  for (const row of SCORED_ROWS) {
    const pairs = cards.flatMap(([a, b]) => itemPairs(a, b, row));
    if (pairs.length > 0) rows.push({ row, ...pairedDifference(pairs) });
  }
  return rows;
}

/** The paired rows as a Markdown table. */
function pairedLines(rows) {
  const lines = ['| Row | Items | Mean difference | 95% interval | Direction per set | Verdict |', '| --- | --- | --- | --- | --- | --- |'];
  for (const r of rows) {
    const verdict = r.improves ? 'improves' : r.regresses ? 'may regress (lower bound at or below −7 points)' : 'no claim';
    const dirs = Object.entries(r.bySet).map(([set, d]) => `${set} ${d}`).join(', ');
    lines.push(`| ${r.row} | ${r.items} | ${r.mean} points | ${r.low} to ${r.high} | ${dirs} | ${verdict} |`);
  }
  return lines;
}

/**
 * Two folders of scorecards (as score-testsets.sh writes them) compared on the same items, every
 * set they share pooled: the claim the epic's section 13 allows, row by row.
 */
export function pairedMarkdown(dirA, dirB, labelA = 'A', labelB = 'B') {
  const cards = (dir) =>
    new Map(
      readdirSync(dir)
        .filter((f) => f.endsWith('.scorecard.json'))
        .map((f) => [f.replace(/\.scorecard\.json$/, ''), JSON.parse(readFileSync(join(dir, f), 'utf8'))]),
    );
  const a = cards(dirA);
  const b = cards(dirB);
  const sets = [...a.keys()].filter((set) => b.has(set)).sort();
  const lines = [`# Paired comparison: ${labelB} − ${labelA}`, '', `Sets: ${sets.join(', ') || 'none in common'}.`, ''];
  lines.push(...pairedLines(pairedRows(sets.map((set) => [a.get(set), b.get(set)]))));
  return `${lines.join('\n')}\n`;
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
    if (args[0] === 'check-key') {
      const [, keyPath, referencePath, checkout] = args;
      if (!keyPath || !referencePath || !checkout) {
        throw new Error('usage: score.mjs check-key <answer-key.json> <reference.c4.json> <checkout>');
      }
      const key = readAnswerKey(keyPath);
      if (!key) throw new Error(`no answer key at ${keyPath}`);
      const reference = JSON.parse(readFileSync(referencePath, 'utf8'));
      const issues = [...answerKeyIssues(key, reference), ...answerKeyCodeIssues(key, checkout)];
      for (const issue of issues) console.log(issue);
      console.log(
        `${issues.length === 0 ? 'ok' : `${issues.length} issues`}: ${(key.resolve ?? []).length} links, ${(key.risks ?? []).length} risks, ${(key.decoys ?? []).length} decoys`,
      );
      if (issues.length > 0) process.exit(1);
    } else if (args[0] === 'paired') {
      const labels = option(args, '--labels');
      const [, dirA, dirB] = args;
      if (!dirA || !dirB) {
        throw new Error('usage: score.mjs paired <scores folder a> <scores folder b> [--labels <a>,<b>] [--out <file.md>]');
      }
      const [labelA, labelB] = labels ? labels.split(',') : [basename(dirA), basename(dirB)];
      const text = pairedMarkdown(dirA, dirB, labelA, labelB);
      if (out) {
        writeFileSync(out, text);
        console.log(`wrote ${out}`);
      } else {
        process.stdout.write(text);
      }
    } else if (args[0] === 'compare') {
      const labels = option(args, '--labels');
      const [, aPath, bPath] = args;
      if (!aPath || !bPath) {
        throw new Error(
          'usage: score.mjs compare <a.scorecard.json> <b.scorecard.json> [--labels <a>,<b>] [--out <file.md>]',
        );
      }
      const label = (p) => basename(p).replace(/\.scorecard\.json$/, '');
      const [labelA, labelB] = labels ? labels.split(',') : [label(aPath), label(bPath)];
      const text = compareMarkdown(
        JSON.parse(readFileSync(aPath, 'utf8')),
        JSON.parse(readFileSync(bPath, 'utf8')),
        labelA,
        labelB,
      );
      if (out) {
        writeFileSync(out, text);
        console.log(`wrote ${out}`);
      } else {
        process.stdout.write(text);
      }
    } else {
      const run = option(args, '--run');
      const answerKey = option(args, '--answer-key');
      const warm = option(args, '--warm');
      const change = option(args, '--change');
      const labels = option(args, '--labels');
      const [reportPath, setDir] = args;
      if (!reportPath || !setDir) {
        throw new Error(
          'usage: score.mjs <report .html|.json> <set folder> [--run <run.json>] [--answer-key <file>] [--warm <run.json>] [--change <run.json>] [--labels <folder>] [--out <prefix>]',
        );
      }
      const card = scoreFiles(reportPath, setDir, run, {
        answerKey: answerKey ?? undefined,
        warm,
        change,
        labels: labels ?? undefined,
      });
      const prefix = out ?? reportPath.replace(/\.(html|json)$/, '');
      writeFileSync(`${prefix}.scorecard.json`, `${JSON.stringify(card, null, 2)}\n`);
      writeFileSync(`${prefix}.scorecard.md`, scorecardMarkdown(card));
      console.log(`wrote ${prefix}.scorecard.json and ${prefix}.scorecard.md`);
    }
  } catch (e) {
    console.error(`score.mjs: ${e.message}`);
    process.exit(1);
  }
}
