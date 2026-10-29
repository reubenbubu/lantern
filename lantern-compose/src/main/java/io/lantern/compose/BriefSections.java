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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
 * risks technology currency finds (ADR 0050).
 *
 * <p>The blast radius is read from the linker's links (ADR 0042) of the deployable units in scope:
 * those running the repository's code. Technology currency compares the units' runtime and
 * framework versions (ADR 0039) with the end-of-life table bundled with Lantern, as of the
 * snapshot's date, so composing stays deterministic.
 */
final class BriefSections {

  /** How long before its end a line's support counts as ending soon. */
  private static final int SOON_MONTHS = 6;

  /** The factor a Java risk's confidence gets: the Java runtime a unit runs on is not recorded. */
  private static final BigDecimal JAVA_FACTOR = new BigDecimal("0.5");

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  private static final String SENDS_MESSAGES_TO = "SENDS_MESSAGES_TO";
  private static final String JAVA_RELEASE = "java-release";
  private static final String SPRING_GROUP = "org.springframework:spring-";

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final String repo;
  private final SortedSet<EntityId> modules;
  private final SortedSet<EntityId> units;
  private final Set<Fact> replaced;
  private final LocalDate asOf;
  private final EndOfLife table;
  private List<Version> versions;

  /**
   * @param repo the repository the brief is about
   * @param modules the repository's modules
   * @param units the deployable units running the repository's code
   * @param replaced the profile-only links that stand in for a default link, left out as the
   *     diagrams leave them out
   * @param asOf the snapshot's date, against which support is judged
   */
  BriefSections(
      Knowledge knowledge,
      Assembler assembler,
      String repo,
      SortedSet<EntityId> modules,
      SortedSet<EntityId> units,
      Set<Fact> replaced,
      LocalDate asOf,
      EndOfLife table) {
    this.knowledge = knowledge;
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
      String profile = RepoSections.attribute(fact, "profile", null);
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
    SortedMap<EntityId, Row> dependents = new TreeMap<>(Knowledge.BY_ID);
    SortedMap<EntityId, Row> dependencies = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId unit : units) {
      String name = assembler.name(unit);
      for (Fact fact : links(unit, C4Views.CALLS_HTTP)) {
        add(dependencies, Knowledge.object(fact), name + " calls it", fact);
      }
      for (Fact fact : links(unit, SENDS_MESSAGES_TO)) {
        add(dependents, Knowledge.object(fact), "receives messages from " + name, fact);
      }
      for (Fact fact : links(unit, C4Views.ACCESSES_DATA_OF)) {
        add(dependencies, Knowledge.object(fact), name + " " + tables(fact, "its"), fact);
      }
      for (Fact fact : links(unit, C4Views.USES_DATABASE)) {
        boolean owns = !C4Views.strings(fact, "schemas").isEmpty();
        add(
            dependencies,
            Knowledge.object(fact),
            name + (owns ? " stores its data in it" : " connects to it"),
            fact);
      }
    }
    for (EntityId other : C4Views.units(knowledge)) {
      if (units.contains(other)) {
        continue;
      }
      for (Fact fact : links(other, C4Views.CALLS_HTTP)) {
        if (units.contains(Knowledge.object(fact))) {
          add(dependents, other, "calls " + assembler.name(Knowledge.object(fact)), fact);
        }
      }
      for (Fact fact : links(other, SENDS_MESSAGES_TO)) {
        if (units.contains(Knowledge.object(fact))) {
          add(
              dependencies,
              other,
              assembler.name(Knowledge.object(fact)) + " receives its messages",
              fact);
        }
      }
      for (Fact fact : links(other, C4Views.ACCESSES_DATA_OF)) {
        if (units.contains(Knowledge.object(fact))) {
          add(
              dependents,
              other,
              tables(fact, "the") + " of " + assembler.name(Knowledge.object(fact)),
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
                  + ", and nothing they depend on. Dependents outside the snapshot are not"
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
              knowledge.attributes(unit).stream()
                  .filter(f -> f.predicate().equals(C4Views.UNRESOLVED_LINK))
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
    return knowledge.relations(unit, relationship).stream()
        .filter(f -> Knowledge.isEntity(f) && !replaced.contains(f))
        .filter(f -> !(units.contains(unit) && units.contains(Knowledge.object(f))))
        .toList();
  }

  private static void add(SortedMap<EntityId, Row> rows, EntityId entity, String how, Fact fact) {
    rows.computeIfAbsent(entity, Row::new).add(how, fact);
  }

  /** Whether a table access reads, writes or does both, of the tables named by the article. */
  private static String tables(Fact fact, String article) {
    boolean reads = !C4Views.strings(fact, "reads").isEmpty();
    boolean writes = !C4Views.strings(fact, "writes").isEmpty();
    String verb = reads && !writes ? "reads" : writes && !reads ? "writes" : "reads and writes";
    return verb + " " + article + " tables";
  }

  private String unitsPhrase() {
    return units.size() == 1 ? assembler.name(units.first()) : repo + "'s deployable units";
  }

  /**
   * Units of other repositories that bundle the repository's libraries, so its code runs in them,
   * each with the repositories it is built in.
   */
  private Optional<ListBlock> runsIn(String section) {
    List<ListBlock.Item> items = new ArrayList<>();
    for (EntityId unit : units) {
      if (knowledge.parts(unit, BuiltInTypes.MODULE).stream().anyMatch(modules::contains)) {
        continue;
      }
      List<Fact> facts = new ArrayList<>();
      for (Fact fact : knowledge.relations(unit, C4Views.BUNDLES)) {
        if (knowledge.parts(Knowledge.object(fact), BuiltInTypes.MODULE).stream()
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
    for (EntityId module : knowledge.parts(unit, BuiltInTypes.MODULE)) {
      repos.addAll(knowledge.repos(module));
    }
    if (repos.isEmpty()) {
      for (EntityId module : C4Views.codeModules(knowledge, unit)) {
        repos.addAll(knowledge.repos(module));
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
            case C4Views.EXTERNAL_SYSTEM -> "external system";
            case C4Views.DATABASE -> "database";
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
      case C4Views.EXTERNAL_SYSTEM -> "External system";
      case C4Views.DATABASE ->
          knowledge
              .attribute(entity, C4Views.TECHNOLOGY)
              .map(t -> t + " database")
              .orElse("Database");
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

  /** A runtime or framework version in use, by whom, and the facts saying so. */
  private record Version(
      EndOfLife.Product product,
      String version,
      EndOfLife.Support support,
      SortedSet<EntityId> usedBy,
      List<Fact> facts) {}

  /** The versions in use: of the units' technology, else of the modules' Java release. */
  private List<Version> versions() {
    if (versions != null) {
      return versions;
    }
    Map<String, Version> found = new TreeMap<>();
    for (EntityId unit : units) {
      for (Fact fact : knowledge.attributes(unit)) {
        if (!fact.predicate().equals(C4Views.TECHNOLOGY)) {
          continue;
        }
        if ("Spring Boot".equals(RepoSections.attribute(fact, "framework", null))) {
          version(
              found,
              EndOfLife.Product.SPRING_BOOT,
              RepoSections.attribute(fact, "frameworkVersion", null),
              unit,
              fact);
        }
        String java = RepoSections.attribute(fact, "java", null);
        if (java != null) {
          version(found, EndOfLife.Product.JAVA, java, unit, fact);
        }
      }
    }
    SortedSet<EntityId> code = new TreeSet<>(Knowledge.BY_ID);
    code.addAll(modules);
    units.forEach(u -> code.addAll(C4Views.codeModules(knowledge, u)));
    for (EntityId module : code) {
      for (Fact fact : knowledge.relations(module, RepoSections.BUILD_DEPENDS_ON)) {
        EntityId target = Knowledge.object(fact);
        String version = RepoSections.attribute(fact, "version", null);
        if (target.type().equals("artifact")
            && target.key().startsWith(SPRING_GROUP)
            && version != null
            && !RepoSections.isTestScope(fact)) {
          version(found, EndOfLife.Product.SPRING_FRAMEWORK, version, user(module), fact);
        }
      }
    }
    if (units.isEmpty()) {
      for (EntityId module : modules) {
        for (Fact fact : knowledge.attributes(module)) {
          if (fact.predicate().equals(JAVA_RELEASE)) {
            version(found, EndOfLife.Product.JAVA, fact.object().value(), user(module), fact);
          }
        }
      }
    }
    List<Version> result = new ArrayList<>(found.values());
    result.sort(
        Comparator.comparing((Version v) -> v.product())
            .thenComparing(
                v -> EndOfLife.line(v.product(), v.version()).orElse(null),
                Comparator.nullsLast(EndOfLife.BY_VERSION))
            .thenComparing(v -> v.version() == null ? "" : v.version()));
    versions = List.copyOf(result);
    return versions;
  }

  /** Who uses a module's version: the service or library building it, else the module. */
  private EntityId user(EntityId module) {
    SortedSet<EntityId> containers = knowledge.containers(module);
    return containers.isEmpty() ? module : containers.first();
  }

  private void version(
      Map<String, Version> found,
      EndOfLife.Product product,
      String version,
      EntityId user,
      Fact fact) {
    String key = product.ordinal() + " " + (version == null ? "" : version);
    Version v =
        found.computeIfAbsent(
            key,
            k ->
                new Version(
                    product,
                    version,
                    table.support(product, version),
                    new TreeSet<>(Knowledge.BY_ID),
                    new ArrayList<>()));
    v.usedBy().add(user);
    v.facts().add(fact);
  }

  /** Whether a version's support has ended by the snapshot's date. */
  private boolean ended(Version v) {
    return switch (v.support()) {
      case EndOfLife.Support.Until u -> u.end().isBefore(asOf);
      case EndOfLife.Support.Older o -> true;
      case EndOfLife.Support.Unknown u -> false;
    };
  }

  /** Whether a version's support ends within {@value #SOON_MONTHS} months of the snapshot. */
  private boolean endsSoon(Version v) {
    return v.support() instanceof EndOfLife.Support.Until u
        && !u.end().isBefore(asOf)
        && !u.end().isAfter(asOf.plusMonths(SOON_MONTHS));
  }

  /**
   * The runtime and framework versions the repository's code runs on, each with the end of its
   * line's free support as of the snapshot's date; then where the dates come from.
   */
  List<Block> technologyCurrency(String section) {
    List<Block> blocks = new ArrayList<>();
    List<Version> all = versions();
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
    long ended = all.stream().filter(this::ended).count();
    long soon = all.stream().filter(this::endsSoon).count();
    StringBuilder text =
        new StringBuilder("As of the snapshot's date, " + DATE.format(asOf) + ", ");
    if (all.size() == 1) {
      text.append("the one version in use is ").append(ended == 0 ? "not " : "");
    } else if (ended == 0) {
      text.append("none of the ").append(all.size()).append(" versions in use is ");
    } else {
      text.append(ended).append(" of the ").append(all.size()).append(" versions in use ");
      text.append(ended == 1 ? "is " : "are ");
    }
    text.append("past the end of free support");
    if (soon > 0) {
      text.append(
          all.size() == 1
              ? ", but reaches it"
              : ", and " + soon + (soon == 1 ? " reaches it" : " reach it"));
      text.append(" within six months");
    }
    text.append('.');
    blocks.add(
        new Paragraph(
            assembler.id(section, "summary"),
            List.of(TextRun.of(text.toString())),
            assembler.facts(facts)));
    List<Table.Row> rows = new ArrayList<>();
    for (Version v : all) {
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
                  List.of(TextRun.of(supportText(v)))),
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
                + DATE.format(table.asOf())
                + ": Eclipse Temurin's for Java, and the end of open-source support for Spring"
                + " Boot and Spring Framework. The Java version is the release the code is"
                + " compiled for; the Java runtime it runs on is not recorded and may be newer."));
    return blocks;
  }

  private String supportText(Version v) {
    if (v.version() == null) {
      return "Version not known";
    }
    return switch (v.support()) {
      case EndOfLife.Support.Until u ->
          u.end().isBefore(asOf)
              ? "Ended " + DATE.format(u.end())
              : endsSoon(v)
                  ? "Ends " + DATE.format(u.end()) + ", within six months"
                  : DATE.format(u.end());
      case EndOfLife.Support.Older o -> "Ended";
      case EndOfLife.Support.Unknown u -> "Not in Lantern's table";
    };
  }

  /** When a version's support ended or ends, in the middle of a sentence. */
  private String riskSupportText(Version v) {
    return switch (v.support()) {
      case EndOfLife.Support.Until u ->
          (u.end().isBefore(asOf) ? "ended " : "ends ") + DATE.format(u.end());
      default -> "ended";
    };
  }

  /**
   * The risks technology currency finds: framework versions past their end of free support (high)
   * or reaching it within six months (medium), and Java releases past it (medium) or reaching it
   * (low), whose confidence is halved since the Java runtime is not recorded.
   */
  List<Callout> currencyRisks(String section) {
    List<Callout> risks = new ArrayList<>();
    List<Version> frameworks =
        versions().stream().filter(v -> v.product() != EndOfLife.Product.JAVA).toList();
    List<Version> java =
        versions().stream().filter(v -> v.product() == EndOfLife.Product.JAVA).toList();
    currencyRisk(
            section,
            "framework-ended",
            frameworks.stream().filter(this::ended).toList(),
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
            frameworks.stream().filter(this::endsSoon).toList(),
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
            java.stream().filter(this::ended).toList(),
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
            java.stream().filter(this::endsSoon).toList(),
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
      List<Version> found,
      Callout.Severity severity,
      String title,
      String lead,
      String consequence,
      BigDecimal factor) {
    if (found.isEmpty()) {
      return Optional.empty();
    }
    List<Fact> facts = new ArrayList<>();
    List<String> named = new ArrayList<>();
    for (Version v : found) {
      facts.addAll(v.facts());
      List<String> users = new ArrayList<>();
      v.usedBy().forEach(u -> users.add(assembler.name(u)));
      named.add(
          v.product().title()
              + " "
              + v.version()
              + " ("
              + riskSupportText(v)
              + ") in "
              + join(users));
    }
    String text =
        repo
            + " uses "
            + (found.size() == 1 ? "a version" : found.size() + " versions")
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

  private static String count(int n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }
}
