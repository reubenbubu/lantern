# 0006: Maven extractor

Status: accepted (#8), amended (#100, #93, #265), amended by ADR 0064 (#226)

## Context

`docs/design.md` (Static analysis and extractors) says the Maven extractor reads the effective POM,
reactor and dependency tree and produces modules, artifacts, internal vs. third-party dependencies
and deployable vs. library. Issue #8 adds that a thin service repository must be linked to the
`.lib` artifact it packages, the OpenWMS pattern (ADR 0004). The design leaves open
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
  OpenWMS artifact without a repository in the snapshot is third-party.
- **Deployable vs. library.** A module is deployable if its packaging is `war` or `ear`, or its
  build runs Spring Boot's `repackage` goal (not skipped with `<skip>` or the
  `spring-boot.repackage.skip` property). In an effective POM the goal must be
  bound; in a POM as written a bare `spring-boot-maven-plugin` declaration also counts, since the
  parent may bind it. Deployable modules become `service:` entities, other non-`pom` modules
  `library:` entities. Other ways of making a runnable artifact (shade or assembly plugins with a
  main class) are not recognized yet (they are since the amendment for #382).
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
  code in its own module and no separate library, so it has no `PACKAGES` link. With the test
  set's Maven repositories (ADR 0023), 15 of the 16 repositories have an effective POM;
  `org.openwms` is read as written because its `core.util` module names a parent version that
  does not exist (ADR 0020).
  `OpenWmsMavenIT` checked this when the clones were present (removed in #100).

## Amendment (#100): rules that came from OpenWMS

OpenWMS is no longer a test set (#100), and `OpenWmsMavenIT` is removed. The rules it shaped
stay as general Maven rules: the `PACKAGES` link from a thin service to its library, and
accepting any classifier on that dependency. The `lib-and-service` fixture and `MavenRulesTest`
cover the link, and `MavenRulesTest` a dependency with classifier `all`.

## Amendment (#93): the Java release and the parent

- A container's technology names its Java release (ADR 0039), so each module gets a
  `java-release` attribute fact (object kind `string`): the major release, `21`, or `8` for `1.8`,
  from the first that gives one of `maven.compiler.release`, the compiler plugin's `release`,
  `maven.compiler.source`, the plugin's `source` and `java.version`, as the sandbox chooses a
  build's JDK (ADR 0005). Values come from the effective POM, or the POM as written with its
  parents in the snapshot. Evidence is the property or compiler plugin in the module's own POM
  that sets it, else the POM without lines. The extractor's version becomes 2.
- Each module with a `<parent>` also gets a `parent` attribute fact (object kind `string`):
  `groupId:artifactId:version` as its POM writes them, properties resolved, with the `<parent>`
  element as evidence. The linker reads it to find Spring Boot's version without a build
  (ADR 0039).

## Amendment (#226): coverage tools

Each module whose build runs a coverage plugin gets a `coverage-tool` attribute fact, with the
goals it binds and, for JaCoCo, the limits its `check` goal enforces. The extractor's version
becomes 3. The details are in ADR 0064.

## Amendment (#265): JitPack coordinates

project-4's services depend on their shared library through JitPack, which builds a repository
from its git host and publishes it under coordinates of its own, not those in the repository's
POMs: `com.github.APA-CRM:crm-shared-lib` for the library whose POMs say `com.crm:sharedlib`,
`com.crm:core` and so on. Matched by `groupId:artifactId` only, the library was third-party, so no
unit bundled it.

- **A dependency under JitPack coordinates is internal** when the snapshot has a repository of
  that name, compared without case; the owner is not checked. A group ID starting with
  `com.github.`, `com.gitlab.`, `org.bitbucket.` or `com.gitee.` is JitPack's.
  `<host>.<owner>:<repo>` names the whole build, so the dependency points at every module of the
  repository that builds a service or library (not its `pom` modules), one `BUILD_DEPENDS_ON`
  each; `<host>.<owner>.<repo>:<artifactId>` names the module with that artifact ID. Exact
  coordinates are matched first. A JitPack dependency that names no analyzed repository or module
  stays an `artifact:`.
- Such a `BUILD_DEPENDS_ON` has the declared `groupId:artifactId` as its `coordinates` attribute,
  and keeps the declared `version`, which for JitPack is a git tag, branch or commit, not
  necessarily the version the repository's POM declares. The linker compares the two (ADR 0039,
  amendment for #265).
- **Known false positive.** The match is by repository name only. A dependency from a Maven
  repository other than JitPack whose group ID starts with `com.github.` (some projects publish to
  Maven Central under `com.github.<owner>`) is taken for the analyzed repository whenever one has
  the dependency's artifact ID as its name, though its code is someone else's. Checking the
  owner against the repository's git remote would rule that out; it is not done.
- **The version is JitPack's.** A JitPack version is a git tag, a `<branch>-SNAPSHOT` or a commit
  hash, not a version from a POM. The skew check compares it with the version the library's POM
  declares as text, so a tag spelt differently from that version (`v0.3.0` for `0.3.0`) or a
  commit hash reads as skew even when it names the analyzed commit. Tags are not resolved to
  commits.
- The build is unchanged: the sandbox still resolves JitPack coordinates from the Maven
  repositories or mirror it is given (ADR 0023, ADR 0030), and does not give the library's own
  build under them; whether the extractor finds the library does not depend on the build.
- The extractor's version becomes 4.

## Amendment (#382): what makes a module deployable

Research: `docs/research/extractor-maven.md`. Only `war`, `ear` and Spring Boot's `repackage`
made a module deployable, so a Quarkus service, an image built by Jib or Eclipse JKube, or a jar
made executable by shade or assembly was drawn as a library; a repackage moved to
`<phase>none</phase>` still counted; and nothing said why a module was deployable. The
extractor's version becomes 5.

- **Deployable** is decided from the build's `<plugins>` (never `pluginManagement`, which the
  effective POM keeps out), in this order of strength: `war` or `ear` packaging; Spring Boot's
  `repackage`; Quarkus (`quarkus` packaging, or `quarkus-maven-plugin`'s `build` goal); a shade
  execution with a transformer whose `mainClass` resolves; an assembly `single` execution whose
  archive manifest's `mainClass` resolves; Spring Boot's `build-image` or `build-image-no-fork`
  (unless `spring-boot.build-image.skip`); Jib, Eclipse JKube (`kubernetes-maven-plugin`,
  `openshift-maven-plugin`) and the Spotify and fabric8 Docker plugins, unless `jib.skip`,
  `jkube.skip`, `dockerfile.skip` or `docker.skip` is set. An image plugin declared without
  executions counts, since it is run from the command line; one with executions counts only if
  one remains. The fabric8 Docker plugin counts only for a `build` execution, or, without
  executions, an image with a `<build>`: it is often declared just to start and stop containers
  for integration tests. A plugin or execution with `<skip>` true, or an execution bound to phase
  `none`, does not count, nor does Quarkus with `quarkus.build.skip`. A `pom` module is never
  deployable. In a POM as written, a bare Spring Boot or Quarkus plugin still counts, since a
  parent's plugins or `pluginManagement` may bind its goal, and the shade and assembly main class
  may sit in the plugin's configuration rather than the execution's. A single `main` method and the jar plugin's manifest are not signals:
  Spring Boot's parent gives every jar module a `${start-class}` main class.
- **`deployable-by`**, a new string attribute of a deployable module: `war`, `ear`,
  `spring-boot`, `quarkus`, `shade`, `assembly`, `spring-boot-build-image`, `jib`, `jkube` or
  `docker`, the strongest that applies. Its evidence is the module's `<packaging>` or the plugin
  in its own POM, or the file without lines when a parent declares it.
- **The Java release** now follows Maven's precedence: the compiler plugin's `release`, then
  `maven.compiler.release`, then the plugin's `source`, then `maven.compiler.source`, then
  `java.version`.
- **Not covered yet:** an app that is also a library (#484), deployables only named profiles build
  (#485), test and sample modules (#486), the built artifact's manifest, and Dockerfiles or
  manifests that ship a module (the Deployment extractor's, #397).

