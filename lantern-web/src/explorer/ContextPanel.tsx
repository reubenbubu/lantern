// The context panel: the selected entity's details, where it appears and the facts behind it with
// their evidence, origin and confidence; or the facts a citation names; or, with nothing selected,
// what the document was made from.
import type { MouseEvent } from 'react';
import { Content, EntityLink, isPlainClick, shortCommit, useLookup } from '../DocumentView.tsx';
import type { Document as LanternDocument, FactEnvelope } from '../generated/document.ts';
import { Breakable } from './Breakable.tsx';
import { type EntityTree, entityType, type Placement, typeLabel } from './model.ts';

export type Selection = { kind: 'none' } | { kind: 'entity'; id: string } | { kind: 'facts'; ids: string[] };

/** Children listed in the panel; the navigation tree has the rest. */
const MAX_CHILDREN = 50;

interface Props {
  document: LanternDocument;
  selection: Selection;
  tree: EntityTree;
  placements: Map<string, Placement[]>;
  onGoTo(id: string): void;
  onClose(): void;
}

export function ContextPanel({ document, selection, tree, placements, onGoTo, onClose }: Props) {
  const heading =
    selection.kind === 'entity' ? 'Details' : selection.kind === 'facts' ? 'Cited facts' : 'About this document';
  return (
    <div className="context-panel">
      <div className="pane-header">
        <h2>{heading}</h2>
        <button type="button" className="icon-button pane-close" aria-label="Close details" onClick={onClose}>
          ✕
        </button>
      </div>
      {selection.kind === 'entity' && (
        <EntityDetails id={selection.id} tree={tree} placements={placements.get(selection.id) ?? []} onGoTo={onGoTo} />
      )}
      {selection.kind === 'facts' && <FactList ids={selection.ids} />}
      {selection.kind === 'none' && <DocumentDetails document={document} />}
    </div>
  );
}

function DocumentDetails({ document }: { document: LanternDocument }) {
  const { source, template } = document;
  return (
    <>
      <dl className="detail-list">
        <dt>Document</dt>
        <dd>{document.title}</dd>
        <dt>Template</dt>
        <dd>
          {template.name} {template.version}
        </dd>
        {document.audience && (
          <>
            <dt>Reader</dt>
            <dd>{document.audience}</dd>
          </>
        )}
        <dt>Snapshot</dt>
        <dd>
          {source.snapshot}, taken {source.date}
        </dd>
        <dt>Commits</dt>
        <dd>
          {Object.entries(source.commits).map(([repo, commit]) => (
            <div key={repo}>
              {repo} <code title={commit}>{shortCommit(commit)}</code>
            </div>
          ))}
        </dd>
        <dt>Vocabulary revision</dt>
        <dd>{source.vocabularyRevision}</dd>
        <dt>Made by</dt>
        <dd>Lantern {source.toolVersion}</dd>
        <dt>Contents</dt>
        <dd>
          {document.entities.length} entities, {document.facts.length} facts
        </dd>
      </dl>
      <p className="hint">
        Select anything underlined, a diagram box or an entry in the navigation to see its details and the
        evidence behind it here.
      </p>
    </>
  );
}

