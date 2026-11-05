package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Metrics;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.document.TextRun;
import io.lantern.core.document.Tree;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.Elements;
import io.lantern.derive.EndOfLife;
import io.lantern.derive.ScopeFlows;
import io.lantern.derive.Unknowns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the sections of a document about one repository (see ADR 0012).
 *
 * <p>The repository's knowledge is: the modules observed in it, the services and libraries that
 * build them (its containers), their packages and the types in those. All of these are in the
 * document's entities, with their summaries as descriptions, so the document is self-contained.
 */
final class RepoSections {

  private static final Set<String> TRANSITIVE_SCOPES = Set.of("compile", "runtime");

  /** The software system all repositories of a snapshot form, the system scope's subject. */
  static final EntityId SYSTEM = ArchitectureModel.SYSTEM;

  /**
   * What the system's overview says when no purpose is known: plainly, without a reason, since the
   * model may have been left out, refused or not asked.
   */
  static final String UNKNOWN_PURPOSE = "The system's purpose is not known.";

  /** How many repositories the system's name lists before it counts them instead. */
  private static final int NAMED_REPOS = 4;

  private final ArchitectureModel model;
  private final String repo;
  private final EntityId repoId;
  private final SortedSet<String> repos;
  private final SortedSet<EntityId> modules = new TreeSet<>(ArchitectureModel.BY_ID);
  private final SortedSet<EntityId> containers = new TreeSet<>(ArchitectureModel.BY_ID);
  private final Assembler assembler;
  private final C4Views c4;
  private final SortedSet<EntityId> units;
  private final LocalDate asOf;
  private SequenceFlows flows;
  private ExplorerSections explorer;
  private BriefSections brief;
  private ClaimContradictions claims;
  private InvestigatedFindings investigated;
  private Elements elements;
  private Unknowns unknowns;

  /**
   * @param model the snapshot's model, with the texts the LLM roles stored (ADR 0050)
   * @param repo the repository the document is about, or {@code null} for the whole system
   * @param snapshotRepos the snapshot's repositories, which together form the system
   * @param systemName the system's title, or {@code null} to name it after its repositories
   * @param asOf the snapshot's date, against which technology currency is judged
   */
  RepoSections(
      ArchitectureModel model,
      String repo,
      SortedSet<String> snapshotRepos,
      String systemName,
      LocalDate asOf,
      DiagramLimits limits) {
    this.model = model;
    this.asOf = asOf;
    this.repo = repo;
    this.repoId = repo == null ? SYSTEM : Assembler.repo(repo);
    this.repos = repo == null ? snapshotRepos : new TreeSet<>(Set.of(repo));
    this.repos.forEach(r -> modules.addAll(model.modules(r)));
    SortedSet<EntityId> inScope = new TreeSet<>(ArchitectureModel.BY_ID);
    inScope.add(repoId);
    for (EntityId module : modules) {
      inScope.add(module);
      containers.addAll(model.containers(module));
      inScope.addAll(model.typesWithin(module));
      for (EntityId pkg : model.parts(module, Types.PACKAGE)) {
        inScope.add(pkg);
        inScope.addAll(model.typesWithin(pkg));
      }
    }
    if (repo == null) {
      repos.forEach(r -> inScope.add(Assembler.repo(r)));
    }
    this.units = repo == null ? Elements.units(model) : Elements.units(model, modules);
    inScope.addAll(containers);
    inScope.addAll(units);
    this.assembler =
        new Assembler(
            model, inScope, repo, systemName == null ? systemName(snapshotRepos) : systemName);
    assembler.groups(limits.smallestGroup());
    inScope.forEach(assembler::entity);
    this.c4 = new C4Views(model, assembler, SYSTEM, units, repos, limits);
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

  /**
   * The glossary of the acronyms the document, composed without it, uses (ADR 0063): what the
   * documentation of the repositories in scope says each stands for, citing it, else Lantern's
   * table (#229).
   */
  Section glossary(Document document) {
    Map<String, String> texts = model.documentationTexts(repo);
    Map<String, Glossary.Definition> documented = new TreeMap<>();
    Glossary.defined(texts)
        .forEach(
            (acronym, definition) -> {
              List<Fact> cited = definition.facts().stream().map(model::fact).toList();
              // Where the reader finds it: the files, named with their repository in a system's.
              String source =
                  String.join(
                      ", ",
                      cited.stream()
                          .map(
                              f ->
                                  f.evidence().path() == null
                                      ? f.evidence().repo()
                                      : repo == null
                                          ? f.evidence().repo() + "/" + f.evidence().path()
                                          : f.evidence().path())
                          .distinct()
                          .toList());
              documented.put(
                  acronym,
                  new Glossary.Definition(definition.meaning(), assembler.facts(cited), source));
            });
    return Glossary.BUNDLED.section(
        assembler.id(SectionKind.GLOSSARY.jsonName()),
        QualityBar.acronymsUsed(document),
        documented);
  }

  Section section(SectionKind kind) {
    String id = assembler.id(kind.jsonName());
    List<Block> blocks = new ArrayList<>();
    List<Section> subsections = new ArrayList<>();
    switch (kind) {
      case OVERVIEW -> overview(id, blocks);
      case CONTEXT -> blocks.addAll(c4.context(id));
      case CONTAINERS -> c4.containers(id, blocks, subsections);
      case DEPLOYMENT -> c4.deployment(id).ifPresent(blocks::add);
      case DECISIONS -> {
        c4.decisions(id).ifPresent(blocks::add);
        blocks.addAll(unknownsPage().blocks(id));
        blocks.addAll(claims().callouts(id));
      }
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
              new RepoRisks(model, assembler, repo, containers)
                  .callouts(id, brief().currencyRisks(id), investigated().callouts(id)));
      case FINDINGS -> {
        blocks.addAll(investigated().table(id));
        blocks.addAll(new StoredFindings(model, assembler, repo, units).table(id));
      }
      case BLAST_RADIUS -> blocks.addAll(brief().blastRadius(id));
      case TECHNOLOGY_CURRENCY -> blocks.addAll(brief().technologyCurrency(id));
      case QUALITY_EVIDENCE -> blocks.addAll(brief().qualityEvidence(id));
      case GLOSSARY ->
          throw new IllegalArgumentException("The glossary is built from the whole document");
    }
    return new Section(id, kind.title(), kind.levels(), repoId, List.of(), blocks, subsections);
  }

