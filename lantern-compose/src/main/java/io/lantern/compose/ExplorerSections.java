package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.ConfigValues;
import io.lantern.derive.Decisions;
import io.lantern.derive.Elements;
import io.lantern.derive.EntryPoints;
import io.lantern.derive.ScopeFlows;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The sections the Quality bar asks of the Architecture Explorer besides its diagrams ({@code
 * docs/design.md}, Quality bar; ADR 0048): where to start reading, a catalog of what the scope is
 * made of, and how to make the common changes. Everything is built from facts and cites them; what
 * the code does not show is left out, or said once in a note that cites nothing.
 */
final class ExplorerSections {

  /** How many entry points the start-here list names when there are no main flows. */
  static final int START_ENTRIES = 5;

  private static final Set<String> DESTINATIONS = Set.of(Types.TOPIC, Types.QUEUE, Types.EXCHANGE);

  private final ArchitectureModel model;
  private final Assembler assembler;
  private final C4Views c4;
  private final SequenceFlows flows;
  private final SortedSet<EntityId> units;
  private final SortedSet<String> repos;
  private final Set<Fact> alternatives;

  /**
   * @param units the deployable units in scope
   * @param repos the repositories in scope
   */
  ExplorerSections(
      ArchitectureModel model,
      Assembler assembler,
      C4Views c4,
      SequenceFlows flows,
      SortedSet<EntityId> units,
      SortedSet<String> repos) {
    this.model = model;
    this.assembler = assembler;
    this.c4 = c4;
    this.flows = flows;
    this.units = units;
    this.repos = repos;
    this.alternatives = c4.elements().alternativeLinks().keySet();
  }

  /** An entry point of a unit in scope. */
  private record Entry(EntityId unit, EntityId method, EntryPoints.EntryPoint entry) {

    Fact fact() {
      return entry.fact();
    }
  }

  /** The entry points of the units in scope, by unit, method and kind. */
  private List<Entry> entries() {
    List<Entry> result = new ArrayList<>();
    for (EntityId unit : units) {
      for (EntityId type : code(unit)) {
        for (EntityId method : model.parts(type, Types.METHOD)) {
          for (EntryPoints.EntryPoint entry : EntryPoints.of(model, method)) {
            result.add(new Entry(unit, method, entry));
          }
        }
      }
    }
    return result;
  }

  /** The types whose code runs in the unit. */
  private SortedSet<EntityId> code(EntityId unit) {
    SortedSet<EntityId> code = new TreeSet<>(ArchitectureModel.BY_ID);
    for (EntityId module : Elements.codeModules(model, unit)) {
      code.addAll(model.typesWithin(module));
      for (EntityId pkg : model.parts(module, Types.PACKAGE)) {
        code.addAll(model.typesWithin(pkg));
      }
    }
    return code;
  }

  // Start here.

  /**
   * The start-here list: the entry points of the main flows (or the first few entry points when no
   * flow is drawn), where each unit starts, the repositories' READMEs and where their decision
   * records are. A note when there is none of these.
   */
  List<Block> startHere(String section) {
    List<ListBlock.Item> items = new ArrayList<>();
    List<ScopeFlows.Entry> main = flows.mainEntries();
    if (!main.isEmpty()) {
      for (ScopeFlows.Entry entry : main) {
        items.add(entryItem(entry.unit(), entry.method(), entry.fact()));
      }
    } else {
      entries().stream()
          .limit(START_ENTRIES)
          .forEach(e -> items.add(entryItem(e.unit(), e.method(), e.fact())));
    }
    for (EntityId unit : units) {
      for (EntityId type : code(unit)) {
        for (Fact fact : model.mainClassFacts(type)) {
          List<Inline> content = new ArrayList<>();
          content.add(entity(unit));
          content.add(TextRun.of(" starts in "));
          content.add(entity(type));
          file(fact).ifPresent(path -> content.addAll(inFile(path)));
          content.add(TextRun.of("."));
          items.add(new ListBlock.Item(content, assembler.facts(List.of(fact))));
        }
      }
    }
    for (String repo : repos) {
      readme(repo).ifPresent(items::add);
      decisionRecords(repo).ifPresent(items::add);
    }
    if (items.isEmpty()) {
      return List.of(
          note(
              assembler.id(section, "none"),
              "No entry point, main class, readme or decision record was found, so there is no"
                  + " obvious place to start reading."));
    }
    return List.of(new ListBlock(assembler.id(section, "list"), false, items, List.of()));
  }

