package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.Elements;
import io.lantern.derive.EndOfLife;
import io.lantern.derive.TechnologyCurrency;
import io.lantern.derive.TestEvidence;
import io.lantern.derive.attributes.Types;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Executive Brief's sections on one repository's blast radius and technology currency, and the
 * risks technology currency finds (ADR 0052), and its quality evidence (ADR 0062).
 *
 * <p>The blast radius is read from the linker's links (ADR 0042) of the deployable units in scope:
 * those running the repository's code. Technology currency compares the units' runtime and
 * framework versions (ADR 0039) with the end-of-life table bundled with Lantern, as of the
 * snapshot's date, so composing stays deterministic.
 */
final class BriefSections {

  /** The factor a Java risk's confidence gets: the Java runtime a unit runs on is not recorded. */
  private static final BigDecimal JAVA_FACTOR = new BigDecimal("0.5");

  private final ArchitectureModel model;
  private final Assembler assembler;
  private final String repo;
  private final SortedSet<EntityId> modules;
  private final SortedSet<EntityId> units;
  private final Set<Fact> replaced;
  private final LocalDate asOf;
  private final EndOfLife table;
  private List<TechnologyCurrency.Version> versions;

  /**
   * @param repo the repository the brief is about
   * @param modules the repository's modules
   * @param units the deployable units running the repository's code
   * @param replaced the profile-only links that stand in for a default link, left out as the
   *     diagrams leave them out
   * @param asOf the snapshot's date, against which support is judged
   */
  BriefSections(
      ArchitectureModel model,
      Assembler assembler,
      String repo,
      SortedSet<EntityId> modules,
      SortedSet<EntityId> units,
      Set<Fact> replaced,
      LocalDate asOf,
      EndOfLife table) {
    this.model = model;
    this.assembler = assembler;
    this.repo = repo;
    this.modules = modules;
    this.units = units;
    this.replaced = replaced;
    this.asOf = asOf;
    this.table = table;
  }

  // Blast radius.

  /** One row of a blast radius table: an entity and how it is connected, with the facts. */
  private static final class Row {
    final EntityId entity;
    final Map<String, Set<String>> hows = new TreeMap<>();
    final List<Fact> facts = new ArrayList<>();

    Row(EntityId entity) {
      this.entity = entity;
    }

    void add(String how, Fact fact) {
      String profile = ArchitectureModel.view(fact);
      Set<String> profiles = hows.computeIfAbsent(how, h -> new TreeSet<>());
      // An empty string stands for "without a profile", so the connection always holds.
      profiles.add(profile == null ? "" : profile);
      facts.add(fact);
    }

    String how() {
      List<String> parts = new ArrayList<>();
      hows.forEach(
          (how, profiles) -> {
            if (profiles.contains("")) {
              parts.add(how);
            } else {
              parts.add(
                  how
                      + " (only under the Spring "
                      + (profiles.size() == 1 ? "profile " : "profiles ")
                      + String.join(", ", profiles)
                      + ")");
            }
          });
      return String.join("; ", parts);
    }
  }