  /** What the model of the scope holds as open or unanswered (#700), built once per document. */
  private Unknowns unknowns() {
    if (unknowns == null) {
      elements = new Elements(model, assembler.vocabulary(), assembler.names(), repos);
      unknowns =
          new Unknowns(
              model,
              elements,
              units,
              repo == null,
              repos,
              id -> model.summary(assembler.summarized(id)).isPresent());
    }
    return unknowns;
  }

  private UnknownsPage unknownsPage() {
    return new UnknownsPage(unknowns(), elements, assembler);
  }

  /**
   * The quality bar checks the scope's model fails (#700): what its containers, components and
   * lines lack, whichever diagram draws them.
   */
  List<QualityReport.Failure> modelFailures() {
    UnknownsPage page = unknownsPage();
    return QualityBar.model(model.vocabulary(), unknowns(), assembler::name, page::componentName);
  }

  /** What the LLM investigator found in scope (ADR 0110), built once per document. */
  private InvestigatedFindings investigated() {
    if (investigated == null) {
      investigated = new InvestigatedFindings(model, assembler, inScopeUnits());
    }
    return investigated;
  }

  /** The units in scope, and the system for the system's documents. */
  private Set<EntityId> inScopeUnits() {
    Set<EntityId> scope = new java.util.HashSet<>(units);
    if (repo == null) {
      scope.add(SYSTEM);
    }
    return scope;
  }

  /** The main flows, built once per document: the start-here list and the flows both use them. */
  private SequenceFlows flows() {
    if (flows == null) {
      flows =
          new SequenceFlows(
              model,
              assembler,
              c4,
              units,
              assembler.chosenFlows(repo == null ? ArchitectureModel.SYSTEM_SCOPE : repo));
    }
    return flows;
  }

  private BriefSections brief() {
    if (brief == null) {
      brief =
          new BriefSections(
              model,
              assembler,
              repo == null ? assembler.name(SYSTEM) : repo,
              modules,
              units,
              c4.elements().alternativeLinks().keySet(),
              asOf,
              EndOfLife.BUNDLED);
    }
    return brief;
  }

  /** The README claims of the scope's repositories that the code contradicts (ADR 0051). */
  private ClaimContradictions claims() {
    if (claims == null) {
      claims = new ClaimContradictions(model, assembler, repos);
    }
    return claims;
  }

