// The synthetic views of the diagram-limit measurement (#836): shaped like Lantern's, and the same
// on every run.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
import { batchDocument, batches, readableOf, report, shapeOf, summarize, TEMPLATE } from '../scripts/diagram-limit.mjs';

const shape = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../docs/research/diagram-element-limit/shape.json', import.meta.url)), 'utf8'),
);

test('a batch is the same on every run, and a view the same whatever batch draws it', () => {
  const whole = { level: 'containers', n: 20, density: 1.5, frame: true, views: 4 };
  assert.deepEqual(batchDocument(shape, whole), batchDocument(shape, whole));
  const part = batchDocument(shape, { ...whole, from: 2, to: 3 });
  const all = batchDocument(shape, whole);
  assert.deepEqual(part.pages, [all.pages[2]]);
});

test('a synthetic view has the elements and about the lines asked for, framed or not', () => {
  for (const level of ['containers', 'components']) {
    for (const frame of [true, false]) {
      const doc = batchDocument(shape, { level, n: 25, density: 2, frame, views: 3 });
      for (const page of doc.pages) {
        const section = page.sections[0];
        const ids = new Set([...section.relationships.flatMap((r) => [r.from, r.to]), ...(section.elements ?? [])]);
        assert.equal(ids.size, 25);
        assert.ok(section.relationships.length >= 45 && section.relationships.length <= 50, `${section.relationships.length} lines`);
        assert.equal(Boolean(section.boundary), frame);
        assert.equal([...ids].some((id) => doc.elements[id].parent), frame);
        // one line per ordered pair, none to itself
        const pairs = section.relationships.map((r) => `${r.from}>${r.to}`);
        assert.equal(new Set(pairs).size, pairs.length);
        assert.ok(section.relationships.every((r) => r.from !== r.to));
      }
      assert.deepEqual(doc.document.readable, readableOf());
    }
  }
});

test('the shape is read from the container and component views of real reports', () => {
  const doc = {
    document: { title: 't' },
    elements: {
      s: { kind: 'system', name: 'Shop' },
      a: { kind: 'container', name: 'orders', parent: 's', technology: 'Java' },
      b: { kind: 'database', name: 'orders db', parent: 's' },
      x: { kind: 'external', name: 'Pay' },
    },
    pages: [
      { level: 'context', sections: [{ type: 'diagram', relationships: [{ from: 's', to: 'x' }] }] },
      {
        level: 'containers',
        sections: [{ type: 'diagram', boundary: 's', relationships: [{ from: 'a', to: 'b', label: 'Stores orders' }, { from: 'a', to: 'x' }] }],
      },
    ],
  };
  const s = shapeOf([doc]);
  assert.equal(s.containers.views, 1);
  assert.deepEqual(s.containers.elements, [3]);
  assert.deepEqual(s.containers.places, { 'container/in': 1, 'database/in': 1, 'external/out': 1 });
  assert.deepEqual(s.containers.pairs, { 'container/in>database/in': 1, 'container/in>external/out': 1 });
  assert.equal(s.components.views, 0);
  assert.deepEqual(s.lengths.label, [0, 13]);
});

test('every variant is drawn, the large ones a view to a batch', () => {
  const all = batches(50);
  const views = (pred) => all.filter(pred).reduce((n, b) => n + b.to - b.from, 0);
  assert.equal(views((b) => b.level === 'containers' && b.n === 20 && b.density === 1.5 && b.frame), 50);
  assert.equal(views((b) => b.level === 'components' && b.n === 40 && b.density === 2), 10);
  assert.ok(all.filter((b) => b.n >= 35).every((b) => b.to - b.from === 1));
});

test('the summary gives per variant the share of views that pass each check', () => {
  // Fit is by width alone (owner's decision of 4 Nov 2026): a tall view that is narrow enough fits.
  const row = (crossings, width, through = 0, height = 900) => ({
    level: 'containers', n: 10, density: 1, frame: true, elements: 10, crossings, width, height,
    fits: 1600 / width >= 0.8 && 1000 / height >= 0.8, through, labelOverlaps: 0, cost: 20, violations: 0,
  });
  const rows = [row(0, 1600), row(9, 1600), row(0, 3200), row(0, 1600, 2), row(0, 1600, 0, 4000)];
  const [s] = summarize(rows, { scale: 0.8, crossings: 5, canvasWidth: 1600 });
  assert.deepEqual([s.views, s.fits, s.fitsWhole, s.crossingsOk, s.readable, s.clean], [5, 0.8, 0.6, 0.8, 0.6, 0.4]);
  assert.ok(s.fitsLow < s.fits && s.fitsLow > 0);
});

test('the report written for the browser holds the views and the probe, and closes no script early', () => {
  const doc = batchDocument(shape, { level: 'components', n: 5, density: 1, frame: true, views: 1 });
  doc.elements[Object.keys(doc.elements)[0]].name = '</script><b>';
  const html = report(readFileSync(TEMPLATE, 'utf8'), doc, true);
  assert.ok(html.includes('\\u003c/script>\\u003cb>'));
  assert.ok(html.includes('id = \'lantern-out\''));
});
