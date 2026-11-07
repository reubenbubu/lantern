package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.core.types.Types;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.harness.LlmFacts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The architecture model of one snapshot as documents read it (knowledge-build epic, section 12;
 * {@code docs/design.md}, Composition, "Composition reads the model"): the stored facts, and the
 * texts the LLM roles stored about them, read apart.
 *
 * <p>It is read-only and built only from what is stored: the facts that hold in a snapshot, those
 * of origin llm included. The roles' texts (responsibilities, intents, summaries, group names, flow
 * judgements and narratives, purpose and key fact) are not among {@link #index()}'s facts, so what
 * is drawn does not depend on whether they were written; they are read through the methods below.
 * Where one thing holds several texts, the one with the highest confidence is read, the first by
 * fact ID on a tie, so the same facts always give the same texts.
 */
public final class ArchitectureModel {

  /** The scope of the system's documents among the flows' significance facts. */
  public static final String SYSTEM_SCOPE = "system";

  /** The software system all repositories of a snapshot form, the system scope's subject. */
  public static final EntityId SYSTEM = EntityId.SYSTEM;

  /** The ordering of texts: the most confident first, then by fact ID. */
  private static final Comparator<Fact> PREFERRED =
      Comparator.comparingDouble(Fact::confidence).reversed().thenComparing(Fact::id);

  private final Vocabulary vocabulary;
  private final Knowledge knowledge;
  private final Map<EntityId, Fact> responsibilities = new HashMap<>();
  private final Map<String, Fact> intents = new HashMap<>();
  private final Map<String, Fact> everyFact;
  private final Map<EntityId, Fact> summaries = new HashMap<>();
  private final Map<EntityId, List<Fact>> narratives = new HashMap<>();
  private final Map<String, Fact> groupIntents = new HashMap<>();
  private final Map<EntityId, Fact> displayNames = new HashMap<>();
  private final Map<String, List<Fact>> significance = new HashMap<>();
  private final List<Fact> purposes = new ArrayList<>();
  private final List<Fact> keyFacts = new ArrayList<>();
  private final Set<String> documented = new TreeSet<>();
  private final Set<String> rejected = new java.util.HashSet<>();

  /**
   * The model of the facts, with this version of Lantern's vocabulary ({@link
   * Vocabularies#standard()}).
   *
   * @param facts the facts that hold in a snapshot, the roles' texts included
   */
  public ArchitectureModel(Collection<Fact> facts) {
    this(Vocabularies.standard(), facts);
  }

  /**
   * @param vocabulary the vocabulary the snapshot's facts were recorded with, which says how each
   *     type is drawn (ADR 0114)
   * @param facts the facts that hold in a snapshot, the roles' texts included
   */
  public ArchitectureModel(Vocabulary vocabulary, Collection<Fact> held) {
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
    Objects.requireNonNull(held, "facts");
    // What the support judge rejected, though asked again, is kept in the store for the record and
    // left out of the model, so the documents show Lantern's plain wording in its place (ADR 0101,
    // the owner's decision of 4 Nov 2026 on #751).
    List<Fact> facts = new ArrayList<>();
    for (Fact fact : held) {
      if (rejectedByTheJudge(fact)) {
        rejected.add(fact.subject() + "\u0000" + fact.predicate());
      } else {
        facts.add(fact);
      }
    }
    List<Fact> others = new ArrayList<>();
    List<Fact> texts = new ArrayList<>();
    for (Fact fact : facts) {
      (LlmFacts.isText(fact) ? texts : others).add(fact);
    }
    this.knowledge = new Knowledge(others);
    texts.sort(PREFERRED);
    Map<String, Fact> byId = new HashMap<>();
    facts.forEach(f -> byId.putIfAbsent(f.id(), f));
    this.everyFact = byId;
    // A component's sentence also describes the type it is anchored on, which its diagram draws.
    Map<EntityId, EntityId> anchors = new HashMap<>();
    for (Fact anchored : knowledge.withPredicate(Types.ANCHORED_ON)) {
      if (Knowledge.isEntity(anchored)) {
        anchors.putIfAbsent(anchored.subject(), Knowledge.object(anchored));
      }
    }
    List<Fact> componentTexts = new ArrayList<>();
    for (Fact text : texts) {
      switch (text.predicate()) {
        case Types.RESPONSIBILITY -> {
          responsibilities.putIfAbsent(text.subject(), text);
          if (anchors.containsKey(text.subject())) {
            componentTexts.add(text);
          }
          if (LlmFacts.Basis.EVIDENCED
              .jsonName()
              .equals(text.attributes().path("basis").asString(""))) {
            // Code with Javadoc a sentence rests on counts as evidence, as README claims do (ADR
            // 0069).
            for (String cited : cites(text.attributes().get("cites"))) {
              Fact fact = byId.get(cited);
              if (fact != null && fact.predicate().equals(Types.KIND)) {
                documented.add(cited);
              }
            }
          }
        }
        case Types.INTENT -> {
          // A group's intent is keyed by its other end and protocol, which another group's may
          // share (ADR 0107): it is kept by the group as well.
          if (text.subject().type().equals(Types.GROUP)) {
            groupIntents.putIfAbsent(text.subject() + " " + text.object().value(), text);
          } else {
            intents.putIfAbsent(text.object().value(), text);
          }
        }
        case Types.SUMMARY -> summaries.putIfAbsent(text.subject(), text);
        case Types.DISPLAY_NAME -> displayNames.putIfAbsent(text.subject(), text);
        case Types.FLOW_NARRATIVE ->
            narratives.computeIfAbsent(text.subject(), f -> new ArrayList<>()).add(text);
        case Types.FLOW_SIGNIFICANCE ->
            significance.computeIfAbsent(text.object().value(), s -> new ArrayList<>()).add(text);
        case Types.PURPOSE -> purposes.add(text);
        case Types.KEY_FACT -> keyFacts.add(text);
        default -> {}
      }
    }
    // A sentence about the element itself comes before one about a component anchored on it.
    for (Fact text : componentTexts) {
      responsibilities.putIfAbsent(anchors.get(text.subject()), text);
    }
  }

  /**
   * The index of the stored facts, without the roles' texts, for the query classes of this package;
   * composition reads the model's own queries, never the index.
   */
  Knowledge index() {
    return knowledge;
  }

  /** The vocabulary, which says what each type is a kind of and how it is drawn. */
  public Vocabulary vocabulary() {
    return vocabulary;
  }

  /** The order of entities: by their text, which every sorted entity list uses. */
  public static final Comparator<EntityId> BY_ID = Knowledge.BY_ID;

  /** Whether the fact relates its subject to an entity, not to a value. */
  public static boolean isEntity(Fact fact) {
    return Knowledge.isEntity(fact);
  }

  /** The entity a fact relates its subject to; the fact must be {@link #isEntity}. */
  public static EntityId object(Fact fact) {
    return Knowledge.object(fact);
  }

  /** The view under which alone a fact holds, or {@code null} for the default view. */
  public static String view(Fact fact) {
    return Knowledge.view(fact);
  }

  /** The fact with the ID, or {@code null}. */
  public Fact fact(String id) {
    return knowledge.fact(id);
  }

  /**
   * A fact of the snapshot by its ID, the roles' texts included, or {@code null}: what a finding
   * may cite, such as an earlier answer a merge reason rests on (#765).
   */
  public Fact factOrText(String id) {
    return everyFact.get(id);
  }

  /** The facts a derived fact was made from, those that hold in the model, in the order stored. */
  public List<Fact> derivedFrom(Fact derived) {
    List<Fact> facts = new ArrayList<>();
    for (String id : Elements.strings(derived, "facts")) {
      Fact fact = knowledge.fact(id);
      if (fact != null) {
        facts.add(fact);
      }
    }
    return facts;
  }

  /** The facts a fact cites, those that hold in the model, in the order cited. */
  public List<Fact> citedFacts(Fact fact) {
    List<Fact> facts = new ArrayList<>();
    for (String id : cites(fact)) {
      Fact cited = knowledge.fact(id);
      if (cited != null) {
        facts.add(cited);
      }
    }
    return facts;
  }

  // ---------------------------------------------------------------------------------------------
  // What the composer reads (#761): typed queries, each for one thing a document shows.

  /** The facts that describe an element, which its box and rows cite. */
  public List<Fact> describing(EntityId element) {
    return knowledge.attributes(element);
  }

  /** Whether any fact is about the entity. */
  public boolean hasFactsAbout(EntityId id) {
    return !knowledge.about(id).isEmpty();
  }

  /** The connections Investigate found to an entity (ADR 0110). */
  public List<Fact> investigatedTo(EntityId id) {
    return knowledge.withPredicate(Types.INVESTIGATED_CONNECTION).stream()
        .filter(f -> isEntity(f) && object(f).equals(id))
        .toList();
  }

  /**
   * Whether only the LLM's facts know the entity: every fact about it comes from the LLM, and no
   * fact of static analysis or derivation relates anything to it.
   */
  public boolean knownOnlyToTheLlm(EntityId id) {
    if (knowledge.about(id).stream().anyMatch(f -> f.origin().kind() != Origin.Kind.LLM)) {
      return false;
    }
    if (notFromTheLlm == null) {
      Set<EntityId> objects = new java.util.HashSet<>();
      for (Origin.Kind kind : Origin.Kind.values()) {
        if (kind != Origin.Kind.LLM) {
          knowledge.withOrigin(kind).stream()
              .filter(ArchitectureModel::isEntity)
              .forEach(f -> objects.add(object(f)));
        }
      }
      notFromTheLlm = objects;
    }
    return !notFromTheLlm.contains(id);
  }

  /** The entities a fact not from the LLM relates something to, found once. */
  private Set<EntityId> notFromTheLlm;

  /**
   * The links of one interaction type from a unit, method or frontend ({@code CALLS_HTTP}, {@code
   * PUBLISHES}, {@code READS_TABLE}, ...), in the order the model holds them.
   */
  public List<Fact> linksOf(EntityId from, String interaction) {
    return knowledge.relations(from, interaction);
  }

  /** The libraries a unit bundles, as its {@code BUNDLES} facts. */
  public List<Fact> bundles(EntityId unit) {
    return knowledge.relations(unit, Types.BUNDLES);
  }

  /**
   * The members of a group, as its {@code GROUPS} facts: those it still holds once a group formed
   * by meaning has split it (#932), none when too few are left for it to stand for anything.
   */
  public List<Fact> groupMembers(EntityId group) {
    if (resolvedMembers == null) {
      Map<EntityId, Set<EntityId>> members = new HashMap<>();
      for (String kind : List.of(Groups.DOMAIN, Groups.FEATURE_AREA)) {
        Groups.of(knowledge, kind).forEach(g -> members.put(g.id(), g.members()));
      }
      resolvedMembers = members;
    }
    Set<EntityId> held = resolvedMembers.getOrDefault(group, Set.of());
    return knowledge.relations(group, Types.GROUPS).stream()
        .filter(f -> held.contains(Knowledge.object(f)))
        .toList();
  }

  private Map<EntityId, Set<EntityId>> resolvedMembers;

  /** The facts that make a group: those about it and those holding its members. */
  public List<Fact> groupFacts(EntityId group) {
    List<Fact> facts = new ArrayList<>(knowledge.about(group));
    facts.addAll(groupMembers(group));
    return facts;
  }

  /** The Unknown caller's lines (ADR 0074, amendment for #766). */
  public List<Fact> unknownCallerLines() {
    return knowledge.withPredicate(Types.CALLS_UNREACHED);
  }

  /** The links of a unit Lantern could not link, resolved or not. */
  public List<Fact> unresolvedLinks(EntityId unit) {
    return attributes(unit, Types.UNRESOLVED_LINK);
  }

  /** An element's technology, as stored. */
  public Optional<String> technology(EntityId element) {
    return knowledge.attribute(element, Types.TECHNOLOGY);
  }

  /** How a module is packaged ({@code jar}, {@code pom}, ...), as stored. */
  public Optional<String> packaging(EntityId module) {
    return knowledge.attribute(module, Types.PACKAGING);
  }

  /** The facts saying how a module is packaged. */
  public List<Fact> packagingFacts(EntityId module) {
    return attributes(module, Types.PACKAGING);
  }

  /** How a component accesses its unit's data: reads, writes or both; empty when it does not. */
  public Optional<String> dataAccess(EntityId component) {
    return knowledge.attribute(component, Types.DATA_ACCESS);
  }

  /** A noun phrase made plural: "work order" is "work orders", "entry" "entries". */
  static String plural(String words) {
    if (words.endsWith("s")) {
      return words;
    }
    if (words.matches(".*[^aeiou]y")) {
      return words.substring(0, words.length() - 1) + "ies";
    }
    if (words.matches(".*(x|ch|sh)")) {
      return words + "es";
    }
    return words + "s";
  }

  /**
   * What a component's data access touches (#944): the tables it reads or writes, or the types its
   * repositories are for, in words ("work_orders" and {@code WorkOrder} are "work orders" and "work
   * order"), once each, sorted.
   */
  public List<String> dataAccessed(EntityId component) {
    java.util.SortedSet<String> names = new java.util.TreeSet<>();
    for (Fact access : attributes(component, Types.DATA_ACCESS)) {
      for (Fact basis : derivedFrom(access)) {
        String words = DataStores.words(basis);
        if (words != null) {
          names.add(words);
        }
      }
    }
    return List.copyOf(names);
  }

  private DataStores dataStores;

  /**
   * What the types of a unit's component do in each store of the unit, by store (#962): only the
   * stores their code touches.
   */
  public java.util.SortedMap<EntityId, DataStores.Access> storeAccess(
      EntityId unit, java.util.Collection<EntityId> types) {
    if (dataStores == null) {
      dataStores = new DataStores(knowledge);
    }
    return dataStores.access(unit, types);
  }

  /** The facts saying a type is an application's main class. */
  public List<Fact> mainClassFacts(EntityId type) {
    return attributes(type, Types.MAIN_CLASS);
  }

  /** The build dependencies of a module as stored, in the order the model holds them. */
  public List<Fact> buildDependencyFacts(EntityId module) {
    return knowledge.relations(module, Types.BUILD_DEPENDS_ON);
  }

  /** The build dependencies of modules on other entities, outside test scope. */
  public List<Fact> moduleBuildDependencies() {
    return knowledge.withPredicate(Types.BUILD_DEPENDS_ON).stream()
        .filter(f -> isEntity(f) && f.subject().type().equals(Types.MODULE) && !isTestScope(f))
        .toList();
  }

  /** The claims of a repository's READMEs, in the order the model holds them. */
  public List<Fact> readmeClaims(String repo) {
    return knowledge.withPredicate(Types.CLAIM).stream()
        .filter(
            f ->
                f.subject().type().equals(Types.README) && f.subject().key().startsWith(repo + ":"))
        .toList();
  }

  /**
   * What the documentation says, by fact ID: the text of each fact read from a README or other
   * document, of the repository, or of all when it is {@code null}.
   */
  public Map<String, String> documentationTexts(String repo) {
    Map<String, String> texts = new java.util.LinkedHashMap<>();
    for (Fact fact : knowledge.withOrigin(Origin.Kind.README)) {
      if (fact.object().kind() == io.lantern.core.fact.FactObject.Kind.STRING
          && (repo == null || repo.equals(fact.evidence().repo()))) {
        texts.put(fact.id(), fact.object().value());
      }
    }
    return texts;
  }

  /**
   * How a schema is migrated: the fact naming its most specific kind with a file (Flyway, then
   * Liquibase, then plain SQL, as the SQL extractor names them, ADR 0034), and that kind.
   */
  public record Migration(Fact fact, String kind) {}

  private static final List<String> MIGRATION_KINDS = List.of("flyway", "liquibase", "sql");

  /** How the schema is migrated, or empty. */
  public Optional<Migration> migration(EntityId schema) {
    return attributes(schema, Types.KIND).stream()
        .filter(f -> f.evidence().path() != null)
        .filter(f -> MIGRATION_KINDS.contains(f.object().value()))
        .min(Comparator.comparingInt((Fact f) -> MIGRATION_KINDS.indexOf(f.object().value())))
        .map(f -> new Migration(f, f.object().value()));
  }

  /** The facts with the entity as subject. */
  List<Fact> about(EntityId subject) {
    return knowledge.about(subject);
  }

  /** The facts with the entity as subject and a value, not an entity, as object. */
  List<Fact> attributes(EntityId subject) {
    return knowledge.attributes(subject);
  }

  /**
   * The facts with the entity as subject and one of the predicates, a value rather than an entity
   * as object, in the order the model holds them.
   */
  List<Fact> attributes(EntityId subject, Collection<String> predicates) {
    return knowledge.attributes(subject).stream()
        .filter(f -> predicates.contains(f.predicate()))
        .toList();
  }

  /** The value of the first attribute of the entity with the predicate, by fact ID. */
  Optional<String> attribute(EntityId subject, String predicate) {
    return knowledge.attribute(subject, predicate);
  }

  /** The facts of the origin kind, in {@link Knowledge#STABLE} order. */
  List<Fact> withOrigin(Origin.Kind kind) {
    return knowledge.withOrigin(kind);
  }

  /**
   * The types whose endpoints a gateway routes requests to in a unit (#931), with the routed
   * endpoint facts that say so: a route's link to the unit names no method at the unit's end when
   * the gateway rewrites the path, but each endpoint it reaches is stored on the gateway.
   */
  public SortedMap<EntityId, List<Fact>> routedTypes(EntityId gateway, EntityId unit) {
    SortedMap<EntityId, List<Fact>> result = new TreeMap<>(BY_ID);
    for (Fact fact : knowledge.attributes(gateway)) {
      if (!fact.predicate().equals(Types.ROUTED_ENDPOINT)) {
        continue;
      }
      JsonNode target = fact.attributes().get("target");
      JsonNode endpoint = fact.attributes().get("endpoint");
      if (target == null || endpoint == null || !unit.toString().equals(target.asString())) {
        continue;
      }
      EntityId method;
      try {
        method = EntityId.parse(endpoint.asString());
      } catch (IllegalArgumentException e) {
        continue;
      }
      result
          .computeIfAbsent(Components.ownerType(knowledge, method), t -> new ArrayList<>())
          .add(fact);
    }
    return result;
  }

  /** The facts with the predicate. */
  List<Fact> withPredicate(String predicate) {
    return knowledge.withPredicate(predicate);
  }

  /** The facts relating the subject to an entity by the predicate. */
  List<Fact> relations(EntityId subject, String predicate) {
    return knowledge.relations(subject, predicate);
  }

  /** What the entity contains, of the given entity type. */
  public SortedSet<EntityId> parts(EntityId whole, String type) {
    return knowledge.parts(whole, type);
  }

  /** What contains the entity, of the given entity type. */
  public SortedSet<EntityId> wholes(EntityId part, String type) {
    return knowledge.wholes(part, type);
  }

  /** Everything that contains the entity. */
  public SortedSet<EntityId> wholes(EntityId part) {
    return knowledge.wholes(part);
  }

  /** The facts saying that the whole contains the part. */
  public List<Fact> containsFacts(EntityId whole, EntityId part) {
    return knowledge.containsFacts(whole, part);
  }

  /** The services and libraries that build the module. */
  public SortedSet<EntityId> containers(EntityId module) {
    return knowledge.containers(module);
  }

  /**
   * The module of the main library a deployable unit bundles, which describes the unit when it has
   * no summary of its own (ADR 0066), if it bundles one.
   */
  public Optional<EntityId> mainLibraryModule(EntityId unit) {
    for (Fact fact : knowledge.relations(unit, Types.BUNDLES)) {
      if (Elements.attribute(fact, "main", "false").equals("true")) {
        SortedSet<EntityId> modules = knowledge.parts(object(fact), Types.MODULE);
        if (!modules.isEmpty()) {
          return Optional.of(modules.first());
        }
      }
    }
    return Optional.empty();
  }

  /**
   * A build dependency of a module (a Maven dependency fact): what it depends on, and how.
   *
   * @param fact the dependency fact
   * @param target the artifact depended on
   * @param scope its Maven scope, {@code compile} when none is stated
   * @param optional whether it is declared optional
   * @param version its version, or {@code null} when none is stated
   * @param classifier its classifier, or {@code null}
   * @param type its artifact type, or {@code null} when none is stated
   */
  public record BuildDependency(
      Fact fact,
      EntityId target,
      String scope,
      boolean optional,
      String version,
      String classifier,
      String type) {}

  /**
   * The build dependencies of a module, sorted by what they depend on, then their version and fact
   * ID, so the same facts always give the same order.
   */
  public List<BuildDependency> buildDependencies(EntityId module) {
    List<Fact> facts = new ArrayList<>(knowledge.relations(module, Types.BUILD_DEPENDS_ON));
    facts.sort(
        Comparator.comparing((Fact f) -> object(f).key())
            .thenComparing(f -> f.object().value())
            .thenComparing(Fact::id));
    return facts.stream()
        .map(
            f ->
                new BuildDependency(
                    f,
                    object(f),
                    Elements.attribute(f, "scope", "compile"),
                    Elements.attribute(f, "optional", "false").equals("true"),
                    Elements.attribute(f, "version", null),
                    Elements.attribute(f, "classifier", null),
                    Elements.attribute(f, "artifactType", null)))
        .toList();
  }

  /** Whether a build dependency is in test scope only, so that the code it runs is not built. */
  public static boolean isTestScope(Fact fact) {
    return Elements.attribute(fact, "scope", "compile").equals("test");
  }

  /** A fact's description attribute, or the empty text when it has none. */
  public static String description(Fact fact) {
    return fact.attributes().path("description").asString("");
  }

  /** The IDs of the facts a fact cites, in order. */
  public static List<String> cites(Fact fact) {
    return cites(fact.attributes().get("cites"));
  }

  /** Whether the fact is a claim a README makes, which the code can be checked against. */
  public static boolean isClaim(Fact fact) {
    return fact.predicate().equals(Types.CLAIM);
  }

  /**
   * Who builds a module, for what uses it: the service or library building it, else the module
   * itself.
   */
  public EntityId builderOf(EntityId module) {
    SortedSet<EntityId> containers = knowledge.containers(module);
    return containers.isEmpty() ? module : containers.first();
  }

  /** The types directly in a package, module or type, and their member types, recursively. */
  public SortedSet<EntityId> typesWithin(EntityId whole) {
    return knowledge.typesWithin(whole);
  }

  /** The repositories whose modules' facts the model holds, sorted. */
  public SortedSet<String> repos() {
    return knowledge.repos();
  }

  /** The modules whose facts were observed in the repository. */
  public SortedSet<EntityId> modules(String repo) {
    return knowledge.modules(repo);
  }

  /** The repositories the module's facts were observed in. */
  public SortedSet<String> repos(EntityId module) {
    return knowledge.repos(module);
  }

  /** Whether a link the linker left open was resolved by the LLM resolver. */
  public boolean resolved(Fact unresolved) {
    return knowledge.resolved(unresolved);
  }

  /** The link of the LLM resolver that resolved the link left open, or {@code null}. */
  public Fact resolution(Fact unresolved) {
    return knowledge.resolution(unresolved);
  }

  /**
   * The responsibility sentence of an element (section 11.4), or of the component anchored on it.
   */
  public Optional<Text> responsibility(EntityId element) {
    return Optional.ofNullable(responsibilities.get(element)).map(ArchitectureModel::text);
  }

  /**
   * The intent phrase of an interaction (section 11.4), found by its claim ID, or its fact ID when
   * it has none; a person's uses are not looked up here.
   */
  public Optional<Text> intent(Fact interaction) {
    String key = interaction.claimId() != null ? interaction.claimId() : interaction.id();
    return Optional.ofNullable(intents.get(key))
        .filter(f -> !f.subject().type().equals(Types.PERSON))
        .map(ArchitectureModel::text);
  }

  /**
   * The intent Describe stored for a container line that merges links whose intents differ (ADR
   * 0103, amendment for #810), or empty.
   */
  public Optional<Text> lineIntent(EntityId from, EntityId to, String relationship) {
    return Optional.ofNullable(intents.get(MergedLines.key(from, to, relationship)))
        .map(ArchitectureModel::text);
  }

  /**
   * The intent phrase of a person's use of a frontend (ADR 0103): stored under the person's basis
   * and the frontend, since one basis stands for every frontend the person uses.
   */
  public Optional<Text> personIntent(Fact basis, EntityId frontend) {
    String key = basis.claimId() != null ? basis.claimId() : basis.id();
    return Optional.ofNullable(intents.get(key + "/" + frontend)).map(ArchitectureModel::text);
  }

  /**
   * The name Name groups gave a group (section 11.8; ADR 0107), without citations.
   *
   * @param group the group
   */
  public Optional<String> displayName(EntityId group) {
    return Optional.ofNullable(displayNames.get(group)).map(f -> text(f).text());
  }

  /**
   * The intent Name groups stored for a line leaving a group (section 11.8; ADR 0107).
   *
   * @param group the group
   * @param otherEnd what the line leads to, with its protocol: {@code "<other end> <protocol>"}
   */
  public Optional<Text> groupIntent(EntityId group, String otherEnd) {
    return Optional.ofNullable(groupIntents.get(group + " " + otherEnd))
        .map(ArchitectureModel::text);
  }

  /** The summary of a unit of code (section 11.3): its sentences, each with what it cites. */
  public Optional<Summary> summary(EntityId unit) {
    Fact fact = summaries.get(unit);
    if (fact == null) {
      return Optional.empty();
    }
    List<Text> sentences = new ArrayList<>();
    LlmFacts.Basis basis = basis(fact);
    for (JsonNode sentence : fact.attributes().path("sentences")) {
      // A citation of a fact that no longer holds, such as one of a repository carried forward, is
      // left out, as a sentence's is; a sentence left citing nothing is left out with it.
      List<String> held =
          cites(sentence.get("cites")).stream().filter(c -> knowledge.fact(c) != null).toList();
      if (!held.isEmpty()) {
        sentences.add(new Text(sentence.path("text").asString(""), held, basis, null));
      }
    }
    if (sentences.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Summary(fact.subject(), sentences, basis, fact.confidence()));
  }

  /**
   * A flow's narrative (section 11.5), the most confident of those that hold for its steps as they
   * are: one written for another content of its steps is not read.
   *
   * @param content the content hash of the flow's steps, or {@code null} to take any
   */
  public Optional<Narrative> narrative(EntityId flow, String content) {
    // The most confident of those written for the steps as they are.
    Fact fact =
        narratives.getOrDefault(flow, List.of()).stream()
            .filter(
                f -> {
                  String held = f.attributes().path("content").asString("");
                  return content == null || held.isEmpty() || held.equals(content);
                })
            .findFirst()
            .orElse(null);
    if (fact == null) {
      return Optional.empty();
    }
    JsonNode fieldCites = fact.attributes().get("fieldCites");
    List<String> all = cites(fact.attributes().get("cites"));
    LlmFacts.Basis basis = basis(fact);
    java.util.function.Function<String, Text> field =
        name -> {
          JsonNode value = fact.attributes().get(name);
          if (value == null || !value.isString()) {
            return null;
          }
          List<String> own = fieldCites == null ? all : cites(fieldCites.get(name));
          return new Text(value.asString(), own, basis, null);
        };
    Text title = field.apply("title");
    return Optional.of(
        new Narrative(
            title == null ? null : title.text().replaceAll("[.\\s]+$", ""),
            field.apply("walkthrough"),
            field.apply("preconditions"),
            field.apply("postconditions")));
  }

  /**
   * The flows the flow significance role judged significant for a scope (section 11.5), in its rank
   * order, by flow ID; empty when it did not judge the scope, or when any of its judgements was
   * made on other steps than the flow has now, as a narrative's would not be read (#743).
   *
   * @param scope {@code system}, or a repository's name
   */
  public Optional<List<String>> significantFlows(String scope) {
    List<Fact> judged = significance.get(scope);
    if (judged == null) {
      return Optional.empty();
    }
    Map<EntityId, Fact> byFlow = new HashMap<>();
    judged.forEach(f -> byFlow.putIfAbsent(f.subject(), f));
    // One judgement made on other steps than its flow has now makes the scope's choice stale, since
    // the flows were judged against each other: the rank decides until the role judges it again.
    // One that names no steps, or whose flow's steps are not known here, is read.
    boolean stale =
        byFlow.values().stream()
            .anyMatch(
                f -> {
                  String held = f.attributes().path("content").asString("");
                  String now = FlowModel.content(knowledge, f.subject()).orElse("");
                  return !held.isEmpty() && !now.isEmpty() && !held.equals(now);
                });
    if (stale) {
      return Optional.empty();
    }
    return Optional.of(
        byFlow.values().stream()
            .filter(f -> f.attributes().path("significant").asBoolean(false))
            .filter(f -> f.attributes().path("rank").isNumber())
            .sorted(
                Comparator.comparingInt((Fact f) -> f.attributes().path("rank").asInt())
                    .thenComparing(f -> f.subject().toString()))
            .map(f -> f.subject().toString())
            .toList());
  }

  /** Whether the support judge found a text a role wrote unsupported by what it cites. */
  static boolean rejectedByTheJudge(Fact fact) {
    return fact.origin().kind() == Origin.Kind.LLM
        && "unsupported".equals(fact.attributes().path(LlmFacts.SUPPORT).asString(""));
  }

  /**
   * Whether the model left out a text of the kind about the subject because the support judge
   * rejected it (#751): the documents then show Lantern's plain wording for it.
   */
  public boolean rejected(EntityId subject, String predicate) {
    return rejected.contains(subject + "\u0000" + predicate);
  }

  /**
   * A paragraph of a README that reads as a description of the system.
   *
   * @param text the paragraph as its authors wrote it, its line breaks made spaces
   * @param fact the ID of the claim fact it comes from
   */
  public record ReadmeParagraph(String text, String fact) {}

  /**
   * The first README claim, in the order of the repositories, that reads as a description: a
   * sentence of at least six words; what the documents quote in place of a purpose the support
   * judge rejected (#751).
   */
  public Optional<ReadmeParagraph> firstReadmeDescription() {
    for (String repo : repos()) {
      for (Fact claim : readmeClaims(repo)) {
        String text = claim.object().value().strip().replaceAll("\\s+", " ");
        if (descriptive(text)) {
          return Optional.of(new ReadmeParagraph(text, claim.id()));
        }
      }
    }
    return Optional.empty();
  }

  /** Whether a README paragraph reads as a description: a sentence of at least six words. */
  static boolean descriptive(String text) {
    return text.split(" ").length >= 6
        && text.matches("[\\p{L}\\p{N}\"'(].*[.!?]")
        && !text.startsWith("|");
  }

  /** The entities of the type that facts of the model are about, by ID. */
  public SortedSet<EntityId> entities(String type) {
    SortedSet<EntityId> found = new TreeSet<>(Knowledge.BY_ID);
    everyFact.values().stream()
        .map(Fact::subject)
        .filter(id -> id.type().equals(type))
        .forEach(found::add);
    return found;
  }

  /** The system's purpose (section 11.6). */
  public Optional<Text> purpose() {
    return purposes.stream().findFirst().map(ArchitectureModel::text);
  }

  /** The system's key fact (section 11.6). */
  public Optional<Text> keyFact() {
    return keyFacts.stream().findFirst().map(ArchitectureModel::text);
  }

  /** The IDs of the code facts with Javadoc that evidenced sentences cite (ADR 0069). */
  public Set<String> documented() {
    return Set.copyOf(documented);
  }

  /**
   * A text a role wrote.
   *
   * @param text the text, without citations
   * @param cites the IDs of the facts it cites, in order
   * @param basis whether it rests on what people wrote
   * @param support the support judge's verdict, or {@code null} if it did not check it
   */
  public record Text(String text, List<String> cites, LlmFacts.Basis basis, String support) {

    public Text {
      Objects.requireNonNull(text, "text");
      cites = List.copyOf(cites);
    }

    /** The text with its citation markers after it, {@code [fact:<fact ID>]}. */
    public String marked() {
      StringBuilder marked = new StringBuilder(text);
      cites.forEach(c -> marked.append(" [fact:").append(c).append(']'));
      return marked.toString();
    }

    /** Whether it rests on what people wrote, not only on what the code shows. */
    public boolean evidenced() {
      return basis == LlmFacts.Basis.EVIDENCED;
    }
  }

  /**
   * A unit's summary.
   *
   * @param unit what it summarizes
   * @param sentences its sentences
   * @param basis whether its first sentence rests on what people wrote
   * @param confidence how sure it is
   */
  public record Summary(
      EntityId unit, List<Text> sentences, LlmFacts.Basis basis, double confidence) {

    public Summary {
      sentences = List.copyOf(sentences);
    }

    /** Its sentences, each followed by its citation markers. */
    public String marked() {
      return String.join(" ", sentences.stream().map(Text::marked).toList());
    }

    /** Whether its first sentence rests on what people wrote, not only on what the code shows. */
    public boolean evidenced() {
      return basis == LlmFacts.Basis.EVIDENCED;
    }
  }

  /**
   * A flow's narrative; any part may be {@code null}.
   *
   * @param title the scenario, a few words without citations
   * @param walkthrough two to four sentences of what it does
   * @param preconditions what must hold before it starts
   * @param postconditions what has changed when it ends
   */
  public record Narrative(
      String title, Text walkthrough, Text preconditions, Text postconditions) {}

  /**
   * The facts with the entity as subject and the predicate, a value rather than an entity as
   * object, by fact ID.
   */
  List<Fact> attributes(EntityId subject, String predicate) {
    return knowledge.attributes(subject).stream()
        .filter(f -> f.predicate().equals(predicate))
        .toList();
  }

  /**
   * A README claim the code contradicts (ADR 0051): the claim, and the facts of the code that
   * contradict it, once a link the resolver picked is read as that link.
   *
   * @param fact the contradiction fact
   * @param claim the claim it is about, which holds in the snapshot
   * @param contradictedBy the facts of the code that contradict the claim, in the order stored
   * @param stated what the claim states, if the contradiction says
   * @param found what the code shows, if the contradiction says
   */
  public record Contradiction(
      Fact fact, Fact claim, List<Fact> contradictedBy, String stated, List<String> found) {

    /** What the claim says, as the README states it. */
    public String claimed() {
      return claim.object().value();
    }

    /** The kind of contradiction, as stored. */
    public String kind() {
      return fact.object().value();
    }
  }

  /**
   * How a fact on the code's side reads (#761): its subject, then either a verb and the entity it
   * relates the subject to, or the rest of the sentence; and the profile it holds under.
   *
   * @param subject what the fact is about
   * @param verb how the subject relates to the entity, or {@code null}
   * @param entity the entity, or {@code null}
   * @param rest the rest of the sentence after the subject, when no entity, or {@code null}
   * @param view the Spring profile it holds under, or {@code null}
   */
  public record Reading(EntityId subject, String verb, EntityId entity, String rest, String view) {}

  private static final Map<String, String> VERBS =
      Map.of(
          Types.CALLS_HTTP,
          "calls",
          Types.SENDS_MAIL,
          "sends mail through",
          Types.USES_IDENTITY_PROVIDER,
          "uses as its identity provider",
          Types.ACCESSES_DATA_OF,
          "accesses the data of",
          Types.USES_DATABASE,
          "uses",
          Types.PUBLISHES,
          "publishes to",
          Types.CONSUMES,
          "consumes from",
          Types.USES_BROKER,
          "uses");

  /** How the fact reads (see {@link Reading}). */
  public static Reading reading(Fact fact) {
    String predicate = fact.predicate();
    if (isEntity(fact)) {
      return new Reading(
          fact.subject(),
          VERBS.getOrDefault(predicate, words(predicate)),
          object(fact),
          null,
          view(fact));
    }
    String value = fact.object().value();
    String rest =
        predicate.equals(Types.UNRESOLVED_LINK)
            ? " makes a call Lantern could not link: " + value
            : "'s " + words(predicate) + " is " + value;
    return new Reading(fact.subject(), null, null, rest, view(fact));
  }

  private static String words(String predicate) {
    return predicate.toLowerCase(java.util.Locale.ROOT).replace('_', ' ').replace('-', ' ');
  }

  /**
   * The contradictions of the claims in the repositories, each with both sides in the snapshot
   * (only those with both are returned), in the order stored.
   *
   * @param repos the repositories in scope
   */
  public List<Contradiction> contradictions(Set<String> repos) {
    List<Contradiction> result = new ArrayList<>();
    for (Fact fact : knowledge.withPredicate(Types.CLAIM_CONTRADICTION)) {
      if (isEntity(fact) || !repos.contains(fact.evidence().repo())) {
        continue;
      }
      Fact claim = knowledge.fact(Elements.attribute(fact, "claim", ""));
      List<Fact> by = new ArrayList<>();
      JsonNode entries = fact.attributes().get("contradictedBy");
      if (entries != null && entries.isArray()) {
        for (JsonNode entry : entries) {
          JsonNode id = entry.get("fact");
          Fact found = id == null ? null : knowledge.fact(id.asString());
          if (found != null && found.predicate().equals(Types.UNRESOLVED_LINK)) {
            // A call the resolver linked is shown as the link it picked.
            Fact resolution = knowledge.resolution(found);
            found = resolution == null ? found : resolution;
          }
          if (found != null && !by.contains(found)) {
            by.add(found);
          }
        }
      }
      // Both sides must hold in the snapshot to be shown and cited.
      if (claim != null && !by.isEmpty()) {
        result.add(
            new Contradiction(
                fact,
                claim,
                by,
                Elements.attribute(fact, "stated", null),
                Elements.strings(fact, "found")));
      }
    }
    return result;
  }

  private static Text text(Fact fact) {
    JsonNode support = fact.attributes().get(LlmFacts.SUPPORT);
    return new Text(
        fact.attributes().path("text").asString(""),
        cites(fact.attributes().get("cites")),
        basis(fact),
        support != null && support.isString() ? support.asString() : null);
  }

  private static LlmFacts.Basis basis(Fact fact) {
    return LlmFacts.Basis.EVIDENCED.jsonName().equals(fact.attributes().path("basis").asString(""))
        ? LlmFacts.Basis.EVIDENCED
        : LlmFacts.Basis.INFERRED;
  }

  private static List<String> cites(JsonNode array) {
    List<String> cites = new ArrayList<>();
    if (array != null) {
      array.forEach(c -> cites.add(c.asString()));
    }
    return cites;
  }
}
