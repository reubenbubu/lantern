# Calibrating and measuring the LLM roles

This is the research for #662, the first issue of milestone 1F "Roles on the harness".

**Depth: high.** It is a milestone's outcome and an open question of how to measure: it sets the rules for packet-size calibration, the judge, scoring "unknown" answers, matching flows and risks, and when an improvement can be claimed (`docs/epics/001-knowledge-build.md`, sections 10, 11 and 13). Twenty-three sources were read: twenty in full, three in substantial part (sources 5, 9 and 10, each to about 100,000 characters of 113,000 to 145,000). Two of the read sources (7 and 8) turned out to hold nothing on the question and carry no weight. Nine more were looked for and not read; they carry no weight either (see Not read). Research is generic: web sources only, never the test sets.

## Question

Lantern has about five development systems and a few dozen items per role on each, so roughly 100 to 200 items per role, and single-digit dollars per role. How should each role be calibrated and scored so that its score can be trusted?

1. Packet size against accuracy.
2. Judging descriptions.
3. Scoring "unknown" answers.
4. Matching flows and risks to a sparse reference.
5. Baselines, run-to-run variance and when an improvement can be claimed.

## Summary of findings

- **A one-point margin cannot be measured at this size.** With 150 right-or-wrong items at 80% accuracy, one score has a standard error of about 3.3 points, so its 95% interval is about ±6.4 points. A paired comparison on the same items resolves differences of about 5 to 7 points. The epic's "within one point of size 1" would be decided by noise.
- **The right test is non-inferiority on paired items.** Compare each item's answer at size k with its answer at size 1, and measure the noise floor by asking size 1 twice: runs at temperature 0 are not deterministic.
- **The literature agrees on the shape of the batching curve.** Short, uniform classifications lose little up to 16 to 32 items (1 to 3 points for GPT-4-class models). Answers that select or list items, reasoning and generation lose much more. Position inside the packet matters.
- **LLM judges are usable once validated.** They have position, length and self-preference biases. Grading one dimension per call, with a binary or short scale and an "unknown" exit, by a judge other than the writer, works best. Validate the judge against human labels (true positive and true negative rate), then correct its raw pass rate. BLEU and ROUGE agree poorly with people on code summaries.
- **Abstention needs three outcomes: correct, wrong and abstained**, with a penalty for a wrong answer that matches the confidence the role's instruction demands, and reference items whose right answer is "unknown".
- **Matching needs fixed rules.** A flow by its trigger plus participant overlap, one to one. A risk by subject plus category, as the OWASP Benchmark matches by file plus weakness type. Decoys in the reference make false positives measurable. An unmatched prediction is unjudged, not wrong, until adjudicated once, as TREC pooling does.

## Sources read

