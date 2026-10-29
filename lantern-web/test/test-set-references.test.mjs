// Checks the references committed under testsets/<set>/reference/ (docs/design.md, Quality bar;
// docs/adr/0029): each reference.c4.json is valid for the C4 report spec and keeps its
// cross-reference rules, every (repo, commit) its sources cite is a repository and a commit of the
// set's pins.txt, and its notes.md names every pin's full commit hash. No network: the pins are
// compared with the reference, not with the repositories.
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';
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

test('references are committed for the sets that have one', () => {
  assert.ok(sets.length >= 2, `found references for ${sets.join(', ')}`);
});

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
      const pin = pinned.find((p) => p.name === repo);
      assert.ok(pin, `${repo} is cited but not in pins.txt`);
      assert.ok(pin.commit.startsWith(commit), `${repo}: cites ${commit}, pinned ${pin.commit}`);
    }
    const notes = readFileSync(`${dir}notes.md`, 'utf8');
    for (const { name, commit } of pinned) {
      assert.ok(notes.includes(commit), `notes.md of ${set} does not name ${name}'s pinned commit`);
    }
  });

  test(`${set}: the reference has its verification notes`, () => {
    assert.ok(existsSync(`${dir}verification.md`));
  });
}
