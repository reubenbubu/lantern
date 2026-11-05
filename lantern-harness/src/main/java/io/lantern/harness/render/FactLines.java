package io.lantern.harness.render;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fact lines as every role's prompt shows them (knowledge-build epic, section 11.0, How inputs are
 * rendered): {@code subject predicate object}, the attributes as compact JSON with sorted keys, and
 * {@code (repo:path)}. A line holds no identifier that changes: no fact or claim IDs, commits or
 * line numbers. An object that is itself a claim ID is shown by the readable key of what it refers
 * to. Lines are sorted by their text, which starts with subject, predicate and object.
 */
public final class FactLines {

  /**
   * The attributes a fact line leaves out: those that name other facts by ID (ADR 0091) or list a
   * link's candidates, and line numbers, since an ID or a line changes with any edit of its file
   * and would re-ask whatever shows it.
   */
  public static final List<String> HIDDEN =
      List.of("facts", "cites", "fact", "claim", "contradictedBy", "link", "candidates", "lines");

  private static final Pattern CLAIM_ID = Pattern.compile("[0-9a-f]{64}");

  private FactLines() {}

  /** The fact's line, with an object that is a claim ID shown as is. */
  public static String line(Fact fact) {
    return line(fact, UnaryOperator.identity());
  }

  /**
   * The fact's line.
   *
   * @param claims the readable key of what a claim ID refers to, such as {@code orders → payments
   *     [CALLS_HTTP]} for a link; given a claim ID, it returns the ID itself when it knows none
   */
  public static String line(Fact fact, UnaryOperator<String> claims) {
    StringBuilder line = new StringBuilder();
    line.append(fact.subject()).append(' ').append(fact.predicate()).append(' ');
    line.append(object(fact, claims));
    String attributes = attributes(fact);
    if (!attributes.isEmpty()) {
      line.append(' ').append(attributes);
    }
    line.append(" (").append(place(fact)).append(')');
    return oneLine(Refs.clean(line.toString()));
  }

  /**
   * The fact's object as its line writes it: a string in double quotes, escaped; an entity or other
   * value as it is.
   */
  public static String object(Fact fact, UnaryOperator<String> claims) {
    FactObject object = fact.object();
    String value = object.value();
    if (object.kind() == FactObject.Kind.STRING) {
      if (CLAIM_ID.matcher(value).matches()) {
        value = claims.apply(value);
      }
      return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
    return value;
  }

  /** The fact's attributes as compact JSON with sorted keys, as {@link #shown} gives them. */
  public static String attributes(Fact fact) {
    ObjectNode attributes = shown(fact.attributes());
    return attributes.isEmpty() ? "" : attributes.toString();
  }

  /** Where the fact was observed: {@code repo:path}, or the repository alone. */
  public static String place(Fact fact) {
    Evidence evidence = fact.evidence();
    return evidence.path() == null ? evidence.repo() : evidence.repo() + ":" + evidence.path();
  }

  /**
   * The attributes a prompt shows: all but those {@link #HIDDEN} and those, whatever their name,
   * whose value is a fact or claim ID, or a list or map of them only, such as a flow's {@code
   * trigger} (#881). An ID changes with any edit of its file, and tells the model nothing.
   */
  public static ObjectNode shown(ObjectNode attributes) {
    ObjectNode shown = attributes.deepCopy();
    HIDDEN.forEach(shown::remove);
    List<String> ids = new ArrayList<>();
    shown
        .properties()
        .forEach(
            e -> {
              if (onlyIds(e.getValue())) {
                ids.add(e.getKey());
              }
            });
    ids.forEach(shown::remove);
    return shown;
  }

  private static boolean onlyIds(JsonNode value) {
    if (value.isString()) {
      return CLAIM_ID.matcher(value.asString()).matches();
    }
    if (value.isArray() || value.isObject()) {
      if (value.isEmpty()) {
        return false;
      }
      for (JsonNode element : value.values()) {
        if (!onlyIds(element)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /** The facts in the order of their lines, which does not depend on their IDs. */
  public static List<Fact> sorted(Collection<Fact> facts) {
    List<Fact> sorted = new ArrayList<>(facts);
    sorted.sort(Comparator.comparing((Fact f) -> line(f)).thenComparing(Fact::id));
    return sorted;
  }

  /** The text on one line: line breaks and tabs as spaces. */
  public static String oneLine(String text) {
    return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
  }
}
