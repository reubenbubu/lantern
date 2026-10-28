// The documents the tests render: the schema fixture lantern-core's Java tests write, which has
// every kind of block, and the composer's golden output for a small multi-module repository.
import { readFileSync } from 'node:fs';
import type { Diagram, Document, Section } from '../src/generated/document.ts';

const read = (relative: string): string =>
  readFileSync(new URL(`../../${relative}`, import.meta.url), 'utf8');

export const schemaFixtureJson = read(
  'lantern-core/src/test/resources/schema/document/valid-document.json',
);
export const composerOutputJson = read('lantern-compose/src/test/resources/golden/shop.json');

export const schemaFixture = (): Document => JSON.parse(schemaFixtureJson) as Document;
export const composerOutput = (): Document => JSON.parse(composerOutputJson) as Document;

export function diagrams(document: Document): Diagram[] {
  const found: Diagram[] = [];
  const visit = (sections: Section[]) => {
    for (const section of sections) {
      for (const block of section.blocks) {
        if (block.kind === 'diagram') {
          found.push(block);
        }
      }
      visit(section.sections);
    }
  };
  visit(document.sections);
  return found;
}
