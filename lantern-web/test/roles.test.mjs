// The scoring rows per LLM role (scripts/roles.mjs; docs/adr/0072-scoring-harness.md, amendment for
// #667): the three-outcome score with abstention, the flow match rule and the paired comparison.
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
import {
  answerKeyIssues,
  describeRow,
  isGenericLabel,
  harnessRow,
  investigateRow,
  matchFlows,
  pairedDifference,
  reportId,
  resolveRow,
  threeOutcomes,
} from '../scripts/roles.mjs';
import { pairedRows } from '../scripts/score.mjs';

test('three outcomes: a wrong answer costs p, an abstention nothing, not asked is apart', () => {
  const row = threeOutcomes(
    [
      { id: 'a', outcome: 'correct' },
      { id: 'b', outcome: 'correct' },
      { id: 'c', outcome: 'wrong' },
      { id: 'd', outcome: 'abstained' },
      { id: 'e', outcome: 'correct', unknown: true, abstainedRight: true },
      { id: 'f', outcome: 'notAsked' },
    ],
    3,
  );
  assert.equal(row.items, 5);
  assert.equal(row.notAsked, 1);
  // e abstained where the reference says unknown: correct, but not an answer.
  assert.equal(row.coverage, 3 / 5);
  assert.equal(row.accuracy, 2 / 3);
  assert.equal(row.penalized, (3 - 3 * 1) / 5);
  // Of the four items the reference answers, one was abstained on.
  assert.equal(row.overAbstention, 1 / 4);
  assert.deepEqual(row.scores, { a: 1, b: 1, c: -3, d: 0, e: 1 });
  assert.equal('f' in row.scores, false);
});

test('Resolve: a pick is right or wrong, an unknown abstention is right, a pick against unknown is wrong', () => {
  const key = {
    resolve: [
      { link: 'L1', answer: 'service:a', candidates: ['service:a', 'service:b'] },
      { link: 'L2', answer: 'service:a', candidates: ['service:a', 'service:b'] },
      { link: 'L3', answer: 'unknown', candidates: ['service:a', 'service:b'] },
      { link: 'L4', answer: 'unknown', candidates: ['service:a', 'service:b'] },
      { link: 'L5', answer: 'service:b', candidates: ['service:a', 'service:b'] },
      { link: 'L6', answer: 'service:b', candidates: [] },
      { link: 'L7', answer: 'service:a', candidates: ['service:a'] },
      { link: 'L8', answer: ['service:a', 'service:b'], candidates: ['service:a', 'service:b'] },
    ],
  };
  const pick = (link, target) => ({
    origin: { kind: 'llm', name: 'resolve' },
    predicate: 'CALLS_HTTP',
    object: { kind: 'entity', value: target },
    attributes: { resolves: link },
  });
  const linked = [pick('L1', 'service:a'), pick('L2', 'service:b'), pick('L4', 'service:a'), pick('L8', 'service:b')];
  const run = {
    harness: [
      { role: 'resolve', items: { planned: 7 }, askedSubjects: ['L1', 'L2', 'L3', 'L4', 'L5', 'L8'], unaskedSubjects: ['L7'] },
    ],
  };
  const row = resolveRow(key, linked, [], run);
  assert.equal(row.correct, 3); // L1, L3 abstained where the key says unknown, L8 one of two
  assert.equal(row.wrong, 2); // L2 picked the other, L4 picked where the key says unknown
  assert.equal(row.abstained, 1); // L5
  assert.equal(row.notAsked, 2); // L6 has no candidates, L7 was not asked for budget
  assert.equal(row.penalty, 3);
  assert.equal(row.penalized, (3 - 3 * 2) / 6);
  // Picks: L1, L2, L4 and L8; L3's right abstention is no answer.
  assert.equal(row.coverage, 4 / 6);
  assert.equal(row.accuracy, 2 / 4);
});

