// What the Architecture Explorer derives from a document: the entity tree for navigation, where
// each entity is shown, the search index and deep links. All of it is plain data, so it is tested
// without a browser.
import type { Block, Document, DocumentEntity, Inline, Section } from '../generated/document.ts';

/** An entity's type: the part of its ID before the first colon (`module`, `type`, ...). */
export function entityType(id: string): string {
  const colon = id.indexOf(':');
  return colon < 0 ? id : id.slice(0, colon);
}

/** Types in the order the navigation tree lists them; others follow alphabetically. */
const TYPE_ORDER = ['system', 'repo', 'service', 'library', 'module', 'package', 'type', 'artifact'];

const TYPE_LABELS: Record<string, { short: string; long: string; group: string }> = {
  system: { short: 'sys', long: 'System', group: 'Systems' },
  repo: { short: 'repo', long: 'Repository', group: 'Other repositories' },
  service: { short: 'svc', long: 'Service', group: 'Services' },
  library: { short: 'lib', long: 'Library', group: 'Libraries' },
  module: { short: 'mod', long: 'Module', group: 'Modules' },
  package: { short: 'pkg', long: 'Package', group: 'Packages' },
  type: { short: 'type', long: 'Type', group: 'Types' },
  artifact: { short: 'ext', long: 'Third-party artifact', group: 'Third-party artifacts' },
};

export function typeLabel(type: string): { short: string; long: string; group: string } {
  return TYPE_LABELS[type] ?? { short: type.slice(0, 4), long: type, group: type };
}

function typeRank(type: string): number {
  const rank = TYPE_ORDER.indexOf(type);
  return rank < 0 ? TYPE_ORDER.length : rank;
}

const compareText = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

export function compareEntities(a: DocumentEntity, b: DocumentEntity): number {
  const ta = entityType(a.id);
  const tb = entityType(b.id);
  return (
    typeRank(ta) - typeRank(tb) ||
    compareText(ta, tb) ||
    compareText(a.name.toLowerCase(), b.name.toLowerCase()) ||
    compareText(a.id, b.id)
  );
}

export interface NavGroup {
  /** Stable key, for expansion state. */
  key: string;
  label: string;
  roots: DocumentEntity[];
}

export interface EntityTree {
  /** Children of each entity, sorted by type and name. */
  children: Map<string, DocumentEntity[]>;
  /** The entity's chain of parents, nearest first, as far as they are in the document. */
  ancestors(id: string): string[];
  /** The document's own entity and everything else without a parent, grouped by type. */
  groups: NavGroup[];
}

/** The entity the document is about: that of its first section that has one. */
export function homeEntity(document: Document): string | undefined {
  const find = (sections: Section[]): string | undefined => {
    for (const section of sections) {
      const found = section.entity ?? find(section.sections);
      if (found !== undefined) {
        return found;
      }
    }
    return undefined;
  };
  return find(document.sections);
}

export function buildEntityTree(document: Document): EntityTree {
  const byId = new Map(document.entities.map((e) => [e.id, e]));
  const parentOf = (e: DocumentEntity) =>
    e.parent !== undefined && e.parent !== e.id && byId.has(e.parent) ? e.parent : undefined;
  const children = new Map<string, DocumentEntity[]>();
  const roots: DocumentEntity[] = [];
  for (const entity of document.entities) {
    const parent = parentOf(entity);
    if (parent === undefined) {
      roots.push(entity);
    } else {
      children.set(parent, [...(children.get(parent) ?? []), entity]);
    }
  }
  for (const list of children.values()) {
    list.sort(compareEntities);
  }
  const ancestors = (id: string): string[] => {
    const chain: string[] = [];
    let current = byId.get(id);
    while (current) {
      const parent = parentOf(current);
      if (parent === undefined || chain.includes(parent) || parent === id) {
        break;
      }
      chain.push(parent);
      current = byId.get(parent);
    }
    return chain;
  };
  // A parent cycle leaves entities unreachable from any root; list them at the top level.
  const reachable = new Set<string>();
  const visit = (e: DocumentEntity) => {
    if (!reachable.has(e.id)) {
      reachable.add(e.id);
      (children.get(e.id) ?? []).forEach(visit);
    }
  };
  roots.forEach(visit);
  for (const entity of document.entities) {
    if (!reachable.has(entity.id)) {
      roots.push(entity);
      visit(entity);
    }
  }

  const home = homeEntity(document);
  const homeRoot = home !== undefined && byId.has(home) ? (ancestors(home).at(-1) ?? home) : undefined;
  const groups: NavGroup[] = [];
  if (homeRoot !== undefined) {
    groups.push({ key: 'group:home', label: typeLabel(entityType(homeRoot)).long, roots: [byId.get(homeRoot)!] });
  }
  const byType = new Map<string, DocumentEntity[]>();
  for (const root of roots) {
    if (root.id !== homeRoot) {
      const type = entityType(root.id);
      byType.set(type, [...(byType.get(type) ?? []), root]);
    }
  }
  const types = [...byType.keys()].sort((a, b) => typeRank(a) - typeRank(b) || compareText(a, b));
  for (const type of types) {
    groups.push({
      key: `group:${type}`,
      label: typeLabel(type).group,
      roots: byType.get(type)!.sort(compareEntities),
    });
  }
  return { children, ancestors, groups };
}

