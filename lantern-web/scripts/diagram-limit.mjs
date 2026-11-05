// Measures how many elements the C4 report template draws well in a container or component view
// (#836, docs/research/diagram-element-limit.md). It draws many randomized synthetic views, shaped
// like the views Lantern writes, with the template itself in headless Chrome, and records how each
// one comes out: its line crossings, the scale it must be shrunk to so it fits the page, lines
// running through boxes, labels on top of other labels or boxes, and the layout's own cost and
// missed rules. The views are generated from a fixed seed and the layout is deterministic, so a
// rerun gives the same numbers.
//
// Usage, from the repository root:
//   node lantern-web/scripts/diagram-limit.mjs shape <document.c4.json>... --out <shape.json>
//       The shape of real views: element kinds, lines per element, label and name lengths.
//   node lantern-web/scripts/diagram-limit.mjs measure [--shape <shape.json>] [--out <dir>]
//       [--jobs <n>] [--per-step <n>] [--steps <n,n,...>] [--chrome <path>]
//       Draws the synthetic views and writes <dir>/views.csv, <dir>/summary.json and charts.
//   node lantern-web/scripts/diagram-limit.mjs summary [--out <dir>]
//       summary.json and the charts again from <dir>/views.csv, without drawing.
//   node lantern-web/scripts/diagram-limit.mjs example --elements <n> [--density <d>]
//       [--level containers|components] [--seed <n>] [--shape <shape.json>] --out <file.html>
//       One synthetic view as a report, to look at by eye.
//
// Chrome is found as $CHROME, google-chrome, chromium or chromium-browser on the PATH. Each batch
// runs in a fresh profile, since the template caches layouts in localStorage.
import { execFile } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const TEMPLATE = resolve(
  HERE,
  '../../report-specs/c4-model-sequence-diagrams/c4-model-sequence-diagrams.html',
);
const DEFAULT_SHAPE = resolve(HERE, '../../docs/research/diagram-element-limit/shape.json');
const DEFAULT_OUT = resolve(HERE, '../../docs/research/diagram-element-limit');
const SAMPLE = resolve(
  HERE,
  '../../report-specs/c4-model-sequence-diagrams/c4-model-sequence-diagrams.sample-data.json',
);

/**
 * The element counts measured and the line densities (lines per element), which span the views of
 * the five development sets (median 1.06 to 1.49, at most 2.89 lines per element; shape.json).
 * Laying out a view takes time that grows about with the fourth power of its elements (5 s at 20,
 * 2 minutes at 40), so past {@link FULL_TO} each variant draws {@link TAIL_VIEWS} views, not the
 * full count, and the views without a frame are drawn at one density up to {@link FULL_TO}.
 */
export const STEPS = [5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 20, 25, 30, 35, 40];
export const DENSITIES = [1.0, 1.5, 2.0];
export const FULL_TO = 30;
export const TAIL_VIEWS = 10;
export const UNFRAMED_DENSITY = 1.5;
export const LEVELS = ['containers', 'components'];
/** The seed every synthetic view is drawn from. */
export const SEED = 836;

// ---------------------------------------------------------------------------------------------
// The shape of real views.

/** The container and component diagrams of a template document, with their elements resolved. */
function views(doc) {
  const out = [];
  for (const page of doc.pages ?? []) {
    if (page.level !== 'containers' && page.level !== 'components') continue;
    for (const s of page.sections ?? []) {
      if (s.type !== 'diagram') continue;
      const rels = (s.relationships ?? []).filter((r) => doc.elements[r.from] && doc.elements[r.to]);
      const ids = new Set([...rels.flatMap((r) => [r.from, r.to]), ...(s.elements ?? [])]);
      const ids2 = [...ids].filter((id) => doc.elements[id]);
      out.push({ level: page.level, boundary: s.boundary, ids: ids2, rels });
    }
  }
  return out;
}

/** Where an element sits in a view: its kind, and whether the view's frame holds it. */
function place(doc, view, id) {
  const e = doc.elements[id];
  return `${e.kind}${view.boundary && e.parent === view.boundary ? '/in' : '/out'}`;
}

