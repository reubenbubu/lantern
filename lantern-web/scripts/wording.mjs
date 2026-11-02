// The wording judge's rows and its label set (docs/adr/0111-wording-judge.md;
// docs/research/role-measurement.md, Judge). A run that judges wording writes wording.jsonl.gz
// beside its documents: each text a role wrote, once per dimension, with the judge's verdict. The
// judge's score counts only once the owner's labels validate it: TPR and TNR at least 0.8 on the
// held-out labels; until then each judged row says "not validated", with the raw counts beside it.
// Validated, the row reports the corrected pass rate θ = (p + TNR − 1) / (TPR + TNR − 1) with a 95%
// bootstrap interval that includes the labels' own uncertainty.
//
// The label set is drawn by the judge's verdict (half it passed, half it failed), not at random, so
// each label stands for the texts of its stratum: TPR and TNR are weighted by the stratum's size
// over the labels drawn from it (inverse inclusion probability), or they would be biased.
//
//   node scripts/wording.mjs draw <scores folder> [--per-dimension 60] [--out <labels folder>] [--force]
//     draws the label set from a scored run's folders (<scores folder>/<set>/wording.jsonl.gz):
//     per dimension about half the texts the judge passed and half it failed, spread over sets and
//     roles, a third to tune and the rest held out. It writes <dimension>.json, the texts for the
//     owner to label (no verdicts shown), and <dimension>.judge.json, the judge's verdicts on them,
//     the stratum each was drawn from and the strata's sizes. It refuses to replace a file that
//     holds the owner's labels unless --force is given.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

/** The dimensions the judge grades, one per call. */
export const DIMENSIONS = ['role', 'specific'];

/** What each dimension asks, as the scorecard says it. */
export const DIMENSION_LABELS = {
  role: 'says what the element does for the system',
  specific: 'not true of any Spring application',
};

/** The least TPR and TNR on the held-out labels before the judge's score counts. */
export const MIN_RATE = 0.8;

/** The least held-out labels of each class (pass and fail) a validation needs. */
export const MIN_PER_CLASS = 10;

/** Where the owner's labels sit: testsets/wording-labels of this checkout. */
export const LABELS_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../../testsets/wording-labels');

/** The texts each judged row grades, by the role that wrote them and their predicate. */
export const JUDGED_ROWS = {
  describe: (l) => ['describe-unit', 'describe-element'].includes(l.role) && ['responsibility', 'intent'].includes(l.predicate),
  nameGroups: (l) => l.role === 'name-groups' && ['display-name', 'responsibility'].includes(l.predicate),
  purpose: (l) => l.predicate === 'key-fact',
};

const notMeasured = (why) => ({ measured: false, why });

/** The lines of a gzipped JSON-lines file, or null when there is none. */
export function readLines(path) {
  if (!path || !existsSync(path)) return null;
  return gunzipSync(readFileSync(path))
    .toString('utf8')
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => JSON.parse(line));
}

/**
 * The owner's labels and the judge's verdicts on them, per dimension, or null for a dimension
 * whose files are missing.
 */
export function readLabels(dir = LABELS_DIR) {
  const labels = {};
  for (const d of DIMENSIONS) {
    const owner = join(dir, `${d}.json`);
    const judge = join(dir, `${d}.judge.json`);
    labels[d] =
      existsSync(owner) && existsSync(judge)
        ? { owner: JSON.parse(readFileSync(owner, 'utf8')), judge: JSON.parse(readFileSync(judge, 'utf8')) }
        : null;
  }
  return labels;
}

/** A seeded pseudo-random number generator, so a draw and an interval are the same every time. */
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 2 ** 32;
  };
}

/**
 * How the judge did against the owner's held-out labels of one dimension, or why it cannot be
 * told: no labels yet, too few, or labels of another judge or prompt version than the run's.
 *
 * @param labels the dimension's `{ owner, judge }` (readLabels), or null
 * @param run `{ judge, promptVersion }` of the run's verdicts
 */
