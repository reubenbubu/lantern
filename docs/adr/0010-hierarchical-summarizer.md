# 0010: Hierarchical summarizer

Status: accepted (#11), pending the user's review (#24)

## Context

`docs/design.md` (LLM integration, Versioning and incremental computation) asks for LLM summaries
at every level, built bottom-up from a unit's facts and its parts' summaries, cached by a hash of
the input facts, child summaries, prompt version and model, citing the fact IDs they used, and
invalidated with their parents when inputs change. Issue #11 builds this from class up to package,
module and repository. The design leaves open where the summarizer lives, how the hierarchy is
found in the facts, how summaries are stored and cited, and what happens to uncited output.

## Decisions

- **A module of its own, `lantern-summarize`**, depending on `lantern-core` and `lantern-llm`.
  Extractors may not use the LLM (ArchUnit rule), and `lantern-llm` stays a thin access layer
  (0008) that knows nothing about facts.
- **The hierarchy comes from facts.** `SummaryTree` builds one tree per repository from the facts
  of a snapshot: the repository, its top modules (those no other module of the same repository
  contains), submodules, packages, types and member types, following `CONTAINS` facts between
  modules, packages and types (0006, 0007). A module belongs to the repositories its facts were
  observed in. Methods and fields are not summarized on their own: their facts belong to their
  type. A unit's facts are those about it, about its methods and fields, and about what contains
  it outside the hierarchy (the `service` or `library` that builds a module). Facts with the unit
  as object, such as calls into it, are left out, so a unit is summarized again only when it
  changes itself. All facts about a containing `service` or `library` go to each module it
  contains; they are few today, but if later stages add many, this should narrow to the
  container's own `CONTAINS` and attribute facts. Types are keyed by name alone (0007), so a type
  declared in two modules is one shared unit with the facts of both. A unit with neither facts nor summarized parts gets no summary.
- **Repository summaries use the entity ID `repo:<name>`.** Repositories have no entity in the
  fact store and summaries are not facts, so `repo` is not added to the vocabulary.
- **Summaries are not facts.** A summary cites many facts while a fact's evidence is one location,
  and the design lists cached LLM results as their own kind of state. They are kept in a
  `summaries` table keyed by cache key (`JdbcSummaryStore`; `InMemorySummaryStore` for tests), with
  the entity, level, text, cited and input fact IDs, cited and input child summary keys, prompt
  version, provider and model. Summaries are not tied to snapshots: whoever needs a snapshot's
  summaries runs the summarizer on its facts, which reuses every stored summary without calling
  the model.
- **Cache key** is the SHA-256 of the prompt version, provider, model identifier, level, entity,
  the sorted IDs of the unit's facts, and each contained unit's entity, summary key and the
  SHA-256 of its summary's text, so a parent's recorded inputs always point at the child summaries
  it was made from. Invalidation follows from the key: when a fact changes, its unit gets a new key
  and is summarized again; the new text gives the parent a new key, and so on up to the
  repository, while units whose inputs did not change are reused. Old rows are left in place;
  pruning them can come with retention. Fact IDs include the commit (0001), so until extractor
  results are reused across commits (incremental runs, Phase 3), a new commit summarizes the
  whole repository again.
- **Citations.** The prompt lists facts as `[F1]`, `[F2]`, ... and the parts' summaries as `[S1]`,
  ... (shown without their own citations), and asks for references at the end of every sentence.
  The answer's references are rewritten to `[fact:<fact ID>]` and `[summary:<entity ID>]` in the
  stored text. An answer is rejected if it is empty, cites nothing, cites a reference that was not
  in the prompt, writes `[fact:...]` or `[summary:...]` markers itself, or was cut off at its
  output limit (the stop reason `max_tokens`, as Anthropic reports it; stop reasons should be
  normalized in `lantern-llm` when a second provider arrives, #38). A rejected answer is sent back once with the
  reason (a second call, logged with the purpose suffix "(retry)"); if the second answer is also
  rejected the run fails with `SummaryRejectedException` and nothing is stored for that unit. The
  check needs at least one valid citation, not one per sentence, since models do not always cite
  every sentence and the design allows rejecting or marking low confidence.
- **Prompt version** is a constant (`Prompt.VERSION`). A test fingerprints the prompt texts, the
  per-level limits and a rendered prompt, and fails until the version is raised when they change.
- **Length.** The prompt asks for at most 80 words for a type, 120 for a package, 150 for a module
  and 200 for a repository, with output limits of 400 to 1,000 tokens to leave room for
  citations. Prompt caching is not requested: the system prompt is shorter than the providers'
  minimum cacheable length.
- **Children before parents**, through the `SUMMARIZE` role's model and the caller's budgets.
  Calls were one at a time in entity ID order; since #44 independent units run in parallel (ADR
  0021).

## Not in this issue

Source excerpts in the prompt and units too large for one prompt (#43), parallel calls
with rate limiting (#44), the glossary in prompts (annotations, Phase 3), and levels
above the repository (service, group, system), which come with the linker and grouping.
