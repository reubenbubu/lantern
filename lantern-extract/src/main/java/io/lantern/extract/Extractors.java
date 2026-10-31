package io.lantern.extract;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Validity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the extractors over a snapshot (ADR 0092): in the order their requirements give, those whose
 * requirements are met at the same time, each unit from the cache when its key is stored there.
 */
public final class Extractors {

  /** The order every extractor gives its facts in, and the runner the facts of several units. */
  public static final Comparator<Fact> ORDER =
      Comparator.comparing((Fact f) -> f.subject().toString())
          .thenComparing(Fact::predicate)
          .thenComparing(f -> f.object().value().toString())
          .thenComparing(Fact::id);

  /** {@link #ORDER} with the evidence's path and first line and the attributes before the ID. */
  public static final Comparator<Fact> ORDER_BY_LOCATION =
      Comparator.comparing((Fact f) -> f.subject().toString())
          .thenComparing(Fact::predicate)
          .thenComparing(f -> f.object().value().toString())
          .thenComparing(f -> f.evidence().path())
          .thenComparing(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
          .thenComparing(f -> f.attributes().toString())
          .thenComparing(Fact::id);

  private final List<Extractor> extractors;
  private final ExtractionCache cache;

  /**
   * @param extractors the extractors, in any order
   * @param cache where units' facts are kept between runs
   * @throws IllegalArgumentException if two extractors share a name, a requirement is missing, or
   *     the requirements form a cycle
   */
  public Extractors(Collection<? extends Extractor> extractors, ExtractionCache cache) {
    this.extractors = order(extractors);
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  /** The extractors found through {@link ServiceLoader}, in the order of their requirements. */
  public static List<Extractor> load() {
    List<Extractor> found = new ArrayList<>();
    ServiceLoader.load(Extractor.class, Extractor.class.getClassLoader()).forEach(found::add);
    return order(found);
  }

  /** The extractors run, in the order of their requirements. */
  public List<Extractor> extractors() {
    return extractors;
  }

  /**
   * Sorts the extractors so that each comes after those it requires; ties go by name.
   *
   * @throws IllegalArgumentException if two share a name, a requirement is missing, or the
   *     requirements form a cycle
   */
  static List<Extractor> order(Collection<? extends Extractor> extractors) {
    Map<String, Extractor> byName = new TreeMap<>();
    for (Extractor extractor : extractors) {
      if (byName.put(extractor.info().name(), extractor) != null) {
        throw new IllegalArgumentException("Two extractors named " + extractor.info().name());
      }
    }
    for (Extractor extractor : byName.values()) {
      for (String required : extractor.requires()) {
        if (!byName.containsKey(required)) {
          throw new IllegalArgumentException(
              extractor.info().name() + " requires the missing extractor " + required);
        }
      }
    }
    List<Extractor> sorted = new ArrayList<>();
    Set<String> placed = new java.util.HashSet<>();
    while (sorted.size() < byName.size()) {
      List<Extractor> ready =
          byName.values().stream()
              .filter(e -> !placed.contains(e.info().name()))
              .filter(e -> placed.containsAll(e.requires()))
              .toList();
      if (ready.isEmpty()) {
        throw new IllegalArgumentException(
            "The requirements of these extractors form a cycle: "
                + byName.keySet().stream().filter(n -> !placed.contains(n)).toList());
      }
      sorted.addAll(ready);
      ready.forEach(e -> placed.add(e.info().name()));
    }
    return List.copyOf(sorted);
  }

  /**
   * Extracts the snapshot's repositories.
   *
   * @param snapshot the snapshot number the facts hold from
   * @param repos every repository of the snapshot, each at its commit with its build
   * @return each extractor's facts, in the order of the extractors, with how many units were
   *     extracted and how many read from the cache
   */
  public Result run(long snapshot, List<RepoBuild> repos) {
    ExtractionContext base = ExtractionContext.of(snapshot, repos);
    UnitKeys keys = new UnitKeys();
    Map<String, List<Fact>> done = new ConcurrentHashMap<>();
    AtomicInteger extracted = new AtomicInteger();
    AtomicInteger cached = new AtomicInteger();
    List<Extractor> pending = new ArrayList<>(extractors);
    // Platform threads, one per extractor of a wave: extractors block on the shared code model and
    // on git, whose output git reads on virtual threads, which must find a free carrier.
    try (ExecutorService executor = Executors.newCachedThreadPool()) {
      while (!pending.isEmpty()) {
        List<Extractor> ready =
            pending.stream().filter(e -> done.keySet().containsAll(e.requires())).toList();
        Map<String, Future<List<Fact>>> running = new LinkedHashMap<>();
        for (Extractor extractor : ready) {
          Map<String, List<Fact>> required = new TreeMap<>();
          extractor.requires().forEach(name -> required.put(name, done.get(name)));
          ExtractionContext context = base.requiring(required);
          running.put(
              extractor.info().name(),
              executor.submit(() -> runOne(extractor, context, keys, required, extracted, cached)));
        }
        for (Map.Entry<String, Future<List<Fact>>> entry : running.entrySet()) {
          done.put(entry.getKey(), join(entry.getValue()));
        }
        pending.removeAll(ready);
      }
    }
    Map<String, List<Fact>> byExtractor = new LinkedHashMap<>();
    extractors.forEach(e -> byExtractor.put(e.info().name(), done.get(e.info().name())));
    return new Result(byExtractor, extracted.get(), cached.get());
  }

  private List<Fact> runOne(
      Extractor extractor,
      ExtractionContext context,
      UnitKeys keys,
      Map<String, List<Fact>> required,
      AtomicInteger extracted,
      AtomicInteger cached) {
    List<Fact> requiredFacts = required.values().stream().flatMap(List::stream).toList();
    Validity validity = Validity.from(context.snapshot());
    Map<String, Fact> facts = new LinkedHashMap<>();
    List<ExtractionUnit> units = units(extractor, context);
    for (ExtractionUnit unit : units) {
      String key = keys.key(extractor, unit, context.repos(), requiredFacts);
      List<Fact> unitFacts = cache.get(extractor.info(), key).orElse(null);
      if (unitFacts != null) {
        cached.incrementAndGet();
      } else {
        List<Fact> emitted = new ArrayList<>();
        extractor.extract(context, unit, fact -> emitted.add(checked(extractor, fact)));
        cache.put(extractor.info(), key, emitted);
        extracted.incrementAndGet();
        unitFacts = emitted;
      }
      for (Fact fact : unitFacts) {
        facts.putIfAbsent(fact.id(), fact.stored(fact.claimId(), validity));
      }
    }
    // One unit keeps the extractor's own order; the facts of several are merged in the order the
    // extractor gives the facts of several repositories.
    return units.size() == 1
        ? List.copyOf(facts.values())
        : facts.values().stream().sorted(extractor.order()).toList();
  }

  /** The units of the extractor in the snapshot. */
  private static List<ExtractionUnit> units(Extractor extractor, ExtractionContext context) {
    List<RepoBuild> applicable = context.repos().stream().filter(extractor::appliesTo).toList();
    return switch (extractor.unit()) {
      case SNAPSHOT -> List.of(new ExtractionUnit(applicable, null));
      case REPOSITORY ->
          applicable.stream().map(repo -> new ExtractionUnit(List.of(repo), null)).toList();
      case FILE ->
          applicable.stream()
              .flatMap(
                  repo ->
                      extractor.files(context, repo).stream()
                          .map(path -> new ExtractionUnit(List.of(repo), path)))
              .toList();
    };
  }

  private static Fact checked(Extractor extractor, Fact fact) {
    if (!fact.origin().name().equals(extractor.info().name())
        || !fact.origin().version().equals(extractor.info().version())) {
      throw new IllegalArgumentException(
          extractor.info().name()
              + " emitted a fact of origin "
              + fact.origin().name()
              + " "
              + fact.origin().version());
    }
    return fact;
  }

  private static List<Fact> join(Future<List<Fact>> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while extracting", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(e.getCause());
    }
  }

  /**
   * What a run extracted.
   *
   * @param byExtractor each extractor's facts, in the order of the extractors
   * @param extracted how many units were extracted
   * @param cached how many units were read from the cache
   */
  public record Result(Map<String, List<Fact>> byExtractor, int extracted, int cached) {

    public Result {
      byExtractor = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byExtractor));
    }

    /** Every fact, extractor after extractor. */
    public List<Fact> facts() {
      return byExtractor.values().stream().flatMap(List::stream).toList();
    }

    /** The facts of one extractor, empty if it did not run. */
    public List<Fact> facts(String extractor) {
      return byExtractor.getOrDefault(extractor, List.of());
    }
  }
}
