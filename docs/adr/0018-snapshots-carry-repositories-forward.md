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
- **Only the named repositories are analyzed.** The run builds, summarizes, composes and renders
  only the repositories it is given, and records only their facts; carried-forward ones keep their
  facts and their documents stay where an earlier run wrote them. The extractors still see every
  repository of the snapshot, each carried-forward one at its commit with its stored build if
  there is one, because whether a dependency is internal or a call is kept depends on the other
  repositories (ADR 0006, 0007). So `analyze A B` followed by `analyze A` finds the same facts
  for A. A carried-forward repository whose working tree no longer has its commit is left out of
  that context, with a warning. A named repository at an unchanged commit reuses
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

A repository stays in every later snapshot once it has been in one. Removing a repository from
the system is not supported yet; nothing unregisters one today.

The snapshot is stored before its builds and extraction run, so a run that fails after creating it
leaves its commits behind, and later runs carry a repository forward at that commit even though
its facts are still those of the commit analyzed before. Carrying forward only commits whose
analysis completed needs a marker in the snapshot tables, a schema change left to #61.