  /**
   * Who depends on the repository's deployable units and what they depend on: where its code runs
   * when other repositories' units bundle it, the containers that call them, receive their messages
   * or read their tables, and the containers, external systems and databases they use.
   */
  List<Block> blastRadius(String section) {
    List<Block> blocks = new ArrayList<>();
    if (units.isEmpty()) {
      blocks.add(
          note(
              assembler.id(section, "none"),
              repo
                  + " has no deployable unit and no unit in the snapshot runs its code, so nothing"
                  + " depends on it at runtime as far as the snapshot shows."));
      return blocks;
    }
    SortedMap<EntityId, Row> dependents = new TreeMap<>(ArchitectureModel.BY_ID);
    SortedMap<EntityId, Row> dependencies = new TreeMap<>(ArchitectureModel.BY_ID);
    for (EntityId unit : units) {
      String name = assembler.name(unit);
      for (Fact fact : links(unit, Types.CALLS_HTTP)) {
        add(dependencies, ArchitectureModel.object(fact), name + " calls it", fact);
      }
      for (Fact fact : links(unit, Types.SENDS_MESSAGES_TO)) {
        add(dependents, ArchitectureModel.object(fact), "receives messages from " + name, fact);
      }
      for (Fact fact : links(unit, Types.ACCESSES_DATA_OF)) {
        add(dependencies, ArchitectureModel.object(fact), name + " " + tables(fact, "its"), fact);
      }
      for (Fact fact : links(unit, Types.USES_DATABASE)) {
        boolean owns = Elements.ownsSchemas(fact);
        add(
            dependencies,
            ArchitectureModel.object(fact),
            name + (owns ? " stores its data in it" : " connects to it"),
            fact);
      }
    }
    for (EntityId other : Elements.units(model)) {
      if (units.contains(other)) {
        continue;
      }
      for (Fact fact : links(other, Types.CALLS_HTTP)) {
        if (units.contains(ArchitectureModel.object(fact))) {
          add(dependents, other, "calls " + assembler.name(ArchitectureModel.object(fact)), fact);
        }
      }
      for (Fact fact : links(other, Types.SENDS_MESSAGES_TO)) {
        if (units.contains(ArchitectureModel.object(fact))) {
          add(
              dependencies,
              other,
              assembler.name(ArchitectureModel.object(fact)) + " receives its messages",
              fact);
        }
      }
      for (Fact fact : links(other, Types.ACCESSES_DATA_OF)) {
        if (units.contains(ArchitectureModel.object(fact))) {
          add(
              dependents,
              other,
              tables(fact, "the") + " of " + assembler.name(ArchitectureModel.object(fact)),
              fact);
        }
      }
    }
    runsIn(section).ifPresent(blocks::add);
    List<Fact> all = new ArrayList<>();
    dependents.values().forEach(r -> all.addAll(r.facts));
    dependencies.values().forEach(r -> all.addAll(r.facts));
    if (all.isEmpty()) {
      blocks.add(
          note(
              assembler.id(section, "none"),
              "Lantern found no container in the snapshot that depends on "
                  + unitsPhrase()
                  + ", and nothing "
                  + (units.size() == 1 ? "it depends" : "they depend")
                  + " on. Dependents outside the snapshot are not"
                  + " known."));
    } else {
      blocks.add(
          new Paragraph(
              assembler.id(section, "summary"),
              List.of(TextRun.of(summary(dependents, dependencies))),
              assembler.facts(all)));
      if (!dependents.isEmpty()) {
        blocks.add(
            table(assembler.id(section, "dependents"), "Who depends on it", "Who", dependents));
      }
      if (!dependencies.isEmpty()) {
        blocks.add(
            table(
                assembler.id(section, "dependencies"), "What it depends on", "What", dependencies));
      }
    }
    int unresolved = 0;
    for (EntityId unit : units) {
      unresolved +=
          (int)
              model.attributes(unit, Types.UNRESOLVED_LINK).stream()
                  .filter(f -> !model.resolved(f))
                  .count();
    }
    if (unresolved > 0) {
      blocks.add(
          note(
              assembler.id(section, "unresolved"),
              (unresolved == 1
                      ? "1 call, message or database connection"
                      : unresolved + " calls, messages or database connections")
                  + " of "
                  + unitsPhrase()
                  + " could not be traced to "
                  + (unresolved == 1 ? "its" : "their")
                  + " target, so "
                  + repo
                  + " may depend on more than this shows."));
    }
    return blocks;
  }

  /**
   * The unit's links of the relationship, without those standing in for a default link and those
   * between two units in scope.
   */
  private List<Fact> links(EntityId unit, String relationship) {
    return model.relations(unit, relationship).stream()
        .filter(f -> ArchitectureModel.isEntity(f) && !replaced.contains(f))
        .filter(f -> !(units.contains(unit) && units.contains(ArchitectureModel.object(f))))
        .toList();
  }

  private static void add(SortedMap<EntityId, Row> rows, EntityId entity, String how, Fact fact) {
    rows.computeIfAbsent(entity, Row::new).add(how, fact);
  }

  /** Whether a table access reads, writes or does both, of the tables named by the article. */
  private static String tables(Fact fact, String article) {
    boolean reads = Elements.readsTables(fact);
    boolean writes = Elements.writesTables(fact);
    String verb = reads && !writes ? "reads" : writes && !reads ? "writes" : "reads and writes";
    return verb + " " + article + " tables";
  }

  private String unitsPhrase() {
    return units.size() == 1
        ? assembler.name(units.first())
        : "the deployable units running " + repo + "'s code";
  }

  /**
   * Units of other repositories that bundle the repository's libraries, so its code runs in them,
   * each with the repositories it is built in.
   */
  private Optional<ListBlock> runsIn(String section) {
    List<ListBlock.Item> items = new ArrayList<>();
    for (EntityId unit : units) {
      if (model.parts(unit, BuiltInTypes.MODULE).stream().anyMatch(modules::contains)) {
        continue;
      }
      List<Fact> facts = new ArrayList<>();
      for (Fact fact : model.relations(unit, Types.BUNDLES)) {
        if (model.parts(ArchitectureModel.object(fact), BuiltInTypes.MODULE).stream()
            .anyMatch(modules::contains)) {
          facts.add(fact);
        }
      }
      if (facts.isEmpty()) {
        continue;
      }
      assembler.entity(unit);
      List<Inline> content = new ArrayList<>();
      content.add(new CrossReference(assembler.name(unit), CrossReference.Target.entity(unit)));
      SortedSet<String> built = repos(unit);
      content.add(
          TextRun.of(built.isEmpty() ? "" : ", built in " + String.join(" and ", built) + ","));
      content.add(TextRun.of(" runs its code"));
      items.add(new ListBlock.Item(content, assembler.facts(facts)));
    }
    if (items.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new ListBlock(assembler.id(section, "runs-in"), false, items, List.of()));
  }

