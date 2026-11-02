package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Item;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.PacketRole;
import io.lantern.harness.PromptLayout;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.checks.SupportJudge;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.Refs;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.JsonSchema;
import io.lantern.llm.Role;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Describe role on the harness (knowledge-build epic, section 11.4): a responsibility sentence
 * for every deployable unit, component, data store, destination, external system and person, and an
 * intent phrase for every interaction, each citing the inputs it rests on and stored as a claim of
 * origin {@code llm}. Its two prompts, block 2 of every request, are the epic's texts and answer
 * schemas, read from {@code prompts/describe-*}; block 1 is the common system prompt.
 *
 * <p>A unit item shows the facts that make it a unit, its README claims, its components {@code
 * [C..]} with their facts and the start of their main type's code, its interactions {@code [R..]}
 * with other containers, between its components and with the people who use it, and the summaries
 * of its code. An element item shows the element's facts, the tables built in it, and how the units
 * use it, with their responsibilities as earlier answers {@code [A..]}.
 */
final class DescribeRole {

  /** The prompt version of the unit role. */
  static final String UNIT_VERSION = "5";

  /** The prompt version of the element role. */
  static final String ELEMENT_VERSION = "4";

  /** The most words of a responsibility sentence. */
  static final int SENTENCE_WORDS = 25;

  /** The most words of an intent phrase. */
  static final int PHRASE_WORDS = 8;

  /** The most components of one unit item. */
  static final int MAX_COMPONENTS = 25;

  /** The most interactions of one unit item. */
  static final int MAX_INTERACTIONS = 60;

  /** The most elements of one kind a request holds, a starting size to calibrate. */
  static final int ELEMENT_PACKET = 10;

  static final String UNIT = "unit";
  static final String COMPONENTS = "components";
  static final String INTERACTIONS = "interactions";
  static final String RESPONSIBILITY = "responsibility";

  /** Block 2 of a unit request. */
  static final String UNIT_TASK = prompt("describe-units.task.txt");

  /** The answer schema of a unit request. */
  static final String UNIT_SCHEMA = prompt("describe-units.schema.json");

  /** Block 2 of an element request. */
  static final String ELEMENT_TASK = prompt("describe-elements.task.txt");

  /** The answer schema of an element request. */
  static final String ELEMENT_SCHEMA = prompt("describe-elements.schema.json");

  /** What separates a role's instructions from its answer schema in block 2. */
  static final String SCHEMA_HEADING = "\n\nAnswer schema:\n";

  /** The role that words units, their components and interactions. */
  static final RoleSpec UNITS =
      RoleSpec.single(
          "describe-unit", Role.INFER_INTENT, UNIT_VERSION, "describe", RunMode.BULK, 1500);

  /** The role that words data stores, destinations, external systems and people. */
  static final RoleSpec ELEMENTS =
      RoleSpec.single(
              "describe-element",
              Role.DESCRIBE_ELEMENTS,
              ELEMENT_VERSION,
              "describe",
              RunMode.BULK,
              120)
          .withPacketSize(ELEMENT_PACKET);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What a unit item's texts may cite, as its task says: facts, code excerpts and summaries. */
  private static final Set<Character> UNIT_CITABLE = Set.of('F', 'E', 'S');

  /** What an element item's text may cite, as its task says: facts and earlier answers. */
  private static final Set<Character> ELEMENT_CITABLE = Set.of('F', 'A');

  /** The output room of a request, at least. */
  private static final int MIN_OUTPUT_TOKENS = 1_000;

  /** The output room of an element's sentence with its citations. */
  private static final int ELEMENT_OUTPUT_TOKENS = 200;

  /** The output room of a unit's or a component's sentence with its citations. */
  private static final int SENTENCE_OUTPUT_TOKENS = 150;

  /** The output room of an interaction's phrase with its citations. */
  private static final int PHRASE_OUTPUT_TOKENS = 90;

  private static final JsonNode UNIT_ITEM_SCHEMA =
      JsonSchema.itemSchema(JSON.readTree(UNIT_SCHEMA));
  private static final JsonNode ELEMENT_ITEM_SCHEMA =
      JsonSchema.itemSchema(JSON.readTree(ELEMENT_SCHEMA));

  private DescribeRole() {}

  /** Whether an item is about a unit or an element. */
  enum Kind {
    UNIT,
    ELEMENT
  }