1. Miller, "Adding Error Bars to Evals" (Anthropic), <https://arxiv.org/html/2411.00640v1>. Standard errors from the central limit theorem, clustered standard errors, paired differences, resampling K times rather than lowering temperature, power and minimum-detectable-effect formulas. Its example: about 969 items to detect 3 points.
2. Zheng et al., "Judging LLM-as-a-Judge with MT-Bench", <https://arxiv.org/html/2306.05685>. GPT-4's verdict held when the two answers were swapped only 65% of the time; Claude-v1 and GPT-3.5 preferred padded answers 91% of the time; GPT-4 rated its own answers about 10% higher than people; GPT-4 agreed with experts 85%, experts with each other 81%. Mitigations: swap and count a win only when consistent; reference-guided grading cut math grading failures from 70% to 15%.
3. Liu et al., "G-Eval", <https://arxiv.org/html/2303.16634>. Chain of thought plus form filling reached 0.514 Spearman with people on SummEval, ROUGE about 0.17 to 0.21, BERTScore 0.225. Integer scores tie often. The judge preferred LLM-written summaries even when people did not.
4. Wu et al., "Can LLMs Serve as Evaluators for Code Summarization?", <https://arxiv.org/html/2412.01333v1>. BLEU, ROUGE-L, METEOR and BERTScore reach 0.48 to 0.64 Spearman with people on Java summaries; a GPT-4 judge playing a code reviewer 0.816 without a reference, 0.822 with one. Four examples added about 3.6 points; "analyze, then rate" beat "rate, then explain".
5. "Software Architecture Recovery with Information Fusion", <https://arxiv.org/html/2311.04643> (to about 100,000 of 113,000 characters). MoJoFM favours many small clusters; a2a scores poor architectures too high; c2c matches clusters by overlap at thresholds of 10 to 66%, the authors chose 66%, and at 10% the score was always 1.
6. OWASP Benchmark wiki, <https://wiki.owasp.org/index.php/Benchmark>. Every case is a true or false vulnerability in an expected-results file; a finding counts when it has the expected type in the case's file; the score is TPR − FPR, so a tool that flags everything scores 0; decoys punish over-reporting.
7. OWASP Benchmark project page, <https://owasp.org/www-project-benchmark/>. No scoring detail; no weight.
8. OWASP BenchmarkUtils README, <https://github.com/OWASP-Benchmark/BenchmarkUtils>. How to run the scorecard generator only; no weight.
9. Wen et al., "Know Your Limits: A Survey of Abstention in LLMs", <https://arxiv.org/html/2407.18418> (to about 100,000 of 136,000 characters). Abstention precision, recall and F1, coverage, accuracy on the answered items, risk-coverage curves, over-abstention rate; combine several, never one alone.
10. Kalai et al., "Why Language Models Hallucinate" (OpenAI), <https://arxiv.org/html/2509.04664> (to about 100,000 of 145,000 characters). Right-or-wrong grading always rewards guessing. With a stated confidence target t, a wrong answer costs t/(1−t), a correct one earns 1, "I don't know" 0.
11. Wei et al., "SimpleQA" (OpenAI), <https://arxiv.org/html/2411.04368>. Three grades (correct, incorrect, not attempted); overall correct, correct among attempted, and an F-score that still rewards guessing, so a penalty is suggested. A hedge that contradicts the reference is incorrect.
12. Lin et al., "BatchPrompt", <https://arxiv.org/html/2309.00384>. GPT-4 on BoolQ: 90.6% at size 1, 89.1% at 16, 87.8% at 32; GPT-3.5 fell to 70.0% at 32. Accuracy depends on position; reshuffling and voting recovers it; not suited to generation.
13. "Multi-item classification", <https://arxiv.org/html/2406.10786v1>. Batched classification lost 3.2 points on average; GPT-4 kept at least 90% of single-item accuracy; answers that list indices were 12 to 32 points worse.
14. Liu et al., "Lost in the Middle", <https://arxiv.org/html/2307.03172>. With 20 documents, 75.8% when the answer was first, 53.8% in the middle, 63.2% last. A longer window did not help.
15. Atil et al., "Non-Determinism of 'Deterministic' LLM Settings", <https://arxiv.org/html/2408.04667>. Ten runs at temperature 0 with a fixed seed varied in accuracy by up to 15%; parsed answers agreed more than raw text, not always. Report the spread.
16. Anthropic, "Define success criteria and build evaluations", <https://platform.claude.com/docs/en/test-and-evaluate/develop-tests>. Grade by code where possible, by an LLM where needed; give the grader a rubric and let it reason first; grade with another model than the writer.
17. Anthropic, "Demystifying evals for AI agents", <https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents>. Check model graders against experts, give the judge an "Unknown" exit, one judge per dimension, several trials, read the transcripts.
18. promptfoo model-graded assertions, <https://www.promptfoo.dev/docs/configuration/expected-outputs/model-graded/>. Rubric grading, G-Eval, factuality against a reference, select-best; scores 0 to 1 with thresholds and a separate grader model. No calibration guidance.
19. Panickssery et al., "LLM Evaluators Recognize and Favor Their Own Generations", <https://arxiv.org/html/2404.13076>. GPT-4 recognised its own summaries 73.5% of the time, and preferred them the more it did; swapping options reversed GPT-4's preference 25% of the time. Average both orders, hide authorship.
20. Dubois et al., "Length-Controlled AlpacaEval", <https://arxiv.org/html/2404.04475>. Asking for concise or verbose output swung win rate from 22.9% to 64.3%; regressing on length cut the swing to 41.9 to 51.6%.
21. Lee et al., "How to Correctly Report LLM-as-a-Judge Evaluations", <https://arxiv.org/html/2511.21140v1>. Correct the judge's raw pass rate p with θ = (p + TNR − 1)/(TPR + TNR − 1), with an interval that includes the calibration set's uncertainty; about 200 labels per class give ±0.05; start with about 10 labels per class.
22. Husain, "validate-evaluator", <https://skills.sh/hamelsmu/evals-skills/validate-evaluator>. About 100 expert-labelled cases, half pass and half fail, split train, dev and test; iterate on dev until TPR and TNR pass 90% (80% minimum), run test once.
23. Yilmaz and Aslam, "Inferred AP", <https://www-nlpir.nist.gov/projects/tv2006/infap/inferredAP.pdf>. Treating unjudged documents as non-relevant biases the measures; judging a random sample tracks the true values (ρ 0.99 with 30% of judgments).