  /** The repositories a unit is built in: those of its own modules. */
  private SortedSet<String> repos(EntityId unit) {
    SortedSet<String> repos = new TreeSet<>();
    for (EntityId module : model.parts(unit, BuiltInTypes.MODULE)) {
      repos.addAll(model.repos(module));
    }
    if (repos.isEmpty()) {
      for (EntityId module : Elements.codeModules(model, unit)) {
        repos.addAll(model.repos(module));
      }
    }
    return repos;
  }

  private String summary(Map<EntityId, Row> dependents, Map<EntityId, Row> dependencies) {
    StringBuilder text = new StringBuilder();
    text.append(
        dependents.isEmpty()
            ? "No container in the snapshot depends on " + unitsPhrase() + ". "
            : count(dependents.size(), "container", "containers")
                + " in the snapshot "
                + (dependents.size() == 1 ? "depends" : "depend")
                + " on "
                + unitsPhrase()
                + ". ");
    Map<String, Integer> kinds = new LinkedHashMap<>();
    kinds.put("container", 0);
    kinds.put("external system", 0);
    kinds.put("database", 0);
    for (EntityId entity : dependencies.keySet()) {
      String kind =
          switch (entity.type()) {
            case Types.EXTERNAL_SYSTEM -> "external system";
            case Types.DATABASE -> "database";
            default -> "container";
          };
      kinds.merge(kind, 1, Integer::sum);
    }
    List<String> parts = new ArrayList<>();
    kinds.forEach(
        (kind, n) -> {
          if (n > 0) {
            parts.add(count(n, kind, kind + "s"));
          }
        });
    String subject = units.size() == 1 ? "It" : "They";
    if (parts.isEmpty()) {
      text.append(subject).append(" depend").append(units.size() == 1 ? "s" : "").append(" on");
      text.append(" no other container, external system or database.");
    } else {
      text.append(subject)
          .append(" depend")
          .append(units.size() == 1 ? "s" : "")
          .append(" on ")
          .append(join(parts))
          .append('.');
    }
    return text.toString();
  }

  private Table table(String id, String title, String first, Map<EntityId, Row> rows) {
    List<Table.Row> result = new ArrayList<>();
    for (Row row : rows.values()) {
      assembler.entity(row.entity);
      result.add(
          new Table.Row(
              row.entity,
              List.of(
                  List.of(
                      new CrossReference(
                          assembler.name(row.entity), CrossReference.Target.entity(row.entity))),
                  List.of(TextRun.of(kind(row.entity))),
                  List.of(TextRun.of(row.how()))),
              assembler.facts(row.facts)));
    }
    return new Table(
        id,
        title,
        List.of(new Table.Column(first), new Table.Column("Kind"), new Table.Column("How")),
        result,
        List.of());
  }

  private String kind(EntityId entity) {
    return switch (entity.type()) {
      case Types.EXTERNAL_SYSTEM -> "External system";
      case Types.DATABASE ->
          model.attribute(entity, Types.TECHNOLOGY).map(t -> t + " database").orElse("Database");
      default -> {
        SortedSet<String> built = repos(entity);
        yield built.isEmpty()
            ? "Container"
            : "Container in "
                + (built.size() == 1 ? "repository " : "repositories ")
                + String.join(", ", built);
      }
    };
  }

  // Technology currency.

  /** The versions in use, each with the end of its line's free support (ADR 0039). */
  private List<TechnologyCurrency.Version> versions() {
    if (versions == null) {
      versions = TechnologyCurrency.versions(model, table, units, modules);
    }
    return versions;
  }

  /**
   * The runtime and framework versions the repository's code runs on, each with the end of its
   * line's free support as of the snapshot's date; then where the dates come from.
   */
  List<Block> technologyCurrency(String section) {
    List<Block> blocks = new ArrayList<>();
    List<TechnologyCurrency.Version> all = versions();
    if (all.isEmpty()) {
      blocks.add(
          note(
              assembler.id(section, "none"),
              "Lantern found no Java, Spring Boot or Spring Framework version in the code of "
                  + repo
                  + "."));
      return blocks;
    }
    List<Fact> facts = new ArrayList<>();
    all.forEach(v -> facts.addAll(v.facts()));
    blocks.add(
        new Paragraph(
            assembler.id(section, "summary"),
            List.of(TextRun.of(TechnologyCurrency.summary(all, asOf))),
            assembler.facts(facts)));
    List<Table.Row> rows = new ArrayList<>();
    for (TechnologyCurrency.Version v : all) {
      List<Inline> users = new ArrayList<>();
      for (EntityId user : v.usedBy()) {
        assembler.entity(user);
        if (!users.isEmpty()) {
          users.add(TextRun.of(", "));
        }
        users.add(new CrossReference(assembler.name(user), CrossReference.Target.entity(user)));
      }
      rows.add(
          new Table.Row(
              null,
              List.of(
                  List.of(TextRun.of(v.product().title())),
                  List.of(TextRun.of(v.version() == null ? "Not known" : v.version())),
                  users,
                  List.of(TextRun.of(TechnologyCurrency.supportText(v, asOf)))),
              assembler.facts(v.facts())));
    }
    blocks.add(
        new Table(
            assembler.id(section, "versions"),
            "Runtime and framework versions",
            List.of(
                new Table.Column("Technology"),
                new Table.Column("Version"),
                new Table.Column("Used by"),
                new Table.Column("End of free support")),
            rows,
            List.of()));
    blocks.add(
        note(
            assembler.id(section, "source"),
            "End-of-life dates come from the table bundled with this version of Lantern, checked"
                + " on "
                + TechnologyCurrency.date(table.asOf())
                + ": Eclipse Temurin's for Java, and the end of open-source support for Spring"
                + " Boot and Spring Framework. The Java version is the release the code is"
                + " compiled for; the Java runtime it runs on is not recorded and may be newer."));
    return blocks;
  }

