# 0056: Responsibility sentences for data stores and destinations

Status: accepted (#194), amends ADR 0050, amended by ADR 0065 (#216) and ADR 0067 (#200)

## Context

`docs/quality-bar.md` (Rubric, every diagram) asks every element for a responsibility sentence.
ADR 0050 has the LLM word deployable units, components and links only, so the databases, topics,
queues, exchanges and external systems of the context and container diagrams have no description:
they have no summary either, since summaries are of code. Issue #194 asks the Describer to word
them from their facts, with citations and caching as in ADR 0050.

## Decisions

- **One call per data store or destination.** After the units, `Describer` asks about each
  database, topic, queue, exchange and external system the container diagrams draw: an end of a
  line to or from a deployable unit, so profile alternatives, RabbitMQ queues folded into a line
  (ADR 0044) and exchanges or queues joined only by routes are not asked about. One call per element, rather than adding them to
  each unit's call, gives an element used by several units one sentence that sees all its uses,
  and keeps the units' prompts, and their cached answers, as they were. Role `INFER_INTENT`, as
  for units. Every such element is asked about, since each has a line to or from a unit.
- **Facts only.** The prompt (`WordingPrompt.element`) names the element, its kind and its
  technology (`The database shop on db, PostgreSQL.`), then shows the facts about it (at most 8),
  for a database the facts placing tables in the schemas that units build there (from the
  `schemas` of their `USES_DATABASE` links, at most 30), and how it is used: each other end of its
  lines with what it does (`shop-app (a deployable unit) connects to it:`, `worker (a deployable
  unit) receives messages from it through payments.order-events:`), followed by the link facts of
  those lines (at most 40 in all). Facts are written as in ADR 0050, without fact IDs or line
  numbers, listed by line, and uses are listed by what they say, so a new commit that changes
  nothing shown gives the same prompt.
- **The prompt** has its own system prompt, `WordingPrompt.ELEMENT_SYSTEM`: one plain sentence of
  at most 25 words saying what the element holds or carries, or what it does for the system, not
  restating its name or kind, inferred from names, paths, routing keys, tables and how the units
  use it, and left out when the facts don't show it. The answer is `{"element":
  {"text": ..., "cites": [...]}}`, or `{}`.
- **The check, retry and failures are ADR 0050's**: the sentence must cite facts of the prompt,
  hold no reference, have at most 30 words and not be only the element's name; a rejected answer
  is sent back once, and a second rejection, a refused call or a provider failure logs a warning
  and caches nothing. An answer may take at most 1,000 tokens.
- **Cached by what the model sees**, in the same `unit_wordings` table (its `unit` column holds
  the element), keyed by the SHA-256 of the prompt version, provider, model, system prompt and user
  turn. The element prompt has its own version, `WordingPrompt.ELEMENT_VERSION`, which
  `WordingPromptTest` enforces as it does `VERSION`; the units' prompt and version are unchanged,
  so answers cached for units stay valid.
- **Descriptions.** An element's sentence, with its citations, is its description in every
  document that draws it, so the C4 report's catalogue of data stores and queues and of external
  systems carries it. Citations of facts that are not in the snapshot's facts are left out, and a
  sentence left with none is not used, as in ADR 0050. An element the model leaves out has no description,
  as before.
- **Counts.** `Describer.Result`, `AnalysisRun.Result.wordingsAsked` and `wordingsReused` count
  data stores and destinations with units, so `EndToEndIT`'s checks that every call is counted and
  that the second run reuses every wording cover them; the analyze run prints "units, data stores
  and destinations worded by the LLM" (with flows since ADR 0058).

## Not verified

Whether real models word these elements well from their facts alone needs real calls, which tests
do not make. The cost is one call per element the diagrams draw: about 1,000 characters of system
prompt plus some 150 characters per fact shown, so some 500 input tokens for an element with five
facts, at most 1,000 output tokens.

## Not in this issue

Code in the Describer's unit prompt, for generically named components (ADR 0069); the glossary in
the prompt (annotations, Phase 3).