function EntityDetails({
  id,
  tree,
  placements,
  onGoTo,
}: {
  id: string;
  tree: EntityTree;
  placements: Placement[];
  onGoTo(id: string): void;
}) {
  const { entities } = useLookup();
  const entity = entities.get(id);
  if (!entity) {
    return <p className="hint">This document has nothing about {id}.</p>;
  }
  const type = typeLabel(entityType(id));
  const ancestors = tree.ancestors(id).reverse();
  const children = tree.children.get(id) ?? [];
  const go = (target: string) => (event: MouseEvent) => {
    if (isPlainClick(event)) {
      event.preventDefault();
      onGoTo(target);
    }
  };
  return (
    <div className="entity-details">
      <p className="entity-type">
        <span className={`type-badge type-${entityType(id)}`}>{type.short}</span> {type.long}
      </p>
      <h3 className="entity-name">
        <Breakable text={entity.name} />
      </h3>
      <p className="entity-id">
        <code>
          <Breakable text={entity.id} />
        </code>
      </p>
      {ancestors.length > 0 && (
        <p className="breadcrumb">
          In{' '}
          {ancestors.map((a, i) => (
            <span key={a}>
              {i > 0 && ' › '}
              <EntityLink id={a}>
                <Breakable text={entities.get(a)?.name ?? a} />
              </EntityLink>
            </span>
          ))}
        </p>
      )}
      {entity.description && (
        <p className="entity-description">
          <Content content={entity.description} />
        </p>
      )}
      {placements.length > 0 && (
        <>
          <h4>Shown in</h4>
          <ul className="plain-list">
            {placements.map((p) => (
              <li key={p.id}>
                <a href={`#${p.id}`} onClick={go(p.id)}>
                  <Breakable text={p.title} />
                </a>
              </li>
            ))}
          </ul>
        </>
      )}
      {children.length > 0 && (
        <>
          <h4>
            Contains <span className="nav-count">{children.length}</span>
          </h4>
          <ul className="plain-list">
            {children.slice(0, MAX_CHILDREN).map((child) => (
              <li key={child.id}>
                <span className={`type-badge type-${entityType(child.id)}`}>{typeLabel(entityType(child.id)).short}</span>{' '}
                <EntityLink id={child.id}>
                  <Breakable text={child.name} />
                </EntityLink>
              </li>
            ))}
            {children.length > MAX_CHILDREN && (
              <li className="hint">and {children.length - MAX_CHILDREN} more in the navigation tree</li>
            )}
          </ul>
        </>
      )}
      <h4>
        Facts <span className="nav-count">{entity.facts.length}</span>
      </h4>
      {entity.facts.length > 0 ? <FactList ids={entity.facts} /> : <p className="hint">No facts recorded.</p>}
    </div>
  );
}

function FactList({ ids }: { ids: string[] }) {
  const { facts } = useLookup();
  return (
    <ol className="fact-list">
      {ids.flatMap((id) => {
        const entry = facts.get(id);
        return entry ? [<FactCard key={id} fact={entry.fact} number={entry.number} />] : [];
      })}
    </ol>
  );
}

function FactValue({ fact }: { fact: FactEnvelope }) {
  const { entities } = useLookup();
  const value = fact.object.value;
  if (fact.object.kind === 'entity' && typeof value === 'string') {
    return entities.has(value) ? <EntityLink id={value}>{entities.get(value)!.name}</EntityLink> : <code>{value}</code>;
  }
  return <code>{String(value)}</code>;
}

function FactCard({ fact, number }: { fact: FactEnvelope; number: number }) {
  const { entities } = useLookup();
  const { evidence, origin } = fact;
  const lines = evidence.lines ? `:${evidence.lines.start}-${evidence.lines.end}` : '';
  const attributes = Object.entries(fact.attributes);
  return (
    <li className="fact" value={number}>
      <p className="fact-statement">
        <span className="fact-number">[{number}]</span>{' '}
        {entities.has(fact.subject) ? (
          <EntityLink id={fact.subject}>{entities.get(fact.subject)!.name}</EntityLink>
        ) : (
          <code>{fact.subject}</code>
        )}{' '}
        <span className="fact-predicate">{fact.predicate}</span> <FactValue fact={fact} />
      </p>
      {attributes.length > 0 && (
        <p className="fact-attributes">
          {attributes.map(([key, value]) => (
            <span key={key}>
              {key}: <code>{typeof value === 'string' ? value : JSON.stringify(value)}</code>{' '}
            </span>
          ))}
        </p>
      )}
      <p className="fact-evidence">
        <code>
          {evidence.repo}
          {evidence.path ? `/${evidence.path}` : ''}
          {lines}
        </code>{' '}
        at <code title={evidence.commit}>{evidence.commit.slice(0, 12)}</code>
      </p>
      <p className="fact-meta">
        <span className={`badge origin-${origin.kind}`}>
          {origin.kind} · {origin.name}
          {origin.version ? ` ${origin.version}` : ''}
        </span>{' '}
        <span className="badge" title="Confidence">
          {Math.round(fact.confidence * 100)}% confidence
        </span>{' '}
        <span className="badge" title="Snapshots this fact holds in">
          {fact.validity.to !== undefined
            ? `snapshots ${fact.validity.from} to ${fact.validity.to - 1}`
            : `since snapshot ${fact.validity.from}`}
        </span>
      </p>
    </li>
  );
}
