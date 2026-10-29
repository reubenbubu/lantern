// Tests the scoring harness (scripts/score.mjs; docs/adr/0072-scoring-harness.md): how report
// elements, connections, flows, risks and decisions are matched with a reference's, and that
// snapshot-4's project-4 report, the one the course correction assessed (docs/adr/0070), scores
// as that assessment counted.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
import {
  compareMarkdown,
  normalizeName,
  normalizeTrigger,
  protocolOf,
  readReport,
  score,
  scoreFiles,
  scorecardMarkdown,
  subjectOverlap,
  subjectWords,
} from '../scripts/score.mjs';

const testsets = fileURLToPath(new URL('../../testsets/', import.meta.url));
const fixture = fileURLToPath(new URL('./fixtures/project-4-snapshot-4.c4.json', import.meta.url));

const page = (id, level, sections, extra = {}) => ({ id, level, title: id, sections, ...extra });
const diagram = (id, relationships) => ({ type: 'diagram', id, relationships });

/** A small reference: a gateway, a service, its database, an exchange, a person and a flow. */
const reference = {
  document: { title: 'Ref', updated: '2026-10-29', subtitle: 'At commit `abcdef1`.' },
  elements: {
    user: { kind: 'person', name: 'User' },
    sys: { kind: 'system', name: 'Shop' },
    gw: { kind: 'container', name: 'api-gateway', parent: 'sys', technology: 'Boot' },
    orders: { kind: 'container', name: 'order-service', parent: 'sys', technology: 'Boot' },
    db: { kind: 'database', name: 'orders_db', parent: 'sys' },
    events: { kind: 'queue', name: 'orders.events', parent: 'sys' },
    api: { kind: 'component', name: 'Order API', parent: 'orders', technology: 'MVC' },
  },
  pages: [
    page('ctx', 'context', [
      {
        type: 'table',
        id: 'risks',
        title: 'Risks',
        columns: ['Impact', 'Finding', 'Consequence'],
        rows: [
          ['High', 'The `OrderController` accepts any `customerId` without a check.', 'Data leak.'],
          ['Low', 'Single PostgreSQL instance with no replica.', 'Outage.'],
        ],
      },
      { type: 'decisions', id: 'decisions', items: [{ title: 'Database per service', body: 'orders_db only.' }] },
      { type: 'table', id: 'glossary', title: 'Glossary', columns: ['Term', 'Meaning'], rows: [['JWT', 'A token.']] },
    ]),
    page('cnt', 'containers', [
      diagram('d', [
        { from: 'user', to: 'gw', label: 'Orders', technology: 'HTTPS' },
        { from: 'gw', to: 'orders', label: 'Routes', technology: 'HTTP' },
        { from: 'orders', to: 'db', label: 'Stores', technology: 'JDBC' },
        { from: 'orders', to: 'events', label: 'Publishes', technology: 'AMQP' },
      ]),
      // The same line again on another diagram counts once.
      diagram('d2', [{ from: 'orders', to: 'db', label: 'Stores', technology: 'JDBC' }]),
    ]),
    page('seq', 'sequence', [
      {
        type: 'sequence',
        id: 'place',
        participants: ['user', 'gw', 'orders', 'db'],
        steps: [
          { from: 'user', to: 'gw', message: 'Places' },
          { from: 'gw', to: 'orders', message: 'Forwards', code: 'POST /api/orders/{orderId}/items [JSON/HTTP]' },
        ],
      },
    ], { title: 'Place an order' }),
  ],
};

