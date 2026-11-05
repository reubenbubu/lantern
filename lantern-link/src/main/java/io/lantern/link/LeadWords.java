package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.harness.render.FactLines;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of the system's links and a unit's facts a lead's conversation is shown (#860): those that
 * mention the lead's rarest word, the word of its ID (a path, header, setting or class) that the
 * fewest of them hold. A lead is shown its own facts and code in any case; the rest it looks up
 * with its tools.
 */
final class LeadWords {

  private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9-]{3,}");

  private static final Set<String> COMMON = Set.of("api", "internal", "http", "https");

  private LeadWords() {}

  /** The words of the leads' IDs that can pick the lines about them. */
  static List<String> of(List<String> leadIds) {
    Set<String> words = new LinkedHashSet<>();
    for (String id : leadIds) {
      String bare = id.replaceFirst(" \\(again\\)$", "");
      // lead:<kind>/<unit or place>/<what it is about>
      String[] parts = bare.split("/", 3);
      if (parts.length < 3) {
        // A lead about a whole unit: no word picks lines about it.
        continue;
      }
      String label = parts[2];
      label = URLDecoder.decode(label.replace("+", "%2B"), StandardCharsets.UTF_8);
      label = label.replaceFirst("^(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)\\s+", "");
      label = label.replaceFirst("^(header|parameter|property|config|env|environment):", "");
      label = label.replaceAll("\\{[^}]*}", " ");
      Matcher m = WORD.matcher(label);
      while (m.find()) {
        if (!COMMON.contains(m.group().toLowerCase(Locale.ROOT))) {
          words.add(m.group());
        }
      }
    }
    return List.copyOf(words);
  }

  /** The lines that hold the rarest of the words that any of them holds; none if none does. */
  static List<String> aboutLines(List<String> words, List<String> lines) {
    String rarest = rarest(words, lines);
    if (rarest == null) {
      return List.of();
    }
    String needle = rarest.toLowerCase(Locale.ROOT);
    return lines.stream().filter(l -> l.toLowerCase(Locale.ROOT).contains(needle)).toList();
  }

  /** The facts whose line holds the rarest of the words; none if none does. */
  static List<Fact> about(List<String> words, List<Fact> facts) {
    List<String> lines = facts.stream().map(FactLines::line).toList();
    String rarest = rarest(words, lines);
    if (rarest == null) {
      return List.of();
    }
    String needle = rarest.toLowerCase(Locale.ROOT);
    List<Fact> kept = new ArrayList<>();
    for (int i = 0; i < facts.size(); i++) {
      if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
        kept.add(facts.get(i));
      }
    }
    return kept;
  }

  private static String rarest(List<String> words, List<String> lines) {
    String best = null;
    long fewest = Long.MAX_VALUE;
    for (String word : words) {
      String needle = word.toLowerCase(Locale.ROOT);
      long n = lines.stream().filter(l -> l.toLowerCase(Locale.ROOT).contains(needle)).count();
      if (n > 0 && n < fewest) {
        fewest = n;
        best = word;
      }
    }
    return best;
  }
}
