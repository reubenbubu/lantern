// The scoring rows per LLM role (docs/epics/001-knowledge-build.md, section 13, as changed by #662;
// docs/research/role-measurement.md; docs/adr/0072-scoring-harness.md, amendment for #667). Each row
// is computed from a run (its report, run.json, linked.jsonl.gz and llm.jsonl.gz) against the set's
// reference and its answer key (testsets/<set>/answer-key.json), which is prepared from the code
// and the references, never from a model's answers. A row that a run cannot fill says so as
// "not measured", with why.
//
// Every row that scores items keeps each item's score under `items`, so two scorecards can be
// compared item by item (pairedDifference below).
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { interactionKinds, readVocabulary } from './vocabulary.mjs';

/** The penalty of a wrong answer against a right one, per role (the role's ADR records it). */
export const PENALTY = { resolve: 3, flows: 1, describe: 1 };

/** The least share of participants two flows must share, over the larger set (#662). */
export const FLOW_OVERLAP = 2 / 3;

/** A row a run cannot fill, and why. */
export const notMeasured = (why) => ({ measured: false, why });

/** The facts of a gzipped JSON-lines file, or null when there is none. */
export function readFacts(path) {
  if (!path || !existsSync(path)) return null;
  return gunzipSync(readFileSync(path))
    .toString('utf8')
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => JSON.parse(line));
}

/** A set's answer key, or null when it has none. */
export function readAnswerKey(path) {
  return path && existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : null;
}

/** A Lantern entity ID as the report writes element IDs: lower case, dashes for the rest. */
export function reportId(entity) {
  return String(entity ?? '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

const ratio = (a, b) => (b === 0 ? null : a / b);

/** The outcomes of an item no answer was had for: not asked, its request failed, or never an item. */
const UNJUDGED = new Set(['notAsked', 'failed', 'notAnItem']);

/**
 * The three-outcome score of a role's items (#662): each item correct, wrong or abstained, or left
 * unjudged and counted apart: not asked (budget, a model without what it needs), failed, or never
 * an item (#734).
 *
 * @param items `{ id, outcome, unknown }`, outcome one of correct, wrong, abstained, notAsked,
 *     failed, notAnItem; unknown when the reference's right answer is "unknown"
 * @param p the penalty of a wrong answer
 */

export function threeOutcomes(items, p) {
  const judged = items.filter((i) => !UNJUDGED.has(i.outcome));
  const count = (o) => judged.filter((i) => i.outcome === o).length;
  const correct = count('correct');
  const wrong = count('wrong');
  const abstained = count('abstained');
  // A right abstention (the key says unknown) is correct but not an answer.
  const picked = judged.filter((i) => i.outcome === 'correct' && !i.abstainedRight).length;
  const answerable = judged.filter((i) => !i.unknown);
  const overAbstained = answerable.filter((i) => i.outcome === 'abstained').length;
  const scores = {};
  for (const i of judged) scores[i.id] = i.outcome === 'correct' ? 1 : i.outcome === 'wrong' ? -p : 0;
  return {
    measured: true,
    items: judged.length,
    notAsked: items.filter((i) => i.outcome === 'notAsked').length,
    failed: items.filter((i) => i.outcome === 'failed').length,
    notItems: items.filter((i) => i.outcome === 'notAnItem').length,
    correct,
    wrong,
    abstained,
    coverage: ratio(picked + wrong, judged.length),
    accuracy: ratio(picked, picked + wrong),
    penalized: ratio(correct - p * wrong, judged.length),
    penalty: p,
    overAbstention: ratio(overAbstained, answerable.length),
    scores,
  };
}

/** A role's record in run.json's harness list, by its name. */
function record(run, role) {
  return (run?.harness ?? []).find((r) => r.role === role) ?? null;
}

/**
 * Resolve: each open link of the answer key, as the run answered it. A pick is the target of a
 * link of origin llm/resolve that resolves the open link's claim; no pick of a link the role asked
 * or had cached is an abstention. A link it did not ask (budget, a model without tool use) is not
 * asked, one whose request failed is failed, and one it never had as an item (no candidates) is not
 * an item; the three are counted apart and none is judged (#734).
 */
export function resolveRow(key, linked, llm, run) {
  const entries = key?.resolve ?? [];
  if (!key) return notMeasured('the set has no answer key');
  if (entries.length === 0) return { ...threeOutcomes([], PENALTY.resolve), note: 'no open link in the answer key' };
  if (!linked && !llm) return notMeasured('the run wrote no linked.jsonl.gz or llm.jsonl.gz');
  const picks = new Map();
  for (const fact of [...(linked ?? []), ...(llm ?? [])]) {
    const resolves = fact.attributes?.resolves;
    if (resolves && fact.origin?.kind === 'llm') picks.set(resolves, fact.object?.value);
  }
  const resolve = record(run, 'resolve');
  const unasked = new Set(resolve?.unaskedSubjects ?? []);
  const failed = new Set(resolve?.failedSubjects ?? []);
  // The links the role had an answer for this run: asked, or answered from the cache.
  const answered = new Set([...(resolve?.askedSubjects ?? []), ...(resolve?.cachedSubjects ?? [])]);
  const items = entries.map((entry) => {
    // A link more than one target receives (every queue bound to the key) lists them all; a pick
    // of any of them is right.
    const right = new Set([entry.answer].flat());
    const unknown = right.has('unknown');
    const pick = picks.get(entry.link);
    let outcome;
    if (pick !== undefined) outcome = !unknown && right.has(pick) ? 'correct' : 'wrong';
    // Its request failed; not asked for budget or a model without tool use; never an item (no
    // candidates).
    else if (failed.has(entry.link)) outcome = 'failed';
    else if (unasked.has(entry.link)) outcome = 'notAsked';
    else if (!answered.has(entry.link)) outcome = 'notAnItem';
    // Asked and answered unknown or left out: abstained, which is right when the key says unknown.
    else outcome = unknown ? 'correct' : 'abstained';
    const abstainedRight = pick === undefined && outcome === 'correct';
    return { id: entry.link, outcome, unknown, abstainedRight, pick: pick ?? null, answer: [...right].join(' or ') };
  });
  const row = threeOutcomes(items, PENALTY.resolve);
  row.wrongItems = items.filter((i) => i.outcome === 'wrong').map((i) => `${i.id}: picked ${i.pick}, right ${i.answer}`);
  return row;
}

/**
 * A text as a line's label shows it: citation markers dropped, spaces folded, and the note a line
 * drawn only under some Spring profiles carries, " (only with the Spring profile x)", left off.
 */
export const label = (text) =>
  String(text ?? '')
    .replace(/\[(fact|summary):[^\]]*\]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^Only with the Spring profiles? .*$/, '')
    .replace(/ \(only with the Spring profiles? [^)]*\)$/, '');

