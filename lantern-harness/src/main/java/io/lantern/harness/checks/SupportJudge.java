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
import io.lantern.harness.verify.Check;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.Checks;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Pipeline;
import io.lantern.harness.verify.Rejection;
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
 * The support judge (knowledge-build epic, section 11.7; ADR 0101): the model of the role that
 * wrote a text, or the judge role's own, splits an accepted text into atomic claims and says
 * whether the inputs the text cites support each. It checks a deterministic 10% sample, the texts
 * whose judge item (the text and its cited inputs), hashed and read as a number, is divisible by
 * 10, plus any text it is told to check regardless. A verdict never drops a text from the store;
 * what the run does with a rejected one is {@code SupportChecks}' and the analyze run's (#751).
 */
public final class SupportJudge {

  /**
   * The judge's role, on the high-volume tier unless it judges a writer's texts on its model
   * (#751).
   */
  public static final RoleSpec ROLE =
      RoleSpec.single(
              "support-judge",
              Role.JUDGE,
              Prompts.version(
                  "4", "common.system.txt", "support-judge.task.txt", "support-judge.schema.json"),
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

  /**
   * Whether the hash of a judge item ({@link #sampleKey}), read as a number, is divisible by 10.
   */
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
    return judge(byGroup, always, Map.of());
  }

  /**
   * As {@link #judge(Map, Set)}, each group judged by the model of the role that wrote it: the
   * owner's choice on #751, a model checking its own texts with a strict prompt, so neither model
   * checks the other's (ADR 0101, amendment for #751).
   *
   * @param writers the model role of each group's writer; a group not named is judged by {@link
   *     Role#JUDGE}'s model
   */
  public Map<String, Support> judge(
      Map<String, List<Text>> byGroup, Set<String> always, Map<String, Role> writers) {
    Map<String, Support> supports = new LinkedHashMap<>();
    judged(byGroup, always, writers)
        .forEach((key, verdict) -> supports.put(key, verdict.support()));
    return supports;
  }

  /**
   * A text's verdict and the claims the judge found unsupported, which tell the text's role why its
   * answer is asked again (#751).
   *
   * @param support how well the cited inputs support the text
   * @param unsupported the claims no cited input shows, as the judge wrote them
   */
  public record Verdict(Support support, List<String> unsupported) {

    public Verdict {
      Objects.requireNonNull(support, "support");
      unsupported = List.copyOf(unsupported);
    }
  }

  /** As {@link #judge(Map, Set, Map)}, with the claims found unsupported. */
  public Map<String, Verdict> judged(
      Map<String, List<Text>> byGroup, Set<String> always, Map<String, Role> writers) {
    Map<String, List<Item>> items = new java.util.TreeMap<>();
    // A text that reads the same and cites the same inputs as one already taken is judged once,
    // its verdict given to each (#751).
    Map<String, List<String>> same = new LinkedHashMap<>();
    byGroup.forEach(
        (group, texts) -> {
          Map<String, String> firstByRendering = new LinkedHashMap<>();
          for (Text text : texts) {
            Item item = item(text);
            if (!text.cited().isEmpty()
                && (always.contains(text.key()) || sampled(sampleKey(item)))) {
              String first = firstByRendering.putIfAbsent(item.rendering(), text.key());
              if (first != null) {
                same.computeIfAbsent(first, k -> new ArrayList<>()).add(text.key());
                continue;
              }
              items.computeIfAbsent(group, g -> new ArrayList<>()).add(item);
            }
          }
        });
    Map<String, Verdict> verdicts = new LinkedHashMap<>();
    // One session for each model that judges, planned with all the items it asks.
    Map<Role, List<String>> groupsByModel = new java.util.EnumMap<>(Role.class);
    items
        .keySet()
        .forEach(
            group ->
                groupsByModel
                    .computeIfAbsent(
                        writers.getOrDefault(group, Role.JUDGE), r -> new ArrayList<>())
                    .add(group));
    groupsByModel.forEach(
        (model, groups) -> {
          RoleSession session = harness.session(on(model));
          session.plan(
              groups.stream().flatMap(g -> items.get(g).stream()).toList(),
              io.lantern.llm.Tokens.estimate(CommonPrompt.SYSTEM + TASK));
          for (String group : groups) {
            session
                .askPackets(items.get(group), PACKETS)
                .forEach(
                    (key, outcome) ->
                        support(outcome)
                            .ifPresent(
                                support ->
                                    verdicts.put(key, new Verdict(support, unsupported(outcome)))));
          }
        });
    same.forEach(
        (first, others) -> {
          Verdict verdict = verdicts.get(first);
          if (verdict != null) {
            others.forEach(key -> verdicts.put(key, verdict));
          }
        });
    return verdicts;
  }

  /**
   * The judge's role on the given model role's model: named {@code support-judge-<model role>},
   * since a run holds one session a role name, at the judge's packet size (#751).
   */
  static RoleSpec on(Role model) {
    return model == ROLE.modelRole()
        ? ROLE
        : new RoleSpec(
            ROLE.name() + "-" + model.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
            model,
            ROLE.promptVersion(),
            ROLE.budgetGroup(),
            ROLE.packetSize(),
            ROLE.runMode(),
            ROLE.expectedOutputTokens(),
            ROLE.expectedThinkingTokens(),
            ROLE.expectedTurns(),
            ROLE.expectedTurnTokens(),
            ROLE.tools(),
            ROLE.limits());
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

  /** The claims of an answered item the judge found unsupported, as it wrote them. */
  static List<String> unsupported(Outcome outcome) {
    List<String> claims = new ArrayList<>();
    for (JsonNode claim : JSON.readTree(outcome.answer()).path("claims")) {
      if (claim.path("verdict").asString("").equals("unsupported")) {
        claims.add(claim.path("claim").asString("").strip());
      }
    }
    return claims;
  }

  /** A fact's or an earlier answer's tag, as an item writes it: {@code F1}. */
  private static final java.util.regex.Pattern TAG =
      java.util.regex.Pattern.compile("[A-Za-z][0-9]+");

  /** A trailing line or line range of a file reference: {@code :3} or {@code :3-9}. */
  private static final java.util.regex.Pattern LINES =
      java.util.regex.Pattern.compile(":[0-9]+(-[0-9]+)?$");

  /**
   * The inputs a judge item cites, by the reference each is written with: the tag of a fact or an
   * earlier answer ({@code F1} for {@code [F1] ...}), or a file range as written. A line that
   * starts with neither, such as the next line of a cited excerpt, adds none.
   */
  static Map<String, String> citedInputs(Item item) {
    Map<String, String> cited = new LinkedHashMap<>();
    String rendering = item.rendering();
    int start = rendering.indexOf("<inputs>\n");
    int end = rendering.lastIndexOf("</inputs>");
    if (start < 0 || end < start) {
      return cited;
    }
    for (String line : rendering.substring(start + "<inputs>\n".length(), end).split("\n")) {
      String ref = reference(line.strip().split("\\s+", 2)[0]);
      if (TAG.matcher(ref).matches() || ref.contains(":")) {
        cited.putIfAbsent(ref, line);
      }
    }
    return cited;
  }

  /** A reference without its brackets and trailing punctuation. */
  static String reference(String token) {
    String ref = token.strip().replaceAll("^[\\[(]+|[\\]),;.]+$", "");
    int close = ref.indexOf(']');
    return close < 0 ? ref : ref.substring(0, close);
  }

  /**
   * A file reference without its lines: {@code orders:src/A.java} for {@code
   * orders:src/A.java:3-9}.
   */
  private static String file(String ref) {
    return LINES.matcher(ref).replaceFirst("");
  }

  /**
   * A claim found supported, or partly, rests on an input the item cites: a verdict whose "by"
   * names a fact, an answer or a file the item does not cite, such as the file a text shown as data
   * asked for, is rejected (#768, the decision of 4 Nov 2026). Only what reads as a reference is
   * checked, a tag or a file with a colon, so words around it ("F1 and F2", "F1 (line 3)") pass; a
   * file named without its repository, or a narrower line range, counts as the cited file. A
   * verdict that gives no "by" is not checked here.
   */
  static final class SupportedBy implements Check {

    @Override
    public String name() {
      return "Supported by";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      JsonNode raw = answer.raw();
      if (raw == null) {
        return found;
      }
      String own = answer.item() + " ";
      List<String> cited =
          inputs.references().keySet().stream()
              .filter(k -> k.startsWith(own))
              .map(k -> k.substring(own.length()))
              .toList();
      for (JsonNode claim : raw.path("claims")) {
        String verdict = claim.path("verdict").asString("");
        String by = claim.path("by").asString("").strip();
        if (by.isEmpty() || verdict.equals("unsupported")) {
          continue;
        }
        for (String part : by.split("[,;\\s]+")) {
          String ref = reference(part);
          // A tag with a line added (F1:3) is still the tag.
          boolean tag = TAG.matcher(file(ref)).matches();
          if (tag) {
            ref = file(ref);
          }
          if (!(tag || ref.contains(":")) || cites(cited, ref, tag)) {
            continue;
          }
          found.add(
              new Rejection(
                  answer.item(),
                  "claims",
                  name(),
                  "Gives "
                      + ref
                      + " as the support of \""
                      + claim.path("claim").asString("")
                      + "\", which the item does not cite; judge only against its cited"
                      + " inputs."));
        }
      }
      return found;
    }

    /** Whether the item cites the reference: the same tag, or the same file, by path's end. */
    private static boolean cites(List<String> cited, String ref, boolean tag) {
      if (tag) {
        return cited.contains(ref);
      }
      String file = file(ref);
      return cited.stream()
          .filter(c -> c.contains(":"))
          .map(SupportJudge::file)
          .anyMatch(c -> c.equals(file) || c.endsWith(":" + file) || c.endsWith("/" + file));
    }
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
          // Each item's cited inputs, keyed by its ID in the packet, so a verdict is held to the
          // inputs of its own item.
          Map<String, String> cited = new LinkedHashMap<>();
          for (int i = 0; i < packet.size(); i++) {
            String id = ids.get(i);
            citedInputs(packet.get(i)).forEach((ref, line) -> cited.put(id + " " + ref, line));
          }
          return new CheckInputs(
              SCHEMA, cited, Set.of(), false, (repo, path) -> Optional.empty(), Map.of(), Set.of());
        }

        @Override
        public Pipeline pipeline() {
          List<Check> checks = new ArrayList<>(Checks.STANDARD);
          checks.add(new SupportedBy());
          return new Pipeline(checks);
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
