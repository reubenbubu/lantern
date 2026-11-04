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
import java.util.SortedSet;
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
  public static final EntityId SYSTEM = new EntityId(Types.SYSTEM, "snapshot");

  /** The ordering of texts: the most confident first, then by fact ID. */
  private static final Comparator<Fact> PREFERRED =
      Comparator.comparingDouble(Fact::confidence).reversed().thenComparing(Fact::id);

  private final Vocabulary vocabulary;
  private final Knowledge knowledge;
  private final Map<EntityId, Fact> responsibilities = new HashMap<>();
  private final Map<String, Fact> intents = new HashMap<>();
  private final Map<EntityId, Fact> summaries = new HashMap<>();
  private final Map<EntityId, List<Fact>> narratives = new HashMap<>();
  private final Map<String, Fact> groupIntents = new HashMap<>();
  private final Map<EntityId, Fact> displayNames = new HashMap<>();
  private final Map<String, List<Fact>> significance = new HashMap<>();
  private final List<Fact> purposes = new ArrayList<>();
  private final List<Fact> keyFacts = new ArrayList<>();
  private final Set<String> documented = new TreeSet<>();

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
  public ArchitectureModel(Vocabulary vocabulary, Collection<Fact> facts) {
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
    Objects.requireNonNull(facts, "facts");
    List<Fact> others = new ArrayList<>();
    List<Fact> texts = new ArrayList<>();
    for (Fact fact : facts) {
      (LlmFacts.isText(fact) ? texts : others).add(fact);
    }
    this.knowledge = new Knowledge(others);
    texts.sort(PREFERRED);
    Map<String, Fact> byId = new HashMap<>();
    facts.forEach(f -> byId.putIfAbsent(f.id(), f));
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

  /** The facts with the entity as subject. */
  public List<Fact> about(EntityId subject) {
    return knowledge.about(subject);
  }

  /** The facts with the entity as subject and a value, not an entity, as object. */
  public List<Fact> attributes(EntityId subject) {
    return knowledge.attributes(subject);
  }

  /**
   * The facts with the entity as subject and one of the predicates, a value rather than an entity
   * as object, in the order the model holds them.
   */
  public List<Fact> attributes(EntityId subject, Collection<String> predicates) {
    return knowledge.attributes(subject).stream()
        .filter(f -> predicates.contains(f.predicate()))
        .toList();
  }

  /** The value of the first attribute of the entity with the predicate, by fact ID. */
  public Optional<String> attribute(EntityId subject, String predicate) {
    return knowledge.attribute(subject, predicate);
  }

  /** The facts of the origin kind, by ID. */
  public List<Fact> withOrigin(Origin.Kind kind) {
    return knowledge.withOrigin(kind);
  }

  /** The facts with the predicate. */
  public List<Fact> withPredicate(String predicate) {
    return knowledge.withPredicate(predicate);
  }

  /** The facts relating the subject to an entity by the predicate. */
  public List<Fact> relations(EntityId subject, String predicate) {
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
   * order, by flow ID; empty when it did not judge the scope. A judgement made on other steps than
   * the flow has now is not read, as a narrative's is not (#743); when none is left, the scope is
   * not judged.
   *
   * @param scope {@code system}, or a repository's name
   */
  public Optional<List<String>> significantFlows(String scope) {
    List<Fact> judged = significance.get(scope);
    if (judged == null) {
      return Optional.empty();
    }
    Map<EntityId, Fact> byFlow = new HashMap<>();
    judged.stream()
        .filter(
            f -> {
              // One that names no steps, or whose flow's steps are not known here, is read.
              String held = f.attributes().path("content").asString("");
              String now = FlowModel.content(knowledge, f.subject()).orElse("");
              return held.isEmpty() || now.isEmpty() || held.equals(now);
            })
        .forEach(f -> byFlow.putIfAbsent(f.subject(), f));
    if (byFlow.isEmpty()) {
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

    /** Whether the support judge found a claim of it supported by none of its cited facts. */
    public boolean unsupported() {
      return "unsupported".equals(support);
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
   * What a stored fact says, read apart from its citation: a relation to an entity or a value, and
   * the view it holds under, if any.
   *
   * @param subject what the fact is about
   * @param predicate the relation or attribute's name
   * @param entity the entity it relates the subject to, or {@code null} for a value
   * @param value the value, or {@code null} for an entity
   * @param view the view the fact holds under, or {@code null} for the default view
   */
  public record Statement(
      EntityId subject, String predicate, EntityId entity, String value, String view) {}

  /** What the stored fact says (see {@link Statement}). */
  public static Statement statement(Fact fact) {
    if (isEntity(fact)) {
      return new Statement(fact.subject(), fact.predicate(), object(fact), null, view(fact));
    }
    return new Statement(fact.subject(), fact.predicate(), null, fact.object().value(), view(fact));
  }

  /**
   * The facts with the entity as subject and the predicate, a value rather than an entity as
   * object, by fact ID.
   */
  public List<Fact> attributes(EntityId subject, String predicate) {
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
      Fact fact, Fact claim, List<Fact> contradictedBy, String stated, List<String> found) {}

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
