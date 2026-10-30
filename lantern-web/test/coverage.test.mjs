// Tests the extraction coverage report (scripts/coverage.mjs; docs/adr/0089-extraction-coverage.md):
// which reference items are backed by a fact of their area on their cited lines, and what the map
// check finds wrong.
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { test } from 'vitest';
import {
  checkCitations,
  checkMap,
  composeServices,
  coverage,
  coverageItems,
  coverageMarkdown,
  parseAt,
  readFacts,
} from '../scripts/coverage.mjs';

/** A reference with a service, its database, a person, a component and two container lines. */
const reference = {
  elements: {
    sys: { kind: 'system', name: 'Shop' },
    user: { kind: 'person', name: 'User' },
    orders: { kind: 'container', name: 'order-service', parent: 'sys' },
    db: { kind: 'database', name: 'orders_db', parent: 'sys' },
    api: { kind: 'component', name: 'Order API', parent: 'orders' },
  },
  pages: [
    {
      id: 'containers',
      level: 'containers',
      sections: [
        {
          type: 'diagram',
          id: 'd',
          relationships: [
            { from: 'user', to: 'orders', technology: 'HTTP' },
            { from: 'orders', to: 'db', technology: 'JDBC' },
          ],
        },
      ],
    },
  ],
};

const map = {
  items: {
    user: { linking: 'Inferred from the endpoints.' },
    orders: {
      needs: [
        { area: 'maven', at: 'shop:orders/pom.xml:5' },
        { area: 'spring', at: 'shop:orders/src/App.java:7-9' },
      ],
    },
    db: { needs: [{ area: 'config', at: 'shop:orders/application.yml:4' }] },
    api: { needs: [{ area: 'spring', at: 'shop:orders/src/Api.java:10' }] },
    'user -> orders': { linking: 'A person reaches the service through its API.' },
    'orders -> db': { needs: [{ area: 'persistence', at: 'shop:orders/src/Repo.java:3' }] },
  },
};

const fact = (extractor, path, start, end, predicate = 'P') => ({
  subject: 'service:orders',
  predicate,
  object: { kind: 'string', value: 'x' },
  origin: { kind: 'static', name: extractor, version: '1' },
  evidence: { repo: 'shop', commit: 'a'.repeat(40), path, ...(start ? { lines: { start, end } } : {}) },
});

test('a citation names a repository, a path and optionally a line range', () => {
  assert.deepEqual(parseAt('shop:a/b.yml:4'), { repo: 'shop', path: 'a/b.yml', start: 4, end: 4 });
  assert.deepEqual(parseAt('shop:a/b.yml:4-9'), { repo: 'shop', path: 'a/b.yml', start: 4, end: 9 });
  assert.deepEqual(parseAt('shop:Dockerfile'), { repo: 'shop', path: 'Dockerfile', start: null, end: null });
  assert.equal(parseAt('shop:a.yml:9-4'), null);
  assert.equal(parseAt('a.yml'), null);
  assert.equal(parseAt('shop:a.yml:0'), null);
});

test('the items are the scored elements, the components and the container lines', () => {
  const items = coverageItems(reference, map);
  assert.deepEqual(
    items.map((item) => `${item.category} ${item.key}`),
    ['containers orders', 'dataStores db', 'people user', 'components api', 'connections user -> orders', 'connections orders -> db'],
  );
  assert.deepEqual(checkMap(items, map), []);
});

test('the map check names unmapped, unknown and malformed entries', () => {
  const items = coverageItems(reference, map);
  const broken = {
    items: {
      ...map.items,
      db: { needs: [{ area: 'orm', at: 'orders/application.yml' }] },
      api: {},
      ghost: { linking: 'x' },
    },
  };
  delete broken.items['orders -> db'];
  assert.deepEqual(checkMap(items, broken), [
    'orders -> db: not mapped',
    "db: unknown area 'orm'",
    "db: bad citation 'orders/application.yml'",
    'api: neither needs nor linking',
    'ghost: not an item of the reference',
  ]);
});

