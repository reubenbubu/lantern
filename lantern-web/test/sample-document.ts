// A document written against the generated types, so the types are checked to compile and to
// describe a document the schema accepts (see document-schema.test.mjs).
import type {
  Block,
  Diagram,
  Document,
  FactEnvelope,
  FactObject,
  Inline,
  QualityReport,
  Section,
  SequenceDiagram,
  SequenceStep,
} from '../src/generated/document.ts';

const factId = 'de687af6623289be0a116c5f48767399aac0a5f1e80ce7344764d0bf860bfac6';

const object: FactObject = { kind: 'entity', value: 'library:orders-lib' };

const fact: FactEnvelope = {
  id: factId,
  subject: 'service:orders',
  predicate: 'CONTAINS',
  object,
  attributes: {},
  origin: { kind: 'static', name: 'maven', version: '1' },
  evidence: {
    repo: 'orders',
    commit: '0123456789abcdef0123456789abcdef01234567',
    path: 'pom.xml',
    lines: { start: 10, end: 14 },
  },
  confidence: 1,
  validity: { from: 1 },
};

const intro: Inline[] = [
  { kind: 'text', text: 'The ' },
  { kind: 'ref', text: 'orders', target: { kind: 'entity', id: 'service:orders' } },
  { kind: 'text', text: ' service.', style: 'strong' },
  { kind: 'citation', facts: [factId] },
  { kind: 'ref', text: 'orders-lib', target: { kind: 'entity', id: 'library:orders-lib' }, citation: true },
];

const diagram: Diagram = {
  kind: 'diagram',
  id: 'containers',
  diagramKind: 'c4-container',
  nodes: [
    {
      id: 'library:orders-lib',
      label: 'orders-lib',
      technology: 'Java',
      parent: 'service:orders',
      position: { x: 20, y: 10, pinned: false },
      facts: [factId],
    },
    { id: 'service:orders', label: 'orders', style: { color: '#1f77b4' }, facts: [] },
  ],
  edges: [
    {
      id: 'orders-lib',
      from: 'service:orders',
      to: 'library:orders-lib',
      relationship: 'CONTAINS',
      facts: [factId],
    },
  ],
  facts: [factId],
};

const flow: SequenceDiagram = {
  kind: 'diagram',
  id: 'order-flow',
  diagramKind: 'sequence',
  title: 'Place an order',
  participants: [
    { id: 'service:orders', label: 'Orders', technology: 'Spring Boot', facts: [factId] },
    { id: 'library:orders-lib', facts: [] },
  ],
  steps: [
    {
      kind: 'message',
      from: 'service:orders',
      to: 'library:orders-lib',
      label: 'Create order',
      code: 'POST /orders',
      messageKind: 'sync',
      activate: true,
      deactivate: false,
      profiles: ['prod'],
      info: [{ kind: 'text', text: 'Validates first.' }],
      facts: [factId],
    },
    {
      kind: 'frame',
      operator: 'alt',
      operands: [
        { guard: 'in stock', steps: [{ kind: 'message', from: 'library:orders-lib', to: 'library:orders-lib', facts: [] }] },
        { steps: [{ kind: 'note', over: ['library:orders-lib'], content: [{ kind: 'text', text: 'Rejected.' }], facts: [] }] },
      ],
      facts: [],
    },
    { kind: 'message', facts: [] },
    { kind: 'note', content: [{ kind: 'text', text: 'The rest happens later.' }], facts: [] },
  ],
  facts: [factId],
};

const quality: QualityReport = {
  kind: 'quality-report',
  id: 'quality',
  failures: [
    {
      check: 'Every line is labelled',
      target: { kind: 'block', id: 'containers' },
      detail: [{ kind: 'text', text: 'One line has no label.' }],
      facts: [factId],
    },
    { check: 'It states its reader', facts: [] },
  ],
  facts: [],
};