/**
 * The openings that say only that there is a relationship, not what it is for: those Describe's
 * prompt forbids an intent to start with, and "accesses", which the quality bar's check of line
 * labels also counts as generic.
 */
const GENERIC_OPENING =
  /^(uses|use|calls|depends on|connects to|talks to|communicates with|interacts with|accesses)\b/i;

/** Whether a line label or an intent is empty or opens only with a generic verb. */
export const isGenericLabel = (text) => {
  const plain = label(text);
  return plain === '' || GENERIC_OPENING.test(plain);
};

/** The kinds of report element Describe gives a responsibility. */
const DESCRIBED_KINDS = new Set(['container', 'database', 'queue', 'external', 'component', 'person']);

/** The kinds of report element a message passes through. */
const MESSAGING_KINDS = new Set(['queue', 'exchange', 'topic']);

/** The report IDs an entity of the facts may be drawn under: its own, or its type's for a component. */
const drawnIds = (entity) => [reportId(entity), reportId(String(entity).split('/').pop())];

const list = (v) => (Array.isArray(v) ? v : v === undefined || v === null ? [] : [v]);

/**
 * Whether a unit's link is the alternative of a default one under a Spring profile, which Describe
 * leaves out (Screens.alternatives): it holds only under a profile, and a link of the same unit and
 * kind holds by default, is absent under that profile and has the same origin (each names the other
 * as an alternate, or both come from the same source or key).
 */
function isAlternative(link, sameKind) {
  const profile = link.attributes?.view;
  if (!profile) return false;
  return sameKind.some(
    (f) =>
      f !== link &&
      !f.attributes?.view &&
      list(f.attributes?.absentIn).includes(profile) &&
      (list(link.attributes?.alternates).includes(f.object?.value) ||
        ['source', 'key'].some((k) => link.attributes?.[k] !== undefined && link.attributes[k] === f.attributes?.[k])),
  );
}

/** The vocabulary, read once (vocabulary.mjs). */
let builtVocabulary = null;
const defaultVocabulary = () => (builtVocabulary ??= readVocabulary());

/**
 * Every interaction of the stored model that a view can draw (#742, reopened), whether Describe
 * asks about it or not, each with its kind, the units whose Describe item would hold it (`owners`)
 * and the claim and fact IDs an intent's object may name:
 *
 * - each link of an interaction kind, a relationship type the vocabulary puts under INTERACTS_WITH
 *   (so a kind added to the vocabulary is counted without a change here), between two ends the
 *   report draws (a queue folded into its exchange's line drawn through the exchange), other than a
 *   profile's alternative (Describe and the diagrams show the default);
 *   Investigate's found connections are such links, of origin llm;
 * - each line between two components of the same unit (COMPONENT_LINK), other than to code at the
 *   end of a call, which the component view draws as the link between containers it belongs to;
 * - each component's data access, one per database its unit uses (the component view's line);
 * - each person's use of the system (person-basis), owned by the frontends it names.
 *
 * @param facts the run's linked and llm facts
 * @param report the report, whose elements tell which ends are drawn
 */
export function interactionsOf(facts, report, vocabulary = defaultVocabulary()) {
  const kinds = interactionKinds(vocabulary);
  const shown = (entity) => drawnIds(entity).some((id) => report.elements?.[id]);
  // A queue an exchange routes to is folded into the exchange's line when the diagrams leave it
  // out: its consumers' lines run from the exchange.
  const routedFrom = new Map();
  for (const f of facts) {
    if (f.predicate === 'ROUTES_TO' && f.object?.kind === 'entity') {
      if (!routedFrom.has(f.object.value)) routedFrom.set(f.object.value, []);
      routedFrom.get(f.object.value).push(f.subject);
    }
  }
  const drawn = (entity) => shown(entity) || (routedFrom.get(entity) ?? []).some(shown);
  const keys = (f) => [f.claim, f.id].filter(Boolean);
  const unitOf = new Map();
  for (const f of facts) if (f.predicate === 'PART_OF') unitOf.set(f.subject, f.object?.value);
  const byOwnerAndKind = new Map();
  for (const f of facts) {
    if (!kinds.has(f.predicate) || f.object?.kind !== 'entity' || !drawn(f.subject)) continue;
    // A route to a folded queue is not a line of its own: the queue's consumers are drawn instead.
    if (!(f.predicate === 'ROUTES_TO' ? shown : drawn)(f.object.value)) continue;
    const key = `${f.subject} ${f.predicate}`;
    if (!byOwnerAndKind.has(key)) byOwnerAndKind.set(key, []);
    byOwnerAndKind.get(key).push(f);
  }
  const result = [];
  const links = [];
  for (const group of byOwnerAndKind.values()) {
    for (const f of group) {
      if (isAlternative(f, group)) continue;
      links.push(f);
      result.push({ id: `interaction:${f.id}`, fact: f, kind: f.predicate, owners: [f.subject], keys: keys(f) });
    }
  }
  for (const f of facts) {
    if (f.predicate !== 'COMPONENT_LINK' || f.attributes?.kind === 'code-end') continue;
    const unit = unitOf.get(f.subject);
    if (unit && unitOf.get(f.object?.value) === unit) {
      result.push({ id: `interaction:${f.id}`, fact: f, kind: 'COMPONENT_LINK', owners: [unit], keys: keys(f) });
    }
  }
  for (const f of facts) {
    if (f.predicate !== 'data-access') continue;
    const unit = unitOf.get(f.subject);
    // A component's access takes its unit's intent for the store (#739), so the store link's keys
    // count for it too (#741).
    for (const db of links.filter((l) => l.predicate === 'USES_DATABASE' && l.subject === unit)) {
      result.push({ id: `interaction:${f.id}:${db.object.value}`, fact: f, kind: 'data-access', owners: [unit], keys: [...keys(f), ...keys(db)] });
    }
  }
  // A person-basis names its frontends as their own names: matched to the frontends drawn, by the
  // name the report shows or the end of the ID, in any case.
  const byName = new Map();
  for (const f of facts) {
    const e = report.elements?.[reportId(f.subject)];
    if (!e) continue;
    for (const n of [e.name, String(f.subject).split(':').pop()]) if (n) byName.set(String(n).toLowerCase(), f.subject);
  }
  // Or by a line the diagrams draw from the person to a frontend, whose basis may name it
  // otherwise (#741).
  const byReportId = new Map();
  for (const f of facts) if (report.elements?.[reportId(f.subject)]) byReportId.set(reportId(f.subject), f.subject);
  const drawnTo = new Map();
  for (const page of report.pages ?? []) {
    for (const section of page.sections ?? []) {
      for (const r of section.relationships ?? []) {
        if (report.elements?.[r.from]?.kind !== 'person') continue;
        const to = byReportId.get(r.to);
        if (!to || !String(to).startsWith('frontend:')) continue;
        if (!drawnTo.has(r.from)) drawnTo.set(r.from, new Set());
        drawnTo.get(r.from).add(to);
      }
    }
  }
  for (const f of facts) {
    if (f.predicate !== 'person-basis') continue;
    const owners = [
      ...list(f.attributes?.frontends)
        .map((n) => byName.get(String(n).toLowerCase()))
        .filter(Boolean),
      ...(drawnTo.get(reportId(f.subject)) ?? []),
    ];
    // One interaction per frontend the person uses, whose intent is stored under the basis and the
    // frontend (ADR 0103, #739; #741).
    for (const frontend of new Set(owners)) {
      result.push({
        id: `interaction:${f.id}:${frontend}`,
        fact: f,
        kind: 'USES (person)',
        owners: [frontend],
        keys: keys(f).map((k) => `${k}/${frontend}`),
      });
    }
  }
  return result;
}

