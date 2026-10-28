# 0001: Fact envelope details

Status: accepted (#3)

## Context

`docs/design.md` (Knowledge model and schema evolution) lists the envelope fields but leaves their
exact shape open. Issue #3 implements the envelope, so the shape has to be fixed now. The
authoritative form is `lantern-core/src/main/resources/schema/fact-envelope.schema.json`; this
record explains the choices.

## Decisions

- **Entity IDs** are `<type>:<key>`. The type is a vocabulary type name (`[a-z][a-z0-9-]*`); the
  key is everything after the first colon, so keys may contain colons
  (`topic:kafka:order-created`). Keys must not be empty or contain whitespace or control
  characters.
- **Object** is `{"kind", "value"}` with kind `entity`, `string`, `integer` (64-bit), `decimal`
  (plain text without trailing zeros, to stay exact) or `boolean`. Values are held in one canonical
  text form, so equal objects compare and hash equal.
- **Origin** is `{"kind", "name", "version"?}`. `name` is the extractor, model, user or rule;
  `version` is the extractor, prompt or rule version and is absent when there is none.
- **Evidence** is one location: `repo` and full `commit` are required; `path`, inclusive 1-based
  `lines` and the file's SHA-256 `contentHash` are optional, and lines and hash need a path.
- **Validity** is `{"from", "to"?}` in snapshot numbers, from inclusive and to exclusive, with no
  `to` while the fact still holds. Snapshots (#5) must therefore have increasing numeric IDs. It is
  stored as a PostgreSQL `int8range` with a GiST index.
- **Fact ID** is the SHA-256 (lowercase hex) of the canonical JSON of subject, predicate, object,
  attributes, origin and evidence: keys sorted, no whitespace, absent optional fields left out,
  numbers in plain form without trailing zeros. Confidence and validity are excluded, so a claim
  keeps its ID while its validity range grows or its confidence is re-assessed. Because evidence
  includes the commit, the same claim seen at a new commit gets a new ID unless the extractor
  cache reuses the earlier fact.
- **Attributes** are held in canonical form (keys sorted, numbers re-parsed from canonical
  text), so they read back from JSON and the database unchanged. A fact's ID must match its
  content; a mismatch is rejected.
- **Storage** is one `facts` table, one row per version of a fact (ADR 0017). Saving a fact that is already
  stored changes nothing, and so does saving one found again in a later snapshot while the stored one still
  holds (ADR 0016); validity and confidence change only by recording a snapshot (ADR 0017). Origin and evidence are `NOT NULL` with checks on their required keys, so
  the database also refuses facts without provenance.
- **Java types** are hand-written records in `io.lantern.core.fact`, kept in line with the schema
  by `FactSchemaTest`, rather than generated. Generation would need a code generator the design
  does not name, and hand-written records can enforce invariants (provenance, ID matches content).
  Decided by the user; `docs/design.md` says the same.
