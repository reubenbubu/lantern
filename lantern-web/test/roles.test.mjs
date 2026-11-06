// The scoring rows per LLM role (scripts/roles.mjs; docs/adr/0072-scoring-harness.md, amendment for
// #667): the three-outcome score with abstention, the flow match rule and the paired comparison.
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
import {
  answerKeyIssues,
  investigateIssues,
  describeRow,
  flowSelectionRow,
  isGenericLabel,
  changeGate,
  harnessRow,
  interactionsOf,
  connectionsRow,
  findingKey,
  fixedCheckRow,
  investigateRow,
  investigateSpread,
  repeatedRisks,
  unusedVerdicts,
  matchFlows,
  pairedDifference,
  reportId,
  resolveRow,
  threeOutcomes,
} from '../scripts/roles.mjs';
import { pairedRows } from '../scripts/score.mjs';
import { interactionKinds, readVocabulary } from '../scripts/vocabulary.mjs';

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
  assert.equal(row.notAsked, 1); // L7 was not asked for budget
  assert.equal(row.notItems, 1); // L6 has no candidates, so it was never an item
  assert.equal(row.failed, 0);
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

test('Investigate: a risk citing the lines of two reference risks it matches is credited for both (#747)', () => {
  // On project-8 the gateway's two contract mismatches are two reference risks; a risk gathering
  // the leads of both cites each one's lines.
  const key = {
    risks: [
      { id: 'r2', reference: 'Misspelt path', subjects: ['gateway', 'technician'], category: 'contract-mismatch', cites: ['gw:src/Overview.java:25-25'] },
      { id: 'r10', reference: 'Status path', subjects: ['gateway', 'workorder'], category: 'contract-mismatch', cites: ['gw:src/Status.java:40-42'] },
      { id: 'r11', reference: 'Other path', subjects: ['gateway'], category: 'contract-mismatch', cites: ['gw:src/Other.java:7-7'] },
    ],
  };
  const llm = [
    {
      predicate: 'investigated-risk',
      subject: 'service:gw',
      attributes: {
        units: ['service:gw'],
        lead: 'lead:contract-mismatch/service:gw/a',
        title: 'The gateway calls paths nobody serves',
        finding: 'Two calls miss.',
        cites: ['gw:src/Overview.java:20-30', 'gw:src/Status.java:41-41'],
      },
    },
  ];
  const row = investigateRow(key, llm, (e) => (e === 'service:gw' ? 'gateway' : null), () => 0, 0.5);
  assert.equal(row.matched, 2);
  assert.deepEqual(row.scores, { r2: 1, r10: 1, r11: 0 });
  // Precision still counts the drawn risk once.
  assert.equal(row.right, 1);
});