export function validation(labels, run) {
  if (!labels) return { validated: false, why: 'no owner labels yet (testsets/wording-labels)' };
  const { owner, judge } = labels;
  if (judge.judge !== run.judge || String(judge.promptVersion) !== String(run.promptVersion)) {
    return {
      validated: false,
      why: `the labels were judged by ${judge.judge} (prompt ${judge.promptVersion}), the run by ${run.judge} (prompt ${run.promptVersion})`,
    };
  }
  const heldOut = (owner.items ?? [])
    .filter((i) => i.split === 'test' && (i.label === 'pass' || i.label === 'fail'))
    .map((i) => ({ label: i.label, verdict: judge.verdicts?.[i.id] ?? null, stratum: judge.drawnAs?.[i.id] ?? null }));
  const pos = heldOut.filter((i) => i.label === 'pass').length;
  const neg = heldOut.length - pos;
  if (pos < MIN_PER_CLASS || neg < MIN_PER_CLASS) {
    return {
      validated: false,
      why: `${pos} pass and ${neg} fail held-out labels; at least ${MIN_PER_CLASS} of each needed`,
      labelled: heldOut.length,
    };
  }
  if (heldOut.some((i) => !(i.stratum in (judge.population ?? {})))) {
    return { validated: false, why: 'the labels do not say which stratum each was drawn from (redraw them)', labelled: heldOut.length };
  }
  const strata = { pass: heldOut.filter((i) => i.stratum === 'pass'), fail: heldOut.filter((i) => i.stratum === 'fail') };
  const { tpr, tnr } = rates(strata, judge.population);
  const result = { tpr, tnr, strata, population: judge.population, labelled: heldOut.length };
  if (tpr < MIN_RATE || tnr < MIN_RATE) {
    return { ...result, validated: false, why: `TPR ${pct(tpr)} and TNR ${pct(tnr)}; both must be at least ${pct(MIN_RATE)}` };
  }
  return { ...result, validated: true };
}

const pct = (x) => `${Math.round(x * 100)}%`;

/**
 * TPR and TNR over labels drawn by stratum (the judge's verdict when drawn): each label weighs its
 * stratum's size over the labels from that stratum. An "unknown" verdict counts as the judge wrong.
 *
 * @param strata `{ pass: [...], fail: [...] }`, each label `{ label, verdict }`
 * @param population `{ pass, fail }`, how many texts each stratum held when the labels were drawn
 */
export function rates(strata, population) {
  let tp = 0;
  let p = 0;
  let tn = 0;
  let n = 0;
  for (const s of ['pass', 'fail']) {
    const xs = strata[s] ?? [];
    if (xs.length === 0) continue;
    const w = population[s] / xs.length;
    for (const x of xs) {
      if (x.label === 'pass') {
        p += w;
        if (x.verdict === 'pass') tp += w;
      } else {
        n += w;
        if (x.verdict === 'fail') tn += w;
      }
    }
  }
  return { tpr: p === 0 ? null : tp / p, tnr: n === 0 ? null : tn / n };
}
const clamp = (x) => Math.min(1, Math.max(0, x));

/** The corrected pass rate θ of a raw rate p (Lee et al.), clamped to [0, 1]; null when TPR + TNR ≤ 1. */
export function corrected(p, tpr, tnr) {
  const d = tpr + tnr - 1;
  return d <= 0 ? null : clamp((p + tnr - 1) / d);
}

/**
 * The 95% interval of θ: resamples the run's verdicts and the held-out labels within each stratum
 * together, so the labels' uncertainty is in it.
 */
export function correctedInterval(verdicts, strata, population, resamples = 2000, seed = 668) {
  const next = random(seed);
  const resample = (xs) => Array.from({ length: xs.length }, () => xs[Math.floor(next() * xs.length)]);
  const thetas = [];
  for (let k = 0; k < resamples; k++) {
    const p = resample(verdicts).filter(Boolean).length / verdicts.length;
    const r = rates({ pass: resample(strata.pass), fail: resample(strata.fail) }, population);
    const t = r.tpr === null || r.tnr === null ? null : corrected(p, r.tpr, r.tnr);
    if (t !== null) thetas.push(t);
  }
  if (thetas.length === 0) return { low: null, high: null };
  thetas.sort((a, b) => a - b);
  return { low: thetas[Math.floor(0.025 * thetas.length)], high: thetas[Math.ceil(0.975 * thetas.length) - 1] };
}

/**
 * One judged row's dimension: the run's verdicts on the texts it selects, counted, with the
 * corrected score once the labels validate the judge. A text whose writer is the judge's model is
 * counted apart and not scored (the judge must not grade its own model's texts).
 */
