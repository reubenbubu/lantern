package io.lantern.summarize;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Chooses the source excerpts a type's prompt shows: the whole source file the type is declared in,
 * taken from the evidence of the type's {@code kind} fact, which is what citing it cites. A type
 * declared in two files (one per module) shows both.
 */
final class Excerpts {

  private Excerpts() {}

  /**
   * An excerpt of a source file.
   *
   * @param fact the ID of the fact whose evidence it comes from, which citing it cites
   * @param path the file, relative to its repository
   * @param start the first line shown, 1-based
   * @param end the last line shown
   * @param code the lines, joined with newlines
   */
  record Excerpt(String fact, String path, int start, int end, String code) {}

  /**
   * The excerpts for a unit, in path order; none unless it is a type.
   *
   * @param lines a file's lines for a fact's evidence, or empty if the file cannot be read or is
   *     not the version the fact was observed in
   */
  static List<Excerpt> of(SummaryNode node, Function<Evidence, Optional<List<String>>> lines) {
    if (node.level() != SummaryLevel.TYPE) {
      return List.of();
    }
    List<Fact> declarations = new ArrayList<>();
    for (Fact fact : node.facts()) {
      if (fact.subject().equals(node.entity())
          && fact.predicate().equals("kind")
          && fact.evidence().path() != null) {
        declarations.add(fact);
      }
    }
    // Ordered by path, then fact ID, so the same fact is chosen for a file on every run.
    declarations.sort(
        Comparator.comparing((Fact f) -> f.evidence().repo())
            .thenComparing(f -> f.evidence().path())
            .thenComparing(Fact::id));
    List<Excerpt> excerpts = new ArrayList<>();
    Set<String> shown = new HashSet<>();
    for (Fact fact : declarations) {
      Evidence evidence = fact.evidence();
      String file = evidence.repo() + '\u0000' + evidence.path();
      if (shown.contains(file)) {
        continue;
      }
      Optional<List<String>> content = lines.apply(evidence);
      if (content.isPresent() && !content.get().isEmpty()) {
        shown.add(file);
        List<String> text = content.get();
        excerpts.add(
            new Excerpt(fact.id(), evidence.path(), 1, text.size(), String.join("\n", text)));
      }
    }
    return List.copyOf(excerpts);
  }
}