/** The shape of the container and component views of the given documents. */
export function shapeOf(docs) {
  const shape = {};
  for (const level of LEVELS) {
    shape[level] = { views: 0, elements: [], linesPerElement: [], places: {}, pairs: {}, tags: {} };
  }
  const lengths = { name: {}, technology: {}, label: [], relTechnology: [] };
  for (const doc of docs) {
    for (const view of views(doc)) {
      const s = shape[view.level];
      s.views++;
      s.elements.push(view.ids.length);
      s.linesPerElement.push(round2(view.rels.length / Math.max(1, view.ids.length)));
      for (const id of view.ids) {
        const p = place(doc, view, id);
        s.places[p] = (s.places[p] ?? 0) + 1;
        const e = doc.elements[id];
        (lengths.name[e.kind] ??= []).push(e.name.length);
        (lengths.technology[e.kind] ??= []).push((e.technology ?? '').length);
        if (e.tag) s.tags[`${e.kind}:${e.tag}`] = (s.tags[`${e.kind}:${e.tag}`] ?? 0) + 1;
      }
      for (const r of view.rels) {
        const pair = `${place(doc, view, r.from)}>${place(doc, view, r.to)}`;
        s.pairs[pair] = (s.pairs[pair] ?? 0) + 1;
        lengths.label.push((r.label ?? '').length);
        lengths.relTechnology.push((r.technology ?? '').length);
      }
    }
  }
  for (const level of LEVELS) {
    shape[level].elements.sort((a, b) => a - b);
    shape[level].linesPerElement.sort((a, b) => a - b);
  }
  for (const kind of Object.keys(lengths.name)) {
    lengths.name[kind].sort((a, b) => a - b);
    lengths.technology[kind].sort((a, b) => a - b);
  }
  lengths.label.sort((a, b) => a - b);
  lengths.relTechnology.sort((a, b) => a - b);
  return { ...shape, lengths };
}

// ---------------------------------------------------------------------------------------------
// Synthetic views.

