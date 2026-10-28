// Validates documents against the document model JSON Schema: the fixture that lantern-core's
// Java tests write must pass, and each invalid case, applied to the fixture, must fail.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import Ajv2020 from 'ajv/dist/2020.js';
import { documentSchema, factEnvelopeSchema } from '../scripts/schemas.mjs';
import { sample } from './sample-document.ts';

const fixtures = fileURLToPath(
  new URL('../../lantern-core/src/test/resources/schema/document/', import.meta.url),
);
const readFixture = (name) => JSON.parse(readFileSync(fixtures + name, 'utf8'));

const ajv = new Ajv2020({ allErrors: true, strict: true });
ajv.addSchema(factEnvelopeSchema);
const validate = ajv.compile(documentSchema);

test('the Java fixture is valid', () => {
  assert.ok(validate(readFixture('valid-document.json')), ajv.errorsText(validate.errors));
});

test('the TypeScript sample is valid', () => {
  assert.ok(validate(sample), ajv.errorsText(validate.errors));
});

/** Sets the value at a JSON pointer, or removes the field when the case says so. */
function apply(invalidCase, document) {
  const keys = invalidCase.pointer.split('/').slice(1);
  const last = keys.pop();
  const parent = keys.reduce((node, key) => node[key], document);
  assert.ok(parent !== null && typeof parent === 'object', `no parent at ${invalidCase.pointer}`);
  if (invalidCase.remove) {
    assert.ok(last in parent, `nothing to remove at ${invalidCase.pointer}`);
    delete parent[last];
  } else {
    parent[last] = invalidCase.value;
  }
  return document;
}

for (const invalidCase of readFixture('invalid-cases.json')) {
  test(`refuses: ${invalidCase.description}`, () => {
    const document = apply(invalidCase, readFixture('valid-document.json'));
    assert.equal(validate(document), false);
  });
}