/**
 * Describe's coverage (section 13; #742). Three numbers:
 *
 * - elements: the share of the report's elements with a responsibility it wrote;
 * - model: every interaction of the stored model a view can draw (`interactionsOf`), whether
 *   Describe asks about it or not, scored right when it has an intent of its own that does not
 *   open with a generic verb, wrong when its intent does (a point lost, PENALTY.describe), "don't
 *   know" when it has none though Describe asked about a unit that would hold it, and not asked
 *   when it asked about none (budget, a failure, or an element no unit owns); its coverage is the
 *   share with an intent of their own among them all, and each kind is counted apart;
 * - drawn: of the lines the report draws, other than a person's (whose labels are fixed), those
 *   labelled with the intent Describe wrote for a link running between the line's two ends, or
 *   elements inside them; and of the rest, those whose label is generic.
 *
 * Only Describe's intents count, not those Name groups writes for lines leaving a group. Its
 * wording's judge score is added by the scorecard (wording.mjs, judgedRows).
 */
export function describeRow(report, linked, llm, run = null, vocabulary = defaultVocabulary()) {
  if (!llm) return notMeasured('the run wrote no llm.jsonl.gz');
  // A component is drawn under its anchor type's ID (component:<unit>/type:<type> as type-<type>).
  const described = new Set(llm.filter((f) => f.predicate === 'responsibility').flatMap((f) => drawnIds(f.subject)));
  const elements = Object.entries(report.elements).filter(([, e]) => DESCRIBED_KINDS.has(e.kind));
  const withText = elements.filter(([id]) => described.has(id));
  const scores = {};
  for (const [id] of elements) scores[`element:${id}`] = withText.some(([w]) => w === id) ? 1 : 0;

  // Describe's intents, by the claim or fact of the link each words; a link worded in more than one
  // item, or again by a later run whose answer is in the snapshot too, has several.
  const intentOf = new Map();
  for (const f of llm) {
    if (f.predicate !== 'intent' || f.origin?.name !== 'describe-unit') continue;
    const key = f.object?.value;
    if (!intentOf.has(key)) intentOf.set(key, new Set());
    intentOf.get(key).add(label(f.attributes?.text));
  }

  // The model: each interaction scored right, wrong or "don't know".
  const record = (run?.harness ?? []).find((r) => r.role === 'describe-unit') ?? null;
  const unasked = new Set(record?.unaskedSubjects ?? []);
  const asked = new Set([...(record?.askedSubjects ?? []), ...(record?.cachedSubjects ?? [])]);
  const units = new Set([...asked, ...unasked]);
  let model = null;
  if (linked && record) {
    const items = interactionsOf([...linked, ...llm], report, vocabulary).map(({ id, kind, owners, keys }) => {
      const texts = keys.flatMap((k) => [...(intentOf.get(k) ?? [])]);
      // Right when one of its intents is not generic.
      const text = texts.find((t) => !isGenericLabel(t)) ?? texts[0];
      const outcome =
        text !== undefined
          ? isGenericLabel(text)
            ? 'wrong'
            : 'correct'
          : owners.some((o) => asked.has(o))
            ? 'abstained'
            : 'notAsked';
      return { id, kind, outcome, unknown: false, text };
    });
    const row = threeOutcomes(items, PENALTY.describe);
    Object.assign(scores, row.scores);
    const byKind = new Map();
    for (const i of items) {
      const k = byKind.get(i.kind) ?? { kind: i.kind, interactions: 0, own: 0, generic: 0, unknown: 0, notAsked: 0 };
      k.interactions++;
      k[{ correct: 'own', wrong: 'generic', abstained: 'unknown', notAsked: 'notAsked' }[i.outcome]]++;
      byKind.set(i.kind, k);
    }
    model = {
      // Every interaction a view can draw, not asked included: coverage is the share with an
      // intent of its own among them all.
      interactions: items.length,
      own: row.correct,
      generic: row.wrong,
      unknown: row.abstained,
      notAsked: row.notAsked,
      coverage: ratio(row.correct, items.length),
      penalized: row.penalized,
      penalty: row.penalty,
      genericItems: items.filter((i) => i.outcome === 'wrong').map((i) => i.text),
      byKind: [...byKind.values()].sort((a, b) => b.interactions - b.own - (a.interactions - a.own) || a.kind.localeCompare(b.kind)),
    };
  }

  // The page: each line drawn, once, with its own link's intent or not. A line and a link share an
  // end when one is the other or lies inside it: a view draws a link between the boxes it shows,
  // a container for its component or a component for its unit. A consumer's line is drawn from
  // what it reads, the queue or the exchange the queue is bound to, so any messaging box is that
  // end.
  const chain = (id) => {
    const result = [];
    for (let at = id; at && !result.includes(at); at = report.elements[at]?.parent) result.push(at);
    return result;
  };
  const related = (box, ids) => chain(box).some((a) => ids.includes(a)) || ids.some((id) => chain(id).includes(box));
  const worded = [];
  if (linked) {
    const byKey = new Map();
    for (const f of linked) for (const k of [f.claim, f.id]) if (k) byKey.set(k, f);
    for (const [key, texts] of intentOf) {
      const link = byKey.get(key);
      if (link) {
        worded.push({ texts, consumes: link.predicate === 'CONSUMES', source: drawnIds(link.subject), target: drawnIds(link.object?.value) });
      }
    }
  }
  const ownLink = (r) => {
    const text = label(r.label);
    return worded.some((w) =>
      !w.texts.has(text)
        ? false
        : w.consumes
          ? MESSAGING_KINDS.has(report.elements[r.from]?.kind) && related(r.to, w.source)
          : related(r.from, w.source) && related(r.to, w.target),
    );
  };
  // Whether a link running between the line's ends has a stored intent, whatever it says (#739).
  const intended = (r) =>
    worded.some((w) =>
      w.consumes
        ? MESSAGING_KINDS.has(report.elements[r.from]?.kind) && related(r.to, w.source)
        : related(r.from, w.source) && related(r.to, w.target),
    );
  const lines = new Map();
  for (const page of report.pages) {
    for (const section of page.sections) {
      if (section.type !== 'diagram') continue;
      for (const r of section.relationships) {
        if (report.elements[r.from]?.kind === 'person') continue;
        const key = `${r.from}->${r.to}`;
        const own = ownLink(r);
        const was = lines.get(key) ?? { own: false, generic: true, intended: false };
        lines.set(key, {
          own: was.own || own,
          generic: was.generic && !own && isGenericLabel(r.label),
          intended: was.intended || intended(r),
        });
      }
    }
  }
  const drawnOwn = [...lines.values()].filter((l) => l.own).length;
  const drawnGeneric = [...lines.values()].filter((l) => !l.own && l.generic).length;
  const genericWithIntent = [...lines.values()].filter((l) => !l.own && l.generic && l.intended).length;
  for (const [key, l] of lines) scores[`line:${key}`] = l.own ? 1 : 0;
  return {
    measured: true,
    elements: elements.length,
    elementsDescribed: withText.length,
    elementCoverage: ratio(withText.length, elements.length),
    model:
      model ??
      notMeasured(!linked ? 'the run wrote no linked.jsonl.gz' : 'run.json has no record of describe-unit'),
    drawn: {
      lines: lines.size,
      own: drawnOwn,
      generic: drawnGeneric,
      genericWithIntent,
      other: lines.size - drawnOwn - drawnGeneric,
      coverage: ratio(drawnOwn, lines.size),
    },
    undescribed: elements.filter(([id]) => !withText.some(([w]) => w === id)).map(([, e]) => e.name),
    scores,
  };
}

