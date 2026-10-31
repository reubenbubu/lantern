// Checks the references committed under testsets/<set>/reference/ (docs/design.md, Quality bar;
// docs/adr/0029): each reference.c4.json is valid for the C4 report spec and keeps its
// cross-reference rules, every (repo, commit) its sources cite is a repository and a commit of the
// set's pins.txt, and its notes.md names every pin's full commit hash. No network: the pins are
// compared with the reference, not with the repositories.
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
import { checkMap, coverageItems, parseAt, readCoverageMap, readReference } from '../scripts/coverage.mjs';
import { reportSpecIssues } from '../scripts/report-spec-check.mjs';

const spec = 'c4-model-sequence-diagrams';
const testsets = fileURLToPath(new URL('../../testsets/', import.meta.url));

/** The "<name> <url> <hash>" lines of a set's pins.txt, as { name, commit }. */
function pins(set) {
  return readFileSync(`${testsets}${set}/pins.txt`, 'utf8')
    .split('\n')
    .filter((line) => line.trim() !== '' && !line.startsWith('#'))
    .map((line) => {
      const [name, url, commit] = line.trim().split(/\s+/);
      assert.match(commit, /^[0-9a-f]{40}$/, `${set}: ${name} must be pinned to a full hash`);
      assert.ok(url.startsWith('https://'), `${set}: ${name} needs a clone URL`);
      return { name, commit };
    });
}

/** Every { repo, commit } cited as a source anywhere in the data. */
function citedSources(node, found = []) {
  if (Array.isArray(node)) node.forEach((item) => citedSources(item, found));
  else if (node !== null && typeof node === 'object') {
    if (typeof node.repo === 'string' && typeof node.commit === 'string') found.push(node);
    Object.values(node).forEach((item) => citedSources(item, found));
  }
  return found;
}

const sets = readdirSync(testsets).filter((name) =>
  existsSync(`${testsets}${name}/reference/reference.c4.json`),
);

test('exactly the development sets project-1 to project-4 have a reference', () => {
  assert.deepEqual(sets.sort(), ['project-1', 'project-2', 'project-3', 'project-4']);
});

// Every open-source set, held-out ones included, has well-formed pins (format only, no network).
for (const set of readdirSync(testsets).filter((name) => /^project-[1-9]$/.test(name))) {
  test(`${set}: pins.txt is well formed`, () => {
    assert.ok(pins(set).length > 0);
  });
}

for (const set of sets) {
  const dir = `${testsets}${set}/reference/`;
  const data = JSON.parse(readFileSync(`${dir}reference.c4.json`, 'utf8'));

  test(`${set}: the reference is valid for the C4 report spec`, () => {
    assert.deepEqual(reportSpecIssues(spec, data), []);
  });

  test(`${set}: the reference describes the pinned commits`, () => {
    const pinned = pins(set);
    const sources = citedSources(data);
    assert.ok(sources.length > 0, 'the reference cites no sources');
    for (const { repo, commit } of sources) {
      assert.ok(commit.length >= 7, `${repo}: cited commit ${commit} is shorter than 7 characters`);
      const pin = pinned.find((p) => p.name === repo);
      assert.ok(pin, `${repo} is cited but not in pins.txt`);
      assert.ok(pin.commit.startsWith(commit), `${repo}: cites ${commit}, pinned ${pin.commit}`);
    }
    const notes = readFileSync(`${dir}notes.md`, 'utf8');
    for (const { name, commit } of pinned) {
      assert.ok(notes.includes(commit), `notes.md of ${set} does not name ${name}'s pinned commit`);
    }
  });

  test(`${set}: the alias table names only reference elements and known kinds`, () => {
    const file = `${dir}aliases.json`;
    if (!existsSync(file)) return;
    const aliases = JSON.parse(readFileSync(file, 'utf8'));
    const kinds = new Set(['container', 'database', 'queue', 'external', 'person']);
    for (const id of Object.values(aliases.elements ?? {})) {
      assert.ok(data.elements[id], `aliases.json maps to unknown reference element ${id}`);
    }
    for (const [id, other] of Object.entries(aliases.eitherKind ?? {})) {
      assert.ok(kinds.has(data.elements[id]?.kind), `eitherKind: ${id} is not a reference element of a scored kind`);
      for (const kind of other) {
        assert.ok(kinds.has(kind) && kind !== data.elements[id].kind, `eitherKind: ${id} lists ${kind}`);
      }
    }
  });

  test(`${set}: the reference has its verification notes`, () => {
    assert.ok(existsSync(`${dir}verification.md`));
  });
}

// The coverage maps (docs/adr/0089-extraction-coverage.md): each maps every item of its set's
// reference (or, without one, lists its items) to the facts it needs, in a known area, and cites
// only the set's pinned repositories where the set has a reference.
const mapped = readdirSync(testsets).filter((name) => existsSync(`${testsets}${name}/coverage.json`));

test('every development set with a reference has a coverage map', () => {
  for (const set of sets) assert.ok(mapped.includes(set), `${set} has no coverage.json`);
});

for (const set of mapped) {
  test(`${set}: its coverage map is sound`, () => {
    const map = readCoverageMap(`${testsets}${set}`);
    const reference = readReference(`${testsets}${set}`);
    assert.deepEqual(checkMap(coverageItems(reference, map), map), []);
    if (reference) {
      const repos = new Set(pins(set).map((pin) => pin.name));
      for (const [key, entry] of Object.entries(map.items)) {
        for (const need of entry.needs ?? []) {
          assert.ok(repos.has(parseAt(need.at).repo), `${set}: ${key} cites ${need.at}, not a pinned repository`);
        }
      }
    }
  });
}

// project-8 (FleetCare) keeps its reference in its source, which testsets/project-8/setup.sh copies
// beside the checkout (docs/adr/0029, amendment for #285). Where it is set up in the default folder,
// the checkout must be at the pin and its reference valid; without the private set, this is skipped.
const project8 = `${process.env.HOME}/workspace/lantern-test-projects/project-8`;
test.skipIf(!existsSync(`${project8}/.lantern-pin`))('project-8: the checkout is at the pin and its reference is valid', () => {
  const [pin] = pins('project-8');
  assert.equal(readFileSync(`${project8}/.lantern-pin`, 'utf8').trim(), pin.commit, 'run testsets/project-8/setup.sh');
  const data = JSON.parse(readFileSync(`${project8}/expected/reference/reference.c4.json`, 'utf8'));
  assert.deepEqual(reportSpecIssues(spec, data), []);
});
