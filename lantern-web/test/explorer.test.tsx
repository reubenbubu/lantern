import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { fit, initialView, MAX_SCALE, MIN_SCALE, READABLE_SCALE, zoomAt } from '../src/diagram/zoom.ts';
import { Explorer } from '../src/explorer/Explorer.tsx';
import {
  buildEntityTree,
  buildSearchIndex,
  entityPlacements,
  entityType,
  homeEntity,
  parseHash,
  search,
} from '../src/explorer/model.ts';
import type { Document, DocumentEntity, Section } from '../src/generated/document.ts';
import { composerOutput, schemaFixture } from './fixtures.ts';

function allSections(sections: Section[]): Section[] {
  return sections.flatMap((s) => [s, ...allSections(s.sections)]);
}

const entity = (id: string, parent?: string): DocumentEntity => ({
  id,
  name: id.slice(id.indexOf(':') + 1),
  ...(parent ? { parent } : {}),
  facts: [],
});

const withEntities = (entities: DocumentEntity[]): Document => ({ ...composerOutput(), entities });

describe('the entity tree', () => {
  const document = composerOutput();
  const tree = buildEntityTree(document);

  test('starts with the document own repository, then groups the other roots by type', () => {
    expect(homeEntity(document)).toBe('repo:shop');
    expect(tree.groups[0]).toMatchObject({ key: 'group:home', roots: [{ id: 'repo:shop' }] });
    for (const group of tree.groups.slice(1)) {
      expect(new Set(group.roots.map((r) => entityType(r.id))).size).toBe(1);
    }
  });

  test('holds every entity exactly once', () => {
    const seen: string[] = [];
    const visit = (e: DocumentEntity) => {
      seen.push(e.id);
      (tree.children.get(e.id) ?? []).forEach(visit);
    };
    tree.groups.flatMap((g) => g.roots).forEach(visit);
    expect(seen.sort()).toEqual(document.entities.map((e) => e.id).sort());
  });

  test('lists children by type, then name', () => {
    expect(tree.children.get('repo:shop')!.map((e) => e.id)).toEqual([
      'library:com.example.shop:shop-api',
      'library:com.example.shop:shop-core',
      'module:com.example.shop:shop-parent',
    ]);
  });

  test('knows each entity chain of parents', () => {
    expect(tree.ancestors('type:com.example.shop.api.PriceService')).toEqual([
      'package:com.example.shop:shop-api:com.example.shop.api',
      'module:com.example.shop:shop-api',
      'module:com.example.shop:shop-parent',
      'repo:shop',
    ]);
    expect(tree.ancestors('repo:shop')).toEqual([]);
  });

  test('shows entities caught in a parent cycle, and parents missing from the document, at the top', () => {
    const cyclic = buildEntityTree(
      withEntities([entity('module:a', 'module:b'), entity('module:b', 'module:a'), entity('package:c', 'module:x')]),
    );
    const roots = cyclic.groups.flatMap((g) => g.roots.map((r) => r.id));
    expect(roots).toContain('package:c');
    expect(roots.filter((id) => id.startsWith('module:'))).toHaveLength(1);
    expect(cyclic.ancestors('module:a')).toEqual(['module:b']);
  });
});

describe('entity placements', () => {
  test('name the sections about an entity and the diagrams that draw it', () => {
    const placements = entityPlacements(composerOutput());
    expect(placements.get('library:com.example.shop:shop-api')!.map((p) => p.id)).toEqual([
      'containers.diagram',
      'components.library:com.example.shop:shop-api',
      'components.library:com.example.shop:shop-api.diagram',
    ]);
  });
});

describe('search', () => {
  const index = buildSearchIndex(composerOutput());

  test('finds sections, titled blocks and entities', () => {
    const kinds = new Set(index.map((i) => i.target.kind));
    expect(kinds).toEqual(new Set(['section', 'entity']));
    expect(index.some((i) => i.target.id === 'context.diagram')).toBe(true);
  });

  test('needs every word and ranks exact and leading matches of the name first', () => {
    expect(search(index, '')).toEqual([]);
    const results = search(index, 'shop-api');
    expect(results[0].name).toBe('shop-api');
    expect(results.every((r) => r.text.includes('shop-api'))).toBe(true);
    expect(search(index, 'price service').map((r) => r.label).slice(0, 2)).toEqual(['FixedPriceService', 'PriceService']);
    expect(search(index, 'nothing-like-this')).toEqual([]);
  });
});