  /** {@code GET /orders} on shop-app: Controller.list in {@code path}. */
  private ListBlock.Item entryItem(EntityId unit, EntityId method, Fact fact) {
    List<Inline> content = new ArrayList<>();
    content.add(TextRun.of(entryLabel(fact) + " on "));
    content.add(entity(unit));
    content.add(TextRun.of(": "));
    assembler.entity(method);
    content.add(
        new CrossReference(ScopeFlows.methodName(method), CrossReference.Target.entity(method)));
    file(fact).ifPresent(path -> content.addAll(inFile(path)));
    content.add(TextRun.of("."));
    return new ListBlock.Item(content, assembler.facts(List.of(fact)));
  }

  /** What starts an entry point, in words: the endpoint, the destination or the schedule. */
  private static String entryLabel(Fact fact) {
    return EntryPoints.label(fact);
  }

  /** The repository's top-level README, else its first by path. */
  private Optional<ListBlock.Item> readme(String repo) {
    SortedMap<String, List<Fact>> byPath = new TreeMap<>(README_ORDER);
    for (Fact fact : model.readmeClaims(repo)) {
      byPath.computeIfAbsent(path(fact.subject()), p -> new ArrayList<>()).add(fact);
    }
    if (byPath.isEmpty()) {
      return Optional.empty();
    }
    String path = byPath.firstKey();
    Fact first =
        byPath.get(path).stream().min(Comparator.comparingInt(ExplorerSections::line)).get();
    List<Inline> content = new ArrayList<>();
    content.add(TextRun.of("Read "));
    content.add(code(path));
    content.add(TextRun.of(" in " + repo + "."));
    return Optional.of(new ListBlock.Item(content, assembler.facts(List.of(first))));
  }

  /** Paths with fewer directories first, then by name. */
  private static final Comparator<String> README_ORDER =
      Comparator.comparingLong((String p) -> p.chars().filter(c -> c == '/').count())
          .thenComparing(Comparator.naturalOrder());

