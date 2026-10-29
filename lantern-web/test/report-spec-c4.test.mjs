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
