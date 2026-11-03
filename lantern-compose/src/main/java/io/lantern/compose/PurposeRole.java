package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Validity;
import io.lantern.derive.attributes.Types;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Fingerprints;
import io.lantern.harness.Item;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.Outcome;
import io.lantern.harness.PacketRole;
import io.lantern.harness.PromptLayout;
import io.lantern.harness.Prompts;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import io.lantern.harness.render.Refs;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.JsonSchema;
import io.lantern.harness.verify.Pipeline;
import io.lantern.llm.Role;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The purpose and key-fact role (knowledge-build epic, section 11.6; ADR 0109): one item for the
 * system, showing what Describe wrote of its units and people, its external systems, the titles of
 * its main flows and its READMEs' claims, answered with the system's purpose and the single fact
 * about how it is built a new reader most needs. Both are stored as claims of the system.
 */
final class PurposeRole {

  /** The field of the answer that holds the purpose. */
  static final String PURPOSE_FIELD = "purpose";

  /** The prompt version; change it whenever the role's prompt or what an item shows changes. */
  static final String VERSION = "4";

  /** The most words of the purpose and of the key fact. */
  static final int PURPOSE_WORDS = 25;

  static final int KEY_FACT_WORDS = 30;

  static final String TASK = DescribeRole.prompt("purpose.task.txt");
  static final String SCHEMA = DescribeRole.prompt("purpose.schema.json");

  static final String KEY_FACT = "keyFact";

  /** The role: one call, on the reasoning tier, in the group it shares with the flows. */
  static final RoleSpec ROLE =
      RoleSpec.single(
          "purpose",
          Role.INFER_INTENT,
          Prompts.version(VERSION, "common.system.txt", "purpose.task.txt", "purpose.schema.json"),
          "flows-and-purpose",
          RunMode.INTERACTIVE,
          500);

  /** The README claims an item shows of each repository, at most. */
  static final int MAX_CLAIMS = 8;

  /** The facts an item shows under one sentence, at most. */
  static final int MAX_CITED = 8;

  /** The units and people an item shows, at most. */
  static final int MAX_SENTENCES = 30;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode ITEM_SCHEMA = JsonSchema.itemSchema(JSON.readTree(SCHEMA));
  private static final Set<Character> CITABLE = Set.of('F', 'A');

  private PurposeRole() {}

  /**
   * A sentence Describe wrote, with what it is about.
   *
   * @param about the element it is about, which keys its reference
   * @param name the element's name, as documents give it
   * @param answered the sentence and the facts it cites
   */
  record Said(EntityId about, String name, DescribeRole.Answered answered) {}

  /** An item with what its references stand for. */
  record Asked(Item item, Refs refs) {}

  /**
   * The system's item, or none when it would show no sentence and no README claim, so the purpose
   * is stated as unknown without a call.
   *
   * @param units the units' responsibilities, by name
   * @param people the people's responsibilities, by name
   * @param externals the external systems by name, each with the fact that shows it
   * @param flows the titles of the system's main flows, in the order drawn
   * @param claims each repository's first README claims, by repository and README path
   */
  static Optional<Asked> item(
      List<Said> units,
      List<Said> people,
      Map<String, Fact> externals,
      List<String> flows,
      Map<String, List<Fact>> claims) {
    if (units.isEmpty() && people.isEmpty() && claims.isEmpty()) {
      return Optional.empty();
    }
    Refs refs = new Refs();
    List<FingerprintInput> inputs = new ArrayList<>();
    StringBuilder text = new StringBuilder("The software system.\n");
    said(text, refs, inputs, "Its deployable units, each with what it does:", units);
    said(text, refs, inputs, "The people who use it, each with what they do:", people);
    if (!externals.isEmpty()) {
      text.append("The external systems it uses:\n");
      externals.forEach(
          (name, fact) -> {
            boolean shown = refs.shown('F', key(fact));
            String line = FactLines.line(fact);
            text.append("- ")
                .append(FactLines.oneLine(name))
                .append(": [")
                .append(refs.add('F', key(fact), fact, line))
                .append("] ")
                .append(shown ? "(as above)" : line)
                .append('\n');
            if (!shown) {
              inputs.add(DescribeRole.input(fact));
            }
          });
    }
    if (!flows.isEmpty()) {
      text.append("Its main flows:\n");
      flows.forEach(title -> text.append("- ").append(FactLines.oneLine(title)).append('\n'));
      inputs.add(FingerprintInput.answer(Fingerprints.sha256(String.join("\n", flows))));
    }
    if (!claims.isEmpty()) {
      text.append("What the READMEs of its repositories claim:\n");
      claims.forEach(
          (readme, facts) -> {
            text.append(FactLines.oneLine(readme)).append(":\n");
            for (Fact fact : facts.subList(0, Math.min(MAX_CLAIMS, facts.size()))) {
              boolean shown = refs.shown('F', key(fact));
              String line = FactLines.line(fact);
              text.append("  [")
                  .append(refs.add('F', key(fact), fact, line))
                  .append("] ")
                  .append(shown ? "(as above)" : line)
                  .append('\n');
              if (!shown) {
                inputs.add(DescribeRole.input(fact));
              }
            }
          });
    }
    Item item = new Item(RepoSections.SYSTEM.toString(), "", inputs, text.toString().strip());
    return Optional.of(new Asked(item, refs));
  }

