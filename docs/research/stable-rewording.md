# Keeping re-asked answers stable

**Question.** When one input of an item changes, how should an incremental LLM pipeline keep the
unchanged parts of the new answer stable, so that only the parts resting on the change are worded
anew and the items that show the answer keep their cache keys? (#722)

**Depth: high.** An open design question: a choice between approaches that decides cache
correctness and a rule of the design. A read-only `opus` research subagent read the sources below
in full (where marked) and returned the findings; this note is written from them.

## What the sources say

**Build systems and incremental computation**

- *Build Systems à la Carte* (Mokhov, Mitchell, Peyton Jones, ICFP 2018) defines **minimality**
  (a task runs only if it depends, transitively, on an input that changed) and **early cutoff**
  (a task whose result is unchanged does not re-run its dependents). Its section 6.3 treats
  **non-deterministic tasks**: correctness weakens from "the result equals recomputation" to "the
  result is *one possible result* of recomputing the task". Keeping an earlier valid output is
  therefore correct. Early cutoff needs equal outputs, which a task that rewords every run never
  gives.
- **Salsa** (the engine behind rust-analyzer) calls the same mechanism **backdating**: a query
  re-run to the same value keeps its old revision, so its dependents are reused.
- **rust-analyzer, "Durable Incrementality"**: early cutoff works only when outputs keep out
  volatile detail. The syntax tree shields higher layers from whitespace edits only "if you don't
  store positions in the AST". For an LLM, *wording* is that volatile detail.

**Determinism of LLM APIs**

- **Anthropic's glossary**: even at temperature 0, "identical inputs might produce different
  outputs across API calls".
- **OpenAI's `seed`** is best effort; determinism is not guaranteed and changes with the serving
  infrastructure.
- **Thinking Machines** traces non-determinism at temperature 0 to kernels that are not batch
  invariant; with invariant kernels 1,000 completions were identical. That covers *identical*
  inputs on one fixed stack, at a cost in speed, and says nothing about a slightly changed input.
- **Atil et al.** (arXiv 2408.04667): at temperature 0, raw outputs over 10 runs often disagree
  (agreement as low as 0 to 46%), parsed answers much less so (96.4%); longer outputs are less
  stable, and errors compound across pipeline stages.
- **Sclar et al.** (arXiv 2310.11324, abstract read): meaning-preserving changes to a prompt's
  format move accuracy by up to 76 points. A deterministic model would still reword after a
  one-fact change, because the prompt changed.

**Incremental documentation and edit-style generation**

- **RepoAgent** (arXiv 2402.16667, partly read) regenerates documentation only for affected
  objects, and deliberately ignores some dependency changes: a lossy cutoff by design. It does not
  say how unchanged text inside a regenerated document is kept.
- **HatCUP** (arXiv 2205.00600, abstract read) updates a comment by edit actions on the old one
  instead of regenerating it, and beats generation from scratch by 53.8% in accuracy.
- **Aider, unified diffs**: asking for edits instead of whole files raised a refactoring score from
  20% to 61%, but edits still fail without tolerant application. Models keep text better when
  asked for edits, not reliably.
- **OpenAI Predicted Outputs** take the old text as a prediction to cut latency; the model is free
  to diverge, so it promises no stability.

**Semantic caching**

- **Thoughtworks Radar on GPTCache**: similarity-keyed caches give false hits and misses; assess
  carefully before use.

**Where they disagree.** Determinism is achievable on a fixed stack (Thinking Machines) but hosted
APIs do not provide it (Anthropic, OpenAI); for Lantern, on hosted models, the vendors decide.
Build systems key on every input and cut off only after recomputing; RepoAgent ignores a class of
changes by design.

## Options

| Option | Stability | Correctness | Cost | Verdict |
| --- | --- | --- | --- | --- |
| 1. Determinism levers (temperature 0, seed, prompt cache) | Noise on identical prompts only; the prompt did change | Unchanged | None | Hygiene, not a solution |
| 2. Fuzzy downstream keys (embeddings, or cited IDs instead of wording) | Good | A stale hit is silent; documents quote wording that no longer exists upstream | Low | Rejected: it blurs the key instead of fixing the volatility at its source |
| 3. One item per claim | Good | No coherence across sentences; claims exist only after the answer | Many more calls | Only where the structure is fixed in advance |
| 4. Ask for edits: show the old texts and what changed, ask keep, replace, delete or add | Better than regeneration, not reliable (Aider) | Good: the model sees the change and can retract a stale claim | One call, shorter output; two prompt modes | A second step if measurement shows the risks of 5 |
| 5. Carry forward by citation: re-ask as normal, then keep every old text whose cited inputs are all unchanged and add new texts only where they cite something new | Exact, enforced by code | "One possible result"; risks below | No extra call | **Recommended** |
| 6. An LLM judge of equivalence as the cutoff | Judge-dependent | Its errors are silent | A call per re-ask | Rejected |

Risks of option 5, and their mitigations:

- **A claim made false by a fact it does not cite**, such as "the only writer of the table" when a
  second writer appears. Mitigation: a claim about a whole set cites the facts it ranges over; what
  remains is a residual risk.
- **Duplicates or gaps**: a new sentence restates a kept one, or a kept one refers to one dropped.
  Mitigation: a new sentence that adds no new cited line is not added; texts are fairly
  self-contained.
- **Uncited texts** have nothing to keep them by: never carried.
- **A change of prompt version or model** carries nothing.

## Recommendation

Option 5, enforced in code, with downstream keys kept exact on the full wording. With upstream
wording stable, ordinary early cutoff stops the cascade without weakening any cache key. Option 4
is the fallback if measurement shows stale or duplicated claims in merged answers. It holds while
every text cites what it rests on, inputs have stable identities, and answers are sets of fairly
self-contained texts.

**Testing it with a fake model.** A fake whose content is deterministic but whose wording changes
every call; after a one-input change, every text citing only unchanged inputs is byte-identical,
those citing the change are new, the items that show the answer are asked only if a text they show
changed, and an unchanged rerun asks nothing.

## What it changes in Lantern

- `docs/design.md`, LLM integration: a re-asked answer keeps, word for word, each text whose cited
  inputs are all still shown unchanged; downstream fingerprints stay exact (ADR 0126).
- Not adopted here: option 4's edit-style re-ask, until a real run shows the context risks.

## Sources

Read:

- Mokhov, Mitchell, Peyton Jones, *Build Systems à la Carte*, ICFP 2018, pp. 1–8 and 22–24:
  https://www.microsoft.com/en-us/research/wp-content/uploads/2018/03/build-systems.pdf
- Salsa book, "The red-green algorithm": https://salsa-rs.github.io/salsa/reference/algorithm.html
- rust-analyzer blog, "Durable Incrementality" (2023):
  https://rust-analyzer.github.io/blog/2023/07/24/durable-incrementality.html
- Thinking Machines, "Defeating Nondeterminism in LLM Inference":
  https://thinkingmachines.ai/blog/defeating-nondeterminism-in-llm-inference/
- OpenAI Cookbook, "Reproducible outputs with the seed parameter":
  https://developers.openai.com/cookbook/examples/reproducible_outputs_with_the_seed_parameter
- Anthropic, Glossary (temperature): https://platform.claude.com/docs/en/about-claude/glossary
- Atil et al., "Non-Determinism of 'Deterministic' LLM Settings":
  https://arxiv.org/html/2408.04667v5
- Aider, "Unified diffs make GPT-4 Turbo 3X less lazy": https://aider.chat/docs/unified-diffs.html
- OpenAI, Predicted Outputs: https://developers.openai.com/api/docs/guides/predicted-outputs
- Thoughtworks Technology Radar, GPTCache:
  https://www.thoughtworks.com/radar/languages-and-frameworks/gptcache

Read in part (weight limited to what was read):

- Sclar et al., arXiv 2310.11324 (abstract): https://arxiv.org/abs/2310.11324
- Zhu et al., HatCUP, arXiv 2205.00600 (abstract): https://arxiv.org/abs/2205.00600
- Luo et al., RepoAgent, arXiv 2402.16667 (first part of the paper):
  https://arxiv.org/html/2402.16667v1

Found but not read (no weight): CUP (ASE 2020); CCT5 (arXiv 2305.10785); Ouyang et al. on
ChatGPT's non-determinism in code generation (arXiv 2308.02828); arXiv 2601.06118 and 2604.22411;
practitioner posts on semantic caching.
