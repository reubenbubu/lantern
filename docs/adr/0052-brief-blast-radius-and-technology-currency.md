# 0052: Executive Brief blast radius, technology currency and ranked risks

Status: accepted (#164), amended for #369

## Context

`docs/design.md` (Checking the quality bar, Other documents) asks the Executive Brief for who depends on the repository and what
it depends on (blast radius), runtime and framework versions against their end of life
(technology currency), and risks ranked by impact, each with its consequence and confidence. ADR
0015's brief had headline numbers counted from build dependencies, risks with a severity but no
consequence or confidence, and neither section; ADR 0046 reports their absence. The design left
open where end-of-life data comes from, recommending a table bundled with Lantern so that no
network call or new dependency is needed. The linker now records the links between deployable units
(ADR 0042) and each unit's technology (ADR 0039).

## Decisions

- **Two new section kinds**, `blast_radius` ("Blast radius") and `technology_currency`
  ("Technology currency"), both at L0 and L1, the IDs ADR 0046's checks look for.
  `ViewDefinition.executiveBrief` lists overview, headlines, risks, blast radius, technology
  currency, context and containers: risks stay before the detail. Default views are not stored
  (ADR 0047), so every workspace gets them.
- **Blast radius** is read from the linker's links of the deployable units running the
  repository's code (its own, and those of other repositories that bundle its libraries), leaving
  out links between two of them and the profile-only links that stand in for a default link, as the
  diagrams do (ADR 0044):
  - where its code runs, when another repository's unit bundles one of its libraries (`BUNDLES`);
  - who depends on it: units that call its units (`CALLS_HTTP`), receive their messages
    (`SENDS_MESSAGES_TO` from its units) or read or write their tables (`ACCESSES_DATA_OF`);
  - what it depends on: the units and external systems its units call, the units whose messages
    they receive, the units whose tables they use and the databases they use (`USES_DATABASE`).
    Infrastructure (discovery, config servers, tracing) is left out, as the design says.
  A sentence counts them, citing every link, then a table each (who or what, its kind with the
  repository a container is built in, and how, marked with the Spring profiles when it holds only
  under them), each row citing its links. No endpoints or protocols: the brief has no
  implementation detail. A note says so when nothing is found, and how many of the units' links
  could not be traced (`unresolved-link`), since then it may depend on more. The headline numbers
  "Repositories it uses" and "Repositories using it" still count build dependencies: a library's
  dependents build against it, which links do not show.
- **End-of-life data** (the open question, decided as recommended): a table bundled with
  Lantern, `lantern-compose/src/main/resources/io/lantern/compose/end-of-life.csv`, giving the
  end of free support of each line: a Java major release, a Spring Boot or Spring Framework major
  and minor version. Java uses Eclipse Temurin's end of availability
  (<https://adoptium.net/support/>), the free OpenJDK build most containers use, by the day
  <https://endoflife.date/eclipse-temurin> gives (Adoptium gives months), and for Java 9, 10 and
  12 to 15, which Temurin never built, the end of their public updates when the next feature
  release came out; Spring Boot and Spring
  Framework the end of open-source support, not the paid extension: `ossSupportEndDate` in
  <https://api.spring.io/projects/spring-boot/generations> and
  <https://api.spring.io/projects/spring-framework/generations>, the data behind
  <https://spring.io/projects/spring-boot#support>. All as of 2026-10-29. The table records that
  as-of date and the brief shows it. It is updated each release. A version older than a product's
  oldest line in the table has ended. One between two lines ended no later than the next newer
  line, since every missing line is older than it and not a long-term-support release: shown as
  "Ended by" that date once it has passed, else as not in the table; today the table has no such
  gaps, so this guards rows added later. One newer than every line is not in the table. Lines
  older than the table count as ended whatever the snapshot's date.
- **Technology currency** lists each version in use: Spring Boot and Java from the units'
  `technology` facts; Spring Framework from direct dependencies outside test scope on
  `org.springframework:spring-*` artifacts with a version, in the repository's modules or those its
  units run (a built module's dependencies carry the version Spring Boot manages, ADR 0006, so a
  service depending on `spring-tx` directly shows its Spring Framework); with no unit, Java from
  the `java-release` of the modules that are not `pom` modules. A Java release reads as its line
  (`1.8` is 8). A row names the product, the version, who uses it and the end of its line's free
  support: the date, "Ended" and the date, "Ends" and the date when within six months, or why it is
  not known (not in the table, or a version that could not be read, such as an unresolved
  property). The lead sentence counts only the versions whose support is known and says how many
  are not. Support is judged **as of the snapshot's date** (`createdAt`, UTC), not
  the day of composing, so composing stays deterministic. A note says where the dates come from
  and that the Java version is the release the code is compiled for: the Java runtime is not
  recorded and may be newer.
- **Risks from technology currency**, before the other checks among risks of the same severity:
  framework versions past their end of free support (high) or within six months of it (medium);
  Java releases past it (medium) or within six months (low). Each names the units running the
  version: in a library's brief these are other repositories' units, so the risk says the
  repository's code runs on the version rather than that it uses it.
- **Consequence and confidence** are added to the document model as optional fields of a risk
  callout: `consequence`, one sentence on what the risk means if nothing is done, and
  `confidence`, from 0 to 1. Like the severity (ADR 0015) they are allowed only on risks, and the
  schema version stays 1 since both are optional. Each check's consequence moves out of its text
  into the field. A risk's confidence is the lowest of its facts', halved for Java risks, since
  the Java runtime is not known. Risks stay sorted by severity, the impact. Templates show the
  consequence and the confidence as a percentage under the text.
- **Quality bar.** The brief's sections now pass ADR 0046's checks for blast radius and
  technology currency; the risks check also fails a risk without a consequence or confidence.
  Quality evidence remains missing (#186).
- **The C4 report** shows a risk's consequence and confidence under its text, as the templates
  do, since the consequence is no longer part of the text.
- **Checks.** `ExecutiveBriefTest` covers both sections and the new risks on the fixture facts
  with links (`TestFacts.runtime`), `EndOfLifeTest` the table, `QualityBarTest` the risk check,
  and the golden briefs hold the sections of the fixture repositories (their snapshot is dated
  1970, so every version there is supported). Vitest checks that templates show a risk's
  consequence and confidence.

## Not in this issue

Quality evidence (#186); the Java runtime from Dockerfiles or build images (later extractors);
end-of-life data for other runtimes, frameworks and libraries; paid extended-support dates; and a
check that the table is current.

## Amendment (#369): the Architecture Explorer and the Executive Brief are out of scope for now (`docs/design.md`, Non-goals; `docs/quality-bar.md`, Scope). What this ADR built stays; it gets no further work until the user brings the document back into scope.
