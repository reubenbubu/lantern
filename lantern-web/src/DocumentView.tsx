// Renders a document model: its sections and every kind of block, in document order.
import { createContext, memo, type MouseEvent, type ReactNode, useContext, useMemo } from 'react';
import type {
  Block,
  Callout,
  CrossReference,
  Document as LanternDocument,
  DocumentEntity,
  FactEnvelope,
  Inline,
  Metrics,
  QualityReport,
  Section,
  SequenceDiagram,
  SequenceStep,
  Table,
  Tree,
  TreeNode,
} from './generated/document.ts';
import { DiagramView } from './diagram/DiagramView.tsx';

export interface Lookup {
  entities: Map<string, DocumentEntity>;
  /** Each fact with its 1-based position in the document's facts, which is its citation number. */
  facts: Map<string, { fact: FactEnvelope; number: number }>;
  /** The first section about each entity, so entity references can link to it. */
  sectionByEntity: Map<string, string>;
}

export const LookupContext = createContext<Lookup | undefined>(undefined);

/**
 * What an interactive template (the Architecture Explorer) does when the reader picks an entity or
 * a citation. Without it the document renders as a plain page: entity references link to their
 * section and diagrams are static pictures.
 */
export interface Interaction {
  selectEntity(id: string): void;
  selectFacts(ids: string[]): void;
  /** Called each time a diagram has been laid out, which changes the page's height. */
  diagramLaidOut(): void;
}

export const InteractionContext = createContext<Interaction | undefined>(undefined);

export function useInteraction(): Interaction | undefined {
  return useContext(InteractionContext);
}

/**
 * The selected entity, apart from the interaction so that a new selection re-renders only what
 * highlights it (diagram nodes), not the whole document.
 */
export const SelectedEntityContext = createContext<string | undefined>(undefined);

export function useLookup(): Lookup {
  const lookup = useContext(LookupContext);
  if (!lookup) {
    throw new Error('DocumentView must be rendered around this component');
  }
  return lookup;
}

export function buildLookup(document: LanternDocument): Lookup {
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

/** Renders the document; reuses the lookup of a template that already provides one for it. */
export const DocumentView = memo(function DocumentView({ document }: { document: LanternDocument }) {
  const outer = useContext(LookupContext);
  const lookup = useMemo(() => outer ?? buildLookup(document), [outer, document]);
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
});

export function SectionView({ section, depth }: { section: Section; depth: number }) {
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
      return block.diagramKind === 'sequence' ? <SequenceView diagram={block} /> : <DiagramView diagram={block} />;
    case 'tree':
      return <TreeView tree={block} />;
    case 'metrics':
      return <MetricsView metrics={block} />;
    case 'quality-report':
      return <QualityReportView report={block} />;
  }
}

/**
 * A sequence diagram as a plain list of its steps. These templates do not draw sequence diagrams;
 * report-spec templates do (docs/adr/0028).
 */
function SequenceView({ diagram }: { diagram: SequenceDiagram }) {
  const { entities } = useLookup();
  const labels = new Map(diagram.participants.map((p) => [p.id, p.label]));
  const name = (id: string | undefined) =>
    id === undefined ? 'unknown' : (labels.get(id) ?? entities.get(id)?.name ?? id);
  return (
    <figure id={diagram.id} className="sequence">
      {diagram.title && <figcaption>{diagram.title}</figcaption>}
      <SequenceSteps steps={diagram.steps} name={name} />
    </figure>
  );
}

const MESSAGE_KINDS = { sync: '', async: ' (async)', return: ' (reply)' } as const;

