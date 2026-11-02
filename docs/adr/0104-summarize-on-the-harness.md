# 0104: Summarize on the harness

Status: accepted (#665); amends ADRs 0010 (which units, which tiers), 0024 (part and merge
prompts), 0057 (fifteen facts a group), 0059 (the purpose mark), 0061 (how a sentence is dropped)
and 0100 (budget rounds across roles); supersedes the prompt wording of ADR 0025

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 11.3) moves Summarize onto
the conventions every role shares (ADR 0103): the common system prompt, its own block 2 and answer
schema, the item envelope, fact lines and code blocks, checks run by the shared pipeline, and its
answers stored as claims. It also narrows what is summarized to what later roles read. This
records how #665 lands it, and what the epic left open. The agent decided these under the owner's
standing go-ahead for milestone 1F.

## Decisions

- **Which units.** The tree is built for the architecture model: a type is summarized if a
  component is anchored on it or holds it as a member (`ANCHORED_ON`, `HAS_MEMBER`, of the
  linker's `components` rule), or if it declares or holds such a type; a package if it holds one;
  every module and repository always. Derivation already leaves domain, DTO, mapper, configuration
  and util classes out of components (#271), so they are not summarized. A left-out unit's
  `CONTAINS` fact stays a fact of its parent. The run logs the units per level it summarizes and
  those the facts hold.
- **Two roles, one budget group.** Types are the role `summarize-type` on the new model role
  `SUMMARIZE_TYPES` (bulk tier); packages, modules and repositories stay `summarize` on
  `SUMMARIZE` (high-volume). Both are in the group `summarize`. Each role plans its own items,
  types first, and warms its own prompt prefix. A unit whose parts are of the other role still
  waits for them, and under a budget the group's rounds hold an item back while an item it rests
  on waits, whichever role either is: `after` now names any item of the group, not only of its
  own role (amends ADR 0100, #661). Roles on the same model share its slots, so its concurrency
  limit holds across both.
- **The request.** Block 1 is the common system prompt; block 2 is the epic's instructions and
  schema, read from `prompts/summarize.*` and held to the epic character for character by a test.
  The user turn holds one item, `I1`: a line naming the unit (`Unit: type Order in package
  com.example`), how many sentences of at most 35 words it asks for (the level's count; 1 to 3 for
  a unit with one part, which is told not to restate it), its facts in `<facts>`, a module's or
  repository's fact sheet as one `<facts>` block per group under its heading, a type's source files
  as `<code>` blocks with line numbers, and the parts' summaries in `<summaries>`. The parts'
  summaries no longer carry a purpose mark, and the repository no longer gets its own line about
  the business reader: block 2 says what every summary leads with.
- **Checking the answer.** The answer is the item's entry: a list of sentences, each with its
  `cites`. A sentence that does not fit the schema (one that cites nothing, or over 300
  characters), one past the count the item asks for, or one the shared checks reject (a reference
  not in the request, a name its citations do not show, more than 35 words, a secret) is left out
  and logged, without a repair turn. A citation written as a range (`F1-F3`) cites each reference
  in it. Only an answer that is not an entry for `I1`, or leaves no sentence, gets the repair turn,
  which lists every reason; a second failure leaves the unit without a summary, as before.
  `{"omitted": true}` is a correct answer: the unit has no summary, it is counted as omitted, and
  a rerun reuses the omission.
- **Basis and confidence are computed.** The model no longer writes a `Purpose:` line. The
  summary's purpose is evidenced when its first sentence is kept and cites a README claim, a source
  file with Javadoc, or a part's evidenced summary; otherwise inferred (amends ADR 0059). Its
  confidence is ADR 0066's, from that and the share of the answer's sentences kept.
- **Parts and merges (ADR 0024).** A unit too large for one request is still split into parts
  under its usual references and merged. Each part asks for 1 to 3 sentences; the merge shows the
  parts' kept sentences, each with the references it cites, in `<summaries>`, and may cite only
  those; it asks for the level's count.
- **Fact sheets** list at most fifteen facts a group, as the epic sets (amends ADR 0057).
- **Stored as claims.** Every summary is stored as `(unit, summary, "<level>")` of origin `llm`
  and the role of its level, its attributes holding the kept sentences, each with the IDs of the
  facts it cites (a cited part's summary resolved to the facts that summary cites), the basis and
  the model, its confidence the summary's, recorded for the snapshot before anything is composed.
  The summary vocabulary type is in `vocabulary/llm-types.json`. Roles that read facts leave these
  out, as they do Describe's texts, so a summary never feeds a prompt as a fact.
- **The fake model** answers Summarize in the epic's form: a placeholder sentence citing the first
  part's summary, and one citing the first fact or code block, or for a merge the first reference
  its parts cite; an item with neither is omitted.

## Consequences

- Fewer types are summarized: only those the model reads, which cuts Summarize's calls on every
  set (evidence in #665).
- Every summary prompt changed, so the first run after this one re-asks every summary
  (prompt version 16).
- The summary text the documents fall back on keeps its form, sentences with `[fact:]` and
  `[summary:]` markers, so composition is unchanged.