  /**
   * The risks technology currency finds: framework versions past their end of free support (high)
   * or reaching it within six months (medium), and Java releases past it (medium) or reaching it
   * (low), whose confidence is halved since the Java runtime is not recorded.
   */
  List<Callout> currencyRisks(String section) {
    List<Callout> risks = new ArrayList<>();
    List<TechnologyCurrency.Version> frameworks =
        versions().stream().filter(v -> v.product() != EndOfLife.Product.JAVA).toList();
    List<TechnologyCurrency.Version> java =
        versions().stream().filter(v -> v.product() == EndOfLife.Product.JAVA).toList();
    currencyRisk(
            section,
            "framework-ended",
            frameworks.stream().filter(v -> TechnologyCurrency.ended(v, asOf)).toList(),
            Callout.Severity.HIGH,
            "Unsupported framework versions",
            " past the end of free support: ",
            "It gets no more free security or bug fixes; staying on these versions needs paid"
                + " support or an upgrade.",
            BigDecimal.ONE)
        .ifPresent(risks::add);
    currencyRisk(
            section,
            "framework-ending",
            frameworks.stream().filter(v -> TechnologyCurrency.endsSoon(v, asOf)).toList(),
            Callout.Severity.MEDIUM,
            "Framework support ends soon",
            " whose free support ends within six months: ",
            "Once support ends it gets no more free security fixes, so the upgrade needs planning"
                + " now.",
            BigDecimal.ONE)
        .ifPresent(risks::add);
    currencyRisk(
            section,
            "java-ended",
            java.stream().filter(v -> TechnologyCurrency.ended(v, asOf)).toList(),
            Callout.Severity.MEDIUM,
            "Built for an unsupported Java release",
            " past the end of free support: ",
            "If it also runs on that release it gets no more free security fixes; the Java"
                + " runtime it runs on is not recorded, so it may be newer.",
            JAVA_FACTOR)
        .ifPresent(risks::add);
    currencyRisk(
            section,
            "java-ending",
            java.stream().filter(v -> TechnologyCurrency.endsSoon(v, asOf)).toList(),
            Callout.Severity.LOW,
            "Java release support ends soon",
            " whose free support ends within six months: ",
            "A runtime of that release gets no more free security fixes once support ends, so"
                + " moving to a newer one needs planning.",
            JAVA_FACTOR)
        .ifPresent(risks::add);
    return risks;
  }

  private Optional<Callout> currencyRisk(
      String section,
      String id,
      List<TechnologyCurrency.Version> found,
      Callout.Severity severity,
      String title,
      String lead,
      String consequence,
      BigDecimal factor) {
    // A version is a risk when the end-of-life check stored a finding for it (#619); the risk
    // cites the facts its findings rest on.
    Set<Fact> raised = TechnologyCurrency.raisedEndOfLife(model);
    List<Fact> facts = new ArrayList<>();
    List<String> named = new ArrayList<>();
    for (TechnologyCurrency.Version v : found) {
      List<Fact> cited = v.facts().stream().filter(raised::contains).toList();
      if (cited.isEmpty()) {
        continue;
      }
      facts.addAll(cited);
      List<String> users = new ArrayList<>();
      v.usedBy().forEach(u -> users.add(assembler.name(u)));
      named.add(
          v.product().title()
              + " "
              + v.version()
              + " ("
              + TechnologyCurrency.riskSupportText(v, asOf)
              + ") in "
              + join(users));
    }
    if (named.isEmpty()) {
      return Optional.empty();
    }
    String text =
        repo
            + "'s code runs on "
            + (named.size() == 1 ? "a version" : named.size() + " versions")
            + lead
            + names(named);
    return Optional.of(
        new Callout(
            assembler.id(section, id),
            Callout.Variant.RISK,
            severity,
            title,
            List.of(TextRun.of(text)),
            assembler.facts(facts),
            consequence,
            confidence(facts, factor)));
  }

