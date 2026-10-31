# 0017: Fact validity across snapshots

Status: accepted (#23), amended by ADR 0018 (#60), for #165 and by ADR 0091 (#281: validity per observation; versions of one observation never overlap)

## Context

A fact's ID leaves out its validity and confidence (ADR 0001), so the same claim keeps its ID
across snapshots. Issue #23 asks for a way to extend or close a stored fact's validity when a new
snapshot is taken, and to decide how a confidence change for the same claim is handled.
`docs/design.md` gives validity as one snapshot range and has snapshots fully materialized, so
comparing two snapshots is a query. It does not say which stored facts a snapshot's analysis may
close, or what happens when a fact stops holding and later holds again, for example when one run
analyzes repository A, the next one B, and a third A again at the same commit.

## Decisions

- **One row per version.** A stored fact is one or more versions, each one row with one
  contiguous validity range and one confidence. The envelope is unchanged: each version reads back
  as a `Fact` with a single `{from, to}` validity. The `facts` table is no longer keyed by fact ID;
  an exclusion constraint (`btree_gist`, a trusted extension shipped with PostgreSQL) keeps the
  versions of a fact from overlapping, so at most one is open and `findValidAt` returns each fact
  at most once. `findById` returns the latest version; queries by subject, predicate or object
  return every version. One multirange validity per fact was the alternative; it would have
  changed the envelope's JSON shape and lost the confidence each range had.
- **A snapshot is recorded as a whole.** `FactRepository.recordSnapshot(snapshot, kind, found)`
  takes every fact of one origin kind found in a snapshot, each valid from it onwards. Afterwards
  exactly those facts of that kind hold in the snapshot:
  - a found fact that already holds with the same confidence keeps its open validity and start;
  - a found fact that holds with another confidence is closed at the snapshot, and a new version
    with the new confidence holds from it, so older snapshots keep the confidence they had;
  - a found fact that does not hold, new or closed since, starts a new version at the snapshot;
  - a fact of that kind that holds but was not found is closed at the snapshot.
- **The scope of closing is the origin kind, across all repositories.** (Replaced by ADR 0018:
  closing is limited to the repositories the snapshot's analysis covered, and `recordSnapshot`
  also takes their names.) Facts of other kinds (human, LLM, README, rule) belong
  to other stages and are never closed by a static analysis; annotations have their own staleness
  rules in the design. The analysis run records its extractors' facts as `static`, and
  (amendment for #165) the docs extractor's as `readme` (ADR 0033), each kind with its own
  `recordSnapshot` call, so README claims and decision records no longer found stop holding too.
- **Order.** Snapshots must be recorded in order; recording one older than a snapshot the stored
  facts of that kind already show is refused. Recording the latest snapshot again replaces what
  was recorded for it, so a repeated run of the same snapshot is safe. The caller runs the
  recording in one transaction, as the analysis run does. The store expects one writer at a time
  (the analysis lock); the queries by origin kind scan the table, which is fine at today's sizes.
- **`save` stays** for single facts and still refuses a different confidence or validity; changes
  go through `recordSnapshot`.

## Not in this issue

Whether the analysis run should now rebuild failed builds, since a successful rebuild no longer
makes the run fail (ADR 0016, #58). Decided in ADR 0016's amendment for #58: a failed build is
built again.
