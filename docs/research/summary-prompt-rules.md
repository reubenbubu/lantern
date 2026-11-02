# Prompts that state the rules the checks reject on

This is the research for #685: the roles' first answers were rejected 20 to 59% of the time on the
development sets, each rejection costing a repair call, and the owner asked for the prompts to be
tested in isolation and refined until they cut rejections while keeping quality.

**Depth: medium.** How to make a model follow output rules in one request is documented practice
(the provider's prompting guides), so the official documentation and two outside sources were read;
six pages, one of them in part. The open part, whether a change keeps quality, was answered by
experiment, not by sources: the experiment is below. Research is generic: web sources only; the
experiments replay requests Lantern recorded on project-2 and project-4, which are development sets.

## Question

Which wording of a role's instructions makes Claude Haiku 5.5 meet the checks of section 11 of
`docs/epics/001-knowledge-build.md` on its first answer, without losing accuracy or substance?

## Sources read

1. Anthropic, "Prompting best practices",
   <https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices>
   (its clarity, examples, XML, long-context, formatting, prefill and thinking sections). Clear,
   explicit instructions with the reason behind them; say what to do rather than what not to do;
   examples are "one of the most reliable ways" to steer format, and should be varied; long data
   first, instructions and query last; "before you finish, verify your answer against [criteria]"
   catches errors reliably.
2. Anthropic, "Prompting Claude Haiku 5.5",
   <https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-haiku-5-5>.
   Thinking is on by default with effort levels; high effort suits strict instruction following,
   lower effort skips more checks; leave room in the output limit for thinking.
3. Anthropic, "Structured outputs",
   <https://platform.claude.com/docs/en/build-with-claude/structured-outputs> (about 100,000 of
   106,000 characters). Schema-valid JSON through `output_config.format`, on Haiku 5.5 too, but no
   `minLength`, `maxLength` or numeric bounds; a `max_tokens` stop can still break the schema.
4. Anthropic, "Increase output consistency",
   <https://platform.claude.com/docs/en/test-and-evaluate/strengthen-guardrails/increase-consistency>.
   Specify the format exactly, constrain with examples; prefill is not available on current models.
5. OpenAI, "Prompt engineering",
   <https://developers.openai.com/api/docs/guides/prompt-engineering>. Identity, instructions,
   examples, then the variable context; reusable text first, for caching.
6. Xie and Lee, prompt-only length control (CAPEL), <https://arxiv.org/html/2508.13805v1>. Models
   have no internal word counter: plain counting hit an exact 10-word target about 15% of the time,
   and most misses are off by one or two. Leave a margin and let the checker hold the real limit.

Not read, and carrying no weight: Anthropic's multishot and long-context pages (the fetch returned
source 1 instead), the extended-thinking page, LIFEBench (arXiv 2505.16234) and Plan-and-Write
(arXiv 2511.01807), seen only as search snippets.

## What the experiment did

- **The summary writer in isolation.** 95 recorded type-summary requests were replayed against
  Haiku 5.5 with each prompt variant, the shared system prompt cached, and scored with a copy of
  Lantern's checks (validated against all 313 rejections the runs had logged). Variants added, one
  at a time: the first sentence stands alone and opens with a verb; bare citations; ask for 30 words
  where the check allows 35; a JSON line; a checklist at the end; and the wiring that places the
  rules after the answer schema.
- **Quality.** A blinded Claude Sonnet 5.5 judge compared each pair of answers to the same input,
  the side shown first drawn at random: accuracy first (every statement supported by the input),
  then substance, then clarity.
- **The other roles on real runs.** Each role's prompt was changed the same way and project-2 and
  project-4 were analyzed again with Haiku, comparing first-answer rejections per role; the judge
  then compared the old and new answers of every re-asked item.

## Findings

- **Summaries: rules stated with an example each cut repairs from 40 of 95 to 0**, and cost fell
  from $0.139 to $0.094, because a repair costs a second call. On the real runs, 1 repair in 215
  summaries on project-4 and 0 in 163 on project-2. The judge found the new summaries as good.
- **Thinking stays at medium effort.** Low effort and thinking off were cheaper ($0.064, $0.061)
  with 1 and 3 repairs, but the judge found them less accurate (11 against 3, and 13 against 6,
  inaccuracy losses). Plain text instead of JSON would save about 2%; thinking is about 42% of the
  cost. JSON stays.
