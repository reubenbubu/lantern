package io.lantern.summarize;

import io.lantern.harness.Packing;
import io.lantern.harness.Toml;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Summarize's requests of several types joined into one (#874): each type's item is renamed to its
 * place in the packet ({@code item.I3}) and its references numbered after the items before it, so a
 * reference never means two things in one request; the answer is split back into each item's answer
 * as its own request would have had it, {@code [I1]} with its own references.
 */
final class Packets implements Packing {

  static final Packets INSTANCE = new Packets();

  /** An item's turn: its TOML in {@code <items>}. */
  private static final Pattern TURN = Pattern.compile("(?s)<items>\\n(.*?)\\n?</items>\\s*");

  /** The item's own tables, {@code [item.I1]}, {@code [[item.I1.code]]}. */
  private static final Pattern TABLE =
      Pattern.compile("(?m)^(\\[{1,2})item\\." + Prompt.ITEM + "([.\\]])");

  /** A fact or summary listed with its reference, at the start of its line. */
  private static final Pattern LISTED = Pattern.compile("(?m)^([ \\t]*)\\[([FS])(\\d+)\\]");

  /** A code block's reference. */
  private static final Pattern CODE = Pattern.compile("(?m)^ref = \"E(\\d+)\"$");

  /** A reference to a contained summary in the item's ask. */
  private static final Pattern ASKED = Pattern.compile("(?m)^ask = .*$");

  private static final Pattern ASKED_REF = Pattern.compile("\\[S(\\d+)\\]");

  /** A reference as an answer cites it. */
  private static final Pattern CITED = Pattern.compile("([FES])(\\d+)");

  private static final Pattern RANGE =
      Pattern.compile("([FES])(\\d+)\\s*[-\u2013\u2014]\\s*([FES]?)(\\d+)");

  /** An item's table in a packed answer, which an answer that is not one document is cut at. */
  private static final Pattern ANSWERED = Pattern.compile("(?m)^[ \\t]*\\[(I\\d+)\\][ \\t]*$");

  private Packets() {}

  @Override
  public boolean packs(String turn) {
    // A merge shows the parts' sentences with their citations inline, which are not renumbered.
    return TURN.matcher(turn).matches() && !turn.contains("\nparts = ");
  }

  @Override
  public String purpose(int items) {
    return "summarize " + items + " types";
  }

  @Override
  public String join(List<String> turns) {
    StringBuilder out = new StringBuilder("<items>\n");
    Map<Character, Integer> offsets = new HashMap<>();
    for (int i = 0; i < turns.size(); i++) {
      String body = body(turns.get(i));
      Map<Character, Integer> counts = counts(body);
      out.append(renumbered(body, "I" + (i + 1), offsets).stripTrailing()).append("\n\n");
      counts.forEach((letter, n) -> offsets.merge(letter, n, Integer::sum));
    }
    return out.toString().stripTrailing() + "\n</items>";
  }

  @Override
  public List<String> split(String answer, List<String> turns) {
    JsonNode root;
    try {
      root = Toml.read(answer).root();
    } catch (Toml.Fault e) {
      root = byTable(answer);
    }
    List<String> parts = new ArrayList<>();
    Map<Character, Integer> offsets = new HashMap<>();
    for (int i = 0; i < turns.size(); i++) {
      Map<Character, Integer> counts = counts(body(turns.get(i)));
      JsonNode entry = root.get("I" + (i + 1));
      parts.add(entry == null || !entry.isObject() ? null : part(entry, offsets, counts));
      counts.forEach((letter, n) -> offsets.merge(letter, n, Integer::sum));
    }
    return parts;
  }

  /** The item's answer as its own request would have had it: {@code [I1]}, its own references. */
  private static String part(
      JsonNode entry, Map<Character, Integer> offsets, Map<Character, Integer> counts) {
    ObjectNode own = ((ObjectNode) entry).deepCopy();
    for (JsonNode sentence : own.path("sentences")) {
      if (sentence instanceof ObjectNode s && s.get("cites") instanceof ArrayNode cites) {
        for (int c = 0; c < cites.size(); c++) {
          if (cites.get(c).isString()) {
            cites.set(c, local(cites.get(c).asString(), offsets, counts));
          }
        }
      }
    }
    return written(own);
  }

  /**
   * An item's answer as TOML in the form the prompt shows: {@code [I1]}, its plain values, then a
   * {@code [[I1.sentences]]} table per sentence.
   */
  static String written(ObjectNode entry) {
    StringBuilder out = new StringBuilder("[").append(Prompt.ITEM).append("]\n");
    List<ObjectNode> tables = new ArrayList<>();
    String tablesKey = null;
    for (var field : entry.properties()) {
      JsonNode value = field.getValue();
      if (value.isArray() && !value.isEmpty() && value.get(0).isObject() && tablesKey == null) {
        tablesKey = field.getKey();
        value.forEach(v -> tables.add((ObjectNode) v));
      } else {
        out.append(Toml.key(field.getKey())).append(" = ").append(value(value)).append('\n');
      }
    }
    for (ObjectNode table : tables) {
      out.append("[[").append(Prompt.ITEM).append('.').append(Toml.key(tablesKey)).append("]]\n");
      for (var field : table.properties()) {
        out.append(Toml.key(field.getKey()))
            .append(" = ")
            .append(value(field.getValue()))
            .append('\n');
      }
    }
    return out.toString();
  }