  private static void said(
      StringBuilder text,
      Refs refs,
      List<FingerprintInput> inputs,
      String heading,
      List<Said> said) {
    if (said.isEmpty()) {
      return;
    }
    List<Said> sorted = new ArrayList<>(said);
    sorted.sort(
        java.util.Comparator.comparing(Said::name)
            .thenComparing(s -> s.about().toString())
            .thenComparing(s -> s.answered().text()));
    text.append(heading).append('\n');
    for (Said one : sorted.subList(0, Math.min(MAX_SENTENCES, sorted.size()))) {
      String sentence = FactLines.oneLine(one.answered().text());
      // The facts it cites, so the purpose and key fact can cite them too, at most MAX_CITED: a
      // citation of the sentence stands for those shown only.
      List<Fact> cited = FactLines.sorted(one.answered().cited());
      List<Fact> shownFacts = List.copyOf(cited.subList(0, Math.min(MAX_CITED, cited.size())));
      text.append("- ")
          .append(FactLines.oneLine(one.name()))
          .append(": [")
          .append(
              refs.add(
                  'A',
                  one.about().toString(),
                  new DescribeRole.Answered(one.answered().text(), shownFacts),
                  sentence))
          .append("] ")
          .append(sentence)
          .append('\n');
      inputs.add(FingerprintInput.answer(Fingerprints.sha256(sentence)));
      // One shown before is written by its reference alone.
      for (Fact fact : shownFacts) {
        boolean shown = refs.shown('F', key(fact));
        String line = FactLines.line(fact);
        text.append("  [")
            .append(refs.add('F', key(fact), fact, line))
            .append("] ")
            .append(shown ? "(as above)" : line)
            .append('\n');
        if (!shown) {
          inputs.add(DescribeRole.input(fact));
        }
      }
    }
  }

  private static String key(Fact fact) {
    return fact.claimId() != null ? fact.claimId() : fact.id();
  }

  /** Asks the item, laid out for its role, and returns its outcome. */
  static Outcome ask(RoleSession session, Asked asked) {
    return session
        .askPackets(List.of(asked.item()), new OneItem(asked))
        .get(asked.item().subject());
  }

  /** The layout, checks and storage of the system's item. */
  private static final class OneItem implements PacketRole {

    private final Asked asked;

    OneItem(Asked asked) {
      this.asked = asked;
    }

    @Override
    public PromptLayout layout(List<Item> packet, List<String> ids) {
      Refs.Resolved resolved =
          Refs.resolve(List.of(asked.item().rendering()), List.of(asked.refs()));
      return new PromptLayout(
          CommonPrompt.SYSTEM,
          TASK + DescribeRole.SCHEMA_HEADING + SCHEMA,
          "",
          "",
          false,
          Blocks.items(ids, resolved.texts()));
    }

    @Override
    public int maxOutputTokens(int items) {
      return 1_000;
    }

    @Override
    public CheckInputs inputs(List<Item> packet, List<String> ids) {
      return new CheckInputs(
          ITEM_SCHEMA,
          asked.refs().lines(),
          CITABLE,
          false,
          (repo, path) -> Optional.empty(),
          Map.of(),
          Set.of());
    }

    @Override
    public Pipeline pipeline() {
      return Pipeline.STANDARD;
    }

