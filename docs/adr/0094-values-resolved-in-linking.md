# 0094: Values written in code are resolved in linking, one kind at a time

Status: accepted (#556); a step of #519 toward #522

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 7, "Values are emitted as
written") moves placeholder resolution out of the extractors. Seven extractors resolve `${...}`
against their module's config per profile (`CodeConfig`). The linker's `ValueResolution` rule
(#522) is to take that over, and it depends on the link rules of #521.

On the development sets most HTTP calls, gateway routes and datasources, and project-2's messaging,
hold placeholders. If #519 stopped the extractors resolving in one step, the documents would lose
those links until #522 lands, two large changes later, with no unchanged scorecard to check each
step against. The owner set the order #519, #521, #522 on 2026-10-31.

## Decisions

- **#519 is cut into slices**, each of which keeps the fake-model scorecards unchanged:
  - the link-side lookup (#556, this ADR);
  - HTTP requests and addresses (#557);
  - gateway routes (#558);
  - message destinations (#559);
  - datasources and data config (#560);
  - endpoints and Spring's scheduled values (#519 itself), which closes the issue.

  Each slice makes one kind of fact keep its values as written and moves resolving it to the
  linker.
- **The lookup** (`io.lantern.link.CodeValues`) resolves a text from code against the config of
  the code's module, in the default view and each profile, as `CodeConfig` did:
  - It reads the module's own `config-value` facts. A config repository's files and code's
    defaults are left out, as `CodeConfig` leaves them out.
  - It falls back to the config of the only module of the repository that has config, else to the
    placeholders' defaults.
  - It looks up each key's value as written in the view (the profile's fact, else the default's),
    so placeholders nest as Spring nests them (`ConfigExtractor.resolve`).
  - A profile that sets any item of a list hides the default's other items, as Spring replaces a
    list whole.
  - A value a secret was substituted into is kept as written and masked. The secret is a key the
    config extractor names a secret, a key the identity extractor finds holding a JWT secret, or a
    key whose value the config extractor masked whole.
- **Known gaps**, from what the facts record:
  - The facts hold a profile's value only where it differs from the default view. So a profile
    that sets a list to fewer items, all equal to the default's first ones, is not seen to drop
    the rest.
  - A value recorded with what looked like a URL's credentials removed is used as recorded. An
    e-mail address reads as user info (#561).
- `CodeValuesTest` checks the lookup against `CodeConfig` on fixtures covering:
  - profiles, defaults and nested values;
  - lists a profile replaces;
  - name-based and JWT secrets, and URLs with credentials;
  - escapes, and a module without config.

  `CodeValuesIT` checks every config key of every module of the development sets, as a
  placeholder, in every view.
- **What #522 adds.** #522 turns the lookup into the `ValueResolution` rule:
  - deployment environments as views;
  - `RESOLVES_TO` claims with lineage to the config facts used;
  - an architecture rule that only it resolves placeholders.

## Consequences

- No output changes with #556. The lookup is used from #557 on.
- On the development sets the lookup gives what `CodeConfig` gives for 1,293 of 1,295 key and
  view pairs. The two others are #561's e-mail address, which no code value uses.