  /** A value as TOML writes it inline. */
  private static String value(JsonNode value) {
    if (value.isString()) {
      return Toml.basic(value.asString());
    }
    if (value.isArray()) {
      List<String> items = new ArrayList<>();
      value.forEach(v -> items.add(value(v)));
      return "[" + String.join(", ", items) + "]";
    }
    if (value.isObject()) {
      List<String> fields = new ArrayList<>();
      value
          .properties()
          .forEach(f -> fields.add(Toml.key(f.getKey()) + " = " + value(f.getValue())));
      return "{" + String.join(", ", fields) + "}";
    }
    return value.toString();
  }

  /**
   * A reference of the packet as the item's own request numbers it; one that is not the item's is
   * kept as written and marked, so the checks reject it as one the item does not show. Brackets
   * around a reference are dropped, a range such as {@code F17-F18} is renumbered at both ends, and
   * a cite that names a reference in a form not read here is marked too, never left in the packet's
   * numbering, where it would name another item's fact.
   */
  static String local(
      String cite, Map<Character, Integer> offsets, Map<Character, Integer> counts) {
    String bare = cite.replaceAll("[\\[\\]]", "").strip();
    Matcher range = RANGE.matcher(bare);
    if (range.matches()) {
      char letter = range.group(1).charAt(0);
      String to = range.group(3).isEmpty() ? range.group(1) : range.group(3);
      if (to.charAt(0) == letter) {
        String from = own(letter, range.group(2), offsets, counts);
        String last = own(letter, range.group(4), offsets, counts);
        if (from != null && last != null) {
          return from + "-" + last;
        }
      }
      return bare + " (another item's)";
    }
    Matcher m = CITED.matcher(bare);
    if (m.matches()) {
      String own = own(m.group(1).charAt(0), m.group(2), offsets, counts);
      return own != null ? own : bare + " (another item's)";
    }
    // No reference in it at all: the checks reject it as written.
    return CITED.matcher(bare).find() ? bare + " (another item's)" : cite;
  }

  /** The item's own number of a reference of the packet, or null when it is another item's. */
  private static String own(
      char letter, String number, Map<Character, Integer> offsets, Map<Character, Integer> counts) {
    int local = Integer.parseInt(number) - offsets.getOrDefault(letter, 0);
    return local >= 1 && local <= counts.getOrDefault(letter, 0)
        ? letter + Integer.toString(local)
        : null;
  }

  /**
   * The tables of an answer that is not one TOML document, each read on its own where it can be.
   */
  private static JsonNode byTable(String answer) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    Matcher m = ANSWERED.matcher(answer);
    List<int[]> starts = new ArrayList<>();
    while (m.find()) {
      starts.add(new int[] {m.start()});
    }
    for (int i = 0; i < starts.size(); i++) {
      int from = starts.get(i)[0];
      int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : answer.length();
      try {
        Toml.read(answer.substring(from, to))
            .root()
            .properties()
            .forEach(e -> root.set(e.getKey(), e.getValue()));
      } catch (Toml.Fault e) {
        // Left out: the item is asked again alone.
      }
    }
    return root;
  }

  /** The item's TOML, without the {@code <items>} around it. */
  private static String body(String turn) {
    Matcher m = TURN.matcher(turn);
    if (!m.matches()) {
      throw new IllegalArgumentException("Not a Summarize item's turn");
    }
    return m.group(1);
  }

  /** The most of each reference letter the item lists. */
  private static Map<Character, Integer> counts(String body) {
    Map<Character, Integer> counts = new HashMap<>();
    Matcher listed = LISTED.matcher(body);
    while (listed.find()) {
      counts.merge(listed.group(2).charAt(0), Integer.parseInt(listed.group(3)), Math::max);
    }
    Matcher code = CODE.matcher(body);
    while (code.find()) {
      counts.merge('E', Integer.parseInt(code.group(1)), Math::max);
    }
    return counts;
  }

  /** The item's TOML under its packet ID, its references moved by the offsets. */
  private static String renumbered(String body, String id, Map<Character, Integer> offsets) {
    String text =
        TABLE
            .matcher(body)
            .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "item." + id + m.group(2)));
    text =
        LISTED
            .matcher(text)
            .replaceAll(
                m ->
                    Matcher.quoteReplacement(
                        m.group(1)
                            + "["
                            + m.group(2)
                            + moved(m.group(2), m.group(3), offsets)
                            + "]"));
    text =
        CODE.matcher(text)
            .replaceAll(
                m ->
                    Matcher.quoteReplacement("ref = \"E" + moved("E", m.group(1), offsets) + "\""));
    return ASKED
        .matcher(text)
        .replaceAll(
            line ->
                Matcher.quoteReplacement(
                    ASKED_REF
                        .matcher(line.group())
                        .replaceAll(
                            r ->
                                Matcher.quoteReplacement(
                                    "[S" + moved("S", r.group(1), offsets) + "]"))));
  }

  private static int moved(String letter, String number, Map<Character, Integer> offsets) {
    return Integer.parseInt(number) + offsets.getOrDefault(letter.charAt(0), 0);
  }
}