    @Override
    public ItemAnswer read(String id, Item item, JsonNode entry) {
      List<CheckedText> texts = new ArrayList<>();
      if (entry != null && entry.isObject() && !entry.has("omitted")) {
        for (String field : List.of(PURPOSE_FIELD, KEY_FACT)) {
          JsonNode node = entry.get(field);
          if (node != null && node.isObject() && node.path("text").isString()) {
            List<String> cites = new ArrayList<>();
            node.path("cites").forEach(c -> cites.add(FlowJudgements.cleanRef(c.asString())));
            texts.add(
                new CheckedText(
                    field,
                    CheckedText.Kind.PROSE,
                    node.path("text").stringValue().strip(),
                    cites,
                    field.equals(PURPOSE_FIELD) ? PURPOSE_WORDS : KEY_FACT_WORDS,
                    "",
                    Set.of()));
          }
        }
      }
      return new ItemAnswer(id, entry, texts, List.of());
    }

    /** The accepted texts as written, citations as the item numbers them. */
    @Override
    public String store(Item item, ItemAnswer accepted) {
      ObjectNode stored = JSON.createObjectNode();
      for (CheckedText text : accepted.texts()) {
        ObjectNode value = stored.putObject(text.field());
        value.put("text", text.text());
        ArrayNode cites = value.putArray("cites");
        text.cites().forEach(cites::add);
      }
      return stored.toString();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Reading answers back

  /**
   * A text of the answer: the sentence, the facts it cites (an earlier answer standing for the
   * facts it cites), and whether it rests on what people wrote, a README claim.
   */
  record Worded(String text, List<Fact> cited, boolean evidenced) {

    /** The sentence with the citation markers documents read, {@code [fact:<fact ID>]}. */
    String marked() {
      StringBuilder marked = new StringBuilder(text);
      cited.forEach(f -> marked.append(" [fact:").append(f.id()).append(']'));
      return marked.toString();
    }

    /** The cited facts' lines, as the support judge reads them. */
    List<String> lines() {
      return cited.stream().map(FactLines::line).toList();
    }
  }

  /** The purpose and the key fact of a stored answer, each if it has one that cites a fact. */
  static Map<String, Worded> worded(Asked asked, String stored) {
    JsonNode answer = JSON.readTree(stored);
    Map<String, Worded> worded = new LinkedHashMap<>();
    for (String field : List.of(PURPOSE_FIELD, KEY_FACT)) {
      JsonNode node = answer.get(field);
      if (node == null || !node.path("text").isString()) {
        continue;
      }
      List<Fact> facts = new ArrayList<>();
      boolean evidenced = false;
      for (JsonNode cite : node.path("cites")) {
        Optional<Object> thing = asked.refs().thing(cite.asString());
        if (thing.isPresent() && thing.get() instanceof Fact fact) {
          if (!facts.contains(fact)) {
            facts.add(fact);
          }
          evidenced |= fact.predicate().equals(Types.CLAIM);
        } else if (thing.isPresent() && thing.get() instanceof DescribeRole.Answered answered) {
          // A sentence stands for the facts it cites, a README claim among them too, as the
          // documents read it (ADR 0060).
          for (Fact fact : answered.cited()) {
            if (!facts.contains(fact)) {
              facts.add(fact);
            }
            evidenced |= fact.predicate().equals(Types.CLAIM);
          }
        }
      }
      if (!facts.isEmpty()) {
        worded.put(field, new Worded(node.path("text").stringValue(), facts, evidenced));
      }
    }
    return worded;
  }

  /** The claims of the system's purpose and key fact, of the model that wrote them. */
  static List<Fact> facts(Map<String, Worded> worded, String model, long snapshot) {
    List<Fact> facts = new ArrayList<>();
    worded.forEach(
        (field, text) -> {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("text", text.text());
          ArrayNode cites = attributes.putArray("cites");
          text.cited().forEach(f -> cites.add(f.id()));
          attributes.put(
              "basis",
              (text.evidenced() ? LlmFacts.Basis.EVIDENCED : LlmFacts.Basis.INFERRED).jsonName());
          attributes.put("model", model);
          Fact fact =
              Fact.create(
                  RepoSections.SYSTEM,
                  field.equals(PURPOSE_FIELD) ? LlmFacts.PURPOSE : LlmFacts.KEY_FACT,
                  FactObject.string(LlmFacts.C4),
                  attributes,
                  LlmFacts.origin(ROLE),
                  LlmFacts.evidence(text.cited()),
                  LlmFacts.CONFIDENCE,
                  Validity.from(snapshot));
          List<String> lineage =
              text.cited().stream()
                  .map(Fact::claimId)
                  .filter(java.util.Objects::nonNull)
                  .distinct()
                  .toList();
          facts.add(lineage.isEmpty() ? fact : fact.withLineage(lineage));
        });
    return facts;
  }
}
