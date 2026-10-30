# 0087: A glossary in the C4 report, and its legend's boundary and async arrowhead

Status: accepted (#229), amends ADR 0063 and ADR 0086, pending the user's review (#24)

## Context

The C4 reports failed two Quality bar checks (ADR 0046, ADR 0063): they had no glossary, since
the renderer had no page for one, and the report spec's legend had no entry for the dashed
boundary of container and component diagrams, nor for the open arrowhead of asynchronous
messages. Documentation pages (ADR 0086) now give a glossary a page. The glossary of ADR 0063
explained acronyms only from a table bundled with Lantern, so a term a system coins, such as BFF,
stayed unexplained even when its README spells it out. Role names the code checks, such as
`ADMIN`, were counted as acronyms.

## Decisions

- **The C4 views end with the glossary** (`ViewDefinition.c4Report` and `c4System`, after the
  deployment), which the renderer makes a documentation page (ADR 0086).
- **What the documentation says first.** The glossary explains an acronym by what the README or
  decision records (facts of origin `readme`) of the repositories in scope write it out as, citing
  those facts: the acronym next to words whose initials spell it, as in `Backend for Frontend
  (BFF)`, `JWKS (JSON Web Key Set)` or `PKCE stands for Proof Key for Code Exchange`. A minor word
  ("for", "of") may give no letter; a camel-case word may give one per part (WebSocket: W, S).
  The first text by fact ID wins; up to three facts with the same words are cited. Otherwise the
  bundled table of standard terms explains it (ADR 0063), and an acronym neither explains stays
  unknown: named as code in a note, so the check still fails. No LLM call is made, so nothing is
  invented.
- **A Source column** says where each meaning comes from: the file (in a system's report with its
  repository), or "Lantern's glossary". The report spec shows no citations, so this is how its
  reader sees the evidence. The Explorer's and the Brief's glossaries get the same column.
- **Role names are code.** A person's description (ADR 0074) shows the role names the code checks
  as code, so the Quality bar does not count `ADMIN` or `ATTENDEE`, constants in the code, as
  acronyms.
- **The bundled table** gains the standard terms the test sets' documents use: CVV, JWKS, POM, QR,
  README, RESP, RSA, W3C and XA.
- **The report spec's legend** (`report-specs/c4-model-sequence-diagrams`, a change to the user's
  spec) shows, under a diagram drawn inside a boundary, a dashed box named after the boundary's
  kind ("Software System boundary", "Container boundary"); under a sequence, the call's filled
  arrowhead, the async message's open arrowhead when the sequence has one, and the dashed return.
  So the Quality bar no longer fails the C4 report's diagrams for these.

## Consequences

- A C4 report's glossary lists every acronym the Quality bar finds in it; an acronym that only the
  code uses, without documentation, is reported as unknown rather than guessed.
- The legend check of the C4 report now fails only for what its template does not draw.

## Not verified

The fake-model runs' READMEs of project-1 to project-4 spell out none of the acronyms their
reports use, so the documented meanings are checked by `GlossaryTest` only.