- **Lead investigations: first-answer rejections fell from 59% to 20%** on both sets, by stating
  that each name must appear in a cited range, that the repository is the name before the first
  colon, and that a tool the cited code does not show must not be named at all.
- **Flow walkthroughs: repairs fell from 24 to 3 on project-4 and from 2 to 0 on project-2.** Asked
  for 70 words of the check's 80, they came out thinner and skipped steps (the judge preferred the
  old 42 to 21). Asked for 75 words covering every step, saying only what the steps show, and, for
  a queued message, that it was sent rather than what its receiver does, the judge preferred the new
  34 to 29, with fewer inaccurate statements (57 against 66).
- **Asking for much less than the check allows, and banning the words of a part's name, cost
  substance.** The second round asked for 20 words where the check allows 25, 6-word intents where
  it allows 8, and said "do not repeat the words of its name". The judge preferred the old unit
  descriptions 16 to 5 and counted more inaccurate statements in the new (29 against 18): the model
  swapped the code's own terms for vaguer ones ("group" for organization, "forgotten-secret" for
  password) and repeated one generic intent across many interactions. The check behind that rule
  only rejects a sentence whose first six words mostly repeat the name. With that clause worded as
  "do not open by echoing its name", the code's own terms encouraged, and the limits at 22 of 25 and
  7 of 8 words, the judge preferred the old unit descriptions 10 to 8 with 2 ties, and found fewer
  inaccurate statements in the new (17 against 22).
- **The name check itself rejected good sentences.** "Stores and returns each payment's transaction
  history in time order" and 13 others like it were rejected because two of their first six words
  were the part's name's. The check now also requires the sentence to add fewer than three content
  words beyond the name (plurals matched, empty words such as "handles" or "service" not counted),
  so "Handles orders for the service" is still rejected.

- **The smaller roles, judged on all four development sets (#687).** Recorded requests of
  project-1 to project-4 were replayed with the prompts from before #685 and judged against the new
  answers to the same input. The new prompts were no less accurate:
  - element descriptions, 35 items in 13 calls: preferred old 4, new 2, ties 7; inaccurate
    statements old 4, new 2;
  - flow significance, 16 items: old 4, new 7, ties 2; inaccurate old 13, new 13;
  - group names, 12 items (every group the four sets have): old 2, new 2, tie 1; inaccurate old 5,
    new 1.

  The same run found the significance and group-name answers writing a reason as a string with its
  citations beside it. The prompts now show the reason's JSON shape, and no answer of the four sets
  made that slip again (3 repairs in 63 items: one stray bracket, one citation not in the input, one
  technology the citations did not show).

## Options

1. **State each rule the check enforces, with a good and a bad example, a margin under each word
   limit and a checklist at the end** (sources 1, 4 and 6). Cuts rejections; the margin must stay
   small and the rule must say exactly what the check rejects, or the model over-obeys.
2. **Structured outputs** (source 3). Guarantees the JSON's shape, which is a small share of the
   rejections, but not the word limits, openings or citations, which stay with the checks; a
   separate change if JSON faults stay frequent.
3. **Higher thinking effort** (source 2). More reliable rule-following at a higher cost per call;
   not needed once the rules are stated.

## Recommendation

Option 1, with two limits learned from the experiment: ask for about 90% of a word limit (30 of
35, 22 of 25, 7 of 8, 18 of 20, 75 of 80), not less; word each rule as narrowly as its check: "do
not open by echoing its name", not "never use its words", with the code's own terms encouraged; and
judge every change for quality against the old answers, since rejections alone rewarded thinner
texts. The experiment cost about $14 of real calls: the lab $0.72, the judge about $8.80, the runs
about $4.20; #687 added about $3.30 (a recorded run of the four sets $1.76, replays $0.13, the judge $1.39).

## What it changes

Section 11 of `docs/epics/001-knowledge-build.md`: the role instructions state the rules their
checks reject on, ask for less than the check accepts, and end with a checklist; and the Form
check's restated-name rule also requires the sentence to add little beyond the name (both amended by
#685).
The bar is unchanged.