test('repeated risks: two risks of one lead kind with the same units are listed; fixed-check wordings are not (#747)', () => {
  const risk = (units, lead, title, predicate = 'investigated-risk') => ({
    predicate,
    subject: units[0],
    attributes: { units, lead, title },
  });
  const llm = [
    risk(['service:a'], 'lead:send-before-commit/service:a/x', 'Sends before commit'),
    risk(['service:a'], 'lead:send-before-commit/service:a/y', 'Mails before commit'),
    risk(['service:a', 'service:b'], 'lead:send-before-commit/service:a/z', 'Calls b before commit'),
    risk(['service:a'], 'lead:identity-from-request/service:a/h', 'Trusts a header'),
    risk(['service:a'], undefined, 'Found exploring'),
    risk(['service:a'], 'lead:plaintext-secret/service:a/s', 'One secret', 'finding-wording'),
    risk(['service:a'], 'lead:plaintext-secret/service:a/t', 'Another secret', 'finding-wording'),
  ];
  const row = repeatedRisks(llm);
  assert.equal(row.measured, true);
  assert.deepEqual(row.repeated, [{ kind: 'send-before-commit', units: ['service:a'], titles: ['Mails before commit', 'Sends before commit'] }]);
  assert.deepEqual(repeatedRisks(llm.slice(2)).repeated, []);
  // The units are the leads' own, in the stored value, not the ones the answer named: two risks
  // the join kept apart are not repeated even when the model named the same units for both.
  const keyed = (value, lead, title) => ({
    predicate: 'investigated-risk',
    subject: 'service:a',
    object: { kind: 'string', value },
    attributes: { units: ['service:a'], lead, title },
  });
  assert.deepEqual(
    repeatedRisks([
      keyed('identity-from-request service:a', 'lead:identity-from-request/service:a/x', 'Header'),
      keyed('identity-from-request service:a,service:b', 'lead:identity-from-request/service:a/y', 'Org header'),
    ]).repeated,
    [],
  );
  assert.equal(repeatedRisks(null).measured, false);
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

test('the one-file change gate: an item may be re-asked only for the changed file or a reworded answer', () => {
  const file = 'shop:src/A.java';
  const change = {
    calls: 6,
    harness: [
      {
        role: 'summarize-type',
        askedSubjects: ['type:A', 'type:B'],
        askedWhy: [
          { subject: 'type:A', files: [file] }, // its own file: expected
          { subject: 'type:B', files: ['shop:src/B.java'] }, // another file: not expected
        ],
      },
      {
        role: 'describe-unit',
        askedSubjects: ['service:shop', 'service:other', 'service:new', 'service:unknown'],
        askedWhy: [
          { subject: 'service:shop', files: [file], answers: 1 }, // the file and an answer: expected
          // Answers it shows, reworded by an item re-asked for the file: expected (#722).
          { subject: 'service:other', answers: 2, changedAnswers: ['summarize-type type:A'] },
          { subject: 'service:new', new: true }, // never asked before: not expected
          // service:unknown has no reason recorded: not expected
        ],
      },
      { role: 'purpose', askedSubjects: ['system'], askedWhy: [{ subject: 'system', answers: 1, prompt: true }] },
    ],
  };
  const row = harnessRow({ calls: 1, harness: [] }, null, change, file);
  assert.deepEqual(
    { items: row.oneFileChange.items, expected: row.oneFileChange.expected, through: row.oneFileChange.throughAnswers, gate: row.oneFileChange.gate },
    { items: 7, expected: 3, through: 1, gate: false },
  );
  assert.deepEqual(row.oneFileChange.unexpected, [
    'summarize-type type:B: inputs from shop:src/B.java changed',
    'describe-unit service:new: never asked before',
    'describe-unit service:unknown: no reason recorded',
    'purpose system: its prompt changed',
  ]);
  assert.equal(changeGate({ harness: [{ role: 'r', askedSubjects: ['x'], askedWhy: [{ subject: 'x', files: [file] }] }] }, file).gate, true);
  // Without the changed file only the calls are given.
  assert.equal(harnessRow({ harness: [] }, null, change).oneFileChange.gate, null);
});

test('the one-file change gate: retries, judges of reworded texts and answers that no longer hold', () => {
  const file = 'shop:src/A.java';
  const gate = (harness) => changeGate({ harness }, file);
  // A retry alone is judged by its item's reason; a subject listed twice counts once.
  const retried = gate([
    { role: 'investigate-leads', askedSubjects: ['lead:x', 'lead:x (again)', 'lead:x'], askedWhy: [{ subject: 'lead:x', files: [file] }, { subject: 'lead:x (again)', new: true }] },
  ]);
  assert.deepEqual({ items: retried.items, gate: retried.gate }, { items: 2, gate: true });
  // A judge's item on a new or changed text is expected once a writing role re-asked for the file...
  const judges = [{ role: 'support-judge', askedSubjects: ['text:1', 'text:2'], askedWhy: [{ subject: 'text:1', new: true }, { subject: 'text:2', other: 1 }] }];
  const withWriter = gate([{ role: 'summarize-type', askedSubjects: ['type:A'], askedWhy: [{ subject: 'type:A', files: [file] }] }, ...judges]);
  assert.deepEqual({ gate: withWriter.gate, through: withWriter.throughAnswers }, { gate: true, through: 2 });
  // ...and not when nothing was re-asked for it.
  assert.equal(gate(judges).gate, false);
  // A stored answer refused because a fact it cites no longer holds is named as such.
  assert.deepEqual(gate([{ role: 'resolve', askedSubjects: ['link:1'], askedWhy: [{ subject: 'link:1', unheld: true }] }]).unexpected, [
    'resolve link:1: a fact its stored answer cites no longer holds',
  ]);
});

test("the one-file change gate: an item re-asked through a shown answer is expected only when that answer's item was re-asked for the file (#722)", () => {
  const file = 'shop:src/A.java';
  const gate = (harness) => changeGate({ harness }, file);
  const type = { role: 'summarize-type', askedSubjects: ['type:A'], askedWhy: [{ subject: 'type:A', files: [file] }] };
  // The package shows the type's summary, which changed; the module shows the package's.
  const pkg = (changed) => ({ role: 'summarize', askedSubjects: ['package:p'], askedWhy: [{ subject: 'package:p', answers: 1, changedAnswers: changed }] });
  const module = { role: 'summarize', askedSubjects: ['module:m'], askedWhy: [{ subject: 'module:m', answers: 1, changedAnswers: ['summarize package:p'] }] };
  // Two levels, each through an answer re-asked in this run for the file: expected.
  const chain = gate([type, pkg(['summarize-type type:A']), module]);
  assert.deepEqual({ gate: chain.gate, through: chain.throughAnswers, unexpected: chain.unexpected }, { gate: true, through: 2, unexpected: [] });
  // A cascade from an answer this run did not re-ask for the file: both levels unexpected.
  const cascade = gate([type, pkg(['summarize-type type:B']), module]);
  assert.equal(cascade.gate, false);
  assert.deepEqual(cascade.unexpected, [
    'summarize package:p: a shown answer changed whose item was not re-asked for the file (summarize-type type:B)',
    'summarize module:m: a shown answer changed whose item was not re-asked for the file (summarize package:p)',
  ]);
  // A changed answer whose source is not recorded is not taken on trust.
  assert.deepEqual(gate([type, pkg(['?'])]).unexpected, ['summarize package:p: a shown answer changed whose source is not recorded']);
  // A ref naming a role alone is met by any item of that role re-asked for the file.
  assert.equal(gate([type, pkg(['summarize-type'])]).gate, true);
  // Alternatives: met by either.
  assert.equal(gate([type, pkg(['describe-unit type:A|summarize-type type:A'])]).gate, true);
  // A shown answer only removed: expected once something was re-asked for the file.
  const lost = { role: 'summarize', askedSubjects: ['package:q'], askedWhy: [{ subject: 'package:q', answers: 1 }] };
  assert.equal(gate([type, lost]).gate, true);
  assert.deepEqual(gate([lost]).unexpected, ['summarize package:q: a shown answer was removed though nothing was re-asked for the file']);
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

test('the investigate section of an answer key: one entry per lead, a verdict of the list, evidence, a risk only when confirmed', () => {
  const key = {
    commits: { web: 'abc' },
    risks: [{ id: 'r1' }],
    decoys: [{ id: 'd1' }],
    investigate: [
      { lead: 'lead:open-cors/x', kind: 'open-cors', verdict: 'confirmed', why: 'w', evidence: ['web:a.ts:1-3'], risk: 'r1' },
      { lead: 'lead:open-cors/y', kind: 'open-cors', verdict: 'refuted', why: 'w', evidence: ['web:a.ts:4-4'], decoy: 'd1' },
      { lead: 'lead:send-before-commit/z', kind: 'send-before-commit', verdict: 'unclear', scored: false, why: 'w', evidence: ['web:a.ts:5-5'] },
    ],
  };
  assert.deepEqual(investigateIssues(key), []);
  const bad = {
    ...key,
    investigate: [
      { lead: 'lead:open-cors/x', kind: 'contract-mismatch', verdict: 'refuted', why: '', evidence: ['other:a.ts:1-3', 'web:a.ts:9-2'], risk: 'r1' },
      { lead: 'lead:open-cors/x', kind: 'open-cors', verdict: 'unclear', why: 'w', evidence: [], decoy: 'd9' },
    ],
  };
  assert.deepEqual(investigateIssues(bad), [
    "investigate[0]: kind contract-mismatch is not its lead's",
    'investigate[0]: does not say why',
    'investigate[0]: other:a.ts:1-3 names a repository without a commit',
    'investigate[0]: web:a.ts:9-2 is not a range of 1 to 40 lines',
    'investigate[0]: names risk r1 but is not confirmed',
    'investigate[1]: lead:open-cors/x is keyed twice',
    'investigate[1]: an unclear verdict must be marked scored: false',
    'investigate[1]: cites no evidence',
    'investigate[1]: decoy d9 is not an entry of the key',
    'investigate[1]: names decoy d9 but is not refuted',
  ]);
});

test('Resolve: a failed link is counted apart from not asked, a cached one without a pick is an abstention', () => {
  const key = {
    resolve: [
      { link: 'F', answer: 'service:a', candidates: ['service:a'] },
      { link: 'C', answer: 'service:a', candidates: ['service:a'] },
    ],
  };
  // A failed item is listed as unasked and as failed (RoleRecord), a cached one as cached.
  const run = { harness: [{ role: 'resolve', items: { planned: 2 }, askedSubjects: [], unaskedSubjects: ['F'], failedSubjects: ['F'], cachedSubjects: ['C'] }] };
  const row = resolveRow(key, [], [], run);
  // Failed, counted apart from not asked (#734).
  assert.equal(row.failed, 1);
  assert.equal(row.notAsked, 0);
  assert.equal(row.abstained, 1);
  // An older run.json with no failed list counts it as not asked.
  const older = { harness: [{ ...run.harness[0], failedSubjects: undefined }] };
  assert.equal(resolveRow(key, [], [], older).notAsked, 1);
});

test('Investigate: a wording of a fixed check\'s finding is of the key\'s category for its kind, in its own row', () => {
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
  const row = fixedCheckRow(key, llm, (e) => (e === 'service:gw' ? 'gateway' : null), () => 0, 0.5);
  assert.equal(row.matched, 1);
  assert.deepEqual(row.scores, { r1: 1 });
  // Investigate's own row leaves it out: the finding is the check's (#748).
  const own = investigateRow(key, llm, (e) => (e === 'service:gw' ? 'gateway' : null), () => 0, 0.5);
  assert.equal(own.found, 0);
  assert.equal(own.matched, 0);
  assert.deepEqual(own.scores, { r1: 0 });
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

// The boxes the views draw for the fixtures above: an interaction counts when both its ends are drawn.
const modelReport = {
  elements: Object.fromEntries(
    ['service-a', 'service-b', 'service-c', 'queue-orders', 'external-system-sandbox', 'external-system-live', 'type-p-x', 'type-p-y'].map(
      (id) => [id, { kind: 'container', name: id }],
    ),
  ),
  pages: [],
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
  const row = describeRow(modelReport, linked, llm, run);
  assert.deepEqual(
    { ...row.model, genericItems: undefined, byKind: undefined },
    {
      // ab, ab2, ba, xy, cb and sandbox; not the table nobody draws, the live alternative, the
      // line to b's component or the code end.
      interactions: 6,
      own: 2,
      generic: 1,
      unknown: 2,
      notAsked: 1,
      // Not asked stays in the denominator: 2 of 6.
      coverage: 2 / 6,
      // A generic intent costs a point, over the five Describe was asked about.
      penalized: 0.2,
      penalty: 1,
      genericItems: undefined,
      byKind: undefined,
    },
  );
  assert.deepEqual(row.model.byKind, [
    { kind: 'CALLS_HTTP', interactions: 4, own: 2, generic: 0, unknown: 1, notAsked: 1 },
    { kind: 'COMPONENT_LINK', interactions: 1, own: 0, generic: 1, unknown: 0, notAsked: 0 },
    { kind: 'PUBLISHES', interactions: 1, own: 0, generic: 0, unknown: 1, notAsked: 0 },
  ]);
  assert.deepEqual(row.model.genericItems, ['Calls validate']);
  assert.equal(row.scores['interaction:f-ab'], 1);
  assert.equal(row.scores['interaction:f-xy'], -1);
  assert.equal(row.scores['interaction:f-sandbox'], 1);
  assert.equal(row.scores['interaction:f-live'], undefined);
  assert.equal(row.scores['interaction:f-xw'], undefined);
  // Without the run's record of Describe, the model cannot be scored.
  assert.equal(describeRow(modelReport, linked, llm, null).model.measured, false);
});

test('the interactions counted are every relationship type below INTERACTS_WITH in the vocabulary', () => {
  const vocabulary = readVocabulary();
  const kinds = interactionKinds(vocabulary);
  for (const k of ['CALLS_HTTP', 'CONSUMES', 'PUBLISHES', 'USES_DATABASE', 'ROUTES_TO', 'INVESTIGATED_CONNECTION']) {
    assert.ok(kinds.has(k), k);
  }
  // A structural relationship is not one.
  assert.equal(kinds.has('PART_OF'), false);
  // A kind the vocabulary gains is counted with no change to the scorer.
  const grown = new Map(vocabulary);
  grown.set('CALLS_GRPC', { name: 'CALLS_GRPC', kind: 'relationship', parent: 'CALLS' });
  const facts = [link('g', 'CALLS_GRPC', 'service:a', 'service:b')];
  assert.deepEqual(interactionsOf(facts, modelReport, vocabulary).map((i) => i.kind), []);
  assert.deepEqual(interactionsOf(facts, modelReport, grown).map((i) => i.kind), ['CALLS_GRPC']);
});

test("the interactions counted include components' data access, found connections and people's uses", () => {
  const report = {
    elements: {
      ...modelReport.elements,
      'database-orders': { kind: 'container', name: 'orders' },
      'external-system-zipkin': { kind: 'external-system', name: 'Zipkin' },
      'person-admin': { kind: 'person', name: 'Admin' },
      'frontend-shop': { kind: 'container', name: 'Shop' },
    },
    pages: [],
  };
  const facts = [
    ...linked,
    link('db', 'USES_DATABASE', 'service:a', 'database:orders'),
    { id: 'f-da', claim: 'c-da', predicate: 'data-access', subject: 'component:service:a/type:p.Y', object: { kind: 'string', value: 'Reads and writes' } },
    link('zip', 'INVESTIGATED_CONNECTION', 'service:b', 'external-system:zipkin'),
    { id: 'f-pb', claim: 'c-pb', predicate: 'person-basis', subject: 'person:admin', object: { kind: 'string', value: 'role' }, attributes: { frontends: ['shop'] } },
    link('fs', 'PART_OF', 'frontend:shop', 'service:a'),
  ];
  const items = interactionsOf(facts, report);
  const byId = Object.fromEntries(items.map((i) => [i.id, i]));
  assert.equal(byId['interaction:f-da:database:orders'].kind, 'data-access');
  assert.deepEqual(byId['interaction:f-da:database:orders'].owners, ['service:a']);
  assert.equal(byId['interaction:f-zip'].kind, 'INVESTIGATED_CONNECTION');
  assert.equal(byId['interaction:f-pb:frontend:shop'].kind, 'USES (person)');
  assert.deepEqual(byId['interaction:f-pb:frontend:shop'].owners, ['frontend:shop']);
  // Scored: the data access has no intent though a was asked; zipkin's b was asked too.
  const row = describeRow(report, facts, [], run);
  const kinds = Object.fromEntries(row.model.byKind.map((k) => [k.kind, k]));
  assert.equal(kinds['data-access'].unknown, 1);
  assert.equal(kinds.INVESTIGATED_CONNECTION.unknown, 1);
  assert.equal(kinds['USES (person)'].notAsked, 1);
  // The unit's intent for the store counts for its component's access, and a person's intent is
  // the one stored for that frontend (#741).
  const intent = (object) => ({ predicate: 'intent', origin: { name: 'describe-unit' }, object: { kind: 'string', value: object }, attributes: { text: 'Keeps the orders' } });
  const worded = describeRow(report, facts, [intent('c-db'), intent('c-pb/frontend:shop')], run);
  const after = Object.fromEntries(worded.model.byKind.map((k) => [k.kind, k]));
  assert.equal(after['data-access'].own, 1);
  assert.equal(after['USES (person)'].own, 1);
});

test("Describe's model: a line the unknowns page gives a unit is that unit's, whatever its subject (#741)", () => {
  const report = {
    elements: Object.fromEntries(
      ['service-a', 'service-b', 'actor-unknown-caller', 'exchange-events', 'queue-orders'].map((id) => [id, { kind: 'container', name: id }]),
    ),
    pages: [],
  };
  const facts = [
    { id: 'u-a', predicate: 'deployable-unit', subject: 'service:a', object: { kind: 'string', value: 'a' } },
    { id: 'u-b', predicate: 'deployable-unit', subject: 'service:b', object: { kind: 'string', value: 'b' } },
    // The Unknown caller's line: held by the unit it calls, a.
    link('uc', 'CALLS_UNREACHED', 'actor:unknown-caller', 'service:a'),
    // An exchange's route to a queue: held by the first unit with a line touching either end, b.
    link('pub', 'PUBLISHES', 'service:b', 'exchange:events'),
    link('rt', 'ROUTES_TO', 'exchange:events', 'queue:orders'),
  ];
  const asked = { harness: [{ role: 'describe-unit', askedSubjects: ['service:a', 'service:b'], cachedSubjects: [], unaskedSubjects: [] }] };
  const items = interactionsOf(facts, report);
  const byId = Object.fromEntries(items.map((i) => [i.id, i]));
  assert.deepEqual(byId['interaction:f-uc'].owners, ['service:a']);
  assert.deepEqual(byId['interaction:f-rt'].owners, ['service:b']);
  // Left unworded, both are "don't know", as the page lists them under "Intent not known".
  const kinds = Object.fromEntries(describeRow(report, facts, [], asked).model.byKind.map((k) => [k.kind, k]));
  assert.equal(kinds.CALLS_UNREACHED.unknown, 1);
  assert.equal(kinds.ROUTES_TO.unknown, 1);
});

test("Describe's model: a folded queue's consumer is drawn at the exchange, so it can hold the exchange's route (#741)", () => {
  // queue-audit is not drawn: its consumer a is drawn from the exchange, as the page draws it.
  const report = {
    elements: Object.fromEntries(
      ['service-a', 'service-z', 'exchange-events', 'queue-orders'].map((id) => [id, { kind: 'container', name: id }]),
    ),
    pages: [],
  };
  const facts = [
    { id: 'u-a', predicate: 'deployable-unit', subject: 'service:a', object: { kind: 'string', value: 'a' } },
    { id: 'u-z', predicate: 'deployable-unit', subject: 'service:z', object: { kind: 'string', value: 'z' } },
    link('rt', 'ROUTES_TO', 'exchange:events', 'queue:orders'),
    link('ra', 'ROUTES_TO', 'exchange:events', 'queue:audit'),
    link('zc', 'CONSUMES', 'service:z', 'queue:orders'),
    link('ac', 'CONSUMES', 'service:a', 'queue:audit'),
  ];
  const byId = Object.fromEntries(interactionsOf(facts, report).map((i) => [i.id, i]));
  // a's line touches the exchange and a comes first by ID, as UnitInteractions.otherLines finds.
  assert.deepEqual(byId['interaction:f-rt'].owners, ['service:a']);
  assert.equal(byId['interaction:f-ra'], undefined);
});

test("Describe's model: a push a flow draws to a unit's STOMP subscribers is the unit's (#738)", () => {
  const report = { elements: { 'service-a': { kind: 'container', name: 'a' } }, pages: [] };
  const push = { id: 'f-push', claim: 'c-push', predicate: 'stomp-send', subject: 'method:p.A#tell()', object: { kind: 'string', value: '/topic/orders' } };
  const step = (id, facts) => ({
    id,
    predicate: 'STEP_OF',
    subject: `flow-step:${id}`,
    object: { kind: 'entity', value: 'flow:service:a/x' },
    attributes: { action: 'stomp-push', kind: 'note', over: ['service:a'], facts },
  });
  const facts = [
    { id: 'u-a', predicate: 'deployable-unit', subject: 'service:a', object: { kind: 'string', value: 'a' } },
    push,
    // Two flows draw the same push: one interaction.
    step('s1', ['f-push', 'f-ws']),
    step('s2', ['f-push']),
  ];
  const pushes = interactionsOf(facts, report).filter((i) => i.kind === 'stomp-send');
  assert.deepEqual(
    pushes.map(({ id, owners, keys }) => ({ id, owners, keys })),
    [{ id: 'interaction:f-push:service:a', owners: ['service:a'], keys: ['c-push', 'f-push'] }],
  );
  const asked = { harness: [{ role: 'describe-unit', askedSubjects: ['service:a'], cachedSubjects: [], unaskedSubjects: [] }] };
  const kinds = (llm) => Object.fromEntries(describeRow(report, facts, llm, asked).model.byKind.map((k) => [k.kind, k]));
  assert.equal(kinds([])['stomp-send'].unknown, 1);
  const intent = { predicate: 'intent', origin: { name: 'describe-unit' }, object: { kind: 'string', value: 'c-push' }, attributes: { text: 'Tells browsers about new orders' } };
  assert.equal(kinds([intent])['stomp-send'].own, 1);
});

test("Describe's model: a unit asked in parts is asked, and an interaction a refused part may hold is not asked (#741)", () => {
  const report = { elements: { 'service-a': { kind: 'container', name: 'a' }, 'service-b': { kind: 'container', name: 'b' } }, pages: [] };
  const facts = [link('ab', 'CALLS_HTTP', 'service:a', 'service:b')];
  const record = (asked, unasked) => ({ harness: [{ role: 'describe-unit', askedSubjects: asked, cachedSubjects: [], unaskedSubjects: unasked }] });
  const outcome = (run) => describeRow(report, facts, [], run).model;
  // Only its second part asked: a was asked.
  assert.equal(outcome(record(['service:a#2'], [])).unknown, 1);
  // Part 1 asked, part 2 refused: the unworded call may be part 2's, so it is not asked.
  const split = outcome(record(['service:a'], ['service:a#2']));
  assert.equal(split.unknown, 0);
  assert.equal(split.notAsked, 1);
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
              // A person's line with no stored intent for the person: generic, not intended (#739).
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
  assert.deepEqual(row.drawn, { lines: 5, own: 2, generic: 2, genericWithIntent: 0, other: 1, coverage: 0.4 });
  assert.equal(row.scores['line:service-a->service-b'], 1);
  assert.equal(row.scores['line:service-b->service-a'], 0);
});

test("Describe on the page: a line worded as a whole counts only when it reads the line's intent, not one link's (#810)", () => {
  const report = (label) => ({
    elements: {
      'service-a': { kind: 'container', name: 'a' },
      'service-b': { kind: 'container', name: 'b' },
    },
    pages: [{ sections: [{ type: 'diagram', relationships: [{ from: 'service-a', to: 'service-b', label }] }] }],
  });
  const more = [link('ab3', 'CALLS_HTTP', 'service:a', 'service:b'), link('ab4', 'CALLS_HTTP', 'service:a', 'service:b')];
  const requests = [intentFor('c-ab', 'Looks up prices'), intentFor('c-ab3', 'Reserves stock for orders'), intentFor('c-ab4', 'Cancels a reservation')];
  const line = intentFor('service:a|service:b|CALLS_HTTP', 'Prices and reserves stock for orders', 'describe-element');
  const own = (label, llm) => describeRow(report(label), [...linked, ...more], llm, run).scores['line:service-a->service-b'];
  // Without a stored line intent, one link's intent counts, as before.
  assert.equal(own('Looks up prices', requests), 1);
  // With one, a link's intent no longer stands for the whole line; the line's own intent does.
  assert.equal(own('Looks up prices', [...requests, line]), 0);
  assert.equal(own('Prices and reserves stock for orders', [...requests, line]), 1);
  // Another kind of link between the same ends keeps its own intent.
  const published = link('abp', 'PUBLISHES', 'service:a', 'service:b');
  const ownWith = (label, llm) => describeRow(report(label), [...linked, ...more, published], llm, run).scores['line:service-a->service-b'];
  assert.equal(ownWith('Announces price changes', [...requests, line, intentFor('c-abp', 'Announces price changes')]), 1);
  // A flow's step is one request: it keeps reading that request's intent.
  const flow = {
    elements: report('x').elements,
    pages: [{ sections: [{ type: 'sequence', id: 'f1', steps: [{ from: 'service-a', to: 'service-b', message: 'Looks up prices' }] }] }],
  };
  const row = describeRow(flow, [...linked, ...more], [...requests, line], run);
  assert.equal(row.scores['step:f1/service-a->service-b#0'], 1);
});

test('Describe on the page: a generic label on a line whose link has a stored intent is counted apart (#739)', () => {
  const report = {
    elements: {
      'service-a': { kind: 'container', name: 'a' },
      'service-b': { kind: 'container', name: 'b' },
    },
    pages: [{ sections: [{ type: 'diagram', relationships: [{ from: 'service-a', to: 'service-b', label: 'Calls get' }] }] }],
  };
  const row = describeRow(report, linked, [intentFor('c-ab', 'Looks up prices')], run);
  assert.deepEqual(row.drawn, { lines: 1, own: 0, generic: 1, genericWithIntent: 1, other: 0, coverage: 0 });
  // Without a stored intent, the generic label is not counted among them.
  assert.equal(describeRow(report, linked, [], run).drawn.genericWithIntent, 0);
});

test('Describe on the sequence diagrams: a step reading a fallback though its link has an intent is counted apart (#740)', () => {
  const sequence = (id, steps) => ({ type: 'sequence', id, participants: ['service-a', 'service-b'], steps });
  const report = {
    elements: {
      'service-a': { kind: 'container', name: 'a' },
      'service-b': { kind: 'container', name: 'b' },
      'service-c': { kind: 'container', name: 'c' },
      'person-u': { kind: 'person', name: 'User' },
    },
    pages: [
      {
        sections: [
          sequence('flow-1', [
            { note: 'A note is not a step', over: ['service-a'] },
            // Its link has an intent, and the step reads the fallback: counted apart.
            { from: 'service-a', to: 'service-b', message: 'Calls X.get', code: 'GET /prices' },
            // It reads its link's intent: own.
            { from: 'service-a', to: 'service-b', message: 'Looks up prices', code: 'GET /prices' },
            // No worded link between its ends: drawn, not intended.
            { from: 'service-c', to: 'service-b', message: 'Calls Y.get' },
            // A person's step with no stored intent for the person: drawn, not intended (#739).
            { from: 'person-u', to: 'service-a', message: 'Uses the screens' },
          ]),
        ],
      },
    ],
  };
  const row = describeRow(report, linked, [intentFor('c-ab', 'Looks up prices')], run);
  assert.deepEqual(row.steps, { messages: 4, intended: 2, own: 1, fallbackWithIntent: 1 });
  assert.equal(row.scores['step:flow-1/service-a->service-b#0'], 0);
  assert.equal(row.scores['step:flow-1/service-a->service-b#1'], 1);
  assert.equal(row.scores['step:flow-1/service-c->service-b#0'], undefined);
});

test('Describe on the sequence diagrams: a step drawn on two diagrams counts twice (#740)', () => {
  const step = { from: 'service-a', to: 'service-b', message: 'Calls X.get' };
  const report = {
    elements: { 'service-a': { kind: 'container', name: 'a' }, 'service-b': { kind: 'container', name: 'b' } },
    pages: [
      { sections: [{ type: 'sequence', id: 'flow-1', steps: [step] }] },
      { sections: [{ type: 'sequence', id: 'flow-2', steps: [step] }] },
    ],
  };
  const row = describeRow(report, linked, [intentFor('c-ab', 'Looks up prices')], run);
  assert.deepEqual(row.steps, { messages: 2, intended: 2, own: 0, fallbackWithIntent: 2 });
});

test("Describe on the page: a person's line reads the intent stored for the person's use of that frontend (#739)", () => {
  const report = {
    elements: {
      'person-planner': { kind: 'person', name: 'Planner' },
      'frontend-shop': { kind: 'container', name: 'shop' },
      'system-fleet': { kind: 'system', name: 'Fleet' },
    },
    pages: [
      {
        sections: [
          {
            type: 'diagram',
            relationships: [
              // The fallback, though an intent is stored for the planner's use of shop.
              { from: 'person-planner', to: 'frontend-shop', label: 'Uses the screens' },
              // The context view: one of the planner's intents, for any of their frontends.
              { from: 'person-planner', to: 'system-fleet', label: 'Plans technician routes' },
            ],
          },
        ],
      },
      {
        sections: [
          {
            type: 'sequence',
            id: 'flow-1',
            steps: [{ from: 'person-planner', to: 'frontend-shop', message: 'Uses the screens' }],
          },
        ],
      },
    ],
  };
  const facts = [
    { id: 'f-pb', claim: 'c-pb', predicate: 'person-basis', subject: 'person:planner', object: { kind: 'string', value: 'route' }, attributes: { frontends: ['shop'] } },
    { id: 'f-fs', predicate: 'PART_OF', subject: 'frontend:shop', object: { kind: 'entity', value: 'service:a' } },
  ];
  const intent = intentFor('c-pb/frontend:shop', 'Plans technician routes');
  const row = describeRow(report, facts, [intent], run);
  assert.deepEqual(row.drawn, { lines: 2, own: 1, generic: 1, genericWithIntent: 1, other: 0, coverage: 0.5 });
  assert.equal(row.scores['line:person-planner->system-fleet'], 1);
  assert.deepEqual(row.steps, { messages: 1, intended: 1, own: 0, fallbackWithIntent: 1 });

  // Labelled with the intent, the frontend's line and the step are its own.
  for (const r of report.pages[0].sections[0].relationships) r.label = 'Plans technician routes';
  report.pages[1].sections[0].steps[0].message = 'Plans technician routes';
  const worded = describeRow(report, facts, [intent], run);
  assert.equal(worded.drawn.own, 2);
  assert.equal(worded.drawn.genericWithIntent, 0);
  assert.deepEqual(worded.steps, { messages: 1, intended: 1, own: 1, fallbackWithIntent: 0 });
});

test("Describe on the page: a person's line to one frontend never reads the intent stored for another, and a step to a person is left out (#739)", () => {
  const report = {
    elements: {
      'person-planner': { kind: 'person', name: 'Planner' },
      'frontend-shop': { kind: 'container', name: 'shop' },
      'frontend-desk': { kind: 'container', name: 'desk' },
    },
    pages: [
      {
        sections: [
          {
            type: 'diagram',
            relationships: [
              // It reads desk's intent, though the line runs to shop, which has none.
              { from: 'person-planner', to: 'frontend-shop', label: 'Dispatches jobs' },
              { from: 'person-planner', to: 'frontend-desk', label: 'Dispatches jobs' },
            ],
          },
          {
            type: 'sequence',
            id: 'flow-1',
            steps: [
              { from: 'person-planner', to: 'frontend-desk', message: 'Dispatches jobs' },
              // A reply to the person: left out.
              { from: 'frontend-desk', to: 'person-planner', message: 'Shows the job list' },
            ],
          },
        ],
      },
    ],
  };
  const facts = [
    { id: 'f-pb', claim: 'c-pb', predicate: 'person-basis', subject: 'person:planner', object: { kind: 'string', value: 'route' }, attributes: { frontends: ['shop', 'desk'] } },
    { id: 'f-fs', predicate: 'PART_OF', subject: 'frontend:shop', object: { kind: 'entity', value: 'service:a' } },
    { id: 'f-fd', predicate: 'PART_OF', subject: 'frontend:desk', object: { kind: 'entity', value: 'service:a' } },
  ];
  const row = describeRow(report, facts, [intentFor('c-pb/frontend:desk', 'Dispatches jobs')], run);
  assert.equal(row.scores['line:person-planner->frontend-desk'], 1);
  assert.notEqual(row.scores['line:person-planner->frontend-shop'], 1);
  assert.equal(row.drawn.own, 1);
  assert.deepEqual(row.steps, { messages: 1, intended: 1, own: 1, fallbackWithIntent: 0 });
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
  // The queue is folded into the exchange that routes to it: b's consumption is drawn from the
  // exchange, and the route itself is no line of its own.
  const routes = link('eq', 'ROUTES_TO', 'exchange:events', 'queue:orders');
  const llm = [
    intentFor('c-bq', 'Reads new orders to bill them'),
    intentFor('c-ab', 'Looks up prices'),
    intentFor('c-ab', 'Fetches current prices'),
  ];
  const row = describeRow(report, [...linked, consumes, routes], llm, run);
  assert.equal(row.scores['line:exchange-events->service-b'], 1);
  assert.equal(row.scores['line:service-b->exchange-events'], 0);
  assert.equal(row.scores['line:service-a->service-b'], 1);
  assert.equal(row.model.own, 2);
  assert.equal(row.scores['interaction:f-bq'], 1);
  assert.equal(row.scores['interaction:f-eq'], undefined);
});

test('a generic label is empty or opens with a verb that only says there is a relationship', () => {
  for (const t of ['', 'Uses', 'Uses, through the web app', 'Calls write, flush', 'Depends on b', 'Connects to the broker', 'Accesses data', 'Only with the Spring profile dev']) {
    assert.equal(isGenericLabel(t), true, t);
  }
  for (const t of ['Callbacks the payer', 'Useful lookups of prices', 'Looks up prices (only with the Spring profile dev)', 'Reads and writes']) {
    assert.equal(isGenericLabel(t), false, t);
  }
});

/** A request spelled as the scorecard spells a trigger, here only upper-cased methods. */
const spell = (t) => t.replace(/^(\w+)\s+/, (m, v) => `${v.toUpperCase()} `);

/** The facts of one traced flow started at an endpoint: its entry point's fact and the flow. */
function flowFacts(name, request, extra = []) {
  return [
    { id: `e-${name}`, claim: `entry-${name}`, predicate: 'endpoint', subject: `method:C#${name}()`, object: { kind: 'string', value: request }, attributes: {} },
    { id: `s-${name}`, predicate: 'flow-shape', subject: `flow:service:a/entry-${name}`, object: { kind: 'string', value: 'x' }, attributes: {} },
    ...extra,
  ];
}

const verdict = (name, scope, significant) => ({
  predicate: 'flow-significance',
  subject: `flow:service:a/entry-${name}`,
  object: { kind: 'string', value: scope },
  attributes: { significant },
});

const selectionLinked = [
  ...flowFacts('order', 'POST /api/orders'),
  // Started through the gateway, whose path the reference names.
  ...flowFacts('pay', 'POST /api/payments', [
    { id: 'r1', predicate: 'routed-endpoint', subject: 'service:gw', object: { kind: 'string', value: 'POST /pay/api/payments' }, attributes: { endpoint: 'method:C#pay()' } },
  ]),
  // Started by the browser, whose call the reference names.
  ...flowFacts('login', 'GET /bff/{path}', [
    { id: 'h1', predicate: 'CALLS_HTTP', subject: 'frontend:web', object: { kind: 'entity', value: 'service:bff' }, attributes: { httpMethod: 'GET', path: '/bff/login', reaches: 'method:C#login()' } },
  ]),
  ...flowFacts('list', 'GET /api/orders'),
  ...flowFacts('health', 'GET /health'),
];
const selectionRef = [
  { title: 'Place an order', trigger: 'POST /api/orders' },
  { title: 'Pay', trigger: 'POST /pay/api/payments' },
  { title: 'Sign in', trigger: 'GET /bff/login' },
  { title: 'Stream updates', trigger: 'stomp' },
];

test("flow selection, judged: each reference flow among the candidates is right when chosen, wrong when not", () => {
  const llm = [
    verdict('order', 'system', true),
    verdict('pay', 'system', false), // a reference flow left out: wrong
    verdict('login', 'system', true), // started by the browser's call
    verdict('health', 'system', true), // chosen, in no reference flow: unjudged, lowers precision
    verdict('list', 'service:a', true), // a unit's verdict: a candidate, not the system's choice
  ];
  const row = flowSelectionRow(selectionRef, [], llm, selectionLinked, spell);
  assert.equal(row.mode, 'judged');
  assert.deepEqual(
    { candidates: row.candidates, chosen: row.chosen, items: row.items, right: row.right, chosenMatching: row.chosenMatching },
    { candidates: 5, chosen: 3, items: 3, right: 2, chosenMatching: 2 },
  );
  assert.equal(row.precision, 2 / 3);
  assert.equal(row.penalized, (2 - 1) / 3);
  assert.deepEqual(row.missed, ['Pay']);
  assert.deepEqual(row.notCandidates, ['Stream updates (stomp)']);
  assert.deepEqual(row.scores, { 'POST /api/orders#0': 1, 'POST /pay/api/payments#1': -1, 'GET /bff/login#2': 1 });
});

test('flow selection: a flow a unit pass dropped is missed, and a flow of the same shape starts its twin', () => {
  const linked = [
    ...selectionLinked,
    // "refund" has the shape of "pay": one candidate, started by either request.
    ...flowFacts('refund', 'POST /api/refunds'),
    { id: 'same', predicate: 'SAME_SHAPE_AS', subject: 'flow:service:a/entry-refund', object: { kind: 'entity', value: 'flow:service:a/entry-pay' }, attributes: {} },
    // A frontend call known only by its URL.
    { id: 'h2', predicate: 'CALLS_HTTP', subject: 'frontend:web', object: { kind: 'entity', value: 'service:a' }, attributes: { httpMethod: 'POST', url: 'POST-ONLY-URL', reaches: 'method:C#order()' } },
  ];
  const llm = [
    verdict('order', 'service:a', false), // dropped in the unit's pass: never reached the system judge
    verdict('pay', 'system', true),
    verdict('login', 'system', false),
  ];
  const ref = [
    { title: 'Place an order', trigger: 'POST /api/orders' },
    { title: 'Refund', trigger: 'POST /api/refunds' },
    { title: 'Order by URL', trigger: 'POST POST-ONLY-URL' },
  ];
  const row = flowSelectionRow(ref, [], llm, linked, spell);
  assert.equal(row.candidates, 3);
  assert.deepEqual(row.missed, ['Place an order', 'Order by URL']);
  assert.equal(row.right, 1); // the refund, by its twin's verdict
  assert.deepEqual(row.notCandidates, []);
});

test("flow selection: one run's flows.json scores the model's choice and the rank's on the same candidates (#743)", () => {
  const flow = (name) => `flow:service:a/entry-${name}`;
  const selection = {
    scopes: [
      { scope: 'service-a', candidates: [], judged: null, rank: [], overlaps: [] },
      {
        scope: 'system',
        candidates: ['order', 'pay', 'login', 'list', 'health'].map(flow),
        judged: ['order', 'login', 'health'].map(flow),
        rank: ['order', 'pay', 'list'].map(flow),
        overlaps: [`${flow('order')} contains ${flow('pay')}`],
      },
    ],
  };
  const row = flowSelectionRow(selectionRef, [], [], selectionLinked, spell, selection);
  assert.equal(row.mode, 'judged');
  const pick = (r) => ({ candidates: r.candidates, chosen: r.chosen, right: r.right, items: r.items, chosenMatching: r.chosenMatching });
  // The model: order and login of the three reference flows among the candidates; health is extra.
  assert.deepEqual(pick(row.judged), { candidates: 5, chosen: 3, right: 2, items: 3, chosenMatching: 2 });
  assert.deepEqual(pick(row), pick(row.judged));
  // The rank on the same candidates: order and pay; list is extra.
  assert.deepEqual(pick(row.rank), { candidates: 5, chosen: 3, right: 2, items: 3, chosenMatching: 2 });
  assert.deepEqual(row.rank.missed, ['Sign in']);
  assert.deepEqual(row.judged.missed, ['Pay']);
  assert.deepEqual(row.overlaps, [`${flow('order')} contains ${flow('pay')}`]);
  // A scope with no judgement that holds is scored by its rank alone.
  const unjudged = { scopes: [{ ...selection.scopes[1], judged: null, overlaps: [] }] };
  const rankOnly = flowSelectionRow(selectionRef, [], [], selectionLinked, spell, unjudged);
  assert.equal(rankOnly.mode, 'rank');
  assert.equal(rankOnly.judged, null);
  assert.deepEqual(pick(rankOnly), pick(row.rank));
});

test('flow selection, rank fallback: without the system judge the chosen flows are those the report draws', () => {
  // Only units were judged; the system fell back to the rank, and the report draws two flows.
  const llm = [verdict('order', 'service:a', true), verdict('pay', 'service:a', true), verdict('list', 'service:a', false)];
  const row = flowSelectionRow(selectionRef, ['POST /api/orders', 'GET /api/orders', null], llm, selectionLinked, spell);
  assert.equal(row.mode, 'rank');
  assert.deepEqual({ candidates: row.candidates, chosen: row.chosen, items: row.items, right: row.right }, { candidates: 3, chosen: 2, items: 2, right: 1 });
  assert.equal(row.precision, 1 / 2);
  // With no judgement at all, every traced flow is a candidate.
  const none = flowSelectionRow(selectionRef, ['POST /api/orders'], [], selectionLinked, spell);
  assert.equal(none.candidates, 5);
  // Every traced flow, one per shape.
  assert.equal(none.right, 1);
  assert.equal(flowSelectionRow([], [], [], [], spell).measured, false);
  assert.equal(flowSelectionRow(selectionRef, [], null, selectionLinked, spell).measured, false);
});

test('Investigate: precision is over every drawn risk, a judgement deciding first (#748)', () => {
  const key = {
    risks: [{ id: 'r1', reference: 'Gateway trusts a header', subjects: ['gateway'], category: 'identity-from-request' }],
    decoys: [{ id: 'd1', looksLike: 'A secret in config', subjects: ['auth'], category: 'plaintext-secret' }],
  };
  const risk = (unit, lead, title) => ({
    predicate: 'investigated-risk',
    subject: unit,
    attributes: { units: [unit], lead, title, finding: title },
  });
  const subjects = { 'service:gw': 'gateway', 'service:auth': 'auth', 'service:files': 'files' };
  const llm = [
    risk('service:gw', 'lead:identity-from-request/service:gw/a', 'Gateway trusts X'),
    risk('service:gw', 'lead:identity-from-request/service:gw/b', 'Gateway trusts Y'),
    risk('service:auth', 'lead:plaintext-secret/service:auth/x', 'A password in config'),
    risk('service:files', 'lead:open-cors/service:files', 'Any origin allowed'),
    risk('service:files', undefined, 'Uploads are never scanned'),
  ];
  const before = investigateRow(key, llm, (e) => subjects[e] ?? null, () => 0, 0.5);
  // Two drawn risks hit r1, so both count: 2 right, 1 wrong, 2 unjudged.
  assert.equal(before.right, 2);
  assert.equal(before.wrong, 1);
  assert.equal(before.precision, 2 / 3);
  assert.deepEqual(before.unjudged, ['Any origin allowed', 'Uploads are never scanned']);

  // A broken scorer counting reference entries hit, as before #748, would read 1/2 here.
  assert.notEqual(before.precision, 1 / 2);

  const judgements = {
    findings: [
      { key: 'lead:open-cors/service:files', verdict: 'wrong' },
      { key: findingKey(llm[4]), verdict: 'right' },
      // A judgement overrides the key's match.
      { key: 'lead:identity-from-request/service:gw/b', verdict: 'wrong' },
    ],
  };
  assert.equal(findingKey(llm[4]), 'service:files | Uploads are never scanned');
  const after = investigateRow(key, llm, (e) => subjects[e] ?? null, () => 0, 0.5, judgements);
  assert.equal(after.right, 2);
  assert.equal(after.wrong, 3);
  assert.equal(after.byJudgement, 3);
  assert.deepEqual(after.unjudged, []);
  assert.equal(after.precision, 2 / 5);
  assert.equal(after.matched, 1);
});

test('Investigate connections: scored by their two ends, either way round, a judgement first (#748)', () => {
  const link = (from, to) => ({ predicate: 'INVESTIGATED_CONNECTION', subject: from, object: { kind: 'entity', value: to } });
  const subjects = { 'service:a': 'a', 'service:b': 'b', 'service:c': 'c', 'external-system:z': 'z' };
  const llm = [link('service:b', 'service:a'), link('service:a', 'service:c'), link('service:a', 'external-system:q'), link('service:c', 'external-system:z')];
  const row = connectionsRow(llm, ['a -> b', 'c -> z'], (e) => subjects[e] ?? null, {
    connections: [{ key: 'service:a -> service:c', verdict: 'wrong' }],
  });
  assert.equal(row.found, 4);
  assert.equal(row.right, 2);
  assert.equal(row.wrong, 1);
  assert.equal(row.matched, 2);
  assert.equal(row.precision, 2 / 3);
  assert.deepEqual(row.unjudged, ['service:a -> external-system:q']);
  // The reference's pairs, whichever way round: a -> b and b -> a are one.
  assert.equal(connectionsRow([], ['a -> b', 'b -> a', 'c -> z'], () => null).reference, 2);
  // A broken scorer matching one direction only would leave b -> a unjudged.
  assert.ok(!row.unjudged.includes('service:b -> service:a'));
  // A verdict whose key names nothing the run found is listed (#748).
  assert.deepEqual(
    unusedVerdicts({ connections: [{ key: 'service:a -> service:c', verdict: 'wrong' }, { key: 'service:x -> service:y', verdict: 'right' }] }, llm),
    ['service:x -> service:y'],
  );
});

test('Investigate spread: two runs of one set, apart in points (#748)', () => {
  const card = (recall, precision) => ({ scores: { roles: { investigate: { measured: true, recall, precision } } } });
  const sp = investigateSpread(card(0.5, 0.8), card(0.75, 0.6));
  assert.equal(sp.recallSpread, 25);
  assert.equal(sp.precisionSpread, 20);
  assert.equal(investigateSpread(card(0.5, 0.8), { scores: { roles: {} } }).measured, false);
});
