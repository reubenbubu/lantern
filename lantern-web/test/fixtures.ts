// The documents the tests render: the schema fixture lantern-core's Java tests write, which has
// every kind of block, the composer's golden output for a small multi-module repository, as an
// Architecture Explorer document and as an Executive Brief, and its C4 report of a small system
// with deployable units, stores, queues, external systems and components.
import { readFileSync } from 'node:fs';
import type { Diagram, Document, Section } from '../src/generated/document.ts';

const read = (relative: string): string =>
  readFileSync(new URL(`../../${relative}`, import.meta.url), 'utf8');

export const schemaFixtureJson = read(
  'lantern-core/src/test/resources/schema/document/valid-document.json',
);
export const composerOutputJson = read('lantern-compose/src/test/resources/golden/shop.json');
export const briefOutputJson = read('lantern-compose/src/test/resources/golden/shop.brief.json');
export const systemOutputJson = read('lantern-compose/src/test/resources/golden/runtime.c4.json');

export const schemaFixture = (): Document => JSON.parse(schemaFixtureJson) as Document;
export const composerOutput = (): Document => JSON.parse(composerOutputJson) as Document;
export const briefOutput = (): Document => JSON.parse(briefOutputJson) as Document;
export const systemOutput = (): Document => JSON.parse(systemOutputJson) as Document;

/**
 * The document with a summary's citation of another summary, a cross-reference marked as a
 * citation (ADR 0027), at the end of its first paragraph and of each entity description that has
 * one: the composer no longer writes them, since stored summaries cite facts (#530), but documents
 * may still hold them. Each cites an entity that has no section of its own.
 */
export function withSummaryCitations(document: Document): Document {
  const sectioned = new Set<string>();
  const visit = (sections: Section[]) =>
    sections.forEach((s) => {
      if (s.entity) sectioned.add(s.entity);
      visit(s.sections);
    });
  visit(document.sections);
  const cited = document.entities.find((e) => !sectioned.has(e.id) && e.id.startsWith('package:'))!;
  const ref = { kind: 'ref' as const, text: cited.name, target: { kind: 'entity' as const, id: cited.id }, citation: true };
  let done = false;
  const sections = (list: Section[]): Section[] =>
    list.map((section) => ({
      ...section,
      blocks: section.blocks.map((block) => {
        if (!done && block.kind === 'paragraph') {
          done = true;
          return { ...block, content: [...block.content, ref] };
        }
        return block;
      }),
      sections: sections(section.sections),
    }));
  return {
    ...document,
    sections: sections(document.sections),
    entities: document.entities.map((e) =>
      e.description && e.description.length > 0 && e.id !== cited.id && !`${e.name} ${e.id}`.includes(cited.name)
        ? { ...e, description: [...e.description, ref] }
        : e,
    ),
  };
}

/** The document's diagrams that are graphs: every diagram but sequence diagrams. */
export function diagrams(document: Document): Diagram[] {
  const found: Diagram[] = [];
  const visit = (sections: Section[]) => {
    for (const section of sections) {
      for (const block of section.blocks) {
        if (block.kind === 'diagram' && block.diagramKind !== 'sequence') {
          found.push(block);
        }
      }
      visit(section.sections);
    }
  };
  visit(document.sections);
  return found;
}
