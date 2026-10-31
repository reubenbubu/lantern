# 0090: Key attributes, claim IDs, runtime and build parents, generated records

Status: accepted (#518); amends ADR 0002 and ADR 0042

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 6) makes a fact's identity
what it claims, not where or when it was seen, so later stages, LLM answers and caches key on IDs
that survive commits. Today's fact ID hashes the evidence and the origin version, so every commit
re-identifies its repository's facts (epic, finding 7). Before the store switches to claims and
observations (#281), the vocabulary must say which attributes identify a claim. The epic also fixes
four vocabulary flaws and asks for typed records instead of attribute JSON read by string key. Its
key-attribute rules are fixed by the epic; this record says how they are built and the two rows
#518 changed with the owner.

## Decisions

- **Key attributes.** A relationship or attribute type lists its `keyAttributes` in its definition
  (`schema/vocabulary-type.schema.json`), on top of those its parent lists, so a type's key
  attributes are its own and all its ancestors'. Each must be declared by the type or an ancestor.
  Entity types have none, since they are never predicates. The other attributes are descriptive.
- **The claim ID.** `ClaimId.of`, or `Vocabulary.claimId(fact)`: the SHA-256, as 64 lowercase hex
  characters, of the canonical JSON (the fact ID's canonical form) of the subject, the predicate, the
  object and the key attributes the fact has. An absent key attribute and a `null` one are alike,
  so a type that gains a key attribute keeps the IDs of facts without it. Evidence, origin,
  confidence, validity and descriptive attributes are not part of it. Nothing stores it yet; #281
  makes it the `claims` table's ID.
- **Which attributes are key.** The epic's rules (section 6, Key attributes): every attribute is
  key except the global descriptive list, `profile` in extractor types other than `config-value`,
  and the per-type table. A test of the app (`ClaimIdentityTest`) holds the rules and checks every
  type against them, so a new attribute fails until it is classed. Three choices made on #518:
  - **Link claims also key on `protocol`** (decided with the owner). C4 draws a line per protocol,
    so a frontend's HTTP calls and its WebSocket to the same gateway stay two claims. Call sites,
    routes and paths stay descriptive: they are sightings of one link.
  - **`config-value` keys on `application`** (decided with the owner). A config server's
    repository holds a file per application; with `application` descriptive, three applications
    setting one key to the same (masked) value were one claim, 12 facts on project-3.
  - **`profile` stands for the view in link-stage types** until 1C introduces `view`. The epic
    makes the view the key of link and derived types; today's link types record it as `profile`,
    so it is key there (all types the linker defines), and descriptive in extractor types.
- **Changing key attributes** is an ordinary new revision, like a new parent (ADR 0002). It changes
  the identity of that type's claims, so it is done only through an issue.
- **Runtime and build parents.** A new built-in root `INTERACTS_WITH` (shape `arrow`) holds every
  runtime relationship: `CALLS` (and so `CALLS_HTTP`, `SENDS_MAIL`, `USES_IDENTITY_PROVIDER`),
  `PUBLISHES`, `CONSUMES`, `ROUTES_TO`, `SENDS_MESSAGES_TO`, `USES_DATABASE`, `ACCESSES_DATA_OF`,
  `USES_BROKER` and `USES_INFRASTRUCTURE`. `DEPENDS_ON` (shape `dashed-arrow`) now means what the
  build needs and keeps only `BUILD_DEPENDS_ON` and `PACKAGES`. A renderer falling back to a parent's
  shape can then never draw a build edge as a runtime line. Nothing in Lantern read these parents
  before, so no document changes.
- **Actors and containers.** A new built-in root `actor` (C4 level `context`, shape `person`) is the
  parent of `person`, which was under `system`. `message-broker` and `infrastructure` get the parent
  `container`, so they are containers by level (amending ADR 0042, which gave them none); the
  composer still draws a broker's topics, queues and exchanges and keeps infrastructure apart, as
  before.
- **`repo` is a built-in type**, with the definition the Redis extractor gave it, which no longer
  defines it. An existing database keeps the Redis extractor as its owner (ADR 0002, amendment for
  #156), since the definition is unchanged and so no revision records the move; a later change to
  it needs an ownership transfer then.
- **Generated records.** `AttributeRecords` generates a Java record for each relationship and
  attribute type with attributes, named after the type (`http-call` gives `HttpCallAttributes`),
  with a component per declared or inherited attribute (a Java keyword gets `Value` after it),
  `TYPE`, `KEY_ATTRIBUTES`, `from(attributes)` and `toJson()`; `AttributeValues` reads and writes
  the values. Undeclared attributes of open types are not part of a record. The records are
  committed in each defining module's `attributes` package (`io.lantern.extract.attributes`,
  `io.lantern.link.attributes`), left out of the formatting check, and a test of the module fails
  when they differ from what the generator gives; run it with `-Dlantern.records.write=true` to
  rewrite them. Committing them, rather than generating them in the build, needs no new Maven
  plugin. Consumers move to them in 1B, 1C and 1G.
- **One list of sources.** `ExtractorVocabularies` and `LinkVocabularies` list each source's types
  in the order they are applied; the app's startup, the record tests and the claim tests use them.
- **Identity on real facts.** `ClaimIdentityCheck` checks the facts of a snapshot: a fact seen again
  (another commit, lines and file, origin version and confidence, descriptive attributes dropped)
  is the same claim; and facts of one origin that are one claim differ in nothing but evidence,
  unless they are a runtime link seen from several call sites. project-0's end-to-end test runs it
  in every full check, and `ClaimIdentityIT` runs it on any development set.

## Consequences

- On project-8, 13,154 facts give 13,137 claims; the 17 merged are 16 runtime links seen from
  several call sites and one test container seen twice. project-1 to project-4 merge only links
  and exact duplicates.
- The merged links carry different descriptive attributes per sighting (call site, route, path),
  so #281 must keep each sighting's descriptive attributes rather than one set per claim and
  origin.
- The fake-model scorecards on project-1 to project-4 and project-8 are unchanged (#518's PR).