function SequenceSteps({ steps, name }: { steps: SequenceStep[]; name: (id: string | undefined) => string }) {
  if (steps.length === 0) {
    return null;
  }
  return (
    <ol className="sequence-steps">
      {steps.map((step, i) => {
        switch (step.kind) {
          case 'message':
            return (
              <li key={i}>
                {name(step.from)} → {name(step.to)}
                {MESSAGE_KINDS[step.messageKind ?? 'sync']}
                {(step.label || step.code) && ': '}
                {step.label}
                {step.label && step.code && ' '}
                {step.code && <code>{step.code}</code>}
                {step.profiles && <span className="sequence-profiles"> (only with profile {step.profiles.join(', ')})</span>}
                {step.info && (
                  <>
                    {' — '}
                    <Content content={step.info} />
                  </>
                )}
              </li>
            );
          case 'note':
            return (
              <li key={i}>
                Note{step.over && ` over ${step.over.map(name).join(', ')}`}: <Content content={step.content} />
              </li>
            );
          case 'frame':
            return (
              <li key={i}>
                {step.operands.map((operand, j) => (
                  <div key={j} className="sequence-operand">
                    {j === 0 ? step.operator : 'else'}
                    {operand.guard && ` [${operand.guard}]`}
                    <SequenceSteps steps={operand.steps} name={name} />
                  </div>
                ))}
              </li>
            );
        }
      })}
    </ol>
  );
}

