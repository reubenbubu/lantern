# 0050: Executive Brief blast radius, technology currency and ranked risks

Status: accepted (#164), pending the user's review (#24)

## Context

`docs/design.md` (Quality bar) asks the Executive Brief for who depends on the repository and what
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
  (<https://adoptium.net/support/>), the free OpenJDK build most containers use; Spring Boot and
  Spring Framework the end of open-source support (<https://spring.io/projects/spring-boot#support>,
  <https://spring.io/projects/spring-framework#support>), not the paid extension. The days are as
  <https://endoflife.date/eclipse-temurin>, <https://endoflife.date/spring-boot> and
  <https://endoflife.date/spring-framework> gave them on 2026-10-29, checked against Adoptium's
  roadmap, which gives months. The table records that as-of date and the brief shows it. It is
  updated each release. A version older than a product's oldest line in the table has ended; one
  between or after its lines (Java 9, a future Spring Boot) is shown as not in the table.
- **Technology currency** lists each version in use: Spring Boot and Java from the units'
  `technology` facts; Spring Framework from direct dependencies outside test scope on
  `org.springframework:spring-*` artifacts with a version, in the repository's modules or those its
  units run (with Spring Boot it is usually managed, so not shown); with no unit, Java from the
  modules' `java-release`. A row names the product, the version, who uses it and the end of its
  line's free support: the date, "Ended" and the date, "Ends" and the date when within six months,
  or why it is not known. Support is judged **as of the snapshot's date** (`createdAt`, UTC), not
  the day of composing, so composing stays deterministic. A note says where the dates come from
  and that the Java version is the release the code is compiled for: the Java runtime is not
  recorded and may be newer.
- **Risks from technology currency**, before the other checks among risks of the same severity:
  framework versions past their end of free support (high) or within six months of it (medium);
  Java releases past it (medium) or within six months (low).
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
- **Checks.** `ExecutiveBriefTest` covers both sections and the new risks on the fixture facts
  with links (`TestFacts.runtime`), `EndOfLifeTest` the table, `QualityBarTest` the risk check,
  and the golden briefs hold the sections of the fixture repositories (their snapshot is dated
  1970, so every version there is supported). Vitest checks that templates show a risk's
  consequence and confidence.

## Not in this issue

Quality evidence (#186); the Java runtime from Dockerfiles or build images (later extractors);
end-of-life data for other runtimes, frameworks and libraries; paid extended-support dates; and a
check that the table is current.
