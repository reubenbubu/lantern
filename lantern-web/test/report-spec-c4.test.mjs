// Checks the data Lantern writes into the report spec report-specs/c4-model-sequence-diagrams:
// the golden files lantern-app's C4ReportRendererTest writes must be valid against the spec's
// schema and keep the cross-reference rules its template checks on load (the schema's $comment).
// The spec's own sample data must pass the same checks, so the checks match the spec.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
import Ajv2020 from 'ajv/dist/2020.js';

const spec = 'c4-model-sequence-diagrams';
const specDir = fileURLToPath(new URL(`../../report-specs/${spec}/`, import.meta.url));
const goldenDir = fileURLToPath(
  new URL(`../../lantern-app/src/test/resources/report-specs/${spec}/`, import.meta.url),
);
const read = (path) => JSON.parse(readFileSync(path, 'utf8'));

// The spec's schema is the user's file; strict mode would refuse its annotation keywords.
const ajv = new Ajv2020({ allErrors: true, strict: false });
const validate = ajv.compile(read(`${specDir}${spec}.schema.json`));

/** The rules the template checks on load that JSON Schema cannot express. */
function crossReferenceIssues(data) {
  const issues = [];
  const elements = data.elements;
  const parentKinds = {
    container: ['system'],
    database: ['system', 'container'],
    queue: ['system', 'container'],
    component: ['container'],
  };
  const known = (id, where) => {
    if (!elements[id]) issues.push(`${where}: unknown element "${id}"`);
  };
  for (const [id, element] of Object.entries(elements)) {
    if (element.parent === undefined) continue;
    known(element.parent, `element ${id}`);
    const parent = elements[element.parent];
    if (parent && parentKinds[element.kind] && !parentKinds[element.kind].includes(parent.kind)) {
      issues.push(`element ${id}: a ${element.kind} cannot sit inside a ${parent.kind}`);
    }
  }
  const pageIds = new Set();
  for (const page of data.pages) {
    if (pageIds.has(page.id)) issues.push(`page ${page.id} is used more than once`);
    pageIds.add(page.id);
    if (page.element !== undefined) known(page.element, `page ${page.id}`);
    const sectionIds = new Set();
    const sequences = new Set(page.sections.filter((s) => s.type === 'sequence').map((s) => s.id));
    for (const section of page.sections) {
      const where = `page ${page.id} section ${section.id}`;
      if (sectionIds.has(section.id)) issues.push(`${where}: used more than once`);
      sectionIds.add(section.id);
      if (section.type === 'diagram') {
        const ids = new Set(section.elements ?? []);
        for (const r of section.relationships) {
          known(r.from, where);
          known(r.to, where);
          ids.add(r.from);
          ids.add(r.to);
        }
        (section.elements ?? []).forEach((id) => known(id, where));
        if (section.boundary !== undefined) {
          known(section.boundary, where);
          if (![...ids].some((id) => elements[id]?.parent === section.boundary)) {
            issues.push(`${where}: nothing has the boundary ${section.boundary} as its parent`);
          }
        }
        if (section.focus !== undefined) known(section.focus, where);
      }
      if (section.type === 'sequence') {
        section.participants.forEach((id) => known(id, where));
        for (const step of section.steps) {
          for (const id of [step.from, step.to, ...(step.over ?? [])]) {
            if (id !== undefined && !section.participants.includes(id)) {
              issues.push(`${where}: step uses ${id}, which is not a participant`);
            }
          }
        }
      }
      if (section.type === 'steps' && !sequences.has(section.of)) {
        issues.push(`${where}: "of" names no sequence section on this page`);
      }
      if (section.type === 'elements') (section.select.ids ?? []).forEach((id) => known(id, where));
    }
  }
  return issues;
}

function check(data) {
  assert.ok(validate(data), ajv.errorsText(validate.errors));
  assert.deepEqual(crossReferenceIssues(data), []);
}

test("the spec's sample data passes the checks", () => {
  check(read(`${specDir}${spec}.sample-data.json`));
});

const goldens = readdirSync(goldenDir).filter((name) => name.endsWith('.json'));

test('there are golden files to check', () => {
  assert.ok(goldens.length > 0);
});

for (const name of goldens) {
  test(`Lantern's ${name} is valid for the spec`, () => {
    check(read(goldenDir + name));
  });
}