const blocks: Block[] = [
  { kind: 'paragraph', id: 'intro', content: [intro[0], ...intro.slice(1)], facts: [factId] },
  { kind: 'metrics', id: 'headline', items: [{ label: 'Services', value: 1, facts: [] }], facts: [] },
  {
    kind: 'callout',
    id: 'risk',
    variant: 'risk',
    severity: 'high',
    content: [{ kind: 'text', text: 'Shared.' }],
    facts: [],
  },
  {
    kind: 'list',
    id: 'points',
    ordered: true,
    items: [{ content: [{ kind: 'text', text: 'One.' }], facts: [] }],
    facts: [],
  },
  diagram,
  flow,
  quality,
  {
    kind: 'table',
    id: 'deps',
    columns: [{ label: 'Name' }],
    rows: [{ entity: 'library:orders-lib', cells: [[{ kind: 'text', text: 'orders-lib' }]], facts: [] }],
    facts: [],
  },
  {
    kind: 'tree',
    id: 'modules',
    roots: [{ label: 'orders', entity: 'service:orders', facts: [], children: [] }],
    facts: [],
  },
];

const section: Section = {
  id: 'overview',
  title: 'Overview',
  levels: ['L0', 'L1'],
  entity: 'service:orders',
  facts: [factId],
  blocks,
  sections: [],
};

export const sample: Document = {
  schemaVersion: 1,
  id: 'orders-explorer',
  title: 'orders',
  template: { name: 'architecture-explorer', version: '1' },
  source: { snapshot: 1, vocabularyRevision: 0, toolVersion: '0.1.0-SNAPSHOT' },
  entities: [
    { id: 'library:orders-lib', name: 'orders-lib', parent: 'service:orders', facts: [factId] },
    { id: 'service:orders', name: 'orders', facts: [factId] },
  ],
  facts: [fact],
  sections: [section],
};

// The generated types must also refuse what the schema refuses.
export const refused: Block[] = [
  // @ts-expect-error unknown block kind
  { kind: 'chart', id: 'c', facts: [] },
  // @ts-expect-error a paragraph without facts
  { kind: 'paragraph', id: 'p', content: [{ kind: 'text', text: 'x' }] },
  // @ts-expect-error unknown callout variant
  { kind: 'callout', id: 'c', variant: 'tip', content: [{ kind: 'text', text: 'x' }], facts: [] },
  {
    kind: 'callout',
    id: 'c',
    variant: 'risk',
    // @ts-expect-error unknown risk severity
    severity: 'critical',
    content: [{ kind: 'text', text: 'x' }],
    facts: [],
  },
  // @ts-expect-error empty rich text
  { kind: 'paragraph', id: 'p', content: [], facts: [] },
  {
    kind: 'diagram',
    id: 'd',
    diagramKind: 'dependency',
    // @ts-expect-error misspelled node property
    nodes: [{ id: 'service:orders', label: 'orders', facts: [], colour: '#000000' }],
    edges: [],
    facts: [],
  },
  // @ts-expect-error a sequence diagram with nodes and edges
  { kind: 'diagram', id: 'f', diagramKind: 'sequence', nodes: [], edges: [], facts: [] },
  // @ts-expect-error a quality report without failures
  { kind: 'quality-report', id: 'q', facts: [] },
  {
    kind: 'quality-report',
    id: 'q',
    // @ts-expect-error a failure without its check
    failures: [{ facts: [] }],
    facts: [],
  },
];
export const refusedSteps: SequenceStep[] = [
  // @ts-expect-error unknown message kind
  { kind: 'message', messageKind: 'oneway', facts: [] },
  // @ts-expect-error unknown frame operator
  { kind: 'frame', operator: 'switch', operands: [], facts: [] },
  // @ts-expect-error a note without content
  { kind: 'note', facts: [] },
  // @ts-expect-error a step of unknown kind
  { kind: 'divider', facts: [] },
];
// @ts-expect-error an inline of unknown kind
export const refusedInline: Inline = { kind: 'image', text: 'x' };
