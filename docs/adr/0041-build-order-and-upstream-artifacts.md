# 0041: Build a snapshot's repositories in dependency order, with each other's artifacts

Status: accepted (#118), pending the user's review (#24)

## Context

The sandbox builds each repository alone, to `compile`, and installs nothing (ADR 0005), so a
repository that uses a library or parent POM another repository of the snapshot builds fails into
degraded mode unless that version happens to be published. ADR 0020 named this as #30's option 1;
project-0 keeps its thin service and library in one repository because of it (ADR 0029), and
project-4 (#88) has a shared-library repository that needs it. Builds run untrusted code, so what
one snapshot's builds produce must not reach the shared Maven cache, where builds of other
snapshots would pick it up (ADR 0005, 0019).

## Decisions

- **Order by internal Maven dependencies.** `BuildOrder` (lantern-sandbox) reads every POM of each
  repository at its commit, from git, following `<modules>` (also those of profiles) from the top
  `pom.xml`, as written, with properties resolved within each POM only (`${project.groupId}` and
  the like included). A repository depends on another when one of its POMs names, as parent,
  dependency (managed ones and plugin dependencies included), plugin or extension, the
  `groupId:artifactId` of a module the other builds, whatever the version, as ADR 0006 decides what
  is internal. A coordinate that does not resolve, a module path leading outside the repository and
  a POM that cannot be parsed add nothing. The POMs are parsed as in JDK detection (no DTDs, no
  external entities), at most 1,000 per repository.
- **Cycles fall back to building alone.** Repositories that depend on each other, directly or
  through others, are a cycle (a strongly connected component); none gets the others' artifacts,
  and the run logs a warning naming them. A repository that depends on a cycle gets the artifacts
  of all its members. Repositories are otherwise built in an order where each comes after those it
  depends on, by name where the order is free.
- **Artifacts are given to a build, never installed.** `BuildSandbox.build(repo, commit, upstream)`
  takes the stored builds of the repositories this one depends on, directly or through others
  (`UpstreamBuild`). For each module of each successful one it writes, in Maven repository layout,
  the module's POM as written at that build's commit and, unless its packaging is `pom`, a jar of
  its compiled classes (resources included, since `compile` copies them there). That directory goes
  into the build's container with Lantern's other files, at `/lantern/upstream`, owned by root and
  read-only for the build user, and Maven gets it as a read-only tail of its local repository
  (`-Dmaven.repo.local.tail`, with `ignoreAvailability`, Maven 3.9). Maven reads from it what it
  does not find in the shared cache and writes nothing there, nor what it found there into the
  cache: `BuildSandboxIT` checks that the cache gains none of those artifacts. JDK detection gets
  the same tail, so a parent POM from another repository is found.
- **Only successful builds are given.** A failed build's classes may be partial; the build log says
  which builds were left out and why. When two builds have a module with the same coordinates, the
  first one's is used, in name order, and the log says so. Coordinates that are not plain path
  parts are skipped.
- **Classpaths.** Jars from the tail are copied into the build's `lib/` like those from the cache,
  so the stored outputs hold the whole classpath and the extractors see the other repository's
  classes (ADR 0005).
- **The analysis run** (`AnalysisRun`) orders the analyzed and carried-forward repositories
  together. A build waits for the builds it depends on (several still run at once, up to the
  sandbox's limit). A repository carried forward is not built (ADR 0018); its stored build, if it
  succeeded, is given to the builds that need it. Reuse is unchanged (ADR 0016): a stored successful
  build of a `repo@commit` is reused, a failed one is built again, now with the artifacts it lacked.

## Consequences

- A repository that uses a library or parent POM another repository of the snapshot builds now
  builds, if the version it asks for is the one that repository builds at its commit. A different
  version still comes from the Maven repositories, or fails into degraded mode.
- Reuse stays keyed by `repo@commit` alone: a stored successful build is not built again when a
  repository it depends on moves to a new commit with the same version. A failed one is.
- A build that used another repository's artifacts runs that repository's code only as far as
  compiling against it runs it (annotation processors in its jar, for example); both are builds of
  the same snapshot, which is already trusted no further than any build.
- POMs are given as written, so a module whose version is a CI-friendly property (`${revision}`)
  that the flatten plugin would replace gives a POM Maven cannot use; a classifier other than none
  (OpenWMS's `all`) and packaging such as `war` used as a dependency type are not given. These can
  be added when a test set needs them.
- The build order reads POMs as written, so a dependency added only by a parent POM outside the
  snapshot is not seen, and such a repository may be built before the one it needs.
- `BuildSandboxIT` builds the `lib-and-service` fixture's service with the library's build, and a
  repository on another's unpublished `-SNAPSHOT` parent POM and library. `EndToEndIT` now has the
  carried-forward library's build given to the service's, which builds. `BuildOrderTest` and
  `UpstreamRepositoryTest` check the order, cycles and the written repository.
- project-0 still keeps its thin service and library in one repository; splitting them is a change
  to its source repository, outside this one (ADR 0029), described on #118.
