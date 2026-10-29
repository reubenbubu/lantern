# 0050: Responsibility sentences and intent phrases

Status: accepted (#181), amends ADR 0044, ADR 0047 and ADR 0048, amended by ADR 0056 (#194),
ADR 0058 (#195), ADR 0060 (#196) and ADR 0061 (#216), pending the user's review (#24)

## Context

`docs/design.md` (Quality bar, every diagram) asks every element for a responsibility sentence that
doesn't restate its name, and every line for an intent phrase, never just "uses" or "depends on".
LLM integration, Grounding, says the LLM words these from what extraction found, citing it, and
never invents elements or relationships; its Roles table gives responsibilities to Infer intent.
Until now a unit's description was its main module's summary, a component's its class's summary
(ADR 0044), and lines and sequence messages said what static facts say (`Calls GET
/api/products/{id}`, `Sends order.created`; ADRs 0044, 0047). Issue #181 asks for the LLM's
wording, cited and cached by input hash like summaries (ADR 0010), building on ADR 0025's prompt,
with fakes in tests and everything optional: a missing sentence or phrase leaves the description
or static label as it was.

## Decisions

- **One call per deployable unit.** `Describer` (`lantern-compose`, since it needs the components
  and links as the C4 views find them) asks about each unit of the snapshot: a responsibility
  sentence for the unit and for each of its components, and an intent phrase for each of its links
  (`CALLS_HTTP`, `PUBLISHES`, `CONSUMES`, `USES_DATABASE`, `ACCESSES_DATA_OF`, profile alternatives
  left out as on the diagrams). One call per unit keeps the count small and lets the model see the
  unit whole. Role `INFER_INTENT`, the design's role for responsibilities (reasoning tier). The
  analyze run calls it after summarizing, over every fact that holds in the snapshot.
- **Facts only.** The prompt shows the facts that make the unit a unit, the claims of the README
  with the fewest folders in each repository holding the unit's own module (at most 8), its
  components when it has more than one, as the component diagram draws them (at most 25, each with
  its role and at most 8 of its stereotype, entry point and repository facts), or else its entry
  points (at most 15), and its links (at most 40), each with the kind of the other end. Facts are
  written as in ADR 0045, without fact IDs or line numbers. Summaries are not shown: they exist only
  for the repositories a run analyzes, so showing them would make the prompt, and so the calls,
  depend on which repositories a run names, and a run over commits analyzed before would call the
  model again. Facts are listed in the order of their lines, not by fact ID, which holds the
  commit, so a new commit that changes nothing shown gives the same prompt. The issue asks for wording from extracted facts in any case. A unit with nothing
  but the facts that make it a unit is not asked about.
- **The prompt** asks, in ADR 0025's spirit, for one plain sentence of at most 25 words saying
  what the unit or component does for the system, not restating its name or kind, and for an intent
  phrase of at most 8 words starting with a verb, without the protocol; anything the facts don't
  show is left out rather than guessed. The answer is one JSON object, `unit`, `components` (by
  `C1`, ...) and `relationships` (by `R1`, ...), each item a `text` and the `cites` (`F1`, ...) it
  rests on, every part optional.
- **The check** rejects an answer that is not one JSON object, was cut off, names a component or
  relationship the prompt does not show, cites nothing or a reference that is not a fact of the
  prompt, writes a reference in a text, has a sentence over 30 words or one that is only the
  element's name, or a phrase over 10 words or one that is only "uses", "calls", "depends on" or a
  similar word. As with summaries and links (ADRs 0010, 0045), a rejected answer is sent back once
  with the reason (`(retry)`); a second rejection logs a warning and caches nothing, so the unit
  keeps its summaries and static labels and a later run asks again. A call refused by a budget or
  a capability check, or failing in the provider, does the same. The run never fails for it.
- **Cached by what the model sees**, as in ADR 0045: the key is the SHA-256 of the prompt version,
  provider, model, system prompt and user turn, so a new commit that changes nothing the prompt
  shows makes no call. Answers, with their references, are kept in the `unit_wordings` table
  (migration V13; `JdbcWordingStore`, `InMemoryWordingStore` for tests) and resolved against the
  prompt of the same key, so they cite the current snapshot's fact IDs. `WordingPrompt.VERSION` is
  raised whenever the prompt or its limits change, which `WordingPromptTest` enforces.
- **Descriptions.** A unit's and a component's main type's responsibility sentence, with its
  citations, becomes the entity's description in every document instead of the summary (ADR
  0044), so the C4 report's element descriptions and catalogues carry it. A type has one
  description, so a component class's longer summary is no longer shown in the Explorer; its
  module's and package's summaries are. A class that is a component of two units gets the sentence
  of the first by ID. Citations of facts not in a document's facts are left out; a sentence left
  with none is not used, while a phrase still is, since its line cites the link it words.
- **Labels.** A container, context or component line whose links all have an intent phrase is
  labelled with them, naming two (`Looks up product prices; places orders`) and counting the rest;
  the facts they cite join the line's. A line with a link without one keeps its static label, as do
  calls between components, data access from components and routes between exchanges and queues.
  A sequence message for an HTTP call or a send takes its link's phrase, the endpoint or protocol
  after it as before ("Create order [POST /orders]", as the design asks); deliveries, data access
  and notes keep what the facts say. A `CONSUMES` link is worded from the receiving unit's side and
  labels the line from the queue or exchange to it.
- **The analyze run** prints how many units the LLM worded and how many came from the cache;
  `AnalysisRun.Result` gains `wordingsAsked` and `wordingsReused`. `EndToEndIT` checks that the
  second run reuses every wording, and its call report counts the calls by role.

## Not verified

Whether real models word well from facts alone, and whether one call per unit stays within output
limits for very large units, needs real calls, which tests do not make. The cost estimate for a
unit is roughly 1,800 characters of system prompt plus some 150 characters per fact shown: about
1,500 input tokens for a unit with ten facts, at most 3,000 output tokens.

## Not in this issue

Responsibility sentences for databases, queues and external systems (ADR 0056); the glossary in the prompt
(annotations, Phase 3); unknowns stated as such where the model leaves a unit out (the element then
keeps its summary or has no description, as before).