  /** Where the repository's decision records are: the folder holding most of them. */
  private Optional<ListBlock.Item> decisionRecords(String repo) {
    Map<String, List<Fact>> byFolder = new TreeMap<>();
    for (Decisions.Decision decision : Decisions.of(model)) {
      if (decision.repo().equals(repo)) {
        String path = path(decision.id());
        String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        byFolder.computeIfAbsent(folder, f -> new ArrayList<>()).add(decision.title());
      }
    }
    if (byFolder.isEmpty()) {
      return Optional.empty();
    }
    String folder =
        byFolder.entrySet().stream()
            .max(
                Comparator.comparingInt((Map.Entry<String, List<Fact>> e) -> e.getValue().size())
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
            .get()
            .getKey();
    List<Fact> facts = byFolder.get(folder);
    List<Inline> content = new ArrayList<>();
    if (folder.isEmpty()) {
      content.add(TextRun.of("The decision records of " + repo + " are in its root folder"));
    } else {
      content.add(TextRun.of("The decision records of " + repo + " are in "));
      content.add(code(folder));
    }
    content.add(TextRun.of(" (" + facts.size() + (facts.size() == 1 ? " record)." : " records).")));
    return Optional.of(new ListBlock.Item(content, assembler.facts(facts)));
  }

  // Catalog.

  /**
   * The catalog: tables of the scope's containers, components, entry points, topics and queues,
   * tables and configuration, each row citing the facts behind it; a table is left out when it
   * would be empty, and a note says so when all are.
   */
  List<Block> catalog(String section) {
    List<Block> blocks = new ArrayList<>();
    containersTable(section).ifPresent(blocks::add);
    componentsTable(section).ifPresent(blocks::add);
    entriesTable(section).ifPresent(blocks::add);
    destinationsTable(section).ifPresent(blocks::add);
    tablesTable(section).ifPresent(blocks::add);
    configTable(section).ifPresent(blocks::add);
    if (blocks.isEmpty()) {
      blocks.add(
          note(
              assembler.id(section, "none"),
              "No deployable unit, component, entry point, topic, queue, table or configuration"
                  + " was found in the code."));
    }
    return blocks;
  }

  /** The deployable units and the databases they use. */
  private Optional<Table> containersTable(String section) {
    List<Table.Row> rows = new ArrayList<>();
    SortedMap<EntityId, List<Fact>> databases = new TreeMap<>(ArchitectureModel.BY_ID);
    SortedMap<EntityId, SortedSet<EntityId>> usedBy = new TreeMap<>(ArchitectureModel.BY_ID);
    for (EntityId unit : units) {
      rows.add(
          row(
              unit,
              List.of(
                  List.of(entity(unit)),
                  text("Deployable unit"),
                  text(c4.elements().technology(unit)),
                  List.of()),
              c4.elements().containerFacts(unit)));
      for (Fact fact : model.linksOf(unit, Types.USES_DATABASE)) {
        if (!alternatives.contains(fact)) {
          EntityId database = ArchitectureModel.object(fact);
          databases.computeIfAbsent(database, d -> new ArrayList<>()).add(fact);
          usedBy.computeIfAbsent(database, d -> new TreeSet<>(ArchitectureModel.BY_ID)).add(unit);
        }
      }
    }
    databases.forEach(
        (database, facts) -> {
          List<Fact> cited = new ArrayList<>(facts);
          cited.addAll(c4.elements().containerFacts(database));
          rows.add(
              row(
                  database,
                  List.of(
                      List.of(entity(database)),
                      text("Database"),
                      text(c4.elements().technology(database)),
                      entities(usedBy.get(database))),
                  cited));
        });
    return table(
        section, "containers", "Containers", rows, "Container", "Kind", "Technology", "Used by");
  }

  /** The components of each unit in scope, by role. */
  private Optional<Table> componentsTable(String section) {
    List<Table.Row> rows = new ArrayList<>();
    for (EntityId unit : units) {
      for (Elements.Component component : c4.elements().components(unit)) {
        rows.add(
            row(
                component.main(),
                List.of(
                    List.of(entity(component.main())),
                    text(component.technology()),
                    List.of(entity(unit))),
                c4.elements().componentFacts(component)));
      }
    }
    return table(section, "components", "Components", rows, "Component", "Role", "Container");
  }

  /** The endpoints, message listeners and scheduled jobs of the units in scope. */
  private Optional<Table> entriesTable(String section) {
    List<Table.Row> rows = new ArrayList<>();
    for (Entry entry : entries()) {
      String kind = entry.entry.kindName();
      assembler.entity(entry.method);
      rows.add(
          row(
              entry.method,
              List.of(
                  List.of(code(entry.entry.value())),
                  text(kind),
                  List.of(
                      new CrossReference(
                          ScopeFlows.methodName(entry.method),
                          CrossReference.Target.entity(entry.method))),
                  List.of(entity(entry.unit)),
                  file(entry.fact()).map(p -> List.<Inline>of(code(p))).orElse(List.of())),
              List.of(entry.fact())));
    }
    return table(
        section,
        "entry-points",
        "Endpoints and other entry points",
        rows,
        "Entry point",
        "Kind",
        "Handler",
        "Container",
        "File");
  }

  /** The topics, queues and exchanges the units in scope send to or receive from. */
  private Optional<Table> destinationsTable(String section) {
    SortedMap<EntityId, SortedSet<EntityId>> senders = new TreeMap<>(ArchitectureModel.BY_ID);
    SortedMap<EntityId, SortedSet<EntityId>> receivers = new TreeMap<>(ArchitectureModel.BY_ID);
    SortedMap<EntityId, List<Fact>> facts = new TreeMap<>(ArchitectureModel.BY_ID);
    for (EntityId unit : units) {
      for (String predicate : List.of(Types.PUBLISHES, Types.CONSUMES)) {
        for (Fact fact : model.linksOf(unit, predicate)) {
          EntityId destination = ArchitectureModel.object(fact);
          if (alternatives.contains(fact) || !DESTINATIONS.contains(destination.type())) {
            continue;
          }
          (predicate.equals(Types.PUBLISHES) ? senders : receivers)
              .computeIfAbsent(destination, d -> new TreeSet<>(ArchitectureModel.BY_ID))
              .add(unit);
          facts.computeIfAbsent(destination, d -> new ArrayList<>()).add(fact);
        }
      }
    }
    List<Table.Row> rows = new ArrayList<>();
    facts.forEach(
        (destination, cited) ->
            rows.add(
                row(
                    destination,
                    List.of(
                        List.of(entity(destination)),
                        text(c4.elements().technology(destination)),
                        entities(senders.get(destination)),
                        entities(receivers.get(destination))),
                    cited)));
    return table(
        section,
        "destinations",
        "Topics, queues and exchanges",
        rows,
        "Name",
        "Technology",
        "Sent by",
        "Received by");
  }

  /**
   * The tables of the schemas the units' code builds, and the tables their code reads or writes,
   * each with the units it belongs to or is used by.
   */
  private Optional<Table> tablesTable(String section) {
    SortedMap<EntityId, SortedSet<EntityId>> containers = new TreeMap<>(ArchitectureModel.BY_ID);
    SortedMap<EntityId, List<Fact>> facts = new TreeMap<>(ArchitectureModel.BY_ID);
    for (EntityId unit : units) {
      for (EntityId module : Elements.codeModules(model, unit)) {
        for (EntityId schema : model.parts(module, Types.SCHEMA)) {
          for (EntityId table : model.parts(schema, Types.TABLE)) {
            containers
                .computeIfAbsent(table, t -> new TreeSet<>(ArchitectureModel.BY_ID))
                .add(unit);
            facts
                .computeIfAbsent(table, t -> new ArrayList<>())
                .addAll(model.containsFacts(schema, table));
          }
        }
      }
      for (EntityId type : code(unit)) {
        for (EntityId method : model.parts(type, Types.METHOD)) {
          for (String predicate : List.of(Types.READS_TABLE, Types.WRITES_TABLE)) {
            for (Fact fact : model.linksOf(method, predicate)) {
              EntityId table = ArchitectureModel.object(fact);
              containers
                  .computeIfAbsent(table, t -> new TreeSet<>(ArchitectureModel.BY_ID))
                  .add(unit);
              facts.computeIfAbsent(table, t -> new ArrayList<>()).add(fact);
            }
          }
        }
      }
    }
    List<Table.Row> rows = new ArrayList<>();
    facts.forEach(
        (table, cited) -> {
          assembler.entity(table);
          String key = table.key();
          int colon = key.lastIndexOf(':');
          rows.add(
              row(
                  table,
                  List.of(
                      List.of(
                          new CrossReference(
                              key.substring(colon + 1), CrossReference.Target.entity(table))),
                      colon < 0 ? List.of() : List.of(code(key.substring(0, colon))),
                      entities(containers.get(table))),
                  cited));
        });
    return table(section, "tables", "Tables", rows, "Table", "Schema", "Container");
  }

  /**
   * The configuration of the units' modules: each key's default value, and each value a profile
   * sets, as the config extractor records them (secrets masked, ADR 0032).
   */
  private Optional<Table> configTable(String section) {
    SortedMap<EntityId, SortedSet<EntityId>> unitsOf = new TreeMap<>(ArchitectureModel.BY_ID);
    for (EntityId unit : units) {
      for (EntityId module : Elements.codeModules(model, unit)) {
        unitsOf.computeIfAbsent(module, m -> new TreeSet<>(ArchitectureModel.BY_ID)).add(unit);
      }
    }
    List<ConfigValues.Value> values = new ArrayList<>(ConfigValues.of(model, unitsOf.keySet()));
    values.sort(
        Comparator.comparing((ConfigValues.Value v) -> v.fact().subject().toString())
            .thenComparing(v -> v.key() == null ? "" : v.key())
            .thenComparing(v -> v.profile() == null ? "" : v.profile())
            .thenComparing(v -> v.fact().id()));
    List<Table.Row> rows = new ArrayList<>();
    for (ConfigValues.Value configValue : values) {
      Fact fact = configValue.fact();
      String key = configValue.key();
      if (key == null) {
        continue;
      }
      String profile = configValue.profile();
      String value = configValue.value();
      rows.add(
          row(
              fact.subject(),
              List.of(
                  List.of(code(key)),
                  value.isEmpty() ? List.of() : List.of(code(value)),
                  text(profile),
                  entities(unitsOf.get(fact.subject())),
                  file(fact).map(p -> List.<Inline>of(code(p))).orElse(List.of())),
              List.of(fact)));
    }
    return table(
        section, "config", "Configuration", rows, "Key", "Value", "Profile", "Container", "File");
  }

  // Change guidance.

  /**
   * How to make the common changes, each by an example the code already has: an endpoint, a message
   * listener, a scheduled job, a send, an HTTP call, a schema change and a setting. Only the kinds
   * the code has an example of are listed; a note when it has none.
   */
  List<Block> changeGuidance(String section) {
    List<ListBlock.Item> items = new ArrayList<>();
    List<Entry> entries = entries();
    example(entries, EntryPoints.Kind.ENDPOINT)
        .ifPresent(e -> items.add(follow("To add an endpoint, follow ", e.method, e.fact(), null)));
    example(entries, EntryPoints.Kind.MESSAGE_LISTENER)
        .ifPresent(
            e ->
                items.add(
                    follow(
                        "To handle a new message, follow ",
                        e.method,
                        e.fact(),
                        ", which receives from " + e.entry.value())));
    example(entries, EntryPoints.Kind.SCHEDULED)
        .ifPresent(
            e -> items.add(follow("To add a scheduled job, follow ", e.method, e.fact(), null)));
    linkExample(Types.PUBLISHES)
        .ifPresent(
            f ->
                items.add(
                    follow(
                        "To send a message, follow ",
                        Elements.sourceOf(f),
                        f,
                        ", which sends to " + assembler.name(ArchitectureModel.object(f)))));
    linkExample(Types.CALLS_HTTP)
        .ifPresent(
            f ->
                items.add(
                    follow(
                        "To call another service over HTTP, follow ",
                        Elements.sourceOf(f),
                        f,
                        ", which calls " + assembler.name(ArchitectureModel.object(f)))));
    migrationExample().ifPresent(items::add);
    settingExample().ifPresent(items::add);
    if (items.isEmpty()) {
      return List.of(
          note(
              assembler.id(section, "none"),
              "The code has no endpoint, listener, scheduled job, send, call to another service,"
                  + " schema or configuration to follow as an example."));
    }
    return List.of(new ListBlock(assembler.id(section, "list"), false, items, List.of()));
  }

  /**
   * An entry point of the kind to follow: one in the type with most entry points of that kind, ties
   * broken by type ID, its first by method ID.
   */
  private Optional<Entry> example(List<Entry> entries, EntryPoints.Kind kind) {
    Map<EntityId, List<Entry>> byType = new TreeMap<>(ArchitectureModel.BY_ID);
    for (Entry entry : entries) {
      if (entry.entry.kind() == kind) {
        byType
            .computeIfAbsent(
                model.wholes(entry.method).stream().findFirst().orElse(entry.method),
                t -> new ArrayList<>())
            .add(entry);
      }
    }
    return byType.values().stream().max(Comparator.comparingInt(List::size)).map(List::getFirst);
  }

  /** The first link of the kind, by unit and fact ID, whose code is known. */
  private Optional<Fact> linkExample(String predicate) {
    for (EntityId unit : units) {
      for (Fact fact : model.linksOf(unit, predicate)) {
        if (!alternatives.contains(fact) && Elements.sourceOf(fact) != null) {
          return Optional.of(fact);
        }
      }
    }
    return Optional.empty();
  }

  /** "To ..., follow Type.method in path, which ...." */
  private ListBlock.Item follow(String lead, EntityId method, Fact fact, String which) {
    List<Inline> content = new ArrayList<>();
    content.add(TextRun.of(lead));
    assembler.entity(method);
    String name =
        method.type().equals(Types.METHOD) ? ScopeFlows.methodName(method) : assembler.name(method);
    content.add(new CrossReference(name, CrossReference.Target.entity(method)));
    file(fact).ifPresent(path -> content.addAll(inFile(path)));
    if (which != null) {
      content.add(TextRun.of(which));
    }
    content.add(TextRun.of("."));
    return new ListBlock.Item(content, assembler.facts(List.of(fact)));
  }

  /**
   * Where a schema of the units' code is migrated: the folder of the first file of its most
   * specific kind (Flyway, then Liquibase, then plain SQL), which the kind fact points at (ADR
   * 0034).
   */
  private Optional<ListBlock.Item> migrationExample() {
    for (EntityId unit : units) {
      for (EntityId module : Elements.codeModules(model, unit)) {
        for (EntityId schema : model.parts(module, Types.SCHEMA)) {
          Optional<ArchitectureModel.Migration> kind = model.migration(schema);
          if (kind.isEmpty()) {
            continue;
          }
          String path = kind.get().fact().evidence().path();
          String how =
              switch (kind.get().kind()) {
                case "flyway" -> "a Flyway migration";
                case "liquibase" -> "a Liquibase change set";
                default -> "an SQL script";
              };
          List<Inline> content = new ArrayList<>();
          content.add(TextRun.of("To change a table of " + assembler.name(unit) + ", add " + how));
          if (path.contains("/")) {
            content.add(TextRun.of(" in "));
            content.add(code(path.substring(0, path.lastIndexOf('/') + 1)));
          } else {
            content.add(TextRun.of(" next to "));
            content.add(code(path));
          }
          content.add(TextRun.of("."));
          return Optional.of(
              new ListBlock.Item(content, assembler.facts(List.of(kind.get().fact()))));
        }
      }
    }
    return Optional.empty();
  }

  /** Where the units' default configuration is: the file defining most default keys. */
  private Optional<ListBlock.Item> settingExample() {
    Map<String, List<Fact>> byFile = new TreeMap<>();
    List<EntityId> modules = new ArrayList<>();
    for (EntityId unit : units) {
      modules.addAll(Elements.codeModules(model, unit));
    }
    for (ConfigValues.Value value : ConfigValues.of(model, modules)) {
      if (!value.profiled() && value.fact().evidence().path() != null) {
        byFile
            .computeIfAbsent(value.fact().evidence().path(), p -> new ArrayList<>())
            .add(value.fact());
      }
    }
    return byFile.entrySet().stream()
        .max(
            Comparator.comparingInt((Map.Entry<String, List<Fact>> e) -> e.getValue().size())
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
        .map(
            e -> {
              List<Inline> content = new ArrayList<>();
              content.add(TextRun.of("To add a setting, add it to "));
              content.add(code(e.getKey()));
              content.add(TextRun.of(", where most default settings are."));
              Fact first = e.getValue().stream().min(Comparator.comparing(Fact::id)).get();
              return new ListBlock.Item(content, assembler.facts(List.of(first)));
            });
  }

  // Helpers.

  private Optional<Table> table(
      String section, String role, String title, List<Table.Row> rows, String... columns) {
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    List<Table.Column> cols = new ArrayList<>();
    for (String column : columns) {
      cols.add(new Table.Column(column));
    }
    return Optional.of(new Table(assembler.id(section, role), title, cols, rows, List.of()));
  }

  private Table.Row row(EntityId entity, List<List<Inline>> cells, List<Fact> facts) {
    assembler.entity(entity);
    return new Table.Row(entity, cells, assembler.facts(facts));
  }

  private CrossReference entity(EntityId id) {
    assembler.entity(id);
    return new CrossReference(assembler.name(id), CrossReference.Target.entity(id));
  }

  private List<Inline> entities(SortedSet<EntityId> ids) {
    List<Inline> content = new ArrayList<>();
    if (ids == null) {
      return content;
    }
    for (EntityId id : ids) {
      if (!content.isEmpty()) {
        content.add(TextRun.of(", "));
      }
      content.add(entity(id));
    }
    return content;
  }

  private static List<Inline> text(String text) {
    return text == null || text.isBlank() ? List.of() : List.of(TextRun.of(text));
  }

  private static TextRun code(String text) {
    return new TextRun(text, TextRun.Style.CODE);
  }

  private static List<Inline> inFile(String path) {
    return List.of(TextRun.of(" in "), code(path));
  }

  /**
   * The file a README or decision record entity is about, from its key {@code <repo>:<path>}, whose
   * whitespace and {@code %} are percent-encoded (ADR 0033).
   */
  private static String path(EntityId entity) {
    String key = entity.key();
    String encoded = key.substring(key.indexOf(':') + 1);
    try {
      return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return encoded;
    }
  }

  private static Optional<String> file(Fact fact) {
    return Optional.ofNullable(fact.evidence().path());
  }

  private static int line(Fact fact) {
    return fact.evidence().lines() == null ? Integer.MAX_VALUE : fact.evidence().lines().start();
  }

  private static Callout note(String id, String text) {
    return new Callout(id, Callout.Variant.NOTE, null, null, List.of(TextRun.of(text)), List.of());
  }
}
