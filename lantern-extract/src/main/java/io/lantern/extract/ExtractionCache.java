package io.lantern.extract;

import io.lantern.core.fact.Fact;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The facts of extracted units, by a key covering every input of the unit (ADR 0092). Facts are
 * stored as extracted, valid from the snapshot they were extracted for; {@link Extractors} makes
 * them valid from the snapshot they are reused in.
 */
public interface ExtractionCache {

  /** The facts stored under the key, if any. */
  Optional<List<Fact>> get(ExtractorInfo extractor, String key);

  /** Stores the facts of a unit under the key, replacing any stored before. */
  void put(ExtractorInfo extractor, String key, List<Fact> facts);

  /**
   * Deletes the entries no run has used for a while; by default nothing.
   *
   * @return how many were deleted
   */
  default int prune() {
    return 0;
  }

  /** A cache that keeps nothing: every unit is extracted. */
  ExtractionCache NONE =
      new ExtractionCache() {
        @Override
        public Optional<List<Fact>> get(ExtractorInfo extractor, String key) {
          return Optional.empty();
        }

        @Override
        public void put(ExtractorInfo extractor, String key, List<Fact> facts) {}
      };

  /** A cache held in memory, for tests and single runs. */
  static ExtractionCache inMemory() {
    Map<String, List<Fact>> entries = new ConcurrentHashMap<>();
    return new ExtractionCache() {
      @Override
      public Optional<List<Fact>> get(ExtractorInfo extractor, String key) {
        return Optional.ofNullable(entries.get(extractor.name() + "\0" + key));
      }

      @Override
      public void put(ExtractorInfo extractor, String key, List<Fact> facts) {
        entries.put(extractor.name() + "\0" + key, List.copyOf(facts));
      }
    };
  }
}
