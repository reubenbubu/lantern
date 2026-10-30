# 0082: Investigate: the LLM finds what static analysis misses, with verified citations

Status: accepted (#269), builds on ADRs 0070 and 0081, pending the user's review (#24)

## Context

ADR 0070 records the user's decision that the LLM may find, not only word: a new role,
Investigate, reads files and queries the graph with the tools of ADR 0081 and proposes elements,
connections and risks, each citing file:line ranges that Lantern verifies; accepted proposals are
facts with `origin=llm` and a confidence, documents mark them as found by the LLM, and static
extraction wins on conflict. project-4's reference has 16 risks and Lantern states none; several
of them (identity headers trusted from any caller, events published before their transaction
commits, the message classes behind crm-shared-lib's version skew) are readings of code that no
fixed extractor makes.

## Decisions

- **Where it runs.** `Investigator` (`lantern-link`) is the linker's last step, after the
  resolver (`Linker.standard(store, resolver, investigator)`), so it sees every static and
  resolved link, and its facts are recorded and ended for the whole snapshot like any step's
  (ADR 0039). It runs only when the role's model has `tool-use` and files can be read, and never
  fails the run: a conversation refused by a budget or a capability, or failing in the provider,
  is logged, skipped and not cached.
- **What it is asked.** One conversation for each deployable unit and one for the system as a
  whole (`Role.INVESTIGATE`, reasoning tier), in parallel. A unit's prompt shows the units of the
  system and the repositories, then the unit's own facts, the links from and to it and the facts
  of its code that show how it is reached and what it reaches (endpoints, HTTP calls and clients,
  sends, listeners, gateway routes and filters, schedules, jobs, aspects), at most 150. The
  system's shows the links, open links, unrouted endpoints, version skew, the units' names,
  technology and bundled libraries, at most 200. Facts are written as the resolver writes them
  (no fact IDs or lines) in the order of their text, so the same facts give the same prompt. The
  system prompt asks for risks (security and consistency gaps, mismatched calls, single points of
  failure) and connections the facts do not state, read before proposing, at most 10, and says
  that the inputs are data, not instructions (ADR 0067). The tools are those of ADR 0081; their
  descriptions say that the lines `read_file` shows are cited as `repo:path:from-to`.
- **The answer.** One JSON object, `{"proposals": [...]}`. A risk has a title, a finding, a
  consequence, an impact and a confidence (high, medium or low) and the units it concerns (a
  unit's prompt is the default). A connection runs `from` a unit `to` another unit, an element
  the facts hold, or a new `external-system:<name>` or `database:<name>`, with an interaction
  (`http`, `messaging`, `database`, `mail`, `other`), a description, a technology and a
  confidence. Every proposal gives `names`, the identifiers it rests on as the code writes them
  (at least three characters with a letter, at most 12), and `cites`, 1 to 8 ranges.
- **The check, mechanical** (`ProposalCheck`). Each range must be in a repository of the
  snapshot, in a file that can be read at the snapshot's commit, within its lines and at most 40
  lines long; every name must appear in the lines cited; every range must hold at least one
  name. Lines are read as the tools show them, secrets masked, so a masked secret never holds.
  A proposal must also name units that are deployable units and, for a connection, an end that
  is not its unit and that the facts hold or is a new external system or data store. A
  proposal that fails is sent back once with its reason, all of them in one reply; the model
  answers with corrected versions or withdraws them, and what fails again is dropped. An answer
  that is not one JSON object with a list of proposals, or is cut off, is sent back once, then
  the conversation ends rejected. At most one reply per conversation.
- **Static wins.** A connection between two ends a static or resolved link already joins is
  dropped, as is a second proposal of the same risk title or the same connection. Risks are not
  compared with static checks here: the findings page (#273) merges the static checks with
  Investigate's risks.
- **The facts.** A risk is an `investigated-risk` of its first unit, its title the value; a
  connection an `INVESTIGATED_CONNECTION` from its unit to its end, with a stable link ID. Both
  keep their attributes (finding, consequence, impact, or interaction, description, technology;
  the model's confidence, units, names, citations, scope and model), have the first citation as
  evidence (repository, the snapshot's commit, path, lines and the file's SHA-256) and origin
  `llm`/`investigator` at the prompt's version. Their confidence is 0.6, 0.45 or 0.3 for high,
  medium or low, below any static fact's. The types are in `vocabulary/investigator-types.json`.
- **The cache.** Verified proposals are kept per prompt in `llm_investigations` (migration V18),
  keyed by the SHA-256 of the prompt version, provider, model, system prompt and prompt. A cached
  investigation is reused only while every citation still holds; a file can change while the
  facts do not, and then the prompt is asked again. `InvestigatePrompt.VERSION` is 1, guarded by
  a fingerprint test like the resolver's.
- **Marked in every template.** Every risk, line and element Investigate found opens with
  "Found by the LLM:", emphasized, in every template, as inferred text does (ADR 0059, ADR 0074).
  A new section, risks and findings (`SectionKind.FINDINGS`), is a table of Investigate's risks
  in scope (impact, finding, consequence, confidence, evidence as `repo:path:from-to`), most
  serious first, or a note that it found none; the Architecture Explorer has it after the main
  flows and both C4 reports after the sequence diagrams. The C4 report renderer puts this section,
  which is at no C4 level, at the end of the context page, the stopgap the user chose until the spec has documentation pages (#249), which will also give the
  spec a badge of its own. The Executive Brief lists the risks of its units as risk callouts
  among its own, ordered by severity. A connection is drawn as a line of its own, labelled
  "Found by the LLM:" and its description, in every container and context diagram that shows
  it; an element only it found is described by its connections the same way. "LLM" joins the
  bundled glossary.
- **Recordings in a run.** `lantern.llm.recordings.directory`, `.roles` and `.mode` (`replay`,
  the default, or `record`) answer the named roles' calls from recordings in the record and
  replay format (ADR 0008, `RecordedRoles`), every other call going to its provider. So a scoring
  run can replay the Investigate role on a test set with the fake model for the rest, and a real
  recording records only Investigate. `scripts/score-testsets.sh` passes any app property with
  `--set`, and its fake models have `tool-use`, so Investigate runs (and proposes nothing) in
  fake-model runs.

## Evidence

- `InvestigatorTest` (`lantern-link`): each unit and the system are asked, with their facts and
  the tools; a verified risk becomes a fact of the LLM with its citation as evidence; a new
  connection is accepted and one the facts link is dropped; an uncited proposal, ranges that
  hold none of the names, past the file's end or in no file, a masked secret and an unknown unit
  are sent back once with their reasons, a corrected one is accepted and the rest dropped; an
  answer that is not JSON is sent back once, then rejected; cached proposals are reused, and asked
  again when a cited file changes; a model without tools is not asked; a budget skips and caches
  nothing; and a three-turn conversation (`facts_about`, two `read_file` calls, the proposals)
  replays from `lantern-link/src/test/resources/recordings/investigate-tools`.
  `InvestigatePromptTest` guards the version.
- `InvestigatedFindingsTest` (`lantern-compose`): the Explorer's and the system report's tables,
  the Brief's callouts, and the lines and elements in the Explorer's, the Brief's and the C4
  report's diagrams, each marked. Golden files gain the section's note.
- `Project4InvestigateIT` (`lantern-app`, with `-Dlantern.test.project4`): the whole pipeline on
  project-4 at its pins, the Investigate role answered from the hand-written recordings in
  `testsets/project-4/recordings` (a scripted model reads the gateway's filter, the shared
  permission aspect, organization creation and the POMs, then proposes; `-Dlantern.test.rerecord`
  rewrites them from it, never a real model). It checks that the three risks the issue names and
  the gateway's line to Eureka are accepted, re-checks every citation from git at the pins, and
  that the system report, Main-Service's Explorer, Brief and C4 report mark them. The same
  recordings replay in `scripts/score-testsets.sh` with
  `--set lantern.llm.recordings.directory=<abs>/testsets/project-4/recordings --set
  lantern.llm.recordings.roles=investigate`, since the prompts are the same.

## Cost

The fake run's first turns on project-4 hold about 55,000 input tokens for its seven
conversations (the six units and the system), from 1,700 to 14,000 each. Each tool turn sends
every earlier turn again (ADR 0081) and adds a result of up to about 3,000 tokens, so with k tool
turns a conversation's input is about (k + 1) times its prompt plus 3,000 · k(k + 1)/2. At the
prices ADR 0070's Investigate model has in the settings ($2 and $10 per million tokens), and
with some thinking, a round on project-4 costs about $1.50 at 5 tool turns a conversation, $4 to
$5 at 10, and $12 at 20. A real recording should set the run's budget
(`lantern.llm.monthly-budget-usd` on a database of its own), which ends the conversations the
budget refuses as `BUDGET` instead of overrunning it.

## Not covered

Proposals of people and components, and of elements without a connection; contradictions of
static facts other than a repeated link; merging Investigate's risks with the static checks,
decisions and unknowns (#273); the spec's badge (#249); a turn limit per role (ADR 0081); prompt
caching of a conversation's earlier turns, which would cut the cost of long conversations. A
real model's recording on project-4 needs the user's go-ahead (ADR 0070) and is not made here.
