# 0112: Packet-size calibration runs

Status: accepted (#529), amended for #529 below (the recordings, and items per request as a setting)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 10, as changed by #662)
calibrates each batched role's packet size on real recordings: the role recorded at 1 (twice, for
the noise between runs), 5, 10 and 20 items a request, the kept size the largest that a paired test
shows no worse than size 1. Each role's packet size is a constant in its code, and the answer cache
answers an item whatever size asked it, so a rerun at another size would make no call and measure
nothing. #529 needs a way to ask one role again at another size without changing its usual
answers.

## Decisions

- **A calibration per role and run.** `lantern.harness.calibrate=<role>:<size>[,<role>:<size>]`
  (the harness role names, such as `resolve`, `investigate-leads`, `describe-element`,
  `support-judge`) and `lantern.harness.calibration-trial=<label>` (default `1`) ask each named
  role at that packet size in this run. `scripts/score-testsets.sh --set` passes them. Only the
  roles that ask several items a request can be named; any other name stops the run before it
  starts, since a misspelled role would change nothing and look like a recording.
- **Every packing reads the role's size.** Resolve and Investigate's leads build their own
  conversations; they take the size from the role's spec, so a calibration changes them too.
- **Fingerprinted apart.** A calibrated role's fingerprints carry the size and trial beside its
  prompt version, so it is asked again at that size, a repeat of the same size and trial reads
  the cache (the recording replays with no call), a second trial at one size asks again, and the
  role's usual answers are never replaced. The other roles of the run read the cache as usual.
- **The same items at every size.** A sample drawn by fingerprint, the support judge's 10%, and the
  order items are packed in read the role's own fingerprint, not the calibrated one, so every size
  judges the same texts with the same neighbours in order, and the sizes can be compared item by
  item.
- **Recorded.** Each role's entry in `run.json` carries `packetSize`, the items it sent a request.
- **Facts.** A calibration run records the calibrated role's facts like any run; the next run at
  the usual size records the usual answers again from the cache.

## Consequences

- The calibration costs only the calibrated roles' calls; everything else of a calibration run is
  cached.
- Each role's kept size, with its paired differences, intervals and costs, is in that role's ADR;
  what the recordings share is below.

## Amendment for #529: the recordings, and items per request as a setting

- **Where and on what.** On 2026-11-02, on project-1 to project-4, every role on DeepSeek V4.1 Flash
  through DeepSeek's own Anthropic-compatible API: the Anthropic limits had been reached and the
  owner chose DeepSeek, capped at $8. The kept sizes hold for that model. They are to be measured
  again on Claude Haiku 5.5 while Haiku stays the default writer.
- **Runs.** A run at the usual sizes, then each calibrated role at 1 (trials a and b), 5, 10 and 20
  items a request, all four roles in each run. They cost $4.26 in all: the usual run $1.70, the
  size-1 runs $0.64 and $0.61, size 5 $0.44, size 10 $0.43, size 20 $0.44 (stopped halfway by a
  time limit once, and finished from the cache).
- **Scores.** Per item, from the scorer (`score.mjs`, roles of the scorecard), 0 to 100:
  Investigate's leads by each reference risk and decoy of the answer key; Describe's elements by
  whether each was described; the support judge, which has no right answer, by the share of an
  item's claims it judged supported. The analysis is the epic's (section 10): a one-sided 90%
  bootstrap lower bound of the mean per-item difference against size 1, trial a, above −7; the
  share of changed answers at most the two size-1 runs' plus 5 points; no set more than 14 points
  down; sizes in order.
- **Noise between two runs.** Investigate's leads changed verdict on 16% of 103 leads between the
  two size-1 runs (the epic assumed 10%). Resampling those differences around zero, a size truly
  equal to size 1 fails the −7 bound about 14% of the time with 77 scored items: one failure in
  seven is noise.
- **Results.**
  - Investigate's leads: 1 item (ADR 0110).
  - Describe's elements: 10 (ADR 0103).
  - The support judge: 5 (ADR 0101).
  - Resolve: 4, unmeasured, since no development set has an open link (ADR 0105).
- **Also measured.**
  - Every request after a role's first, and every turn after a conversation's first, read the
    cache on all four sets.
  - Replaying a recording made no call: size 5 on project-4 replayed with 0 calls. The usual run's
    replay made one, for the purpose: it had been skipped when its budget share ran out, and a
    budget skip is asked again.
  - With the injection fixtures of ADR 0101 in a repository, the screen flagged all four and not the
    clean file. No other role's output followed them: no claim of being secure or risk-free, no
    password, and no file read outside the repository.
  - The estimate printed on project-4 was $0.38 against $0.77 spent, outside the 25% #529 asked
    for. It counts no thinking tokens and no conversation turns beyond the first; flow narratives
    cost 3 times their estimate and unit investigations 8 times. That is #694.
- **Items per request is a setting.** `lantern.harness.items-per-request.<role>` sets how many items
  a role asks a request, for every role a calibration can name; `application.properties` holds the
  kept sizes, and a role without the property keeps the size in its code. Unlike a calibration it
  is not part of a fingerprint, since an answer does not depend on its request's neighbours
  (epic, section 10): changing it asks nothing again.
