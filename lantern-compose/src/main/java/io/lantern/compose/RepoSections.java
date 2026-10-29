package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Metrics;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Section;
import io.lantern.core.document.TextRun;
import io.lantern.core.document.Tree;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.summarize.Summary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Builds the sections of a document about one repository (see ADR 0012).
 *
 * <p>The repository's knowledge is: the modules observed in it, the services and libraries that
 * build them (its containers), their packages and the types in those. All of these are in the
 * document's entities, with their summaries as descriptions, so the document is self-contained.
 */
final class RepoSections {

  static final String BUILD_DEPENDS_ON = "BUILD_DEPENDS_ON";
  private static final String PACKAGING = "packaging";
  private static final String ARTIFACT = "artifact";
  private static final Set<String> TRANSITIVE_SCOPES = Set.of("compile", "runtime");

  /** The software system all repositories of a snapshot form, the system scope's subject. */
  static final EntityId SYSTEM = new EntityId(BuiltInTypes.SYSTEM, "snapshot");

  /** How many repositories the system's name lists before it counts them instead. */
  private static final int NAMED_REPOS = 4;

  private final Knowledge knowledge;
  private final Map<EntityId, Summary> summaries;
  private final String repo;
  private final EntityId repoId;
  private final SortedSet<String> repos;
  private final SortedSet<EntityId> modules = new TreeSet<>(Knowledge.BY_ID);
  private final SortedSet<EntityId> containers = new TreeSet<>(Knowledge.BY_ID);
  private final Assembler assembler;
  private final C4Views c4;
  private final SortedSet<EntityId> units;
  private SequenceFlows flows;
  private ExplorerSections explorer;

