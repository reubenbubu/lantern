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
 * <p>A size rule has two ranges. The requested range is what the prompt states, such as "at most 22
 * words". The acceptable range is what the checks enforce: the requested maximum plus a tolerance,
 * {@value #DEFAULT_TOLERANCE} of it by default and at least one unit, unless the rule gives its own
 * {@code accepted}. A contract is read from the prompts folder like the prompts ({@link
 * Prompts#text}), but it is not part of any role's prompt version: it changes what the checks
 * accept, not what a model is asked, so editing it asks nothing again.
 */
public final class Contract {

  /** The share of a requested maximum an answer may exceed it by and still be accepted. */
  public static final double DEFAULT_TOLERANCE = 0.15;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, Contract> CONTRACTS = new ConcurrentHashMap<>();

  /**
   * One text a role writes.
   *
   * @param name the field's name in the contract
   * @param requestedWords the most words the prompt asks for, or {@code null} if it states none
   * @param acceptedWords the most words the checks accept, or {@code null} for no limit
   * @param stated the prompt's own words for the requested size, or {@code null} if it states none
   * @param enforced whether the checks enforce the accepted size; a rule that is not is only
   *     measured
   */
  public record Field(
      String name,
      Integer requestedWords,
      Integer acceptedWords,
      String stated,
      boolean enforced) {}

  private final String role;
  private final double tolerance;
  private final List<String> prompts;
  private final Map<String, Field> fields;
  private final Map<String, Integer> counts;

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
      Integer requested = words.has("requested") ? words.get("requested").asInt() : null;
      Integer accepted =
          words.has("accepted")
              ? Integer.valueOf(words.get("accepted").asInt())
              : requested == null ? null : accepted(requested, tolerance);
      read.put(
          name,
          new Field(
              name,
              requested,
              accepted,
              words.has("stated") ? words.get("stated").asString() : null,
              words.path("enforced").asBoolean(true)));
    }
    this.fields = Map.copyOf(read);
    Map<String, Integer> limits = new LinkedHashMap<>();
    json.path("counts")
        .forEach(c -> limits.put(c.path("name").asString(), c.path("requested").asInt()));
    this.counts = Map.copyOf(limits);
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
   * The most units a check accepts for a requested maximum: that maximum plus the tolerance's share
   * of it, rounded, and at least one unit.
   */
  public static int accepted(int requested, double tolerance) {
    return requested + Math.max(1, (int) Math.round(requested * tolerance));
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

  /** The most words the checks accept for the field. */
  public int acceptedWords(String field) {
    Integer accepted = field(field).acceptedWords();
    if (accepted == null) {
      throw new IllegalStateException(role + ".contract.json gives " + field + " no word limit");
    }
    return accepted;
  }

  /** The most of the named count the prompt asks for and the checks keep, such as proposals. */
  public int count(String name) {
    Integer count = counts.get(name);
    if (count == null) {
      throw new IllegalStateException(role + ".contract.json has no count " + name);
    }
    return count;
  }

  /** The most words the prompt asks for in the field. */
  public int requestedWords(String field) {
    Integer requested = field(field).requestedWords();
    if (requested == null) {
      throw new IllegalStateException(role + ".contract.json requests no size for " + field);
    }
    return requested;
  }
}
