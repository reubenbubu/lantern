# 0112: Packet-size calibration runs

Status: accepted (#529)

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
- The kept sizes, paired differences, intervals and costs go into each role's ADR from the
  recordings (#529); this ADR records only how the runs are made.
