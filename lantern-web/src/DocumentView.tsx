// Renders a document model: its sections and every kind of block, in document order.
import { createContext, type ReactNode, useContext, useMemo } from 'react';
import type {
  Block,
  Callout,
  Document as LanternDocument,
  DocumentEntity,
  FactEnvelope,
  Inline,
  Metrics,
  Section,
  Table,
  Tree,
  TreeNode,
} from './generated/document.ts';
import { DiagramView } from './diagram/DiagramView.tsx';

interface Lookup {
  entities: Map<string, DocumentEntity>;
  /** Each fact with its 1-based position in the document's facts, which is its citation number. */
  facts: Map<string, { fact: FactEnvelope; number: number }>;
  /** The first section about each entity, so entity references can link to it. */
  sectionByEntity: Map<string, string>;
}

const LookupContext = createContext<Lookup | undefined>(undefined);

function useLookup(): Lookup {
  const lookup = useContext(LookupContext);
  if (!lookup) {
    throw new Error('DocumentView must be rendered around this component');
  }
  return lookup;
}

function buildLookup(document: LanternDocument): Lookup {
  const sectionByEntity = new Map<string, string>();
  const visit = (sections: Section[]) => {
    for (const section of sections) {
      if (section.entity !== undefined && !sectionByEntity.has(section.entity)) {
        sectionByEntity.set(section.entity, section.id);
      }
      visit(section.sections);
    }
  };
  visit(document.sections);
  return {
    entities: new Map(document.entities.map((e) => [e.id, e])),
    facts: new Map(document.facts.map((fact, i) => [fact.id, { fact, number: i + 1 }])),
    sectionByEntity,
  };
}

export function DocumentView({ document }: { document: LanternDocument }) {
  const lookup = useMemo(() => buildLookup(document), [document]);
  return (
    <LookupContext.Provider value={lookup}>
      <article className="document">
        <header className="document-header">
          <h1>{document.title}</h1>
          <p className="document-source">
            {document.template.name} {document.template.version} · snapshot {document.source.snapshot} ·
            Lantern {document.source.toolVersion}
          </p>
        </header>
        {document.sections.map((section) => (
          <SectionView key={section.id} section={section} depth={0} />
        ))}
      </article>
    </LookupContext.Provider>
  );
}

function SectionView({ section, depth }: { section: Section; depth: number }) {
  const Heading = (['h2', 'h3', 'h4', 'h5', 'h6'] as const)[Math.min(depth, 4)];
  return (
    <section id={section.id} className="section">
      <Heading>{section.title}</Heading>
      {section.blocks.map((block) => (
        <BlockView key={block.id} block={block} />
      ))}
      {section.sections.map((child) => (
        <SectionView key={child.id} section={child} depth={depth + 1} />
      ))}
    </section>
  );
}

export function BlockView({ block }: { block: Block }) {
  switch (block.kind) {
    case 'paragraph':
      return (
        <p id={block.id}>
          <Content content={block.content} />
        </p>
      );
    case 'list': {
      const List = block.ordered ? 'ol' : 'ul';
      return (
        <List id={block.id}>
          {block.items.map((item, i) => (
            <li key={i}>
              <Content content={item.content} />
            </li>
          ))}
        </List>
      );
    }
    case 'callout':
      return <CalloutView callout={block} />;
    case 'table':
      return <TableView table={block} />;
    case 'diagram':
      return <DiagramView diagram={block} />;
    case 'tree':
      return <TreeView tree={block} />;
    case 'metrics':
      return <MetricsView metrics={block} />;
  }
}

const CALLOUT_TITLES: Record<Callout['variant'], string> = {
  note: 'Note',
  warning: 'Warning',
  risk: 'Risk',
};

function CalloutView({ callout }: { callout: Callout }) {
  return (
    <aside id={callout.id} className={`callout callout-${callout.variant}`}>
      <p className="callout-title">{callout.title ?? CALLOUT_TITLES[callout.variant]}</p>
      <p>
        <Content content={callout.content} />
      </p>
    </aside>
  );
}

