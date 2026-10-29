# 0063: Test evidence: test classes, architecture tests and coverage tools

Status: accepted (#226), amends ADR 0006 and ADR 0062, pending the user's review (#24)

## Context

`docs/design.md` (Quality bar) asks the Executive Brief for quality evidence: tests, architecture
tests and coverage where present. ADR 0062 could only name the test libraries modules declare,
since no extractor read test sources or build plugins. The design's knowledge model lets new
entity, relationship and attribute types be added as vocabulary data.

## Decisions

- **A test extractor.** `TestExtractor` in `lantern-extract` (name `test`, vocabulary source
  `test-extractor`) reads each module's test sources from git at the commit, never the working
  tree, and parses each `.java` file with JavaParser without resolving types; it never compiles or
  runs them, and uses no build. Modules are found as the Maven extractor finds them. A module's
  test sources are the `<build><testSourceDirectory>` its POM as written sets, when that is a
  directory inside the module (relative, or under `${project.basedir}` or `${basedir}`), else
  `src/test/java`. `package-info.java`, `module-info.java` and files over 4 MB are skipped, as is
  a file that does not parse.
- **What a test is.** A method annotated with JUnit 5's `@Test`, `@RepeatedTest`, `@TestFactory`,
  `@TestTemplate` or `@ParameterizedTest`, JUnit 4's or TestNG's `@Test`, or ArchUnit's `@ArchTest`;
  each variable of a field annotated `@ArchTest`; a public, non-static, non-abstract `void` method
  without annotations in a class TestNG's `@Test` annotates; and a public `void test...()` method
  without parameters in a subclass of JUnit 3's `TestCase`. An abstract class is not a test class:
  its tests run in its subclasses, which count them only if they declare tests of their own. An annotation counts when its name as
  written is the qualified name, or the simple name with a single-type or on-demand import of
  it, so a project's own `@Test` does not count. A parameterized test counts once, not per
  invocation. Spock, Kotlin and other languages are not read.
- **Test classes.** A top-level class declaring at least one test, its member classes' tests
  (such as JUnit 5's `@Nested`) included, becomes a `test-class` entity,
  `test-class:<groupId>:<artifactId>:<binary name>`: keyed by module like a package, since test
  classes of the same name are common across modules. Its fact is `test-class TESTS module`, with
  the attribute `tests` (how many it declares) and `architecture: true` when its file imports
  ArchUnit (`com.tngtech.archunit`), Taikai (`com.enofex.taikai`), jMolecules' ArchUnit rules
  (`org.jmolecules.archunit`) or Spring Modulith's module model (`org.springframework.modulith.core`,
  whose `verify()` checks module boundaries). Evidence is the file and the class's lines.
  Confidence is 0.9: annotations are matched by their imports, not resolved. The module is the
  object, so its own facts, and so its summary prompt, do not change (the summarizer shows a unit
  the facts it is the subject of). `test-class` has no parent and no C4 level, and `TESTS` no
  parent, so neither reaches diagrams or dependency views.
- **Coverage tools (amends ADR 0006).** The Maven extractor (now version 3) gives each module whose
  build plugins (not plugin management) include a coverage plugin a `coverage-tool` attribute fact
  whose value is the plugin's `groupId:artifactId`: JaCoCo, Cobertura, OpenClover or Clover. A
  plugin skipped with `<skip>` or, for JaCoCo, the `jacoco.skip` property, gives none. Its
  attributes are `goals`, the goals its executions bind, sorted, and for JaCoCo `limits`: each limit
  of the rules a `check` execution enforces (the execution's rules, else the plugin's), as
  `element`, `counter`, `value` and `minimum` or `maximum`, with JaCoCo's defaults (`BUNDLE`,
  `INSTRUCTION`, `COVEREDRATIO`) filled in. A limit whose values do not resolve is left out.
  Evidence is the plugin in the module's own POM, else its POM without lines, as for an inherited
  dependency. Thresholds of other tools are not read. Without an effective POM, a module's own
  declaration of the plugin replaces its parent's whole (ADR 0006), so goals, limits or a skip set
  only in the parent's declaration or plugin management are missed.
- **The brief (amends ADR 0062).** The quality evidence section, for the modules that build code,
  counted by the services and libraries building them:
  - a sentence counting the tests and test classes, in how many of the services and libraries,
    how many of the classes are architecture tests, and which have no test classes, citing every
    `TESTS` fact;
  - a sentence naming the coverage tools and in how many builds they run, and which builds check a
    minimum coverage, worded from JaCoCo's bundle-wide covered-ratio minimums, written as a ratio
    or a percentage ("80% of lines"),
    citing the `coverage-tool` facts;
  - a table "Tests and coverage" with a row per service or library with test classes or a
    coverage tool: test classes, tests, architecture test classes and coverage tool with its
    minimums, each row citing its facts;
  - then the test libraries sentence and table of ADR 0062. Its sentence no longer says Lantern
    found no architecture tests when test classes are architecture tests.
  The limits note says the tests are counted, not run, so whether they pass is not known, and that
  coverage reports are not read, so the coverage reached is not known; with no test classes, that a
  declared library or coverage tool shows a module is set up for tests, not that it has any. With
  no test class, coverage tool or library, a note says Lantern found no evidence. Names carry no
  class names, coordinates or versions: the brief has no implementation detail.
- **Wiring.** The app applies the vocabulary after the messaging extractor's and runs the
  extractor in each analysis after the SQL extractor.

## Not in this issue

Coverage reports: the sandbox builds with tests skipped (ADR 0005), so no build produces one;
reading them needs a design decision first (#232). Test sources of Gradle builds, Kotlin and
Groovy, and test frameworks beyond those above.