test('flows match by trigger and two thirds of the larger participant set, one to one, best first', () => {
  const ref = [
    { title: 'Order', trigger: 'POST /orders', participants: ['web', 'orders', 'db'] },
    { title: 'Pay', trigger: 'POST /pay', participants: ['web', 'pay', 'bank', 'db'] },
    { title: 'Order again', trigger: 'POST /orders', participants: ['web', 'orders', 'mq'] },
  ];
  const rep = [
    // 2 shared of 3: matches Order (2/3), and Order again equally (web, orders); the better pair wins.
    { title: 'r1', trigger: 'POST /orders', participants: ['web', 'orders', 'mq'] },
    // 3 shared over the larger set of 5: 0.6, below two thirds.
    { title: 'r2', trigger: 'POST /pay', participants: ['web', 'pay', 'bank', 'cache', 'log'] },
    { title: 'r3', trigger: 'POST /orders', participants: ['web', 'orders', 'db'] },
  ];
  const matches = matchFlows(ref, rep).map((m) => `${ref[m.i].title} = ${rep[m.j].title}`);
  assert.deepEqual(matches.sort(), ['Order = r3', 'Order again = r1']);
});

test('the paired comparison is the same every time and claims an improvement only when it may', () => {
  const pairs = [];
  for (let i = 0; i < 60; i++) pairs.push({ set: i % 2 ? 'p1' : 'p2', a: 0, b: i % 3 === 0 ? 0 : 1 });
  const first = pairedDifference(pairs);
  assert.deepEqual(pairedDifference(pairs), first);
  assert.equal(first.mean, 66.7);
  assert.ok(first.low > 0 && first.high <= 100);
  assert.deepEqual(first.bySet, { p1: 'up', p2: 'up' });
  assert.equal(first.improves, true);
  assert.equal(first.regresses, false);

  const mixed = pairedDifference([
    { set: 'p1', a: 1, b: 0 },
    { set: 'p1', a: 0, b: 1 },
    { set: 'p2', a: 1, b: 1 },
  ]);
  assert.equal(mixed.improves, false);
});

test('two scorecards pair their item scores row by row', () => {
  const card = (scores) => ({ set: 'p1', scores: { roles: { resolve: { scores } } } });
  const rows = pairedRows([[card({ L1: 0, L2: -3, L3: 1 }), card({ L1: 1, L2: 1, L4: 1 })]]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].row, 'resolve');
  assert.equal(rows[0].items, 2);
  assert.equal(rows[0].mean, 250);
});

test('Investigate: risks match by subject and category; decoys count against; the rest is unjudged', () => {
  const key = {
    risks: [
      { reference: 'Headers trusted', subjects: ['gateway'], category: 'identity-from-request' },
      { reference: 'Events before commit', subjects: ['main'], category: 'send-before-commit' },
    ],
    decoys: [{ looksLike: 'A password in the code', subjects: ['auth'], category: 'plaintext-secret' }],
  };
  const risk = (unit, lead, title) => ({
    predicate: 'investigated-risk',
    subject: unit,
    attributes: { units: [unit], lead, title, finding: title },
  });
  const llm = [
    risk('service:gw', 'lead:identity-from-request/service:gw/header:X', 'Gateway trusts X'),
    risk('service:auth', 'lead:plaintext-secret/service:auth/x', 'A password in config'),
    risk('service:files', 'lead:open-cors/service:files', 'Any origin allowed'),
  ];
  const subjects = { 'service:gw': 'gateway', 'service:main': 'main', 'service:auth': 'auth', 'service:files': 'files' };
  const row = investigateRow(key, llm, (e) => subjects[e] ?? null, () => 0, 0.5);
  assert.equal(row.matched, 1);
  assert.equal(row.decoysFlagged, 1);
  assert.equal(row.recall, 1 / 2);
  assert.equal(row.precision, 1 / 2);
  assert.equal(row.tprMinusFpr, 1 / 2 - 1);
  assert.deepEqual(row.unjudged, ['Any origin allowed']);
  assert.deepEqual(row.missing, ['Events before commit']);

  // A decoy of the same subject and category as a risk: the words decide which one a find is.
  const both = {
    risks: [{ reference: 'Events published before the order commits', subjects: ['main'], category: 'send-before-commit' }],
    decoys: [{ looksLike: 'The outbox relay sends inside a transaction', names: ['OutboxRelay'], subjects: ['main'], category: 'send-before-commit' }],
  };
  const words = (t) => new Set(t.toLowerCase().split(/\W+/).filter((w) => w.length >= 4));
  const share = (a, b) => {
    const x = words(a);
    const y = words(b);
    return [...x].filter((w) => y.has(w)).length / Math.max(1, Math.min(x.size, y.size));
  };
  const relay = investigateRow(
    both,
    [risk('service:main', 'lead:send-before-commit/service:main/x', 'OutboxRelay sends inside a transaction')],
    (e) => subjects[e] ?? null,
    share,
    0.5,
  );
  assert.equal(relay.decoysFlagged, 1);
  assert.equal(relay.matched, 0);
});