function QualityReportView({ report }: { report: QualityReport }) {
  const { entities } = useLookup();
  return (
    <aside id={report.id} className="callout quality-report">
      <p className="callout-title">{report.title ?? 'Quality report'}</p>
      {report.failures.length === 0 ? (
        <p>No check failed.</p>
      ) : (
        <ul>
          {report.failures.map((failure, i) => (
            <li key={i}>
              {failure.check}
              {failure.target && (
                <>
                  {' ('}
                  <Content
                    content={[
                      {
                        kind: 'ref',
                        text:
                          failure.target.kind === 'entity'
                            ? (entities.get(failure.target.id)?.name ?? failure.target.id)
                            : failure.target.id,
                        target: failure.target,
                      },
                    ]}
                  />
                  {')'}
                </>
              )}
              {failure.detail && (
                <>
                  {': '}
                  <Content content={failure.detail} />
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}

const CALLOUT_TITLES: Record<Callout['variant'], string> = {
  note: 'Note',
  warning: 'Warning',
  risk: 'Risk',
};

const SEVERITIES: Record<NonNullable<Callout['severity']>, string> = {
  high: 'High',
  medium: 'Medium',
  low: 'Low',
};

function CalloutView({ callout }: { callout: Callout }) {
  const severity = callout.severity;
  const className = `callout callout-${callout.variant}${severity ? ` callout-severity-${severity}` : ''}`;
  return (
    <aside id={callout.id} className={className}>
      <p className="callout-title">
        {severity && <span className={`severity severity-${severity}`}>{SEVERITIES[severity]}</span>}
        {callout.title ?? CALLOUT_TITLES[callout.variant]}
      </p>
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
      <TreeNodes nodes={tree.roots} depth={0} />
    </figure>
  );
}

function TreeNodes({ nodes, depth }: { nodes: TreeNode[]; depth: number }) {
  const interactive = useInteraction() !== undefined;
  if (nodes.length === 0) {
    return null;
  }
  return (
    <ul>
      {nodes.map((node, i) => {
        const label =
          node.entity !== undefined ? <EntityLink id={node.entity}>{node.label}</EntityLink> : node.label;
        // Interactive pages fold subtrees below the roots; each <details> keeps its own state, so
        // tree nodes need no IDs (docs/adr/0014).
        return (
          <li key={i}>
            {interactive && node.children.length > 0 ? (
              <details open={depth === 0}>
                <summary>{label}</summary>
                <TreeNodes nodes={node.children} depth={depth + 1} />
              </details>
            ) : (
              <>
                {label}
                <TreeNodes nodes={node.children} depth={depth + 1} />
              </>
            )}
          </li>
        );
      })}
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
      if (inline.citation) {
        return <SourceCitation target={inline.target} text={inline.text} />;
      }
      return inline.target.kind === 'entity' ? (
        <EntityLink id={inline.target.id}>{inline.text}</EntityLink>
      ) : (
        <a href={`#${inline.target.id}`}>{inline.text}</a>
      );
    case 'citation':
      return <CitationView facts={inline.facts} />;
  }
}

/**
 * Whether a click on a link is a plain one the page should handle itself; with a modifier key or
 * another button the browser opens the link, for example in a new tab.
 */
export function isPlainClick(event: MouseEvent): boolean {
  return event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;
}

/** The deep link that selects an entity in an interactive template. */
export function entityHash(id: string): string {
  return `#entity=${encodeURIComponent(id)}`;
}

/**
 * Links to the section about the entity when there is one; otherwise names it. In an interactive
 * template it selects the entity instead.
 */
export function EntityLink({
  id,
  children,
  title: label,
}: {
  id: string;
  children: ReactNode;
  /** The link's tooltip and accessible name; the entity's name by default. */
  title?: string;
}) {
  const { entities, sectionByEntity } = useLookup();
  const interaction = useInteraction();
  const section = sectionByEntity.get(id);
  const title = label ?? entities.get(id)?.name ?? id;
  if (interaction) {
    const select = (event: MouseEvent) => {
      if (isPlainClick(event)) {
        event.preventDefault();
        interaction.selectEntity(id);
      }
    };
    return (
      <a href={entityHash(id)} className="entity-ref" title={title} aria-label={label} onClick={select}>
        {children}
      </a>
    );
  }
  return section !== undefined ? (
    <a href={`#${section}`} className="entity-ref" title={title} aria-label={label}>
      {children}
    </a>
  ) : (
    <span className="entity-ref" title={title} aria-label={label}>
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

/**
 * A reference that cites what it points to, as a summary cites the summaries of its parts: a
 * marker after the text, like a citation of facts, that names its source only in its tooltip and
 * accessible name. On a page without interaction it links to the section about the target, and
 * without one it is left out, since it would lead nowhere.
 */
function SourceCitation({ target, text }: { target: CrossReference['target']; text: string }) {
  const { sectionByEntity } = useLookup();
  const interaction = useInteraction();
  const label = `Summary of ${text}`;
  if (target.kind !== 'entity') {
    return (
      <sup className="citation">
        <a href={`#${target.id}`} title={label} aria-label={label}>
          {SOURCE_MARKER}
        </a>
      </sup>
    );
  }
  if (!interaction && !sectionByEntity.has(target.id)) {
    return null;
  }
  return (
    <sup className="citation">
      <EntityLink id={target.id} title={label}>
        {SOURCE_MARKER}
      </EntityLink>
    </sup>
  );
}

const SOURCE_MARKER = '[\u2197]';

/** How many fact numbers a citation shows; the rest are counted, and all are in its tooltip. */
const SHOWN_NUMBERS = 3;

export function citationLabel(numbers: number[]): string {
  if (numbers.length <= SHOWN_NUMBERS + 1) {
    return `[${numbers.join(', ')}]`;
  }
  return `[${numbers.slice(0, SHOWN_NUMBERS).join(', ')} +${numbers.length - SHOWN_NUMBERS}]`;
}

function CitationView({ facts }: { facts: string[] }) {
  const lookup = useLookup();
  const interaction = useInteraction();
  const cited = facts.flatMap((id) => {
    const entry = lookup.facts.get(id);
    return entry ? [entry] : [];
  });
  if (cited.length === 0) {
    return null;
  }
  const numbers = citationLabel(cited.map((c) => c.number));
  const title = cited.map((c) => describeFact(c.fact)).join('\n');
  if (interaction) {
    return (
      <sup className="citation">
        <button type="button" className="citation-button" title={title} onClick={() => interaction.selectFacts(facts)}>
          {numbers}
        </button>
      </sup>
    );
  }
  return (
    <sup className="citation" title={title}>
      {numbers}
    </sup>
  );
}