  /** The lowest confidence of the facts times the factor, to two decimal places. */
  static BigDecimal confidence(List<Fact> facts, BigDecimal factor) {
    double lowest = facts.stream().mapToDouble(Fact::confidence).min().orElse(1.0);
    return BigDecimal.valueOf(lowest)
        .multiply(factor)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }

  // Quality evidence.

  /** What a test library is for, in the order the section lists them. */
  enum TestKind {
    FRAMEWORK("Test framework", "a test framework", "test frameworks"),
    MOCKING(
        "Mocking and assertions",
        "a mocking or assertion library",
        "mocking and assertion libraries"),
    INTEGRATION("Integration testing", "an integration testing tool", "integration testing tools"),
    ARCHITECTURE(
        "Architecture tests", "an architecture test library", "architecture test libraries"),
    COVERAGE("Coverage", "a coverage tool", "coverage tools");

    final String title;
    final String one;
    final String many;

    TestKind(String title, String one, String many) {
      this.title = title;
      this.one = one;
      this.many = many;
    }
  }

  /** A known test library: the start of its {@code groupId:artifactId}, its name and kind. */
  record TestLibrary(String prefix, String name, TestKind kind) {}

  /** The test libraries Lantern knows, matched on the start of a dependency's coordinates. */
  static final List<TestLibrary> TEST_LIBRARIES =
      List.of(
          new TestLibrary("junit:", "JUnit", TestKind.FRAMEWORK),
          new TestLibrary("org.junit:", "JUnit", TestKind.FRAMEWORK),
          new TestLibrary("org.junit.", "JUnit", TestKind.FRAMEWORK),
          new TestLibrary("org.testng:", "TestNG", TestKind.FRAMEWORK),
          new TestLibrary("org.spockframework:", "Spock", TestKind.FRAMEWORK),
          new TestLibrary("io.cucumber:", "Cucumber", TestKind.FRAMEWORK),
          new TestLibrary(
              "org.springframework.boot:spring-boot-starter-test",
              "Spring Boot Test",
              TestKind.FRAMEWORK),
          new TestLibrary(
              "org.springframework.boot:spring-boot-test", "Spring Boot Test", TestKind.FRAMEWORK),
          new TestLibrary("org.springframework:spring-test", "Spring Test", TestKind.FRAMEWORK),
          new TestLibrary("org.mockito:", "Mockito", TestKind.MOCKING),
          new TestLibrary("org.assertj:", "AssertJ", TestKind.MOCKING),
          new TestLibrary("org.hamcrest:", "Hamcrest", TestKind.MOCKING),
          new TestLibrary("org.easymock:", "EasyMock", TestKind.MOCKING),
          new TestLibrary("com.google.truth:", "Truth", TestKind.MOCKING),
          new TestLibrary("org.testcontainers:", "Testcontainers", TestKind.INTEGRATION),
          new TestLibrary("com.github.tomakehurst:wiremock", "WireMock", TestKind.INTEGRATION),
          new TestLibrary("org.wiremock:", "WireMock", TestKind.INTEGRATION),
          new TestLibrary("io.rest-assured:", "Rest Assured", TestKind.INTEGRATION),
          new TestLibrary("org.awaitility:", "Awaitility", TestKind.INTEGRATION),
          new TestLibrary(
              "org.springframework.kafka:spring-kafka-test",
              "Spring for Apache Kafka Test",
              TestKind.INTEGRATION),
          new TestLibrary(
              "org.springframework.amqp:spring-rabbit-test",
              "Spring for RabbitMQ Test",
              TestKind.INTEGRATION),
          new TestLibrary(
              "org.springframework.cloud:spring-cloud-contract-",
              "Spring Cloud Contract",
              TestKind.INTEGRATION),
          new TestLibrary(
              "org.springframework.cloud:spring-cloud-starter-contract-",
              "Spring Cloud Contract",
              TestKind.INTEGRATION),
          new TestLibrary("com.tngtech.archunit:", "ArchUnit", TestKind.ARCHITECTURE),
          new TestLibrary(
              "org.jmolecules.integrations:jmolecules-archunit",
              "jMolecules ArchUnit rules",
              TestKind.ARCHITECTURE),
          new TestLibrary("com.enofex:taikai", "Taikai", TestKind.ARCHITECTURE),
          new TestLibrary("org.jacoco:", "JaCoCo", TestKind.COVERAGE));

  /** The known test library a dependency's coordinates name, if any. */
  static Optional<TestLibrary> testLibrary(String coordinates) {
    return TEST_LIBRARIES.stream().filter(l -> coordinates.startsWith(l.prefix())).findFirst();
  }

  /** A test library in use: where it is declared and the facts saying so. */
  private record UsedLibrary(TestLibrary library, SortedSet<EntityId> usedBy, List<Fact> facts) {}