export function judgedDimension(lines, dimension, labels) {
  const all = lines.filter((l) => l.dimension === dimension);
  if (all.length === 0) return notMeasured('the run judged no such text');
  const byJudge = (l) => l.state === 'sameModel' || (l.writer && l.writer === l.judge);
  const sameModel = all.filter(byJudge);
  const own = all.filter((l) => !byJudge(l));
  const judged = own.filter((l) => l.state === 'judged');
  const count = (v) => judged.filter((l) => l.verdict === v).length;
  const pass = count('pass');
  const fail = count('fail');
  const graded = judged.filter((l) => l.verdict === 'pass' || l.verdict === 'fail');
  const words = own.map((l) => l.words ?? 0);
  const row = {
    measured: true,
    dimension,
    question: DIMENSION_LABELS[dimension],
    judge: all[0].judge,
    texts: all.length,
    pass,
    fail,
    unknown: count('unknown'),
    notAnswered: own.filter((l) => l.state === 'notAnswered').length,
    tooLong: own.filter((l) => l.state === 'tooLong').length,
    sameModel: sameModel.length,
    meanWords: words.length === 0 ? null : Math.round((words.reduce((s, w) => s + w, 0) / words.length) * 10) / 10,
    raw: graded.length === 0 ? null : pass / graded.length,
  };
  const v = validation(labels, { judge: all[0].judge, promptVersion: all[0].promptVersion });
  if (!v.validated || graded.length === 0) {
    return { ...row, validated: false, why: v.validated ? 'no text graded pass or fail' : v.why, tpr: v.tpr ?? null, tnr: v.tnr ?? null };
  }
  const theta = corrected(row.raw, v.tpr, v.tnr);
  const { low, high } = correctedInterval(
    graded.map((l) => l.verdict === 'pass'),
    v.strata,
    v.population,
  );
  return { ...row, validated: true, tpr: v.tpr, tnr: v.tnr, labelled: v.labelled, corrected: theta, low, high };
}

/**
 * The judged rows of a run: per row (describe, nameGroups, purpose) and dimension, or "not
 * measured" when the run did not judge wording.
 */
export function judgedRows(wording, labels = {}) {
  const rows = {};
  for (const [name, select] of Object.entries(JUDGED_ROWS)) {
    if (!wording) {
      rows[name] = notMeasured('the run did not judge wording (score-testsets.sh --judge-wording)');
      continue;
    }
    const lines = wording.filter(select);
    rows[name] = Object.fromEntries(DIMENSIONS.map((d) => [d, judgedDimension(lines, d, labels?.[d] ?? null)]));
  }
  return rows;
}

/** One judged row as a line of text. */
export function judgedCell(row) {
  if (row.measured === false) return `not measured (${row.why})`;
  return DIMENSIONS.map((d) => {
    const r = row[d];
    if (r.measured === false) return `${d}: not measured (${r.why})`;
    const counts = `${r.pass} pass, ${r.fail} fail, ${r.unknown} unknown of ${r.texts}; mean ${r.meanWords} words${
      r.notAnswered ? `; ${r.notAnswered} not answered` : ''
    }${r.tooLong ? `; ${r.tooLong} too long` : ''}${r.sameModel ? `; ${r.sameModel} by the judge's own model, not scored` : ''}`;
    if (!r.validated) return `${d} (${r.question}): not validated (${r.why}); raw ${r.raw === null ? '-' : pct(r.raw)}, ${counts}`;
    return `${d} (${r.question}): ${pct(r.corrected)} corrected (95% ${pct(r.low)} to ${pct(r.high)}), raw ${pct(r.raw)}, TPR ${pct(r.tpr)}, TNR ${pct(r.tnr)} on ${r.labelled} labels; ${counts}`;
  }).join('; ');
}

const shortId = (...parts) => createHash('sha256').update(parts.join('\n')).digest('hex').slice(0, 12);

/**
 * The label set of one dimension, drawn from the judged lines of several sets: up to `perDimension`
 * texts, half the judge passed and half it failed where there are enough (the other class fills
 * in), taken in turn over set and role so every set and role is represented, each text once. The
 * first third of each class is for tuning, the rest held out.
 *
 * @param bySet `{ <set>: lines }` from each set's wording.jsonl.gz
 */
