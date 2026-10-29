package io.lantern.core.repo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The title of a system that people gave none (ADR 0071), chosen by one fixed rule, in order:
 *
 * <ol>
 *   <li>the longest run of leading words that the first {@code # } heading of the root README of at
 *       least two repositories, and of at least half of the repositories that have one, starts
 *       with: words are split at whitespace, {@code -}, {@code —} and {@code :} and compared
 *       ignoring case, and the run is spelled as the first such repository (in sorted order) spells
 *       it. Of runs equally long, the one more headings share wins. {@code # APA-CRM Auth-Service}
 *       and {@code # APA-CRM — File-Service} give {@code APA-CRM};
 *   <li>with two or more repositories, the words all their names start with, where words are split
 *       at {@code -}, {@code _} and {@code .} and compared ignoring case, as the first name (in
 *       sorted order) spells them: {@code mediahub-api-gateway} and {@code mediahub-config-server}
 *       give {@code mediahub};
 *   <li>with one repository, its name;
 *   <li>otherwise the system's name.
 * </ol>
 */
public final class SystemTitle {

  private static final Pattern NAME_WORD = Pattern.compile("[^-_.]+");
  private static final Pattern HEADING_WORD = Pattern.compile("[^\\s\\-—:]+");
  private static final Pattern README =
      Pattern.compile("readme(\\.md|\\.markdown)?", Pattern.CASE_INSENSITIVE);

  private SystemTitle() {}

  /**
   * The title the rule gives without READMEs.
   *
   * @param system the system's name
   * @param repos the names of the system's repositories in the snapshot
   */
  public static String derive(String system, Collection<String> repos) {
    return derive(system, repos, Map.of());
  }

  /**
   * The title the rule gives.
   *
   * @param system the system's name
   * @param repos the names of the system's repositories in the snapshot
   * @param headings the first {@code # } heading of each repository's root README, by repository
   *     name, for those that have one
   */
  public static String derive(
      String system, Collection<String> repos, Map<String, String> headings) {
    Objects.requireNonNull(system, "system");
    Optional<String> fromHeadings = sharedHeading(headings);
    if (fromHeadings.isPresent()) {
      return fromHeadings.get();
    }
    List<String> names = new ArrayList<>(new TreeSet<>(repos));
    if (names.size() == 1) {
      return names.getFirst();
    }
    if (names.size() > 1) {
      int shared = sharedLeadingWords(names);
      if (shared > 0) {
        return upToWord(names.getFirst(), NAME_WORD, shared);
      }
    }
    return system;
  }

  /** The leading words the headings share, as the rule's first step picks them. */
  private static Optional<String> sharedHeading(Map<String, String> headings) {
    Map<String, List<String>> words = new TreeMap<>();
    headings.forEach(
        (repo, heading) -> {
          List<String> split = lower(words(heading, HEADING_WORD));
          if (!split.isEmpty()) {
            words.put(repo, split);
          }
        });
    int n = words.size();
    String best = null;
    int bestLength = 0;
    int bestCount = 0;
    for (Map.Entry<String, List<String>> entry : words.entrySet()) {
      List<String> own = entry.getValue();
      for (int k = 1; k <= own.size(); k++) {
        List<String> run = own.subList(0, k);
        int count = 0;
        for (List<String> other : words.values()) {
          if (other.size() >= k && other.subList(0, k).equals(run)) {
            count++;
          }
        }
        if (count < 2 || 2 * count < n) {
          break;
        }
        if (k > bestLength || (k == bestLength && count > bestCount)) {
          best = upToWord(headings.get(entry.getKey()).strip(), HEADING_WORD, k);
          bestLength = k;
          bestCount = count;
        }
      }
    }
    return Optional.ofNullable(best);
  }

  /** How many leading words all the names share, compared ignoring case. */
  private static int sharedLeadingWords(List<String> names) {
    List<String> common = lower(words(names.getFirst(), NAME_WORD));
    for (String name : names) {
      List<String> own = lower(words(name, NAME_WORD));
      int shared = 0;
      while (shared < common.size()
          && shared < own.size()
          && common.get(shared).equals(own.get(shared))) {
        shared++;
      }
      common = common.subList(0, shared);
    }
    return common.size();
  }

  /** The text up to the end of its {@code count}-th word. */
  private static String upToWord(String text, Pattern word, int count) {
    Matcher matcher = word.matcher(text);
    for (int i = 0; i < count; i++) {
      matcher.find();
    }
    return text.substring(0, matcher.end());
  }

  private static List<String> words(String text, Pattern word) {
    List<String> words = new ArrayList<>();
    Matcher matcher = word.matcher(text);
    while (matcher.find()) {
      words.add(matcher.group());
    }
    return words;
  }

  private static List<String> lower(List<String> words) {
    return words.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
  }

  /**
   * The first {@code # } heading of a README's text, without its marker and any closing {@code #}s,
   * if it has one.
   */
  public static Optional<String> heading(String readme) {
    for (String line : readme.lines().toList()) {
      if (line.startsWith("# ")) {
        String heading = line.substring(2).replaceFirst("\\s+#+\\s*$", "").strip();
        return heading.isEmpty() ? Optional.empty() : Optional.of(heading);
      }
    }
    return Optional.empty();
  }

  /**
   * The first {@code # } heading of the root README ({@code README}, {@code README.md} or {@code
   * README.markdown}, in any case) of a repository at a commit, read from git, if it has one.
   *
   * @param repo the top of the repository's working tree
   * @param commit the commit to read
   */
  public static Optional<String> readmeHeading(Path repo, String commit) {
    Git.Result files = Git.run(repo, "ls-tree", "--name-only", commit);
    if (!files.ok()) {
      return Optional.empty();
    }
    Optional<String> readme =
        files.out().lines().filter(name -> README.matcher(name).matches()).sorted().findFirst();
    if (readme.isEmpty()) {
      return Optional.empty();
    }
    Git.Result text = Git.run(repo, "show", commit + ":" + readme.get());
    return text.ok() ? heading(text.out()) : Optional.empty();
  }
}