/** A seeded random number generator (mulberry32). */
export function random(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const WORDS = (
  'order orders payment payments customer catalog product products stock invoice invoices ' +
  'shipment shipments account accounts user users report reports gateway service store event ' +
  'events message messages request requests reads writes calls sends publishes delivers checks ' +
  'lists creates updates notifies schedules returns refunds price prices cart basket audit ' +
  'search index query billing ledger tenant session token profile admin portal worker job'
).split(' ');

const pick = (rnd, list) => list[Math.floor(rnd() * list.length)];

/** A text of about the given length, made of words. */
function text(rnd, length) {
  if (length <= 0) return '';
  let out = pick(rnd, WORDS);
  while (out.length < length) out += ' ' + pick(rnd, WORDS);
  return out.length > length + 4 ? out.slice(0, Math.max(3, length)) : out;
}

/** A value drawn from weighted counts. */
function weighted(rnd, counts) {
  const entries = Object.entries(counts).sort(([a], [b]) => a.localeCompare(b));
  const total = entries.reduce((n, [, c]) => n + c, 0);
  let r = rnd() * total;
  for (const [key, c] of entries) {
    r -= c;
    if (r < 0) return key;
  }
  return entries[entries.length - 1][0];
}

/**
 * One synthetic view of a level with n elements and about density × n lines, drawn from the
 * shape: element kinds and where they sit as in real views of that level, lines between kinds as
 * often as real views draw them, names, technologies and labels of real lengths. Element IDs carry
 * the view's prefix, so views in one document share nothing.
 */
export function syntheticView(shape, level, n, density, frame, rnd, prefix) {
  const s = shape[level];
  const elements = {};
  const boundaryKind = level === 'containers' ? 'system' : 'container';
  const boundary = `${prefix}-frame`;
  elements[boundary] = { kind: boundaryKind, name: text(rnd, 12) };
  const placed = [];
  for (let i = 0; i < n; i++) {
    // the first element always sits in the frame, so the frame is never empty
    let p = weighted(rnd, s.places);
    if (i === 0 && !p.endsWith('/in')) {
      p = weighted(rnd, Object.fromEntries(Object.entries(s.places).filter(([k]) => k.endsWith('/in'))));
    }
    const [kind, where] = p.split('/');
    const id = `${prefix}-e${i}`;
    const lengths = shape.lengths;
    const e = { kind, name: text(rnd, pick(rnd, lengths.name[kind] ?? [14])) };
    const tech = pick(rnd, lengths.technology[kind] ?? [0]);
    if (tech) e.technology = text(rnd, tech);
    if (where === 'in') e.parent = boundary;
    elements[id] = e;
    placed.push({ id, place: p });
  }
  const wanted = Math.round(n * density);
  const rels = [];
  const seen = new Set();
  const byPlace = {};
  for (const e of placed) (byPlace[e.place] ??= []).push(e.id);
  for (let tries = 0; rels.length < wanted && tries < wanted * 50; tries++) {
    const [from, to] = weighted(rnd, s.pairs).split('>');
    let a = byPlace[from] ? pick(rnd, byPlace[from]) : pick(rnd, placed).id;
    let b = byPlace[to] ? pick(rnd, byPlace[to]) : pick(rnd, placed).id;
    if (tries > wanted * 10) {
      // the view lacks the kinds the pairs want: any two elements
      a = pick(rnd, placed).id;
      b = pick(rnd, placed).id;
    }
    if (a === b || seen.has(`${a}>${b}`)) continue;
    seen.add(`${a}>${b}`);
    const r = { from: a, to: b, label: text(rnd, pick(rnd, shape.lengths.label)) };
    const tech = pick(rnd, shape.lengths.relTechnology);
    if (tech) r.technology = text(rnd, tech);
    rels.push(r);
  }
  const linked = new Set(rels.flatMap((r) => [r.from, r.to]));
  const section = {
    type: 'diagram',
    id: `${prefix}-diagram`,
    title: `${level} ${n} ${density}${frame ? '' : ' unframed'}`,
    relationships: rels,
  };
  const alone = placed.map((e) => e.id).filter((id) => !linked.has(id));
  if (alone.length) section.elements = alone;
  if (frame) section.boundary = boundary;
  else for (const e of placed) delete elements[e.id].parent;
  return { elements, section };
}

/** The variants measured, in a fixed order: level, element count, density and frame. */
export function variants(perStep) {
  const out = [];
  for (const level of LEVELS)
    for (const n of STEPS) {
      const views = n <= FULL_TO ? perStep : Math.min(perStep, TAIL_VIEWS);
      for (const density of DENSITIES) out.push({ level, n, density, frame: true, views });
      if (n <= FULL_TO) out.push({ level, n, density: UNFRAMED_DENSITY, frame: false, views });
    }
  return out;
}

/**
 * The batches a variant is drawn in, each in its own browser: few views to a batch where each
 * takes long, so the batches spread over the jobs.
 */
export function batches(perStep) {
  const out = [];
  for (const variant of variants(perStep)) {
    const size = variant.n >= 35 ? 1 : variant.n >= 25 ? 3 : variant.n >= 15 ? 10 : variant.views;
    for (let from = 0; from < variant.views; from += size) {
      out.push({ ...variant, from, to: Math.min(variant.views, from + size) });
    }
  }
  return out;
}

/**
 * The document of one batch: its views, each on its own page. Every view of the variant is drawn
 * from the variant's own seed in order, so a view is the same whatever batch it is drawn in.
 */
export function batchDocument(shape, batch, readable = readableOf()) {
  const seed = hash(`${SEED}/${batch.level}/${batch.n}/${batch.density}/${batch.frame}`);
  const rnd = random(seed);
  const elements = {};
  const pages = [];
  const from = batch.from ?? 0, to = batch.to ?? batch.views;
  for (let v = 0; v < to; v++) {
    const prefix = `v${v}`;
    const view = syntheticView(shape, batch.level, batch.n, batch.density, batch.frame, rnd, prefix);
    if (v < from) continue;
    Object.assign(elements, view.elements);
    pages.push({ id: `p${v}`, level: batch.level, title: `View ${v}`, sections: [view.section] });
  }
  return { document: { id: 'diagram-limit', title: 'Diagram limit', readable }, elements, pages };
}

function hash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

// ---------------------------------------------------------------------------------------------
// Drawing in the browser.

/**
 * What the page runs once the template has drawn: every container and component view laid out
 * and measured on the drawn geometry, written into a <pre> that --dump-dom returns.
 */
const PROBE = `<script>
(() => {
  const rows = {};
  const inner = measureDiagram;
  measureDiagram = function (raw, spec, geoms) {
    inner(raw, spec, geoms);
    if (!raw._key) return;
    const [cw, ch] = READABLE.canvas;
    const scale = Math.min(cw / spec.vb[2], ch / spec.vb[3]);
    const boxOf = (n) => ({ x: n.x, y: n.y - (EL[n.id].kind === 'person' ? 20 : 0), w: n.w, h: n.h + (EL[n.id].kind === 'person' ? 20 : 0) });
    const over = (r, o) => r.x < o.x + o.w && r.x + r.w > o.x && r.y < o.y + o.h && r.y + r.h > o.y;
    let through = 0;
    for (const g of geoms) for (const n of spec.nodes) {
      if (n.id === g.e.f || n.id === g.e.t) continue;
      const b = boxOf(n);
      for (let i = 1; i < g.pts.length; i++) if (segHitsBox(g.pts[i-1], g.pts[i], b)) { through++; break; }
    }
    const labels = geoms.map((g) => ({ x: g.mx - g.w/2, y: g.my - g.h/2, w: g.w, h: g.h }));
    let labelOverlaps = 0;
    labels.forEach((r, i) => {
      if (labels.some((o, j) => j !== i && over(r, o)) || spec.nodes.some((n) => over(r, boxOf(n)))) labelOverlaps++;
    });
    const r = raw._section.readability;
    rows[raw._key] = { elements: spec.nodes.length, lines: geoms.length, crossings: r.crossings,
      scale: Math.round(scale * 1000) / 1000, fits: r.fits, width: r.width, height: r.height,
      through, labelOverlaps, cost: Math.round(spec._cost * 10) / 10,
      violations: (spec._violations || []).length };
  };
  measureAll();
  const out = document.createElement('pre');
  out.id = 'lantern-out';
  out.textContent = JSON.stringify(rows);
  document.body.appendChild(out);
})();
</script>`;

/** The template with the document as its data, and the probe when measuring. */
export function report(template, doc, probe) {
  const open = '<script type="application/json" id="arc-data">';
  const i = template.indexOf(open);
  const j = template.indexOf('</script>', i);
  if (i < 0 || j < 0) throw new Error('The template has no arc-data element');
  const json = JSON.stringify(doc).replaceAll('<', '\\u003c');
  let html = template.slice(0, i + open.length) + json + template.slice(j);
  if (probe) {
    const end = html.lastIndexOf('</body>');
    html = html.slice(0, end) + PROBE + html.slice(end);
  }
  return html;
}

function chrome(path) {
  if (path) return path;
  if (process.env.CHROME) return process.env.CHROME;
  for (const dir of (process.env.PATH ?? '').split(':')) {
    for (const name of ['google-chrome', 'chromium', 'chromium-browser']) {
      if (existsSync(join(dir, name))) return join(dir, name);
    }
  }
  throw new Error('No Chrome found: set CHROME or pass --chrome <path>');
}

/** Draws one batch in a fresh headless Chrome and returns its rows, by view key. */
function draw(browser, html, work) {
  const dir = mkdtempSync(join(work, 'batch-'));
  const file = join(dir, 'report.html');
  writeFileSync(file, html);
  const args = ['--headless=new', '--no-sandbox', '--disable-gpu', `--user-data-dir=${join(dir, 'profile')}`,
    '--virtual-time-budget=600000', '--dump-dom', `file://${file}`];
  // Chrome keeps a socket in its temporary folder, whose path must be short (about 100 bytes).
  const socket = mkdtempSync('/tmp/lantern-dl-');
  const env = { ...process.env, TMPDIR: socket };
  return new Promise((resolveRows, reject) => {
    execFile(browser, args, { env, maxBuffer: 512 * 1024 * 1024, timeout: 3 * 60 * 60 * 1000 }, (err, stdout) => {
      rmSync(dir, { recursive: true, force: true });
      rmSync(socket, { recursive: true, force: true });
      if (err) return reject(err);
      const m = stdout.match(/<pre id="lantern-out">([^<]*)<\/pre>/);
      if (!m) return reject(new Error('The page wrote no measurements'));
      const json = m[1].replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>');
      resolveRows(JSON.parse(json));
    });
  });
}

// ---------------------------------------------------------------------------------------------
// Summaries.

const round2 = (x) => Math.round(x * 100) / 100;

function quantile(sorted, q) {
  if (!sorted.length) return null;
  const i = (sorted.length - 1) * q;
  const lo = Math.floor(i), hi = Math.ceil(i);
  return round2(sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo));
}

