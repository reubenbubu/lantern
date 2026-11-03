// The response contracts on the scorecard (scripts/contracts.mjs; docs/adr/0118-response-contracts.md):
// each size rule split three ways, and each judged text's 0 to 10 quality score from weighted
// yes/no checks, with the points lost.
import assert from 'node:assert/strict';
import { test } from 'vitest';
import { accepted, contractLines, qualityRows, readContracts, readQuality, sentences, sizeRows, words } from '../scripts/contracts.mjs';

const contracts = readContracts();
const quality = readQuality();

const intent = (subject, text) => ({ subject, predicate: 'intent', attributes: { text } });

test('the accepted size is the requested one plus 15%, at least one unit, as the checks compute it', () => {
  assert.equal(accepted(22), 25);
  assert.equal(accepted(7), 8);
  assert.equal(accepted(3), 4);
  assert.equal(accepted(30), 35);
  const describe = contracts['describe-units'].fields.find((f) => f.name === 'responsibility');
  assert.equal(describe.words.requested, 22);
  assert.equal(describe.words.accepted, 25);
  // A margin decided before contracts is the contract's own.
  assert.equal(contracts['flow-narrative'].fields.find((f) => f.name === 'walkthrough').words.accepted, 80);
});

test('a size rule counts texts within the requested size, beyond it, and rejected for their length', () => {
  const llm = [
    intent('component:service:a/type:A', 'Verifies the organization before adding a file'), // 7: within
    intent('component:service:a/type:B', 'Reads the stored invoice totals for each customer'), // 8: beyond
    intent('group:service:a/area/x', 'Notify users of new tasks'), // a group's line: name-groups
  ];
  const rejections = [
    { role: 'describe-unit', kind: 'rejected', attempt: 1, reasons: ['interactions.R2: Has 11 words; the limit is 8.'] },
    // Its repair is rejected again; then the item is dropped. Each is counted once, apart.
    { role: 'describe-unit', kind: 'rejected', attempt: 2, reasons: ['interactions.R2: Has 10 words; the limit is 8.'] },
    { role: 'describe-unit', kind: 'dropped', attempt: 0, reasons: ['interactions.R2: Has 10 words; the limit is 8.'] },
    { role: 'describe-unit', kind: 'rejected', attempt: 1, reasons: ['interactions.R3: Names Kafka, which none of its citations show.'] },
    // A rule that stores no texts still shows its rejections, as a count.
    { role: 'resolve', kind: 'rejected', attempt: 1, reasons: ['reason: Has 40 words; the limit is 35.'] },
  ];
  const rows = sizeRows(contracts, llm, rejections);
  const row = rows.find((r) => r.role === 'describe-units' && r.field === 'intent');
  assert.deepEqual(
    { within: row.within, beyond: row.beyond, rejected: row.rejected, again: row.rejectedAgain, total: row.total },
    { within: 1, beyond: 1, rejected: 1, again: 1, total: 2 },
  );
  assert.equal(rows.find((r) => r.role === 'name-groups' && r.field === 'intent').within, 1);
  const md = contractLines(rows, null, 7).join('\n');
  assert.match(md, /\| describe-units \| intent \| 7 \| 8 \| 2 \| 50% \| 50% \| 1, 1 \|/);
  assert.match(md, /\| resolve \| reason \| 30 \| 35 \| 0 \| - \| - \| 1, 0 \|/);
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
    ...judged('Issues signed access tokens for the gateway', 'pass', 'pass'), // 10
    ...judged('Issues signed access tokens for the gateway', 'pass', 'pass').map((j) => ({ ...j, fact: 'twin' })), // 10
    ...judged('It is responsible for various things', 'pass', 'fail'), // loses specific, opening, filler
    ...judged('Stores the data of the system in tables', 'fail', 'pass'), // gate fails: at most 4
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
