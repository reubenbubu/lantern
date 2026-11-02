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
    sets: 60 texts per dimension, half the judge passed and half it failed (the other class fills
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
