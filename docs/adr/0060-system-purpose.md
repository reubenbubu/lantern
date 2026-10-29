# 0060: The system's purpose

Status: accepted (#196), amends ADR 0044, ADR 0050 and ADR 0058, pending the user's review (#24)

## Context

`docs/design.md` (Quality bar) wants every document's first paragraph to give the system's purpose,
and LLM integration, Grounding, lists "a system's purpose" among what the LLM words, while business
purpose comes from what people wrote where there is any, and otherwise is inferred from the code
and shown as inferred (ADR 0059). Summaries
and wordings stop at the deployable unit (ADRs 0010, 0050), so the system scope's C4 report
(`_system.c4.html`, ADR 0044) has no source for the system's purpose. Issue #196 asks for a
system-level wording made from the units' sentences and README claims, or the purpose stated plainly
as unknown.

## Decisions

- **One call for the system, in the Describer.** After the units, data stores, destinations and
  flows (ADRs 0050, 0056, 0058), `Describer` asks once about the system (`system:snapshot`, over
  every fact that holds in the snapshot) for its purpose. It reuses the Describer's asking,
  checking, retrying, caching and counting. Role `INFER_INTENT`, the role for responsibilities,
  which the design's Roles table now names for a system's purpose too.
- **What the prompt shows** (`WordingPrompt.purpose`): each deployable unit the model gave a
  responsibility sentence in this run, by name (at most 30), with that sentence and, under it, the
  facts it cites (at most 8 each), and the claims of the README with the fewest folders in each
  repository (at most 8 each, as for units), under the repository and the README's path, after
  the line "The software system.". Facts are
  written as in ADR 0050, without fact IDs or line numbers. Units are listed by name and READMEs
  by repository, so a new commit that changes nothing shown gives the same prompt. The units'
  sentences are the model's own earlier output, which ADR 0050 keeps out of unit prompts; here they
  are what the issue asks for, and each comes with the extracted facts it rests on, which are the
  only things the answer may cite. Since unit answers are cached, a run over unchanged facts gives
  the same sentences and so the same prompt. The system's name is not shown: it is the list of
  repositories, which the prompt shows already.
- **Not asked** when no unit has a sentence and no repository has README claims: there is nothing
  to word from, so the report states the purpose as unknown without a call.
- **The prompt** has its own system prompt, `WordingPrompt.PURPOSE_SYSTEM`: one plain sentence of at
  most 25 words saying what the system as a whole is for, taken from the README claims when they
  state it, else inferred from what the units do together, never inventing a business, user or
  capability, and left out when neither shows it. The answer is `{"purpose": {"text": ...,
  "cites": [...]}}`, or `{}`.
- **The check, retry and failures are ADR 0050's**: the sentence cites facts of the prompt, holds no
  reference and has at most 30 words. A rejected answer is sent back once; a second rejection, a
  refused call or a provider failure logs a warning and caches nothing. An answer may take at most
  1,000 tokens.
- **Cached by what the model sees**, in the `unit_wordings` table (its `unit` column holds
  `system:snapshot`), keyed by the SHA-256 of the prompt version, provider, model, system prompt and
  user turn. The prompt has its own version, `WordingPrompt.PURPOSE_VERSION`, which
  `WordingPromptTest` enforces as it does the others; the other prompts and versions are unchanged.
- **In the system's report.** The purpose, with its citations, is kept with the responsibility
  sentences under `system:snapshot` (`Wordings.responsibilities`), so it becomes the system's
  description wherever the system is drawn in the system scope, and it opens the overview, before
  the repositories, which the C4 report renders as the first paragraph of its introduction.
  Whether it is evidenced or inferred is read from its citations, in the spirit of ADR 0059: one
  citing a README claim is evidenced and shown as written; one citing none rests only on the
  units' sentences, so on the code, and opens with ADR 0059's emphasized "Purpose inferred from the
  code:", in the overview and in the system's description alike. The model is not asked to mark
  it, since its citations say which it is. Without a purpose (none asked, left out, rejected,
  skipped, or citing no fact of the snapshot) the overview opens with a note: "The system's purpose
  is not known." It gives no reason, since the model may have been left out, refused or not asked.
  A note cites nothing, as the context's note on people does. Per-repository documents are
  unchanged.
- **Counts.** `Describer.Result`, `AnalysisRun.Result.wordingsAsked` and `wordingsReused` count the
  system with the units, data stores, destinations and flows, so `EndToEndIT`'s checks that every
  call is counted and that the second run reuses every wording cover it; the analyze run prints
  "units, data stores, destinations, flows and the system worded by the LLM". A rejected or skipped
  purpose is listed as `system:snapshot`.

## Not verified

Whether real models tell a purpose the README claims state from one they infer, and keep from
inventing a business, needs real calls, which tests do not make. The cost is one call per run that
misses the cache: about 1,500 characters of system prompt plus some 150 characters per fact shown,
so some 1,000 input tokens for five units and a few claims, at most 1,000 output tokens.

## Not in this issue

Purposes from annotations and the glossary in the prompt (Phase 3); a purpose for groups (Phase 4);
the purpose in per-repository documents and the Executive Brief, which open with the repository's
summary.
