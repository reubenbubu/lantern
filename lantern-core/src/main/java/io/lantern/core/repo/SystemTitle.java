package io.lantern.core.repo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The title of a system that people gave none (ADR 0071), chosen by one fixed rule from the names
 * of its repositories, in order:
 *
 * <ol>
 *   <li>with two or more repositories, the words all their names start with, where words are split
 *       at {@code -}, {@code _} and {@code .} and compared ignoring case, as the first name (in
 *       sorted order) spells them: {@code mediahub-api-gateway} and {@code mediahub-config-server}
 *       give {@code mediahub};
 *   <li>with one repository, its name;
 *   <li>otherwise the system's name.
 * </ol>
 */
public final class SystemTitle {

  private static final Pattern WORD = Pattern.compile("[^-_.]+");

  private SystemTitle() {}

  /**
   * The title the rule gives.
   *
   * @param system the system's name
   * @param repos the names of the system's repositories in the snapshot
   */
  public static String derive(String system, Collection<String> repos) {
    Objects.requireNonNull(system, "system");
    List<String> names = new ArrayList<>(new TreeSet<>(repos));
    if (names.size() == 1) {
      return names.getFirst();
    }
    if (names.size() > 1) {
      String first = names.getFirst();
      List<String> common = words(first);
      for (String name : names) {
        List<String> words = words(name);
        int shared = 0;
        while (shared < common.size()
            && shared < words.size()
            && common
                .get(shared)
                .toLowerCase(Locale.ROOT)
                .equals(words.get(shared).toLowerCase(Locale.ROOT))) {
          shared++;
        }
        common = common.subList(0, shared);
      }
      if (!common.isEmpty()) {
        // As the first name spells it, up to the end of the last common word.
        Matcher word = WORD.matcher(first);
        for (int i = 0; i < common.size(); i++) {
          word.find();
        }
        return first.substring(0, word.end());
      }
    }
    return system;
  }

  /** The name's words, the runs between {@code -}, {@code _} and {@code .}. */
  private static List<String> words(String name) {
    List<String> words = new ArrayList<>();
    Matcher word = WORD.matcher(name);
    while (word.find()) {
      words.add(word.group());
    }
    return words;
  }
}
