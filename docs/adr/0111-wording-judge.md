# 0111: The wording judge

Status: accepted (#668)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 13) scores three roles by a
judge of their wording: Describe's responsibilities and intents, the system's key fact, and group
names. Until now those rows reported coverage only and waited for the report judge of #277
(milestone 2D.2). #668 brings a wording judge into milestone 1F, built to the judge rules of
`docs/research/role-measurement.md` (Judge): pointwise, one dimension per call, an "unknown" exit,
reasoning before the verdict, a different model from the writer, authorship hidden, length shown,
and validated against the owner's labels (TPR and TNR at least 0.8 on held-out labels) before its
score counts, reported as the corrected score with its interval. The support judge (ADR 0101)
already checks whether a text's citations support it; that stays its job.

## Decisions

- **A measuring step, off by default.** The judge runs at the end of `analyze` only when
  `lantern.judge.wording=true` (`scripts/score-testsets.sh --judge-wording`). It judges every text
  of origin llm that holds in the snapshot and is a responsibility, an intent, the key fact or a
  group's display name, and writes each text once per dimension, with its verdict, to
  `wording.jsonl.gz` beside the documents. It changes no fact and no document: it measures for the
  scorecard. Its verdicts are not stored as facts, since nothing in the model reads them.
- **Two dimensions, one call each.** Each dimension is its own harness role, `wording-judge-role`
  and `wording-judge-specific`, so a request grades one dimension only.
  - `role`: the text says what the element does for the system, not only its name, kind or
    technology.
  - `specific`: the text is not true of almost any Spring application.
  - Each item shows what kind of text it is (such as "responsibility of a service"), the element
    it is about and the text; never who wrote it. The answer is `{reasoning, verdict}`, reasoning
    first, the verdict `pass`, `fail` or `unknown`. Up to 10 items a request: short, uniform
    judgements batch well (the note, Packet size).
  - A text of more than 60 words is not judged and is counted apart (`tooLong`); the scorecard
    shows the mean length in words beside each score.
  - A verdict is cached by the text, its kind and what it is about, so a text judged once is not
    judged again, and a rerun costs nothing.
- **A model of its own.** `WORDING_JUDGE` joins the configurable roles on the reasoning tier, in
  the `model-checks` share of the run budget. The writers span the reasoning and high-volume tiers,
  so no tier differs from all of them: a run that judges sets
  `lantern.llm.roles.wording-judge` to a model no writing role uses. A text whose writer is the
  judge's model is not asked at all (no spend), is written with the state `sameModel`, and is
  counted apart by the scorecard; the run logs a warning naming how many there were.
- **Only texts a row reads.** Name groups' line intents are in no judged row, so they are not
  judged. A run that does not judge deletes an earlier run's `wording.jsonl.gz` in the snapshot's
  folder, and `score-testsets.sh` copies the file only from a run that judged, so a scorecard never
  reads another run's verdicts.
- **Validation by the owner's labels.** The label set lives in `testsets/wording-labels/`, one
  pair of files per dimension:
  - `<dimension>.json`, the texts for the owner to label, in an order that does not show the
    judge's verdicts and without them;
  - `<dimension>.judge.json`, the judge and prompt version that judged them, its verdicts, the
    stratum each text was drawn from (the verdict when drawn) and how many texts each stratum
    held. The owner labels without opening it.
  - `node lantern-web/scripts/wording.mjs draw <scores folder>` draws them from a scored run's
    sets: 60 texts per dimension by default (the first labels were drawn at 160; amendment for #668), half the judge passed and half it failed (the other class fills
    in where one is short), taken in turn over set and role, each text once; a third of each class
    to tune the prompt, the rest held out. It refuses to replace a file that holds the owner's
    labels unless given `--force`.
  - The draw balances the judge's verdicts, not the owner's classes: drawing by verdict is what
    gives the owner enough of each class to label when one class is rare. A sample drawn by the
    judge's verdict reads TPR and TNR wrong if read as random (a good judge on a set where 90% of
    texts pass would read TPR 0.66), so each held-out label is weighted by its stratum's size over
    the held-out labels drawn from that stratum (inverse inclusion probability), and the interval
    resamples within each stratum. Only texts the judge passed or failed are drawn, so its
    "unknown" exit is not validated by the labels; on a re-judged labelled text it counts as wrong.
- **The judged rows.** For Describe, Name groups and Purpose (the key fact), per dimension, the
  scorecard shows the counts (pass, fail, unknown, not answered, too long, by the judge's own
  model), the mean length and the raw pass rate p. The row says "not validated" until the
  held-out labels hold at least 10 of each class, judged by the same judge and prompt version as
  the run, with weighted TPR and TNR both at least 0.8. Validated, it reports θ = (p + TNR − 1) /
  (TPR + TNR − 1), clamped to [0, 1], with a 95% bootstrap interval that resamples the run's
  verdicts and both classes of labels, so the labels' uncertainty is in it. An unknown verdict on
  a labelled text counts as the judge being wrong; on a run's text it is counted apart from p.