test('the harness row reads the run record, and the warm rerun gate needs zero calls', () => {
  const run = {
    calls: 4,
    costUsd: '0.5',
    harness: [{ role: 'r', calls: 4, tokens: { input: 10, output: 2, cacheRead: 5, cacheWrite: 1 }, items: { planned: 4, cached: 1 } }],
  };
  const row = harnessRow(run, { calls: 0, harness: [] }, null);
  assert.equal(row.cacheHitRate, 1 / 4);
  assert.equal(row.warm.gate, true);
  assert.equal(row.oneFileChange.measured, false);
  assert.equal(reportId('service:com.crm:file'), 'service-com-crm-file');
});

// Every committed answer key is well formed: who prepared it and its status, each repository's
// commit, every entry citing repo:path:start-end with names to check it by, a category of the list
// and subjects among the reference's elements. Its citations are checked against the code with
// `score.mjs check-key` where the set is checked out.
const testsets = fileURLToPath(new URL('../../testsets/', import.meta.url));
for (const set of readdirSync(testsets).filter((s) => existsSync(`${testsets}${s}/answer-key.json`))) {
  test(`the answer key of ${set} is well formed`, () => {
    const key = JSON.parse(readFileSync(`${testsets}${set}/answer-key.json`, 'utf8'));
    const referencePath = `${testsets}${set}/reference/reference.c4.json`;
    const reference = existsSync(referencePath) ? JSON.parse(readFileSync(referencePath, 'utf8')) : null;
    assert.deepEqual(answerKeyIssues(key, reference), []);
  });
}

test('Resolve: a failed link is not asked, a cached one without a pick is an abstention', () => {
  const key = {
    resolve: [
      { link: 'F', answer: 'service:a', candidates: ['service:a'] },
      { link: 'C', answer: 'service:a', candidates: ['service:a'] },
    ],
  };
  // A failed item is listed as unasked only (RoleRecord), a cached one as cached.
  const run = { harness: [{ role: 'resolve', items: { planned: 2 }, askedSubjects: [], unaskedSubjects: ['F'], cachedSubjects: ['C'] }] };
  const row = resolveRow(key, [], [], run);
  assert.equal(row.notAsked, 1);
  assert.equal(row.abstained, 1);
});

test('Investigate: a wording of a fixed check\'s finding is of the key\'s category for its kind', () => {
  const key = {
    risks: [{ id: 'r1', reference: 'One signing key', subjects: ['gateway'], category: 'plaintext-secret' }],
    decoys: [],
  };
  const llm = [
    {
      predicate: 'finding-wording',
      subject: 'finding:shared-jwt-secret/x',
      attributes: { units: ['service:gw'], title: 'Every unit signs with one key' },
    },
  ];
  const row = investigateRow(key, llm, (e) => (e === 'service:gw' ? 'gateway' : null), () => 0, 0.5);
  assert.equal(row.matched, 1);
  assert.deepEqual(row.scores, { r1: 1 });
  // No decoys: TPR − FPR cannot be told.
  assert.equal(row.tprMinusFpr, null);
});

test('Describe: elements with a responsibility, components by their anchor type', () => {
  const report = {
    elements: {
      'service-a': { kind: 'container', name: 'a' },
      'type-x-y': { kind: 'component', name: 'Y', parent: 'service-a' },
      'service-b': { kind: 'container', name: 'b' },
      'person-u': { kind: 'person', name: 'User' },
    },
    pages: [],
  };
  const llm = [
    { predicate: 'responsibility', subject: 'service:a', attributes: {} },
    { predicate: 'responsibility', subject: 'component:service:a/type:x.Y', attributes: {} },
  ];
  const row = describeRow(report, [], llm);
  assert.equal(row.elementsDescribed, 2);
  assert.equal(row.elements, 4);
  assert.deepEqual(row.undescribed.sort(), ['User', 'b']);
});

