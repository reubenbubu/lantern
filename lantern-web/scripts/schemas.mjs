// Loads Lantern's JSON Schemas from lantern-core, where they are defined.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const dir = fileURLToPath(new URL('../../lantern-core/src/main/resources/schema/', import.meta.url));

export function loadSchema(name) {
  return JSON.parse(readFileSync(dir + name, 'utf8'));
}

export const factEnvelopeSchema = loadSchema('fact-envelope.schema.json');
export const documentSchema = loadSchema('document.schema.json');

/** The schemas by $id, so references between them resolve without the network. */
export const schemasById = new Map(
  [factEnvelopeSchema, documentSchema].map((schema) => [schema.$id, schema]),
);