/** The lower end of the 95% Wilson interval of a share of n views. */
function wilsonLow(share, n) {
  if (!n) return null;
  const z = 1.96, z2 = z * z;
  const centre = share + z2 / (2 * n);
  const spread = z * Math.sqrt((share * (1 - share)) / n + z2 / (4 * n * n));
  return round2(Math.max(0, (centre - spread) / (1 + z2 / n)));
}

/**
 * Per level, element count, density and frame: the share of views meeting each check.
 *
 * - fits: legible scaled to the page's width, the owner's definition of fit (4 Nov 2026, #764):
 *   the canvas width over the view's width is at least the limits' scale; height does not count.
 * - fitsWhole: the template's own check, which also needs the height to fit (ADR 0123).
 * - crossingsOk: at most the limits' crossings, a warning only.
 * - readable: fits and crossingsOk. clean: readable, no line through a box, no label on another.
 *
 * @param readable the limits ({scale, crossings}) and the canvas width ({canvasWidth})
 */
export function summarize(rows, readable) {
  const widthScale = (r) => readable.canvasWidth / r.width;
  const fits = (r) => widthScale(r) >= readable.scale;
  const crossingsOk = (r) => r.crossings <= readable.crossings;
  const groups = new Map();
  for (const r of rows) {
    const key = `${r.level}|${r.n}|${r.density}|${r.frame}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(r);
  }
  const out = [];
  for (const [key, list] of groups) {
    const [level, n, density, frame] = key.split('|');
    const sorted = (f) => list.map(f).sort((a, b) => a - b);
    const share = (f) => round2(list.filter(f).length / list.length);
    out.push({
      level, n: Number(n), density: Number(density), frame: frame === 'true', views: list.length,
      fits: share(fits),
      fitsLow: wilsonLow(share(fits), list.length),
      fitsWhole: share((r) => r.fits),
      crossingsOk: share(crossingsOk),
      readable: share((r) => fits(r) && crossingsOk(r)),
      readableLow: wilsonLow(share((r) => fits(r) && crossingsOk(r)), list.length),
      clean: share((r) => fits(r) && crossingsOk(r) && r.through === 0 && r.labelOverlaps === 0),
      widthScale: { p10: quantile(sorted(widthScale), 0.1), median: quantile(sorted(widthScale), 0.5) },
      crossings: { median: quantile(sorted((r) => r.crossings), 0.5), p90: quantile(sorted((r) => r.crossings), 0.9) },
      through: { median: quantile(sorted((r) => r.through), 0.5), p90: quantile(sorted((r) => r.through), 0.9) },
      labelOverlaps: { median: quantile(sorted((r) => r.labelOverlaps), 0.5), p90: quantile(sorted((r) => r.labelOverlaps), 0.9) },
      costPerElement: quantile(sorted((r) => r.cost / Math.max(1, r.elements)), 0.5),
      violations: share((r) => r.violations > 0),
    });
  }
  return out.sort((a, b) => a.level.localeCompare(b.level) || a.n - b.n || a.density - b.density || Number(b.frame) - Number(a.frame));
}

/** A line chart, as SVG, of one share per element count: one line per density. */
export function chart(summary, level, measure, title) {
  const W = 560, H = 300, L = 48, R = 110, T = 34, B = 40;
  const x = (n) => L + ((n - STEPS[0]) / (STEPS[STEPS.length - 1] - STEPS[0])) * (W - L - R);
  const y = (v) => T + (1 - v) * (H - T - B);
  const colors = ['#2a6fdb', '#d9822b', '#3a9e5c', '#8a4fd1'];
  let s = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" font-family="sans-serif" font-size="11">`;
  s += `<rect width="${W}" height="${H}" fill="#fff"/><text x="${L}" y="18" font-size="13" font-weight="bold">${title}</text>`;
  for (const v of [0, 0.25, 0.5, 0.75, 1]) {
    s += `<line x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}" stroke="#ddd"/><text x="${L - 6}" y="${y(v) + 4}" text-anchor="end">${Math.round(v * 100)}%</text>`;
  }
  for (const n of STEPS.filter((n) => n % 5 === 0)) s += `<text x="${x(n)}" y="${H - B + 16}" text-anchor="middle">${n}</text>`;
  s += `<text x="${(L + W - R) / 2}" y="${H - 6}" text-anchor="middle">elements in the view</text>`;
  const series = [...DENSITIES.map((d) => ({ d, frame: true, label: `${d} lines/element` })),
    { d: UNFRAMED_DENSITY, frame: false, label: `${UNFRAMED_DENSITY}, no frame` }];
  series.forEach(({ d, frame: framed, label }, i) => {
    const pts = summary.filter((r) => r.level === level && r.frame === framed && r.density === d).sort((a, b) => a.n - b.n);
    if (!pts.length) return;
    const dash = framed ? '' : ' stroke-dasharray="5 4"';
    s += `<polyline fill="none" stroke="${colors[i % colors.length]}" stroke-width="2"${dash} points="${pts.map((p) => `${x(p.n)},${y(p[measure])}`).join(' ')}"/>`;
    s += `<text x="${W - R + 8}" y="${T + 14 + i * 16}" fill="${colors[i % colors.length]}">${label}</text>`;
  });
  return s + '</svg>\n';
}

// ---------------------------------------------------------------------------------------------
// Commands.

function args(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) out[argv[i].slice(2)] = argv[++i];
    else out._.push(argv[i]);
  }
  return out;
}

