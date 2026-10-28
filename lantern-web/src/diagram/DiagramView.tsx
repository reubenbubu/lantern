// Draws a diagram block as SVG once elkjs has laid it out.
import { useEffect, useState } from 'react';
import type { Diagram } from '../generated/document.ts';
import { type DiagramLayout, layoutDiagram } from './layout.ts';

const MARGIN = 8;

type State =
  | { kind: 'pending' }
  | { kind: 'done'; layout: DiagramLayout }
  | { kind: 'failed'; message: string };

export function DiagramView({ diagram }: { diagram: Diagram }) {
  const [state, setState] = useState<State>({ kind: 'pending' });
  useEffect(() => {
    let current = true;
    setState({ kind: 'pending' });
    layoutDiagram(diagram).then(
      (layout) => current && setState({ kind: 'done', layout }),
      (e: unknown) => current && setState({ kind: 'failed', message: String(e) }),
    );
    return () => {
      current = false;
    };
  }, [diagram]);
  return (
    <figure className="diagram" id={diagram.id}>
      {diagram.title && <figcaption>{diagram.title}</figcaption>}
      {state.kind === 'done' && <DiagramSvg diagram={diagram} layout={state.layout} />}
      {state.kind === 'pending' && <p className="diagram-status">Laying out the diagram…</p>}
      {state.kind === 'failed' && (
        <p className="diagram-status">The diagram could not be laid out: {state.message}</p>
      )}
    </figure>
  );
}

/** Black or white, whichever reads better on the given #rrggbb color. */
function textOn(color: string): string {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(color.slice(i, i + 2), 16));
  return 0.299 * r + 0.587 * g + 0.114 * b > 150 ? '#000000' : '#ffffff';
}

export function DiagramSvg({ diagram, layout }: { diagram: Diagram; layout: DiagramLayout }) {
  const marker = `arrow-${diagram.id.replace(/[^A-Za-z0-9_-]/g, '_')}`;
  const width = layout.width + 2 * MARGIN;
  const height = layout.height + 2 * MARGIN;
  return (
    <svg
      className="diagram-canvas"
      viewBox={`${-MARGIN} ${-MARGIN} ${width} ${height}`}
      width={width}
      height={height}
      role="img"
      aria-label={diagram.title ?? diagram.diagramKind}
    >
      <defs>
        <marker id={marker} viewBox="0 0 10 10" refX="10" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" className="diagram-arrow" />
        </marker>
      </defs>
      {layout.nodes
        .filter((n) => n.container)
        .map((n) => (
          <g key={n.id} className="diagram-boundary" data-entity={n.id}>
            <rect x={n.x} y={n.y} width={n.width} height={n.height} rx="6" />
            <text x={n.x + 12} y={n.y + 24}>
              {n.label}
            </text>
          </g>
        ))}
      {layout.edges.map((e) => (
        <g key={e.id} className="diagram-edge" data-edge={e.id}>
          <polyline points={e.points.map((p) => `${p.x},${p.y}`).join(' ')} markerEnd={`url(#${marker})`} />
          {e.label && e.labelBox && (
            <text x={e.labelBox.x} y={e.labelBox.y + e.labelBox.height - 4}>
              {e.label}
            </text>
          )}
        </g>
      ))}
      {layout.nodes
        .filter((n) => !n.container)
        .map((n) => (
          <g key={n.id} className="diagram-node" data-entity={n.id}>
            <rect
              x={n.x}
              y={n.y}
              width={n.width}
              height={n.height}
              rx="6"
              style={n.color ? { fill: n.color } : undefined}
            />
            <text
              x={n.x + n.width / 2}
              y={n.y + 12 + 14}
              textAnchor="middle"
              style={n.color ? { fill: textOn(n.color) } : undefined}
            >
              <tspan className="diagram-node-label">{n.label}</tspan>
              {n.technology && (
                <tspan x={n.x + n.width / 2} dy="18" className="diagram-node-technology">
                  [{n.technology}]
                </tspan>
              )}
            </text>
          </g>
        ))}
    </svg>
  );
}
