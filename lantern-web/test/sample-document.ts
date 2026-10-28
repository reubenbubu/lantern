// A document written against the generated types, so the types are checked to compile and to
// describe a document the schema accepts (see document-schema.test.mjs).
import type {
  Block,
  Diagram,
  Document,
  FactEnvelope,
  FactObject,
  Inline,
  Section,
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

const blocks: Block[] = [
  { kind: 'paragraph', id: 'intro', content: [intro[0], ...intro.slice(1)], facts: [factId] },
  { kind: 'metrics', id: 'headline', items: [{ label: 'Services', value: 1, facts: [] }], facts: [] },
  {
    kind: 'callout',
    id: 'risk',
    variant: 'risk',
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