  /**
   * What an item's references stand for, beside its harness item.
   *
   * @param kind whether it is a unit item or an element item
   * @param element the unit or element it is about
   * @param name the element's name, which its responsibility must not restate
   * @param asksUnit whether it asks for the unit's own sentence: a unit split into several items
   *     asks it in the first only
   * @param refs its references
   * @param components each component's entity, by its key {@code C..}
   * @param componentMains each component's main type, by its key
   * @param componentNames each component's name, by its key
   * @param interactions each interaction's fact, by its key {@code R..}
   * @param sources each interaction's source, by its key
   * @param interactionLines each interaction as the item shows it, by its key
   * @param evidenced the item-local references to what people wrote: README claims, code with
   *     Javadoc, evidenced summaries
   * @param item the harness item
   */
  record Described(
      Kind kind,
      EntityId element,
      String name,
      boolean asksUnit,
      Refs refs,
      Map<String, EntityId> components,
      Map<String, EntityId> componentMains,
      Map<String, String> componentNames,
      Map<String, Fact> interactions,
      Map<String, EntityId> sources,
      Map<String, String> interactionLines,
      Set<String> evidenced,
      Item item) {}

  /** The packet layout of the unit role. */
  static PacketRole units(Map<String, Described> bySubject) {
    return new Packets(bySubject, UNIT_TASK + SCHEMA_HEADING + UNIT_SCHEMA, UNIT_ITEM_SCHEMA);
  }

  /** The packet layout of the element role. */
  static PacketRole elements(Map<String, Described> bySubject) {
    return new Packets(
        bySubject, ELEMENT_TASK + SCHEMA_HEADING + ELEMENT_SCHEMA, ELEMENT_ITEM_SCHEMA);
  }

  /**
   * Lays out a packet's request, reads each item's entry into the checks' form, and stores an
   * accepted entry with its citations as the item numbers them, so the stored answer does not
   * depend on which items shared its request.
   */
  private static final class Packets implements PacketRole {

    private final Map<String, Described> bySubject;
    private final String role;
    private final JsonNode schema;
    // Each item's request references to its own, from the layout of the request it is in.
    private final Map<String, Map<String, String>> toLocal = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> shown = new ConcurrentHashMap<>();

    Packets(Map<String, Described> bySubject, String role, JsonNode schema) {
      this.bySubject = bySubject;
      this.role = role;
      this.schema = schema;
    }

    private Described described(Item item) {
      Described described = bySubject.get(item.subject());
      if (described == null) {
        throw new IllegalStateException("No Describe item for " + item.subject());
      }
      return described;
    }

    private Refs.Resolved resolve(List<Item> packet) {
      List<String> renderings = new ArrayList<>();
      List<Refs> refs = new ArrayList<>();
      for (Item item : packet) {
        renderings.add(described(item).item().rendering());
        refs.add(described(item).refs());
      }
      Refs.Resolved resolved = Refs.resolve(renderings, refs);
      for (int i = 0; i < packet.size(); i++) {
        toLocal.put(packet.get(i).subject(), resolved.toLocal().get(i));
        shown.put(packet.get(i).subject(), resolved.global().get(i));
      }
      return resolved;
    }

    @Override
    public PromptLayout layout(List<Item> packet, List<String> ids) {
      Refs.Resolved resolved = resolve(packet);
      return new PromptLayout(
          io.lantern.harness.checks.CommonPrompt.SYSTEM,
          role,
          "",
          "",
          false,
          Blocks.items(ids, resolved.texts()));
    }

    @Override
    public int maxOutputTokens(int items) {
      return Math.max(MIN_OUTPUT_TOKENS, ELEMENT_OUTPUT_TOKENS * items);
    }

    /**
     * Room for every text the packet's items may get: a unit's sentence, one per component and one
     * per interaction, each with its citations, so the largest unit item is not cut off.
     */
    @Override
    public int maxOutputTokens(List<Item> packet) {
      int tokens = 0;
      for (Item item : packet) {
        Described described = described(item);
        tokens +=
            described.kind() == Kind.ELEMENT
                ? ELEMENT_OUTPUT_TOKENS
                : SENTENCE_OUTPUT_TOKENS * (1 + described.components().size())
                    + PHRASE_OUTPUT_TOKENS * described.interactions().size();
      }
      return Math.max(MIN_OUTPUT_TOKENS, tokens);
    }

    @Override
    public CheckInputs inputs(List<Item> packet, List<String> ids) {
      Refs.Resolved resolved = resolve(packet);
      return inputs(resolved.references(), described(packet.getFirst()).kind());
    }

    /** Each item is checked against its own references only, as the request numbers them. */
    @Override
    public CheckInputs inputs(List<Item> packet, List<String> ids, int index) {
      Refs.Resolved resolved = resolve(packet);
      return inputs(resolved.global().get(index), described(packet.get(index)).kind());
    }

