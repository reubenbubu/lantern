# 0039: Deployable units

Status: accepted (#93), pending the user's review (#24)

## Context

`docs/design.md` (Composition, Diagrams) draws one container per deployable unit, folding in the
in-set libraries it packages, so a thin service and its library are one box. The units come from
deployable evidence (a Spring Boot main class, a repackaged executable artifact) and are named by
`spring.application.name`; the C4 report spec (`report-specs/c4-model-sequence-diagrams`) also
gives each container a technology, such as "Spring Boot 4 · J21". The extractors already make the
inputs: services and dependencies (ADR 0006), application classes (ADR 0035) and config values per
profile (ADR 0032). Issue #93 turns them into units that the linker (#94) and the views (#95) can
use. The design leaves open where this runs, what a unit's facts look like and how the edge cases
are decided.

## Decisions

- **The linker's first step, in a new module.** `DeployableUnits` in `lantern-link` derives facts
  from the facts the extractors made for the whole snapshot; `AnalysisRun` runs it right after them
  and stores its facts with theirs. It belongs to the design's "linker and grouping" stage, not to
  an extractor, because it reads no files: it combines facts of several extractors across
  repositories (a service in one repository may bundle a library from another), so its result
  depends on the snapshot's set of facts, which is the cache key the design gives the linked graph,
  not file hashes. The linker (#94) builds on it in the same module: it needs the units to tell
  which container makes a call and to match a client name to a `spring.application.name`. Its
  facts have origin kind `static` (they follow from static facts by fixed rules, and the store
  records one kind per run), extractor name `deployable-units`, version 1.
- **A unit is a `service:` entity**, the built-in container type, so no new entity type is needed
  and libraries and modules keep no C4 level (#90). Each service the Maven extractor finds (a war,
  an ear or a jar Spring Boot's plugin repackages) is a unit. So is a library with a Spring Boot
  application class that no such service bundles, since the design counts the class as deployable
  evidence on its own (an application built into an image by Jib or a Dockerfile has no repackage
  goal); it becomes `service:<groupId>:<artifactId>` of the library, with 0.8 times the class's
  confidence. Whether a library is its own unit therefore depends on which repositories the
  snapshot holds, as whether a dependency is internal does (ADR 0006).
- **What a unit bundles**: the in-set libraries on its runtime classpath, the way Maven puts them
  there: those its own module depends on at compile or runtime scope, and theirs in turn, an
  optional dependency of a library not passed on. Dependencies on a service, a `pom` module or a
  typed artifact (`test-jar`) are not followed, nor are `provided` ones; exclusions are not read.
  A library bundled by several units belongs to each: its code runs in all of them. All of them
  are one box with the unit. The library that holds the unit's main code (the `PACKAGES` link of a
  thin service, or the library with its application class) is marked `main`.
- **Its application class** is the Spring Boot one in the unit's own module, else in the nearest
  library it bundles; of several there, the first by ID.
- **Its name** is the `spring.application.name` of the config Spring Boot loads: that of the
  unit's own module if the module has any config, else that of the nearest bundled library that
  has some, since a library's `application.yml` is only found on the classpath when the
  application has none. This is decided per module, not per file. The default view's name, then
  one fact per profile whose name differs (ADR 0032). With no name in the default view the unit is
  named by its artifact ID, marked `defaulted`, with half the unit's confidence.
- **Its technology** is Spring Boot, with the version of the nearest module's
  `org.springframework.boot` dependency if one is known, when the unit has an application class or
  such a dependency; and the Java release of its own module, else of its main library. The Maven
  extractor now records that release (`java-release`, amendment to ADR 0006). The value reads
  "Spring Boot 3.5.14 / Java 21"; the parts are also attributes, so a view can shorten it. Without
  a build the version Spring Boot's parent POM manages is unknown, and the value reads "Spring
  Boot / Java 21".
- **Facts**, all about the unit: `deployable-unit` (`spring-boot`, `war`, `ear` or
  `executable-jar`, with `mainClass` and `packaging`), `application-name` (with `module`,
  `profile`, `unresolved` and `defaulted`), `technology` (with `framework`, `frameworkVersion` and
  `java`) and `BUNDLES` to each library (with `main` and, for one reached through others, `via`).
  `BUNDLES` has no parent: it is not a dependency between containers, and under `CONTAINS` a
  library bundled twice would break the summary hierarchy. The description of a container is not
  derived here; the summarizer writes it.
- **Evidence stays in the unit's repository.** Each fact's evidence is the input fact it comes
  from when that is in the unit's repository (the application class, the config line, the
  dependency), else the unit's own POM. The store ends a repository's facts by their evidence
  (ADR 0017), so a unit's facts are derived again whenever its repository is analyzed, and a
  repository analyzed alone or with others gets the same facts.
- **Left to the linker (#94):** a library's code runs with the config of each unit that bundles
  it, so its placeholders resolve per unit, and the unit that calls through an HTTP client in a
  library bundled by several is the one that enables or injects it (ADR 0038). Applying both needs
  the links this step does not make.
- **New vocabulary**, applied by the app on startup after the extractors', with source
  `deployable-units`: the attribute types `deployable-unit`, `application-name` and `technology`,
  and the relationship type `BUNDLES`.
- **Not covered:** Dockerfiles and other deployment files (later extractors), shade and assembly
  plugins, Gradle, Jakarta EE applications without Spring Boot beyond their packaging, and
  frameworks other than Spring Boot in the technology.
- **project-0.** `Project0DeployableUnitsIT` checks REPO-1 (four units), MVN-1 (`catalog-service`
  bundles `catalog-lib` as its main library), MVN-2 (`storefront-app` bundles both libraries, one
  box), SPR-3 (each unit's application class), CFG-1 and CFG-2 (names `catalog-service` from
  `bootstrap.yml`, `storefront`, `payments`, `fulfilment`) and MVN-3 (Spring Boot 3.5.14 with Java
  21, or 17 for payments), with the repositories built in the sandbox and read without builds. It
  runs when `lantern.test.project0` is set (see Commands in `CLAUDE.md`).
