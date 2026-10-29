// Lays a diagram out with elkjs. Inputs are sorted by stable ID and the seed is fixed, so the same
// diagram always gets the same layout.
import ELK from 'elkjs/lib/elk.bundled.js';
import type { ElkExtendedEdge, ElkNode } from 'elkjs/lib/elk-api.js';
import type { Diagram, DiagramNode } from '../generated/document.ts';

export interface Point {
  x: number;
  y: number;
}

export interface PlacedNode {
  id: string;
  label: string;
  technology?: string;
  color?: string;
  /** True when other nodes are drawn inside this one (a boundary). */
  container: boolean;
  /** Nesting depth; boundaries are drawn before what they contain. */
  depth: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PlacedEdge {
  id: string;
  label?: string;
  /** Start point, bend points and end point. */
  points: Point[];
  labelBox?: { x: number; y: number; width: number; height: number };
}

export interface DiagramLayout {
  width: number;
  height: number;
  /** Sorted by depth, then ID. */
  nodes: PlacedNode[];
  /** Sorted by ID. */
  edges: PlacedEdge[];
}

const CHAR_WIDTH = 7;
const LINE_HEIGHT = 18;
const NODE_PADDING = 12;
const MIN_NODE_WIDTH = 120;

const ROOT_OPTIONS: Record<string, string> = {
  'elk.algorithm': 'layered',
  'elk.direction': 'DOWN',
  'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
  'elk.edgeRouting': 'ORTHOGONAL',
  'elk.spacing.nodeNode': '40',
  'elk.layered.spacing.nodeNodeBetweenLayers': '50',
  'elk.randomSeed': '1',
  // Every coordinate in the result is relative to the diagram, not to a node's parent.
  'elk.json.shapeCoords': 'ROOT',
  'elk.json.edgeCoords': 'ROOT',
};

const CONTAINER_OPTIONS: Record<string, string> = {
  'elk.padding': `[top=${LINE_HEIGHT + 2 * NODE_PADDING},left=${NODE_PADDING},bottom=${NODE_PADDING},right=${NODE_PADDING}]`,
};

const byId = <T extends { id: string }>(a: T, b: T) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

function textWidth(text: string): number {
  return text.length * CHAR_WIDTH;
}

function leafSize(node: DiagramNode): { width: number; height: number } {
  const lines = node.technology ? 2 : 1;
  const widest = Math.max(textWidth(node.label), textWidth(node.technology ? `[${node.technology}]` : ''));
  return {
    width: Math.max(MIN_NODE_WIDTH, widest + 2 * NODE_PADDING),
    height: lines * LINE_HEIGHT + 2 * NODE_PADDING,
  };
}

/**
 * Builds the ELK input graph. Nodes and edges are sorted by ID; a node is placed inside its parent
 * when the parent is in the diagram, otherwise at the top level.
 */
export function toElkGraph(diagram: Diagram): ElkNode {
  const nodes = [...diagram.nodes].sort(byId);
  const known = new Map(nodes.map((n) => [n.id, n]));
  const children = new Map<string, DiagramNode[]>();
  const roots: DiagramNode[] = [];
  for (const node of nodes) {
    if (node.parent !== undefined && node.parent !== node.id && known.has(node.parent)) {
      const siblings = children.get(node.parent) ?? [];
      siblings.push(node);
      children.set(node.parent, siblings);
    } else {
      roots.push(node);
    }
  }
  const placed = new Set<string>();
  const toElk = (node: DiagramNode): ElkNode => {
    placed.add(node.id);
    const inside = (children.get(node.id) ?? []).filter((c) => !placed.has(c.id)).map(toElk);
    if (inside.length > 0) {
      return {
        id: node.id,
        labels: [{ text: node.label, width: textWidth(node.label), height: LINE_HEIGHT }],
        layoutOptions: CONTAINER_OPTIONS,
        children: inside,
      };
    }
    return { id: node.id, ...leafSize(node) };
  };
  const top = roots.map(toElk);
  // A parent cycle leaves nodes unreachable from the top level; draw them there.
  for (const node of nodes) {
    if (!placed.has(node.id)) {
      top.push(toElk(node));
    }
  }
  const edges: ElkExtendedEdge[] = [...diagram.edges]
    .sort(byId)
    .filter((e) => known.has(e.from) && known.has(e.to))
    .map((e) => {
      const label = edgeLabel(e);
      return {
        id: e.id,
        sources: [e.from],
        targets: [e.to],
        labels: label ? [{ text: label, width: textWidth(label), height: LINE_HEIGHT }] : undefined,
      };
    });
  return { id: 'root', layoutOptions: ROOT_OPTIONS, children: top, edges };
}

/**
 * What an edge's label shows: what happens, its technology in brackets as C4 writes it, and the
 * Spring profiles it alone holds under.
 */
export function edgeLabel(edge: Diagram['edges'][number]): string | undefined {
  const parts = [
    edge.label,
    edge.technology ? `[${edge.technology}]` : undefined,
    edge.profiles ? `(only with ${edge.profiles.join(', ')})` : undefined,
  ].filter((part) => part !== undefined);
  return parts.length > 0 ? parts.join(' ') : undefined;
}

let elk: InstanceType<typeof ELK> | undefined;

export async function layoutDiagram(diagram: Diagram): Promise<DiagramLayout> {
  elk ??= new ELK();
  const graph = await elk.layout(toElkGraph(diagram));
  const source = new Map(diagram.nodes.map((n) => [n.id, n]));
  const nodes: PlacedNode[] = [];
  const visit = (elkNode: ElkNode, depth: number) => {
    const node = source.get(elkNode.id)!;
    nodes.push({
      id: node.id,
      label: node.label,
      technology: node.technology,
      color: node.style?.color,
      container: (elkNode.children?.length ?? 0) > 0,
      depth,
      x: elkNode.x ?? 0,
      y: elkNode.y ?? 0,
      width: elkNode.width ?? 0,
      height: elkNode.height ?? 0,
    });
    for (const child of elkNode.children ?? []) {
      visit(child, depth + 1);
    }
  };
  for (const child of graph.children ?? []) {
    visit(child, 0);
  }
  nodes.sort((a, b) => a.depth - b.depth || byId(a, b));

  const edgeLabels = new Map(diagram.edges.map((e) => [e.id, edgeLabel(e)]));
  const edges: PlacedEdge[] = (graph.edges ?? []).map((e) => {
    const section = e.sections?.[0];
    const points = section ? [section.startPoint, ...(section.bendPoints ?? []), section.endPoint] : [];
    const label = e.labels?.[0];
    return {
      id: e.id,
      label: edgeLabels.get(e.id),
      points: points.map(({ x, y }) => ({ x, y })),
      labelBox:
        label && label.x !== undefined && label.y !== undefined
          ? { x: label.x, y: label.y, width: label.width ?? 0, height: label.height ?? 0 }
          : undefined,
    };
  });
  edges.sort(byId);
  return { width: graph.width ?? 0, height: graph.height ?? 0, nodes, edges };
}