/**
 * The limits the synthetic reports carry, which the template judges each view by: those of the
 * spec's sample data, unless --scale or --crossings is given. Lantern's own come from its setting
 * lantern.diagrams and reach a report the same way, as its document's `readable` (#836).
 */
export function readableOf(opts = {}) {
  const sample = JSON.parse(readFileSync(SAMPLE, 'utf8')).document.readable;
  return {
    ...sample,
    ...(opts.scale ? { scale: Number(opts.scale) } : {}),
    ...(opts.crossings ? { crossings: Number(opts.crossings) } : {}),
  };
}

async function measure(opts) {
  const shape = JSON.parse(readFileSync(opts.shape ?? DEFAULT_SHAPE, 'utf8'));
  const out = resolve(opts.out ?? DEFAULT_OUT);
  const jobs = Number(opts.jobs ?? 8);
  // At most this many batches, for a quick check of the script (the numbers are then partial).
  const limit = opts.batches ? Number(opts.batches) : Infinity;
  const perStep = Number(opts['per-step'] ?? 50);
  const browser = chrome(opts.chrome);
  const template = readFileSync(TEMPLATE, 'utf8');
  const readable = readableOf(opts);
  const work = mkdtempSync(join(process.env.TMPDIR ?? tmpdir(), 'diagram-limit-'));
  mkdirSync(out, { recursive: true });
  // the largest views take longest: start them first
  // --steps 6,7,8 draws only those element counts and keeps the other rows of views.csv, so a
  // finer sweep adds to a run without drawing it all again; each variant has its own seed, so the
  // views are those a full run draws.
  const only = opts.steps ? new Set(opts.steps.split(',').map(Number)) : null;
  const kept = only && existsSync(join(out, 'views.csv')) ? readViews(join(out, 'views.csv')).filter((r) => !only.has(r.n)) : [];
  const todo = batches(perStep)
    .filter((b) => !only || only.has(b.n))
    .sort((a, b) => b.n * b.density - a.n * a.density)
    .slice(0, limit);
  const total = todo.length;
  const rows = [...kept];
  let done = 0;
  const worker = async () => {
    for (let batch = todo.shift(); batch; batch = todo.shift()) {
      const doc = batchDocument(shape, batch, readable);
      const measured = await draw(browser, report(template, doc, true), work);
      // every view of the batch must come back measured: one the page could not draw is an error
      if (Object.keys(measured).length !== doc.pages.length) {
        throw new Error(`${doc.pages.length - Object.keys(measured).length} of ${doc.pages.length} views were not measured (${batch.level}, ${batch.n} elements)`);
      }
      for (const [key, r] of Object.entries(measured)) {
        rows.push({ level: batch.level, n: batch.n, density: batch.density, frame: batch.frame, view: Number(key.slice(1, key.indexOf('/'))), ...r });
      }
      done++;
      process.stderr.write(`diagram-limit: ${done} of ${total} batches drawn (${batch.level}, ${batch.n} elements, ${batch.density}, ${batch.frame ? 'framed' : 'unframed'})\n`);
    }
  };
  await Promise.all(Array.from({ length: jobs }, worker));
  rmSync(work, { recursive: true, force: true });
  rows.sort((a, b) => a.level.localeCompare(b.level) || a.n - b.n || a.density - b.density || Number(b.frame) - Number(a.frame) || a.view - b.view);
  const columns = ['level', 'n', 'density', 'frame', 'view', 'elements', 'lines', 'crossings', 'scale', 'fits', 'width', 'height', 'through', 'labelOverlaps', 'cost', 'violations'];
  writeFileSync(join(out, 'views.csv'), [columns.join(','), ...rows.map((r) => columns.map((c) => r[c]).join(','))].join('\n') + '\n');
  writeSummary(out, rows, readable, template);
  process.stderr.write(`diagram-limit: ${rows.length} views measured into ${out}\n`);
}

