package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.llm.LlmResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The prompt that asks the LLM to word a deployable unit's responsibility sentence, its components'
 * and the intent phrases of its relationships (ADR 0050), and the check of its answers. The prompt
 * shows extracted facts only, listed as {@code [F1]}, ...; components are listed as {@code [C1]}
 * and relationships as {@code [R1]}. Fact IDs and line numbers are not shown, since they change
 * with every commit and every edit above the code; so the same unit gives the same prompt, and the
 * same cache key, until what the prompt shows changes. Summaries are not shown: they exist only for
 * the repositories a run analyzes, so showing them would make the prompt, and so the calls, depend
 * on which repositories a run names.
 *
 * <p>Change {@link #VERSION} whenever the text of the prompt or the way inputs are written into it
 * changes, so answers cached for the old prompt are not reused. {@code WordingPromptTest} fails
 * until it is changed.
 */
final class WordingPrompt {

  /** The prompt's version, part of every answer's cache key. */
  static final String VERSION = "1";

  /** The most words a responsibility sentence may have. */
  static final int SENTENCE_WORDS = 30;

  /** The most words an intent phrase may have. */
  static final int PHRASE_WORDS = 10;

  static final String SYSTEM =
      """
      You help document the architecture of a system of Java services for readers who do not \
      know its code. You are given one deployable unit with facts that static analysis \
      extracted from its code, configuration and README, each with a reference such as [F1]: \
      the facts that make it a unit, what its README claims, its entry points, its components, \
      each with a reference such as [C1], and its relationships with other containers, each \
      with a reference such as [R1].
      Word only what the facts show. Never invent a responsibility, an element or a \
      relationship. Infer purpose from names, paths, routing keys, tables and README claims.
      For the unit, and for each component, write a responsibility sentence: one plain sentence \
      of at most 25 words saying what it is responsible for, in words a non-engineer \
      understands. Do not restate its name or its kind; say what it does for the system.
      For each relationship, write an intent phrase: at most 8 words, starting with a verb, \
      saying what the unit does with the other end and why, such as "Looks up product prices" \
      or "Publishes new orders for shipping". Never write just "uses", "calls", "depends on" or \
      "connects to", and leave out the protocol, which is shown separately.
      Leave out the unit, a component or a relationship when the facts do not show its \
      responsibility or intent, rather than guessing.
      Answer with one JSON object and nothing else, in this form:
      {"unit": {"text": "Sentence.", "cites": ["F1", "F2"]}, \
      "components": {"C1": {"text": "Sentence.", "cites": ["F4"]}}, \
      "relationships": {"R1": {"text": "Phrase", "cites": ["F7"]}}}
      cites lists the references of the facts each text rests on, each on its own; every text \
      cites at least one. Cite only [F...] references from the input. Write no references inside \
      the texts.""";

  static final String RETRY =
      """
      Your answer was rejected: %s
      Answer again with one JSON object in the form asked for.""";

  /** Phrases that say nothing about intent (docs/design.md, Quality bar). */
  static final Set<String> GENERIC =
      Set.of(
          "uses",
          "use",
          "calls",
          "call",
          "depends on",
          "connects to",
          "talks to",
          "communicates with",
          "interacts with",
          "sends",
          "receives",
          "reads",
          "writes");

  private static final Pattern REFERENCE = Pattern.compile("F[1-9][0-9]*");

  private WordingPrompt() {}

  /**
   * A component as the prompt shows it.
   *
   * @param type its main type
   * @param name its name
   * @param role what kind of component it is, or null
   * @param facts the facts shown about it
   */
  record ComponentInput(EntityId type, String name, String role, List<Fact> facts) {}

  /**
   * A relationship as the prompt shows it.
   *
   * @param verb what the unit does with the other end, such as {@code calls}
   * @param other the other end's name
   * @param kind what the other end is, such as {@code a database}
   * @param link the link fact
   */
  record RelationshipInput(String verb, String other, String kind, Fact link) {}

  /**
   * One prompt and what its references stand for.
   *
   * @param unit the deployable unit it asks about
   * @param name the unit's name
   * @param user the user turn
   * @param facts the facts shown, by reference
   * @param components the main type of each component shown, by reference
   * @param componentNames the name of each component shown, by reference
   * @param relationships the link fact of each relationship shown, by reference
   */
  record Question(
      EntityId unit,
      String name,
      String user,
      Map<String, Fact> facts,
      Map<String, EntityId> components,
      Map<String, String> componentNames,
      Map<String, Fact> relationships) {}

  /**
   * Writes the prompt for a unit.
   *
   * @param unit the unit
   * @param name its name
   * @param technology what it is built with, or null
   * @param unitFacts the facts that make it a unit
   * @param claims what its README claims
   * @param entryPoints its entry points, when it has no components shown
   * @param components its components, entry points first
   * @param relationships its links to other containers
   */
  static Question question(
      EntityId unit,
      String name,
      String technology,
      List<Fact> unitFacts,
      List<Fact> claims,
      List<Fact> entryPoints,
      List<ComponentInput> components,
      List<RelationshipInput> relationships) {
    Map<String, Fact> facts = new LinkedHashMap<>();
    Map<String, EntityId> componentRefs = new LinkedHashMap<>();
    Map<String, String> componentNames = new LinkedHashMap<>();
    Map<String, Fact> relationshipRefs = new LinkedHashMap<>();
    StringBuilder user = new StringBuilder();
    user.append("The deployable unit ").append(oneLine(name));
    if (technology != null) {
      user.append(", built with ").append(oneLine(technology));
    }
    user.append(".\n\nFacts about the unit:\n");
    unitFacts.forEach(f -> user.append(fact(f, facts, "")).append('\n'));
    if (!claims.isEmpty()) {
      user.append("\nWhat its README claims:\n");
      claims.forEach(f -> user.append(fact(f, facts, "")).append('\n'));
    }
    if (!entryPoints.isEmpty()) {
      user.append("\nIts entry points:\n");
      entryPoints.forEach(f -> user.append(fact(f, facts, "")).append('\n'));
    }
    if (!components.isEmpty()) {
      user.append("\nComponents:\n");
      for (int i = 0; i < components.size(); i++) {
        ComponentInput component = components.get(i);
        String ref = "C" + (i + 1);
        componentRefs.put(ref, component.type());
        componentNames.put(ref, component.name());
        user.append('[').append(ref).append("] ").append(oneLine(component.name()));
        if (component.role() != null) {
          user.append(", ").append(oneLine(component.role()));
        }
        user.append('\n');
        component.facts().forEach(f -> user.append(fact(f, facts, "  ")).append('\n'));
      }
    }
    if (!relationships.isEmpty()) {
      user.append("\nRelationships:\n");
      for (int i = 0; i < relationships.size(); i++) {
        RelationshipInput relationship = relationships.get(i);
        String ref = "R" + (i + 1);
        relationshipRefs.put(ref, relationship.link());
        user.append('[')
            .append(ref)
            .append("] ")
            .append(relationship.verb())
            .append(' ')
            .append(oneLine(relationship.other()))
            .append(" (")
            .append(relationship.kind())
            .append(")\n");
        user.append(fact(relationship.link(), facts, "  ")).append('\n');
      }
    }
    return new Question(
        unit, name, user.toString(), facts, componentRefs, componentNames, relationshipRefs);
  }

  /** A fact's line with its reference, a new one unless the prompt shows it already. */
  private static String fact(Fact fact, Map<String, Fact> refs, String indent) {
    String ref = null;
    for (Map.Entry<String, Fact> e : refs.entrySet()) {
      if (e.getValue().id().equals(fact.id())) {
        ref = e.getKey();
      }
    }
    if (ref == null) {
      ref = "F" + (refs.size() + 1);
      refs.put(ref, fact);
    }
    return indent + "[" + ref + "] " + fact(fact);
  }

  /**
   * A fact as the prompt shows it: subject, predicate, object, attributes and the file it is in,
   * without the IDs of other facts, which change with every commit, and without its line.
   */
  static String fact(Fact fact) {
    StringBuilder line = new StringBuilder();
    line.append(fact.subject()).append(' ').append(fact.predicate()).append(' ');
    FactObject object = fact.object();
    if (object.kind() == FactObject.Kind.STRING) {
      line.append('"')
          .append(object.value().replace("\\", "\\\\").replace("\"", "\\\""))
          .append('"');
    } else {
      line.append(object.value());
    }
    ObjectNode attributes = fact.attributes().deepCopy();
    List.of("facts", "link", "candidates").forEach(attributes::remove);
    if (!attributes.isEmpty()) {
      line.append(' ').append(attributes);
    }
    Evidence evidence = fact.evidence();
    line.append(" (").append(evidence.repo());
    if (evidence.path() != null) {
      line.append(':').append(evidence.path());
    }
    line.append(')');
    return oneLine(line.toString());
  }

  private static String oneLine(String text) {
    return text.replace('\r', ' ').replace('\n', ' ');
  }

  /** An answer that does not meet the rules, with the reason to send back. */
  static final class Rejected extends RuntimeException {

    Rejected(String reason) {
      super(reason);
    }
  }

  /**
   * Checks an answer: one JSON object whose unit, components and relationships, each optional, are
   * texts citing at least one fact the prompt shows, for components and relationships the prompt
   * shows; sentences of at most {@link #SENTENCE_WORDS} words that are not just the element's name,
   * and phrases of at most {@link #PHRASE_WORDS} words that are not just "uses" or the like.
   *
   * @return the texts, by reference ({@link WordingAnswer#UNIT}, {@code C1}, {@code R1}, ...)
   * @throws Rejected if it does not meet the rules
   */
  static Map<String, WordingAnswer.Item> check(LlmResponse response, Question question) {
    if (response.cutOff()) {
      throw new Rejected("it was cut off at the length limit; write shorter texts.");
    }
    String text = response.text();
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    JsonNode json;
    try {
      json = start < 0 || end < start ? null : FactJson.parse(text.substring(start, end + 1));
    } catch (RuntimeException e) {
      json = null;
    }
    if (json == null || !json.isObject()) {
      throw new Rejected("it is not one JSON object.");
    }
    Map<String, WordingAnswer.Item> items = new TreeMap<>();
    JsonNode unit = json.get(WordingAnswer.UNIT);
    if (unit != null && !unit.isNull()) {
      items.put(WordingAnswer.UNIT, item(unit, "the unit", question, question.name(), false));
    }
    group(json, "components", question.components().keySet(), question, false, items);
    group(json, "relationships", question.relationships().keySet(), question, true, items);
    return items;
  }

  private static void group(
      JsonNode json,
      String field,
      Set<String> shown,
      Question question,
      boolean phrases,
      Map<String, WordingAnswer.Item> items) {
    JsonNode group = json.get(field);
    if (group == null || group.isNull()) {
      return;
    }
    if (!group.isObject()) {
      throw new Rejected(field + " is not an object of references.");
    }
    for (Map.Entry<String, JsonNode> entry : group.properties()) {
      String ref =
          entry.getKey().strip().replace("[", "").replace("]", "").toUpperCase(Locale.ROOT);
      if (!shown.contains(ref)) {
        throw new Rejected(field + " names " + ref + ", which is not in the input.");
      }
      if (entry.getValue() == null || entry.getValue().isNull()) {
        continue;
      }
      String name = phrases ? null : question.componentNames().get(ref);
      items.put(ref, item(entry.getValue(), ref, question, name, phrases));
    }
  }

  private static WordingAnswer.Item item(
      JsonNode node, String what, Question question, String name, boolean phrase) {
    if (!node.isObject()) {
      throw new Rejected("the text of " + what + " is not an object with text and cites.");
    }
    JsonNode textNode = node.get("text");
    if (textNode == null || !textNode.isString() || textNode.stringValue().isBlank()) {
      throw new Rejected(what + " has no text.");
    }
    String text = oneLine(textNode.stringValue()).strip().replaceAll("\\s+", " ");
    if (text.contains("[")) {
      throw new Rejected("the text of " + what + " holds a reference; put references in cites.");
    }
    int words = text.split(" ").length;
    if (phrase) {
      if (text.endsWith(".")) {
        text = text.substring(0, text.length() - 1).strip();
      }
      if (words > PHRASE_WORDS) {
        throw new Rejected(
            "the intent phrase of " + what + " has more than " + PHRASE_WORDS + " words.");
      }
      if (GENERIC.contains(text.toLowerCase(Locale.ROOT))) {
        throw new Rejected(
            "the intent phrase of " + what + " is just \"" + text + "\"; say what it is for.");
      }
    } else {
      if (words > SENTENCE_WORDS) {
        throw new Rejected(
            "the responsibility sentence of "
                + what
                + " has more than "
                + SENTENCE_WORDS
                + " words.");
      }
      String bare = text.endsWith(".") ? text.substring(0, text.length() - 1).strip() : text;
      if (name != null && bare.equalsIgnoreCase(name)) {
        throw new Rejected(
            "the responsibility sentence of "
                + what
                + " only restates its name; say what it does.");
      }
    }
    Set<String> cited = new LinkedHashSet<>();
    JsonNode cites = node.get("cites");
    if (cites != null && !cites.isNull()) {
      if (!cites.isArray()) {
        throw new Rejected("the cites of " + what + " are not a list of references.");
      }
      for (JsonNode cite : cites) {
        String ref = cite.isString() ? cite.stringValue().strip() : cite.toString();
        if (ref.startsWith("[") && ref.endsWith("]")) {
          ref = ref.substring(1, ref.length() - 1).strip();
        }
        ref = ref.toUpperCase(Locale.ROOT);
        if (!REFERENCE.matcher(ref).matches() || !question.facts().containsKey(ref)) {
          throw new Rejected(what + " cites " + ref + ", which is not a fact in the input.");
        }
        cited.add(ref);
      }
    }
    if (cited.isEmpty()) {
      throw new Rejected(what + " cites nothing; cite the facts it rests on.");
    }
    return new WordingAnswer.Item(text, new ArrayList<>(cited));
  }
}