/** A fact of the linker. */
const link = (id, predicate, subject, object, attributes = {}) => ({
  id: `f-${id}`,
  claim: `c-${id}`,
  predicate,
  subject,
  object: { kind: 'entity', value: object },
  attributes,
});

/** An intent Describe (or another role) wrote for the link of the given claim. */
const intentFor = (claim, text, role = 'describe-unit') => ({
  predicate: 'intent',
  subject: 'service:a',
  object: { kind: 'string', value: claim },
  attributes: { text },
  origin: { kind: 'llm', name: role },
});

// Units a and b; a has components X and Y; c is a unit Describe did not ask about.
const linked = [
  link('ab', 'CALLS_HTTP', 'service:a', 'service:b'),
  link('ab2', 'PUBLISHES', 'service:a', 'queue:orders'),
  link('ba', 'CALLS_HTTP', 'service:b', 'service:a'),
  link('xy', 'COMPONENT_LINK', 'component:service:a/type:p.X', 'component:service:a/type:p.Y', { kind: 'calls' }),
  link('end', 'COMPONENT_LINK', 'component:service:a/type:p.X', 'component:service:a/type:p.Z', { kind: 'code-end' }),
  link('cb', 'CALLS_HTTP', 'service:c', 'service:b'),
  link('cx', 'READS_TABLE', 'service:a', 'table:t'),
  { id: 'p1', predicate: 'PART_OF', subject: 'component:service:a/type:p.X', object: { kind: 'entity', value: 'service:a' } },
  { id: 'p2', predicate: 'PART_OF', subject: 'component:service:a/type:p.Y', object: { kind: 'entity', value: 'service:a' } },
  { id: 'p3', predicate: 'PART_OF', subject: 'component:service:a/type:p.Z', object: { kind: 'entity', value: 'service:a' } },
  { id: 'p4', predicate: 'PART_OF', subject: 'component:service:b/type:q.W', object: { kind: 'entity', value: 'service:b' } },
  // A line to another unit's component is not one Describe words.
  link('xw', 'COMPONENT_LINK', 'component:service:a/type:p.X', 'component:service:b/type:q.W', { kind: 'calls' }),
  // a's call to b's sandbox holds by default; under the profile prod it calls b's live host instead,
  // an alternative Describe leaves out.
  link('sandbox', 'CALLS_HTTP', 'service:a', 'external-system:sandbox', { absentIn: ['prod'], alternates: ['external-system:live'] }),
  link('live', 'CALLS_HTTP', 'service:a', 'external-system:live', { view: 'prod', alternates: ['external-system:sandbox'] }),
];
const run = {
  harness: [{ role: 'describe-unit', askedSubjects: ['service:a'], cachedSubjects: ['service:b'], unaskedSubjects: ['service:c'] }],
};

test('Describe in the model: each interaction right, generic (wrong), "don\'t know" or not asked', () => {
  const llm = [
    intentFor('c-ab', 'Looks up prices [fact:1]'), // right
    intentFor('c-xy', 'Calls validate'), // a generic opening: wrong
    intentFor('c-ab2', 'Announces new orders', 'name-groups'), // not Describe's: "don't know"
    // Two intents, one generic: right.
    intentFor('c-sandbox', 'Uses the sandbox'),
    intentFor('c-sandbox', 'Charges cards in the sandbox'),
    // b -> a has none: "don't know"; c -> b: c was not asked; the live call is an alternative.
  ];
  const row = describeRow({ elements: {}, pages: [] }, linked, llm, run);
  assert.deepEqual(
    { ...row.model, genericItems: undefined },
    {
      interactions: 5,
      own: 2,
      generic: 1,
      unknown: 2,
      notAsked: 1,
      coverage: 0.4,
      penalized: 0.2,
      penalty: 1,
      genericItems: undefined,
    },
  );
  assert.deepEqual(row.model.genericItems, ['Calls validate']);
  assert.equal(row.scores['interaction:f-ab'], 1);
  assert.equal(row.scores['interaction:f-xy'], -1);
  assert.equal(row.scores['interaction:f-sandbox'], 1);
  assert.equal(row.scores['interaction:f-live'], undefined);
  assert.equal(row.scores['interaction:f-xw'], undefined);
  // Without the run's record of Describe, the model cannot be scored.
  assert.equal(describeRow({ elements: {}, pages: [] }, linked, llm, null).model.measured, false);
});