/**
 * The requests that start a traced flow, from the linker's facts, by the flow's ID: the flow's ID
 * ends with the claim of its entry point's fact; an endpoint is started by its own request, by each
 * gateway path routed to it (routed-endpoint) and by each frontend call that reaches it, which is
 * where the report says a flow starts. A listener or a schedule has none.
 */
function flowTriggers(linked, spell) {
  const endpoints = new Map();
  for (const f of linked) if (f.predicate === 'endpoint') endpoints.set(f.claim, f);
  const enters = new Map();
  const add = (method, request) => {
    if (!method || !request) return;
    if (!enters.has(method)) enters.set(method, new Set());
    enters.get(method).add(spell(request));
  };
  for (const f of linked) {
    if (f.predicate === 'endpoint') add(f.subject, f.object?.value);
    else if (f.predicate === 'routed-endpoint') add(f.attributes?.endpoint, f.object?.value);
    else if (f.predicate === 'CALLS_HTTP' && String(f.subject).startsWith('frontend:')) {
      // As the composer names the request: its path, or its URL when the path is not known.
      const where = f.attributes?.path ?? f.attributes?.url;
      if (where) add(f.attributes?.reaches, `${f.attributes?.httpMethod ?? ''} ${where}`.trim());
    }
  }
  return (flow) => {
    const endpoint = endpoints.get(String(flow).split('/').pop());
    return endpoint ? [...(enters.get(endpoint.subject) ?? [])] : [];
  };
}

/**
 * The model's choice of flows (#746), apart from whether derivation traced their participants (the
 * Flows row). The candidates are every flow a significance judge saw, in any scope, or every traced
 * flow when none did, one per shape; they are matched to the reference's flows by trigger. The
 * chosen ones:
 *
 * - judged: those the judge of the whole system marked significant, so a flow a unit's pass or the
 *   system's first pass dropped is a candidate not chosen;
 * - rank: the system's choice fell back to the derived rank (the judge failed or was not asked),
 *   so those the report draws.
 *
 * Each reference flow that was a candidate is an item: right when a candidate of its trigger was
 * chosen, wrong when none was (p = 1); a reference flow no candidate starts is apart, and a chosen
 * candidate that matches no reference flow is unjudged, since a reference need not list every
 * significant flow. Recall is the items chosen; precision the chosen candidates that match a
 * reference flow.
 *
 * @param refFlows the reference's flows, triggers spelled as `spell` spells them
 * @param drawnTriggers the triggers of the flows the report draws, spelled the same way
 * @param spell a request as the scorecard spells a trigger (normalized, then aliased)
 */