describe('deep links', () => {
  test('name an entity, an element or nothing', () => {
    expect(parseHash('')).toEqual({ kind: 'none' });
    expect(parseHash('#')).toEqual({ kind: 'none' });
    expect(parseHash('#overview')).toEqual({ kind: 'element', id: 'overview' });
    expect(parseHash(`#entity=${encodeURIComponent('type:a.B$C')}`)).toEqual({ kind: 'entity', id: 'type:a.B$C' });
    expect(parseHash('#entity=%E0%A4%A')).toEqual({ kind: 'none' });
  });
});

describe('zoom', () => {
  test('fits content into the viewport, centered and never enlarged', () => {
    expect(fit({ width: 200, height: 100 }, { width: 400, height: 400 })).toEqual({ scale: 1, x: 100, y: 150 });
    expect(fit({ width: 800, height: 100 }, { width: 400, height: 400 })).toEqual({ scale: 0.5, x: 0, y: 175 });
  });

  test('opens wide diagrams at a readable scale at their left edge', () => {
    const view = initialView({ width: 4000, height: 400 }, { width: 400, height: 300 });
    expect(view).toEqual({ scale: READABLE_SCALE, x: 0, y: 0 });
    expect(initialView({ width: 300, height: 100 }, { width: 400, height: 100 })).toEqual({ scale: 1, x: 50, y: 0 });
  });

  test('keeps the point under the pointer in place and stays within limits', () => {
    const view = zoomAt({ scale: 1, x: 10, y: 20 }, 2, { x: 110, y: 120 });
    expect(view).toEqual({ scale: 2, x: -90, y: -80 });
    // The diagram point (100, 100) is still at (110, 120).
    expect(100 * view.scale + view.x).toBe(110);
    expect(zoomAt({ scale: 1, x: 0, y: 0 }, 100, { x: 0, y: 0 }).scale).toBe(MAX_SCALE);
    expect(zoomAt({ scale: 1, x: 0, y: 0 }, 0.001, { x: 0, y: 0 }).scale).toBe(MIN_SCALE);
  });
});

describe('Explorer', () => {
  const document = composerOutput();
  const html = renderToStaticMarkup(<Explorer document={document} />);

  test('renders the shell: navigation, content and context panel', () => {
    expect(html).toContain('class="explorer-nav pane"');
    expect(html).toContain('class="explorer-content"');
    expect(html).toContain('class="explorer-context pane"');
    expect(html).toContain('About this document');
    expect(html).toContain('placeholder="Search  /"');
  });

  test('renders every section under its ID and lists the top ones in the navigation', () => {
    for (const section of allSections(document.sections)) {
      expect(html).toContain(`id="${section.id}"`);
    }
    for (const section of document.sections) {
      expect(html).toContain(`href="#${section.id}"`);
    }
    expect(html).toContain(`href="#entity=${encodeURIComponent('repo:shop')}"`);
  });

  test('makes entity references select the entity, citations show their facts and trees fold', () => {
    expect(html).toMatch(/<a href="#entity=[^"]+" class="entity-ref"/);
    expect(html).toMatch(/<button type="button" class="citation-button" title="[^"]+">\[\d+(, \d+)*\]<\/button>/);
    expect(html).toContain('<details open=""><summary>');
    expect(html).toMatch(
      /<sup class="citation"><a href="#entity=[^"]+" class="entity-ref" title="Summary of [^"]+" aria-label="Summary of [^"]+">\[\u2197\]<\/a><\/sup>/,
    );
  });

  test('renders the schema fixture with every kind of block', () => {
    const fixture = schemaFixture();
    const page = renderToStaticMarkup(<Explorer document={fixture} />);
    for (const section of allSections(fixture.sections)) {
      expect(page).toContain(`id="${section.id}"`);
    }
  });
});