test('Describe on the page: a line counts when labelled with the intent of its own link', () => {
  const report = {
    elements: {
      'service-a': { kind: 'container', name: 'a' },
      'service-b': { kind: 'container', name: 'b' },
      'queue-orders': { kind: 'queue', name: 'orders' },
      'type-p-x': { kind: 'component', name: 'X', parent: 'service-a' },
      'type-p-y': { kind: 'component', name: 'Y', parent: 'service-a' },
      'person-u': { kind: 'person', name: 'User' },
    },
    pages: [
      {
        sections: [
          {
            type: 'diagram',
            relationships: [
              // Its own link's intent, with a profile note: counts.
              { from: 'service-a', to: 'service-b', label: 'Looks up prices (only with the Spring profile prod)' },
              // Another link's intent: does not count, and is not generic.
              { from: 'service-b', to: 'service-a', label: 'Looks up prices' },
              // A component's link lifted to its container: counts.
              { from: 'service-a', to: 'queue-orders', label: 'Announces new orders' },
              // The fallback label: generic.
              { from: 'type-p-x', to: 'type-p-y', label: 'Calls validate, check' },
              // A person's line keeps its fixed label and is left out.
              { from: 'person-u', to: 'service-a', label: 'Uses' },
            ],
          },
        ],
      },
      // The same line on another page, without its intent: still counted once, as its own.
      { sections: [{ type: 'diagram', relationships: [{ from: 'service-a', to: 'service-b', label: 'Calls get' }] }] },
    ],
  };
  const lifted = link('xq', 'PUBLISHES', 'component:service:a/type:p.X', 'queue:orders');
  const llm = [intentFor('c-ab', 'Looks up prices'), intentFor('c-xq', 'Announces new orders')];
  const row = describeRow(report, [...linked, lifted], llm, run);
  assert.deepEqual(row.drawn, { lines: 4, own: 2, generic: 1, other: 1, coverage: 0.5 });
  assert.equal(row.scores['line:service-a->service-b'], 1);
  assert.equal(row.scores['line:service-b->service-a'], 0);
});

test("Describe on the page: a consumer's line runs from what it reads, and a link may have several intents", () => {
  const report = {
    elements: {
      'service-a': { kind: 'container', name: 'a' },
      'service-b': { kind: 'container', name: 'b' },
      'exchange-events': { kind: 'queue', name: 'events', tag: 'Exchange' },
    },
    pages: [
      {
        sections: [
          {
            type: 'diagram',
            relationships: [
              // b consumes a queue bound to the events exchange, which the view draws instead.
              { from: 'exchange-events', to: 'service-b', label: 'Reads new orders to bill them' },
              // The same text drawn from b to the exchange is not its line.
              { from: 'service-b', to: 'exchange-events', label: 'Reads new orders to bill them' },
              // The second of the link's two intents.
              { from: 'service-a', to: 'service-b', label: 'Fetches current prices' },
            ],
          },
        ],
      },
    ],
  };
  const consumes = link('bq', 'CONSUMES', 'service:b', 'queue:orders');
  const llm = [
    intentFor('c-bq', 'Reads new orders to bill them'),
    intentFor('c-ab', 'Looks up prices'),
    intentFor('c-ab', 'Fetches current prices'),
  ];
  const row = describeRow(report, [...linked, consumes], llm, run);
  assert.equal(row.scores['line:exchange-events->service-b'], 1);
  assert.equal(row.scores['line:service-b->exchange-events'], 0);
  assert.equal(row.scores['line:service-a->service-b'], 1);
  assert.equal(row.model.own, 2);
});

test('a generic label is empty or opens with a verb that only says there is a relationship', () => {
  for (const t of ['', 'Uses', 'Uses, through the web app', 'Calls write, flush', 'Depends on b', 'Connects to the broker', 'Accesses data', 'Only with the Spring profile dev']) {
    assert.equal(isGenericLabel(t), true, t);
  }
  for (const t of ['Callbacks the payer', 'Useful lookups of prices', 'Looks up prices (only with the Spring profile dev)', 'Reads and writes']) {
    assert.equal(isGenericLabel(t), false, t);
  }
});
