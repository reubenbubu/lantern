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
  from the facts that hold in the snapshot. It belongs to the design's "linker and grouping"
  stage, not to an extractor, because it reads no files: it combines facts of several extractors
  across repositories (a service in one repository may bundle a library from another), so its
  result depends on the snapshot's set of facts, which is the cache key the design gives the
  linked graph, not file hashes. The linker (#94) builds on it in the same module: it needs the
  units to tell which container makes a call and to match a client name to a
  `spring.application.name`. Its facts have origin kind `static` (they follow from static facts by
  fixed rules), extractor name `deployable-units`, version 1.
- **The linker records for the whole snapshot.** A derived fact must stop holding when the facts
  it came from change, even if only another repository was analyzed: a library that a newly
  analyzed service now bundles is no longer a unit of its own, and a name changed in a library
  renames the carried-forward service that bundles it. So the linker does not record its facts the
  way extractors do, per analyzed repository (ADR 0017). `Linker` runs its steps (`LinkStep`) in
  order, in the transaction that recorded the extracted facts: each step derives from every fact
  that holds in the snapshot (carried-forward repositories' included, the linker's own left out,
  the earlier steps' added), and `FactRepository.recordDerived` records its facts so that every
  open fact of the step's origin that it did not derive again stops holding, in any repository.
  `recordSnapshot` is told the steps' origin names and leaves their facts alone, so analyzing a
  repository never closes and reopens them. #94's steps follow the same pattern.
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
- **Its name** is the `spring.application.name` Spring Boot finds on the classpath. It loads the
  `application` files and the `bootstrap` files each from the first module on the classpath that
  has any: the unit's own module, else the nearest bundled library. So a service's
  `application.yml` hides a library's, but not the library's `bootstrap.yml`. Which files a module
  has is read from the paths of its config facts. A name in the `application` files wins over one
  in the `bootstrap` files, in every profile (ADR 0032). The default view's name, then one fact per
  profile whose name differs. With no name in the default view the unit is named by its artifact
  ID, marked `defaulted`, with half the unit's confidence.
- **Its technology** is Spring Boot, when the unit has an application class, an
  `org.springframework.boot` dependency or Spring Boot's parent POM; with the version of the
  nearest module's `org.springframework.boot` dependency, else of the `spring-boot-starter-parent`
  (or `spring-boot-parent`) the nearest module inherits from, through parents in the snapshot, so
  no build is needed; and the Java release of its own module, else of its main library. The Maven
  extractor now records each module's parent and Java release (`parent`, `java-release`; amendment
  to ADR 0006). The value reads "Spring Boot 3.5.14 / Java 21"; the parts are also attributes, so a
  view can shorten it. A version that only a BOM import manages is not found without a build.
- **Facts**, all about the unit: `deployable-unit` (`spring-boot`, `war`, `ear` or
  `executable-jar`, with `mainClass` and `packaging`), `application-name` (with `module`,
  `profile`, `unresolved` and `defaulted`), `technology` (with `framework`, `frameworkVersion` and
  `java`) and `BUNDLES` to each library (with `main` and, for one reached through others, `via`).
  `BUNDLES` has no parent: it is not a dependency between containers, and under `CONTAINS` a
  library bundled twice would break the summary hierarchy. The description of a container is not
  derived here; the summarizer writes it.
- **Evidence stays in the unit's repository.** Each fact's evidence is the input fact it comes
  from when that is in the unit's repository (the application class, the config line, the
  dependency, the parent), else the unit's own POM, so a document of the unit's repository shows
  its unit.
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
  `LinkerIT` checks both cases where a derived fact must stop holding: a library bundled by a
  service analyzed later, and a name changed in a carried-forward service's library.