  /** The coverage tools Lantern names, by their Maven plugin's {@code groupId:artifactId}. */
  static final Map<String, String> COVERAGE_TOOLS =
      Map.of(
          "org.jacoco:jacoco-maven-plugin", "JaCoCo",
          "org.codehaus.mojo:cobertura-maven-plugin", "Cobertura",
          "org.openclover:clover-maven-plugin", "OpenClover",
          "com.atlassian.maven.plugins:clover-maven-plugin", "Clover",
          "com.atlassian.maven.plugins:maven-clover2-plugin", "Clover");

  /** The test evidence of one service or library: its test classes and coverage tools. */
  private static final class Tested {
    int classes;
    int tests;
    int architecture;
    final SortedSet<String> tools = new TreeSet<>();
    final SortedSet<String> minimums = new TreeSet<>();
    final List<Fact> facts = new ArrayList<>();
    final List<Fact> testFacts = new ArrayList<>();
    final List<Fact> coverageFacts = new ArrayList<>();
  }

  /**
   * The repository's test evidence: the test classes in the test sources of its modules that build
   * code, the coverage tools their builds run, and the test libraries they declare in test scope,
   * by kind; then what is not known: whether the tests pass, the coverage they reach and the
   * quality goals.
   */
  List<Block> qualityEvidence(String section) {
    List<Block> blocks = new ArrayList<>();
    List<EntityId> code =
        modules.stream()
            .filter(m -> !model.attribute(m, Types.PACKAGING).orElse("jar").equals("pom"))
            .toList();
    if (code.isEmpty()) {
      blocks.add(
          note(
              assembler.id(section, "none"),
              repo + " has no module that builds code, so it has no tests to look for."));
    } else {
      Map<String, UsedLibrary> used = new TreeMap<>();
      // Counted and named by what builds the modules, so a service of several modules is one.
      SortedSet<EntityId> builders = new TreeSet<>(ArchitectureModel.BY_ID);
      SortedSet<EntityId> with = new TreeSet<>(ArchitectureModel.BY_ID);
      Map<EntityId, Tested> tested = new TreeMap<>(ArchitectureModel.BY_ID);
      Map<EntityId, List<TestEvidence.TestClass>> testClasses = new LinkedHashMap<>();
      for (TestEvidence.TestClass test : TestEvidence.testClasses(model)) {
        testClasses.computeIfAbsent(test.module(), m -> new ArrayList<>()).add(test);
      }
      for (EntityId module : code) {
        EntityId builder = model.builderOf(module);
        builders.add(builder);
        for (TestEvidence.TestClass test : testClasses.getOrDefault(module, List.of())) {
          Tested t = tested.computeIfAbsent(builder, b -> new Tested());
          t.classes++;
          t.tests += test.tests();
          if (test.architecture()) {
            t.architecture++;
          }
          t.facts.add(test.fact());
          t.testFacts.add(test.fact());
        }
        for (TestEvidence.Coverage coverage : TestEvidence.coverage(model, module)) {
          String tool = COVERAGE_TOOLS.get(coverage.plugin());
          if (tool != null) {
            Tested t = tested.computeIfAbsent(builder, b -> new Tested());
            t.tools.add(tool);
            t.minimums.addAll(coverage.minimums());
            t.facts.add(coverage.fact());
            t.coverageFacts.add(coverage.fact());
          }
        }
        for (Fact fact : model.relations(module, Types.BUILD_DEPENDS_ON)) {
          EntityId target = ArchitectureModel.object(fact);
          if (!ArchitectureModel.isEntity(fact)
              || !target.type().equals(Types.ARTIFACT)
              || !ArchitectureModel.isTestScope(fact)) {
            continue;
          }
          Optional<TestLibrary> library = testLibrary(target.key());
          if (library.isEmpty()) {
            continue;
          }
          with.add(builder);
          UsedLibrary u =
              used.computeIfAbsent(
                  library.get().kind().ordinal()
                      + " "
                      + library.get().name().toLowerCase(Locale.ROOT),
                  k ->
                      new UsedLibrary(
                          library.get(),
                          new TreeSet<>(ArchitectureModel.BY_ID),
                          new ArrayList<>()));
          u.usedBy().add(builder);
          u.facts().add(fact);
        }
      }
      int classes = tested.values().stream().mapToInt(t -> t.classes).sum();
      int architecture = tested.values().stream().mapToInt(t -> t.architecture).sum();
      if (classes > 0) {
        blocks.add(testsParagraph(assembler.id(section, "tests"), builders, tested));
      }
      if (tested.values().stream().anyMatch(t -> !t.tools.isEmpty())) {
        blocks.add(coverageParagraph(assembler.id(section, "coverage"), builders, tested));
      }
      if (!tested.isEmpty()) {
        blocks.add(testsTable(assembler.id(section, "tests_table"), builders, tested));
      }
      List<EntityId> without = builders.stream().filter(b -> !with.contains(b)).toList();
      if (!used.isEmpty()) {
        List<Fact> facts = new ArrayList<>();
        used.values().forEach(u -> facts.addAll(u.facts()));
        blocks.add(
            new Paragraph(
                assembler.id(section, "summary"),
                List.of(
                    TextRun.of(
                        evidenceSummary(builders.size(), without, used.values(), architecture))),
                assembler.facts(facts)));
        blocks.add(librariesTable(assembler.id(section, "libraries"), used.values()));
      }
      if (used.isEmpty() && tested.isEmpty()) {
        blocks.add(
            note(
                assembler.id(section, "none"),
                "No module of "
                    + repo
                    + " has test classes, runs a coverage tool or declares a test library"
                    + " Lantern recognizes, such as JUnit or TestNG, so Lantern found no evidence"
                    + " of tests, architecture tests or coverage."));
      } else {
        blocks.add(
            note(
                assembler.id(section, "limits"),
                classes > 0
                    ? "Lantern counts the tests in the test sources without running them, so"
                        + " whether they pass is not known, and it does not read coverage reports,"
                        + " so the coverage the tests reach is not known."
                    : "A declared test library or coverage tool shows that a module is set up for"
                        + " tests, not that it has any or whether they pass: Lantern found no test"
                        + " classes in the test sources, and does not read coverage reports, so"
                        + " test coverage is not known."));
      }
    }
    blocks.add(
        note(
            assembler.id(section, "goals"),
            "Quality goals are unrecorded: nothing in the snapshot states the availability,"
                + " performance, security or other targets "
                + repo
                + " must meet."));
    return blocks;
  }

