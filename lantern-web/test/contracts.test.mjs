// The response contracts on the scorecard (scripts/contracts.mjs; docs/adr/0118-response-contracts.md):
// each size rule split three ways, and each judged text's 0 to 10 quality score from weighted
// yes/no checks, with the points lost.
import assert from 'node:assert/strict';
import { test } from 'vitest';
import { accepted, contractLines, qualityRows, readContracts, readQuality, sentences, sizeRows, words } from '../scripts/contracts.mjs';

const contracts = readContracts();
const quality = readQuality();

const intent = (subject, text) => ({ subject, predicate: 'intent', attributes: { text } });

test('the accepted range is the requested one widened by 15% each side, at least one unit, as the checks compute it', () => {
  assert.deepEqual(accepted([10, 22]), [8, 25]);
  assert.deepEqual(accepted([4, 7]), [3, 8]);
  assert.deepEqual(accepted([30, 40]), [25, 46]);
  assert.deepEqual(accepted([1, 3]), [1, 4]);
  assert.deepEqual(accepted([0, 10], 0.15, 0), [0, 12]);
  const describe = contracts['describe-units'].fields.find((f) => f.name === 'responsibility');
  assert.deepEqual(describe.words.requested, [10, 22]);
  assert.deepEqual(describe.words.accepted, [8, 25]);
  // A margin decided before contracts is the contract's own.
  assert.deepEqual(contracts['flow-narrative'].fields.find((f) => f.name === 'walkthrough').words.accepted, [25, 80]);
});

test('every size rule of every contract has a requested and an accepted range', () => {
  for (const c of Object.values(contracts)) {
    for (const f of c.fields ?? []) {
      for (const rule of [f.words, f.sentences].filter(Boolean)) {
        assert.ok(Array.isArray(rule.requested) && rule.requested.length === 2, `${c.role} ${f.name}`);
        assert.ok(rule.accepted[0] <= rule.requested[0] && rule.accepted[1] >= rule.requested[1], `${c.role} ${f.name}`);
      }
    }
  }
});

test('a size rule counts texts too short and too long apart: within, accepted outside, rejected', () => {
  const llm = [
    intent('component:service:a/type:A', 'Verifies the organization before adding a file'), // 7: within 4 to 7
    intent('component:service:a/type:B', 'Reads the stored invoice totals for each customer'), // 8: accepted long
    intent('component:service:a/type:C', 'Sends invoices'), // 2: below 3, only kept by an older run
    intent('component:service:a/type:D', 'Verifies each order'), // 3: accepted short
    intent('group:service:a/area/x', 'Notify users of new tasks today'), // a group's line: name-groups
  ];
  const rejections = [
    { role: 'describe-unit', kind: 'rejected', attempt: 1, reasons: ['interactions.R2: Has 11 words; the accepted range is 3 to 8 words, so say it in fewer words.'] },
    // Its repair is rejected again, now too short; then the item is dropped. Each is counted once, apart.
    { role: 'describe-unit', kind: 'rejected', attempt: 2, reasons: ['interactions.R2: Has 2 words; the accepted range is 3 to 8 words, so say more.'] },
    { role: 'describe-unit', kind: 'dropped', attempt: 0, reasons: ['interactions.R2: Has 2 words; the accepted range is 3 to 8 words, so say more.'] },
    { role: 'describe-unit', kind: 'rejected', attempt: 1, reasons: ['interactions.R4: Has 1 words; the accepted range is 3 to 8 words, so say more.'] },
    { role: 'describe-unit', kind: 'rejected', attempt: 1, reasons: ['interactions.R3: Names Kafka, which none of its citations show.'] },
    // A rule that stores no texts still shows its rejections; a run before ranges said "the limit is".
    { role: 'resolve', kind: 'rejected', attempt: 1, reasons: ['reason: Has 40 words; the limit is 35.'] },
  ];
  const rows = sizeRows(contracts, llm, rejections);
  const row = rows.find((r) => r.role === 'describe-units' && r.field === 'intent');
  assert.deepEqual(
    {
      within: row.within,
      acceptedShort: row.acceptedShort,
      acceptedLong: row.acceptedLong,
      outside: [row.outsideShort, row.outsideLong],
      rejectedShort: [row.rejectedShort, row.rejectedShortAgain],
      rejectedLong: [row.rejectedLong, row.rejectedLongAgain],
      total: row.total,
    },
    { within: 1, acceptedShort: 1, acceptedLong: 1, outside: [1, 0], rejectedShort: [1, 1], rejectedLong: [1, 0], total: 4 },
  );
  assert.equal(rows.find((r) => r.role === 'name-groups' && r.field === 'intent').within, 1);
  const md = contractLines(rows, null, 7).join('\n');
  assert.match(md, /\| describe-units \| intent \| words \| 4 to 7 \| 3 to 8 \| 4 \| 25% \| 25% \| 25% \| 1, 0 \| 1, 1 \| 1, 0 \|/);
  assert.match(md, /\| resolve \| reason \| words \| 8 to 30 \| 7 to 35 \| 0 \| - \| - \| - \| 0, 0 \| 0, 0 \| 1, 0 \|/);
});

test('words and sentences are counted as the checks count them', () => {
  assert.equal(words('  Sends the order  to billing '), 5);
  assert.equal(sentences('Takes orders. Sends them to billing for invoicing.'), 2);
  assert.equal(sentences('Uses v1.2 of the API.'), 1);
});

/** Both verdicts of one judged text. */
function judged(text, role, specific, subject = 'component:service:a/type:A', predicate = 'responsibility') {
  const base = { fact: text, subject, predicate, role: 'describe-unit', text, state: 'judged' };
  return [
    { ...base, dimension: 'role', verdict: role },
    { ...base, dimension: 'specific', verdict: specific },
  ];
}

test('the quality score sums the weights of the checks passed and caps a text that fails its gate', () => {
  const wording = [
    ...judged('Issues signed access tokens for the gateway to check on each request', 'pass', 'pass'), // 10
    ...judged('Issues signed access tokens for the gateway to check on each request', 'pass', 'pass').map((j) => ({ ...j, fact: 'twin' })), // 10
    ...judged('It is responsible for various things across the whole of the system', 'pass', 'fail'), // loses specific, opening, filler
    ...judged('Stores the data of the system in tables for each of its services', 'fail', 'pass'), // gate fails: at most 4
  ];
  const [row] = qualityRows(contracts, quality, wording);
  assert.equal(row.role, 'describe-units');
  assert.equal(row.texts, 4);
  assert.equal(row.capped, 1);
  // 10 + 10 + (10 - 2 - 1 - 1) + min(7, 4) = 30, over 4 texts.
  assert.equal(row.mean, 7.5);
  assert.equal(row.lost[0].id, 'does');
  assert.deepEqual(
    row.lost.map((l) => l.id).sort(),
    ['does', 'filler', 'opening', 'specific'],
  );
  const md = contractLines([], [row], 7).join('\n');
  assert.match(md, /\| describe-units \| 4 \| 7.5\/10 \| 1 \|/);
});

test('without the judge or the run files, the section says what is not measured', () => {
  assert.equal(sizeRows(contracts, null, null), null);
  assert.equal(qualityRows(contracts, quality, null), null);
  const md = contractLines(null, null, 7).join('\n');
  assert.match(md, /Not measured: the run left no llm\.jsonl\.gz/);
  assert.match(md, /Not measured: the run was not judged/);
});