export function flowSelectionRow(refFlows, drawnTriggers, llm, linked, spell) {
  if (refFlows.length === 0) return notMeasured('the reference draws no flow');
  if (!llm || !linked) return notMeasured('the run wrote no llm.jsonl.gz or linked.jsonl.gz');
  const judgements = llm.filter((f) => f.predicate === 'flow-significance');
  const system = judgements.filter((f) => f.object?.value === 'system');
  const mode = system.length > 0 ? 'judged' : 'rank';
  // Flows of one shape are one candidate (ScopeFlows.candidates keeps the smallest ID), started by
  // the requests of each of them.
  const sameAs = new Map();
  for (const f of linked) if (f.predicate === 'SAME_SHAPE_AS') sameAs.set(f.subject, f.object?.value);
  const shapeOf = (flow) => {
    const seen = new Set();
    let at = flow;
    while (sameAs.has(at) && !seen.has(at)) {
      seen.add(at);
      at = sameAs.get(at);
    }
    return at;
  };
  const triggersOf = flowTriggers(linked, spell);
  const startedBy = new Map();
  for (const f of linked) {
    if (f.predicate !== 'flow-shape') continue;
    const shape = shapeOf(f.subject);
    if (!startedBy.has(shape)) startedBy.set(shape, new Set());
    for (const t of triggersOf(f.subject)) startedBy.get(shape).add(t);
  }
  // The candidates: every flow a judge saw, in any scope (the system's own first pass, on a large
  // system, is not stored, but each unit's pass is), or every traced flow when none did.
  const seenFlows = judgements.length > 0 ? judgements.map((f) => f.subject) : [...startedBy.keys()];
  const drawn = new Set(drawnTriggers.filter(Boolean));
  const significant = new Set(system.filter((f) => f.attributes?.significant === true).map((f) => shapeOf(f.subject)));
  const candidates = new Map();
  for (const flow of seenFlows) {
    const shape = shapeOf(flow);
    if (candidates.has(shape)) continue;
    const triggers = [...new Set([...(startedBy.get(shape) ?? []), ...triggersOf(flow)])];
    const chosen = mode === 'judged' ? significant.has(shape) : triggers.some((t) => drawn.has(t));
    candidates.set(shape, { triggers, chosen });
  }
  const all = [...candidates.values()];
  const items = [];
  const notCandidates = [];
  refFlows.forEach((ref, i) => {
    const theirs = all.filter((c) => ref.trigger !== null && c.triggers.includes(ref.trigger));
    if (theirs.length === 0) {
      notCandidates.push(`${ref.title} (${ref.trigger ?? 'no trigger'})`);
      return;
    }
    const chosen = theirs.some((c) => c.chosen);
    items.push({ id: `${ref.trigger}#${i}`, outcome: chosen ? 'correct' : 'wrong', unknown: false, title: ref.title });
  });
  const refTriggers = new Set(refFlows.map((f) => f.trigger).filter(Boolean));
  const chosen = all.filter((c) => c.chosen);
  const chosenMatching = chosen.filter((c) => c.triggers.some((t) => refTriggers.has(t)));
  const row = threeOutcomes(items, PENALTY.flows);
  return {
    measured: true,
    mode,
    candidates: all.length,
    chosen: chosen.length,
    items: row.items,
    right: row.correct,
    recall: ratio(row.correct, row.items),
    chosenMatching: chosenMatching.length,
    precision: ratio(chosenMatching.length, chosen.length),
    penalized: row.penalized,
    penalty: row.penalty,
    missed: items.filter((i) => i.outcome === 'wrong').map((i) => i.title),
    notCandidates,
    scores: row.scores,
  };
}

/**
 * Flows (#662): a report flow matches a reference flow when the trigger is the same entry point and
 * the participants overlap by at least two thirds, shared over the larger set; one to one, best
 * overlap first. Recall and precision of the significant set: the flows the report draws.
 *
 * @param refFlows reference flows `{ title, trigger, participants }`
 * @param repFlows report flows, triggers written the reference's way, participants as reference IDs
 *     where they match one (others kept as they are)
 */
export function matchFlows(refFlows, repFlows) {
  const pairs = [];
  refFlows.forEach((ref, i) => {
    repFlows.forEach((rep, j) => {
      if (ref.trigger === null || rep.trigger !== ref.trigger) return;
      const theirs = new Set(rep.participants);
      const shared = ref.participants.filter((p) => theirs.has(p)).length;
      const overlap = shared / Math.max(ref.participants.length, rep.participants.length, 1);
      if (overlap >= FLOW_OVERLAP - 1e-9) pairs.push({ i, j, overlap });
    });
  });
  pairs.sort((a, b) => b.overlap - a.overlap || a.i - b.i || a.j - b.j);
  const usedRef = new Set();
  const usedRep = new Set();
  const matches = [];
  for (const pair of pairs) {
    if (usedRef.has(pair.i) || usedRep.has(pair.j)) continue;
    usedRef.add(pair.i);
    usedRep.add(pair.j);
    matches.push(pair);
  }
  return matches;
}

export function flowsRow(refFlows, repFlows) {
  if (refFlows.length === 0) return notMeasured('the reference draws no flow');
  const matches = matchFlows(refFlows, repFlows);
  const matched = new Set(matches.map((m) => m.i));
  const scores = {};
  refFlows.forEach((f, i) => {
    scores[`${f.trigger ?? f.title}#${i}`] = matched.has(i) ? 1 : 0;
  });
  return {
    measured: true,
    reference: refFlows.length,
    report: repFlows.length,
    matched: matches.length,
    recall: ratio(matches.length, refFlows.length),
    precision: ratio(matches.length, repFlows.length),
    matches: matches.map((m) => ({
      reference: refFlows[m.i].title,
      report: repFlows[m.j].title,
      overlap: Math.round(m.overlap * 100) / 100,
    })),
    missing: refFlows.filter((_, i) => !matched.has(i)).map((f) => `${f.title} (${f.trigger ?? 'no trigger'})`),
    scores,
  };
}