export function drawLabels(bySet, dimension, perDimension = 60, seed = 668) {
  const next = random(seed + dimension.length);
  const candidates = { pass: [], fail: [] };
  const seen = new Set();
  for (const [set, lines] of Object.entries(bySet).sort(([a], [b]) => a.localeCompare(b))) {
    for (const l of lines ?? []) {
      if (l.dimension !== dimension || l.state !== 'judged' || !(l.verdict in candidates)) continue;
      if (l.writer && l.writer === l.judge) continue;
      const key = l.text.trim().toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      candidates[l.verdict].push({ ...l, set });
    }
  }
  // Round robin over (set, role) groups, each group in a seeded order.
  const spread = (xs) => {
    const groups = new Map();
    for (const x of xs) {
      const g = `${x.set}\n${x.role}`;
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push(x);
    }
    const lists = [...groups.keys()].sort().map((g) => {
      const list = groups.get(g).sort((a, b) => a.fact.localeCompare(b.fact));
      for (let i = list.length - 1; i > 0; i--) {
        const j = Math.floor(next() * (i + 1));
        [list[i], list[j]] = [list[j], list[i]];
      }
      return list;
    });
    const out = [];
    for (let i = 0; lists.some((l) => i < l.length); i++) for (const l of lists) if (i < l.length) out.push(l[i]);
    return out;
  };
  const pass = spread(candidates.pass);
  const fail = spread(candidates.fail);
  const half = Math.floor(perDimension / 2);
  let takePass = Math.min(half, pass.length);
  let takeFail = Math.min(perDimension - takePass, fail.length);
  takePass = Math.min(perDimension - takeFail, pass.length);
  const chosen = [...pass.slice(0, takePass).map((l, i) => ({ l, i, n: takePass })), ...fail.slice(0, takeFail).map((l, i) => ({ l, i, n: takeFail }))];
  const items = chosen.map(({ l, i, n }) => ({
    id: shortId(l.set, l.fact, dimension),
    set: l.set,
    role: l.role,
    kind: l.kind,
    about: l.subject,
    text: l.text,
    split: i < Math.round(n / 3) ? 'tune' : 'test',
    label: null,
  }));
  // The owner's file lists the texts in an order that does not reveal the judge's verdicts.
  const ordered = [...items].sort((a, b) => a.id.localeCompare(b.id));
  const verdicts = Object.fromEntries(chosen.map(({ l }) => [shortId(l.set, l.fact, dimension), l.verdict]));
  return {
    items: ordered,
    verdicts,
    // The stratum each text was drawn from, and the strata's sizes, which weigh the labels; the
    // verdicts may be redone by another judge, the strata stay as drawn.
    drawnAs: { ...verdicts },
    population: { pass: candidates.pass.length, fail: candidates.fail.length },
    judge: chosen[0]?.l.judge ?? null,
    promptVersion: chosen[0]?.l.promptVersion ?? null,
  };
}

/** Writes the label set of every dimension from a folder of scored sets. */
export function drawLabelFiles(scoresDir, outDir = LABELS_DIR, perDimension = 60, force = false) {
  if (!force) {
    for (const d of DIMENSIONS) {
      const file = join(outDir, `${d}.json`);
      if (existsSync(file) && (JSON.parse(readFileSync(file, 'utf8')).items ?? []).some((i) => i.label !== null && i.label !== undefined)) {
        throw new Error(`${file} holds the owner's labels; drawing again would lose them (--force to replace)`);
      }
    }
  }
  const bySet = {};
  for (const set of readdirSync(scoresDir, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name)) {
    const lines = readLines(join(scoresDir, set, 'wording.jsonl.gz'));
    if (lines) bySet[set] = lines;
  }
  if (Object.keys(bySet).length === 0) throw new Error(`no <set>/wording.jsonl.gz under ${scoresDir}; run score-testsets.sh --judge-wording`);
  mkdirSync(outDir, { recursive: true });
  const counts = {};
  for (const d of DIMENSIONS) {
    const drawn = drawLabels(bySet, d, perDimension);
    const owner = {
      dimension: d,
      question: DIMENSION_LABELS[d],
      status: 'awaiting the owner\'s labels at the 1F test drop',
      how: 'Set each item\'s "label" to "pass" or "fail" for the question, judging the text alone; leave null when it cannot be judged. Items marked "tune" may be used to improve the judge\'s prompt; "test" items are held out and validate it.',
      drawnFrom: Object.keys(bySet).sort(),
      items: drawn.items,
    };
    const judge = {
      dimension: d,
      judge: drawn.judge,
      promptVersion: drawn.promptVersion,
      population: drawn.population,
      drawnAs: drawn.drawnAs,
      verdicts: drawn.verdicts,
    };
    writeFileSync(join(outDir, `${d}.json`), `${JSON.stringify(owner, null, 2)}\n`);
    writeFileSync(join(outDir, `${d}.judge.json`), `${JSON.stringify(judge, null, 2)}\n`);
    const v = Object.values(drawn.verdicts);
    counts[d] = { items: drawn.items.length, judgedPass: v.filter((x) => x === 'pass').length, judgedFail: v.filter((x) => x === 'fail').length };
  }
  return counts;
}

function main(argv) {
  const [command, dir, ...rest] = argv;
  if (command !== 'draw' || !dir) {
    console.error('usage: node scripts/wording.mjs draw <scores folder> [--per-dimension 60] [--out <labels folder>] [--force]');
    process.exit(2);
  }
  let per = 60;
  let out = LABELS_DIR;
  let force = false;
  for (let i = 0; i < rest.length; i++) {
    if (rest[i] === '--force') force = true;
    else if (rest[i] === '--per-dimension') per = Number(rest[++i]);
    else if (rest[i] === '--out') out = rest[++i];
    else {
      console.error(`unknown option ${rest[i]}`);
      process.exit(2);
    }
  }
  const counts = drawLabelFiles(dir, out, per, force);
  for (const [d, c] of Object.entries(counts)) {
    console.log(`${d}: ${c.items} texts (${c.judgedPass} the judge passed, ${c.judgedFail} it failed) in ${out}`);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main(process.argv.slice(2));
}
