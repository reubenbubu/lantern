package io.lantern.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A role's response contract, {@code prompts/<role>.contract.json} (ADR 0118): for each text the
 * role writes, the size its prompt requests and the size its checks accept. The prompt, the checks,
 * the scorer and the wording judge read it, so a model's answers can be measured against one set of
 * rules.
 *
 * <p>A size rule has two ranges. The requested range is what the prompt states, such as "8 to 22
 * words". The acceptable range is what the checks enforce: the requested range widened on each side
 * by a tolerance, {@value #DEFAULT_TOLERANCE} of that side's bound by default and at least one
 * unit, unless the rule gives its own {@code accepted} range. A text below the accepted minimum is
 * rejected like one above the maximum. A contract is read from the prompts folder like the prompts
 * ({@link Prompts#text}), but it is not part of any role's prompt version: it changes what the
 * checks accept, not what a model is asked, so editing it asks nothing again.
 */
public final class Contract {

  /** The share of each bound of a requested range an answer may pass it by and be accepted. */
  public static final double DEFAULT_TOLERANCE = 0.15;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, Contract> CONTRACTS = new ConcurrentHashMap<>();

  /**
   * A size range, both ends included.
   *
   * @param min the fewest units
   * @param max the most units
   */
  public record Range(int min, int max) {

    /** Any size at all: for a text no size rule applies to. */
    public static final Range ANY = new Range(0, Integer.MAX_VALUE);

    public Range {
      if (min < 0 || max < min) {
        throw new IllegalArgumentException("Not a size range: " + min + " to " + max);
      }
    }

    /** Whether the size is within the range. */
    public boolean contains(int size) {
      return size >= min && size <= max;
    }

    /** The range as a prompt or a rejection says it, "8 to 22". */
    @Override
    public String toString() {
      return min + " to " + max;
    }
  }

  /**
   * One text a role writes.
   *
   * @param name the field's name in the contract
   * @param requestedWords the words the prompt asks for, or {@code null} if it asks for no size
   * @param acceptedWords the words the checks accept, or {@code null} for no limit
   * @param stated the prompt's own words for the requested size, or {@code null} if it states none
   * @param enforced whether the checks enforce the accepted size; a rule that is not is only
   *     measured
   * @param requestedSentences the sentences the prompt asks for, or {@code null}
   * @param acceptedSentences the sentences accepted; measured only, never enforced, or {@code null}
   */
  public record Field(
      String name,
      Range requestedWords,
      Range acceptedWords,
      String stated,
      boolean enforced,
      Range requestedSentences,
      Range acceptedSentences) {}

  private final String role;
  private final double tolerance;
  private final List<String> prompts;
  private final Map<String, Field> fields;
  private final Map<String, Range> counts;
  private final Map<String, List<String>> lists;

  private Contract(String role, JsonNode json) {
    this.role = role;
    this.tolerance = json.path("tolerance").asDouble(DEFAULT_TOLERANCE);
    List<String> files = new ArrayList<>();
    json.path("prompts").forEach(p -> files.add(p.asString()));
    this.prompts = List.copyOf(files);
    Map<String, Field> read = new LinkedHashMap<>();
    for (JsonNode field : json.path("fields")) {
      String name = field.path("name").asString();
      JsonNode words = field.path("words");
      Range requested = range(words.get("requested"), role, name);
      JsonNode sentences = field.path("sentences");
      Range sentencesAsked = range(sentences.get("requested"), role, name);
      read.put(
          name,
          new Field(
              name,
              requested,
              accepted(words, requested, 1, role, name),
              words.has("stated") ? words.get("stated").asString() : null,
              words.path("enforced").asBoolean(true),
              sentencesAsked,
              accepted(sentences, sentencesAsked, 1, role, name)));
    }
    this.fields = Map.copyOf(read);
    Map<String, Range> limits = new LinkedHashMap<>();
    for (JsonNode c : json.path("counts")) {
      String name = c.path("name").asString();
      limits.put(name, accepted(c, range(c.get("requested"), role, name), 0, role, name));
    }
    this.counts = Map.copyOf(limits);
    Map<String, List<String>> named = new LinkedHashMap<>();
    json.path("lists")
        .properties()
        .forEach(
            e -> {
              List<String> values = new ArrayList<>();
              e.getValue().forEach(v -> values.add(v.asString()));
              named.put(e.getKey(), List.copyOf(values));
            });
    this.lists = Map.copyOf(named);
  }

  /**
   * A named list of the contract, such as the infrastructure Investigate proposes no connection to
   * (#749); empty if the contract has none by that name.
   */
  public List<String> list(String name) {
    return lists.getOrDefault(name, List.of());
  }

  /** A {@code [min, max]} pair of the contract as a range, or {@code null} if it has none. */
  private static Range range(JsonNode pair, String role, String name) {
    if (pair == null || pair.isNull()) {
      return null;
    }
    if (!pair.isArray() || pair.size() != 2) {
      throw new IllegalStateException(
          role + ".contract.json: " + name + " must give a range as [min, max], not " + pair);
    }
    return new Range(pair.get(0).asInt(), pair.get(1).asInt());
  }

  /** The rule's own accepted range, or else its requested range widened by the tolerance. */
  private Range accepted(JsonNode rule, Range requested, int floor, String role, String name) {
    Range own = range(rule.get("accepted"), role, name);
    if (own != null) {
      return own;
    }
    return requested == null ? null : accepted(requested, tolerance, floor);
  }

  /** The role's contract, read once from {@code <role>.contract.json} in the prompts folder. */
  public static Contract of(String role) {
    return CONTRACTS.computeIfAbsent(
        role, r -> new Contract(r, JSON.readTree(Prompts.text(r + ".contract.json"))));
  }

  /**
   * Forgets the contracts read so far, when the prompts folder changes ({@link Prompts#useFolder}).
   */
  static void forget() {
    CONTRACTS.clear();
  }

  /**
   * The range a check accepts for a requested range: each bound moved out by the tolerance's share
   * of it, rounded, and at least one unit, but the minimum never below the floor (one word for a
   * text, none for a count).
   */
  public static Range accepted(Range requested, double tolerance, int floor) {
    return new Range(
        Math.max(floor, requested.min() - margin(requested.min(), tolerance)),
        requested.max() + margin(requested.max(), tolerance));
  }

  private static int margin(int bound, double tolerance) {
    return Math.max(1, (int) Math.round(bound * tolerance));
  }

  /** The role the contract is of. */
  public String role() {
    return role;
  }

  /** The prompt files that state the contract's requested sizes. */
  public List<String> prompts() {
    return prompts;
  }

  /** The contract's fields, by name. */
  public Map<String, Field> fields() {
    return fields;
  }

  /** The field of the given name; an error if the contract has none. */
  public Field field(String name) {
    Field field = fields.get(name);
    if (field == null) {
      throw new IllegalStateException(role + ".contract.json has no field " + name);
    }
    return field;
  }

  /** The words the checks accept for the field. */
  public Range acceptedWords(String field) {
    Range accepted = field(field).acceptedWords();
    if (accepted == null) {
      throw new IllegalStateException(role + ".contract.json gives " + field + " no word range");
    }
    return accepted;
  }

  /** The range of the named count the checks keep, such as proposals. */
  public Range count(String name) {
    Range count = counts.get(name);
    if (count == null) {
      throw new IllegalStateException(role + ".contract.json has no count " + name);
    }
    return count;
  }

  /** The words the prompt asks for in the field. */
  public Range requestedWords(String field) {
    Range requested = field(field).requestedWords();
    if (requested == null) {
      throw new IllegalStateException(role + ".contract.json requests no size for " + field);
    }
    return requested;
  }
}