/** A report of the same system plus an unrelated one, as reports were before one system per run. */
const report = {
  document: { title: 'System', updated: '2026-10-29', subtitle: 'Pinned to `1234567`.' },
  elements: {
    s: { kind: 'system', name: 'System' },
    g: { kind: 'container', name: 'API-Gateway', parent: 's', technology: 'Boot', description: 'Routes.' },
    o: { kind: 'container', name: 'orders', parent: 's', technology: 'Boot', description: 'Orders.' },
    e: { kind: 'queue', name: 'orders.events', parent: 's', description: 'Events.' },
    x: { kind: 'queue', name: 'orders.dead-letters', parent: 's', description: 'Dead letters.' },
    c1: { kind: 'component', name: 'OrderController', parent: 'o', technology: 'MVC', description: 'API.' },
    other: { kind: 'container', name: 'billing', parent: 's', technology: 'Boot', description: 'Bills.' },
    otherDb: { kind: 'database', name: 'billing_db', parent: 's', description: 'Bills.' },
  },
  pages: [
    page('cnt', 'containers', [
      diagram('d', [
        { from: 'g', to: 'o', label: 'Routes order calls', technology: 'JSON/HTTP' },
        // Wrong direction: the reference has orders -> events.
        { from: 'e', to: 'o', label: 'Receives', technology: 'AMQP' },
        { from: 'o', to: 'x', label: 'Parks failures', technology: 'AMQP' },
        { from: 'other', to: 'otherDb', label: 'uses', technology: 'JDBC' },
      ]),
    ]),
    page('seq', 'sequence', [
      {
        type: 'sequence',
        id: 'seq',
        participants: ['o', 'e'],
        steps: [
          { note: 'Starts when orders receives POST /api/orders/{id}/items (OrderController.add).', over: ['o', 'e'] },
          { from: 'o', to: 'e', message: 'Publishes', code: 'AMQP', type: 'async' },
        ],
      },
    ], { title: 'POST /api/orders/{id}/items on orders' }),
    page('risks', 'context', [
      {
        type: 'table',
        id: 'findings',
        title: 'Risks and findings',
        columns: ['Impact', 'Finding'],
        rows: [['High', '`OrderController` takes the `customerId` from the request without any check.']],
      },
    ]),
  ],
};

const aliases = { elements: { orders: 'orders' } };

test('names and triggers are compared in one spelling', () => {
  assert.equal(normalizeName('CRM-Frontend'), 'crm-frontend');
  assert.equal(normalizeName('auth_db'), 'auth-db');
  assert.equal(normalizeTrigger('POST /api/orgs/{org}/invitations [JSON/HTTP]'), 'POST /api/orgs/{}/invitations');
  assert.equal(normalizeTrigger('post /API/Orgs/{organizationId}/'), 'POST /api/orgs/{}');
  assert.equal(normalizeTrigger('/ws-notifications [STOMP over WebSocket]'), '/ws-notifications');
});

test('connections are grouped by protocol from their ends and technology', () => {
  assert.equal(protocolOf('person', 'container', 'HTTPS'), 'person');
  assert.equal(protocolOf('container', 'database', 'Redis'), 'data store');
  assert.equal(protocolOf('container', 'queue', ''), 'messaging');
  assert.equal(protocolOf('container', 'external', 'SMTP STARTTLS'), 'email');
  assert.equal(protocolOf('container', 'container', 'HTTP/Feign'), 'HTTP');
  assert.equal(protocolOf('container', 'container', 'Eureka'), 'other');
});

test('subjects match on identifiers and longer words', () => {
  const a = subjectWords('The `OrderController` accepts any `customerId` without a check.');
  const b = subjectWords('`OrderController` takes the `customerId` from the request.');
  assert.ok(a.has('ordercontroller') && a.has('customerid') && !a.has('the'));
  assert.ok(subjectOverlap(a, b) >= 0.5);
  assert.equal(subjectOverlap(a, subjectWords('Single PostgreSQL instance.')), 0);
});

test('elements match by kind and name or alias; the unrelated system is out of scope', () => {
  const s = score(report, reference, aliases);
  assert.deepEqual(
    { ...s.elements.containers, missing: undefined, extra: undefined },
    { reference: 2, report: 2, matched: 2, recall: 1, precision: 1, reportWhole: 3, precisionWhole: 0.667, missing: undefined, extra: undefined },
  );
  assert.equal(s.elements.queues.matched, 1);
  assert.deepEqual(s.elements.queues.extra, ['orders.dead-letters']);
  assert.deepEqual(s.elements.dataStores.missing, ['orders_db']);
  assert.equal(s.elements.dataStores.report, 0, 'billing_db is not about this system');
  assert.equal(s.elements.dataStores.reportWhole, 1);
  assert.deepEqual(s.elements.people.missing, ['User']);
  assert.deepEqual(s.outOfScope.sort(), ['billing', 'billing_db']);
  assert.equal(s.components.report, 1);
  assert.deepEqual(s.components.byContainer.find((c) => c.container === 'order-service'), {
    container: 'order-service',
    reference: 1,
    report: 1,
    matchedByName: 0,
  });
});

