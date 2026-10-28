# 0010: Hierarchical summarizer

Status: accepted (#11), amended by ADR 0021 (#44) and below (#78, #76), pending the user's review (#24)

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
  rejected the run fails with `SummaryRejectedException` and nothing is stored for that unit
  (since #76 the unit is left without a summary instead; see the amendment below). The
  check needs at least one valid citation, not one per sentence, since models do not always cite
  every sentence and the design allows rejecting or marking low confidence.
- **Prompt version** is a constant (`Prompt.VERSION`). A test fingerprints the prompt texts, the
  per-level limits and a rendered prompt, and fails until the version is raised when they change.
- **Length.** The prompt asks for at most 80 words for a type, 120 for a package, 150 for a module
  and 200 for a repository, with output limits of 400 to 1,000 tokens to leave room for
  citations. Prompt caching is not requested: the system prompt is shorter than the providers'
  minimum cacheable length. ADR 0025 adds sentence counts per level and lowers the repository
  limit to 150 words and 800 output tokens.
- **Children before parents**, through the `SUMMARIZE` role's model and the caller's budgets.
  Calls were one at a time in entity ID order; since #44 independent units run in parallel (ADR
  0021).

## Not in this issue

Source excerpts in the prompt and units too large for one prompt (#43, now ADR 0024), parallel calls
with rate limiting (#44), the glossary in prompts (annotations, Phase 3), and levels
above the repository (service, group, system), which come with the linker and grouping.

## Amendment (#78): citation ranges

- **A range cites every reference in it.** Models sometimes cite ranges such as `[F1-F3, F5]`.
  A range of one kind, written with a hyphen, en dash or em dash (`F1-F3`, `F1–F3`, `F1-3`), is
  read as citing each reference in it, so its facts are recorded as cited and it is stored as one
  marker per reference; no range text is stored or shown.
- **Ranges are checked like single references.** A range that reaches past the references given
  (`F0-F2`, or `F1-F60` with 50 facts) is rejected with the range as written. A range of mixed
  kinds, in reverse order, with an index too large to read or spanning more than 10,000
  references (`F1-S2`, `F3-F1`) is rejected with its own reason, asking for single references.
  A range within the references given but past those a merge prompt showed (ADR 0024) is
  rejected naming the references it lacks. A rejection lists at most 10 references, then how
  many more. Rejection and retry are as above.
- **The prompt asks for single references** (`[F1, F2, F3]`, never `[F1-F3]`), in the system
  prompt and the retry, so ranges should be rare; accepting them avoids a retry when one appears.
- **The prompt version was raised to 4.** Every cached summary is made again on the next run,
  since the version is part of its cache key. That also replaces the cached summaries of prompt 3
  that still hold raw range text.

## Amendment (#76): a unit rejected twice has no summary

A run of about 1,300 calls failed on one module whose answer was rejected twice, and a rerun
passed, so the rejection was a chance bad answer, not a property of the unit.

- **The run goes on.** A unit whose answer is rejected twice gets no summary and nothing is
  stored for it, as before, but the summarizer carries on. Its parent is summarized without it,
  the way a unit with nothing to summarize is left out: the parent's prompt does not show it and
  its key does not include it. A unit summarized in parts or merges counts as rejected when any
  of its answers is rejected twice. `Summarizer.Result.rejected` lists such units; the
  `analyze` command prints them and logs them as a warning. A rerun tries them again, since
  nothing is cached for them, and then summarizes their parents again, whose keys change.
- **Both rejections are logged**, as warnings with the purpose of the call, the reason and the
  rejected answer, cut to its first 1,000 characters.
- **No threshold.** Every unit may be rejected and the run still succeeds, for example with a
  model whose answers are always cut off; the printed list and the warnings show it, and the
  run's budgets bound what it costs. A threshold can be added if this turns out to matter.
- **Documents need no change.** The composer already leaves out the summary paragraph and the
  description of a unit without a summary.
