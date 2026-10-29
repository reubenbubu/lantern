# 0062: Executive Brief quality evidence

Status: accepted (#186), amends ADR 0046, pending the user's review (#24)

## Context

`docs/design.md` (Quality bar) asks the Executive Brief for quality evidence: tests, architecture
tests and coverage where present, with quality goals marked unrecorded when absent. ADR 0046
checks for a top-level section `quality_evidence`, which no section kind composed, so every brief
failed that check. Of what this needs, the extractors record only each module's direct
dependencies with their scope (`BUILD_DEPENDS_ON`, ADR 0006), test scope included. They do not
read test sources, build plugins (such as a coverage plugin) or coverage reports, and nothing
records quality goals: annotations come in Phase 3.

## Decisions

- **A new section kind**, `quality_evidence` ("Quality evidence"), at L0 and L1, the ID ADR 0046's
  check looks for. `ViewDefinition.executiveBrief` lists it after technology currency. Default
  views are not stored (ADR 0047), so every workspace gets it.
- **Evidence from test-scoped dependencies.** For each of the repository's modules that builds code
  (not a `pom` module), its test-scoped dependencies on artifacts are matched, by the start of
  their `groupId:artifactId`, against a short list of test libraries bundled in the composer
  (`BriefSections.TEST_LIBRARIES`), each with a name and a kind: test framework (JUnit, TestNG,
  Spock, Cucumber, Spring Boot Test, Spring Test), mocking and assertions (Mockito, AssertJ,
  Hamcrest, EasyMock, Truth), integration testing (Testcontainers, WireMock, Rest Assured,
  Awaitility, the Kafka and RabbitMQ test support of Spring, Spring Cloud Contract), architecture
  tests (ArchUnit, jMolecules' ArchUnit rules, Taikai) and coverage (JaCoCo). Dependencies not on
  the list are left out. Names carry no version or coordinates: the brief has no implementation
  detail.
- **What the section says.** A sentence counts the services and libraries building those modules
  that declare a test library in any of their modules, names the libraries by kind, says when none
  is an architecture test library, and names the services and libraries declaring none, citing
  every dependency it uses. A service of several modules counts once, as the table names it. A table lists each library with its kind and
  the services or libraries declaring it, each row citing its dependencies. A note then says that a
  declared library shows a module is set up for tests, not how many tests it has or whether they
  pass, and that coverage is not known. With no library found, a note says Lantern found no
  evidence of tests, architecture tests or coverage; with no module building code, that there is
  nothing to look for. A last note always says quality goals are unrecorded: nothing in the
  snapshot states them. Notes cite nothing (ADR 0046).
- **No risk.** Missing tests or architecture tests are not made a risk: a declared library is weak
  evidence either way, and the Quality bar asks only for the evidence.
- **Quality bar.** The brief now passes ADR 0046's check for quality evidence.
- **Checks.** `ExecutiveBriefTest` covers the section on the fixture facts, `QualityBarTest` the
  passing brief, and the golden briefs hold the section of the fixture repositories, which declare
  no test library.

## Not in this issue

Test classes and architecture test classes in test sources, coverage plugins in the build and the
reports they produce, all needing extractor work (#226); quality goals from annotations
(Phase 3).