test('connections match by their ends and direction, once per pair', () => {
  const s = score(report, reference, aliases);
  const c = s.connections;
  assert.equal(c.reference, 4, 'orders -> orders_db is drawn twice and counted once');
  assert.equal(c.report, 3);
  assert.equal(c.reportWhole, 4);
  assert.equal(c.matched, 1);
  assert.deepEqual(c.missing, ['User -> api-gateway', 'order-service -> orders_db', 'order-service -> orders.events']);
  assert.deepEqual(c.extra, ['orders.events -> orders', 'orders -> orders.dead-letters']);
  assert.deepEqual(c.byProtocol.HTTP, { reference: 1, matched: 1, report: 1 });
  assert.deepEqual(c.byProtocol.messaging, { reference: 1, matched: 0, report: 2 });
});

test('flows match by trigger and are compared by participants', () => {
  const s = score(report, reference, aliases);
  assert.equal(s.flows.matched, 1);
  assert.deepEqual(s.flows.matches, [
    {
      reference: 'Place an order',
      report: 'POST /api/orders/{id}/items on orders',
      trigger: 'POST /api/orders/{}/items',
      participants: '1/4',
      missingParticipants: ['User', 'api-gateway', 'orders_db'],
      complete: false,
    },
  ]);
  // A trigger alias makes another spelling of the request the same trigger.
  const renamed = structuredClone(report);
  renamed.pages[1].sections[0].steps[0].note = 'Starts when orders receives POST /orders/{id}/items.';
  assert.equal(score(renamed, reference, aliases).flows.matched, 0);
  const withAlias = { ...aliases, triggers: { 'POST /orders/{x}/items': 'POST /api/orders/{x}/items' } };
  assert.equal(score(renamed, reference, withAlias).flows.matched, 1);
});

test('risks match by subject, decisions and glossary terms are counted', () => {
  const s = score(report, reference, aliases);
  assert.equal(s.risks.matched, 1);
  assert.equal(s.risks.reference, 2);
  assert.deepEqual(s.risks.missing, ['Single PostgreSQL instance with no replica.']);
  assert.deepEqual(s.decisions.missing, ['Database per service']);
  assert.deepEqual(s.glossary.missing, ['JWT']);
});

test('the quality-bar checks that need no reader are read from the data', () => {
  const checks = Object.fromEntries(score(report, reference, aliases).checks.map((c) => [c.id, c]));
  assert.equal(checks['diagram-size'].pass, true);
  assert.equal(checks['line-labels'].pass, false);
  assert.deepEqual(checks['line-labels'].failures, ['cnt / d: billing -> billing_db']);
  assert.equal(checks['pins-snapshot'].pass, true);
  assert.equal(checks.sequences.pass, true);
  assert.equal(checks.risks.pass, true);
  assert.equal(checks.decisions.pass, false);
});

// Scoring each reference against itself finds everything: no two of its items look alike to the
// matching, and its alias table names only what it has.
for (const set of ['project-1', 'project-2', 'project-3', 'project-4']) {
  test(`${set}: the reference scored against itself finds everything`, () => {
    const card = scoreFiles(`${testsets}${set}/reference/reference.c4.json`, `${testsets}${set}`);
    const s = card.scores;
    for (const c of [...Object.values(s.elements), s.connections, s.flows, s.risks, s.decisions, s.glossary]) {
      assert.equal(c.matched, c.reference, JSON.stringify(c.missing));
      assert.equal(c.matched, c.report, JSON.stringify(c.extra));
    }
    assert.deepEqual(s.outOfScope, []);
  });
}

test('an alias to an element the reference does not have is refused', () => {
  assert.throws(() => score(report, reference, { elements: { orders: 'nope' } }), /unknown reference element nope/);
});

