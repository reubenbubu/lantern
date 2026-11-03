// The vocabulary the scorer reads (docs/adr/0072-scoring-harness.md, amendment for #742): every
// vocabulary type the modules declare, in lantern-*/src/main/resources/vocabulary/*.json, so the
// scorer takes the kinds of interaction from the vocabulary, as the composer does, never from a list
// of its own.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** The root of every runtime relationship (BuiltInTypes.INTERACTS_WITH). */
export const INTERACTS_WITH = 'INTERACTS_WITH';

/** The folders the modules declare their vocabulary types in. */
export function vocabularyFolders(repo = REPO) {
  return readdirSync(repo)
    .filter((m) => m.startsWith('lantern-'))
    .map((m) => join(repo, m, 'src', 'main', 'resources', 'vocabulary'))
    .filter((d) => existsSync(d));
}

/** Every vocabulary type of the folders, by name: `{ name, kind, parent }`. */
export function readVocabulary(folders = vocabularyFolders()) {
  const types = new Map();
  for (const dir of folders) {
    for (const file of readdirSync(dir).filter((f) => f.endsWith('.json')).sort()) {
      const parsed = JSON.parse(readFileSync(join(dir, file), 'utf8'));
      for (const t of Array.isArray(parsed) ? parsed : (parsed.types ?? [])) {
        types.set(t.name, { name: t.name, kind: t.kind, parent: t.parent ?? null });
      }
    }
  }
  return types;
}

/** Whether the type is the ancestor or descends from it, by its parents. */
export function isA(vocabulary, name, ancestor) {
  const seen = new Set();
  for (let at = name; at && !seen.has(at); at = vocabulary.get(at)?.parent) {
    if (at === ancestor) return true;
    seen.add(at);
  }
  return false;
}

/** The relationship types that are interactions: INTERACTS_WITH and every type below it. */
export function interactionKinds(vocabulary) {
  return new Set(
    [...vocabulary.values()]
      .filter((t) => t.kind === 'relationship' && isA(vocabulary, t.name, INTERACTS_WITH))
      .map((t) => t.name),
  );
}