test('a need is met only by a fact of its area on the cited lines', () => {
  const facts = [
    fact('maven', 'orders/pom.xml', 1, 20),
    fact('spring', 'orders/src/App.java', 9, 9),
    // Another area on the database's line: the wrong area, not a match.
    fact('java', 'orders/application.yml', 4, 4),
    // The right area, but lines that do not overlap the citation.
    fact('spring', 'orders/src/Api.java', 30, 40),
    // Evidence without lines covers its whole file.
    fact('persistence', 'orders/src/Repo.java'),
  ];
  const card = coverage(facts, coverageItems(reference, map), map);
  const status = Object.fromEntries(card.items.map((item) => [item.key, item.status]));
  assert.deepEqual(status, {
    orders: 'backed',
    db: 'missing',
    user: 'linking',
    api: 'missing',
    'user -> orders': 'linking',
    'orders -> db': 'backed',
  });
  assert.deepEqual(card.items.find((item) => item.key === 'db').needs[0].otherAreas, ['java']);
  assert.deepEqual(card.categories.containers, { items: 1, linking: 0, backed: 1, partial: 0, missing: 0 });
  assert.deepEqual(card.areas.spring, { needs: 2, met: 1 });
  assert.deepEqual(card.extractors.maven, { P: 1 });

  const markdown = coverageMarkdown({ set: 'shop', ...card });
  assert.match(markdown, /\| Containers \| 1\/1 \(100%\) \| 0 \| 0 \| 0 \|/);
  assert.match(markdown, /\*\*orders_db\*\* \(missing\): no config at shop:orders\/application.yml:4, only java there/);
  assert.match(markdown, /\*\*Order API\*\* \(missing\): no spring at shop:orders\/src\/Api.java:10, no fact there/);
});

test('an item with some of its needs met is partly backed', () => {
  const card = coverage([fact('maven', 'orders/pom.xml', 5, 5)], coverageItems(reference, map), map);
  assert.equal(card.items.find((item) => item.key === 'orders').status, 'partial');
});

test('a set without a reference lists its items in its map', () => {
  const own = {
    items: {
      orders: { kind: 'container', name: 'order-service', needs: [{ area: 'maven', at: 'shop:pom.xml:3' }] },
      'orders -> db': { kind: 'connection', name: 'order-service → db', linking: 'x' },
    },
  };
  const items = coverageItems(null, own);
  assert.deepEqual(items.map((item) => item.category), ['containers', 'connections']);
  assert.deepEqual(checkMap(items, own), []);
});

test('the facts are read from gzipped JSON lines', () => {
  const dir = mkdtempSync(join(tmpdir(), 'coverage-'));
  try {
    const file = join(dir, 'extracted.jsonl.gz');
    const facts = [fact('maven', 'pom.xml', 1, 2), fact('config', 'a.yml', 3, 3)];
    writeFileSync(file, gzipSync(`${facts.map((f) => JSON.stringify(f)).join('\n')}\n`));
    assert.deepEqual(readFacts(file), facts);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('the citation check finds missing files, lines past the end and ranges across compose services', () => {
  const dir = mkdtempSync(join(tmpdir(), 'coverage-'));
  try {
    mkdirSync(join(dir, 'shop'));
    writeFileSync(join(dir, 'shop', 'a.yml'), 'one\ntwo\n');
    const compose = 'networks:\n  net:\n\nservices:\n    db:\n        image: pg\n\n    app:\n        image: app\n        environment:\n            DB: db\n';
    writeFileSync(join(dir, 'shop', 'compose.yaml'), compose);
    assert.deepEqual(composeServices(compose), [
      { start: 5, end: 7 },
      { start: 8, end: 11 },
    ]);
    const at = (...cites) => ({ items: { x: { needs: cites.map((c) => ({ area: 'deployment', at: c })) } } });
    assert.deepEqual(checkCitations(at('shop:a.yml:2', 'shop:compose.yaml:8-11', 'shop:compose.yaml:5-7', 'shop:compose.yaml'), dir), []);
    assert.deepEqual(checkCitations(at('shop:a.yml:3', 'shop:b.yml:1', 'shop:compose.yaml:2-9', 'shop:compose.yaml:10'), dir), [
      'x: shop:a.yml:3: the file has 2 lines',
      'x: shop:b.yml:1: no such file',
      'x: shop:compose.yaml:2-9: not one compose service from its first line',
      'x: shop:compose.yaml:10: not one compose service from its first line',
    ]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
