// Scores a Lantern system report against a test set's hand-made reference (docs/design.md, Quality
// bar, "Measured against references"; docs/adr/0072-scoring-harness.md). Both are data for the C4
// report spec (report-specs/c4-model-sequence-diagrams). Free: no LLM, only names and structure.
//
//   node scripts/score.mjs <report .html or .json> <set folder> [--run <run.json>] [--out <prefix>]
//       [--answer-key <file>] [--judgements <file>] [--warm <run.json>] [--change <run.json>] [--labels <folder>]
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
  connectionsRow,
  fixedCheckRow,
  investigateRow,
  investigateSpread,
  repeatedRisks,
  isGenericLabel,
  unusedVerdicts,
  itemPairs,
  nameGroupsRow,
  notMeasured,
  pairedDifference,
  readAnswerKey,
  readFacts,
  readJudgements,
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
 * The diagram limits a report was composed with, its `document.readable`, which Lantern writes from
 * its one setting (#836, docs/design.md, Readable diagrams): the most elements a view shows, the
 * target plus its tolerance, and the most lines per element. Null for a report without them, whose
 * diagram sizes are then not judged.
 */
export function diagramLimits(data) {
  const r = data.document?.readable;
  if (!r || !Number.isInteger(r.target) || !Number.isInteger(r.tolerance) || !Number.isInteger(r.linesPerElement)) {
    return null;
  }
  return { elements: r.target + r.tolerance, linesPerElement: r.linesPerElement };
}

/** The element ids a diagram shows: those its lines use and the extra ones it lists. */
function diagramElements(section) {
  const ids = new Set(section.elements ?? []);
  for (const r of section.relationships) {
    ids.add(r.from);
    ids.add(r.to);
  }
  return ids;
}

/**
 * The text of a section's rows or items, one string each. A table's parent row stands in for the
 * rows folded under it, so each folded risk counts and the parent does not (#747).
 */
function rowsOf(section) {
  if (section.type === 'table') {
    const folded = new Map((section.subRows ?? []).map((g) => [g.row, g.rows]));
    return section.rows.flatMap((row, i) => folded.get(i) ?? [row]);
  }
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

/** What the Unknown caller is named in a report (#766). */
const UNKNOWN_CALLER = 'Unknown caller';

/** The quality check the composer fails when an Unknown caller line cites a reached entry point. */
const UNKNOWN_CALLER_CHECK = 'Every Unknown caller line points to entry points nothing in the set reaches';

/**
 * The Unknown caller's lines a report's diagrams draw (#766), once per element they reach, with
 * the entry points the line names; and whether the report fails its check of them.
 */
export function unknownCallersOf(data) {
  const name = (id) => data.elements?.[id]?.name ?? id;
  const lines = new Map();
  for (const { section } of diagrams(data)) {
    for (const r of section.relationships ?? []) {
      if (name(r.from) !== UNKNOWN_CALLER || data.elements?.[r.to]?.kind === 'system') continue;
      const to = name(r.to);
      const detail = r.description ?? r.label ?? '';
      if (!lines.has(to) || (!lines.get(to) && detail)) lines.set(to, detail);
    }
  }
  const failed = data.pages.some((page) =>
    page.sections.some(
      (s) => s.type === 'list' && /^quality-report/.test(s.id) && s.items.some((i) => String(i).includes(UNKNOWN_CALLER_CHECK)),
    ),
  );
  return {
    lines: [...lines.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([to, detail]) => ({ to, detail })),
    pass: !failed,
  };
}

/** The report without the Unknown caller and its lines, for scoring (#766). */
function withoutUnknownCaller(data) {
  const ids = new Set(
    Object.entries(data.elements ?? {})
      .filter(([, e]) => e.name === UNKNOWN_CALLER)
      .map(([id]) => id),
  );
  if (ids.size === 0) return data;
  const elements = Object.fromEntries(Object.entries(data.elements).filter(([id]) => !ids.has(id)));
  const pages = data.pages.map((page) => ({
    ...page,
    sections: page.sections.map((section) =>
      section.type !== 'diagram'
        ? section
        : {
            ...section,
            elements: (section.elements ?? []).filter((id) => !ids.has(id)),
            relationships: section.relationships.filter((r) => !ids.has(r.from) && !ids.has(r.to)),
          },
    ),
  }));
  return { ...data, elements, pages };
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
    if (element.tag === FEATURE_AREA_TAG) continue; // a feature area is no component (#549)
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
    if (element.tag === FEATURE_AREA_TAG) continue;
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

/** The tag a reference gives a unit's feature area drawn as a component box (#548). */
export const FEATURE_AREA_TAG = 'Feature area';

/** Whether an element is a component, not a feature area drawn as one (#549). */
export const isComponent = (element) => element.kind === 'component' && element.tag !== FEATURE_AREA_TAG;

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
 *
 * @param limits the diagram limits to judge sizes by; the data's own by default. A reference,
 *     which carries none, is judged by those of the report scored against it (#836).
 */
export function qualityChecks(data, limits = diagramLimits(data)) {
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
    if (!limits) {
      // not judged without the report's limits
    } else if (drawn.length > limits.elements) {
      failures['diagram-size'].push(`${where} (${drawn.length} elements)`);
    } else if (section.relationships.length > limits.linesPerElement * drawn.length) {
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
    ...(limits
      ? [['diagram-size', `Every diagram has at most ${limits.elements} elements and ${limits.linesPerElement} lines per element`]]
      : []),
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
export function score(original, reference, aliases = {}, inputs = {}) {
  // The Unknown caller marks what the code cannot tell; it is listed apart, not scored (#766).
  const report = withoutUnknownCaller(original);
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

  // Components: counted per container; names rarely agree, so matches are only by name. A unit's
  // feature areas, which a reference draws as component boxes tagged "Feature area" for want of a
  // group element, are groups, not components (#549).
  const componentsOf = (data, parent) =>
    Object.entries(data.elements).filter(([, e]) => isComponent(e) && e.parent === parent);
  result.components = {
    reference: Object.values(reference.elements).filter(isComponent).length,
    report: Object.values(report.elements).filter(isComponent).length,
    reportConnected: Object.entries(report.elements).filter(([id, e]) => isComponent(e) && scope.has(id)).length,
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
  result.referenceChecks = qualityChecks(reference, diagramLimits(report))
    .filter((c) => !c.pass)
    .map((c) => c.id);
  result.selfReportedGaps = selfReportedGaps(report);
  result.llmMarks = llmMarks(report, infrastructureList());
  result.unknownCallers = unknownCallersOf(original);
  result.roles = roleRows(report, reference, matched, repFlows, inputs, (t) => canonical(normalizeTrigger(t)));
  result.contracts = contractRows(inputs);
  return result;
}

/** Whether a text opens with "Found by the LLM:", plain or emphasized. */
const FOUND_PREFIX = /^\*?Found by the LLM:\*?/;

/**
 * How the report marks what the LLM found (#749): the focus system boxes that carry the LLM badge
 * (none should), how many elements and lines carry it, which of them (and which marked table rows)
 * still open with "Found by the LLM:" where the badge already says it, and the marked lines that go
 * to infrastructure the Investigate contract names (tracing, logging, metrics, registries, config
 * servers), which Investigate must not propose.
 *
 * @param infrastructure the contract's list, lower case
 */
export function llmMarks(data, infrastructure) {
  const elements = data.elements ?? {};
  const named = (text) =>
    infrastructure.find((word) =>
      new RegExp(`(?<![a-z0-9])${word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![a-z0-9])`).test(text.toLowerCase()),
    );
  const prefixed = [];
  const infrastructureLines = [];
  let lines = 0;
  for (const [, e] of Object.entries(elements)) {
    if (e.llm && FOUND_PREFIX.test(e.description ?? '')) prefixed.push(`element ${e.name}`);
  }
  for (const page of data.pages ?? []) {
    for (const section of page.sections ?? []) {
      for (const r of section.relationships ?? []) {
        if (!r.llm) continue;
        lines++;
        const line = `${elements[r.from]?.name ?? r.from} -> ${elements[r.to]?.name ?? r.to}`;
        if (FOUND_PREFIX.test(r.label ?? '')) prefixed.push(`line ${line}`);
        const word = named(`${r.to} ${elements[r.to]?.name ?? ''} ${r.label ?? ''}`);
        if (word) infrastructureLines.push(`${line} (${word})`);
      }
      if (section.type === 'table') {
        for (const i of section.llmRows ?? []) {
          if ((section.rows[i] ?? []).some((cell) => FOUND_PREFIX.test(cell))) {
            prefixed.push(`${page.id} table row ${i + 1}`);
          }
        }
      }
    }
  }
  return {
    systemMarked: Object.values(elements)
      .filter((e) => e.kind === 'system' && e.llm)
      .map((e) => e.name),
    elements: Object.values(elements).filter((e) => e.llm).length,
    lines,
    prefixed,
    infrastructureLines,
  };
}

/** The Investigate contract's infrastructure list (#749), lower case. */
function infrastructureList() {
  return (readContracts().investigate?.lists?.infrastructure ?? []).map((w) => w.toLowerCase());
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
  const judgements = inputs.judgements ?? null;
  const overlap = (a, b) => subjectOverlap(subjectWords(a), subjectWords(b));
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
      inputs.selection ?? null,
    ),
    investigate: investigateRow(answerKey, llm, subjectOf, overlap, SUBJECT_THRESHOLD, judgements),
    // Apart from Investigate's own risks (#748): the fixed checks' findings it only worded, and the
    // connections it found, by their two ends.
    fixedChecks: fixedCheckRow(answerKey, llm, subjectOf, overlap, SUBJECT_THRESHOLD, judgements),
    // Two risks of one lead kind with the same units: one problem stored twice (#747).
    repeatedRisks: repeatedRisks(llm),
    connections: connectionsRow(
      llm,
      connectionsOf(reference).map((l) => l.key),
      subjectOf,
      judgements,
    ),
    unusedVerdicts: unusedVerdicts(judgements, llm),
    nameGroups: withJudge(nameGroupsRow(run), judged.nameGroups),
    purpose: judged.purpose,
    harness: harnessRow(run, warm, change, inputs.changeFile ?? null),
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

/** The one-file change gate as one phrase (#755). */
function changeCell(c, why) {
  if (c.measured === false) return why(c);
  if (c.gate === null || c.gate === undefined) return `${c.calls} calls (${c.why})`;
  const shown = c.unexpected.slice(0, 5).join('; ');
  const more = c.unexpected.length > 5 ? ` and ${c.unexpected.length - 5} more` : '';
  return `${c.calls} calls, ${c.items} items, ${c.expected} expected${c.throughAnswers ? ` (${c.throughAnswers} through a reworded answer)` : ''}${
    c.gate ? ' (gate passed)' : ` (gate FAILED: ${shown}${more})`
  }`;
}

/** Describe's model coverage as one phrase (#742). */
function describeModelCell(m) {
  if (!m || m.measured === false) return `model: not measured (${m?.why ?? 'no run files'})`;
  return `model: ${m.own}/${m.interactions} interactions a view can draw with their own intent (${percent(m.coverage)}), ${m.generic} generic, ${m.unknown} don't know, ${m.notAsked} not asked; penalized ${percent(m.penalized)} (p = ${m.penalty})`;
}

/** The items a row could not judge, apart: not asked, failed, never an item (#734); or empty. */
const unjudged = (r) =>
  [
    r.notAsked ? `${r.notAsked} not asked` : '',
    r.failed ? `${r.failed} failed` : '',
    r.notItems ? `${r.notItems} never an item` : '',
  ]
    .filter(Boolean)
    .map((part) => `; ${part}`)
    .join('');

/** The Describe cell's sequence steps (#740), or nothing for a scorecard written before them. */
function stepsCell(steps) {
  if (!steps) return '';
  return `; steps ${steps.messages} drawn, ${steps.intended} intended, ${steps.own} own, ${steps.fallbackWithIntent} fallback`;
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
      ? `no items${unjudged(r)}${r.note ? ` (${r.note})` : ''}`
      : `${r.items} links: coverage ${percent(r.coverage)}, accuracy ${percent(r.accuracy)}, penalized ${percent(r.penalized)} (p = ${r.penalty}), over-abstention ${percent(r.overAbstention)}${unjudged(r) || '; 0 not asked'}`;
  const d = roles.describe;
  cell.Describe = !d.measured
    ? why(d)
    : `elements ${d.elementsDescribed}/${d.elements} (${percent(d.elementCoverage)}); ${describeModelCell(d.model)}; drawn: ${d.drawn.own}/${d.drawn.lines} lines with their own intent (${percent(d.drawn.coverage)}), ${d.drawn.generic} generic${d.drawn.genericWithIntent === undefined ? "" : ` (${d.drawn.genericWithIntent} of them on a line whose link has a stored intent)`}${stepsCell(d.steps)}; judge ${judgedCell(d.judge)}`;
  const f = roles.flows;
  cell.Flows = !f.measured
    ? why(f)
    : `${f.matched}/${f.reference} recall (${percent(f.recall)}), ${f.matched}/${f.report} precision (${percent(f.precision)})`;
  const sel = roles.selection;
  if (sel) {
    const choice = (r) =>
      `${r.right}/${r.items} reference flows chosen (${percent(r.recall)}), ${r.chosenMatching}/${r.chosen} chosen flows in the reference (${percent(r.precision)}), penalized ${percent(r.penalized)} (p = ${r.penalty})`;
    cell['Flow selection'] = !sel.measured
      ? why(sel)
      : `${sel.mode === 'judged' ? 'judged' : 'rank fallback'}: ${choice(sel)}; ${sel.candidates} candidates, ${sel.notCandidates.length} reference flows not among them${
          // The rank's choice on the same candidates, and drawn flows that overlap (#743).
          sel.judged && sel.rank ? `; rank fallback on the same candidates: ${choice(sel.rank)}` : ''
        }${sel.overlaps ? `; ${sel.overlaps.length} overlapping drawn flows${sel.overlaps.length ? ` (${sel.overlaps.join('; ')})` : ''}` : ''}`;
  }
  const i = roles.investigate;
  cell.Investigate = !i.measured
    ? why(i)
    : `${i.matched}/${i.reference} recall (${percent(i.recall)}), ${i.right}/${i.right + i.wrong} drawn risks right (precision ${percent(i.precision)}), ${i.unjudged.length} of ${i.found} drawn unjudged; decoys flagged ${i.decoysFlagged}/${i.decoys}, TPR − FPR ${percent(i.tprMinusFpr)}`;
  const rr = roles.repeatedRisks;
  if (rr) {
    cell['Repeated risks'] = !rr.measured
      ? why(rr)
      : rr.repeated.length === 0
        ? 'none'
        : rr.repeated.map((g) => `${g.titles.length} of ${g.kind} on ${g.units.join(', ')}: ${g.titles.join('; ')}`).join(' | ');
  }
  const fc = roles.fixedChecks;
  if (fc) {
    cell['Fixed checks worded'] = !fc.measured
      ? why(fc)
      : `${fc.right}/${fc.right + fc.wrong} right (precision ${percent(fc.precision)}), ${fc.unjudged.length} of ${fc.found} unjudged; ${fc.matched}/${fc.reference} reference risks`;
  }
  const cn = roles.connections;
  if (cn) {
    cell['Investigate connections'] = !cn.measured
      ? why(cn)
      : `${cn.right}/${cn.right + cn.wrong} right (precision ${percent(cn.precision)}), ${cn.unjudged.length} of ${cn.found} unjudged; ${cn.matched}/${cn.reference} reference connections found`;
  }
  const n = roles.nameGroups;
  cell['Name groups'] = !n.measured ? why(n) : `${n.named}/${n.groups} named (${percent(n.coverage)}); judge ${judgedCell(n.judge)}`;
  cell.Purpose = `key fact judge ${judgedCell(roles.purpose)}`;
  const h = roles.harness;
  cell.Harness = !h.measured
    ? why(h)
    : `$${h.costUsd}, ${h.calls} calls, tokens in ${h.tokens.input} / out ${h.tokens.output} / cache read ${h.tokens.cacheRead} / cache write ${h.tokens.cacheWrite}; cache hit rate ${percent(h.cacheHitRate)} of ${h.items} items; warm ${
        h.warm.measured === false ? why(h.warm) : `${h.warm.calls} calls${h.warm.gate ? ' (gate passed)' : ' (gate FAILED: not zero)'}`
      }; one-file change ${changeCell(h.oneFileChange, why)}`;
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
    const support = supportLine(card.run.supportVerdicts);
    if (support) lines.push(`- Support judge: ${support}`);
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
    const kinds = s.roles.describe?.model?.byKind;
    if (kinds?.length) {
      lines.push(
        '',
        "Describe's model coverage by kind of interaction: of the interactions a view can draw, how many have no intent of their own (generic, don't know, not asked), most first.",
        '',
        '| Kind | Interactions | Own intent | Uncovered | Generic | Don\'t know | Not asked |',
        '| --- | --- | --- | --- | --- | --- | --- |',
      );
      for (const k of kinds) {
        lines.push(`| ${k.kind} | ${k.interactions} | ${k.own} | ${k.interactions - k.own} | ${k.generic} | ${k.unknown} | ${k.notAsked} |`);
      }
    }
    if (card.answerKey) lines.push('', `Answer key: \`${card.answerKey}\` (prepared from the code and the references; see its header).`);
    const inv = s.roles.investigate;
    if (inv.measured && inv.unjudged.length > 0) {
      lines.push('', `Unjudged risks (${inv.unjudged.length}, matching no reference risk or decoy, with no verdict in the judgement file): ${inv.unjudged.join('; ')}.`);
    }
    const fixed = s.roles.fixedChecks;
    if (fixed?.measured && fixed.unjudged.length > 0) {
      lines.push('', `Unjudged fixed-check wordings (${fixed.unjudged.length}): ${fixed.unjudged.join('; ')}.`);
    }
    const found = s.roles.connections;
    if (found?.measured && found.unjudged.length > 0) {
      lines.push('', `Unjudged connections (${found.unjudged.length}, by their two ends): ${found.unjudged.join('; ')}.`);
    }
    if (card.judgements) lines.push('', `Judgement file: \`${card.judgements}\`.`);
    const unused = s.roles.unusedVerdicts ?? [];
    if (unused.length > 0) {
      lines.push('', `Verdicts naming nothing this run drew (${unused.length}; a typo, or another run's): ${unused.join('; ')}.`);
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
  if (s.unknownCallers) {
    const u = s.unknownCallers;
    lines.push('', '## Unknown caller lines', '');
    if (u.lines.length === 0) lines.push('None drawn.');
    for (const line of u.lines) lines.push(`- ${UNKNOWN_CALLER} to ${line.to}${line.detail ? `: ${line.detail}` : ''}`);
    if (u.lines.length > 0) {
      lines.push(
        '',
        u.pass
          ? 'Each points to entry points nothing in the set reaches (the composer\'s check passes).'
          : 'FAIL: the composer\'s check finds a line citing an entry point something reaches.',
      );
    }
  }
  lines.push('', '## Quality-bar checks', '');
  for (const check of s.checks) {
    const shown = check.failures.slice(0, 5).join('; ');
    const more = check.failures.length > 5 ? ` and ${check.failures.length - 5} more` : '';
    lines.push(`- ${check.pass ? 'pass' : 'FAIL'}: ${check.description}${check.pass ? '' : shown ? ` (${shown}${more})` : ''}`);
  }
  if (s.llmMarks) {
    const m = s.llmMarks;
    const list = (items) => (items.length === 0 ? 'none' : items.join('; '));
    lines.push(
      '',
      '## LLM marks',
      '',
      `- Badged: ${m.elements} elements, ${m.lines} lines.`,
      `- System boxes badged (should be none): ${list(m.systemMarked)}.`,
      `- Badged but still opening with "Found by the LLM:" (should be none): ${list(m.prefixed)}.`,
      `- Found lines to infrastructure (should be none): ${list(m.infrastructureLines)}.`,
    );
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
 * The support judge's verdicts per role from run.json (ADR 0101, #751): how many texts it found
 * supported, partly supported and unsupported, how many it rejected and had asked again once, and
 * how many the documents show in Lantern's plain wording instead; null when the run judged none.
 */
export function supportLine(verdicts) {
  const roles = Object.entries(verdicts ?? {});
  if (roles.length === 0) return null;
  return roles
    .map(
      ([role, v]) =>
        `${role} ${v.supported ?? 0} supported, ${v.partly ?? 0} partly, ${v.unsupported ?? 0} unsupported, ${v.retried ?? 0} asked again, ${v.fellBack ?? 0} in plain wording`,
    )
    .join('; ');
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
 * @param options `answerKey` (default `<set>/answer-key.json`), `judgements` (default
 *     `<set>/judgements.json`, #748), `warm` and `change` (run records
 *     of a warm rerun and of a one-file change), `changeFile` (the file changed, `repo:path`), `labels` (the owner's wording labels, default
 *     testsets/wording-labels); the run's facts (linked.jsonl.gz, llm.jsonl.gz) and the wording
 *     judge's verdicts (wording.jsonl.gz) are read beside run.json, or beside the report without one
 */
export function scoreFiles(reportPath, setDir, runPath = null, options = {}) {
  const referencePath = `${setDir}/reference/reference.c4.json`;
  const report = readReport(reportPath);
  const reference = JSON.parse(readFileSync(referencePath, 'utf8'));
  const beside = dirname(runPath ?? reportPath);
  const keyPath = options.answerKey ?? `${setDir}/answer-key.json`;
  const judgementsPath = options.judgements ?? `${setDir}/judgements.json`;
  const inputs = {
    answerKey: readAnswerKey(keyPath),
    judgements: readJudgements(judgementsPath),
    linked: readFacts(join(beside, 'linked.jsonl.gz')),
    llm: readFacts(join(beside, 'llm.jsonl.gz')),
    run: runPath ? readRun(runPath) : null,
    warm: options.warm ? readRun(options.warm) : null,
    change: options.change ? readRun(options.change) : null,
    changeFile: options.changeFile ?? null,
    wording: readLines(join(beside, 'wording.jsonl.gz')),
    rejections: readLines(join(beside, 'rejections.jsonl.gz')),
    selection: existsSync(join(beside, 'flows.json')) ? JSON.parse(readFileSync(join(beside, 'flows.json'), 'utf8')) : null,
    labels: readLabels(options.labels),
  };
  return {
    answerKey: inputs.answerKey ? keyPath : null,
    judgements: inputs.judgements ? judgementsPath : null,
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
  // Components are matched by name within each matched container, not one by one (#758).
  const components = (c) =>
    `${(c.byContainer ?? []).reduce((n, x) => n + (x.matchedByName ?? 0), 0)}/${c.reference} by name, ${c.report} drawn`;
  lines.push(`| Components | ${components(a.scores.components)} | ${components(b.scores.components)} |`);
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
  const spread = investigateSpread(a, b);
  if (spread.measured) {
    lines.push(
      `| Investigate spread | recall ${percent(spread.recall[0])}, precision ${percent(spread.precision[0])} | recall ${percent(spread.recall[1])}, precision ${percent(spread.precision[1])}; apart by ${spread.recallSpread ?? '-'} and ${spread.precisionSpread ?? '-'} points |`,
    );
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
  // Describe's sequence steps per set, before and after (#740).
  lines.push('', '## Describe steps per set', '', `| Set | ${labelA} | ${labelB} |`, '| --- | --- | --- |');
  for (const set of sets) {
    const steps = (card) => {
      const d = card.scores.roles?.describe;
      return d?.measured && d.steps ? stepsCell(d.steps).replace(/^; steps /, '') : '-';
    };
    lines.push(`| ${set} | ${steps(a.get(set))} | ${steps(b.get(set))} |`);
  }
  // Two runs of one model: how far apart Investigate's recall and precision are, per set (#748).
  lines.push('', '## Investigate per set', '', `| Set | Recall ${labelA} | Recall ${labelB} | Precision ${labelA} | Precision ${labelB} | Spread (points) |`, '| --- | --- | --- | --- | --- | --- |');
  for (const set of sets) {
    const sp = investigateSpread(a.get(set), b.get(set));
    if (!sp.measured) {
      lines.push(`| ${set} | - | - | - | - | ${sp.why} |`);
      continue;
    }
    lines.push(
      `| ${set} | ${percent(sp.recall[0])} | ${percent(sp.recall[1])} | ${percent(sp.precision[0])} | ${percent(sp.precision[1])} | recall ${sp.recallSpread ?? '-'}, precision ${sp.precisionSpread ?? '-'} |`,
    );
  }
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
        `${issues.length === 0 ? 'ok' : `${issues.length} issues`}: ${(key.resolve ?? []).length} links, ${(key.risks ?? []).length} risks, ${(key.decoys ?? []).length} decoys, ${(key.investigate ?? []).length} leads`,
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
      const judgements = option(args, '--judgements');
      const warm = option(args, '--warm');
      const change = option(args, '--change');
      const changeFile = option(args, '--change-file');
      const labels = option(args, '--labels');
      const [reportPath, setDir] = args;
      if (!reportPath || !setDir) {
        throw new Error(
          'usage: score.mjs <report .html|.json> <set folder> [--run <run.json>] [--answer-key <file>] [--judgements <file>] [--warm <run.json>] [--change <run.json> --change-file <repo>:<path>] [--labels <folder>] [--out <prefix>]',
        );
      }
      const card = scoreFiles(reportPath, setDir, run, {
        answerKey: answerKey ?? undefined,
        judgements: judgements ?? undefined,
        warm,
        change,
        changeFile: changeFile ?? undefined,
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
