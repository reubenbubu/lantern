package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The container lines that merge many links of one unit or frontend to one target (ADR 0103,
 * amendment for #810): a frontend's requests to its gateway, each its own claim with its own
 * intent. When no intent covers a majority of a line's links, Describe words the line as a whole
 * and stores its intent under {@link #key}; the composer labels the line with it.
 */
public final class MergedLines {

  /** The fewest distinct claims a line merges before Describe may word it as a whole. */
  public static final int MIN_CLAIMS = 3;

  private MergedLines() {}

  /**
   * A line of the container diagrams that merges several links.
   *
   * @param from the unit or frontend it leaves
   * @param relationship what the links do, as the diagram groups them
   * @param to the target
   * @param links the first fact of each distinct link it merges, in order
   */
  public record Merged(EntityId from, String relationship, EntityId to, List<Fact> links) {

    public Merged {
      links = List.copyOf(links);
    }

    /** The key its intent is stored under. */
    public String key() {
      return MergedLines.key(from, to, relationship);
    }
  }

  /** The key a merged line's intent is stored under: {@code <from>|<to>|<relationship>}. */
  public static String key(EntityId from, EntityId to, String relationship) {
    return from + "|" + to + "|" + relationship;
  }

  /**
   * The lines that leave one of the given units or frontends and merge at least {@link #MIN_CLAIMS}
   * distinct claims, sorted by their key.
   */
  public static List<Merged> of(List<Elements.Line> lines, Set<EntityId> ends) {
    Map<String, Merged> byKey = new TreeMap<>();
    Map<String, Map<String, Fact>> claims = new LinkedHashMap<>();
    for (Elements.Line line : lines) {
      if (!ends.contains(line.from()) || line.facts().isEmpty()) {
        continue;
      }
      String key = key(line.from(), line.to(), line.relationship());
      Fact link = line.facts().getFirst();
      claims.computeIfAbsent(key, k -> new LinkedHashMap<>()).putIfAbsent(claim(link), link);
      byKey.putIfAbsent(key, new Merged(line.from(), line.relationship(), line.to(), List.of()));
    }
    List<Merged> result = new ArrayList<>();
    byKey.forEach(
        (key, line) -> {
          List<Fact> links = new ArrayList<>(claims.get(key).values());
          if (links.size() >= MIN_CLAIMS) {
            result.add(new Merged(line.from(), line.relationship(), line.to(), links));
          }
        });
    return result;
  }

  /**
   * Whether the line is one Describe words as a whole: two or more of its links have intents that
   * differ, and none is said by more than half of them.
   *
   * @param intent the intent stored for a link, if any
   */
  public static boolean needsWording(
      Merged line, java.util.function.Function<Fact, Optional<String>> intent) {
    Map<String, Integer> said = new TreeMap<>();
    for (Fact link : line.links()) {
      intent.apply(link).ifPresent(text -> said.merge(text, 1, Integer::sum));
    }
    return said.size() > 1 && majority(said).isEmpty();
  }

  /** The key a link is told apart by: its claim ID, or its fact ID when it has none. */
  public static String claim(Fact link) {
    return link.claimId() != null ? link.claimId() : link.id();
  }

  /**
   * The phrase more than half of a line's phrased links say, if any.
   *
   * @param phrases each phrase with how many of the line's links say it
   */
  public static Optional<String> majority(Map<String, Integer> phrases) {
    int total = phrases.values().stream().mapToInt(Integer::intValue).sum();
    return phrases.entrySet().stream()
        .filter(e -> e.getValue() * 2 > total)
        .map(Map.Entry::getKey)
        .findFirst();
  }
}