/** The canvas width the template fits a view to, read from the template. */
export function canvasWidth(template) {
  const m = template.match(/canvas: \[(\d+), (\d+)\]/);
  if (!m) throw new Error('The template names no canvas');
  return Number(m[1]);
}

/** Writes summary.json and the charts from the measured views. */
function writeSummary(out, rows, readable, template) {
  const summary = summarize(rows, { scale: readable.scale, crossings: readable.crossings, canvasWidth: canvasWidth(template) });
  writeFileSync(join(out, 'summary.json'), JSON.stringify(summary, null, 1) + '\n');
  for (const level of LEVELS) {
    for (const [name, label] of [
      ['fits', 'are legible at page width'],
      ['readable', 'are legible at page width and cross at most the limit'],
      ['clean', 'are readable, with no line through a box or label on another'],
    ]) {
      writeFileSync(join(out, `${level}-${name}.svg`), chart(summary, level, name, `${level}: share of views that ${label}`));
    }
  }
}

/** The measured views of a views.csv, typed. */
export function readViews(file) {
  const [head, ...lines] = readFileSync(file, 'utf8').trim().split('\n');
  const columns = head.split(',');
  return lines.map((line) => {
    const cells = line.split(',');
    const r = {};
    columns.forEach((c, i) => {
      const v = cells[i];
      r[c] = c === 'level' ? v : v === 'true' ? true : v === 'false' ? false : Number(v);
    });
    return r;
  });
}

