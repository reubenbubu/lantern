# 0091: Claims and observations

Status: accepted (#281); supersedes the fact ID rule of ADR 0001, amends ADR 0017 (validity per
observation) and ADR 0090 (where lineage goes)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 6, Claims and observations)
makes a fact's identity what it claims, not where or when it was seen. Today's fact ID hashes the
evidence, commit included, so every new commit gives every fact of its repository a new ID, and
nothing that keys on a fact ID (link facts that list their source facts, LLM answers, caches)
survives a commit. #518 gave every type its key attributes and the claim ID (ADR 0090). This record
says how the store keeps claims and their sightings, how the envelope changes, and how stored
facts are migrated. The epic sketches the tables; the choices below are those it leaves open, the
ways the tables differ from the sketch, and one rule the owner changed.

## Decisions

- **Two tables.** `claims` holds one row per claim: its ID (ADR 0090), subject, predicate, object
  and key attributes. `observations` holds the sightings: one row per version of a sighting, with
  its claim, origin, descriptive attributes, evidence, lineage, confidence and validity. Migration
  V19 creates them and replaces `facts` (V18 is kept for the Investigate branch, as the epic says).
- **Where the tables differ from the epic's sketch.** An observation's `id` is its content hash
  (below), not a `bigserial`, because one observation is stored as several version rows, each with
  one confidence, and documents cite it; the rows of one ID never overlap. A claim keeps its
  object as `object_kind` and `object` text, as `facts` did, so its indexes and queries are those
  of before. `observations.repo`, generated from the first location, indexes the repository a fact
  belongs to.
- **A `Fact` is one observation joined with its claim**, the read view every stage keeps using:
  its attributes are the claim's key attributes and the observation's descriptive ones together,
  and it gains `claimId` (set on every fact read from the store, absent on one not yet stored) and
  `lineage`. The vocabulary says which attributes are key; the store asks its validator (in the
  app, the vocabulary registry), so a fact needs no vocabulary to be built.
- **The observation ID** (`Fact.id()`) is the SHA-256 of the canonical JSON of the subject,
  predicate, object, all attributes, lineage, origin and evidence; confidence and validity are left
  out, as before (ADR 0001). Evidence no longer holds the commit, so a sighting that is unchanged
  in a later commit keeps its ID and extends its open range, and a document of either commit cites
  the same IDs. A changed line range, descriptive attribute or file gives a new observation of the
  same claim. Stages keep citing observation IDs for now; they move to claim IDs as they are
  rewritten (1B, 1C, 1G).
- **Several sightings of a claim may hold at once** (decided with the owner). The epic said the
  observations of one claim and origin never overlap. But a runtime link found at several call
  sites is one claim (ADR 0090) whose sightings differ in call site, route and path, which the
  documents show, so merging them loses what the documents say. One origin may therefore hold
  several observations of a claim at once when they differ; the versions of one observation never
  overlap (the constraint `observations_versions_do_not_overlap`, as V8 had for facts). An identical
  sighting seen again still extends its open range rather than starting a row. 1B gives each call
  site an entity of its own, which removes most such cases.
- **Evidence is a list of locations**, each with a repository, a path, the file's git **blob**
  SHA, a line range and a role (`declared`, `value`, `config`, `cited`). The first location is the
  one the fact is declared in: the repository a fact belongs to is that location's, and
  `Evidence.repo()`, `path()`, `lines()` and `blob()` are its. Extractors give one location for now;
  1B adds the others. The commit of a sighting comes from the snapshot's `repo@commit` pairs.
- **The store fills the blob.** Extractors keep giving a file's SHA-256 (`contentHash`); recording
  a snapshot looks each path up in git at the snapshot's commit of its repository (`git ls-tree`,
  once per repository and commit) and replaces the content hash with the blob. A file git does not
  hold at that commit (a build output, say) keeps its content hash and has no blob. Readers that
  show a file's text read it by its blob (`git cat-file`), so they need no commit.
- **Lineage** is the list of claim IDs an observation was derived from. The linker steps that name
  their source facts (cross-repo links, deployable units, claim checks) set it from those facts'
  claim IDs. The attributes that name source facts by observation ID (`facts`, `cites`, `fact`,
  `claim`, `contradictedBy`) stay for now, as their readers still follow them; they go when those
  readers move to lineage (1C). This amends ADR 0090, which said #281 would move them.
- **Migrating stored facts** (V19, a Java migration): each fact's claim ID is computed from the
  key attributes of the types as the app's sources define them now (a type no source defines any
  more keys on all its attributes); its blob comes from git at its commit and path, through the
  repository's registered folder, and a fact whose file cannot be read keeps its content hash and
  has no blob; lineage is the claim IDs of the facts its lineage attributes name. Facts that become
  the same observation (the same sighting at two commits) are joined into one row per run of
  adjacent or overlapping snapshots with one confidence. The facts are read a batch at a time, by
  repository and commit, so one git tree is held at a time. The attributes that name source facts
  keep the old IDs, which no observation has any more, until the next analysis links the snapshot
  again; the lineage already names the claims. The tables of summaries, wordings and
  link resolutions keep the fact IDs they cite: they never relied on them surviving a commit
  (their cache keys leave fact IDs out, ADR 0055), and their own stores are replaced in 1G.

## Consequences

- An unchanged repository analyzed at a new commit gives the same claims and the same observation
  IDs, and records nothing new (tests: `ClaimsAndObservationsIT` and the store's tests).
- Documents' facts carry `claim` and `lineage`, and their evidence is a list of locations with
  blobs instead of a commit; the Architecture Explorer shows each location.
- The extracted-facts file `analyze` writes (`extracted.jsonl.gz`) has the new envelope, and
  extraction coverage reads every location.
- The fake-model scorecards on project-1 to project-4 and project-8 are unchanged (#281's PR).