  private Paragraph testsParagraph(
      String id, SortedSet<EntityId> builders, Map<EntityId, Tested> tested) {
    int classes = tested.values().stream().mapToInt(t -> t.classes).sum();
    int tests = tested.values().stream().mapToInt(t -> t.tests).sum();
    int architecture = tested.values().stream().mapToInt(t -> t.architecture).sum();
    List<EntityId> withTests = builders.stream().filter(b -> has(tested, b)).toList();
    StringBuilder text = new StringBuilder("Lantern found ");
    text.append(count(tests, "test", "tests"))
        .append(" in ")
        .append(count(classes, "test class", "test classes"))
        .append(',');
    if (withTests.size() == builders.size()) {
      text.append(
          builders.size() == 1
              ? " in the one service or library " + repo + " builds"
              : " across all " + builders.size() + " services and libraries " + repo + " builds");
    } else {
      text.append(" in ")
          .append(withTests.size())
          .append(" of the ")
          .append(builders.size())
          .append(" services and libraries ")
          .append(repo)
          .append(" builds");
    }
    text.append('.');
    if (architecture == 0) {
      text.append(
          classes == 1
              ? " It is not an architecture test."
              : " None of the test classes is an architecture test.");
    } else if (architecture == classes) {
      text.append(
          classes == 1
              ? " It is an architecture test."
              : " All the test classes are architecture tests.");
    } else {
      text.append(' ')
          .append(architecture)
          .append(
              architecture == 1
                  ? " of the test classes is an architecture test."
                  : " of the test classes are architecture tests.");
    }
    List<String> named = new ArrayList<>();
    builders.stream()
        .filter(b -> !withTests.contains(b))
        .forEach(b -> named.add(assembler.name(b)));
    if (!named.isEmpty()) {
      text.append(" There are no test classes in ").append(names(named));
    }
    List<Fact> facts = new ArrayList<>();
    tested.values().forEach(t -> t.testFacts.forEach(facts::add));
    return new Paragraph(id, List.of(TextRun.of(text.toString())), assembler.facts(facts));
  }

  private static boolean has(Map<EntityId, Tested> tested, EntityId builder) {
    Tested t = tested.get(builder);
    return t != null && t.classes > 0;
  }

  private Paragraph coverageParagraph(
      String id, SortedSet<EntityId> builders, Map<EntityId, Tested> tested) {
    SortedSet<String> tools = new TreeSet<>();
    List<EntityId> measured = new ArrayList<>();
    List<String> checks = new ArrayList<>();
    List<Fact> facts = new ArrayList<>();
    for (EntityId builder : builders) {
      Tested t = tested.get(builder);
      if (t == null || t.tools.isEmpty()) {
        continue;
      }
      measured.add(builder);
      tools.addAll(t.tools);
      if (!t.minimums.isEmpty()) {
        checks.add(assembler.name(builder) + " (" + join(List.copyOf(t.minimums)) + ")");
      }
      facts.addAll(t.coverageFacts);
    }
    StringBuilder text = new StringBuilder();
    text.append(join(List.copyOf(tools)))
        .append(tools.size() == 1 ? " measures" : " measure")
        .append(" test coverage in the build of ");
    if (measured.size() == builders.size()) {
      text.append(
          builders.size() == 1
              ? "the one service or library " + repo + " builds."
              : "all " + builders.size() + " services and libraries " + repo + " builds.");
    } else {
      text.append(measured.size())
          .append(" of the ")
          .append(builders.size())
          .append(" services and libraries ")
          .append(repo)
          .append(" builds.");
    }
    if (checks.isEmpty()) {
      text.append(" No build checks a minimum coverage.");
    } else {
      text.append(checks.size() == 1 ? " One build checks" : " " + checks.size() + " builds check")
          .append(" a minimum coverage: ")
          .append(join(checks))
          .append('.');
    }
    return new Paragraph(id, List.of(TextRun.of(text.toString())), assembler.facts(facts));
  }

