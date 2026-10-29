import { describe, expect, test } from 'vitest';
import type { Diagram } from '../src/generated/document.ts';
import { edgeLabel, layoutDiagram, toElkGraph } from '../src/diagram/layout.ts';
import { composerOutput, diagrams, schemaFixture, systemOutput } from './fixtures.ts';

const containers = (): Diagram => diagrams(systemOutput()).find((d) => d.diagramKind === 'c4-container')!;

const node = (id: string, parent?: string): Diagram['nodes'][number] => ({
  id,
  label: id,
  ...(parent ? { parent } : {}),
  facts: [],
});

const diagram = (nodes: Diagram['nodes'], edges: Diagram['edges'] = []): Diagram => ({
  kind: 'diagram',
  id: 'd',
  diagramKind: 'dependency',
  nodes,
  edges,
  facts: [],
});

describe('toElkGraph', () => {
  test('sorts nodes and edges by ID whatever the input order', () => {
    const edges = [
      { id: 'b-c', from: 'service:b', to: 'service:c', facts: [] },
      { id: 'a-b', from: 'service:a', to: 'service:b', facts: [] },
    ];
    const graph = toElkGraph(diagram([node('service:c'), node('service:a'), node('service:b')], edges));
    expect(graph.children!.map((n) => n.id)).toEqual(['service:a', 'service:b', 'service:c']);
    expect(graph.edges!.map((e) => e.id)).toEqual(['a-b', 'b-c']);
  });

  test('nests nodes inside their parent and keeps orphans at the top level', () => {
    const graph = toElkGraph(
      diagram([node('repo:r'), node('library:a', 'repo:r'), node('library:b', 'repo:missing')]),
    );
    expect(graph.children!.map((n) => n.id)).toEqual(['library:b', 'repo:r']);
    expect(graph.children![1].children!.map((n) => n.id)).toEqual(['library:a']);
  });

  test('draws nodes in a parent cycle at the top level', () => {
    const graph = toElkGraph(diagram([node('x:a', 'x:b'), node('x:b', 'x:a')]));
    expect(graph.children!.map((n) => n.id)).toEqual(['x:a']);
    expect(graph.children![0].children!.map((n) => n.id)).toEqual(['x:b']);
  });

  test('leaves out edges to nodes that are not in the diagram', () => {
    const graph = toElkGraph(diagram([node('x:a')], [{ id: 'e', from: 'x:a', to: 'x:gone', facts: [] }]));
    expect(graph.edges).toEqual([]);
  });
});

describe('layoutDiagram', () => {
  test('places every node, with children inside their boundary', async () => {
    const source = containers();
    const layout = await layoutDiagram(source);
    expect(layout.nodes.map((n) => n.id).sort()).toEqual(source.nodes.map((n) => n.id).sort());
    const byId = new Map(layout.nodes.map((n) => [n.id, n]));
    for (const child of source.nodes.filter((n) => n.parent)) {
      const inner = byId.get(child.id)!;
      const outer = byId.get(child.parent!)!;
      expect(outer.container).toBe(true);
      expect(inner.x).toBeGreaterThanOrEqual(outer.x);
      expect(inner.y).toBeGreaterThanOrEqual(outer.y);
      expect(inner.x + inner.width).toBeLessThanOrEqual(outer.x + outer.width);
      expect(inner.y + inner.height).toBeLessThanOrEqual(outer.y + outer.height);
    }
    expect(layout.edges).toHaveLength(source.edges.length);
    for (const edge of layout.edges) {
      expect(edge.points.length).toBeGreaterThanOrEqual(2);
    }
  });

  test('gives the same layout for the same diagram, whatever the input order', async () => {
    for (const source of [...diagrams(composerOutput()), ...diagrams(systemOutput()), ...diagrams(schemaFixture())]) {
      const first = await layoutDiagram(source);
      const shuffled = { ...source, nodes: [...source.nodes].reverse(), edges: [...source.edges].reverse() };
      expect(await layoutDiagram(source)).toEqual(first);
      expect(await layoutDiagram(shuffled)).toEqual(first);
    }
  });
});

describe('edgeLabel', () => {
  test('shows what happens, the technology and the profiles the edge alone holds under', () => {
    const edge = { id: 'e', from: 'a:x', to: 'b:y', facts: [] };
    expect(edgeLabel(edge)).toBeUndefined();
    expect(edgeLabel({ ...edge, label: 'Calls GET /orders' })).toBe('Calls GET /orders');
    expect(edgeLabel({ ...edge, label: 'Sends', technology: 'Kafka', profiles: ['prod', 'eu'] })).toBe(
      'Sends [Kafka] (only with prod, eu)',
    );
  });
});
