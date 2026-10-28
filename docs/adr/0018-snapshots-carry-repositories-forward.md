# 0018: Snapshots carry repositories forward

Status: accepted (#60), pending the user's review (#24)

## Context

ADR 0017 closes every open static fact a snapshot's analysis did not find, across all
repositories, and the analysis run (ADR 0016) made a snapshot of only the repositories it was
given. So `analyze A` followed by `analyze B` left A out of the second snapshot and ended all of
A's facts: a diff of the two showed A as removed, and analyzing A again at the same commit started
new fact versions (#60). The design has a snapshot describe the whole system: comparing two
snapshots is a query, and the system scope is all repos in the snapshot.

## Decisions

- **A snapshot describes the whole system.** The analysis run creates its snapshot with
  `SnapshotStore.createCarryingForward`: the repositories it is given at their checked-out commits,
  and every other repository that an earlier snapshot has at its commit in the latest snapshot
  that has it. Carried-forward commits are not resolved again, so a repository whose working tree
  moved on or is gone is still described by what was analyzed. `SnapshotStore.create` still makes a
  snapshot of exactly the repositories it is given.
- **Only the named repositories are analyzed.** The run builds, extracts, summarizes, composes and
  renders only the repositories it is given; carried-forward ones are not touched and their
  documents stay where an earlier run wrote them. A named repository at an unchanged commit reuses
  its stored build and is extracted again, which finds the same facts (ADR 0016), and picks up
  extractor changes. Skipping extraction for unchanged commits is the incremental work of Phase 3.
  `analyze` prints carried-forward repositories as "carried forward, not analyzed".
- **Facts end only for analyzed repositories.** `FactRepository.recordSnapshot` takes the names of
  the analyzed repositories. Only open facts of that origin kind whose evidence names one of them
  can be closed; facts of other repositories keep holding. A found fact whose evidence names
  another repository is refused. This replaces ADR 0017's "the scope of closing is the origin kind,
  across all repositories"; the rest of ADR 0017 stands, including that snapshots are recorded in
  order across all repositories.

## Consequences

A repository stays in every later snapshot once analyzed. Removing a repository from the system
is not supported yet; nothing unregisters one today.
