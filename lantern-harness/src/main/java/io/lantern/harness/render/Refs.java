package io.lantern.harness.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The references of one item, numbered within the item (knowledge-build epic, section 11.0, How
 * inputs are rendered): each input gets a letter and a number, such as {@code F3}, and the same
 * input added twice keeps its first reference. An item's rendering holds its references as tokens,
 * so it is the same text whichever items share its request; laying out a request numbers the
 * citable references of its items one after another ({@link #resolve}), so a reference never means
 * two things in one request, and the item keys ({@code C}, {@code R}, {@code K}, {@code W}, {@code
 * G}) stay as they are, since each item's answer holds its own.
 *
 * <p>Not thread-safe; one per item.
 */
public final class Refs {

  /** The letters numbered across a request's items: what an answer cites. */
  public static final Set<Character> CITABLE = Set.of('F', 'E', 'S', 'A');

  private static final char OPEN = '\u0001';
  private static final char CLOSE = '\u0002';
  private static final Pattern TOKEN = Pattern.compile(OPEN + "([A-Z])([0-9]+)" + CLOSE);

  private final Map<Character, Integer> counts = new LinkedHashMap<>();
  private final Map<String, String> byKey = new LinkedHashMap<>();
  private final Map<String, String> lines = new LinkedHashMap<>();
  private final Map<String, Object> things = new LinkedHashMap<>();

  /**
   * The token of an input, adding it under the next number of its letter the first time.
   *
   * @param letter what kind of input it is, such as {@code F} for a fact line
   * @param key what identifies the input, so it is added once, such as a fact's claim ID
   * @param thing what the reference stands for, such as the fact
   * @param line the text a citation of it shows the checks, such as the fact line
   * @return the reference's token, for the rendering
   */
  public String add(char letter, String key, Object thing, String line) {
    String unique = letter + "\u0000" + key;
    String ref = byKey.get(unique);
    if (ref == null) {
      int number = counts.merge(letter, 1, Integer::sum);
      ref = String.valueOf(letter) + number;
      byKey.put(unique, ref);
      lines.put(ref, Objects.requireNonNull(line, "line"));
      things.put(ref, thing);
    }
    return token(ref);
  }

  /** The token of a reference already numbered, such as {@code C2}. */
  public static String token(String ref) {
    return OPEN + ref + CLOSE;
  }

  /** What the item-local reference stands for, if it was added. */
  public Optional<Object> thing(String ref) {
    return Optional.ofNullable(things.get(ref));
  }

  /** Each item-local reference's line, in the order added. */
  public Map<String, String> lines() {
    return Map.copyOf(lines);
  }

  /** How many references of the letter the item has. */
  public int count(char letter) {
    return counts.getOrDefault(letter, 0);
  }

  /**
   * The item-local references of each item in a request, as the request numbers them.
   *
   * @param texts each item's text, with its references as the request numbers them
   * @param global each item's reference lines, by the reference the request shows
   * @param toLocal each item's request reference to its own
   */
  public record Resolved(
      List<String> texts, List<Map<String, String>> global, List<Map<String, String>> toLocal) {

    /** Every reference of the request, by the reference it shows, with its line. */
    public Map<String, String> references() {
      Map<String, String> all = new LinkedHashMap<>();
      global.forEach(all::putAll);
      return all;
    }
  }

  /**
   * Numbers the items of a request: the citable references of each item after those of the items
   * before it, other references as they are.
   *
   * @param renderings each item's rendering, with its references as tokens
   * @param refs each item's references
   */
  public static Resolved resolve(List<String> renderings, List<Refs> refs) {
    if (renderings.size() != refs.size()) {
      throw new IllegalArgumentException("One Refs per rendering");
    }
    Map<Character, Integer> offsets = new LinkedHashMap<>();
    List<String> texts = new ArrayList<>();
    List<Map<String, String>> global = new ArrayList<>();
    List<Map<String, String>> toLocal = new ArrayList<>();
    for (int i = 0; i < renderings.size(); i++) {
      Refs item = refs.get(i);
      Map<Character, Integer> offset = Map.copyOf(offsets);
      texts.add(shift(renderings.get(i), offset));
      Map<String, String> lines = new LinkedHashMap<>();
      Map<String, String> back = new LinkedHashMap<>();
      item.lines.forEach(
          (ref, line) -> {
            String shown = shifted(ref, offset);
            lines.put(shown, line);
            back.put(shown, ref);
          });
      global.add(lines);
      toLocal.add(back);
      for (char letter : CITABLE) {
        offsets.merge(letter, item.count(letter), Integer::sum);
      }
    }
    return new Resolved(texts, global, toLocal);
  }

  /** A single item's rendering with its tokens as plain references. */
  public static String plain(String rendering) {
    return shift(rendering, Map.of());
  }

  private static String shift(String text, Map<Character, Integer> offset) {
    Matcher m = TOKEN.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(shifted(m.group(1) + m.group(2), offset)));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String shifted(String ref, Map<Character, Integer> offset) {
    char letter = ref.charAt(0);
    if (!CITABLE.contains(letter)) {
      return ref;
    }
    int number = Integer.parseInt(ref.substring(1)) + offset.getOrDefault(letter, 0);
    return String.valueOf(letter) + number;
  }

  /** The text without the characters tokens are made of, for code and values shown raw. */
  public static String clean(String text) {
    return text.indexOf(OPEN) < 0 && text.indexOf(CLOSE) < 0
        ? text
        : text.replace(String.valueOf(OPEN), "").replace(String.valueOf(CLOSE), "");
  }
}
