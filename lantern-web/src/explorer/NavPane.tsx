// The navigation pane: the document's sections, then its entities as a tree (the document's own
// repository first, then the other entities without a parent, grouped by type). Subtrees are
// rendered only when expanded, so large documents stay quick.
import { type MouseEvent, useEffect, useRef, useState } from 'react';
import { entityHash, isPlainClick } from '../DocumentView.tsx';
import type { Document as LanternDocument, DocumentEntity, Section } from '../generated/document.ts';
import { Breakable } from './Breakable.tsx';
import { type EntityTree, entityType, typeLabel } from './model.ts';

interface Props {
  document: LanternDocument;
  tree: EntityTree;
  selected?: string;
  onSection(id: string): void;
  onEntity(id: string): void;
  onClose(): void;
}

export function NavPane({ document, tree, selected, onSection, onEntity, onClose }: Props) {
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    const home = tree.groups.find((g) => g.key === 'group:home');
    return new Set(home ? [home.key, ...home.roots.map((r) => r.id)] : tree.groups.slice(0, 1).map((g) => g.key));
  });
  const pane = useRef<HTMLDivElement>(null);
  /** Set when the selection changes, until its entry is rendered and scrolled into view. */
  const reveal = useRef(false);

  // Reveal the selected entity: expand its group and parents, then bring it into view.
  useEffect(() => {
    if (selected === undefined) {
      return;
    }
    reveal.current = true;
    const chain = tree.ancestors(selected);
    const root = chain.at(-1) ?? selected;
    const group = tree.groups.find((g) => g.roots.some((r) => r.id === root));
    setExpanded((current) => {
      const missing = [group?.key, ...chain].filter((k): k is string => k !== undefined && !current.has(k));
      return missing.length === 0 ? current : new Set([...current, ...missing]);
    });
  }, [selected, tree]);
  useEffect(() => {
    const entry = pane.current?.querySelector('[aria-current="true"]');
    if (reveal.current && entry) {
      reveal.current = false;
      entry.scrollIntoView({ block: 'nearest' });
    }
  }, [selected, expanded]);

  const toggle = (key: string) =>
    setExpanded((current) => {
      const next = new Set(current);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });

  const sectionLink = (section: Section) => (
    <a
      href={`#${section.id}`}
      onClick={(event: MouseEvent) => {
        if (isPlainClick(event)) {
          event.preventDefault();
          onSection(section.id);
        }
      }}
    >
      <Breakable text={section.title} />
    </a>
  );

  const entityItem = (entity: DocumentEntity) => {
    const children = tree.children.get(entity.id) ?? [];
    const open = expanded.has(entity.id);
    const type = typeLabel(entityType(entity.id));
    return (
      <li key={entity.id}>
        <div className="nav-row">
          {children.length > 0 ? (
            <button
              type="button"
              className="nav-toggle"
              aria-label={`${open ? 'Collapse' : 'Expand'} ${entity.name}`}
              aria-expanded={open}
              onClick={() => toggle(entity.id)}
            >
              {open ? '▾' : '▸'}
            </button>
          ) : (
            <span className="nav-toggle" />
          )}
          <a
            href={entityHash(entity.id)}
            className="nav-entity"
            aria-current={selected === entity.id ? 'true' : undefined}
            title={entity.id}
            onClick={(event: MouseEvent) => {
              if (isPlainClick(event)) {
                event.preventDefault();
                onEntity(entity.id);
              }
            }}
          >
            <span className={`type-badge type-${entityType(entity.id)}`} title={type.long}>
              {type.short}
            </span>
            <span className="nav-name">
              <Breakable text={entity.name} />
            </span>
          </a>
        </div>
        {open && children.length > 0 && <ul>{children.map(entityItem)}</ul>}
      </li>
    );
  };

  return (
    <div className="nav-pane" ref={pane}>
      <div className="pane-header">
        <h2>Contents</h2>
        <button type="button" className="icon-button pane-close" aria-label="Close navigation" onClick={onClose}>
          ✕
        </button>
      </div>
      <ul className="nav-sections">
        {document.sections.map((section) => (
          <li key={section.id}>
            {sectionLink(section)}
            {section.sections.length > 0 && (
              <ul>
                {section.sections.map((child) => (
                  <li key={child.id}>{sectionLink(child)}</li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
      {tree.groups.map((group) => {
        const open = expanded.has(group.key);
        return (
          <div key={group.key} className="nav-group">
            <h2>
              <button type="button" className="nav-group-toggle" aria-expanded={open} onClick={() => toggle(group.key)}>
                <span aria-hidden="true">{open ? '▾' : '▸'}</span> {group.label}{' '}
                <span className="nav-count">{group.roots.length}</span>
              </button>
            </h2>
            {open && <ul className="nav-tree">{group.roots.map(entityItem)}</ul>}
          </div>
        );
      })}
    </div>
  );
}
