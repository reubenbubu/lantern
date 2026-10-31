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
  - For this to be exact, the config extractor (version 8) records a profile's value also where it
    is written differently but resolves the same, and every item of a list the profile sets
    (one with an item it holds differently from the default view, or not at all). Before, a
    profile's facts held only the values that resolve differently, which does not say which list
    items a profile keeps.
  - A value a secret was substituted into is kept as written and masked. The secret is a key the
    config extractor names a secret, a key the identity extractor finds holding a JWT secret, or a
    key whose value the config extractor masked whole.
- **Known gaps**, from what the facts record:
  - A module that has config files but no values has no facts, so its code takes its repository's
    only other config, where `CodeConfig` took the empty one.
  - The lookup masks a value that uses a key the config extractor masked for holding a credential
    or a cipher; `CodeConfig` masked only on a secret key's name.
  - A value recorded with what looked like a URL's credentials removed is used as recorded. An
    e-mail address reads as user info (#561).
- `CodeValuesTest` checks the lookup against `CodeConfig` on fixtures covering:
  - profiles, defaults and nested values, and a profile value written differently that resolves
    the same;
  - lists a profile replaces, shortens, or changes in one item only;
  - a config server's own config beside the files it serves;
  - name-based and JWT secrets, and URLs with credentials;
  - escapes, and a module without config.

  `CodeValuesIT` checks every config key of every module of the development sets, as a
  placeholder, in every view.
- **What #522 adds.** #522 turns the lookup into the `ValueResolution` rule:
  - deployment environments as views;
  - `RESOLVES_TO` claims with lineage to the config facts used;
  - an architecture rule that only it resolves placeholders.

## Amendment (#557): the first link step

Each slice adds its kind to one link step, `ValueViews`, which runs before every other. It resolves
the values its kind's facts keep as written, through the lookup, and records the facts the
extractor recorded before: the same subject, predicate, object, attributes, evidence and
confidence, with origin `values`. Every later link step and the composer read those facts as before,
and the analysis run gives the summarizer the step's facts too (it summarizes before linking), so
a slice changes no document. The facts' IDs change with their origin, so a real run asks the LLM
again where a prompt cites one; they carry no lineage to the facts they come from until #522. The HTTP client's requests are the first kind
(ADR 0038, amendment for #557). Gateway routes are the second (ADR 0077, amendment for #558): routes
built in code are resolved like requests, and the routes config defines are read from the config
facts, through the gateway extractor's own reading of config. #522 replaces the step's facts with `RESOLVES_TO` claims, and the
readers move to the entities with #521.

## Consequences

- The lookup is used from #557 on. The config extractor's extra profile facts are the only output
  change of #556; the fake-model scorecards are unchanged (#556's PR).
- On the development sets the lookup gives what `CodeConfig` gives for every key and view pair
  but two, #561's e-mail address, which no code value uses.