- **What waits for the recordings.** The first label set is drawn from the first recorded run that
  judges wording (#529), and the owner labels it at the 1F test drop; TPR, TNR and the corrected
  scores are recorded on #668 then. A change of the judge's model or prompt version needs the
  labelled texts judged again, rewriting only the verdicts in `<dimension>.judge.json` (the strata
  stay as drawn), before the score counts; that step is built when it is first needed.

## Consequences

- With the fake model the judged rows show "not validated (no owner labels yet)" and the fake's
  raw counts, so a fake run proves the plumbing without claiming a score.
- The report judge of #277 stays as planned for whole sections; it may reuse this judge's
  labels and validation.
- Ten-item requests of one dimension are a starting size, not calibrated; the note's packet-size
  calibration applies to the judge like any bulk role if its score comes to matter.

## Amendment for #668: the first labels

- **Who labelled.** At the owner's request (2026-11-02), Claude (Opus 5.5) labelled the drawn
  texts without opening the judge's verdicts, and the owner checked 20 of the first 60, chosen
  mostly where the label was in doubt. The owner changed 2 of the 20: a responsibility padded with
  "through standard persistence operations" fails, since the padding says nothing; a plain
  description of a standard tool (Zipkin collects tracing spans) is specific enough. The rest
  were relabelled by those two rules. The labels stand as the owner's, with that provenance in each
  file's `status`. Short line labels that name an action and its object ("Sends a charge to
  PayGrid") pass both questions; the owner kept them.
- **A larger draw.** At 60 texts per question the held-out labels held 3 and 8 failing texts, below
  the 10 each class needs. The set was drawn again at 160 per question from the same recordings
  (#529's Sonnet pass), which keeps the first 60 (the draw is seeded and fills each stratum in
  order); the held-out labels now hold 96 passing and 10 failing for "says what it does for the
  system", 92 and 14 for "specific".
- **Result: not validated.** Sonnet 5.5 as the judge: "says what it does" TPR 98%, TNR 8%;
  "specific" TPR 91%, TNR 44%. The judge passes what the labels fail where it matters most: people
  described only by signing in or by the role an endpoint checks, and generic group names. The
  rates are weighted by the strata, and the judge's pass stratum is 35 times its fail stratum for
  the first question, so each of those misses weighs heavily. It also fails short line labels that
  the labels pass, which weighs little. The judged rows stay "not validated" until the prompts are
  tuned on the `tune` items and the labelled texts judged again.

## Amendment for #668: the tuned prompts, validated

- **Tuning.** The prompts were tuned on the 54 `tune` texts of each question with `scripts/judge-labels.sh` (Sonnet 5.5), in three rounds. The examples in the prompts are written fresh, not taken from the labelled texts. What changed:
  - "says what it does" now judges each kind of text by what it is for. An intent passes when it names what the call does with which thing, and fails when it names only the client it goes through or a technical step. A group's name passes when it names an area of work, not a layer. A person fails when only holding a role or signing in is said. A responsibility that only describes mechanics fails ("through standard repository operations").
  - "specific" passes a common action on this system's own data or store, and a standard product described by what this system uses it for. It fails what reads the same with any system's nouns.

  The prompt version is 2. The labelled texts' verdicts in `<dimension>.judge.json` are this judge's, and the strata stay as drawn.

  | Round | "says what it does" TPR / TNR | "specific" TPR / TNR |
  | --- | --- | --- |
  | Prompt 1 | 83% / 50% | 60% / 56% |
  | 1 | 91% / 63% | 80% / 100% |
  | 2 (kept) | 85% / 88% | 82% / 89% |

  These are unweighted rates on the tune texts. Tuning stopped at round 2, the first with both rates at 80% or more on both questions.
- **Validated on the held-out texts, judged once.** These are weighted by stratum, with a 95% bootstrap interval:

  | Question | Held-out labels (pass / fail) | TPR | TNR |
  | --- | --- | --- | --- |
  | "says what it does for the system" | 96 / 10 | 1.00 (0.99 to 1.00) | 1.00 (1.00 to 1.00) |
  | "specific to this system" | 92 / 14 | 0.84 (0.76 to 0.92) | 0.89 (0.64 to 1.00) |

  Unweighted, the judge got 3 and 31 of the 106 wrong. Most of the specific question's misses fail short phrases that the labels pass, which weigh little. Both questions pass the 0.8 bar, so a run judged by this judge and prompt version shows a corrected score.

  The specific question's TNR rests on 14 failing labels, and its interval reaches down to 0.64. More failing labels would narrow it.
- **Spend.** Sonnet 5.5, $0.48 in all: three tuning rounds of about $0.10 each, and $0.18 for the held-out texts.
- **The corrected score.** From the 1F drop's project-4 run: DeepSeek V4.1 Flash wrote, and this judge (Sonnet 5.5, prompt version 2) judged its 473 Describe texts, 10 group names and the key fact, for $0.77. Per role, corrected θ with its 95% interval and the raw pass rate:

  | Role | "says what it does" | "specific" |
  | --- | --- | --- |
  | Describe (473 texts) | 99% (98% to 100%); raw 99% | 99% (88% to 100%); raw 84% |
  | Name groups (10) | 90% (70% to 100%); raw 90% | 67% (12% to 100%); raw 60% |
  | Purpose, the key fact (1) | 100%; raw 100% | 100%; raw 100% |

  For "specific", Describe's corrected score is well above its raw 84%. The labels show the judge fails texts the owner would pass (TPR 84%), and the correction accounts for that. The interval stays wide because only 14 failing labels back the TNR. Name groups and the key fact are too few texts to read much into. The judge's validation is closed by #668. Total Sonnet spend on #668: $1.25.
