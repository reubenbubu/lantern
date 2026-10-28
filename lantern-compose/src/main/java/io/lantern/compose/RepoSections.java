package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Section;
import io.lantern.core.document.TextRun;
import io.lantern.core.document.Tree;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.summarize.Summary;
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

  private static final String METHOD = "method";
  private static final String FIELD = "field";
  private static final String BUILD_DEPENDS_ON = "BUILD_DEPENDS_ON";
  private static final String PACKAGES = "PACKAGES";
  private static final String PACKAGING = "packaging";
  private static final Set<String> TRANSITIVE_SCOPES = Set.of("compile", "runtime");

  private final Knowledge knowledge;
  private final Map<EntityId, Summary> summaries;
  private final String repo;
  private final EntityId repoId;
  private final SortedSet<EntityId> modules;
  private final SortedSet<EntityId> containers = new TreeSet<>(Knowledge.BY_ID);
  private final Assembler assembler;

  RepoSections(Knowledge knowledge, Map<EntityId, Summary> summaries, String repo) {
    this.knowledge = knowledge;
    this.summaries = summaries;
    this.repo = repo;
    this.repoId = Assembler.repo(repo);
    this.modules = knowledge.modules(repo);
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
    inScope.addAll(containers);
    this.assembler = new Assembler(knowledge, summaries, inScope);
    inScope.forEach(assembler::entity);
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
      case CONTEXT -> blocks.add(context(id));
      case CONTAINERS -> blocks.add(containers(id));
      case COMPONENTS -> containers.forEach(c -> subsections.add(components(id, c)));
      case DEPENDENCIES -> dependencies(id, blocks);
      case STRUCTURE -> structure(id, blocks);
    }
    return new Section(id, kind.title(), kind.levels(), repoId, List.of(), blocks, subsections);
  }

  // Overview: the repository's summary and what it builds.

  private void overview(String section, List<Block> blocks) {
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

  private Optional<Paragraph> paragraph(String section, EntityId entity) {
    Summary summary = summaries.get(entity);
    if (summary == null) {
      return Optional.empty();
    }
    Assembler.Text text = assembler.summary(summary);
    return Optional.of(
        new Paragraph(assembler.id(section, "summary"), text.content(), text.facts()));
  }

  // System context: the repository and the repositories it depends on or that depend on it.

  private Diagram context(String section) {
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
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(repoId, node(repoId, null, null, List.of()));
    List<Diagram.Edge> diagramEdges = new ArrayList<>();
    Set<String> edgeIds = new HashSet<>();
    edges.forEach(
        (from, targets) ->
            targets.forEach(
                (to, facts) -> {
                  for (EntityId end : List.of(from, to)) {
                    nodes.computeIfAbsent(end, e -> node(e, null, null, List.of()));
                  }
                  diagramEdges.add(edge(edgeIds, from, BUILD_DEPENDS_ON, to, "depends on", facts));
                }));
    return new Diagram(
        assembler.id(section, "diagram"),
        Diagram.Kind.C4_CONTEXT,
        "System context of " + repo,
        List.copyOf(nodes.values()),
        diagramEdges,
        List.of());
  }

  // Containers: the services and libraries the repository builds and what they depend on.

  private Diagram containers(String section) {
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(repoId, node(repoId, null, null, List.of()));
    for (EntityId container : containers) {
      nodes.put(
          container, node(container, technology(container), repoId, containerFacts(container)));
    }
    Map<EntityId, Map<String, Map<EntityId, List<Fact>>>> edges = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId container : containers) {
      for (EntityId module : knowledge.parts(container, BuiltInTypes.MODULE)) {
        for (Fact fact : knowledge.relations(module, BUILD_DEPENDS_ON)) {
          if (isTestScope(fact)) {
            continue;
          }
          for (EntityId target : knowledge.containers(Knowledge.object(fact))) {
            if (!target.equals(container)) {
              edgeFact(edges, container, BUILD_DEPENDS_ON, target, fact);
            }
          }
        }
      }
      for (Fact fact : knowledge.relations(container, PACKAGES)) {
        edgeFact(edges, container, PACKAGES, Knowledge.object(fact), fact);
      }
    }
    List<Diagram.Edge> diagramEdges = new ArrayList<>();
    Set<String> edgeIds = new HashSet<>();
    edges.forEach(
        (from, byRelationship) ->
            byRelationship.forEach(
                (relationship, targets) ->
                    targets.forEach(
                        (to, facts) -> {
                          externalContainer(nodes, to);
                          diagramEdges.add(
                              edge(
                                  edgeIds,
                                  from,
                                  relationship,
                                  to,
                                  relationship.equals(PACKAGES) ? "packages" : "depends on",
                                  facts));
                        })));
    return new Diagram(
        assembler.id(section, "diagram"),
        Diagram.Kind.C4_CONTAINER,
        "Containers of " + repo,
        List.copyOf(nodes.values()),
        diagramEdges,
        List.of());
  }

  /** Adds a container of another repository, inside that repository's boundary. */
  private void externalContainer(Map<EntityId, Diagram.Node> nodes, EntityId container) {
    if (nodes.containsKey(container)) {
      return;
    }
    EntityId boundary = null;
    for (EntityId module : knowledge.parts(container, BuiltInTypes.MODULE)) {
      SortedSet<String> repos = knowledge.repos(module);
      if (!repos.isEmpty()) {
        boundary = Assembler.repo(repos.first());
        break;
      }
    }
    if (boundary != null) {
      EntityId outer = boundary;
      nodes.computeIfAbsent(outer, b -> node(outer, null, null, List.of()));
    }
    nodes.put(container, node(container, technology(container), boundary, List.of()));
  }

  /** What a container is and how it is packaged, such as {@code Service, jar}. */
  private String technology(EntityId container) {
    String kind =
        switch (container.type()) {
          case BuiltInTypes.SERVICE -> "Service";
          case BuiltInTypes.LIBRARY -> "Library";
          default -> null;
        };
    if (kind == null) {
      return null;
    }
    for (EntityId module : knowledge.parts(container, BuiltInTypes.MODULE)) {
      Optional<String> packaging = knowledge.attribute(module, PACKAGING);
      if (packaging.isPresent()) {
        return kind + ", " + packaging.get();
      }
    }
    return kind;
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

  // Components: for each container, its summary and the packages it is made of.

  private Section components(String section, EntityId container) {
    String id = assembler.id(section, container.toString());
    List<Block> blocks = new ArrayList<>();
    SortedSet<EntityId> containerModules = knowledge.parts(container, BuiltInTypes.MODULE);
    for (EntityId module : containerModules) {
      paragraph(id, module).ifPresent(blocks::add);
    }
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    Map<EntityId, SortedSet<EntityId>> packagesOfType = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId module : containerModules) {
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        nodes.put(pkg, node(pkg, null, container, knowledge.containsFacts(module, pkg)));
        for (EntityId type : knowledge.typesWithin(pkg)) {
          packagesOfType.computeIfAbsent(type, t -> new TreeSet<>(Knowledge.BY_ID)).add(pkg);
        }
      }
    }
    if (!nodes.isEmpty()) {
      nodes.put(container, node(container, technology(container), null, List.of()));
      Map<EntityId, Map<EntityId, List<Fact>>> edges = new TreeMap<>(Knowledge.BY_ID);
      packagesOfType.forEach(
          (type, from) -> {
            for (Fact fact : dependencies(type)) {
              EntityId target = ownerType(Knowledge.object(fact));
              for (EntityId to : packagesOfType.getOrDefault(target, new TreeSet<>())) {
                for (EntityId pkg : from) {
                  if (!pkg.equals(to)) {
                    edges
                        .computeIfAbsent(pkg, p -> new TreeMap<>(Knowledge.BY_ID))
                        .computeIfAbsent(to, t -> new ArrayList<>())
                        .add(fact);
                  }
                }
              }
            }
          });
      List<Diagram.Edge> diagramEdges = new ArrayList<>();
      Set<String> edgeIds = new HashSet<>();
      edges.forEach(
          (from, targets) ->
              targets.forEach(
                  (to, facts) ->
                      diagramEdges.add(
                          edge(edgeIds, from, BuiltInTypes.DEPENDS_ON, to, "uses", facts))));
      blocks.add(
          new Diagram(
              assembler.id(id, "diagram"),
              Diagram.Kind.C4_COMPONENT,
              "Components of " + assembler.name(container),
              List.copyOf(nodes.values()),
              diagramEdges,
              List.of()));
    }
    return new Section(
        id,
        assembler.name(container),
        SectionKind.COMPONENTS.levels(),
        container,
        List.of(),
        blocks,
        List.of());
  }

  /** What a type depends on: its supertypes and the calls it and its members make. */
  private List<Fact> dependencies(EntityId type) {
    List<Fact> facts = new ArrayList<>();
    facts.addAll(knowledge.relations(type, BuiltInTypes.EXTENDS));
    facts.addAll(knowledge.relations(type, BuiltInTypes.IMPLEMENTS));
    facts.addAll(knowledge.relations(type, BuiltInTypes.CALLS));
    for (EntityId method : knowledge.parts(type, METHOD)) {
      facts.addAll(knowledge.relations(method, BuiltInTypes.CALLS));
    }
    return facts;
  }

  /** The type itself, or the type that declares a method or field. */
  private EntityId ownerType(EntityId entity) {
    if (entity.type().equals(METHOD) || entity.type().equals(FIELD)) {
      SortedSet<EntityId> owners = knowledge.wholes(entity, BuiltInTypes.TYPE);
      return owners.isEmpty() ? entity : owners.first();
    }
    return entity;
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
      String label =
          target.key()
              + (version == null ? "" : ":" + version)
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

  private static String attribute(Fact fact, String name, String otherwise) {
    JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() ? otherwise : value.asString();
  }

  private static boolean isTestScope(Fact fact) {
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

  // Diagram elements.

  private Diagram.Node node(EntityId entity, String technology, EntityId parent, List<Fact> facts) {
    assembler.entity(entity);
    return new Diagram.Node(
        entity, assembler.name(entity), technology, parent, null, null, assembler.facts(facts));
  }

  private Diagram.Edge edge(
      Set<String> edgeIds,
      EntityId from,
      String relationship,
      EntityId to,
      String label,
      List<Fact> facts) {
    return new Diagram.Edge(
        Assembler.edgeId(edgeIds, from, relationship, to),
        from,
        to,
        label,
        relationship,
        assembler.facts(facts));
  }

  private static void edgeFact(
      Map<EntityId, Map<String, Map<EntityId, List<Fact>>>> edges,
      EntityId from,
      String relationship,
      EntityId to,
      Fact fact) {
    edges
        .computeIfAbsent(from, f -> new TreeMap<>())
        .computeIfAbsent(relationship, r -> new TreeMap<>(Knowledge.BY_ID))
        .computeIfAbsent(to, t -> new ArrayList<>())
        .add(fact);
  }
}
