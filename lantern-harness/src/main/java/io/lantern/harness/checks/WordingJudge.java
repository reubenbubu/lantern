package io.lantern.harness.checks;

import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Fingerprints;
import io.lantern.harness.Harness;
import io.lantern.harness.Item;
import io.lantern.harness.Outcome;
import io.lantern.harness.PacketRole;
import io.lantern.harness.PromptLayout;
import io.lantern.harness.Prompts;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.llm.Role;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wording judge (ADR 0111; {@code docs/research/role-measurement.md}, Judge): a model other
 * than the writers' grades each text the roles wrote on one dimension per call, pointwise, as pass,
 * fail or unknown, reasoning before its verdict. It measures; it never changes a text or a
 * document, and its score counts only once the owner's labels validate it.
 */
public final class WordingJudge {

  /** The most words of a text it judges; a longer one is counted apart, never judged. */
  public static final int MAX_WORDS = 60;

  /** What a text is judged on, one dimension a call. */
  public enum Dimension {
    /** Whether it says what the element does for the system, not its name, kind or technology. */
    ROLE("role"),
    /** Whether it says something of this system, not what is true of any Spring application. */
    SPECIFIC("specific");

    private final String key;
    private final String task;
    private final RoleSpec role;

    Dimension(String key) {
      this.key = key;
      this.task = Prompts.text("wording-judge-" + key + ".task.txt");
      this.role =
          RoleSpec.single(
                  "wording-judge-" + key,
                  Role.WORDING_JUDGE,
                  Prompts.version(
                      "2",
                      "common.system.txt",
                      "wording-judge-" + key + ".task.txt",
                      "wording-judge.schema.json"),
                  "model-checks",
                  RunMode.BULK,
                  120)
              .withPacketSize(10);
    }

    /** Its name in files and in the scorecard: {@code role} or {@code specific}. */
    public String key() {
      return key;
    }

    /** Block 2 of its requests, before the answer schema. */
    public String task() {
      return task;
    }

    /** Its role on the harness, one per dimension, so each call grades one dimension. */
    public RoleSpec role() {
      return role;
    }
  }

  static final String ITEM_SCHEMA = Prompts.text("wording-judge.schema.json");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode SCHEMA = JSON.readTree(ITEM_SCHEMA);

  /**
   * A text to judge. Who wrote it is not shown: authorship stays hidden from the judge.
   *
   * @param key what the text is, unique among the texts judged together
   * @param kind what kind of text it is, such as "responsibility of a service"
   * @param about the element it is about, such as {@code service:orders}
   * @param text the text as the role wrote it, without citation markers
   */
  public record Text(String key, String kind, String about, String text) {

    public Text {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(about, "about");
      Objects.requireNonNull(text, "text");
    }

    /** How many words it has. */
    public int words() {
      return WordingJudge.words(text);
    }
  }

  /** The judge's verdict on one text and dimension, with its reasoning. */
  public record Verdict(String verdict, String reasoning) {}

  private final Harness harness;

  public WordingJudge(Harness harness) {
    this.harness = Objects.requireNonNull(harness, "harness");
  }

  /** How many words a text has. */
  static int words(String text) {
    String trimmed = text.strip();
    return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
  }

  /** The judge item of a text: its kind, what it is about and the text are all it rests on. */
  static Item item(Text text) {
    List<FingerprintInput> inputs =
        List.of(
            FingerprintInput.answer(Fingerprints.sha256(text.text())),
            FingerprintInput.text(text.kind()),
            FingerprintInput.text(text.about()));
    String rendering =
        "<kind>"
            + text.kind()
            + "</kind>\n<about>"
            + text.about()
            + "</about>\n<text>"
            + text.text()
            + "</text>";
    return new Item(text.key(), "wording", inputs, rendering);
  }

  /**
   * Judges each text of at most {@link #MAX_WORDS} words on the dimension.
   *
   * @return the verdict of each text judged, by key; a longer text, or one the judge left out (not
   *     asked under the budget, failed, dropped), has none
   */
  public Map<String, Verdict> judge(Dimension dimension, List<Text> texts) {
    List<Item> items = new ArrayList<>();
    for (Text text : texts) {
      if (text.words() > 0 && text.words() <= MAX_WORDS) {
        items.add(item(text));
      }
    }
    Map<String, Verdict> verdicts = new LinkedHashMap<>();
    if (items.isEmpty()) {
      return verdicts;
    }
    RoleSession session = harness.session(dimension.role());
    session.plan(items, io.lantern.llm.Tokens.estimate(CommonPrompt.SYSTEM + dimension.task()));
    Map<String, Outcome> outcomes = session.askPackets(items, packets(dimension));
    outcomes.forEach((key, outcome) -> verdict(outcome).ifPresent(v -> verdicts.put(key, v)));
    return verdicts;
  }

  /** The verdict of an answered item, if it was answered. */
  static Optional<Verdict> verdict(Outcome outcome) {
    if (outcome == null || outcome.answer() == null) {
      return Optional.empty();
    }
    JsonNode answer = JSON.readTree(outcome.answer());
    String verdict = answer.path("verdict").asString("").toLowerCase(Locale.ROOT);
    if (!Set.of("pass", "fail", "unknown").contains(verdict)) {
      return Optional.empty();
    }
    return Optional.of(new Verdict(verdict, answer.path("reasoning").asString("")));
  }

  /** The packets of one dimension: up to 10 items, one dimension's task. */
  static PacketRole packets(Dimension dimension) {
    return new PacketRole() {
      @Override
      public PromptLayout layout(List<Item> packet, List<String> ids) {
        StringBuilder items = new StringBuilder("<items>\n");
        for (int i = 0; i < packet.size(); i++) {
          items
              .append("<item id=\"")
              .append(ids.get(i))
              .append("\">\n")
              .append(packet.get(i).rendering())
              .append("\n</item>\n");
        }
        return new PromptLayout(
            CommonPrompt.SYSTEM,
            dimension.task()
                + "\n\nSchema of each item's answer:\n"
                + CommonPrompt.envelope(ITEM_SCHEMA),
            "",
            "",
            false,
            items.append("</items>").toString());
      }

      @Override
      public int maxOutputTokens(int items) {
        return 200 * items;
      }

      @Override
      public CheckInputs inputs(List<Item> packet, List<String> ids) {
        return new CheckInputs(
            SCHEMA,
            Map.of(),
            Set.of(),
            false,
            (repo, path) -> Optional.empty(),
            Map.of(),
            Set.of());
      }

      @Override
      public ItemAnswer read(String id, Item item, JsonNode entry) {
        return new ItemAnswer(id, entry, List.of(), List.of());
      }

      @Override
      public String store(Item item, ItemAnswer accepted) {
        return accepted.raw().toString();
      }
    };
  }
}
