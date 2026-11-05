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
const tableRenderer = new Function(
  'esc',
  'mdInline',
  'llmBadge',
  `return {${readFileSync(`${specDir}${spec}.html`, 'utf8').match(/^ {2}table: s => \{[\s\S]*?^ {2}\},$/m)[0]}}.table;`,
)((t) => String(t), (t) => String(t), '[LLM]');

test('a parent row shows a toggle and its folded rows hidden beneath it; a plain row has none (#747)', () => {
  const html = tableRenderer({
    columns: ['Impact', 'Finding'],
    rows: [['High', 'One risk'], ['Medium', 'Two risks of a kind']],
    llmRows: [0, 1],
    subRows: [{ row: 1, rows: [['Medium', 'First'], ['Low', 'Second']], llmRows: [0, 1] }],
  });
  assert.match(html, /<tr><td>\[LLM\] High<\/td><td>One risk<\/td><\/tr>/);
  assert.match(
    html,
    /<tr class="row-parent"><td><button type="button" class="row-toggle" aria-expanded="false" data-rows="1"[^>]*>▸<\/button>\[LLM\] Medium<\/td>/,
  );
  assert.match(html, /<tr class="row-child" data-parent="1" hidden><td>\[LLM\] Medium<\/td><td>First<\/td><\/tr>/);
  assert.match(html, /<tr class="row-child" data-parent="1" hidden><td>\[LLM\] Low<\/td><td>Second<\/td><\/tr>/);
  assert.equal(html.match(/row-toggle/g).length, 1);
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
const legendHTML = new Function(
  `${template.match(/^const KIND = \{[\s\S]*?^\};/m)[0]}\n${template.match(/^function legendHTML[\s\S]*?^\}/m)[0]}\nreturn legendHTML;`,
)();

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

// The after-layout readability check (#366), loaded from the template source like mdInline.
const readability = new Function(
  `${template.match(/^const READABLE = \{[\s\S]*?^\};$/m)[0]}\n` +
    `${template.match(/^function segmentsCross[\s\S]*?^\}/m)[0]}\n` +
    `${template.match(/^function lineCrossings[\s\S]*?^\}/m)[0]}\n` +
    `${template.match(/^function readabilityOf[\s\S]*?^\}/m)[0]}\n` +
    `${template.match(/^const isReadable = .*$/m)[0]}\n` +
    `${template.match(/^function readabilityWhy[\s\S]*?^\}/m)[0]}\n` +
    `${template.match(/^function readabilityGaps[\s\S]*?^\}/m)[0]}\n` +
    'return {READABLE, lineCrossings, readabilityOf, isReadable, readabilityGaps};',
)();

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
      `${template.match(/^const READABLE = \{[\s\S]*?^\};$/m)[0]}\n` +
        `${template.match(/^const isReadable = .*$/m)[0]}\n` +
        `${template.match(/^function readabilityWhy[\s\S]*?^\}/m)[0]}\n` +
        `${template.match(/^function readabilityGaps[\s\S]*?^\}/m)[0]}\n` +
        `${template.match(/^function qualitySection[\s\S]*?^\}/m)[0]}\n` +
        `${template.match(/^function afterLayoutReport[\s\S]*?^\}/m)[0]}\n` +
        'const SECTION_BASE = {callout: s => "callout:" + s.body, list: s => "list:" + JSON.stringify(s.items)};\n' +
        'const renderSection = (s) => s.title + ":" + JSON.stringify(s.items);\n' +
        'function measureAll(){ measured.push(true); }\n' +
        'return {qualitySection, afterLayoutReport};',
    )(pages, measured),
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
