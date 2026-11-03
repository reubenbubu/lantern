# A 0 to 10 quality score from yes/no checks

3 Nov 2026, for #760.

**Depth: medium.** How to score short generated text from binary checks is well documented: checklist evaluation papers, tools that weight assertions, and style guides for short descriptions. The choice is a scheme, not an open research question. A read-only `sonnet` subagent researched it; four pages were read in full, two more as abstracts only.

## Question

Each text an LLM role writes needs a quality score from 0 to 10: a responsibility sentence, an intent phrase on a line, a group's name, or a summary or narrative. On 3 Nov the owner decided the score is built from weighted yes/no checks and is never asked of a judge directly. The questions are:
- Which checks make up the score?
- How are they weighted and combined?
- How does a scorecard show where the points were lost?

## Findings

1. **Binary questions make judges agree; fitted weights did not help.**
   - Source: CheckEval ([arxiv.org/html/2403.18771](https://arxiv.org/html/2403.18771), read up to its appendices).
   - Likert-scale judges "suffer from rating inconsistencies". CheckEval asks only yes/no questions and scores "the proportion of 'yes' responses".
   - Across evaluator models, agreement rose from 0.09 (G-Eval) to 0.48 (Krippendorff's alpha, SummEval).
   - Weights learnt by regression helped "a couple of evaluator models … but most others did not", so the paper kept equal weights.
   - Limits it states:
     - not every criterion is a clean yes or no;
     - for poor texts, strict binary questions "often [result] in uniformly low scores".
2. **One requirement per question.**
   - Source: TICK ([arxiv.org/abs/2410.03608](https://arxiv.org/abs/2410.03608), abstract only).
   - YES/NO questions per requirement raised agreement with human preferences from 46.4% to 52.2% over direct scoring.
3. **A gate is a standard pattern.**
   - Source: DeepEval's DAG metric ([deepeval.com/docs/metrics-dag](https://deepeval.com/docs/metrics-dag)).
   - It chains binary judgement nodes, each verdict setting a score or the next node. A root gate can "fail immediately if a requirement is missing; otherwise continue evaluating quality".
4. **Weighted assertions.**
   - Source: Promptfoo's assertions ([promptfoo.dev/docs/configuration/expected-outputs](https://www.promptfoo.dev/docs/configuration/expected-outputs/)).
   - Assertions combine by weighted average ("The `weight` property … determines the relative importance").
   - `metric` tags roll them up by name, and `weight: 0` reports a check without scoring it.
5. **What a short description should do.**
   - Source: Google's developer documentation style guide, API reference comments ([developers.google.com/style/api-reference-comments](https://developers.google.com/style/api-reference-comments)).
   - "Briefly state what action the method performs." Start with a verb.
   - Say what cannot be deduced from the name, do not repeat the class name, keep it as brief as possible, and put the main point first.

## Options

| Option | How | Trade-offs |
| --- | --- | --- |
| Equal weights | 10 times the share passed | Simplest (CheckEval). A fluent but empty text still scores about 6. |
| Weighted sum | Substance checks weigh more | Easy to report. The weights are judgement, so keep them few and coarse. |
| Weighted sum and a gate cap | As above; failing the core check caps the score | Keeps "fluent but says nothing" low, and every loss stays visible. |
| Graph with early exit | Gate first, quality after | Exact control, but it hides the other losses and needs a graph per kind of text. |
| Penalties | Subtract for defects | Needs clipping; a failed "no filler" check does the same. |

## Recommendation

A weighted sum with a gate cap:
- **Weights.** Weights of 1 to 3, summing to 10 per kind of text.
- **Judged checks.** These are the wording judge's two existing questions: "says what it does" (weight 3, the gate) and "specific to this system" (weight 2 or 3).
- **The gate.** A text that fails the gate scores at most 4.
- **Deterministic checks** make up the rest: within the requested length, the requested number of sentences, an opening that is not a pronoun or a restated kind, an intent that opens with a verb and names no protocol, and no filler phrases.

The research also suggested judged checks for "uses the code's own terms" and "no padding". Those would need new judge questions and labels (#757), so for now "no padding" is a deterministic filler list.

The scorecard gives per role:
- the mean score;
- the number of texts capped by the gate;
- the points lost per check, most first.

## What it changes

- ADR 0118 adopts the scheme, in `prompts/quality.json`.
- The design (LLM integration) states it.
- Its weights are reasoned, not fitted: CheckEval found fitting gains nothing for most judges. They change only through an issue.

## Sources

Read:
- CheckEval ([arxiv.org/html/2403.18771](https://arxiv.org/html/2403.18771)), the first 100,000 of 134,911 characters;
- DeepEval DAG ([deepeval.com/docs/metrics-dag](https://deepeval.com/docs/metrics-dag));
- Promptfoo, expected outputs ([promptfoo.dev/docs/configuration/expected-outputs](https://www.promptfoo.dev/docs/configuration/expected-outputs/));
- Google developer documentation style guide, API reference comments ([developers.google.com/style/api-reference-comments](https://developers.google.com/style/api-reference-comments)).

Abstract only, little weight: TICK ([arxiv.org/abs/2410.03608](https://arxiv.org/abs/2410.03608)); HealthBench ([arxiv.org/abs/2505.08775](https://arxiv.org/abs/2505.08775)).

Not read, no weight: the HealthBench full text; the end of CheckEval; Google's "summaries" page and Microsoft's concise-writing page (both 404); LLM-Rubric; Ragas aspect critic.
