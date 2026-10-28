// Generates TypeScript types from the document model JSON Schema into src/generated/.
import { mkdirSync, writeFileSync } from 'node:fs';
import { compile } from 'json-schema-to-typescript';
import { documentSchema, schemasById } from './schemas.mjs';

const lanternSchemas = {
  order: 1,
  canRead: (file) => file.url.startsWith('urn:lantern:schema:'),
  read: (file) => {
    const schema = schemasById.get(file.url.split('#')[0]);
    if (!schema) {
      throw new Error(`Unknown schema ${file.url}`);
    }
    return JSON.stringify(schema);
  },
};

const ts = await compile(documentSchema, 'Document', {
  bannerComment:
    '/* Generated from lantern-core/src/main/resources/schema/document.schema.json by\n' +
    ' * scripts/generate-types.mjs. Do not edit; run `npm run generate`. */',
  // The fact envelope's $defs/object would otherwise become a type named Object, shadowing the
  // global. The generator names unnamed definitions after their key, capitalized.
  customName: (schema) => (schema.$id === 'Object' ? 'FactObject' : undefined),
  $refOptions: { resolve: { lantern: lanternSchemas } },
});

const out = new URL('../src/generated/', import.meta.url);
mkdirSync(out, { recursive: true });
writeFileSync(new URL('document.ts', out), ts);