/** The answer key's category of each kind of fixed-check finding (ADR 0072, amendment for #667). */
export const FINDING_CATEGORIES = {
  'version-skew': 'shared-type-skew',
  'shared-jwt-secret': 'plaintext-secret',
  'unrouted-endpoint': 'contract-mismatch',
  'end-of-life': 'version-skew',
  'dependency-drift': 'version-skew',
  'snapshot-dependency': 'other',
  'package-cycle': 'other',
  'readme-contradiction': 'other',
  'injection-suspected': 'other',
};

/**
 * The category a stored risk is of: its lead's kind, or for a finding's wording the key's category
 * of the finding's kind.
 */
function categoryOf(fact) {
  if (fact.predicate === 'finding-wording') {
    const kind = String(fact.subject).match(/^finding:([^/]+)/);
    return kind ? (FINDING_CATEGORIES[kind[1]] ?? 'other') : null;
  }
  const lead = fact.attributes?.lead;
  const kind = lead ? String(lead).match(/^lead:([^/]+)/) : null;
  return kind ? kind[1] : null;
}

/**
 * Investigate (#662): each risk it stored matches a reference risk of the answer key, or a decoy,
 * by subject element and category; one that matches several is the one whose words it shares
 * most. A risk with no category (one found exploring, without a lead) matches by subject and the
 * words of its title and finding against the entry's. A risk
 * that matches neither is unjudged: listed and counted, not wrong. TPR − FPR over the reference
 * risks and the decoys; precision over the judged risks only.
 *
 * @param subjectOf maps a Lantern entity ID to the reference element ID it matched, or null
 * @param overlap the subject-word overlap of two texts, 0 to 1
 * @param threshold the overlap at which two texts are about the same thing
 */
export function investigateRow(key, llm, subjectOf, overlap, threshold) {
  if (!key) return notMeasured('the set has no answer key');
  if (!llm) return notMeasured('the run wrote no llm.jsonl.gz');
  const risks = key.risks ?? [];
  const decoys = key.decoys ?? [];
  if (risks.length === 0) return notMeasured('the answer key holds no risks');
  const found = llm
    .filter((f) => f.predicate === 'investigated-risk' || f.predicate === 'finding-wording')
    .map((f) => {
      const units = [...(f.attributes?.units ?? []), f.predicate === 'investigated-risk' ? f.subject : null]
        .filter(Boolean)
        .map(subjectOf)
        .filter(Boolean);
      const text = `${f.attributes?.title ?? ''}. ${f.attributes?.finding ?? ''}`;
      return { fact: f, subjects: new Set(units), category: categoryOf(f), text, title: f.attributes?.title ?? f.subject };
    });
  const words = (entry) =>
    `${entry.reference ?? ''} ${entry.looksLike ?? ''} ${entry.how ?? ''} ${(entry.names ?? []).join(' ')}`;
  const matches = (entry, risk) => {
    if (!entry.subjects.some((s) => risk.subjects.has(s))) return false;
    if (risk.category !== null) return entry.category === risk.category;
    return overlap(words(entry), risk.text) >= threshold;
  };
  // The entry a found risk is: of those it matches, the one whose words it shares most, a
  // reference risk before a decoy on a tie.
  const best = (risk) => {
    const candidates = [
      ...risks.map((e, i) => ({ kind: 'risk', i, e })),
      ...decoys.map((e, i) => ({ kind: 'decoy', i, e })),
    ].filter((c) => matches(c.e, risk));
    if (candidates.length === 0) return null;
    return candidates
      .map((c) => ({ ...c, score: overlap(words(c.e), risk.text) }))
      .sort((a, b) => b.score - a.score || (a.kind === 'risk' ? -1 : 1) - (b.kind === 'risk' ? -1 : 1))[0];
  };
  const hit = new Set();
  const decoyHit = new Set();
  const unjudged = [];
  for (const risk of found) {
    const match = best(risk);
    if (match === null) {
      unjudged.push(risk.title);
      continue;
    }
    if (match.kind === 'risk') {
      hit.add(match.i);
    } else {
      decoyHit.add(match.i);
    }
  }
  const scores = {};
  // Items are named by the entries' IDs, so a key that gains an entry still pairs the others.
  risks.forEach((e, i) => {
    scores[e.id ?? `risk:${i}`] = hit.has(i) ? 1 : 0;
  });
  decoys.forEach((e, i) => {
    scores[e.id ?? `decoy:${i}`] = decoyHit.has(i) ? -1 : 0;
  });
  const tpr = ratio(hit.size, risks.length);
  const fpr = ratio(decoyHit.size, decoys.length);
  return {
    measured: true,
    found: found.length,
    reference: risks.length,
    decoys: decoys.length,
    matched: hit.size,
    decoysFlagged: decoyHit.size,
    recall: tpr,
    // Over the judged entries, each once: repeats of one risk do not count twice.
    precision: ratio(hit.size, hit.size + decoyHit.size),
    tprMinusFpr: tpr === null || fpr === null ? null : tpr - fpr,
    unjudged,
    missing: risks.filter((_, i) => !hit.has(i)).map((e) => e.reference),
    scores,
  };
}

/**
 * Name groups (#662): the share of the groups it was given that it named, from the run's record of
 * the role (an omitted or dropped group, or one not asked, is not named).
 */
export function nameGroupsRow(run) {
  const r = record(run, 'name-groups');
  if (!r) return notMeasured('the run has no record of the name-groups role');
  const i = r.items ?? {};
  const named = (i.planned ?? 0) - (i.omitted ?? 0) - (i.dropped ?? 0) - (i.notAsked ?? 0) - (i.failed ?? 0);
  return {
    measured: true,
    groups: i.planned ?? 0,
    named,
    coverage: ratio(named, i.planned ?? 0),
  };
}