// The course correction's assessment of snapshot-4's project-4 report (docs/adr/0070, #252):
// containers 6/7, data stores 0/4, exchanges and queues 4/4, external 0/1, people 0/2, components
// 81/38 (gateway 2/7), flows 3/6 partial; container connections 16/16 precise on the CRM part
// (16/37 over the whole report, which also held project-0's shop), recall 16/28, of them AMQP
// 10/11, store 0/5, SMTP 0/1. Recounts: risks are 16, not 13 (the second pass added three, see
// testsets/project-4/reference/verification.md); HTTP is 6/10 because frontend -> gateway and
// gateway -> eureka-service count as HTTP here, while the assessment counted them apart (8).
test('snapshot-4 project-4 report scores as the assessment counted', () => {
  const s = scoreFiles(fixture, `${testsets}project-4`).scores;
  const counts = (c) => `${c.matched}/${c.reference} recall, ${c.matched}/${c.report} precision`;
  assert.equal(counts(s.elements.containers), '6/7 recall, 6/6 precision');
  assert.equal(counts(s.elements.dataStores), '0/4 recall, 0/0 precision');
  assert.equal(counts(s.elements.queues), '4/4 recall, 4/4 precision');
  assert.equal(counts(s.elements.external), '0/1 recall, 0/0 precision');
  assert.equal(counts(s.elements.people), '0/2 recall, 0/0 precision');
  assert.equal(s.components.report, 81);
  assert.equal(s.components.reference, 38);
  assert.deepEqual(
    s.components.byContainer.find((c) => c.container === 'api-gateway'),
    { container: 'api-gateway', reference: 7, report: 2, matchedByName: 0 },
  );
  assert.equal(counts(s.connections), '16/28 recall, 16/16 precision');
  assert.equal(s.connections.reportWhole, 37);
  assert.deepEqual(s.connections.byProtocol, {
    person: { reference: 1, matched: 0, report: 0 },
    HTTP: { reference: 10, matched: 6, report: 6 },
    'data store': { reference: 5, matched: 0, report: 0 },
    email: { reference: 1, matched: 0, report: 0 },
    messaging: { reference: 11, matched: 10, report: 10 },
  });
  assert.ok(s.connections.missing.includes('main-service -> notification-service.send-message'));
  assert.equal(counts(s.flows), '3/6 recall, 3/6 precision');
  assert.ok(s.flows.matches.every((m) => !m.complete), 'all three are partial');
  assert.deepEqual(
    s.flows.matches.map((m) => m.reference),
    ['Create an organization', 'Invite a member', 'Password reset by email'],
  );
  assert.equal(counts(s.risks), '0/16 recall, 0/0 precision');
  assert.equal(counts(s.decisions), '0/9 recall, 0/0 precision');
  assert.equal(counts(s.glossary), '0/15 recall, 0/0 precision');
  assert.equal(s.outOfScope.length, 12, "project-0's shop is not scored");
  const failing = s.checks.filter((c) => !c.pass).map((c) => c.id);
  assert.deepEqual(failing, ['diagram-size', 'decisions', 'risks', 'glossary']);
  assert.ok(s.checks[0].failures.includes('main-service / Components of main-service (45 elements)'));
});

test('the command line reads HTML reports and writes the scorecard, and compares two', () => {
  const dir = mkdtempSync(join(process.env.TMPDIR ?? tmpdir(), 'score-'));
  try {
    const data = readFileSync(fixture, 'utf8');
    const html = `<html><script type="application/json" id="arc-data">${data.split('<').join('\\u003c')}</script></html>`;
    writeFileSync(join(dir, 'a.c4.html'), html);
    assert.equal(readReport(join(dir, 'a.c4.html')).document.title, 'System');

    const run = (model, cost) => ({
      snapshot: 4,
      calls: 10,
      tokens: 1000,
      costUsd: cost,
      roles: [
        { role: 'SUMMARIZE', provider: 'fake', model, calls: 6, tokens: 600, costUsd: cost },
        { role: 'COMPOSE', provider: 'fake', model: 'fake-model', calls: 4, tokens: 400, costUsd: 0 },
      ],
    });
    writeFileSync(join(dir, 'run-a.json'), JSON.stringify(run('fake-model', 0)));
    writeFileSync(join(dir, 'run-b.json'), JSON.stringify(run('fake-large', 0.12)));
    const a = scoreFiles(join(dir, 'a.c4.html'), `${testsets}project-4`, join(dir, 'run-a.json'));
    const b = scoreFiles(fixture, `${testsets}project-4`, join(dir, 'run-b.json'));
    const markdown = scorecardMarkdown(b);
    assert.match(markdown, /SUMMARIZE: fake\/fake-large \(6 calls, \$0\.12\)/);
    assert.match(markdown, /\| Container connections \| 16\/28 \(57%\) \| 16\/16 \(100%\) \| 16\/37 \(43%\) \|/);

    const side = compareMarkdown(a, b, 'fake-model', 'fake-large');
    assert.match(side, /\| SUMMARIZE \| fake\/fake-model \(6 calls, \$0\) \| fake\/fake-large \(6 calls, \$0\.12\) \|/);
    assert.match(side, /\| COMPOSE \| fake\/fake-model \(4 calls, \$0\) \| fake\/fake-model \(4 calls, \$0\) \|/);
    assert.match(side, /\| Run cost \| \$0 \(10 calls\) \| \$0\.12 \(10 calls\) \|/);
    assert.match(side, /\| Container connections \| 16\/28 recall, 16\/16 precision \| 16\/28 recall, 16\/16 precision \|/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
