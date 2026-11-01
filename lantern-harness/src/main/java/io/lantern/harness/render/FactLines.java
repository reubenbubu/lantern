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
    FactObject object = fact.object();
    String value = object.value();
    if (object.kind() == FactObject.Kind.STRING) {
      if (CLAIM_ID.matcher(value).matches()) {
        value = claims.apply(value);
      }
      line.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    } else {
      line.append(value);
    }
    ObjectNode attributes = fact.attributes().deepCopy();
    HIDDEN.forEach(attributes::remove);
    if (!attributes.isEmpty()) {
      line.append(' ').append(attributes);
    }
    Evidence evidence = fact.evidence();
    line.append(" (").append(evidence.repo());
    if (evidence.path() != null) {
      line.append(':').append(evidence.path());
    }
    line.append(')');
    return oneLine(Refs.clean(line.toString()));
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
