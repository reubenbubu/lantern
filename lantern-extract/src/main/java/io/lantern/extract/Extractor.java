package io.lantern.extract;

import io.lantern.core.fact.Fact;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * An extractor: reports what the files of a repository say, as facts, for one unit at a time (ADR
 * 0092). Extractors are found through {@link java.util.ServiceLoader} and run by {@link Extractors}
 * in the order their {@link #requires} give; each unit's facts are cached under a key covering
 * every input of the unit.
 */
public interface Extractor {

  /** The extractor's name and version, which are those of its facts' origin. */
  ExtractorInfo info();

  /** The names of the extractors whose facts this one reads, through {@link ExtractionContext}. */
  default Set<String> requires() {
    return Set.of();
  }

  /**
   * The order this extractor gives the facts of several repositories in, which the facts of its
   * units are merged in; by default {@link Extractors#ORDER}.
   */
  default Comparator<Fact> order() {
    return Extractors.ORDER;
  }

  /** The unit this extractor's facts are extracted and cached by. */
  CacheUnit unit();

  /** Whether this extractor reads the repository at all; by default every repository. */
  default boolean appliesTo(RepoBuild repo) {
    return true;
  }

  /**
   * The files of the repository this extractor extracts one at a time; used only by extractors
   * whose unit is {@link CacheUnit#FILE}.
   */
  default List<String> files(ExtractionContext context, RepoBuild repo) {
    return List.of();
  }

  /**
   * Emits the facts of one unit: for {@link CacheUnit#SNAPSHOT} every repository it applies to, for
   * {@link CacheUnit#REPOSITORY} one repository, for {@link CacheUnit#FILE} one file of one
   * repository. The facts must be of this extractor's origin and valid from the snapshot.
   */
  void extract(ExtractionContext context, ExtractionUnit unit, ClaimSink sink);
}
