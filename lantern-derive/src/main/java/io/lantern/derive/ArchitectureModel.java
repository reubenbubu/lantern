package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
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
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The architecture model of one snapshot as documents read it (knowledge-build epic, section 12;
 * {@code docs/design.md}, Composition, "Composition reads the model"): the stored facts, and the
 * texts the LLM roles stored about them, read apart.
 *
 * <p>It is read-only and built only from what is stored: the facts that hold in a snapshot, those
 * of origin llm included. The roles' texts (responsibilities, intents, summaries, group names, flow
 * judgements and narratives, purpose and key fact) are not among {@link #knowledge()}'s facts, so
 * what is drawn does not depend on whether they were written; they are read through the methods
 * below. Where one thing holds several texts, the one with the highest confidence is read, the
 * first by fact ID on a tie, so the same facts always give the same texts.
 */
public final class ArchitectureModel {

  /** The scope of the system's documents among the flows' significance facts. */
  public static final String SYSTEM_SCOPE = "system";

  /** The software system all repositories of a snapshot form, the system scope's subject. */
  public static final EntityId SYSTEM = new EntityId(BuiltInTypes.SYSTEM, "snapshot");

  /** The entity type of repository summaries, which have no entity in the fact store. */
  public static final String REPO_ENTITY_TYPE = "repo";

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
    for (Fact anchored : knowledge.withPredicate(Components.ANCHORED_ON)) {
      if (Knowledge.isEntity(anchored)) {
        anchors.putIfAbsent(anchored.subject(), Knowledge.object(anchored));
      }
    }
    List<Fact> componentTexts = new ArrayList<>();
    for (Fact text : texts) {
      switch (text.predicate()) {
        case LlmFacts.RESPONSIBILITY -> {
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
              if (fact != null && fact.predicate().equals("kind")) {
                documented.add(cited);
              }
            }
          }
        }
        case LlmFacts.INTENT -> {
          // A group's intent is keyed by its other end and protocol, which another group's may
          // share (ADR 0107): it is kept by the group as well.
          if (text.subject().type().equals(Groups.GROUP)) {
            groupIntents.putIfAbsent(text.subject() + " " + text.object().value(), text);
          } else {
            intents.putIfAbsent(text.object().value(), text);
          }
        }
        case LlmFacts.SUMMARY -> summaries.putIfAbsent(text.subject(), text);
        case LlmFacts.DISPLAY_NAME -> displayNames.putIfAbsent(text.subject(), text);
        case LlmFacts.FLOW_NARRATIVE ->
            narratives.computeIfAbsent(text.subject(), f -> new ArrayList<>()).add(text);
        case LlmFacts.FLOW_SIGNIFICANCE ->
            significance.computeIfAbsent(text.object().value(), s -> new ArrayList<>()).add(text);
        case LlmFacts.PURPOSE -> purposes.add(text);
        case LlmFacts.KEY_FACT -> keyFacts.add(text);
        default -> {}
      }
    }
    // A sentence about the element itself comes before one about a component anchored on it.
    for (Fact text : componentTexts) {
      responsibilities.putIfAbsent(anchors.get(text.subject()), text);
    }
  }

  /** The stored facts, without the roles' texts. */
  public Knowledge knowledge() {
    return knowledge;
  }

  /** The vocabulary, which says what each type is a kind of and how it is drawn. */
  public Vocabulary vocabulary() {
    return vocabulary;
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
        .filter(f -> !f.subject().type().equals("person"))
        .map(ArchitectureModel::text);
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
   * order, by flow ID; empty when it did not judge the scope.
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
