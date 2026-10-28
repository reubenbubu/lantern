# 0024: Source files in type summaries, and units too large for one prompt

Status: accepted (#43), pending the user's review (#24)

## Context

`docs/design.md` (LLM integration) lists source excerpts as an input of the Summarize role and asks
every output to cite the fact IDs or file ranges it used. The summarizer of ADR 0010 sent only facts
and child summaries, so a model could not see Javadoc, annotations or code, and a unit whose prompt
exceeded the model's context window was refused by `LlmClient` and failed the run. The design does
not say which excerpts to show, how to cite them, or how to summarize a unit that does not fit.

## Decisions

- **A type's prompt shows its whole source file**, the one its `kind` fact was observed in, with no
  selection or budget (the user chose simple and complete over cheaper). A type declared in two
  files (one per module, ADR 0007) shows both. A member type shows the file of its outer type again.
  Packages, modules and repositories show no files: they build on their types' summaries.
- **The file is cited as a fact.** It is listed as `[E1]`, and an `[E1]` citation is stored as
  `[fact:<ID>]` of the `kind` fact, whose evidence is that file. The summary still cites only facts
  and summaries, so the `Summary` record, the store and the composer are unchanged.
- **Files are read from git at the fact's commit** (`GitSourceFiles`, `git cat-file`, files up to 1
  MiB), never from the working tree, and shown only when the file's SHA-256 matches the content
  hash of the fact's evidence. Files that cannot be read (for example generated sources, whose
  evidence paths point into the build directory) are not shown. The app reads from each registered
  repository's directory; `Summarizer` without a `SourceFiles` shows none. Units run in parallel
  (ADR 0021), so the small cache of read files is locked, but not while a file is read.
- **The cache key includes each file** (its fact, line count and the SHA-256 of its text), so the
  same facts summarized with and without files are not mixed up. `Prompt.VERSION` is 2.
- **Cost.** On the fixture projects the type prompts grow from 3,299 to 6,159 characters and all
  prompts from 10,119 to 12,979 (+28%). Not measured on OpenWMS: its main code is about 57,000
  non-blank lines (design.md, Open questions), roughly 2.5 million characters or 600,000 tokens,
  so a full run sends roughly that much more input, more where member types repeat their file;
  about $2 at Sonnet's input price.
- **Units too large for one prompt are split and merged**, as a safety net only; nothing is trimmed.
  A unit's user turn may take the context window minus the system prompt, the model's thinking
  headroom (ADR 0022), the output limit, one and a half output limits for a rejected answer sent
  back with a retry, the retry message and 100 tokens. A unit over that is sent in parts: its
  facts, files and child summaries, in that order, are packed into prompts that each fit, under the
  references of the whole unit, and each part is summarized (purpose suffix `(part i of n)`). The
  parts' summaries, with their references, are then merged into one (`(merge)`); if they do not fit
  one prompt they are merged in groups first (`(merge r.i of n)`). A unit's parts and merges are
  asked one after another, while other units run in parallel. Each answer is checked as in ADR
  0010, and may cite only references its prompt showed. Only the final summary is stored; if a run
  fails part-way, the parts are summarized again next time.
- **A single input larger than the budget** (a source file, a fact or a child summary) still gets
  its own part and is refused by `LlmClient`, failing the run, as is a merge whose part summaries
  cannot be paired within the budget. With today's context windows this needs a source file of
  several hundred thousand characters, which the 1 MiB read limit makes rare.

## Not in this issue

The prompt's wording (hedging, entity IDs in the text, repository summaries repeating their
module's) is #71.
