// The wording judge's rows and label set (scripts/wording.mjs; docs/adr/0111-wording-judge.md):
// "not validated" until the owner's labels pass TPR and TNR of 0.8, then the corrected score with
// its interval; the label set drawn half passed and half failed, held-out labels apart.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { test } from 'vitest';
import { corrected, drawLabelFiles, drawLabels, judgedCell, judgedRows, readLabels, validation } from '../scripts/wording.mjs';

const JUDGE = 'openai/judge';

/** Judged lines of one dimension: `n` texts, the first `passes` passed. */
function lines(n, passes, dimension = 'role', extra = {}) {
  return Array.from({ length: n }, (_, i) => ({
    fact: `f${i}`,
    subject: `service:s${i}`,
    predicate: 'responsibility',
    role: 'describe-unit',
    kind: 'responsibility of a service',
    text: `Text number ${i}.`,
    words: 3,
    writer: 'anthropic/writer',
    judge: JUDGE,
    dimension,
    promptVersion: '1',
    state: 'judged',
    verdict: i < passes ? 'pass' : 'fail',
    ...extra,
  }));
}

/** Labels of one dimension: `pos` pass and `neg` fail held out, the judge right on `tp` and `tn`. */
function labels(pos, neg, tp, tn, judge = JUDGE) {
  const items = [];
  const verdicts = {};
  for (let i = 0; i < pos; i++) {
    items.push({ id: `p${i}`, split: 'test', label: 'pass' });
    verdicts[`p${i}`] = i < tp ? 'pass' : 'fail';
  }
  for (let i = 0; i < neg; i++) {
    items.push({ id: `n${i}`, split: 'test', label: 'fail' });
    verdicts[`n${i}`] = i < tn ? 'fail' : 'pass';
  }
  items.push({ id: 't0', split: 'tune', label: 'pass' });
  verdicts.t0 = 'fail';
  return { owner: { items }, judge: { judge, promptVersion: '1', verdicts } };
}

test('with fake answers and no labels, every judged row says not validated', () => {
  const wording = [
    ...lines(4, 4, 'role'),
    ...lines(4, 4, 'specific'),
    ...lines(2, 2, 'role', { role: 'purpose', predicate: 'key-fact', fact: 'k' }),
  ];
  const rows = judgedRows(wording, { role: null, specific: null });

  assert.equal(rows.describe.role.validated, false);
  assert.match(rows.describe.role.why, /no owner labels yet/);
  assert.equal(rows.describe.role.raw, 1);
  assert.equal(rows.describe.role.meanWords, 3);
  assert.equal(rows.purpose.role.validated, false);
  assert.equal(rows.nameGroups.role.measured, false);
  assert.match(judgedCell(rows.describe), /^role \(says what the element does for the system\): not validated/);
});

test('a run that did not judge wording says so', () => {
  const rows = judgedRows(null);
  assert.equal(rows.describe.measured, false);
  assert.match(judgedCell(rows.purpose), /did not judge wording/);
});

test('validated labels give the corrected score with an interval', () => {
  const wording = lines(50, 30);
  const rows = judgedRows(wording, { role: labels(20, 20, 18, 17), specific: null });
  const r = rows.describe.role;

  assert.equal(r.validated, true);
  assert.equal(r.tpr, 0.9);
  assert.equal(r.tnr, 0.85);
  assert.equal(r.raw, 0.6);
  // θ = (0.6 + 0.85 − 1) / (0.9 + 0.85 − 1) = 0.45 / 0.75.
  assert.ok(Math.abs(r.corrected - 0.6) < 1e-9);
  assert.ok(r.low < r.corrected && r.corrected < r.high);
  // The tuning label is not counted.
  assert.equal(r.labelled, 40);
  assert.match(judgedCell(rows.describe), /60% corrected \(95% \d+% to \d+%\), raw 60%, TPR 90%, TNR 85% on 40 labels/);
});

test('a judge below 0.8 on either rate, too few labels or another judge is not validated', () => {
  const run = { judge: JUDGE, promptVersion: '1' };
  assert.match(validation(labels(20, 20, 15, 19), run).why, /TPR 75% and TNR 95%/);
  assert.match(validation(labels(9, 20, 9, 20), run).why, /at least 10 of each/);
  assert.match(validation(labels(20, 20, 20, 20, 'anthropic/other'), run).why, /judged by anthropic\/other/);
  assert.equal(validation(labels(20, 20, 16, 16), run).validated, true);
});

test('a text written by the judge\'s own model is counted apart, not scored', () => {
  const wording = [...lines(3, 3), ...lines(2, 0, 'role', { writer: JUDGE, fact: 'own' })];
  const r = judgedRows(wording).describe.role;
  assert.equal(r.sameModel, 2);
  assert.equal(r.raw, 1);
});

test('the corrected score is clamped and undefined for a judge no better than chance', () => {
  assert.equal(corrected(0.1, 0.9, 0.8), 0);
  assert.equal(corrected(0.5, 0.5, 0.5), null);
});

test('the label set is half passed and half failed, spread over sets, a third to tune', () => {
  const bySet = {
    'project-1': lines(40, 10),
    'project-2': lines(40, 30).map((l) => ({ ...l, text: `Other ${l.text}` })),
  };
  const drawn = drawLabels(bySet, 'role', 60);
  const verdicts = Object.values(drawn.verdicts);

  assert.equal(drawn.items.length, 60);
  assert.equal(verdicts.filter((v) => v === 'pass').length, 30);
  assert.equal(verdicts.filter((v) => v === 'fail').length, 30);
  assert.equal(drawn.items.filter((i) => i.split === 'tune').length, 20);
  assert.ok(drawn.items.some((i) => i.set === 'project-1') && drawn.items.some((i) => i.set === 'project-2'));
  // The owner's items show no verdict.
  assert.ok(drawn.items.every((i) => !('verdict' in i) && i.label === null));
  assert.deepEqual(drawLabels(bySet, 'role', 60), drawn);
});

test('with too few of one class, the other fills in', () => {
  const drawn = drawLabels({ s: lines(70, 5) }, 'role', 60);
  const verdicts = Object.values(drawn.verdicts);
  assert.equal(verdicts.filter((v) => v === 'pass').length, 5);
  assert.equal(verdicts.filter((v) => v === 'fail').length, 55);
});

test('the label files round-trip into the scorer', () => {
  const dir = mkdtempSync(join(tmpdir(), 'wording-'));
  try {
    const scores = join(dir, 'scores');
    mkdirSync(join(scores, 'project-1'), { recursive: true });
    const all = [...lines(40, 20, 'role'), ...lines(40, 20, 'specific')];
    writeFileSync(join(scores, 'project-1', 'wording.jsonl.gz'), gzipSync(all.map((l) => JSON.stringify(l)).join('\n')));
    const counts = drawLabelFiles(scores, join(dir, 'labels'));

    assert.deepEqual(counts.role, { items: 40, judgedPass: 20, judgedFail: 20 });
    const owner = JSON.parse(readFileSync(join(dir, 'labels', 'role.json'), 'utf8'));
    assert.equal(owner.items.length, 40);
    const read = readLabels(join(dir, 'labels'));
    assert.equal(read.role.judge.judge, JUDGE);
    assert.match(validation(read.role, { judge: JUDGE, promptVersion: '1' }).why, /0 pass and 0 fail held-out labels/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