    private CheckInputs inputs(Map<String, String> references, Kind kind) {
      return new CheckInputs(
          schema,
          references,
          kind == Kind.ELEMENT ? ELEMENT_CITABLE : UNIT_CITABLE,
          false,
          (repo, path) -> Optional.empty(),
          Map.of(),
          Set.of());
    }

    @Override
    public ItemAnswer read(String id, Item item, JsonNode entry) {
      Described described = described(item);
      List<CheckedText> texts = new ArrayList<>();
      if (entry != null && entry.isObject() && !entry.has("omitted")) {
        if (described.kind() == Kind.ELEMENT) {
          text(entry.get(RESPONSIBILITY))
              .ifPresent(
                  t ->
                      texts.add(
                          checked(
                              RESPONSIBILITY,
                              CheckedText.Kind.RESPONSIBILITY,
                              t,
                              SENTENCE_WORDS,
                              described.name())));
        } else {
          if (described.asksUnit()) {
            text(entry.get(UNIT))
                .ifPresent(
                    t ->
                        texts.add(
                            checked(
                                UNIT,
                                CheckedText.Kind.RESPONSIBILITY,
                                t,
                                SENTENCE_WORDS,
                                described.name())));
          }
          fields(entry.get(COMPONENTS))
              .forEach(
                  (key, value) -> {
                    if (described.components().containsKey(key)) {
                      text(value)
                          .ifPresent(
                              t ->
                                  texts.add(
                                      checked(
                                          COMPONENTS + "." + key,
                                          CheckedText.Kind.RESPONSIBILITY,
                                          t,
                                          SENTENCE_WORDS,
                                          described.componentNames().get(key))));
                    }
                  });
          fields(entry.get(INTERACTIONS))
              .forEach(
                  (key, value) -> {
                    if (described.interactions().containsKey(key)) {
                      text(value)
                          .ifPresent(
                              t ->
                                  texts.add(
                                      checked(
                                          INTERACTIONS + "." + key,
                                          CheckedText.Kind.INTENT,
                                          t,
                                          PHRASE_WORDS,
                                          described.interactionLines().get(key))));
                    }
                  });
        }
      }
      return new ItemAnswer(id, entry, texts, List.of());
    }

    @Override
    public String store(Item item, ItemAnswer accepted) {
      Map<String, String> back = toLocal.getOrDefault(item.subject(), Map.of());
      ObjectNode stored = JSON.createObjectNode();
      for (CheckedText text : accepted.texts()) {
        ObjectNode value = JSON.createObjectNode();
        value.put("text", text.text());
        var cites = value.putArray("cites");
        text.cites().forEach(c -> cites.add(back.getOrDefault(c, c)));
        String[] path = text.field().split("\\.", 2);
        if (path.length == 1) {
          stored.set(path[0], value);
        } else {
          ObjectNode group =
              stored.has(path[0]) ? (ObjectNode) stored.get(path[0]) : stored.putObject(path[0]);
          group.set(path[1], value);
        }
      }
      return stored.toString();
    }
  }

  private static CheckedText checked(
      String field, CheckedText.Kind kind, Text text, int words, String subject) {
    return new CheckedText(field, kind, text.text(), text.cites(), words, subject, Set.of());
  }

  /** A text of an answer: what it says and what it cites. */
  record Text(String text, List<String> cites) {}

  private static Optional<Text> text(JsonNode node) {
    if (node == null || !node.isObject() || !node.path("text").isString()) {
      return Optional.empty();
    }
    List<String> cites = new ArrayList<>();
    node.path("cites").forEach(c -> cites.add(c.asString("")));
    return Optional.of(new Text(node.get("text").asString(), cites));
  }