function TableView({ table }: { table: Table }) {
  return (
    <div className="table-wrap" id={table.id}>
      <table>
        {table.title && <caption>{table.title}</caption>}
        <thead>
          <tr>
            {table.columns.map((column, i) => (
              <th key={i} scope="col">
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row, r) => (
            <tr key={r}>
              {row.cells.map((cell, c) => (
                <td key={c}>
                  <Content content={cell} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TreeView({ tree }: { tree: Tree }) {
  return (
    <figure id={tree.id} className="tree">
      {tree.title && <figcaption>{tree.title}</figcaption>}
      <TreeNodes nodes={tree.roots} />
    </figure>
  );
}

function TreeNodes({ nodes }: { nodes: TreeNode[] }) {
  if (nodes.length === 0) {
    return null;
  }
  return (
    <ul>
      {nodes.map((node, i) => (
        <li key={i}>
          {node.entity !== undefined ? <EntityLink id={node.entity}>{node.label}</EntityLink> : node.label}
          <TreeNodes nodes={node.children} />
        </li>
      ))}
    </ul>
  );
}

function MetricsView({ metrics }: { metrics: Metrics }) {
  return (
    <dl id={metrics.id} className="metrics">
      {metrics.items.map((metric, i) => (
        <div key={i} className="metric">
          <dt>{metric.label}</dt>
          <dd>
            {metric.value}
            {metric.unit && <span className="metric-unit"> {metric.unit}</span>}
          </dd>
        </div>
      ))}
    </dl>
  );
}

export function Content({ content }: { content: Inline[] }) {
  return (
    <>
      {content.map((inline, i) => (
        <InlineView key={i} inline={inline} />
      ))}
    </>
  );
}

function InlineView({ inline }: { inline: Inline }) {
  switch (inline.kind) {
    case 'text':
      switch (inline.style) {
        case 'strong':
          return <strong>{inline.text}</strong>;
        case 'emphasis':
          return <em>{inline.text}</em>;
        case 'code':
          return <code>{inline.text}</code>;
        default:
          return <>{inline.text}</>;
      }
    case 'ref':
      return inline.target.kind === 'entity' ? (
        <EntityLink id={inline.target.id}>{inline.text}</EntityLink>
      ) : (
        <a href={`#${inline.target.id}`}>{inline.text}</a>
      );
    case 'citation':
      return <CitationView facts={inline.facts} />;
  }
}

/** Links to the section about the entity when there is one; otherwise names it. */
function EntityLink({ id, children }: { id: string; children: ReactNode }) {
  const { entities, sectionByEntity } = useLookup();
  const section = sectionByEntity.get(id);
  const title = entities.get(id)?.name ?? id;
  return section !== undefined ? (
    <a href={`#${section}`} className="entity-ref" title={title}>
      {children}
    </a>
  ) : (
    <span className="entity-ref" title={title}>
      {children}
    </span>
  );
}

function describeFact(fact: FactEnvelope): string {
  const { evidence } = fact;
  const where = [evidence.repo, evidence.path].filter(Boolean).join('/');
  const lines = evidence.lines ? `:${evidence.lines.start}-${evidence.lines.end}` : '';
  return `${fact.subject} ${fact.predicate} ${String(fact.object.value)} (${where}${lines}, ${fact.origin.kind})`;
}

function CitationView({ facts }: { facts: string[] }) {
  const lookup = useLookup();
  const cited = facts.flatMap((id) => {
    const entry = lookup.facts.get(id);
    return entry ? [entry] : [];
  });
  if (cited.length === 0) {
    return null;
  }
  return (
    <sup className="citation" title={cited.map((c) => describeFact(c.fact)).join('\n')}>
      [{cited.map((c) => c.number).join(', ')}]
    </sup>
  );
}