  /**
   * @param repo the repository the document is about, or {@code null} for the whole system
   * @param snapshotRepos the snapshot's repositories, which together form the system
   */
  RepoSections(
      Knowledge knowledge,
      Map<EntityId, Summary> summaries,
      String repo,
      SortedSet<String> snapshotRepos) {
    this.knowledge = knowledge;
    this.summaries = summaries;
    this.repo = repo;
    this.repoId = repo == null ? SYSTEM : Assembler.repo(repo);
    this.repos = repo == null ? snapshotRepos : new TreeSet<>(Set.of(repo));
    this.repos.forEach(r -> modules.addAll(knowledge.modules(r)));
    SortedSet<EntityId> inScope = new TreeSet<>(Knowledge.BY_ID);
    inScope.add(repoId);
    for (EntityId module : modules) {
      inScope.add(module);
      containers.addAll(knowledge.containers(module));
      inScope.addAll(knowledge.typesWithin(module));
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        inScope.add(pkg);
        inScope.addAll(knowledge.typesWithin(pkg));
      }
    }
    if (repo == null) {
      repos.forEach(r -> inScope.add(Assembler.repo(r)));
    }
    this.units = repo == null ? C4Views.units(knowledge) : C4Views.units(knowledge, modules);
    inScope.addAll(containers);
    inScope.addAll(units);
    this.assembler = new Assembler(knowledge, summaries, inScope, repo, systemName(snapshotRepos));
    inScope.forEach(assembler::entity);
    this.c4 = new C4Views(knowledge, assembler, SYSTEM, units, repos);
  }

  /** The system's name: its repositories, or how many there are. */
  private static String systemName(SortedSet<String> repos) {
    if (repos.isEmpty()) {
      return "The system";
    }
    if (repos.size() > NAMED_REPOS) {
      return "System of " + repos.size() + " repositories";
    }
    List<String> names = new ArrayList<>(repos);
    String last = names.removeLast();
    return names.isEmpty() ? last : String.join(", ", names) + " and " + last;
  }

  List<DocumentEntity> entities() {
    return assembler.entities();
  }

  List<Fact> facts() {
    return assembler.documentFacts();
  }

  Section section(SectionKind kind) {
    String id = assembler.id(kind.jsonName());
    List<Block> blocks = new ArrayList<>();
    List<Section> subsections = new ArrayList<>();
    switch (kind) {
      case OVERVIEW -> overview(id, blocks);
      case CONTEXT -> blocks.addAll(c4.context(id));
      case CONTAINERS -> c4.containers(id, blocks, subsections);
      case COMPONENTS -> subsections.addAll(c4.components(id));
      case START_HERE -> blocks.addAll(explorer().startHere(id));
      case SEQUENCES -> subsections.addAll(flows().flows(id, blocks));
      case CATALOG -> blocks.addAll(explorer().catalog(id));
      case CHANGE_GUIDANCE -> blocks.addAll(explorer().changeGuidance(id));
      case DEPENDENCIES -> dependencies(id, blocks);
      case STRUCTURE -> structure(id, blocks);
      case HEADLINES -> blocks.add(headlines(id));
      case RISKS ->
          blocks.addAll(
              new RepoRisks(
                      knowledge,
                      assembler,
                      repo,
                      modules,
                      containers,
                      c -> PackageDependencies.of(knowledge, c))
                  .callouts(id));
    }
    return new Section(id, kind.title(), kind.levels(), repoId, List.of(), blocks, subsections);
  }

  /** The main flows, built once per document: the start-here list and the flows both use them. */
  private SequenceFlows flows() {
    if (flows == null) {
      flows = new SequenceFlows(knowledge, assembler, c4, units);
    }
    return flows;
  }

  private ExplorerSections explorer() {
    if (explorer == null) {
      explorer = new ExplorerSections(knowledge, assembler, c4, flows(), units, repos);
    }
    return explorer;
  }

  // Overview: the repository's summary and what it builds.

  private void overview(String section, List<Block> blocks) {
    if (repo == null) {
      systemOverview(section, blocks);
      return;
    }
    paragraph(section, repoId).ifPresent(blocks::add);
    List<ListBlock.Item> items = new ArrayList<>();
    for (EntityId container : containers) {
      List<Inline> content =
          List.of(
              new CrossReference(
                  assembler.name(container), CrossReference.Target.entity(container)),
              TextRun.of(
                  container.type().equals(BuiltInTypes.SERVICE) ? ", a service" : ", a library"));
      items.add(new ListBlock.Item(content, assembler.facts(containerFacts(container))));
    }
    if (!items.isEmpty()) {
      blocks.add(new ListBlock(assembler.id(section, "builds"), false, items, List.of()));
    }
  }

  /** The system's repositories, each with the deployable units whose own code it holds. */
  private void systemOverview(String section, List<Block> blocks) {
    List<ListBlock.Item> items = new ArrayList<>();
    for (String name : repos) {
      EntityId id = Assembler.repo(name);
      List<Inline> content = new ArrayList<>();
      content.add(new CrossReference(name, CrossReference.Target.entity(id)));
      List<EntityId> units = new ArrayList<>();
      List<Fact> facts = new ArrayList<>();
      for (EntityId unit : C4Views.units(knowledge)) {
        for (EntityId module : knowledge.parts(unit, BuiltInTypes.MODULE)) {
          if (knowledge.repos(module).contains(name) && !units.contains(unit)) {
            units.add(unit);
            facts.addAll(knowledge.containsFacts(unit, module));
          }
        }
      }
      if (units.isEmpty()) {
        content.add(TextRun.of(": no deployable unit"));
      } else {
        content.add(TextRun.of(": runs "));
        for (int i = 0; i < units.size(); i++) {
          if (i > 0) {
            content.add(TextRun.of(i == units.size() - 1 ? " and " : ", "));
          }
          EntityId unit = units.get(i);
          content.add(new CrossReference(assembler.name(unit), CrossReference.Target.entity(unit)));
        }
      }
      items.add(new ListBlock.Item(content, assembler.facts(facts)));
    }
    if (!items.isEmpty()) {
      blocks.add(new ListBlock(assembler.id(section, "repositories"), false, items, List.of()));
    }
  }

  private Optional<Paragraph> paragraph(String section, EntityId entity) {
    Summary summary = summaries.get(entity);
    if (summary == null) {
      return Optional.empty();
    }
    Assembler.Text text = assembler.summary(summary);
    return Optional.of(
        new Paragraph(assembler.id(section, "summary"), text.content(), text.facts()));
  }

  // Build dependencies between repositories, for the headline numbers.

  /**
   * The build dependencies, outside test scope, between the repository and other repositories of
   * the snapshot, in either direction: from repository to repository to the facts.
   */
  private Map<EntityId, Map<EntityId, List<Fact>>> repoEdges() {
    Map<EntityId, Map<EntityId, List<Fact>>> edges = new TreeMap<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.withPredicate(BUILD_DEPENDS_ON)) {
      if (!Knowledge.isEntity(fact)
          || !fact.subject().type().equals(BuiltInTypes.MODULE)
          || isTestScope(fact)) {
        continue;
      }
      for (String from : knowledge.repos(fact.subject())) {
        for (String to : knowledge.repos(Knowledge.object(fact))) {
          if (!from.equals(to) && (from.equals(repo) || to.equals(repo))) {
            edges
                .computeIfAbsent(Assembler.repo(from), f -> new TreeMap<>(Knowledge.BY_ID))
                .computeIfAbsent(Assembler.repo(to), t -> new ArrayList<>())
                .add(fact);
          }
        }
      }
    }
    return edges;
  }

  /** The facts that a container builds its modules, and how they are packaged. */
  private List<Fact> containerFacts(EntityId container) {
    List<Fact> facts = new ArrayList<>();
    for (EntityId module : knowledge.parts(container, BuiltInTypes.MODULE)) {
      facts.addAll(knowledge.containsFacts(container, module));
      knowledge.attributes(module).stream()
          .filter(f -> f.predicate().equals(PACKAGING))
          .forEach(facts::add);
    }
    return facts;
  }

  // Headlines: what the repository builds, how big it is and how connected.

  private Metrics headlines(String section) {
    List<Fact> services = new ArrayList<>();
    List<Fact> libraries = new ArrayList<>();
    int serviceCount = 0;
    int libraryCount = 0;
    for (EntityId container : containers) {
      if (container.type().equals(BuiltInTypes.SERVICE)) {
        serviceCount++;
        services.addAll(containerFacts(container));
      } else {
        libraryCount++;
        libraries.addAll(containerFacts(container));
      }
    }
    List<Fact> moduleFacts = new ArrayList<>();
    SortedSet<EntityId> types = new TreeSet<>(Knowledge.BY_ID);
    SortedSet<EntityId> thirdParty = new TreeSet<>(Knowledge.BY_ID);
    List<Fact> thirdPartyFacts = new ArrayList<>();
    for (EntityId module : modules) {
      knowledge.attributes(module).stream()
          .filter(f -> f.predicate().equals(PACKAGING))
          .forEach(moduleFacts::add);
      types.addAll(knowledge.typesWithin(module));
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        types.addAll(knowledge.typesWithin(pkg));
      }
      for (Fact fact : knowledge.relations(module, BUILD_DEPENDS_ON)) {
        if (!isTestScope(fact) && Knowledge.object(fact).type().equals(ARTIFACT)) {
          thirdParty.add(Knowledge.object(fact));
          thirdPartyFacts.add(fact);
        }
      }
    }
    List<Fact> typeFacts = new ArrayList<>();
    for (EntityId type : types) {
      for (EntityId whole : knowledge.wholes(type)) {
        typeFacts.addAll(knowledge.containsFacts(whole, type));
      }
    }
    SortedSet<EntityId> uses = new TreeSet<>(Knowledge.BY_ID);
    SortedSet<EntityId> usedBy = new TreeSet<>(Knowledge.BY_ID);
    List<Fact> usesFacts = new ArrayList<>();
    List<Fact> usedByFacts = new ArrayList<>();
    repoEdges()
        .forEach(
            (from, targets) ->
                targets.forEach(
                    (to, facts) -> {
                      if (from.equals(repoId)) {
                        uses.add(to);
                        usesFacts.addAll(facts);
                      } else {
                        usedBy.add(from);
                        usedByFacts.addAll(facts);
                      }
                    }));
    List<Metrics.Item> items =
        List.of(
            metric("Services", serviceCount, services),
            metric("Libraries", libraryCount, libraries),
            metric("Modules", modules.size(), moduleFacts),
            metric("Java types", types.size(), typeFacts),
            metric("Third-party libraries", thirdParty.size(), thirdPartyFacts),
            metric("Repositories it uses", uses.size(), usesFacts),
            metric("Repositories using it", usedBy.size(), usedByFacts));
    return new Metrics(assembler.id(section, "metrics"), items, List.of());
  }

  private Metrics.Item metric(String label, int value, List<Fact> facts) {
    return new Metrics.Item(label, BigDecimal.valueOf(value), null, null, assembler.facts(facts));
  }

  // Dependencies: each module's Maven dependencies, with internal modules' expanded.

  private void dependencies(String section, List<Block> blocks) {
    List<Tree.Node> roots = new ArrayList<>();
    for (EntityId module : modules) {
      if (knowledge.relations(module, BUILD_DEPENDS_ON).isEmpty()) {
        continue;
      }
      Set<EntityId> expanded = new HashSet<>();
      expanded.add(module);
      roots.add(
          new Tree.Node(
              module.key(),
              assembler.entity(module),
              List.of(),
              dependencyNodes(module, expanded, true)));
    }
    if (!roots.isEmpty()) {
      blocks.add(new Tree(assembler.id(section, "tree"), "Maven dependencies", roots, List.of()));
    }
  }

  /**
   * The module's dependencies. Below the top level only those Maven passes on (compile or runtime
   * scope, not optional) are shown, and each internal module is expanded only the first time it
   * appears in a tree. Dependencies are sorted by their coordinates.
   */
  private List<Tree.Node> dependencyNodes(EntityId module, Set<EntityId> expanded, boolean direct) {
    List<Fact> facts = new ArrayList<>(knowledge.relations(module, BUILD_DEPENDS_ON));
    facts.sort(
        Comparator.comparing((Fact f) -> Knowledge.object(f).key())
            .thenComparing(f -> f.object().value())
            .thenComparing(Fact::id));
    List<Tree.Node> nodes = new ArrayList<>();
    for (Fact fact : facts) {
      String scope = attribute(fact, "scope", "compile");
      boolean optional = attribute(fact, "optional", "false").equals("true");
      if (!direct && (optional || !TRANSITIVE_SCOPES.contains(scope))) {
        continue;
      }
      EntityId target = Knowledge.object(fact);
      String version = attribute(fact, "version", null);
      String classifier = attribute(fact, "classifier", null);
      String type = attribute(fact, "artifactType", null);
      String label =
          target.key()
              + (version == null ? "" : ":" + version)
              + (classifier == null ? "" : ":" + classifier)
              + (type == null || type.equals("jar") ? "" : " (" + type + ")")
              + (scope.equals("compile") ? "" : " (" + scope + ")")
              + (optional ? " (optional)" : "");
      List<Tree.Node> children =
          target.type().equals(BuiltInTypes.MODULE) && expanded.add(target)
              ? dependencyNodes(target, expanded, false)
              : List.of();
      nodes.add(
          new Tree.Node(label, assembler.entity(target), assembler.facts(List.of(fact)), children));
    }
    return nodes;
  }

  static String attribute(Fact fact, String name, String otherwise) {
    JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() ? otherwise : value.asString();
  }

  static boolean isTestScope(Fact fact) {
    return attribute(fact, "scope", "compile").equals("test");
  }

  // Structure: the modules and their packages.

  private void structure(String section, List<Block> blocks) {
    List<Tree.Node> roots = new ArrayList<>();
    for (EntityId module : modules) {
      boolean top =
          knowledge.wholes(module, BuiltInTypes.MODULE).stream().noneMatch(modules::contains);
      if (top) {
        roots.add(moduleNode(module, List.of(), new HashSet<>()));
      }
    }
    if (!roots.isEmpty()) {
      blocks.add(new Tree(assembler.id(section, "tree"), "Modules and packages", roots, List.of()));
    }
  }

  private Tree.Node moduleNode(EntityId module, List<Fact> facts, Set<EntityId> visiting) {
    visiting.add(module);
    List<Tree.Node> children = new ArrayList<>();
    for (EntityId sub : knowledge.parts(module, BuiltInTypes.MODULE)) {
      if (modules.contains(sub) && !visiting.contains(sub)) {
        children.add(moduleNode(sub, knowledge.containsFacts(module, sub), visiting));
      }
    }
    for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
      children.add(
          new Tree.Node(
              assembler.name(pkg),
              assembler.entity(pkg),
              assembler.facts(knowledge.containsFacts(module, pkg)),
              List.of()));
    }
    visiting.remove(module);
    return new Tree.Node(
        assembler.name(module), assembler.entity(module), assembler.facts(facts), children);
  }
}
