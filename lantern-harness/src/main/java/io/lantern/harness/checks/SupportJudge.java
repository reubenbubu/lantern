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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The support judge (knowledge-build epic, section 11.7; ADR 0101): a model on the high-volume tier
 * splits an accepted text into atomic claims and says whether the inputs the text cites support
 * each. It checks a deterministic 10% sample, the texts whose judge item's fingerprint read as a
 * number is divisible by 10, plus any text it is told to check regardless. It is never a gate: a
 * verdict only marks a text, it never drops one.
 */
public final class SupportJudge {

  /** The judge's role: the high-volume tier, another tier than the reasoning-tier Describe. */
  public static final RoleSpec ROLE =
      RoleSpec.single(
              "support-judge",
              Role.JUDGE,
              Prompts.version(
                  "2", "common.system.txt", "support-judge.task.txt", "support-judge.schema.json"),
              "model-checks",
              RunMode.BULK,
              300)
          .withPacketSize(10)
          .withThinking(650);

  static final String TASK = Prompts.text("support-judge.task.txt");

  static final String ITEM_SCHEMA = Prompts.text("support-judge.schema.json");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode SCHEMA = JSON.readTree(ITEM_SCHEMA);

  /** How well a text's cited inputs support it. */
  public enum Support {
    /** Every claim is supported. */
    SUPPORTED,
    /** No claim is unsupported, but some are only partly supported. */
    PARTLY,
    /** At least one claim is supported by none of the cited inputs. */
    UNSUPPORTED;

    /** How much the text's confidence is multiplied by. */
    public double factor() {
      return switch (this) {
        case SUPPORTED -> 1.0;
        case PARTLY -> 0.8;
        case UNSUPPORTED -> 0.5;
      };
    }
  }

  /**
   * A text to judge.
   *
   * @param key what the text is about, unique among the texts judged together
   * @param text the text as the model wrote it, without citation markers
   * @param cited the inputs it cites, each as its request showed it, such as {@code [F3] ...}
   */
  public record Text(String key, String text, List<String> cited) {

    public Text {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(text, "text");
      cited = List.copyOf(cited);
    }
  }

  private final Harness harness;

  public SupportJudge(Harness harness) {
    this.harness = Objects.requireNonNull(harness, "harness");
  }

  /** The judge item of a text: the text and its cited inputs are all it rests on. */
  static Item item(Text text) {
    List<FingerprintInput> inputs = new ArrayList<>();
    inputs.add(FingerprintInput.answer(Fingerprints.sha256(text.text())));
    text.cited().forEach(line -> inputs.add(FingerprintInput.text(line)));
    StringBuilder rendering = new StringBuilder("<text>").append(text.text()).append("</text>\n");
    rendering.append("<inputs>\n");
    text.cited().forEach(line -> rendering.append(line).append('\n'));
    rendering.append("</inputs>");
    return new Item(text.key(), "judge", inputs, rendering.toString());
  }

  /**
   * What the sample reads of a judge item: a hash of the text and the inputs it cites, the same
   * whichever model judges, so every judge model checks the same texts (#751).
   */
  static String sampleKey(Item item) {
    return Fingerprints.sha256("support-judge sample\n" + item.rendering());
  }

  /** Whether the judge item's fingerprint, read as a number, is divisible by 10. */
  static boolean sampled(String fingerprint) {
    return new BigInteger(fingerprint, 16).mod(BigInteger.TEN).signum() == 0;
  }

  /**
   * Judges the sampled texts and those whose key is in {@code always}.
   *
   * @return the verdict of each text judged, by key; a text not sampled, or the judge left out, has
   *     none
   */
  public Map<String, Support> judge(List<Text> texts, Set<String> always) {
    return judge(Map.of("", texts), always);
  }

  /**
   * Judges the sampled texts of each group and those whose key is in {@code always}, each group in
   * requests of its own: at most 10 items of one role a request (knowledge-build epic, section
   * 11.7).
   *
   * @param byGroup the texts by the role that wrote them; keys unique across groups
   * @return the verdict of each text judged, by key
   */
  public Map<String, Support> judge(Map<String, List<Text>> byGroup, Set<String> always) {
    RoleSession session = harness.session(ROLE);
    Map<String, List<Item>> items = new java.util.TreeMap<>();
    List<Item> all = new ArrayList<>();
    byGroup.forEach(
        (group, texts) -> {
          for (Text text : texts) {
            Item item = item(text);
            if (!text.cited().isEmpty()
                && (always.contains(text.key()) || sampled(sampleKey(item)))) {
              items.computeIfAbsent(group, g -> new ArrayList<>()).add(item);
              all.add(item);
            }
          }
        });
    Map<String, Support> verdicts = new LinkedHashMap<>();
    if (all.isEmpty()) {
      return verdicts;
    }
    session.plan(all, io.lantern.llm.Tokens.estimate(CommonPrompt.SYSTEM + TASK));
    for (List<Item> group : items.values()) {
      Map<String, Outcome> outcomes = session.askPackets(group, PACKETS);
      outcomes.forEach(
          (key, outcome) -> support(outcome).ifPresent(support -> verdicts.put(key, support)));
    }
    return verdicts;
  }

  /** The support an answered item's claims add up to, if it was answered. */
  static Optional<Support> support(Outcome outcome) {
    if (outcome == null || outcome.answer() == null) {
      return Optional.empty();
    }
    JsonNode claims = JSON.readTree(outcome.answer()).path("claims");
    if (!claims.isArray() || claims.isEmpty()) {
      return Optional.empty();
    }
    boolean partly = false;
    for (JsonNode claim : claims) {
      String verdict = claim.path("verdict").asString("");
      if (verdict.equals("unsupported")) {
        return Optional.of(Support.UNSUPPORTED);
      }
      partly |= verdict.equals("partly");
    }
    return Optional.of(partly ? Support.PARTLY : Support.SUPPORTED);
  }

  private static final PacketRole PACKETS =
      new PacketRole() {
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
              TASK + "\n\nSchema of each item's answer:\n" + CommonPrompt.envelope(ITEM_SCHEMA),
              "",
              "",
              false,
              items.append("</items>").toString());
        }

        @Override
        public int maxOutputTokens(int items) {
          return 400 * items;
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
