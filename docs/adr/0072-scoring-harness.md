# 0072: Scoring harness: a report against a reference, and the models of a run

Status: accepted (#252), amended (#254) and by ADR 0081 (#169: conversations in `run.json`), pending the user's review (#24)

## Context

ADR 0070 made Lantern measured against hand-made references of project-1 to project-4 (#251), and
the design's Quality bar names the scoring: recall and precision of elements, connections, flows,
risks and decisions, the quality-bar checks, the missing and extra items by name, and the model of
every role and the run's cost. The user also asked that every role's model be chosen per run
without code changes, and that two scorecards from different models can be compared. The first
measurement, the assessment of snapshot-4's project-4 report, was counted by hand.

## Decisions

- **Where it lives.** The scorer is `lantern-web/scripts/score.mjs`, a Node script, beside
  `report-spec-check.mjs`: both read data for the C4 report spec, the references' tools are Node,
  scoring is pure JSON with no LLM and no database, and its tests run with the module's Vitest
  tests in `./mvnw verify`. A Java command would start the app, and with it the database, to
  compare two JSON files. What only the app knows, the models and the cost, the app writes down
  (below).
- **What is compared.** The report's data (the `arc-data` element of an HTML report, or a JSON
  file) with `testsets/<set>/reference/reference.c4.json`:
  - Elements by kind (container, database, queue, external, person), matched by name lower-cased
    with every run of other characters than letters and digits as one dash, by the reference's
    id, or by the set's alias table. Only the first report element that matches a reference
    element counts; each later one is extra, listed as a duplicate of the first. Components are counted per container, and matched by name
    only, since a hand-made and a generated component rarely share a name.
  - Connections: the lines of the diagrams on `containers` pages, once per ordered pair of
    elements, so direction matters. Each is grouped by protocol from its ends' kinds and its
    technology: person, email (SMTP), messaging (a queue end, AMQP, Kafka, JMS), data store (a
    database end, JDBC, Redis), HTTP (HTTP, Feign, REST, WebSocket, STOMP) or other.
  - Flows: each sequence by its trigger, the request it starts with (a step note "receives METHOD
    /path", else the code of its first message that has one), written one way: the method in
    upper case, the path in lower case with each `{placeholder}` as `{}`. Matched flows are
    compared by participants; one missing a reference participant is partial.
  - Risks (the rows of a risks or findings table, by its Finding column) and decisions (the items
    of decisions sections) by subject: their identifiers in backticks and words of four letters or
    more, not stop words; two items match when at least half of the smaller one's words are in the
    other, best pairs first, one to one. Glossary terms by name.
  - The quality-bar checks the data alone can show: at most 15 elements a diagram, a description
    for every element shown, a technology for every container and component, an intent phrase on
    every line, a protocol on every line between containers, no diagram repeated, commits (a
    hash in the document's subtitle or revisions) and date pinned, sequences, decisions, risks and a glossary present. Checks marked *(review)* are the
    judge's (#277). The report's own quality gaps are counted, and the reference's own failures
    listed.
- **The alias table** is `testsets/<set>/reference/aliases.json`, optional: `elements` maps a
  report's name for an element to the reference's id, `triggers` maps a trigger to the reference's
  spelling of it; it rewrites only the report's triggers, so lists show the reference's. An
  alias to an id the reference lacks is an error. Aliases are for names that
  mean the same thing, never to make a wrong item count.
- **Precision is over the whole report**, so a wrong item counts wherever it is, and every extra
  item is listed. For a report that also holds another system (as before #253), the precision of
  the part connected to the reference (the elements a diagram line links to one that matches it,
  and the components inside them) is a secondary figure, and the unconnected elements are listed;
  the reproduction of the snapshot-4 assessment, which counted the CRM part only, uses it.
- **The run record.** `analyze` writes `run.json` next to its documents: each role with the
  provider and model it was configured with, its calls, tokens and cost, the run's totals, and the
  calls by the model that served them, from the stored call log since the run started. The
  scorecard keeps it as it is.
- **Choosing models per run.** Roles and tiers already take their model from properties (ADR
  0009). `scripts/launch.sh --set <property>=<value>` passes any app property, so
  `--set lantern.llm.roles.summarize=<name>` serves one role with another model of the settings
  file. `scripts/score-testsets.sh` runs the development sets with a named settings file and
  `--role`/`--tier` overrides, scores each and, with `--compare-with`, writes a side-by-side
  comparison with an earlier run. With `--fake-llm` it runs every role on a free fake model: the
  Spring profile `fake-llm` adds a fake provider bean, which ADR 0009 already lets models name, so
  a whole run of the sets costs nothing. Every text the fake words is wrapped in `{{ }}` with a hint of what a model would write
  (`{{placeholder: summary of class Order from its parts}}`), so a reader tells it from Lantern's own text. It refuses the held-out sets unless `--held-out` is given
  (design, Test sets); `TestSetScoreIT` likewise needs `lantern.test.held-out`. Recordings were already keyed by provider and model (ADR 0008),
  so replaying with one role switched makes new calls only for that role; a test shows it.
- **Output.** `score.mjs` writes `<prefix>.scorecard.json` and `.md` (default: the report's path
  without `.html`); the script writes `workspace/scores/<label>/`.

## Not in this issue

Replaying recordings in `analyze` itself, so a real-model run can be repeated for free, is #202's.
The judge (#277). Repositories without a Maven build were left out of the script's runs until #254
(see the amendment below).

## Recounts against the assessment

Scoring snapshot-4's report reproduces the assessment of ADR 0070 except where it counted
differently: the reference now has 16 risks, not 13, since its second pass added three; and HTTP
connections are 6 of 10, not 6 of 8, because frontend to gateway and gateway to Eureka count as
HTTP here.

## Amendment (#254): every repository of a set is analyzed

Since #254 a repository without a Maven build is analyzed without a build (ADR 0016, amendment for
#254), so `score-testsets.sh` and `TestSetScoreIT` analyze every repository a set's pins name,
project-3's and project-4's frontends and deployment repositories included.
