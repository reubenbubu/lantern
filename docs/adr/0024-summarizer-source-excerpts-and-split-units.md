# 0024: Source excerpts in summaries, and units too large for one prompt

Status: accepted (#43), pending the user's review (#24)

## Context

`docs/design.md` (LLM integration) lists source excerpts as an input of the Summarize role and asks
every output to cite the fact IDs or file ranges it used. The summarizer of ADR 0010 sent only facts
and child summaries, so a model could not see Javadoc, annotations or code, and a unit whose prompt
exceeded the model's context window was refused by `LlmClient` and failed the run. The design does
not say which excerpts to show, how to cite them, or how to summarize a unit that does not fit.

## Decisions

- **Only types get excerpts.** A type's prompt shows its declaration (with the Javadoc right above
  it and its annotations, up to the opening brace), then each method and field: Javadoc,
  annotations and the declaration up to its body (the first brace or semicolon outside
  parentheses, strings and comments, so `@GetMapping("/{id}")` does not end it). While the budget allows, members of up to 20
  lines are then shown whole, in source order. Javadoc over 15 lines and lines over 200 characters
  are cut, and `...` marks what was left out. Packages, modules and repositories get none: they
  build on their types' summaries.
- **Budget:** at most 2,000 tokens of excerpts per type (as `Tokens` estimates them, counting each
  excerpt's code, path and an allowance for its reference and fence), declarations
  first, then every member's signature, then whole short members. An excerpt that overlaps one
  already shown is skipped, so members Lombok places on its annotation's line are not repeated.
  This adds at most 2,000 input tokens a type; OpenWMS has about 900 types, so at most about 1.8
  million tokens a full run, about $5 at Sonnet's input price.
- **Excerpts are cited as facts.** Each excerpt is taken from the file and lines of one fact's
  evidence (the type's `kind` fact, or the `CONTAINS` fact of a member), listed as `[E1]`, `[E2]`,
  ..., and an `[E<n>]` citation is stored as `[fact:<ID>]` of that fact. The fact's evidence is
  the file range, so the summary still cites only facts and summaries, and the `Summary` record,
  the store and the composer are unchanged. The excerpt's own lines (which add the Javadoc above
  and may stop at the signature) are shown in the prompt but not stored.
- **Files are read from git at the fact's commit** (`GitSourceFiles`, `git cat-file`, files up to 1
  MiB), never from the working tree, and an excerpt is shown only when the file's SHA-256 matches
  the content hash of the fact's evidence. Files that cannot be read (for example generated
  sources, whose evidence paths point into the build directory) give no excerpts. The app reads
  from each registered repository's directory; `Summarizer` without a `SourceFiles` shows none.
- **The cache key includes each excerpt** (its fact, lines and the SHA-256 of its text), so the
  same facts summarized with and without excerpts are not mixed up. `Prompt.VERSION` is 2.
- **Units too large for one prompt are split and merged.** A unit's user turn may take the context
  window minus the system prompt, the model's thinking headroom (ADR 0022), the output limit, one and a half output limits for a rejected
  answer sent back with a retry, the retry message and 100 tokens. A unit over that is sent in
  parts: its facts, excerpts and child summaries, in that order, are packed into prompts that each
  fit, under the references of the whole unit, and each part is summarized (purpose suffix
  `(part i of n)`). The parts' summaries, with their references, are then merged into one
  (`(merge)`); if they do not fit one prompt they are merged in groups first (`(merge r.i of n)`).
  A unit's parts and merges are asked one after another, while other units run in parallel (ADR
  0021). Each answer is checked as in ADR 0010, and may cite only references its prompt showed. Only the
  final summary is stored; if a run fails part-way, the parts are summarized again next time. No
  facts are dropped: splitting keeps everything a unit has, at the cost of more calls.
- **A single input larger than the budget** (one fact or child summary) still gets its own part
  and is refused by `LlmClient`, as is a merge whose part summaries cannot be paired within the
  budget. Facts are one line and summaries are bounded by their output limit, so this happens only
  with context windows of a few thousand tokens.

## Not in this issue

The prompt's wording (hedging, entity IDs in the text, repository summaries repeating their
module's) is #71.