export interface Placement {
  id: string;
  title: string;
}

/**
 * Where each entity appears: the sections about it and the diagrams that draw it, in document
 * order.
 */
export function entityPlacements(document: Document): Map<string, Placement[]> {
  const placements = new Map<string, Placement[]>();
  const add = (entity: string, placement: Placement) => {
    const list = placements.get(entity) ?? [];
    if (!list.some((p) => p.id === placement.id)) {
      list.push(placement);
    }
    placements.set(entity, list);
  };
  const visit = (sections: Section[]) => {
    for (const section of sections) {
      if (section.entity !== undefined) {
        add(section.entity, { id: section.id, title: section.title });
      }
      for (const block of section.blocks) {
        if (block.kind === 'diagram') {
          for (const node of block.nodes) {
            add(node.id, { id: block.id, title: block.title ?? section.title });
          }
        }
      }
      visit(section.sections);
    }
  };
  visit(document.sections);
  return placements;
}

export type SearchTarget = { kind: 'section'; id: string } | { kind: 'entity'; id: string };

export interface SearchItem {
  target: SearchTarget;
  /** The name shown in the results. */
  label: string;
  /** What kind of thing it is, shown next to the label. */
  detail: string;
  /** Lower-cased text the query is matched against, label first. */
  text: string;
  /** Lower-cased label, which ranks matches. */
  name: string;
}

function plainText(content: Inline[] | undefined): string {
  return (content ?? []).map((i) => (i.kind === 'citation' ? '' : i.text)).join('');
}

function blockTitle(block: Block): string | undefined {
  return block.kind === 'diagram' || block.kind === 'tree' || block.kind === 'table' || block.kind === 'callout'
    ? block.title
    : undefined;
}

/** Sections, titled blocks and entities, in document order. */
export function buildSearchIndex(document: Document): SearchItem[] {
  const items: SearchItem[] = [];
  const visit = (sections: Section[], path: string[]) => {
    for (const section of sections) {
      const trail = [...path, section.title];
      items.push({
        target: { kind: 'section', id: section.id },
        label: section.title,
        detail: path.length > 0 ? `Section in ${path.join(' › ')}` : 'Section',
        text: `${section.title} ${section.id}`.toLowerCase(),
        name: section.title.toLowerCase(),
      });
      for (const block of section.blocks) {
        const title = blockTitle(block);
        if (title !== undefined) {
          items.push({
            target: { kind: 'section', id: block.id },
            label: title,
            detail: `${block.kind === 'tree' ? 'Tree' : block.kind[0].toUpperCase() + block.kind.slice(1)} in ${trail.join(' › ')}`,
            text: `${title} ${block.id}`.toLowerCase(),
            name: title.toLowerCase(),
          });
        }
      }
      visit(section.sections, trail);
    }
  };
  visit(document.sections, []);
  for (const entity of [...document.entities].sort(compareEntities)) {
    items.push({
      target: { kind: 'entity', id: entity.id },
      label: entity.name,
      detail: typeLabel(entityType(entity.id)).long,
      text: `${entity.name} ${entity.id} ${plainText(entity.description)}`.toLowerCase(),
      name: entity.name.toLowerCase(),
    });
  }
  return items;
}

/**
 * The items matching every word of the query, best first: a label equal to the query, then labels
 * starting with it, labels containing it, labels containing every word, then matches elsewhere
 * (ID, description). Ties keep document order.
 */
export function search(index: SearchItem[], query: string, limit = 50): SearchItem[] {
  const q = query.trim().toLowerCase();
  if (q === '') {
    return [];
  }
  const words = q.split(/\s+/);
  const rank = (item: SearchItem) =>
    item.name === q
      ? 0
      : item.name.startsWith(q)
        ? 1
        : item.name.includes(q)
          ? 2
          : words.every((w) => item.name.includes(w))
            ? 3
            : 4;
  return index
    .map((item, position) => ({ item, position }))
    .filter(({ item }) => words.every((w) => item.text.includes(w)))
    .sort((a, b) => rank(a.item) - rank(b.item) || a.position - b.position)
    .slice(0, limit)
    .map(({ item }) => item);
}

/** What a page address's fragment points at. */
export type DeepLink = { kind: 'entity'; id: string } | { kind: 'element'; id: string } | { kind: 'none' };

export function parseHash(hash: string): DeepLink {
  const fragment = hash.startsWith('#') ? hash.slice(1) : hash;
  if (fragment === '') {
    return { kind: 'none' };
  }
  if (fragment.startsWith('entity=')) {
    try {
      const id = decodeURIComponent(fragment.slice('entity='.length));
      return id === '' ? { kind: 'none' } : { kind: 'entity', id };
    } catch {
      return { kind: 'none' };
    }
  }
  try {
    return { kind: 'element', id: decodeURIComponent(fragment) };
  } catch {
    return { kind: 'element', id: fragment };
  }
}
