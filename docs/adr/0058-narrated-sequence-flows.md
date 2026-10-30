# 0058: Narrated sequence flows

Status: accepted (#195), amends ADR 0047 and ADR 0050, amended by ADR 0060 (#196), ADR 0067 (#200)
and ADR 0047's amendment for #272 (the answer also holds the flow's title), amended for #369

## Context

Each main flow (ADR 0047) has a few sentences of prose that the composer writes from the trace,
naming the participants and the asynchronous hand-offs, and a table with its trigger and its pre-
and post-conditions, which say "Not known from the code.". `docs/design.md` (Composition, Sequence
diagrams) asks for two to four sentences of prose per flow, and LLM integration, Grounding, says
the LLM words what extraction found, citing it. The report spec
`report-specs/c4-model-sequence-diagrams` shows a flow's prose as its summary ("A signed-in
customer submits their cart...") and its conditions as short phrases ("Customer is authenticated;
cart is non-empty", "Order persisted as PLACED; event published"). Issue #195 asks for a step that
words each flow's walkthrough and conditions from the trace's facts only, cited and cached by what
the model sees, as the Describer does for units (ADRs 0050, 0056).

## Decisions

- **One call per flow, in the Describer.** After the units, data stores and destinations,
  `Describer` asks about each flow that the system's documents and each repository's show: the
  flows `SequenceFlows` chooses for the system scope and for each repository's units, each once,
  since the same trace gives the same prompt whichever scope shows it. The system's flows are
  traced from every fact that holds in the snapshot and each repository's from the facts its
  documents are composed from (the analyze run's extracted facts, which leave out repositories
  carried forward), so each is traced as its document will trace it. It reuses the Describer's
  asking, checking, retrying, caching and counting. Role `COMPOSE`, the design's role for narrative
  for a reader (high-volume tier), not `INFER_INTENT`.
- **Facts only.** The prompt (`WordingPrompt.flow`) names the flow by its title and what starts it,
  with the entry point's fact, then lists the steps in order as the trace found them: each message
  with its two ends, the kind of the receiving end, whether it waits, the label the facts give, the
  endpoint or protocol and the walkthrough text of the diagram, each note with its text, the
  profiles a step needs, and the operands of an implementation `alt` frame, indented. Under each
  step are the facts it rests on, written as in ADR 0050, without fact IDs or line numbers. The
  steps leave out the LLM's intent phrases (ADR 0050) and the facts they cite, so the prompt rests
  on extracted facts only and does not change, or miss the cache, when the units are worded
  differently. A trace is at most 40 messages long (ADR 0047), which bounds the prompt.
- **The prompt** has its own system prompt, `WordingPrompt.FLOW_SYSTEM`: a walkthrough of two to
  four plain sentences, at most 80 words, saying what the flow does for the system in the order it
  happens and where it hands work on; pre-conditions of at most 25 words, what must hold before it
  starts as the facts show it; post-conditions of at most 25 words, what has changed when it ends,
  such as data written or messages sent; each left out when the facts don't show it. The answer is
  `{"walkthrough": ..., "preconditions": ..., "postconditions": ...}`, each a `text` and its
  `cites`, every part optional.
- **The check, retry and failures are ADR 0050's**: every text cites facts of the prompt and holds
  no reference; a walkthrough has at most 100 words and conditions at most 30. A rejected answer is
  sent back once; a second rejection, a refused call or a provider failure logs a warning and
  caches nothing. An answer may take at most 1,500 tokens.
- **Cached by what the model sees**, in the `unit_wordings` table (its `unit` column holds the
  entry point's method), keyed by the SHA-256 of the prompt version, provider, model, system prompt
  and user turn. The flow prompt has its own version, `WordingPrompt.FLOW_VERSION`, which
  `WordingPromptTest` enforces as it does the others; the units' and elements' prompts and versions
  are unchanged.
- **Documents.** `Wordings` gains the flows, keyed by the SHA-256 of the user turn of the prompt
  about each. The composer writes that prompt for each flow it shows, from its own trace, and uses
  the wording of the same key, so a flow is worded only where it is traced as the model saw it; a
  document whose facts trace it differently keeps its own text. The walkthrough, with its
  citations, replaces the composed prose (the report's summary), and the conditions replace "Not
  known from the code." in the overview table. Citations of facts that are not in the document's
  facts are left out, and a text left with none is not used, as in ADR 0050. Anything the model
  leaves out keeps today's text: the composed prose, or "Not known from the code.".
- **Counts.** `Describer.Result`, `AnalysisRun.Result.wordingsAsked` and `wordingsReused` count
  flows with units, data stores and destinations, so `EndToEndIT`'s checks that every call is
  counted and that the second run reuses every wording cover them; the analyze run prints "units,
  data stores, destinations and flows worded by the LLM". A rejected or skipped flow is listed by
  its entry point's method.

## Not verified

Whether real models narrate flows well, and keep to what the facts show for pre-conditions, which
code shows less directly than post-conditions, needs real calls, which tests do not make. The cost
is one call per flow shown, at most seven per scope and fewer once scopes share flows: about 1,500
characters of system prompt plus some 200 characters per fact shown, so some 1,500 input tokens for
a flow of ten steps, at most 1,500 output tokens.

## Not in this issue

Error flows (ADR 0047); the glossary in the prompt (annotations, Phase 3); a minimal edit of the
previous text when a flow changes (design, Prose stability).

## Amendment (#369): "at most seven per scope" in the cost estimate no longer holds: the number of flows is not fixed (ADR 0047, amendment for #369), so the estimate counts the flows the LLM keeps.