## Not read (no weight)

- Cheng et al., "Batch Prompting" (arXiv 2301.08721), cited by the epic.
- arXiv 2603.22608 (multi-instance work) and Chroma's "context rot", cited by the epic.
- "Judging the Judges" (arXiv 2406.12624).
- "Balanced Accuracy: The Right Metric for Evaluating LLM Judges" (arXiv 2512.08121).
- Lenarduzzi et al., six static analysis tools (arXiv 2101.08832).
- "Automated Customized Bug-Benchmark Generation" (arXiv 1901.02819).
- Buckley and Voorhees, bpref (SIGIR 2004), known only through source 23.
- DeepEval, RAGAS and OpenAI evals documentation.

## Findings

### 1. Packet size against accuracy

- **The epic's margin is below the noise.** At n = 150 and p = 0.8, one score's standard error is √(0.16/150) = 3.3 points (source 1). Paired, the variance is that of the per-item differences: if 10% of items change answer between sizes, the standard error of the difference is √(0.10/150) = 2.6 points, and the smallest difference detectable at 80% power is 2.8 × 2.6 ≈ 7 points. Detecting 5 points needs about 7.84 × 0.10 / 0.05² ≈ 310 items.
- **Size 1 is not a fixed point.** At temperature 0, accuracy moved by up to 15% between runs (source 15), so part of any gap is rerun noise. A second size-1 run measures that floor.
- **Items cluster by system**, with only about five clusters. Clustered standard errors (source 1) are unreliable with so few; this is our reasoning, not a source's. Report each set, and check the direction agrees across sets.
- **What others found.** Short uniform judgements hold up well (sources 12, 13); answers that list or select items lose 12 to 32 points (source 13); generation is a poor fit for batching (source 12); middle positions fare worst (sources 12, 14). No source chose a size by a fixed point margin: BatchPrompt weighed accuracy and token cost together.

### 2. Judging generated descriptions