  /**
   * The quality report's failures for README claims the code contradicts, pointing at their
   * callouts in the decisions section when the document has one. Call after the sections and before
   * {@link #facts()}.
   */
  List<QualityReport.Failure> claimFailures() {
    return claims().failures();
  }

  /**
   * The leads the LLM investigator refuted (#741, knowledge-build epic, section 11.2), each listed
   * with the finding that refutes it and the file ranges it cites, so a reader can check it.
   */
  List<QualityReport.Failure> refutedLeads() {
    List<QualityReport.Failure> result = new ArrayList<>();
    for (Unknowns.Refuted lead : unknowns().refutedLeads()) {
      StringBuilder text =
          new StringBuilder(
              ScopeFlows.list(lead.about().stream().map(assembler::name).toList())
                  + ": "
                  + lead.kind().replace('-', ' '));
      if (lead.finding() != null && !lead.finding().isBlank()) {
        text.append(". ").append(lead.finding().strip().replaceAll("\\s+", " "));
        if (!lead.finding().strip().endsWith(".")) {
          text.append('.');
        }
      } else {
        text.append('.');
      }
      if (!lead.cites().isEmpty()) {
        text.append(" Cites ").append(String.join(", ", lead.cites())).append('.');
      }
      result.add(
          new QualityReport.Failure(
              "A lead the LLM investigator refuted",
              null,
              List.of(TextRun.of(text.toString())),
              assembler.facts(List.of(lead.verdict()))));
    }
    return result;
  }

