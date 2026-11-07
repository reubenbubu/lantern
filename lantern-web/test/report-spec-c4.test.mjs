// Checks the data Lantern writes into the report spec report-specs/c4-model-sequence-diagrams:
// the golden files lantern-app's C4ReportRendererTest writes must be valid against the spec's
// schema and keep the cross-reference rules its template checks on load (the schema's $comment).
// The spec's own sample data must pass the same checks, so the checks match the spec.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
import { reportSpecIssues } from '../scripts/report-spec-check.mjs';

const spec = 'c4-model-sequence-diagrams';
const specDir = fileURLToPath(new URL(`../../report-specs/${spec}/`, import.meta.url));
const goldenDir = fileURLToPath(
  new URL(`../../lantern-app/src/test/resources/report-specs/${spec}/`, import.meta.url),
);
const read = (path) => JSON.parse(readFileSync(path, 'utf8'));

function check(data) {
  assert.deepEqual(reportSpecIssues(spec, data), []);
}
test("the spec's sample data passes the checks", () => {
  check(read(`${specDir}${spec}.sample-data.json`));
});

const goldens = readdirSync(goldenDir).filter((name) => name.endsWith('.json'));

test('there are golden files to check', () => {
  assert.ok(goldens.length > 0);
});

for (const name of goldens) {
  test(`Lantern's ${name} is valid for the spec`, () => {
    check(read(goldenDir + name));
  });
}