/** A run record's totals over its roles' harness records. */
function totals(run) {
  const t = { calls: 0, costUsd: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0, planned: 0, cached: 0 };
  for (const r of run?.harness ?? []) {
    t.calls += r.calls ?? 0;
    t.costUsd += Number(r.costUsd ?? 0);
    t.input += r.tokens?.input ?? 0;
    t.output += r.tokens?.output ?? 0;
    t.cacheRead += r.tokens?.cacheRead ?? 0;
    t.cacheWrite += r.tokens?.cacheWrite ?? 0;
    t.planned += r.items?.planned ?? 0;
    t.cached += r.items?.cached ?? 0;
  }
  return t;
}

/**
 * The roles that judge or screen texts other roles wrote: a judge's item is about a text, and a
 * text reworded for the change, or sampled for the first time once reworded, makes it new to them.
 */
const JUDGING_ROLES = new Set(['support-judge', 'wording-judge-role', 'wording-judge-specific']);

/**
 * The one-file change gate (knowledge-build epic, section 13; #755): each item the changed run asked
 * may have been re-asked only for the changed file's claims and text, or for an earlier answer it
 * shows that was worded differently. Read from the run record's `askedWhy` (RunRecord, #755).
 *
 * - An item retried alone after its packet left it out, "X (again)", is judged by X's reason.
 * - A judge's item asked for a new or changed text is expected through a reworded answer when the
 *   change re-asked some writing role's item for the file, and counted with those.
 *
 * @param change the changed run's record
 * @param file the changed file, as `repo:path`
 */
export function changeGate(change, file) {
  const items = [];
  const judged = [];
  for (const role of change?.harness ?? []) {
    const why = new Map((role.askedWhy ?? []).map((w) => [w.subject, w]));
    for (const subject of [...new Set(role.askedSubjects ?? [])]) {
      const again = subject.endsWith(' (again)') ? subject.slice(0, -' (again)'.length) : null;
      const w = (again !== null && why.get(again)) || why.get(subject);
      const item = `${role.role} ${subject}`;
      let reason = null;
      if (!w) reason = 'no reason recorded';
      else if (w.new) reason = 'never asked before';
      else if (w.unheld) reason = 'a fact its stored answer cites no longer holds';
      else if (w.prompt) reason = 'its prompt changed';
      else if (w.model) reason = 'its model changed';
      else if (w.other) reason = `${w.other} other input${w.other === 1 ? '' : 's'} changed`;
      else {
        const others = (w.files ?? []).filter((f) => f !== file);
        if (others.length > 0) reason = `inputs from ${others.join(', ')} changed`;
        else if (!(w.files ?? []).includes(file) && !w.answers) reason = 'nothing it rests on changed';
      }
      const entry = { item, expected: reason === null, reason, byAnswer: !!w?.answers && !(w.files ?? []).includes(file) };
      if (JUDGING_ROLES.has(role.role) && (w?.new || w?.other) && !w?.prompt && !w?.model) judged.push(entry);
      else items.push(entry);
    }
  }
  // A judge's item on a reworded text is expected when a writing role re-asked an item for the file.
  const rewrote = items.some((i) => i.expected);
  for (const j of judged) items.push(rewrote ? { ...j, expected: true, reason: null, byAnswer: true } : j);
  const unexpected = items.filter((i) => !i.expected);
  return {
    file,
    items: items.length,
    expected: items.length - unexpected.length,
    throughAnswers: items.filter((i) => i.expected && i.byAnswer).length,
    unexpected: unexpected.map((i) => `${i.item}: ${i.reason}`),
    gate: unexpected.length === 0,
  };
}

/**
 * The harness (section 13): dollars and tokens of the run, the share of planned items the answer
 * cache answered, and, when their run records are given, the warm rerun's calls (gate: zero) and
 * the one-file change's, checked by `changeGate` when the changed file is given.
 */
export function harnessRow(run, warm = null, change = null, changedFile = null) {
  if (!run) return notMeasured('no run.json given');
  const t = totals(run);
  const row = {
    measured: true,
    costUsd: Math.round((Number(run.costUsd ?? t.costUsd) || t.costUsd) * 10000) / 10000,
    calls: run.calls ?? t.calls,
    tokens: { input: t.input, output: t.output, cacheRead: t.cacheRead, cacheWrite: t.cacheWrite },
    items: t.planned,
    cacheHitRate: ratio(t.cached, t.planned),
  };
  if (warm) {
    const w = totals(warm);
    row.warm = { calls: warm.calls ?? w.calls, costUsd: Number(warm.costUsd ?? w.costUsd), gate: (warm.calls ?? w.calls) === 0 };
  } else {
    row.warm = notMeasured('no warm rerun given (--warm <run.json>)');
  }
  row.oneFileChange = !change
    ? notMeasured('no one-file change run given (--change <run.json>)')
    : {
        calls: change.calls ?? totals(change).calls,
        ...(changedFile
          ? changeGate(change, changedFile)
          : { gate: null, why: 'the changed file was not given (--change-file <repo>:<path>)' }),
      };
  return row;
}

/** A seeded pseudo-random number generator, so a comparison gives the same interval every time. */
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 2 ** 32;
  };
}

/**
 * The paired comparison of two runs on the same items (section 13): the mean of the per-item
 * difference (b − a), in points, with a 95% bootstrap interval, and its direction per set.
 *
 * @param pairs `{ set, a, b }` per item scored in both runs
 * @param resamples how many bootstrap resamples
 */