  private ExplorerSections explorer() {
    if (explorer == null) {
      explorer = new ExplorerSections(model, assembler, c4, flows(), units, repos);
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
              TextRun.of(container.type().equals(Types.SERVICE) ? ", a service" : ", a library"));
      items.add(new ListBlock.Item(content, assembler.facts(containerFacts(container))));
    }
    if (!items.isEmpty()) {
      blocks.add(new ListBlock(assembler.id(section, "builds"), false, items, List.of()));
    }
  }

  /**
   * The system's purpose (ADR 0060) and key fact (ADR 0109). Its repositories are not listed here:
   * the C4 report shows only this section's text under its title, and names each repository with
   * its pinned commit there (ADR 0048, amendments for #681 and #767).
   */
  private void systemOverview(String section, List<Block> blocks) {
    blocks.addAll(purpose(section));
  }

  /**
   * The system's purpose as the LLM worded it, marked as inferred when it cites no README claim
   * (ADR 0059), or stated plainly as unknown in a note, which cites nothing (docs/design.md, LLM
   * integration, Grounding); followed by the key fact, in the same paragraph when there is a
   * purpose (ADR 0109).
   */
  private List<Block> purpose(String section) {
    String id = assembler.id(section, "purpose");
    Assembler.Text purpose = assembler.purpose();
    if (purpose == null) {
      // A purpose the support judge rejected, though asked again, gives way to plain wording
      // (#751).
      purpose = assembler.plainPurpose();
    }
    Assembler.Text keyFact = assembler.keyFact();
    if (purpose == null) {
      List<Block> blocks = new ArrayList<>();
      blocks.add(
          new Callout(
              id,
              Callout.Variant.NOTE,
              null,
              null,
              List.of(TextRun.of(UNKNOWN_PURPOSE)),
              List.of()));
      if (keyFact != null) {
        blocks.add(
            new Paragraph(assembler.id(section, "key-fact"), keyFact.content(), keyFact.facts()));
      }
      return blocks;
    }
    if (keyFact == null) {
      return List.of(new Paragraph(id, purpose.content(), purpose.facts()));
    }
    // The purpose, then the key fact, as one paragraph (docs/quality-bar.md, Rubric, C4 report).
    List<Inline> content = new ArrayList<>(purpose.content());
    content.add(TextRun.of(" "));
    content.addAll(keyFact.content());
    List<String> facts = new ArrayList<>(purpose.facts());
    keyFact.facts().stream().filter(f -> !facts.contains(f)).forEach(facts::add);
    return List.of(new Paragraph(id, content, facts));
  }

  private Optional<Paragraph> paragraph(String section, EntityId entity) {
    ArchitectureModel.Summary summary = model.summary(entity).orElse(null);
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
    Map<EntityId, Map<EntityId, List<Fact>>> edges = new TreeMap<>(ArchitectureModel.BY_ID);
    for (Fact fact : model.moduleBuildDependencies()) {
      for (String from : model.repos(fact.subject())) {
        for (String to : model.repos(ArchitectureModel.object(fact))) {
          if (!from.equals(to) && (from.equals(repo) || to.equals(repo))) {
            edges
                .computeIfAbsent(Assembler.repo(from), f -> new TreeMap<>(ArchitectureModel.BY_ID))
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
    for (EntityId module : model.parts(container, Types.MODULE)) {
      facts.addAll(model.containsFacts(container, module));
      facts.addAll(model.packagingFacts(module));
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
      if (container.type().equals(Types.SERVICE)) {
        serviceCount++;
        services.addAll(containerFacts(container));
      } else {
        libraryCount++;
        libraries.addAll(containerFacts(container));
      }
    }
    List<Fact> moduleFacts = new ArrayList<>();
    SortedSet<EntityId> types = new TreeSet<>(ArchitectureModel.BY_ID);
    SortedSet<EntityId> thirdParty = new TreeSet<>(ArchitectureModel.BY_ID);
    List<Fact> thirdPartyFacts = new ArrayList<>();
    for (EntityId module : modules) {
      moduleFacts.addAll(model.packagingFacts(module));
      types.addAll(model.typesWithin(module));
      for (EntityId pkg : model.parts(module, Types.PACKAGE)) {
        types.addAll(model.typesWithin(pkg));
      }
      for (Fact fact : model.buildDependencyFacts(module)) {
        if (!ArchitectureModel.isTestScope(fact)
            && ArchitectureModel.object(fact).type().equals(Types.ARTIFACT)) {
          thirdParty.add(ArchitectureModel.object(fact));
          thirdPartyFacts.add(fact);
        }
      }
    }
    List<Fact> typeFacts = new ArrayList<>();
    for (EntityId type : types) {
      for (EntityId whole : model.wholes(type)) {
        typeFacts.addAll(model.containsFacts(whole, type));
      }
    }
    SortedSet<EntityId> uses = new TreeSet<>(ArchitectureModel.BY_ID);
    SortedSet<EntityId> usedBy = new TreeSet<>(ArchitectureModel.BY_ID);
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
      if (model.buildDependencyFacts(module).isEmpty()) {
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
    List<Tree.Node> nodes = new ArrayList<>();
    for (ArchitectureModel.BuildDependency dependency : model.buildDependencies(module)) {
      String scope = dependency.scope();
      boolean optional = dependency.optional();
      if (!direct && (optional || !TRANSITIVE_SCOPES.contains(scope))) {
        continue;
      }
      EntityId target = dependency.target();
      String version = dependency.version();
      String classifier = dependency.classifier();
      String type = dependency.type();
      String label =
          target.key()
              + (version == null ? "" : ":" + version)
              + (classifier == null ? "" : ":" + classifier)
              + (type == null || type.equals("jar") ? "" : " (" + type + ")")
              + (scope.equals("compile") ? "" : " (" + scope + ")")
              + (optional ? " (optional)" : "");
      List<Tree.Node> children =
          target.type().equals(Types.MODULE) && expanded.add(target)
              ? dependencyNodes(target, expanded, false)
              : List.of();
      nodes.add(
          new Tree.Node(
              label,
              assembler.entity(target),
              assembler.facts(List.of(dependency.fact())),
              children));
    }
    return nodes;
  }

  // Structure: the modules and their packages.

  private void structure(String section, List<Block> blocks) {
    List<Tree.Node> roots = new ArrayList<>();
    for (EntityId module : modules) {
      boolean top = model.wholes(module, Types.MODULE).stream().noneMatch(modules::contains);
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
    for (EntityId sub : model.parts(module, Types.MODULE)) {
      if (modules.contains(sub) && !visiting.contains(sub)) {
        children.add(moduleNode(sub, model.containsFacts(module, sub), visiting));
      }
    }
    for (EntityId pkg : model.parts(module, Types.PACKAGE)) {
      children.add(
          new Tree.Node(
              assembler.name(pkg),
              assembler.entity(pkg),
              assembler.facts(model.containsFacts(module, pkg)),
              List.of()));
    }
    visiting.remove(module);
    return new Tree.Node(
        assembler.name(module), assembler.entity(module), assembler.facts(facts), children);
  }
}
