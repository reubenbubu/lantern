# 0089: Extraction coverage: which reference items the extracted facts back

Status: accepted (#380)

## Context

The scorecards of ADR 0072 compare Lantern's report with a set's reference. They cannot say
whether a missing item was never extracted or was extracted and lost in linking. The milestone
"Extractor revisit" reviews every extractor (design principle 9: extract everything relevant), and
it needs a measure of extraction alone, taken before and after. The research is in
`docs/research/extraction-coverage.md`.

## Decisions

- **The facts of a run.** `analyze` writes the facts the extractors found, before linking, as
  `extracted.jsonl.gz` next to its documents and `run.json`. The file is gzipped JSON lines, one
  fact a line in the core envelope's JSON. It holds the facts of the repositories the run names,
  as stored. Nothing reads it back into Lantern. It is a record of the run, like `run.json`.
- **The coverage map.** Each set has a `testsets/<set>/coverage.json`, beside its pins:
  - **What it maps.** Every item of the set's reference: the elements the scorecard matches
    (containers, data stores, queues, external systems, people), the components, and the lines of
    the `containers` pages (`<from> -> <to>`).
  - **An item's needs.** Each item lists its `needs`. A need is an extractor area (the extractor's
    name in the facts' origin: `maven`, `spring`, `config`, …) and a citation,
    `<repo>:<path>[:<start>[-<end>]]`, of the code where that area should find it.
  - **Linking only.** An item may instead be marked `linking` with a reason, when no code location
    states it: a person's line to the system, or an item only prose describes.
  - **A set without a reference.** project-0 has no reference, so it lists its items in its map,
    each with a `kind` and a `name`.
  - **Where needs come from.** The needs follow the extractor table of the design, and the
    citations follow the reference's own `facts/*.md`, narrowed to the declaring lines.
- **What a citation points at.** A need cites the code where its area records the thing, so a
  miss means the thing is missing, not that the fact cites another line of the same declaration:
  - a deployment need cites its compose service from its first line, because a container's
    environment variables are attributes of the fact on that line
  - a declared exchange cites the bindings that name it
  - a JWT key cites where the key is built
  - a mail server or a JWT secret's property is a `config` need, and the `mail` need cites the
    sending code
  - a class whose calls or Redis uses back an item cites the whole file

  The first baseline fixed 66 citations of the subagent-written maps this way. Every miss left is
  a thing no extractor finds.
- **The match.** A need is met when a fact of its area has evidence in the cited file whose lines
  overlap the cited ones. A fact or citation without lines covers its whole file.
  - **An unmet need** lists the areas of the facts on its lines. Another area there means a rule
    is missing. No fact there means an extractor, or a file type, is missing.
  - **An item’s status.** An item is backed when all its needs are met, partly backed when some
    are, and missing when none are.
- **The report.** `lantern-web/scripts/coverage.mjs <extracted.jsonl.gz> testsets/<set>` writes
  `<set>.coverage.json` and `.md`:
  - backed, partly backed, missing and linking only per category
  - needs met per area
  - every item not backed, with what is missing
  - the facts per extractor and predicate

  `scripts/score-testsets.sh` runs it after scoring each set. `Project0EndToEndIT` runs it on
  project-0 into `lantern-app/target/project-0/`.
- **Checks without network.** `coverage.mjs check testsets/<set> [<checkout>]` checks a map
  against the set's items, and with the checkout, that every cited file and line exists.
  `lantern-web`'s tests check every map against its reference and the set's pins.
- **A diagnostic, not a target.** The scorecards stay the measure of the output. Coverage says
  where a loss is: a backed item missing from the report is a linking loss, and a missing item is
  an extraction loss.

## Consequences

- A change to a reference's items fails `lantern-web`'s tests until the map follows.
- The milestone's baseline (#400) and test drop (#399) compare coverage reports before and after.
  Each extractor's review (#381 to #398) reads its area's unmet needs.
- The maps were written once from the references' citations and spot-checked. A wrong citation
  shows as a missing need and is fixed in the map, with the reference's `facts/*.md` as the
  authority.
