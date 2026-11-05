# 0072: Scoring harness: a report against a reference, and the models of a run

Status: accepted (#252), amended (#254) and by ADR 0081 (#169: conversations in `run.json`), amended for #369

## Context

ADR 0070 made Lantern measured against hand-made references of project-1 to project-4 (#251), and
the design (Checking the quality bar) names the scoring: recall and precision of elements, connections, flows,
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
  alias to an id the reference lacks is an error. `eitherKind` maps a reference id to the other
  kinds an element may also be matched in (project-1's Keycloak: container or external, as its
  `verification.md` accepts); a matched element counts in the reference element's kind, so it is
  neither missing nor extra, and its lines match. Aliases are for names that
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

## Amendment (#369): the scorer's "at most 15 elements a diagram" check is the readability check: no fixed element cap. The quality bar (`docs/quality-bar.md`, Diagrams stay readable) sets no element cap; a view is split at the same level, or detail folded away, when a readability signal passes its configurable threshold (`docs/design.md`, Checking the quality bar, Readable diagrams). 15 elements was that default until #699 replaced it (ADR 0117) and #366 added the after-layout measures (ADR 0123); read "above 15" and "at most 15 elements" in this ADR as the readability thresholds of `docs/design.md`, Readable diagrams.

## Amendment (#667): a row per LLM role, against an answer key

The scorecard gains a row per LLM role (knowledge-build epic, section 13, as #662 changed it; the
research in `docs/research/role-measurement.md`), computed by `lantern-web/scripts/roles.mjs`:

- **Inputs.** Besides the report and `run.json`, `analyze` writes `linked.jsonl.gz` (the linker's
  facts, Resolve's picks among them) and `llm.jsonl.gz` (every fact of origin llm that holds in the
  snapshot); `run.json`'s harness records list each role's asked subjects and, new, its unasked
  ones (not asked for budget, a model without tool use, or failed, which are no longer among the
  asked) and its cached ones. `score-testsets.sh` copies both
  files beside the scorecard. A set's answer key is `testsets/<set>/answer-key.json`
  (`--answer-key` names another).
- **The answer key** is prepared by the agent from the code at the pins and the references, never
  from a model's answers, and says so in its header with its status: the blind check by a
  read-only Opus subagent and the owner's settlement of each disagreement (CLAUDE.md, owner's
  decision of 3 Nov 2026). It holds, per open link of the set (from a fake run's static facts),
  its right target, several when every one of them receives the message, or "unknown"; per
  reference risk, its subject elements (reference IDs) and category (one of the lead kinds,
  `version-skew` or `other`); and decoys, code that looks like a risk of a category and is safe,
  with why. Each risk and decoy has a stable ID (`r1`, `d1`, ...), which its item score is named
  by, so a key that gains an entry still pairs the others. Every entry cites `repo:path:start-end` at the key's commit of its repository, and
  names an identifier the lines hold; `score.mjs check-key` checks each against git, and a test
  checks every key's form.
- **Three outcomes.** Resolve's items are correct, wrong or abstained, with p = 3 (its instruction
  asks for certainty): coverage (picks over items), accuracy on the picks, the penalized score
  (correct − p × wrong over the items), over-abstention on the links the key answers; an item not
  asked, or never an item, is counted apart. Abstaining on a link whose key says unknown is
  correct, though not a pick; a pick there is wrong.
- **Describe**: the share of the report's containers, stores, queues, external systems, people and
  components (by their anchor type's ID) with a responsibility of origin llm, and of the lines the
  report draws, a person's left out (they keep their fixed labels; measured since the amendment for
  #739), whose label is an intent it
  wrote, citation markers aside: what a reader sees worded. Its judge score comes with #668. (#742 replaced the line count; see its amendment.)
- **Flows**: a report flow matches a reference flow when its trigger is the same entry point and
  their participants overlap by at least two thirds of the larger set, one to one, best overlap
  first; recall and precision of the flows the report draws. The scorecard's older flow count
  (by trigger alone) stays beside it, so earlier scorecards compare.
- **Investigate**: each risk or finding wording it stored matches a reference risk, or a decoy, of
  the answer key when a subject is shared and the category is the same: its lead's kind, or for a
  wording the key's category of the finding's kind (`version-skew` → `shared-type-skew`,
  `shared-jwt-secret` → `plaintext-secret`, `unrouted-endpoint` → `contract-mismatch`,
  `end-of-life` and `dependency-drift` → `version-skew`, the others → `other`); a risk without a
  lead matches by subject and the words of its title and finding. One that matches several entries
  is the one whose words it shares most, a reference risk first on a tie. A risk matching nothing
  is unjudged, listed and counted. Recall, precision over the judged entries (each once), and
  TPR − FPR with the decoys (not measured for a key without decoys). In the paired comparison a
  risk found scores +1 and a decoy flagged −1, pooled over the items, which weights them by their
  counts rather than as TPR − FPR does.
- **Name groups**: the share of groups it was given that it named; the judge comes with #668.
- **Harness**: dollars, calls and tokens of the run, the cache hit rate (planned items the answer
  cache answered), and with `--warm` and `--change` the warm rerun's calls (gate: zero) and the
  one-file change's.
- **Purpose**: not measured until the key-fact judge (#668).
- **Paired comparison.** Each scored row keeps its items' scores. `score.mjs compare` adds the
  paired difference of two scorecards; `score.mjs paired <a> <b>` pools every set two score
  folders share: the mean per-item difference in points, a 95% bootstrap interval (2,000
  resamples, seeded, so the same every time), the direction per set, and the epic's verdict (an
  improvement when the interval excludes 0 and all sets but at most one agree; a possible
  regression when its lower bound is at or below −7 points).

## Amendment for #668

The judged rows (Describe's wording, Name groups' names, Purpose's key fact) read the wording
judge's verdicts, `wording.jsonl.gz` beside the run's documents, and the owner's labels in
`testsets/wording-labels/`; they say "not validated" until the labels validate the judge (ADR 0111).

## Amendment (#742): Describe's intent coverage in the model and on the page

The Describe row above counted drawn lines whose label equalled any intent text of any role, so a
line showing another link's intent counted, and one carrying a profile note did not. It now gives
three numbers (`lantern-web/scripts/roles.mjs`, `describeRow`):

- **Elements**, as before: the share of the report's elements with a responsibility it wrote.
- **Model coverage** (corrected when #742 was reopened). The first version rebuilt only the
  interactions Describe asks about (`Describer.LINKS`), so it read 100% on every 1F run while the
  views drew lines no intent words. It now counts every interaction of the stored model that a view
  can draw, whether Describe asks about it or not (`interactionsOf`):
  - every link whose kind is a relationship type below `INTERACTS_WITH` in the vocabulary, read
    from `lantern-*/src/main/resources/vocabulary/*.json` (`lantern-web/scripts/vocabulary.mjs`),
    never from a list of the scorer's own, so a kind the vocabulary gains is counted with no change
    here; Investigate's found connections (`INVESTIGATED_CONNECTION`) are among them;
  - only when both ends are drawn in the report: a box of the entity, or of the exchange that routes
    to a queue the diagrams fold away; a route to such a folded queue is no line of its own;
  - not a profile's alternative to a default link (the rule of `Screens.alternatives`);
  - the lines between two components of the same unit other than to code at the end of a call, a
    component's unit by `PART_OF`;
  - each component's data access (`data-access`), once per database its unit uses;
  - each person's use of the frontends it names (`person-basis`), matched to the drawn frontends by
    name in any case.

  Messages sent to another unit (`SENDS_MESSAGES_TO`) count though the views draw them through the
  queue lines, since both ends are drawn and the model holds no intent for them.

  Three items are named here rather than read from the vocabulary, because they are not
  relationship types below `INTERACTS_WITH`: `COMPONENT_LINK` (a structural type with no parent)
  and the attributes `data-access` and `person-basis`. A component's data access counts only toward
  a database its unit's drawn link reaches, since without that box no view draws the line. A person
  who names no frontend the report draws has no owner and is not asked; on the 1F runs that is
  every person of project-8 and both of project-1, whose model names no frontend for them.

  Each is scored with three outcomes, a wrong answer costing one point (p = 1):
  - right when one of its intents opens with something other than a generic verb;
  - wrong when its intent opens with one, such as "Uses" or "Calls";
  - "don't know" when it has none though Describe asked about a unit that holds it.

  An interaction none of whose owners Describe asked about (budget, a failure, or an element no unit
  owns; `run.json`) is not asked. Coverage is the share with an intent of their own among all
  interactions, not asked included, and the scorecard lists the counts per kind, most uncovered
  first. Each item is its own item in the paired comparison.

  Re-scored on the 1F recordings, coverage fell from 100% on each of the five sets to 84%
  (project-1), 86% (project-2), 54% (project-3), 91% (project-4) and 79% (project-8). The gaps are
  data access, found connections, people's uses, messages to other units, infrastructure use
  (project-3) and an exchange's route (project-8). Whether an intent says something true and
  specific stays the wording judge's row (#668, #757).
- **Drawn coverage.** Of the lines the report draws, the share labelled with the intent Describe
  wrote for a link of their own; a person's line, since #739, with the intent it wrote for the
  person's use of a frontend (amendment for #739 below).
  - A line's own link runs between its two ends, or between elements inside or around them, since
    a view draws a link between the boxes it shows.
  - A consumer's line runs from any queue, exchange or topic to the consumer, since a view draws
    the exchange a queue is bound to.
  - The label is read without citation markers or the note " (only with the Spring profile x)".
  - The rest are split into generic labels and others. The others are mostly the composer's labels
    for lines no intent words, such as "Reads and writes" to a database or "Routes <key>" between
    an exchange and a queue.

Only Describe's intents count, not those Name groups writes for lines leaving a group. The scorer's
line-label check counts a label as generic when it is empty or opens with a generic verb, so the
fallback "Calls <methods>" fails it as "Uses" did. A person's lines are still checked: their fixed
"Uses" and "Uses, through <frontend>" fail it as the bare "Uses" did before, which is what the bar
says of them until people's lines are worded. Both coverages keep their items' scores for the
paired comparison.

## Amendment (#746): the model's choice of flows, apart from their participants

The Flows row matches a report flow only when it shares two thirds of the reference flow's
participants. That measures derivation's trace, and it reads 0 on sets whose traces differ from the
reference whatever flows the model chose. A **Flow selection** row now scores the choice alone, by
trigger (`lantern-web/scripts/roles.mjs`, `flowSelectionRow`); the Flows row stays as derivation's
measure.

- **Candidates and choice.** The candidates are every flow a significance judge saw, in any scope
  (`flow-significance` facts in `llm.jsonl.gz`), or every traced flow (`flow-shape`) when none
  did, one per shape (`SAME_SHAPE_AS`, as `ScopeFlows.candidates` keeps them). The chosen ones:
  - mode "judged": those the whole system's judge marked significant. A flow a unit's pass, or the
    system's first pass on a large system, dropped is a candidate not chosen, because those passes
    are the model's choice too and the system's first pass is not stored.
  - mode "rank fallback": the system has no verdict, so its choice fell back to the derived rank.
    The chosen ones are those the report draws, by trigger, so the row then measures the rank.

  The row scores the verdict, not the page: two significant flows of one drawn shape are one flow
  on the page.
- **A candidate's triggers.** These are read from `linked.jsonl.gz`. A flow's ID ends with the claim
  of its entry point's fact, and an endpoint is started by:
  - its own request;
  - each gateway path routed to it (`routed-endpoint`);
  - each frontend call that reaches it, by its path or, without one, its URL;
  - the requests of the flows of its shape.

  The report names one of these as where a flow starts; any of them matches, so a flow the report
  starts at the gateway still matches a reference flow named by the service's own request. They are spelled as the scorecard spells triggers,
  with the set's trigger aliases.
- **Scores.** Each reference flow that a candidate starts is an item: right when a candidate of its
  trigger was chosen, wrong when none was (p = 1). Recall is the share chosen.
  - Precision is the share of chosen candidates whose trigger is a reference flow's. A wrong pick,
    a chosen flow outside the reference, lowers precision and costs no point, since a reference
    need not list every significant flow; a candidate matching no reference flow is otherwise
    unjudged.
  - Reference flows no candidate starts are listed apart, such as one started by a listener, a
    WebSocket or a client outside the code.

## Amendment (#755): both harness gates in every drop

The harness row's two gates (knowledge-build epic, section 13) are now measured on every run that
asks for them, and the one-file change is checked against the items it may re-ask.

- **What the run records.** For each item the harness asks rather than answer from its cache, it
  compares the item's fingerprint inputs with those of the answer, or drop, it stored last for the
  same role and subject on the same prompt version and model, or failing that on any
  (`io.lantern.harness.AskedWhy`), before this run's answer takes that place. Answers of other
  models share the cache, so comparing with the newest of any would blame the model.
  `run.json` lists, per role, `askedWhy` for each asked subject:
  - `new` when nothing was stored for it before;
  - `unheld` when the answer stored under its fingerprint was refused because a fact it cites no
    longer holds;
  - otherwise the files (`repo:path`) whose claims or text changed, including the files a
    tool-using item read;
  - how many earlier answers it shows changed (`answers`), other changed inputs (`other`);
  - whether its prompt or its model changed.
- **The gate** (`roles.mjs`, `changeGate`). Given the changed file (`--change-file repo:path`), an
  asked item is expected when everything that changed comes from that file or is an earlier answer
  it shows. An item retried alone after its packet left it out, "X (again)", is judged by X's
  reason. A judge's item (support judge, wording judge) asked for a new or changed text is expected,
  through a reworded answer, when the change re-asked some writing role's item for the file, since
  the text it judges was reworded or newly sampled. Any other reason makes an item unexpected and
  fails the gate, listed by item:
  - a new item;
  - a cited fact that no longer holds;
  - another file;
  - a prompt or model change;
  - another input;
  - no recorded reason.

  The row reads, for example, "one-file change 3 calls, 3 items, 3 expected (gate passed)", with
  how many were re-asked only through a reworded answer.
- **The runs.** `scripts/score-testsets.sh --gates` follows each set's analysis with:
  - a warm rerun of the same repositories (`warm.run.json`, gate: no call);
  - a rerun with a one-line comment committed to one Java file in a temporary clone of its
    repository (`change.run.json`, `change-file.txt`).

  The file is `--change-file` where the set has that repository, else the first controller of the
  set's repositories, which its type's summary and its unit's description rest on, else the first
  Java file; `--change-file` must name a Java file git tracks. Scoring picks up these files from
  the set's score folder, so `--score-only` under the same label scores them again. The gates'
  runs' own documents are removed once their records are copied, so the set's newest documents
  stay the main run's, and an analysis without `--gates` removes earlier gate files under its
  label. The change run registers the temporary clone's path for its repository; the next analysis
  that names the repository registers its own path again.
- **project-0** checks the same record: its one-file change test (`Project0EndToEndIT`) asserts that
  every item asked after the change gives only the changed file, or an answer it shows, as its
  reason.

Until #722 keeps a sentence's wording while its evidence is unchanged, a real model rewords
re-asked answers, and the items showing them are expected through a reworded answer. The gate
counts them as expected, as the epic does, and the row shows how many there were.

## Amendment (#741): model coverage credits the intents the page shows

Describe's model coverage (#742) now counts the same interactions the unknowns page lists under
"Intent not known", so that on a run its "don't know" count can be compared with the number of
those entries:

- A component's data access takes its unit's intent for the store (#739): the store link's
  intent counts for it.
- A person's use is counted once per frontend the person uses, and counts as worded when the
  intent stored for that frontend (`<basis>/<frontend>`) exists. Before, the person's basis was
  counted once and could never be found worded, since its intents are stored per frontend.

Second part of #741, so the two counts agree on a run:

- **One owner rule.** The scorer gave every link its fact's subject as owner, but the Unknown
  caller's line has `actor:unknown-caller` as subject and an exchange's route to a queue the
  exchange, neither of them a unit Describe asks about: left unworded, such a line counted as
  "not asked" while the page listed it under "Intent not known". The scorer now takes the page's
  rule (`UnitInteractions.otherLines`, each place saying the two must match): the subject if it is
  a unit or frontend (a `deployable-unit` or `frontend-app` subject); else the line's start, else
  its end, if one is; else the first unit or frontend, by ID, with a line the diagrams draw
  touching either end. The scorer has the facts, not the lines, so it takes the facts the
  diagrams draw and puts a folded queue's end where the page draws it, at the exchanges routing
  to it. It still differs in rare cases: the interaction's own ends are its fact's, not the drawn
  line's (a non-unit's line into a folded queue), it searches the interaction kinds' facts, not
  every line, and a merged line's subject is each fact's, not the first one's. Such an
  interaction may count as not asked while the page lists it under "Intent not known".
- **Part subjects.** A large unit is asked in parts, `u`, `u#2`, `u#3` (Describe's items); each
  part counts as asking `u`. Which part holds an interaction the record does not say, so an
  interaction left unworded is "don't know" only when every part of its owner was asked; when a
  part was not (refused, or its request failed), it may be that part's, and counts as not asked. A
  part dropped after two rejections was asked, so its interactions are "don't know".

## Amendment (#748): Investigate's precision over every risk it draws

The 1F and 1G review found Investigate's "precision over judged" counted the reference entries a
run hit, not the risks it drew: three drawn risks that all matched one entry counted once, and
most drawn risks (23 of 32 on project-1, 55 of 76 on project-8) matched nothing and were left out.
The fixed checks' findings that Investigate only worded were counted as its own, and the
connections it found were not scored at all.

- **A judgement file per set.** `testsets/<set>/judgements.json` holds the verdicts the answer key
  does not settle: `findings` and `connections`, each entry a `key`, a `verdict` (`right` or
  `wrong`), an optional `reference` (the ID of the reference risk a right finding is, so it counts
  for recall) and a `note`. A finding is named by its lead (`lead:<kind>/…`), which stays the same
  from run to run however the model words it, or, without one, by `<subject> | <title>`; a
  connection by `<subject> -> <object>`. `score-testsets.sh` passes the file when it exists, and
  `score.mjs` takes `--judgements`. The file is filled from the real runs' unjudged lists: each
  verdict by the owner, or by a judge validated against the owner's verdicts, never by the model
  that drew the finding. A verdict decides first, then the answer key's match.
- **Precision over every drawn risk.** Each drawn `investigated-risk` is right (a verdict, or a
  match to a reference risk), wrong (a verdict, or a match to a decoy) or unjudged; precision is
  right over right plus wrong, each drawn risk counted as often as it is drawn, and the row says how
  many are unjudged. Recall stays over the reference risks. A right verdict counts for recall only
  through its `reference`, or when the key also matches the finding to a reference risk; the
  scorecard lists verdicts whose key names nothing the run drew, so a typo shows.
- **The fixed checks apart.** A `finding-wording`, Investigate's words on a fixed check's finding,
  is scored the same way in its own row, "Fixed checks worded", and no longer counts in
  Investigate's. Its hits leave Investigate's item scores too, so a paired comparison with a
  scorecard from before #748 shows a drop on the sets where fixed checks were hit (project-4,
  project-8); re-score the earlier run with this scorer before comparing.
- **Connections by their two ends.** Each `INVESTIGATED_CONNECTION` is right when its two ends
  match two reference elements the reference connects, in either direction (the model reads which
  way a connection goes), or a verdict says so; otherwise unjudged unless a verdict says wrong.
  The row is "Investigate connections"; it counts reference connections per pair of elements,
  whichever way they are drawn.
- **The spread of two runs.** `score.mjs compare` adds a row with each run's recall and precision
  and how many points apart they are; `score.mjs paired` adds a table of them per set.

Re-scoring the 1H baseline's complete run with these rules moved Investigate's precision on
project-1 from 75% to 83%, and on project-4 its recall from 65% to 47% (three of its eleven hits
were fixed checks' wordings, now in their own row). Only the wordings moved to their own row
leave the unjudged counts (project-4 28 to 27, project-8 47 to 46) until the judgement files are
filled.

## Amendment (#740): Describe's sequence steps

The Describe row read only the C4 diagrams' lines, so how many sequence steps show a fallback label
though their link has a stored intent was counted by hand (PR #809) and could not be reproduced.
The row now also measures `steps`:

- every message step of every sequence diagram, a note left out, counted on each diagram that draws
  it, not once by its ends, since the question is about every step a reader sees;
- matched as a line is (amendment for #742): a step's link runs between its two ends or elements
  inside or around them, and the label is read the same way. A step carries no fact citation in
  the report, so ends and label are all there is;
- `intended` when a link Describe worded runs between its ends; `own` when it reads one of those
  links' intents; `fallbackWithIntent` when it is intended but reads something else;
- a step to a person is left out; a step from one is measured as a person's line is (amendment for
  #739).

The scorecard's Describe cell shows "steps N drawn, N intended, N own, N fallback", so `compare`
shows both runs' figures, and `paired` lists them per set. Each intended step is an item of the row,
right when own, in the paired comparison. It is keyed by its diagram, its two ends and how many
steps between them came before (`step:<diagram>/<from>-><to>#<n>`), so a note added or dropped on
a rerun shifts no key. The steps join the lines in Describe's one paired difference. A run scored
before #740 has no step items, so a pair of an older and a newer run compares the lines alone.

## Amendment (#739): people's lines and steps are measured

Since #806 a line from a person shows the intent Describe stored for the person's use of a
frontend, but the Describe row left every person's line out, its comment saying their labels were
fixed. They are now measured, in the drawn lines and in the steps:

- a person's frontends are those its basis names, matched to the drawn frontends by name, and those
  a line the diagrams draw from the person reaches (`personsOf`, the one definition the model
  coverage and the page share);
- a line or step from a person to a frontend is intended when Describe stored an intent for the
  person's use of that frontend (`<basis>/<frontend>`), and own when it reads one;
- a line from a person to the system box, which the context view draws, takes the intents of any of
  the person's frontends;
- a person's line is generic, and counts in `genericWithIntent`, as any other line.

The paragraph of the #742 amendment that says a person's fixed "Uses" lines fail the label check
until people's lines are worded still holds for a person no intent words.


## Amendment (#743): the model's choice and the rank's on one run

The Flow selection row of #746 measured one mode per run, so a judged run gave no rank number to
compare with. With the run's `flows.json` beside `run.json` (ADR 0108, amendment for #743), the row
scores the system scope's own candidates twice: as drawn from the model's judgement and as the
rank fallback would draw them, each with its recall and precision (`judged` and `rank`), and lists
the drawn flows that overlap (one reaches the other's entry point, or both have one drawn shape).
The row's own numbers are the judged ones, else the rank's, and they count the flows drawn, not
every flow the judge marked significant. Without `flows.json` the row is computed as #746 says.
`scripts/score-testsets.sh` copies `flows.json` with the run's other files.

## Amendment (#722): the one-file gate fails on a cascade

The gate of #755 counted every item re-asked because a shown answer changed as expected, at any
depth, so a fully cascading run passed. Now (decision of 4 Nov 2026 on #722):

- **What the run records.** `askedWhy` also lists `changedAnswers`: for each changed answer the
  item shows, the item it comes from, as "`<role> <subject>`" or a role alone for any of its items
  (`AskedWhy.changedAnswers`, from `FingerprintInput.from`), alternatives as "`a|b`"; "?" names a
  source not recorded. An item that only lost a shown answer lists none.
- **The gate.** An item re-asked only through shown answers is expected when each changed
  answer's item was re-asked in this run and is itself expected, settled level by level. A cascade
  from an answer this run did not re-ask for the file is unexpected, listed with the answers whose
  items were not re-asked, and so is a changed answer whose source is not recorded. An item that
  only lost a shown answer, whose source is no longer named, is expected once something was
  re-asked for the file. A judge's item on a reworded text stays expected as before, unless another
  file changed too.

## Amendment (#747): a risk gathering several places, and repeated risks

- **Credit.** A drawn risk that is right (by a verdict, or by matching a reference risk) also counts
  for recall toward every other reference risk it matches (subject and category) whose cited lines
  it cites too. A risk that gathers the confirmed leads of several places, such as the
  send-before-commit methods of one service, so finds each reference risk at one of them. Precision
  still counts the drawn risk once. A fixed check's wording gets the same credit in its own row,
  which shares the rule. The report's risks (the risks table of the C4 report) count a parent row
  as the rows folded under it, never as a risk of its own.
- **Repeated risks.** The scorecard's "Repeated risks" line lists every group of investigated risks
  of one lead kind whose leads' units, sorted (the units in their value after the kind), are the
  same: one problem stored twice, which the join of
  ADR 0110 (amendment for #747) should have made one risk. A fixed check's wordings are not
  counted; #748 scores them apart. It reads `llm.jsonl.gz` and needs no answer key.

## Amendment (#749): LLM marks

The scorecard's "LLM marks" section reads the C4 report alone, so the drop is judged by the scorer
and not by eye (`llmMarks` in `lantern-web/scripts/score.mjs`):

- the number of elements and lines carrying the LLM badge;
- the system boxes carrying it, which should be none: the system's purpose is a wording, and what a
  role did not ask is bookkeeping (ADR 0110, amendment for #749);
- the badged elements, lines and table rows whose text still opens with "Found by the LLM:", which
  should be none;
- the badged lines whose target or label names a word of the Investigate contract's
  `infrastructure` list, matched as whole words as the check does, which should be none.

Connection precision, which the issue also judges, is the existing container-connection row.