- **Biases:** position (65% consistency on swap, source 2), length (23% to 64% win rate, source 20), self-preference (sources 2, 19), preference for LLM-written text (source 3).
- **Pointwise or pairwise.** Pointwise scales and gives an absolute score but ties and drifts with the judge; pairwise is more sensitive but more position-biased, so both orders and a win only when they agree (source 2).
- **Rubric or reference.** For code summaries a reference added little (0.816 against 0.822, source 4); for reasoning it helped much (source 2). Lantern's descriptions are factual claims about code, so the reference catalog serves as facts that must not be contradicted, not text to match.
- **Rubric design.** One dimension per call, a binary or short scale, reasoning before the verdict, an "unknown" exit (sources 4, 16, 17).
- **BLEU and ROUGE fail** on code summaries (sources 3, 4).
- **Validating the judge.** TPR and TNR on expert labels, about 100 balanced cases (source 22); correct the raw pass rate and report the interval (source 21). About 200 labels per class give ±5 points, more than one owner can label, so the interval will be wide and must be shown.
- **The judge's own variance.** Asking it K times and averaging per item removes much of it; lowering temperature does not (source 1).

### 3. Coverage and abstentions

- Right-or-wrong grading rewards guessing (source 10). Use three outcomes, correct, wrong, abstained (source 11), and report coverage, accuracy on the answered items, and a penalized score correct − p·wrong with p = t/(1−t) (sources 9 to 11), t chosen to match the role's instruction.
- A correct abstention is scorable only if the reference marks items whose right answer is "unknown", as OWASP's decoys (source 6) and abstention benchmarks' unanswerable questions (source 9) do.
- Report over-abstention: abstaining on items the reference can answer (source 9).
- An item not asked for budget is not an abstention; count it apart, or the score mixes spend with judgement.

### 4. Matching flows and findings

- **Overlap matching depends on its threshold:** at 10% c2c scored unrelated architectures 1, the authors chose 66% (source 5). For flows the trigger is a hard key and the participants a set: match by trigger, then by participant overlap at a high threshold, one to one.
- **OWASP matches by location plus type and scores TPR − FPR with decoys** (source 6). For risks: subject element plus category, with decoys so the false-positive rate is measurable.
- **Sparse references.** Treating unjudged items as wrong biases precision against a system that finds new things (source 23). An unmatched finding is unjudged until adjudicated once, then added to the reference.

### 5. Baselines and comparison cost

- Replay makes rescoring free but freezes one draw of a non-deterministic output (source 15).
- Compare on the same items: pairing removes shared variance at no cost (source 1).
- The smallest detectable difference is 2.8 × √(Var(d)/n) (source 1): about 7 points at n ≈ 150 with 10% discordant items.
- A second baseline run gives the noise floor (source 15); read the transcripts of disagreeing items before believing a score (source 17).

## Options

| Topic | Option | For | Against |
| --- | --- | --- | --- |
| Packet size | A. The epic's one-point rule | Simple | Below the noise; picks a size at random |
| | B. Non-inferiority on paired items, margin δ, against a size-1 rerun | Sound; uses the planned recordings | One extra size-1 run; δ must be about 5 points or more |
| | C. Smallest size that saves enough cost | No statistics | Ignores accuracy |
| Judge | Pointwise, binary, one dimension per call | Scales; absolute; easy to validate | Coarse; ties |
| | Pairwise, both orders | Sensitive for A/B tests | Two calls an item; no absolute score |
| | BLEU, ROUGE, BERTScore | Free | Weak agreement with people |
| Abstention | Right or wrong only | Simple | Rewards guessing |
| | Three outcomes, penalty t/(1−t) | Matches the instruction | Needs "unknown" items in the reference |
| Flows | Exact trigger and participants | Strict | Misses near-equal flows |
| | Trigger plus participant overlap ≥ 2/3, one to one | Tolerant; follows c2c | One threshold to choose |
| Risks | Recall only | Cheap | Reporting everything wins |
| | Subject plus category, decoys, adjudication | Precision measurable; references grow | One-time labelling |
| Improvement | One run against the last scorecard | Cheap | Up to 15% noise |
| | Paired interval, direction per set | Trustworthy | Under about 7 points cannot be claimed |

## Recommendation

**Packet size** (replaces the one-point rule)