export function pairedDifference(pairs, resamples = 2000, seed = 662) {
  if (pairs.length === 0) return { items: 0, mean: null, low: null, high: null, bySet: {} };
  const diffs = pairs.map((p) => p.b - p.a);
  const mean = (xs) => xs.reduce((s, x) => s + x, 0) / xs.length;
  const next = random(seed);
  const means = [];
  for (let k = 0; k < resamples; k++) {
    let sum = 0;
    for (let i = 0; i < diffs.length; i++) sum += diffs[Math.floor(next() * diffs.length)];
    means.push(sum / diffs.length);
  }
  means.sort((x, y) => x - y);
  const points = (x) => Math.round(x * 1000) / 10;
  const bySet = {};
  for (const set of new Set(pairs.map((p) => p.set))) {
    const d = mean(pairs.filter((p) => p.set === set).map((p) => p.b - p.a));
    bySet[set] = d > 0 ? 'up' : d < 0 ? 'down' : 'same';
  }
  const low = points(means[Math.floor(0.025 * resamples)]);
  const high = points(means[Math.ceil(0.975 * resamples) - 1]);
  const directions = Object.values(bySet);
  const against = (dir) => directions.filter((d) => d !== dir).length;
  return {
    items: pairs.length,
    mean: points(mean(diffs)),
    low,
    high,
    bySet,
    // Improves when the interval excludes 0 and all sets but at most one agree; does not regress
    // when the interval's lower bound is above −7 points (section 13).
    improves: low > 0 && against('up') <= 1,
    regresses: low <= -7,
  };
}

/** The rows per role that score items, as the scorecard keeps them. */
export const SCORED_ROWS = ['resolve', 'describe', 'flows', 'selection', 'investigate'];

/** The pairs of item scores two scorecards share, for one row. */
export function itemPairs(a, b, row) {
  const sa = a.scores?.roles?.[row]?.scores;
  const sb = b.scores?.roles?.[row]?.scores;
  if (!sa || !sb) return [];
  return Object.keys(sa)
    .filter((id) => id in sb)
    .map((id) => ({ set: a.set, id, a: sa[id], b: sb[id] }));
}

/** The categories a risk or decoy of an answer key is of: the lead kinds, two finding kinds, other. */
export const RISK_CATEGORIES = [
  'identity-from-request',
  'send-before-commit',
  'contract-mismatch',
  'weak-access-check',
  'shared-type-skew',
  'single-instance',
  'plaintext-secret',
  'environment-baked',
  'open-cors',
  'schema-unmanaged',
  'version-skew',
  'other',
];

/** A citation as an answer key writes it: repo:path:start-end. */
export const CITE = /^([^:\s]+):([^:\s]+):(\d+)-(\d+)$/;

/** Every entry of an answer key that cites code, with where it sits, for the checks below. */
export function citingEntries(key) {
  return [
    ...(key.resolve ?? []).map((e, i) => ({ where: `resolve[${i}]`, entry: e })),
    ...(key.risks ?? []).map((e, i) => ({ where: `risks[${i}]`, entry: e })),
    ...(key.decoys ?? []).map((e, i) => ({ where: `decoys[${i}]`, entry: e })),
  ];
}

/**
 * What is wrong with an answer key's form, without its code (no checkout needed): its header, each
 * entry's citations, names and category, and its subjects among the reference's elements (when
 * the reference is given: project-8's sits in its checkout).
 */
export function answerKeyIssues(key, reference) {
  const issues = [];
  if (!/agent/i.test(key.preparedBy ?? '')) issues.push('preparedBy must say who prepared it');
  if (!key.status) issues.push('status must say whether the owner has checked it');
  if (!key.commits || Object.keys(key.commits).length === 0) issues.push('commits must name each repository\'s commit');
  for (const { where, entry } of citingEntries(key)) {
    const cites = entry.cites ?? [];
    if (cites.length === 0) issues.push(`${where}: cites nothing`);
    for (const cite of cites) {
      const m = String(cite).match(CITE);
      if (!m) issues.push(`${where}: ${cite} is not repo:path:start-end`);
      else if (!(m[1] in (key.commits ?? {}))) issues.push(`${where}: ${cite} names a repository without a commit`);
      else if (Number(m[3]) < 1 || Number(m[4]) < Number(m[3]) || Number(m[4]) - Number(m[3]) >= 40) {
        issues.push(`${where}: ${cite} is not a range of 1 to 40 lines`);
      }
    }
    if ((entry.names ?? []).length === 0) issues.push(`${where}: names nothing to check the citation by`);
    if (!where.startsWith('resolve')) {
      if (!RISK_CATEGORIES.includes(entry.category)) issues.push(`${where}: category ${entry.category} is not one of the list`);
      for (const subject of entry.subjects ?? []) {
        if (reference && !(subject in reference.elements)) {
          issues.push(`${where}: subject ${subject} is not an element of the reference`);
        }
      }
      if ((entry.subjects ?? []).length === 0) issues.push(`${where}: names no subject`);
    } else if (entry.answer === undefined) {
      issues.push(`${where}: has no answer`);
    }
  }
  return issues;
}

/**
 * What is wrong with an answer key's citations against the code: each cited file at the key's
 * commit of its repository, in the checkout, holds the cited lines, and they hold one of the
 * entry's names.
 *
 * @param checkout the set's checkout, one git repository a folder named as the key's repositories
 */
export function answerKeyCodeIssues(key, checkout) {
  const issues = [];
  const files = new Map();
  const show = (repo, path) => {
    const k = `${repo}:${path}`;
    if (!files.has(k)) {
      try {
        files.set(
          k,
          execFileSync('git', ['-C', join(checkout, repo), 'show', `${key.commits[repo]}:${path}`], {
            encoding: 'utf8',
            stdio: ['ignore', 'pipe', 'ignore'],
            maxBuffer: 64 * 1024 * 1024,
          }).split('\n'),
        );
      } catch {
        files.set(k, null);
      }
    }
    return files.get(k);
  };
  for (const { where, entry } of citingEntries(key)) {
    const cited = [];
    for (const cite of entry.cites ?? []) {
      const m = String(cite).match(CITE);
      if (!m) continue;
      const lines = show(m[1], m[2]);
      if (lines === null) {
        issues.push(`${where}: ${cite}: no such file at ${m[1]}@${key.commits[m[1]]}`);
        continue;
      }
      if (Number(m[4]) > lines.length) {
        issues.push(`${where}: ${cite}: the file has ${lines.length} lines`);
        continue;
      }
      cited.push(lines.slice(Number(m[3]) - 1, Number(m[4])).join('\n'));
    }
    const text = cited.join('\n');
    if (cited.length > 0 && !(entry.names ?? []).some((name) => text.includes(name))) {
      issues.push(`${where}: none of ${JSON.stringify(entry.names)} is in its cited lines`);
    }
  }
  return issues;
}
