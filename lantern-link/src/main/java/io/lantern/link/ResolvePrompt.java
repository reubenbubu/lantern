package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.harness.Prompts;
import io.lantern.harness.render.FactLines;
import io.lantern.harness.render.Refs;
import io.lantern.harness.verify.JsonSchema;
import io.lantern.link.attributes.UnresolvedLinkAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The Resolve prompt (knowledge-build epic, section 11.1): block 1 is the common system prompt,
 * block 2 the role's instructions and submit schema, read from {@code prompts/resolve.*}; block 3
 * the system map; block 4 the calling unit's facts that bear on addresses; block 5 its open links,
 * each an item with its candidates {@code [K1]} to {@code [Kn]}. Facts are fact lines ({@link
 * FactLines}) with references {@code F..} numbered across the request ({@link Refs}).
 *
 * <p>Change {@link #VERSION} whenever the text of the prompt or the way inputs are written into it
 * changes, so answers cached for the old prompt are not reused. {@code ResolvePromptTest} fails
 * until it is changed.
 */
final class ResolvePrompt {

  /** The prompt's version, part of every answer's cache key and the version of the facts. */
  static final String VERSION = "7";

  /** Block 2's instructions, as the epic writes them. */
  static final String TASK = resource("resolve.task.txt");

  /** The submit schema, as the epic writes it. */
  static final String SCHEMA = resource("resolve.schema.json");

  /** What separates a role's instructions from its schema in block 2. */
  static final String SCHEMA_HEADING = "\n\nAnswer schema:\n";

  /** Block 2: the role's instructions and submit schema. */
  static final String ROLE = TASK + SCHEMA_HEADING + SCHEMA;

  /** The submit tool's input schema. */
  static final JsonNode SUBMIT_SCHEMA = FactJson.parse(SCHEMA);

  /** The schema of one item's answer. */
  static final JsonNode ITEM_SCHEMA = JsonSchema.itemSchema(SUBMIT_SCHEMA);

  /** The most words of a reason. */
  static final io.lantern.harness.Contract.Range REASON_WORDS =
      io.lantern.harness.Contract.of("resolve").acceptedWords("reason");

  /** The pick of an answer that picks no candidate. */
  static final String UNKNOWN = "unknown";

  /** The confidences an answer may give. */
  static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");

  private ResolvePrompt() {}

  /**
   * A candidate as an item shows it.
   *
   * @param ref its reference, such as {@code K1}
   * @param unit the unit
   * @param facts the facts shown under it: its unit, application name, deployment containers and
   *     the endpoints or listeners that would be reached
   * @param reached the endpoints, bindings or listeners that would be reached, best first
   * @param repos the repositories its code is in, where file ranges on its side are read
   */
  record Candidate(
      String ref, EntityId unit, List<Fact> facts, List<Fact> reached, Set<String> repos) {}

  /**
   * What an item shows of a candidate.
   *
   * @param facts the facts shown under it
   * @param reached the endpoints, bindings or listeners among them, best first, as the linker ranks
   *     them
   * @param repos the repositories its code is in
   */
  record Shown(List<Fact> facts, List<Fact> reached, Set<String> repos) {}

  /**
   * One open link as an item.
   *
   * @param unresolved its {@code unresolved-link} fact
   * @param caller the unit that makes the call or send
   * @param callerRepos the repositories the caller's code is in
   * @param about the link's own facts, shown in the item
   * @param address the caller's facts that bear on the link's address, shown in block 4
   * @param candidates its candidates, in the order of their unit IDs
   * @param rendering the item's text, its fact references as tokens
   * @param refs what its references stand for
   */
  record Question(
      Fact unresolved,
      EntityId caller,
      Set<String> callerRepos,
      List<Fact> about,
      List<Fact> address,
      List<Candidate> candidates,
      String rendering,
      Refs refs) {

    /** The candidate a pick names, by its reference; null if none. */
    Candidate candidate(String pick) {
      for (Candidate candidate : candidates) {
        if (candidate.ref().equalsIgnoreCase(pick.strip())) {
          return candidate;
        }
      }
      return null;
    }

    /** The candidate of a unit; null if it is not one. */
    Candidate candidate(EntityId unit) {
      return candidates.stream().filter(c -> c.unit().equals(unit)).findFirst().orElse(null);
    }

    /**
     * The canonical text of the item, for its fingerprint: what the item shows, then the address
     * facts block 4 shows for it, so a change to either asks it again.
     */
    String canonical() {
      StringBuilder text = new StringBuilder(Refs.plain(rendering));
      text.append("Address facts:\n");
      for (Fact fact : address) {
        text.append(FactLines.line(fact)).append('\n');
      }
      return text.toString();
    }
  }

  /**
   * Writes an item for a link left open.
   *
   * @param unresolved the {@code unresolved-link} fact
   * @param caller the calling unit
   * @param callerRepos the repositories its code is in
   * @param about the link's facts; those also in {@code address} are shown in block 4 only
   * @param address the caller's facts that bear on the link's address
   * @param candidates each candidate with the facts shown under it and its repositories; shown in
   *     the order of their unit IDs, which carries no ranking the model could favour
   */
  static Question question(
      Fact unresolved,
      EntityId caller,
      Set<String> callerRepos,
      List<Fact> about,
      List<Fact> address,
      Map<EntityId, Shown> candidates) {
    Refs refs = new Refs();
    UnresolvedLinkAttributes attributes = Claims.of(unresolved, UnresolvedLinkAttributes.class);
    String kind = attributes.kind();
    StringBuilder text = new StringBuilder();
    text.append("Open link: ")
        .append(
            switch (kind) {
              case "route" -> "the HTTP call a gateway route forwards, ";
              case "http" -> "an HTTP call, ";
              default -> "a message send, ";
            })
        .append(FactLines.oneLine(unresolved.object().value()))
        .append('\n');
    text.append("Of the unit ").append(caller);
    String source = Claims.source(unresolved);
    if (source != null) {
      text.append(", made in ").append(source);
    }
    String view = Claims.view(unresolved);
    if (view != null) {
      text.append(", under the view ").append(view);
    }
    text.append('\n');
    text.append("Static matching stopped because ").append(reason(attributes.reason()));
    List<String> keys = Claims.unresolved(unresolved);
    if (!keys.isEmpty()) {
      text.append("; placeholders no configuration resolves: ").append(String.join(", ", keys));
    }
    text.append(".\n");
    List<Fact> own = new ArrayList<>(about);
    own.removeAll(address);
    if (!own.isEmpty()) {
      text.append("Its facts:\n");
      facts(text, refs, own);
    }
    text.append("Candidates:\n");
    List<Candidate> shown = new ArrayList<>();
    List<Map.Entry<EntityId, Shown>> ordered = new ArrayList<>(candidates.entrySet());
    ordered.sort(Map.Entry.comparingByKey(Comparator.comparing(EntityId::toString)));
    for (Map.Entry<EntityId, Shown> e : ordered) {
      String ref = "K" + (shown.size() + 1);
      text.append('[').append(Refs.token(ref)).append("] ").append(e.getKey()).append('\n');
      List<Fact> facts = FactLines.sorted(e.getValue().facts());
      if (!facts.isEmpty()) {
        facts(text, refs, facts);
      }
      shown.add(
          new Candidate(
              ref,
              e.getKey(),
              facts,
              List.copyOf(e.getValue().reached()),
              Set.copyOf(e.getValue().repos())));
    }
    return new Question(
        unresolved,
        caller,
        Set.copyOf(callerRepos),
        List.copyOf(own),
        FactLines.sorted(address),
        List.copyOf(shown),
        text.toString(),
        refs);
  }

  /** Facts in a {@code <facts>} block, each with its reference. */
  private static void facts(StringBuilder text, Refs refs, List<Fact> facts) {
    text.append("<facts>\n");
    for (Fact fact : FactLines.sorted(facts)) {
      String line = FactLines.line(fact);
      text.append('[')
          .append(refs.add('F', key(fact), fact, line))
          .append("] ")
          .append(line)
          .append('\n');
    }
    text.append("</facts>\n");
  }

  /**
   * Block 4: the caller's facts that bear on its links' addresses (its unit, application name,
   * deployment containers and the configuration values of the keys its links use, per view).
   */
  static String packet(EntityId caller, Refs refs, Collection<Fact> address) {
    StringBuilder text = new StringBuilder();
    text.append("The calling unit: ").append(caller).append('\n');
    text.append(
        "Its facts that bear on addresses (its application name, the deployment containers that"
            + " run it, and the configuration values of the keys its links use, per view):\n");
    facts(text, refs, List.copyOf(new LinkedHashSet<>(address)));
    return text.toString().stripTrailing();
  }

  /**
   * Block 3: the system map, to orient the model: each deployable unit with its application name
   * and the containers that run it, then the links static analysis found between units. It cites
   * nothing.
   *
   * @param units each unit's line, such as {@code service:g:orders, application name orders, runs
   *     in orders (Docker Compose)}
   * @param links each link's line, such as {@code service:g:orders -> service:g:tax [HTTP]}
   */
  static String systemMap(List<String> units, List<String> links) {
    StringBuilder text = new StringBuilder();
    text.append("System map: the deployable units, where they run, and the links static analysis")
        .append(" found between them.\n<facts>\n");
    units.forEach(line -> text.append("unit ").append(FactLines.oneLine(line)).append('\n'));
    links.forEach(line -> text.append("link ").append(FactLines.oneLine(line)).append('\n'));
    return text.append("</facts>").toString();
  }

  /**
   * A fact's key among an item's references and in a stored answer's cites: its claim ID, which
   * stays the same across commits (ADR 0091); for a fact not yet stored, the hash of its line,
   * which holds no line number either.
   */
  static String key(Fact fact) {
    return fact.claimId() != null
        ? fact.claimId()
        : io.lantern.harness.Fingerprints.sha256(FactLines.line(fact));
  }

  /** Why static matching left a link open, in words. */
  static String reason(String reason) {
    if (reason == null) {
      return "it could not decide";
    }
    return switch (reason) {
      case "unresolved-placeholder" -> "its URL or destination has placeholders";
      case "unknown-url" -> "its URL is not known";
      case "unknown-host" -> "its host names no unit";
      case "ambiguous" -> "more than one unit matches it";
      case "no-match" -> "no unit serves it";
      case "unknown-routing-key" ->
          "its routing key is not known, so which binding routes it cannot be told";
      case "unknown-exchange" -> "its exchange is not known, so where it is routed cannot be told";
      default -> reason.replace('-', ' ');
    };
  }

  /** A cited reference as the model may write it ({@code [f3]}), as the checks read it. */
  static String cite(String cite) {
    String ref = cite.strip();
    if (ref.startsWith("[") && ref.endsWith("]")) {
      ref = ref.substring(1, ref.length() - 1).strip();
    }
    return ref.matches("(?i)[fk][0-9]+") ? ref.toUpperCase(Locale.ROOT) : ref;
  }

  /** Reads a prompt resource as written. */
  private static String resource(String name) {
    return Prompts.text(name);
  }
}
