package io.lantern.harness;

import io.lantern.harness.verify.CheckedText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the earlier wording of a re-asked item's texts whose evidence did not change (#722, ADR
 * 0126): early cutoff at the level of one text. When an item is asked again, each text whose
 * earlier version cited only references that the item still shows, word for word, keeps that
 * earlier text; only texts resting on what changed are worded anew. The items that show the answer
 * then see the same texts and keep their fingerprints, so a reworded answer no longer re-asks
 * everything after it.
 *
 * <p>A stored text records what it rests on in a {@code carry} field beside its {@code text} and
 * {@code cites}: its field, a hash of what it is about, and a hash of each cited reference's line.
 * Readers of the stored answer read {@code text} and {@code cites} and ignore it. A text that cites
 * nothing the item shows (no citation, or a file range) records nothing and is never carried.
 */
public final class CarryForward {

  /** The field of a stored text that records what it rests on. */
  public static final String KEY = "carry";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private CarryForward() {}

  /**
   * An earlier text and what it rested on.
   *
   * @param text the text
   * @param about the hash of the name of what it is about
   * @param basis the hash of each cited reference's line, in the order cited
   * @param cites the references it cited, as stored
   */
  public record Earlier(String text, String about, List<String> basis, List<String> cites) {

    public Earlier {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(about, "about");
      basis = List.copyOf(basis);
      cites = List.copyOf(cites);
    }
  }

  /** The texts of a stored answer that recorded what they rest on, by field. */
  public static Map<String, Earlier> earlier(String stored) {
    Map<String, Earlier> earlier = new LinkedHashMap<>();
    if (stored == null) {
      return earlier;
    }
    JsonNode root;
    try {
      root = JSON.readTree(stored);
    } catch (RuntimeException e) {
      return earlier;
    }
    collect(root, earlier);
    return earlier;
  }

