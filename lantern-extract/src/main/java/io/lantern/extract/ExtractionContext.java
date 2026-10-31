package io.lantern.extract;

import io.lantern.core.fact.Fact;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * What an extractor reads besides its unit (ADR 0092): the snapshot, every repository of it with
 * its build, the shared code model, and the facts of the extractors it requires.
 */
public final class ExtractionContext {

  private final long snapshot;
  private final List<RepoBuild> repos;
  private final Supplier<JavaAnalysis> code;
  private final Map<String, List<Fact>> required;
  private final Map<String, Object> memo;

  /**
   * @param snapshot the snapshot number the facts hold from
   * @param repos every repository of the snapshot, each at its commit with its build
   * @param code the code model of those repositories, built on first use
   * @param required the facts of the extractors the reading extractor requires, by name
   */
  public ExtractionContext(
      long snapshot,
      List<RepoBuild> repos,
      Supplier<JavaAnalysis> code,
      Map<String, List<Fact>> required) {
    this.snapshot = snapshot;
    this.repos = List.copyOf(Objects.requireNonNull(repos, "repos"));
    this.code = Objects.requireNonNull(code, "code");
    this.required = Map.copyOf(Objects.requireNonNull(required, "required"));
    this.memo = new java.util.concurrent.ConcurrentHashMap<>();
  }

  private ExtractionContext(ExtractionContext base, Map<String, List<Fact>> required) {
    this.snapshot = base.snapshot;
    this.repos = base.repos;
    this.code = base.code;
    this.required = Map.copyOf(required);
    this.memo = base.memo;
  }

  /** A context of the repositories with no required facts, its code model built when used. */
  public static ExtractionContext of(long snapshot, List<RepoBuild> repos) {
    return new ExtractionContext(snapshot, repos, memoized(repos), Map.of());
  }

  /** The code model of the repositories, built once, on the first call. */
  public static Supplier<JavaAnalysis> memoized(List<RepoBuild> repos) {
    List<RepoBuild> copy = List.copyOf(repos);
    return new Supplier<>() {
      private final java.util.concurrent.locks.ReentrantLock lock =
          new java.util.concurrent.locks.ReentrantLock();
      private volatile JavaAnalysis analysis;

      @Override
      public JavaAnalysis get() {
        JavaAnalysis built = analysis;
        if (built != null) {
          return built;
        }
        lock.lock();
        try {
          if (analysis == null) {
            analysis = JavaExtractor.analyze(copy);
          }
          return analysis;
        } finally {
          lock.unlock();
        }
      }
    };
  }

  /** The snapshot number the facts hold from. */
  public long snapshot() {
    return snapshot;
  }

  /** Every repository of the snapshot, each at its commit with its build. */
  public List<RepoBuild> repos() {
    return repos;
  }

  /** The code model of every repository, built once for all extractors that read it. */
  public JavaAnalysis code() {
    return code.get();
  }

  /**
   * The facts of a required extractor.
   *
   * @throws IllegalArgumentException if the extractor is not one the reader requires
   */
  public List<Fact> facts(String extractor) {
    List<Fact> facts = required.get(extractor);
    if (facts == null) {
      throw new IllegalArgumentException("Not a required extractor: " + extractor);
    }
    return facts;
  }

  /** This context with the facts of the given required extractors; it shares {@link #memo}. */
  ExtractionContext requiring(Map<String, List<Fact>> facts) {
    return new ExtractionContext(this, facts);
  }

  /**
   * A value computed once per run, shared by every unit and extractor of it: an extractor whose
   * repository units read the whole snapshot computes the snapshot's facts once and gives each unit
   * its own repository's.
   */
  @SuppressWarnings("unchecked")
  public synchronized <T> T memo(String key, java.util.function.Supplier<T> value) {
    Object found = memo.get(key);
    if (found == null) {
      found = value.get();
      memo.put(key, found);
    }
    return (T) found;
  }
}