test('folded rows must hang under a row the table has, once, and mark only their own rows (#747)', () => {
  const data = read(`${specDir}${spec}.sample-data.json`);
  const table = data.pages.flatMap((p) => p.sections).find((s) => s.type === 'table' && s.subRows);
  assert.ok(table, 'the sample has a table with folded rows');
  table.subRows.push({ row: table.rows.length, rows: [['x']] });
  table.subRows.push({ ...table.subRows[0], llmRows: [5] });
  const issues = reportSpecIssues(spec, data).join('\n');
  assert.match(issues, new RegExp(`subRows names row ${table.rows.length}, which it lacks`));
  assert.match(issues, new RegExp(`subRows names row ${table.subRows[0].row} twice`));
  assert.match(issues, /subRows of row \d+ mark row 5 as the LLM's, which they lack/);
});

// The template's table renderer, loaded from its source with stand-ins for what it calls.
const source = readFileSync(`${specDir}${spec}.html`, 'utf8');
const tableRenderer = new Function(
  'esc',
  'mdInline',
  'markHTML',
  'MARK_TEXT',
  ['SEVERITY_RANK', 'severityColumn'].map((n) => source.match(new RegExp(`^const ${n} = .*$`, 'm'))[0]).join('\n') +
    '\n' +
    ['sortKey', 'compareKeys', 'sortedRows'].map((n) => source.match(new RegExp(`^function ${n}[\\s\\S]*?^\\}`, 'm'))[0]).join('\n') +
    `\nreturn {${source.match(/^ {2}table: s => \{[\s\S]*?^ {2}\},$/m)[0]}}.table;`,
)((t) => String(t), (t) => String(t), () => '[LLM]', { llm: 'Found by the LLM' });

test('a parent row shows a toggle and its folded rows hidden beneath it; a plain row has none (#747)', () => {
  const html = tableRenderer({
    id: 'risks',
    columns: ['Impact', 'Finding'],
    rows: [['High', 'One risk'], ['Medium', 'Two risks of a kind']],
    llmRows: [0, 1],
    subRows: [{ row: 1, rows: [['Medium', 'First'], ['Low', 'Second']], llmRows: [0, 1] }],
  });
  assert.match(html, /<tr><td>\[LLM\] High<\/td><td>One risk<\/td><\/tr>/);
  assert.match(
    html,
    /<tr class="row-parent"><td><button type="button" class="row-toggle" aria-expanded="false" aria-controls="risks-r1-0 risks-r1-1" data-parent-row="1"[^>]*>▸<\/button>\[LLM\] Medium<\/td>/,
  );
  assert.match(html, /<tr class="row-child" id="risks-r1-0" data-parent="1" hidden><td>\[LLM\] Medium<\/td><td>First<\/td><\/tr>/);
  assert.match(html, /<tr class="row-child" id="risks-r1-1" data-parent="1" hidden><td>\[LLM\] Low<\/td><td>Second<\/td><\/tr>/);
  assert.equal(html.match(/row-toggle/g).length, 1);
  assert.match(html, /<div class="tbl-note">\[LLM\] Found by the LLM<\/div>/);
});

test('a table with a severity column starts most severe first and sorts by any column (#934)', () => {
  const html = tableRenderer({
    id: 'risks',
    columns: ['Impact', 'Finding'],
    rows: [['Low', 'Logs kept'], ['High', 'Open admin'], ['Medium', 'No dead letters']],
  });
  assert.deepEqual([...html.matchAll(/<tr><td>(\w+)<\/td>/g)].map((m) => m[1]), ['High', 'Medium', 'Low']);
  assert.match(html, /<th aria-sort="descending"><button type="button" class="th-sort" data-col="0">Impact/);
  assert.match(html, /<th><button type="button" class="th-sort" data-col="1">Finding/);
  assert.doesNotMatch(tableRenderer({ id: 'one', columns: ['A'], rows: [['x']] }), /th-sort/);
  assert.doesNotMatch(html, /tbl-note/);
});

// mdInline is the template's Markdown-lite formatter; load it from the template source.
const template = readFileSync(`${specDir}${spec}.html`, 'utf8');
const mdInline = new Function(
  `${template.match(/^const esc = .*$/m)[0]}\n${template.match(/^function mdInline[\s\S]*?^\}/m)[0]}\nreturn mdInline;`,
)();

test('text inside code spans renders literally', () => {
  assert.equal(mdInline('`/api/auth/**`'), '<code>/api/auth/**</code>');
  assert.equal(mdInline('`a*b*c` and `_x_`'), '<code>a*b*c</code> and <code>_x_</code>');
  assert.equal(mdInline('`<b>&</b>`'), '<code>&lt;b&gt;&amp;&lt;/b&gt;</code>');
});

test('markdown outside code spans renders as before', () => {
  assert.equal(
    mdInline('**bold** *it* `/api/auth/**` [x](https://a.b)'),
    '<b>bold</b> <em>it</em> <code>/api/auth/**</code> <a href="https://a.b" target="_blank" rel="noopener">x</a>',
  );
  assert.equal(mdInline('**a `/x/**` b**'), '<b>a <code>/x/**</code> b</b>');
});

test('NUL characters in the input cannot forge a code span', () => {
  assert.equal(mdInline('a\u00000\u0000b `x`'), 'a0b <code>x</code>');
});

test('a code span inside link text and an unmatched backtick', () => {
  assert.equal(
    mdInline('[`/api/**`](https://a.b)'),
    '<a href="https://a.b" target="_blank" rel="noopener"><code>/api/**</code></a>',
  );
  assert.equal(mdInline('a ` b **c**'), 'a ` b <b>c</b>');
});

test('a backslash shows link, emphasis and code syntax as written (#752)', () => {
  // The body C4ReportRendererTest expects for a responsibility an LLM wrote.
  const body =
    'Serves \\[x\\](https://example.com) and \\*\\*bold\\*\\* *files* under' +
    ' /files/\\*\\*; `/api/\\*\\*`. See [the (notes)](#/context/context-text).';
  const html = mdInline(body);
  assert.equal(
    html,
    'Serves [x](https://example.com) and **bold** <em>files</em> under /files/**;' +
      ' <code>/api/**</code>. See <a href="#/context/context-text">the (notes)</a>.',
  );
  assert.doesNotMatch(html, /<a href="https:|<b>|<strong>/);
  assert.equal(mdInline('a \\\\ b \\` c'), 'a \\ b ` c');
  assert.equal(mdInline('`a \\* b`'), '<code>a * b</code>');
  assert.equal(mdInline('It publishes `order.created`.'), 'It publishes <code>order.created</code>.');
});

// legendHTML draws the legend under each diagram; load it, with the element kinds, from the
// template source.
const marks = ['MARK', 'MARK_TEXT', 'tagOf', 'descOf']
  .map((name) => template.match(new RegExp(`^const ${name} = .*$`, 'm'))[0])
  .join('\n');
const { legendHTML, tagOf, descOf } = new Function(
  `${template.match(/^const KIND = \{[\s\S]*?^\};/m)[0]}\n${marks}\n${template.match(/^function legendHTML[\s\S]*?^\}/m)[0]}\nreturn {legendHTML, tagOf, descOf};`,
)();

test('an inferred description opens with a small mark the legend explains once, not a word (#934)', () => {
  const box = { kind: 'container', desc: 'Sells books.', inferred: true };
  assert.equal(descOf(box), '≈ Sells books.');
  assert.equal(descOf({ ...box, inferred: false }), 'Sells books.');
  assert.equal(descOf({ ...box, desc: '' }), '');
  const legend = legendHTML(['container'], { inferred: true });
  assert.equal(legend.match(/≈/g).length, 1);
  assert.match(legend, /Description inferred from the code, not written by people/);
  assert.doesNotMatch(legendHTML(['container'], {}), /≈|inferred/);
});

test('what the LLM found carries a small mark, not an "LLM ·" badge (#934)', () => {
  assert.equal(tagOf({ kind: 'container', llm: true }), '✦ Container');
  assert.equal(tagOf({ kind: 'container', tag: 'Domain' }), 'Domain');
  assert.match(legendHTML(['container'], { llm: true }), /✦<\/span>Found by the LLM reading the code/);
});

test('the legend has the dashed boundary of a diagram drawn inside one', () => {
  const legend = legendHTML(['container'], { boundary: 'system' });
  assert.match(legend, /<i class="sw bnd k-system"><\/i>Software System boundary/);
  assert.doesNotMatch(legendHTML(['container'], {}), /boundary/);
});

test('the legend of a sequence has the async arrowhead when a message is async', () => {
  const legend = legendHTML(['container'], { seq: true, async: true });
  assert.match(legend, /Call \(waits for the reply\)/);
  assert.match(legend, /class="sw-arrow async"[^]*?<\/svg>Async message \(does not wait\)/);
  assert.match(legend, /Return/);
  assert.doesNotMatch(legendHTML(['container'], { seq: true }), /Async message/);
});

// The after-layout readability check (#366), loaded from the template source like mdInline, with
// the report's own limits (#836).
const loadReadability = (readable) =>
  new Function(
    'DATA',
    `${template.match(/^const READABLE = .*$/m)[0]}\n` +
      `${template.match(/^function readableOf[\s\S]*?^\}/m)[0]}\n` +
      `${template.match(/^function segmentsCross[\s\S]*?^\}/m)[0]}\n` +
      `${template.match(/^function lineCrossings[\s\S]*?^\}/m)[0]}\n` +
      `${template.match(/^function readabilityOf[\s\S]*?^\}/m)[0]}\n` +
      `${template.match(/^const isReadable = .*$/m)[0]}\n` +
      `${template.match(/^function readabilityWhy[\s\S]*?^\}/m)[0]}\n` +
      `${template.match(/^function readabilityGaps[\s\S]*?^\}/m)[0]}\n` +
      'return {READABLE, lineCrossings, readabilityOf, isReadable, readabilityWhy, readabilityGaps};',
  )({ document: { readable } });
const sampleReadable = read(`${specDir}${spec}.sample-data.json`).document.readable;
const readability = loadReadability(sampleReadable);

test('lines that only touch or run side by side do not cross; lines through each other do', () => {
  const { lineCrossings } = readability;
  // A plus sign: one crossing.
  assert.equal(lineCrossings([[[0, 5], [10, 5]], [[5, 0], [5, 10]]]), 1);
  // An L meeting a line at its end, and two parallel lines: none.
  assert.equal(lineCrossings([[[0, 0], [10, 0]], [[10, 0], [10, 10]]]), 0);
  assert.equal(lineCrossings([[[0, 0], [10, 0]], [[0, 2], [10, 2]]]), 0);
  // An orthogonal line with a bend crossing two others.
  assert.equal(lineCrossings([[[0, 5], [20, 5], [20, 20]], [[5, 0], [5, 10]], [[15, 0], [15, 30]]]), 2);
});

test('a view with many crossing lines is not readable; a sparse one that fits is', () => {
  const { READABLE, readabilityOf, isReadable } = readability;
  // A grid of 6 horizontal and 6 vertical lines: 36 crossings.
  const dense = [];
  for (let i = 0; i < 6; i++) {
    dense.push([[0, 50 + i * 100], [700, 50 + i * 100]]);
    dense.push([[50 + i * 100, 0], [50 + i * 100, 700]]);
  }
  const fitting = [0, 0, 900, 600];
  const denseView = readabilityOf(dense, fitting);
  assert.equal(denseView.crossings, 36);
  assert.equal(denseView.fits, true);
  assert.equal(isReadable(denseView), false);
  // Three lines that never meet, on a view that fits: readable.
  const sparse = readabilityOf([[[0, 0], [100, 0]], [[0, 50], [100, 50]], [[0, 100], [100, 100]]], fitting);
  assert.deepEqual([sparse.crossings, sparse.fits, isReadable(sparse)], [0, true, true]);
  // The same lines on a view too large for the page at a readable scale: not readable.
  const huge = readabilityOf([[[0, 0], [100, 0]]], [0, 0, READABLE.canvas[0] / READABLE.scale + 1, 400]);
  assert.deepEqual([huge.fits, isReadable(huge)], [false, false]);
});

test("a page's quality report lists each of its views that is not readable after layout", () => {
  const { READABLE, readabilityGaps } = readability;
  const page = {
    id: 'containers',
    title: 'Containers',
    sections: [
      { type: 'diagram', id: 'dense', caption: 'Requests [all]', readability: { crossings: READABLE.maxCrossings + 1, fits: true, width: 900, height: 600 } },
      { type: 'diagram', id: 'sparse', readability: { crossings: 0, fits: true, width: 900, height: 600 } },
      { type: 'diagram', id: 'huge', readability: { crossings: 0, fits: false, width: 4000, height: 900 } },
      { type: 'text', id: 'prose', readability: { crossings: 99, fits: false } },
    ],
  };
  assert.deepEqual(readabilityGaps(page), [
    `**Readable after layout** ([Requests \\[all\\]](#/containers/dense)): its lines cross ${READABLE.maxCrossings + 1} times (at most ${READABLE.maxCrossings}).`,
    '**Readable after layout** ([Containers](#/containers/huge)): it is 4000 × 900 px, too large to read without zooming.',
  ]);
  assert.deepEqual(readabilityGaps({ id: 'p', title: 'P', sections: [page.sections[1]] }), []);
});

// The quality reports the gaps reach, with the page's other parts stood in for.
function reports(pages) {
  const measured = [];
  return {
    measured,
    ...new Function(
      'PAGES',
      'measured',
      'DATA',
      `${template.match(/^const READABLE = .*$/m)[0]}\n` +
        `${template.match(/^function readableOf[\s\S]*?^\}/m)[0]}\n` +
        `${template.match(/^const isReadable = .*$/m)[0]}\n` +
        `${template.match(/^function readabilityWhy[\s\S]*?^\}/m)[0]}\n` +
        `${template.match(/^function readabilityGaps[\s\S]*?^\}/m)[0]}\n` +
        `${template.match(/^function qualitySection[\s\S]*?^\}/m)[0]}\n` +
        `${template.match(/^function afterLayoutReport[\s\S]*?^\}/m)[0]}\n` +
        'const SECTION_BASE = {callout: s => "callout:" + s.body, list: s => "list:" + JSON.stringify(s.items)};\n' +
        'const renderSection = (s) => s.title + ":" + JSON.stringify(s.items);\n' +
        'function measureAll(){ measured.push(true); }\n' +
        'return {qualitySection, afterLayoutReport};',
    )(pages, measured, { document: { readable: sampleReadable } }),
  };
}

test('a page with a view not readable gets a quality report when it has none; one with a report does not', () => {
  const huge = { type: 'diagram', id: 'huge', readability: { crossings: 0, fits: false, width: 4000, height: 900 } };
  const { afterLayoutReport } = reports([]);
  assert.equal(
    afterLayoutReport({ id: 'c', title: 'Components', sections: [huge] }),
    'Quality report:["**Readable after layout** ([Components](#/c/huge)): it is 4000 × 900 px, too large to read without zooming."]',
  );
  assert.equal(afterLayoutReport({ id: 'c', title: 'C', sections: [huge, { type: 'list', id: 'q', quality: true, items: [] }] }), '');
  assert.equal(afterLayoutReport({ id: 'c', title: 'C', sections: [{ ...huge, readability: { crossings: 0, fits: true, width: 900, height: 600 } }] }), '');
});

test('the report that says every check passed measures every view first and lists the gaps of every page', () => {
  const later = { id: 'containers', title: 'Containers', sections: [{ type: 'diagram', id: 'dense', readability: { crossings: 50, fits: true, width: 900, height: 600 } }] };
  const first = { id: 'context', title: 'Context', sections: [] };
  const { qualitySection, measured } = reports([first, later]);
  const passed = { type: 'callout', id: 'q', quality: true, body: 'Every check passed.' };
  assert.equal(qualitySection(passed, first), 'list:["**Readable after layout** ([Containers](#/containers/dense)): its lines cross 50 times (at most 5)."]');
  assert.equal(measured.length, 1);
  assert.equal(reports([first]).qualitySection(passed, first), 'callout:Every check passed.');
});

test("the template judges a view by the report's own limits, and none without them (#836)", () => {
  // One view: 1000 px wide on the 1632 px page, its lines crossing 8 times.
  const grid = [];
  for (let i = 0; i < 2; i++) grid.push([[0, 50 + i * 100], [700, 50 + i * 100]]);
  for (let i = 0; i < 4; i++) grid.push([[50 + i * 100, 0], [50 + i * 100, 700]]);
  const view = [0, 0, 1632 / 0.7, 600];
  const strict = loadReadability({ ...sampleReadable, scale: 0.8, crossings: 5 });
  const loose = loadReadability({ ...sampleReadable, scale: 0.6, crossings: 10 });
  assert.equal(strict.readabilityOf(grid, view).crossings, 8);
  assert.match(strict.readabilityWhy(strict.readabilityOf(grid, view)), /too large.*cross 8 times \(at most 5\)/);
  assert.equal(loose.readabilityWhy(loose.readabilityOf(grid, view)), null);
  // Fit is by width alone (owner's decision of 4 Nov 2026 on #764): a narrow view taller than the
  // page fits and scrolls.
  const tall = strict.readabilityOf([], [0, 0, 1500, 3000]);
  assert.equal(tall.fits, true);
  // A report without limits: no view is judged, so its quality report lists no gap.
  const none = loadReadability(undefined);
  assert.equal(none.READABLE, null);
  const page = { id: 'p', title: 'P', sections: [{ type: 'diagram', id: 'd', readability: { crossings: 99, fits: false, width: 9000, height: 900 } }] };
  assert.deepEqual(none.readabilityGaps(page), []);
  assert.equal(strict.readabilityGaps(page).length, 1);
});

// pageTree builds the sidebar's tree (#931); load it from the template source.
const pageTree = new Function(`${template.match(/^function pageTree[\s\S]*?^\}/m)?.[0]}\nreturn pageTree;`)();

test("the sidebar nests a unit's page under its domain's and an area's under its unit's (#931)", () => {
  const tree = pageTree([
    { id: 'context', level: 'context' },
    { id: 'overview', level: 'containers' },
    { id: 'sales', level: 'containers' },
    { id: 'orders', level: 'components', parent: 'sales' },
    { id: 'checkout', level: 'components', parent: 'orders' },
    { id: 'gateway', level: 'components' },
    { id: 'lost', level: 'components', parent: 'no-such-page' },
  ]);
  assert.deepEqual(tree.roots, {
    context: ['context'],
    containers: ['overview', 'sales'],
    components: ['gateway', 'lost'],
  });
  assert.deepEqual(tree.kids, { sales: ['orders'], orders: ['checkout'] });
});

test('a page must name an existing page as its parent, and no page may be its own ancestor (#931)', () => {
  const data = read(`${specDir}${spec}.sample-data.json`);
  const [first, second] = data.pages;
  const named = structuredClone(data);
  named.pages[1].parent = 'no-such-page';
  assert.ok(reportSpecIssues(spec, named).some((i) => i.includes('no-such-page')));
  const cycle = structuredClone(data);
  cycle.pages[0].parent = second.id;
  cycle.pages[1].parent = first.id;
  assert.ok(reportSpecIssues(spec, cycle).some((i) => i.includes('its own ancestor')));
});

// Sorting a table (#934), loaded from the template source.
const { sortedRows, severityColumn } = new Function(
  `${template.match(/^const SEVERITY_RANK = .*$/m)[0]}\n` +
    `${template.match(/^function sortKey[\s\S]*?^\}/m)[0]}\n` +
    `${template.match(/^function compareKeys[\s\S]*?^\}/m)[0]}\n` +
    `${template.match(/^const severityColumn = .*$/m)[0]}\n` +
    `${template.match(/^function sortedRows[\s\S]*?^\}/m)[0]}\n` +
    'return {sortedRows, severityColumn};',
)();

test('a table sorts by any column: severity by how serious, numbers by value, text as text (#934)', () => {
  const rows = [
    ['Low', 'Logs are kept forever', '40%'],
    ['Critical', 'Payments are not idempotent', '90%'],
    ['Medium', 'A queue has no dead letters', '7%'],
    ['High', 'Admin endpoints are open', '75%'],
  ];
  const columns = ['Impact', 'Finding', 'Confidence'];
  assert.equal(severityColumn(columns), 0);
  assert.equal(severityColumn(['Finding', 'Evidence']), -1);
  assert.deepEqual(sortedRows(rows, 0, 'desc'), [1, 3, 2, 0]);
  assert.deepEqual(sortedRows(rows, 0, 'asc'), [0, 2, 3, 1]);
  assert.deepEqual(sortedRows(rows, 2, 'asc'), [2, 0, 3, 1]);
  assert.deepEqual(sortedRows(rows, 1, 'asc'), [2, 3, 0, 1]);
  // a mark or toggle before the word does not hide it; an empty cell goes last either way
  assert.deepEqual(sortedRows([['✦ Low'], ['▸✦ High'], ['']], 0, 'desc'), [1, 0, 2]);
  assert.deepEqual(sortedRows([[''], ['50%'], ['7%']], 0, 'desc'), [1, 2, 0]);
  // words that are no severity stay after the severities in either direction
  assert.deepEqual(sortedRows([['Unknown'], ['Critical'], ['Low']], 0, 'desc'), [1, 2, 0]);
  // ties keep the order they had
  assert.deepEqual(sortedRows([['High', 'b'], ['High', 'a']], 0, 'desc'), [0, 1]);
});

// The index page's opening (#934), loaded from the template source.
const analyzedHTML = new Function(
  `${template.match(/^const esc = .*$/m)[0]}\n${template.match(/^const fmtDate = .*$/m)[0]}\n` +
    `${template.match(/^function analyzedHTML[\s\S]*?^\}/m)[0]}\nreturn analyzedHTML;`,
)();

test('the commits sit behind one short "Analyzed" line that opens into the list (#934)', () => {
  const html = analyzedHTML({ updated: '2026-11-07', commits: { billing: 'a394af573b6c', web: '50a3be190c6f' } });
  assert.match(html, /^<details class="pill analyzed"><summary>Analyzed 7 Nov 2026 · 2 repositories<\/summary>/);
  assert.match(html, /<li>billing <code>a394af573b6c<\/code><\/li><li>web <code>50a3be190c6f<\/code><\/li>/);
  assert.match(analyzedHTML({ commits: { one: 'abc' } }), /<summary>1 repository<\/summary>/);
  assert.equal(analyzedHTML({ updated: '2026-11-07' }), '');
});

// A diagram's lines (#934), loaded from the template source.
const { mergedEdges } = new Function(
  `const MARK = {llm:'✦'};\n${template.match(/^function shortTech[\s\S]*?^\}/m)[0]}\n` +
    `${template.match(/^function mergedEdges[\s\S]*?^\}/m)[0]}\nreturn {mergedEdges};`,
)();

test('two lines with the same ends and direction become one, with the shortest label and every technology (#934)', () => {
  const edges = mergedEdges([
    { from: 'web', to: 'gw', label: 'Sends customer web requests through the gateway.', technology: 'HTTP' },
    { from: 'web', to: 'gw', label: 'Sends customer web requests', technology: 'JSON/HTTP' },
    { from: 'gw', to: 'web', label: 'Answers', technology: 'HTTP' },
  ]);
  assert.deepEqual(edges, [
    { f: 'web', t: 'gw', l: 'Sends customer web requests', llm: false, tech: 'HTTP, JSON/HTTP' },
    { f: 'gw', t: 'web', l: 'Answers', llm: false, tech: 'HTTP' },
  ]);
});

test('a long list of routing keys on a line gives the protocol and a count (#934)', () => {
  const [edge] = mergedEdges([
    {
      from: 'a',
      to: 'b',
      label: 'Receives payment state changes',
      technology: 'AMQP, notification.maintenance.reminders, notification.payment.events, notification.workorder.cancelled',
    },
  ]);
  assert.equal(edge.tech, 'AMQP · 3 more');
  assert.equal(mergedEdges([{ from: 'a', to: 'b', technology: 'AMQP, orders.created' }])[0].tech, 'AMQP, orders.created');
  assert.equal(mergedEdges([{ from: 'a', to: 'b', label: 'x', technology: 'HTTP', llm: true }])[0].l, '✦ x');
});

test('opposite lines that say the same are one line with an arrowhead at each end; different ones stay two (#934)', () => {
  const edges = mergedEdges([
    { from: 'web', to: 'gw', label: 'Sends customer web requests', technology: 'JSON/HTTP' },
    { from: 'gw', to: 'web', label: 'Sends customer web requests through the gateway.', technology: 'HTTP' },
    { from: 'gw', to: 'db', label: 'Keeps request counts', technology: 'RESP' },
    { from: 'db', to: 'gw', label: 'Notifies of expiry', technology: 'RESP' },
  ]);
  assert.deepEqual(edges[0], { f: 'web', t: 'gw', l: 'Sends customer web requests', llm: false, tech: 'JSON/HTTP, HTTP', both: true });
  assert.equal(edges.length, 3);
  assert.ok(edges.slice(1).every((e) => !e.both));
});

test('a bare verb neither wins a merge nor makes two opposite lines one (#934)', () => {
  const [one] = mergedEdges([
    { from: 'a', to: 'b', label: 'Uses' },
    { from: 'a', to: 'b', label: 'Fetches technician shifts for scheduling' },
  ]);
  assert.equal(one.l, 'Fetches technician shifts for scheduling');
  assert.equal(mergedEdges([{ from: 'a', to: 'b', label: 'Sends' }, { from: 'b', to: 'a', label: 'Sends payment results' }]).length, 2);
});

test("a diagram's canvas is at most its own size plus a margin, and the screen caps its height (#934)", () => {
  const canvasFit = new Function(`${template.match(/^const canvasFit = .*$/m)[0]}\nreturn canvasFit;`)();
  assert.equal(canvasFit([0, 0, 400.2, 300]), ' style="--canvas-h:324px;--canvas-w:425px"');
  assert.equal(canvasFit(null), '');
  assert.match(template, /\.dcanvas\{[^}]*height:min\(max\(320px,calc\(100vh - var\(--canvas-top,250px\)\)\),var\(--canvas-h,9999px\)\)/);
});

// The sidebar's badges and counts (#953), loaded from the template source.
const loadNav = (DATA, TREE, PAGE) =>
  new Function(
    'DATA',
    'TREE',
    'PAGE',
    `${template.match(/^const esc = .*$/m)[0]}\nconst ICON = {chev:''};\n` +
      `${template.match(/^const NAV_BADGE = .*$/m)[0]}\n${template.match(/^const NAV_BADGE_TEXT = .*$/m)[0]}\n` +
      `${template.match(/^function navKind[\s\S]*?^\}/m)[0]}\n${template.match(/^function navItem[\s\S]*?^\}/m)[0]}\n` +
      'return {navKind, navItem};',
  )(DATA, TREE, PAGE);

test('each sidebar item carries a badge for what it is about, and an item with pages beneath shows their count (#953)', () => {
  const DATA = {
    elements: {
      d: { kind: 'container', tag: 'Domain' },
      c: { kind: 'container', tag: 'Service' },
      a: { kind: 'component', tag: 'Feature area' },
      p: { kind: 'component' },
    },
  };
  const pages = {
    sales: { id: 'sales', level: 'containers', title: 'Sales', element: 'd' },
    orders: { id: 'orders', level: 'components', title: 'Orders', element: 'c', parent: 'sales' },
    checkout: { id: 'checkout', level: 'components', title: 'Checkout', element: 'a', parent: 'orders' },
    live: { id: 'live', level: 'components', title: 'Live push', parent: 'orders' },
    one: { id: 'one', level: 'components', title: 'OrderController', element: 'p' },
    loose: { id: 'loose', level: 'containers', title: 'In no domain' },
  };
  const { navKind, navItem } = loadNav(DATA, { kids: { sales: ['orders'], orders: ['checkout', 'live'] } }, pages);
  assert.deepEqual(
    Object.values(pages).map(navKind),
    ['domain', 'container', 'area', 'area', 'component', null],
  );
  const html = navItem('sales');
  assert.match(html, /<span class="nb" title="Domain">DO<\/span><span class="nav-t">Sales<\/span><\/a><em class="nav-count"[^>]*>1<\/em>/);
  assert.match(html, /title="Container">CO<\/span><span class="nav-t">Orders<\/span><\/a><em class="nav-count"[^>]*>2<\/em>/);
  assert.match(html, /title="Feature area">FA<\/span><span class="nav-t">Checkout/);
  assert.doesNotMatch(navItem('loose'), /class="nb"|nav-count/);
});
