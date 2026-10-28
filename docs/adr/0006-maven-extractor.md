# 0006: Maven extractor

Status: accepted (#8), pending the user's review (#24)

## Context

`docs/design.md` (Static analysis and extractors) says the Maven extractor reads the effective POM,
reactor and dependency tree and produces modules, artifacts, internal vs. third-party dependencies
and deployable vs. library. Issue #8 adds that a thin service repository must be linked to the
`.lib` artifact it packages, the OpenWMS pattern (design, Open questions). The design leaves open
how these are named and modelled, what "internal" means, and how a service's library is found.

## Decisions

- **One pass over the snapshot.** The extractor (`MavenExtractor` in `lantern-extract`) takes all
  repositories of a snapshot, each at its commit with its sandbox build if there is one, because
  whether a dependency is internal depends on which repositories are analyzed. Its output is
  therefore determined by the POMs and builds of the whole set, not by each file alone (inputs are
  sorted by repository name, so when two repositories build the same coordinates the result does
  not depend on their order); a later
  extractor cache must key Maven facts on the set of internal coordinates as well as the files.
- **Where modules come from.** Modules are found by following `<modules>` from the top
  `pom.xml` at the commit, read from git (never the working tree). Each module is then read from
  the effective POM the sandbox left, matched by `groupId:artifactId`. A failed build usually
  still has one: Maven writes it before resolving dependencies, and the sandbox now keeps it
  (`build.sh`, ADR 0005). Facts from an effective POM have confidence 1.0, even if the build
  failed, because the effective POM is complete. A module without one (no build, or Maven could
  not read the POM, for example an unreachable import BOM) is read from its POM as written, with
  confidence 0.7: properties and managed versions come from parent POMs only when a repository
  in the snapshot builds exactly that parent version, so versions from other parents or BOMs are
  left out. Modules only listed in profiles are not found, and modules a repository's settings skip in the
  build are still read, from their POM as written. The per-module dependency trees are
  not read: direct dependencies and their versions are in the effective POM, and transitive
  third-party dependencies are not emitted.
- **Entity IDs** use Maven coordinates without the version, so they stay stable across releases:
  `module:<groupId>:<artifactId>` for every module (including `pom` parents and aggregators),
  `service:...` or `library:...` with the same key for what a non-`pom` module builds, and
  `artifact:<groupId>:<artifactId>` for a dependency that no repository in the snapshot builds.
  `artifact` is a new entity type with no parent and no C4 level, since third-party code is not
  part of the system's containers.
- **Internal vs. third-party** is by `groupId:artifactId` only: a dependency is internal when any
  repository in the snapshot builds a module with those coordinates, whatever the version (OpenWMS
  services depend on released versions of libraries whose repositories are at the next snapshot).
  An internal dependency points at the `module:` entity, a third-party one at `artifact:`. An
  OpenWMS artifact without a repository in the test set (`core.util`) is third-party.
- **Deployable vs. library.** A module is deployable if its packaging is `war` or `ear`, or its
  build runs Spring Boot's `repackage` goal (not skipped with `<skip>` or the
  `spring-boot.repackage.skip` property). In an effective POM the goal must be
  bound; in a POM as written a bare `spring-boot-maven-plugin` declaration also counts, since the
  parent may bind it. Deployable modules become `service:` entities, other non-`pom` modules
  `library:` entities. Other ways of making a runnable artifact (shade or assembly plugins with a
  main class) are not recognized yet.
- **Facts.** For each module: `version` and `packaging` attribute facts; `<service or library>
  CONTAINS <module>`; `<aggregator> CONTAINS <module>` for each `<modules>` entry; and
  `<module> BUILD_DEPENDS_ON <module or artifact>` for each direct dependency except `import`
  BOMs, with `scope`, and when known `version`, `optional`, `classifier` and `artifactType`
  (`test-jar`, `pom`, ...) as attributes. Test-scoped dependencies are included; readers filter by
  scope. Evidence is the module's `pom.xml` at the commit with its content hash, and the lines of
  the `<dependency>`, `<module>`, `<version>` or `<packaging>` element when the module's own POM
  declares it (a version inherited from the parent points at the `<parent>` element's
  `<version>`; inherited dependencies and packaging point at the file without lines).
- **New vocabulary**, applied by the app on startup after the built-ins, with source
  `maven-extractor`: the `artifact` entity type; `BUILD_DEPENDS_ON` and `PACKAGES`, both extending
  `DEPENDS_ON`; and the attribute types `version` and `packaging`. The Maven attributes go on the
  new `BUILD_DEPENDS_ON` rather than on the built-in `DEPENDS_ON`: changing a built-in from outside
  would make loading the unchanged built-in definition a non-additive change, and `CALLS` would
  inherit Maven attributes.
- **A thin service and its library.** `service:S PACKAGES library:L` links a deployable to the
  library that holds its code. L is the internal library S depends on directly (compile or
  runtime scope, jar type, not optional; any classifier, since OpenWMS uses `all`) whose artifact
  ID shares the most leading name parts with S's, split at `.`, `-` and `_` after a leading
  group ID (S's group ID for both when L's artifact ID starts with it, else each its own): `org.openwms.common.service` and `org.openwms.common.service.lib` share `common`,
  `service`; `greeting-service` and `greeting-lib` share `greeting`. At least one part must be
  shared and the best candidate must be unique. L must also have at least as many Java files under
  `src/main/java` as S, so a service with code of its own is not linked to, say, its API library.
  The fact's confidence is 0.8 times the module's, since it is a heuristic. The services and
  libraries stay separate entities; the link is what lets later stages treat them as one
  deployable.
- **OpenWMS.** On the pinned test set the extractor finds the five services. Four are thin and
  are linked to their libraries: `common.service` to `common.service.lib`, `core.uaa` to
  `core.uaa.lib`, `wms.receiving` to `wms.receiving.lib`, and `tms.routing` to
  `tms.routing.osip` (which in turn depends on `tms.routing.lib`). `tms.transportation` has its
  code in its own module and no separate library, so it has no `PACKAGES` link. Fourteen of the 16
  repositories have an effective POM; two are read as written because Maven cannot read them
  (ADR 0020): `core.process.execution`, whose Activiti BOM is on a host the sandbox does not reach,
  and `org.openwms`, whose `core.util` module names a parent version that does not exist.
  `OpenWmsMavenIT` checks this when the clones are present.
