package io.lantern.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Turns Lantern IDs into a report spec's lowercase kebab-case IDs ({@code ^[a-z0-9][a-z0-9-]*$}),
 * and keeps the mapping both ways: {@code service:orders} becomes {@code service-orders}. Lantern
 * IDs are lowercased and every run of other characters becomes one {@code -}. One namespace, such
 * as a document's elements or one page's sections, holds each ID once: two Lantern IDs that would
 * become the same ID are an error, not something to guess around (#112).
 */
final class ReportSpecIds {

  private static final Pattern OTHER = Pattern.compile("[^a-z0-9]+");

  private final String what;
  private final Map<String, String> byLantern = new HashMap<>();
  private final SortedMap<String, String> byReport = new TreeMap<>();

  /**
   * @param what what the IDs name, for error messages, such as {@code element}
   */
  ReportSpecIds(String what) {
    this.what = what;
  }

  /** The kebab-case form of a Lantern ID, without registering it. */
  static String kebab(String lanternId) {
    String id = OTHER.matcher(lanternId.toLowerCase(Locale.ROOT)).replaceAll("-");
    int start = 0;
    int end = id.length();
    while (start < end && id.charAt(start) == '-') {
      start++;
    }
    while (end > start && id.charAt(end - 1) == '-') {
      end--;
    }
    return id.substring(start, end);
  }

  /**
   * The report ID of a Lantern ID, registered in this namespace.
   *
   * @throws IllegalArgumentException if it has no letter or digit, or another Lantern ID already
   *     became the same report ID
   */
  String id(String lanternId) {
    String known = byLantern.get(lanternId);
    if (known != null) {
      return known;
    }
    String id = kebab(lanternId);
    if (id.isEmpty()) {
      throw new IllegalArgumentException(
          "The " + what + " ID '" + lanternId + "' has no letter or digit for the report spec");
    }
    String other = byReport.putIfAbsent(id, lanternId);
    if (other != null) {
      throw new IllegalArgumentException(
          "The "
              + what
              + " IDs '"
              + other
              + "' and '"
              + lanternId
              + "' would both become '"
              + id
              + "' in the report spec");
    }
    byLantern.put(lanternId, id);
    return id;
  }

  /** The report ID a Lantern ID became, or {@code null} if it was not registered. */
  String find(String lanternId) {
    return byLantern.get(lanternId);
  }

  /** The mapping back: each report ID to the Lantern ID it came from, sorted by report ID. */
  SortedMap<String, String> lanternIds() {
    return Collections.unmodifiableSortedMap(byReport);
  }
}