  private Table testsTable(String id, SortedSet<EntityId> builders, Map<EntityId, Tested> tested) {
    List<Table.Row> rows = new ArrayList<>();
    for (EntityId builder : builders) {
      Tested t = tested.get(builder);
      if (t == null) {
        continue;
      }
      assembler.entity(builder);
      String coverage = t.tools.isEmpty() ? "None found" : join(List.copyOf(t.tools));
      if (!t.minimums.isEmpty()) {
        coverage += ", at least " + join(List.copyOf(t.minimums));
      }
      rows.add(
          new Table.Row(
              null,
              List.of(
                  List.of(
                      new CrossReference(
                          assembler.name(builder), CrossReference.Target.entity(builder))),
                  List.of(TextRun.of(Integer.toString(t.classes))),
                  List.of(TextRun.of(Integer.toString(t.tests))),
                  List.of(TextRun.of(Integer.toString(t.architecture))),
                  List.of(TextRun.of(coverage))),
              assembler.facts(t.facts)));
    }
    return new Table(
        id,
        "Tests and coverage",
        List.of(
            new Table.Column("Service or library"),
            new Table.Column("Test classes"),
            new Table.Column("Tests"),
            new Table.Column("Architecture test classes"),
            new Table.Column("Coverage tool")),
        rows,
        List.of());
  }

  private static String count(int n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }

  private String evidenceSummary(
      int builders, List<EntityId> without, Collection<UsedLibrary> used, int architectureTests) {
    int with = builders - without.size();
    StringBuilder text = new StringBuilder();
    if (without.isEmpty()) {
      text.append(
          builders == 1
              ? "The one service or library " + repo + " builds declares"
              : "All " + builders + " services and libraries " + repo + " builds declare");
    } else {
      text.append(with)
          .append(" of the ")
          .append(builders)
          .append(" services and libraries ")
          .append(repo)
          .append(" builds ")
          .append(with == 1 ? "declares" : "declare");
    }
    text.append(used.size() == 1 ? " a test library: " : " test libraries: ");
    Map<TestKind, List<String>> byKind = new TreeMap<>();
    for (UsedLibrary u : used) {
      byKind.computeIfAbsent(u.library().kind(), k -> new ArrayList<>()).add(u.library().name());
    }
    List<String> parts = new ArrayList<>();
    byKind.forEach(
        (kind, names) ->
            parts.add(
                (names.size() == 1 ? kind.one : kind.many)
                    + " ("
                    + String.join(", ", names)
                    + ")"));
    text.append(join(parts)).append('.');
    if (!byKind.containsKey(TestKind.ARCHITECTURE) && architectureTests == 0) {
      text.append(
          " None is an architecture test library such as ArchUnit, so Lantern found no"
              + " architecture tests.");
    }
    if (!without.isEmpty()) {
      List<String> named = new ArrayList<>();
      without.forEach(b -> named.add(assembler.name(b)));
      text.append(" No recognized test library is declared in ").append(names(named));
    }
    return text.toString();
  }

  private Table librariesTable(String id, Collection<UsedLibrary> used) {
    List<Table.Row> rows = new ArrayList<>();
    for (UsedLibrary u : used) {
      List<Inline> users = new ArrayList<>();
      for (EntityId user : u.usedBy()) {
        assembler.entity(user);
        if (!users.isEmpty()) {
          users.add(TextRun.of(", "));
        }
        users.add(new CrossReference(assembler.name(user), CrossReference.Target.entity(user)));
      }
      rows.add(
          new Table.Row(
              null,
              List.of(
                  List.of(TextRun.of(u.library().kind().title)),
                  List.of(TextRun.of(u.library().name())),
                  users),
              assembler.facts(u.facts())));
    }
    return new Table(
        id,
        "Test libraries",
        List.of(
            new Table.Column("Kind"), new Table.Column("Library"), new Table.Column("Declared in")),
        rows,
        List.of());
  }

  // Text.

  private static Callout note(String id, String text) {
    return new Callout(id, Callout.Variant.NOTE, null, null, List.of(TextRun.of(text)), List.of());
  }

  /** The first {@value RepoRisks#NAMED} items, then how many more there are, ending a sentence. */
  private static String names(List<String> named) {
    int shown = Math.min(named.size(), RepoRisks.NAMED);
    String text = join(named.subList(0, shown));
    if (named.size() > shown) {
      text += ", and " + (named.size() - shown) + " more";
    }
    return text + ".";
  }

  private static String join(List<String> parts) {
    if (parts.size() <= 1) {
      return String.join("", parts);
    }
    return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
  }
}