  private static void collect(JsonNode node, Map<String, Earlier> earlier) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      JsonNode carry = node.get(KEY);
      if (carry != null
          && carry.isObject()
          && node.path("text").isString()
          && carry.path("field").isString()) {
        List<String> basis = new ArrayList<>();
        carry.path("basis").forEach(b -> basis.add(b.asString()));
        List<String> cites = new ArrayList<>();
        node.path("cites").forEach(c -> cites.add(c.asString()));
        earlier.putIfAbsent(
            carry.get("field").asString(),
            new Earlier(
                node.get("text").asString(), carry.path("about").asString(""), basis, cites));
      }
      node.properties().forEach(e -> collect(e.getValue(), earlier));
    } else if (node.isArray()) {
      node.forEach(child -> collect(child, earlier));
    }
  }

  /**
   * The texts, each that may be carried and whose earlier version rests only on lines the item
   * still shows taking that earlier text, with its citations as the item numbers those lines now.
   *
   * @param texts the texts the model wrote this time, accepted by the checks
   * @param earlier the earlier texts by field ({@link #earlier})
   * @param references the item's references as this request numbers them, with their lines
   * @param carries which texts the role lets keep their earlier wording
   * @return the texts to store, in the same order
   */
  public static List<CheckedText> merge(
      List<CheckedText> texts,
      Map<String, Earlier> earlier,
      Map<String, String> references,
      Predicate<CheckedText> carries) {
    return merge(texts, earlier, references, carries, CheckedText::subjectName);
  }

  /**
   * As {@link #merge(List, Map, Map, Predicate)}, with what each text is about named by the role: a
   * text is kept only for the same thing, such as the same line, whatever key the item gives it.
   *
   * @param about the stable name of what a text is about
   */
  public static List<CheckedText> merge(
      List<CheckedText> texts,
      Map<String, Earlier> earlier,
      Map<String, String> references,
      Predicate<CheckedText> carries,
      Function<CheckedText, String> about) {
    if (earlier.isEmpty()) {
      return texts;
    }
    Map<String, String> refByLine = refByLine(references);
    Map<String, String> lines = hashes(references);
    List<CheckedText> merged = new ArrayList<>(texts.size());
    for (CheckedText text : texts) {
      Earlier before = earlier.get(text.field());
      List<String> cites =
          before == null ? null : cites(before, about.apply(text), refByLine, lines);
      merged.add(
          cites == null || !carries.test(text)
              ? text
              : new CheckedText(
                  text.field(),
                  text.kind(),
                  before.text(),
                  cites,
                  text.words(),
                  text.subjectName(),
                  text.names()));
    }
    return merged;
  }

  /**
   * The sentences of a prose answer, such as a summary, merged with its earlier ones: each earlier
   * sentence whose cited lines are all still shown is kept as it was, in its earlier order; a new
   * sentence is added, at its own position, only if it cites a line no kept sentence cites, such as
   * one that changed. Fields are numbered again ({@code <prefix>.1}, ...).
   *
   * @param texts the sentences written this time and accepted, fields {@code <prefix>.<n>}
   * @param earlier the earlier texts by field ({@link #earlier}); those of the prefix are used
   * @param references the item's references with their lines
   * @param prefix the field prefix of a sentence, such as {@code sentences}
   * @param most the most sentences kept: past it, earlier sentences are left out from the last,
   *     before any written this time
   * @return the sentences to keep; those written this time when none earlier may be kept
   */
  public static List<CheckedText> sentences(
      List<CheckedText> texts,
      Map<String, Earlier> earlier,
      Map<String, String> references,
      String prefix,
      int most) {
    if (texts.isEmpty() || earlier.isEmpty()) {
      return texts;
    }
    Map<String, String> refByLine = refByLine(references);
    Map<String, String> lines = hashes(references);
    CheckedText model = texts.getFirst();
    List<Map.Entry<Integer, Earlier>> before = new ArrayList<>();
    earlier.forEach(
        (field, text) -> {
          Integer n = position(field, prefix);
          if (n != null) {
            before.add(Map.entry(n, text));
          }
        });
    before.sort(Map.Entry.comparingByKey());
    List<CheckedText> merged = new ArrayList<>();
    java.util.Set<String> covered = new java.util.HashSet<>();
    for (Map.Entry<Integer, Earlier> entry : before) {
      List<String> cites = cites(entry.getValue(), model.subjectName(), refByLine, lines);
      if (cites != null) {
        merged.add(
            new CheckedText(
                "",
                model.kind(),
                entry.getValue().text(),
                cites,
                model.words(),
                model.subjectName(),
                java.util.Set.of()));
        covered.addAll(entry.getValue().basis());
      }
    }
    if (merged.isEmpty()) {
      return texts;
    }
    List<CheckedText> kept = new ArrayList<>(merged);
    java.util.Set<String> said = new java.util.HashSet<>();
    merged.forEach(t -> said.add(t.text()));
    for (CheckedText text : texts) {
      // A sentence that repeats a kept one word for word adds nothing.
      boolean adds =
          !said.contains(text.text())
              && text.cites().stream()
                  .anyMatch(
                      c ->
                          !references.containsKey(c)
                              || !covered.contains(Fingerprints.sha256(references.get(c))));
      if (adds) {
        Integer n = position(text.field(), prefix);
        int at = n == null ? merged.size() : Math.min(Math.max(n - 1, 0), merged.size());
        merged.add(at, text);
      }
    }
    for (int i = kept.size() - 1; i >= 0 && merged.size() > most; i--) {
      merged.remove(kept.get(i));
    }
    while (merged.size() > most) {
      merged.removeLast();
    }
    List<CheckedText> numbered = new ArrayList<>(merged.size());
    for (int i = 0; i < merged.size(); i++) {
      CheckedText text = merged.get(i);
      numbered.add(
          new CheckedText(
              prefix + "." + (i + 1),
              text.kind(),
              text.text(),
              text.cites(),
              text.words(),
              text.subjectName(),
              text.names()));
    }
    return numbered;
  }

  private static Integer position(String field, String prefix) {
    if (!field.startsWith(prefix + ".")) {
      return null;
    }
    try {
      return Integer.parseInt(field.substring(prefix.length() + 1));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Texts as stored beside an answer that keeps them in another form, such as a summary's joined
   * text: a JSON array of {@code text}, {@code cites} and what each rests on ({@link #mark}).
   */
  public static String record(List<CheckedText> texts, Map<String, String> references) {
    ObjectNode root = JSON.createObjectNode();
    ArrayNode array = root.putArray("texts");
    for (CheckedText text : texts) {
      ObjectNode node = array.addObject();
      node.put("text", text.text());
      ArrayNode cites = node.putArray("cites");
      text.cites().forEach(cites::add);
    }
    return JSON.readTree(mark(JSON.writeValueAsString(root), texts, references))
        .get("texts")
        .toString();
  }

  /**
   * The earlier text's citations as the item numbers them now, or null if it may not be kept: a
   * reference that still shows the same line keeps its name; otherwise the first that shows it.
   */
  private static List<String> cites(
      Earlier before, String about, Map<String, String> refByLine, Map<String, String> lines) {
    if (before.basis().isEmpty() || !before.about().equals(Fingerprints.sha256(about))) {
      return null;
    }
    List<String> cites = new ArrayList<>();
    for (int i = 0; i < before.basis().size(); i++) {
      String line = before.basis().get(i);
      String same = i < before.cites().size() ? before.cites().get(i) : null;
      if (same != null && line.equals(lines.get(same))) {
        cites.add(same);
        continue;
      }
      String ref = refByLine.get(line);
      if (ref == null) {
        return null;
      }
      cites.add(ref);
    }
    return cites;
  }

  /** Each reference's line hash, by reference. */
  private static Map<String, String> hashes(Map<String, String> references) {
    Map<String, String> hashes = new HashMap<>();
    references.forEach((ref, line) -> hashes.put(ref, Fingerprints.sha256(line)));
    return hashes;
  }

  /** For each line hash, the first reference that shows it, in reference order. */
  private static Map<String, String> refByLine(Map<String, String> references) {
    Map<String, String> refByLine = new HashMap<>();
    references.keySet().stream()
        .sorted(CarryForward::byRef)
        .forEach(ref -> refByLine.putIfAbsent(Fingerprints.sha256(references.get(ref)), ref));
    return refByLine;
  }

  /** References in order: by letter, then by number, such as F2 before F10. */
  private static int byRef(String a, String b) {
    String la = a.replaceAll("[0-9]+$", "");
    String lb = b.replaceAll("[0-9]+$", "");
    if (!la.equals(lb)) {
      return la.compareTo(lb);
    }
    String na = a.substring(la.length());
    String nb = b.substring(lb.length());
    if (na.isEmpty() || nb.isEmpty()) {
      return a.compareTo(b);
    }
    return Long.compare(Long.parseLong(na), Long.parseLong(nb));
  }

  /**
   * The stored answer with each of its texts recording what it rests on: every object with a {@code
   * text} and {@code cites} whose text is one of the texts and whose every citation is a reference
   * the item shows.
   *
   * @param stored the answer as the role stores it
   * @param texts the texts stored
   * @param references the item's references as this request numbers them, with their lines
   * @return the answer to store
   */
  public static String mark(
      String stored, List<CheckedText> texts, Map<String, String> references) {
    return mark(stored, texts, references, CheckedText::subjectName);
  }

  /** As {@link #mark(String, List, Map)}, with what each text is about named by the role. */
  public static String mark(
      String stored,
      List<CheckedText> texts,
      Map<String, String> references,
      Function<CheckedText, String> about) {
    if (stored == null || texts.isEmpty()) {
      return stored;
    }
    JsonNode root;
    try {
      root = JSON.readTree(stored);
    } catch (RuntimeException e) {
      return stored;
    }
    List<CheckedText> left = new ArrayList<>(texts);
    boolean[] marked = {false};
    mark(root, left, references, about, marked);
    return marked[0] ? JSON.writeValueAsString(root) : stored;
  }

  private static void mark(
      JsonNode node,
      List<CheckedText> left,
      Map<String, String> references,
      Function<CheckedText, String> about,
      boolean[] marked) {
    if (node == null) {
      return;
    }
    if (node instanceof ObjectNode object) {
      if (object.path("text").isString() && object.path("cites").isArray()) {
        String text = object.get("text").asString();
        int cited = object.get("cites").size();
        for (int i = 0; i < left.size(); i++) {
          CheckedText candidate = left.get(i);
          if (candidate.text().equals(text) && candidate.cites().size() == cited) {
            left.remove(i);
            List<String> basis = basis(candidate, references);
            if (basis != null) {
              ObjectNode carry = object.putObject(KEY);
              carry.put("field", candidate.field());
              carry.put("about", Fingerprints.sha256(about.apply(candidate)));
              ArrayNode lines = carry.putArray("basis");
              basis.forEach(lines::add);
              marked[0] = true;
            }
            break;
          }
        }
      }
      for (var entry : new ArrayList<>(object.properties())) {
        if (!entry.getKey().equals(KEY)) {
          mark(entry.getValue(), left, references, about, marked);
        }
      }
    } else if (node.isArray()) {
      node.forEach(child -> mark(child, left, references, about, marked));
    }
  }

  /** The hash of each cited reference's line, or null if it cites nothing or a line not shown. */
  private static List<String> basis(CheckedText text, Map<String, String> references) {
    if (text.cites().isEmpty()) {
      return null;
    }
    List<String> basis = new ArrayList<>();
    for (String cite : text.cites()) {
      String line = references.get(cite);
      if (line == null) {
        return null;
      }
      basis.add(Fingerprints.sha256(line));
    }
    return basis;
  }
}