1. Per role, record size 1 twice and sizes 5, 10 and 20 once, on the same items. The second size-1 run is the noise floor; if the budget is short, rerun size 1 on two sets only.
2. For each size k, the paired difference of per-item scores against size 1, with a 95% bootstrap interval over items, overall and per set.
3. Keep the largest k for which: the interval's lower bound is above −δ, δ = 5 points on the 0 to 100 role score; the discordance against size 1 is at most the two size-1 runs' discordance plus 5 points; no set drops by more than 2δ.
4. Sizes pass in order: if 10 fails, 20 is not considered.
5. For roles that write prose (Describe, flow narratives), check accuracy by position at size 20; if the middle positions are more than δ worse, cap below 20.
6. Record n, each pair's discordance, the intervals and the cost in the role's ADR.

**Abstention**

1. Score each item correct, wrong or abstained; report coverage, accuracy on the answered items, and correct − p·wrong.
2. p from the role's instruction: Resolve ("only when the code or configuration shows it", t = 0.75) p = 3; flow significance and Describe intents (t = 0.5) p = 1.
3. Over-abstention reported; items not asked for budget reported apart, neither abstained nor missed.
4. Where a reference marks items whose right answer is "unknown", abstaining on them is correct and a pick is wrong.

**Flows and risks**

1. A predicted flow matches a reference flow when its trigger is the same entry point and the overlap of participants (shared over the larger set) is at least 2/3; one to one, best overlap first. Recall and precision of the significant set.
2. A risk matches a reference risk by subject element and category. An unmatched one is unjudged: listed in the scorecard with its count, precision computed over judged ones only, adjudicated once and added to the reference. Where a reference carries decoys, TPR − FPR is reported too.

**Claiming an improvement**

1. Scorecards compare runs on the same items, with a bootstrap interval of the paired difference and the direction per set.
2. An improvement is claimed when the interval excludes 0 and most sets agree in direction; no regression when its lower bound is above −δ.
3. Each role's ADR states its smallest detectable difference.
4. A decision that rests on one new recording records twice, or reruns the disagreeing items, so the noise floor is known.

**Judge** (for the judge of #277 and any judged row)

1. Pointwise, one dimension per call, binary or three levels with an "unknown" exit, reasoning before the verdict, the reference's facts as checks ("contradicts a reference fact" fails).
2. A different model from the writer; authorship hidden.
3. Text length capped and the mean length shown beside the score.
4. Pairwise, both orders, only for A/B tests of a prompt change.
5. Validated once before its score is used: about 60 owner labels per dimension, half pass and half fail, 20 to tune and 40 held out; TPR and TNR at least 0.8 on the held-out labels; the corrected score θ reported with its interval (±10 points expected at this size).
6. No BLEU, ROUGE or BERTScore for wording.

## What it changes

- **Epic, section 10, "Packet sizes are calibrated".** The one-point rule is replaced by the non-inferiority rule above. Changed in this PR.
- **Epic, section 13.** The calibration bullet gains the second size-1 run, the paired bootstrap and the position check; the scoring rules gain three outcomes per item, the flow and risk match rules, unjudged findings, and the rule for claiming an improvement. Changed in this PR.
- **#529** (calibration and baseline recordings) takes the new rule, and the extra size-1 recording in its budget.
- **The judge (#277, milestone 2D.2)** takes the judge rules. The Describe and Name groups rows' judge scores wait for it; their coverage rows do not.
- **Not changed without the owner:** "unknown" items and decoy risks added to the references, and the owner's labels that validate a judge. Both are labelling work by the owner, so they are a direction question; until settled, the rules above apply where references hold such items and are reported as not measured where they do not.
- **The support judge (11.7).** Its prompt is unchanged; an "unknown" exit would be a prompt change, made only through an issue that shows its effect.
- **`docs/quality-bar.md` and `docs/design.md`:** nothing. These are rules of measurement, which the epic owns while it is active (section 13); the bar already requires unknowns to be stated as unknown.
