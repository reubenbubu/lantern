// The scoring rows per LLM role (scripts/roles.mjs; docs/adr/0072-scoring-harness.md, amendment for
// #667): the three-outcome score with abstention, the flow match rule and the paired comparison.
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
import {
  answerKeyIssues,
  describeRow,
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

test('Describe: elements with a responsibility, components by their anchor type, lines labelled by an intent', () => {
  const report = {
    elements: {
      'service-a': { kind: 'container', name: 'a' },
      'type-x-y': { kind: 'component', name: 'Y' },
      'service-b': { kind: 'container', name: 'b' },
      'person-u': { kind: 'person', name: 'User' },
    },
    pages: [
      {
        sections: [
          {
            type: 'diagram',
            relationships: [
              { from: 'service-a', to: 'service-b', label: 'Sends orders to' },
              { from: 'service-b', to: 'service-a', label: 'Calls' },
              { from: 'person-u', to: 'service-a', label: 'Uses' },
            ],
          },
        ],
      },
    ],
  };
  const llm = [
    { predicate: 'responsibility', subject: 'service:a', attributes: {} },
    { predicate: 'responsibility', subject: 'component:service:a/type:x.Y', attributes: {} },
    { predicate: 'intent', subject: 'service:a', attributes: { text: 'Sends orders to [fact:abc]' } },
  ];
  const row = describeRow(report, [], llm);
  assert.equal(row.elementsDescribed, 2);
  assert.equal(row.elements, 4);
  assert.equal(row.interactions, 2);
  assert.equal(row.interactionsIntended, 1);
});