function example(opts) {
  const shape = JSON.parse(readFileSync(opts.shape ?? DEFAULT_SHAPE, 'utf8'));
  const n = Number(opts.elements);
  const density = Number(opts.density ?? 1.5);
  const level = opts.level ?? 'containers';
  const rnd = random(hash(`${SEED}/example/${level}/${n}/${density}/${opts.seed ?? 0}`));
  const view = syntheticView(shape, level, n, density, true, rnd, 'v0');
  const doc = { document: { id: 'diagram-limit', title: `${n} elements`, readable: readableOf(opts) }, elements: view.elements,
    pages: [{ id: 'p0', level, title: `${n} elements, ${density} lines per element`, sections: [view.section] }] };
  writeFileSync(opts.out, report(readFileSync(TEMPLATE, 'utf8'), doc, false));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [command, ...rest] = process.argv.slice(2);
  const opts = args(rest);
  if (command === 'shape') {
    if (!opts._.length || !opts.out) throw new Error('Usage: diagram-limit.mjs shape <document.c4.json>... --out <shape.json>');
    const docs = opts._.map((f) => JSON.parse(readFileSync(f, 'utf8')));
    writeFileSync(opts.out, JSON.stringify(shapeOf(docs)) + '\n');
  } else if (command === 'summary') {
    // The summary and charts again from views.csv, without drawing anything.
    const out = resolve(opts.out ?? DEFAULT_OUT);
    writeSummary(out, readViews(join(out, 'views.csv')), readableOf(opts), readFileSync(TEMPLATE, 'utf8'));
  } else if (command === 'measure') {
    await measure(opts);
  } else if (command === 'example') {
    if (!opts.elements || !opts.out) throw new Error('Usage: diagram-limit.mjs example --elements <n> --out <file.html>');
    example(opts);
  } else {
    console.error('Usage: diagram-limit.mjs shape|measure|example ... (see the comment at the top)');
    process.exit(2);
  }
}