  private static Map<String, JsonNode> fields(JsonNode node) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    if (node != null && node.isObject()) {
      node.properties().forEach(e -> fields.put(e.getKey(), e.getValue()));
    }
    return fields;
  }

  // ---------------------------------------------------------------------------------------------
  // Answers

  /**
   * What one stored answer says, with its citations resolved to what the item showed.
   *
   * @param about what the text is about: the unit, a component's entity, an element, or an
   *     interaction's source
   * @param field the answer's field, such as {@code components.C2}
   * @param text the text
   * @param cited the facts it cites, directly or through the summaries and earlier answers it cites
   * @param shown each citation as the item showed it, for the support judge
   * @param basis whether it rests on what people wrote
   */
  record Worded(
      EntityId about,
      String field,
      String text,
      List<Fact> cited,
      List<String> shown,
      LlmFacts.Basis basis) {}

  /**
   * The texts of a stored answer, each with what it cites; texts citing nothing known are left out.
   */
  static List<Worded> worded(Described described, String stored, SummaryFacts summaries) {
    JsonNode answer = JSON.readTree(stored);
    List<Worded> result = new ArrayList<>();
    if (described.kind() == Kind.ELEMENT) {
      text(answer.get(RESPONSIBILITY))
          .flatMap(t -> worded(described, described.element(), RESPONSIBILITY, t, summaries))
          .ifPresent(result::add);
      return result;
    }
    if (described.asksUnit()) {
      text(answer.get(UNIT))
          .flatMap(t -> worded(described, described.element(), UNIT, t, summaries))
          .ifPresent(result::add);
    }
    fields(answer.get(COMPONENTS))
        .forEach(
            (key, value) -> {
              EntityId component = described.components().get(key);
              if (component != null) {
                text(value)
                    .flatMap(
                        t -> worded(described, component, COMPONENTS + "." + key, t, summaries))
                    .ifPresent(result::add);
              }
            });
    fields(answer.get(INTERACTIONS))
        .forEach(
            (key, value) -> {
              EntityId source = described.sources().get(key);
              if (source != null) {
                text(value)
                    .flatMap(t -> worded(described, source, INTERACTIONS + "." + key, t, summaries))
                    .ifPresent(result::add);
              }
            });
    return result;
  }

  private static Optional<Worded> worded(
      Described described, EntityId about, String field, Text text, SummaryFacts summaries) {
    Set<Fact> cited = new LinkedHashSet<>();
    List<String> shown = new ArrayList<>();
    boolean evidenced = false;
    Map<String, String> lines = described.refs().lines();
    for (String cite : text.cites()) {
      Object thing = described.refs().thing(cite).orElse(null);
      if (thing == null) {
        continue;
      }
      String line = lines.get(cite);
      if (line != null) {
        shown.add("[" + cite + "] " + line);
      }
      evidenced |= described.evidenced().contains(cite);
      switch (thing) {
        case Fact fact -> cited.add(fact);
        case SummaryFacts.Cited summary -> cited.addAll(summaries.facts(summary));
        case Answered answered -> cited.addAll(answered.cited());
        default -> {}
      }
    }
    if (cited.isEmpty()) {
      return Optional.empty();
    }
    String worded = text.text().strip();
    // An intent phrase labels a line, so it ends without a full stop.
    if (field.startsWith(INTERACTIONS + ".") && worded.endsWith(".")) {
      worded = worded.substring(0, worded.length() - 1).strip();
    }
    return Optional.of(
        new Worded(
            about,
            field,
            worded,
            List.copyOf(cited),
            shown,
            evidenced ? LlmFacts.Basis.EVIDENCED : LlmFacts.Basis.INFERRED));
  }

  /** An earlier answer an element item shows, {@code [A..]}: a unit's responsibility. */
  record Answered(String text, List<Fact> cited) {}

  /**
   * The summaries a unit item shows, and the facts each rests on, so a text that cites a summary
   * cites what the summary cites.
   */
  interface SummaryFacts {

    /** A summary as an item's reference stands for it. */
    record Cited(String key, EntityId entity) {}

    /** The facts the summary cites. */
    List<Fact> facts(Cited summary);

    /** No summaries. */
    SummaryFacts NONE = summary -> List.of();
  }

  /** The fact of a worded text: a responsibility or an intent. */
  static Fact fact(Described described, Worded worded, RoleSpec role, String model, long snapshot) {
    boolean intent = worded.field().startsWith(INTERACTIONS + ".");
    String object;
    if (intent) {
      Fact interaction =
          described.interactions().get(worded.field().substring(INTERACTIONS.length() + 1));
      object = interaction.claimId() != null ? interaction.claimId() : interaction.id();
      // A person's use of a frontend rests on the person's one fact, shared by every frontend
      // they use: the frontend tells their uses apart.
      if (worded.about().type().equals(C4Views.PERSON)) {
        object = object + "/" + described.element();
      }
    } else {
      object = LlmFacts.C4;
    }
    return LlmFacts.text(
        role,
        model,
        worded.about(),
        intent ? LlmFacts.INTENT : LlmFacts.RESPONSIBILITY,
        object,
        worded.text(),
        worded.cited(),
        worded.basis(),
        snapshot);
  }

  /** A worded text as the support judge sees it (ADR 0101). */
  static SupportJudge.Text judged(String key, Worded worded) {
    return new SupportJudge.Text(key, worded.text(), worded.shown());
  }

  /** Reads a prompt resource as written. */
  static String prompt(String name) {
    String resource = "/prompts/" + name;
    try (InputStream in = DescribeRole.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + resource, e);
    }
  }

  /** A fingerprint input of a fact. */
  static FingerprintInput input(Fact fact) {
    return FingerprintInput.of(Objects.requireNonNull(fact, "fact"));
  }
}
