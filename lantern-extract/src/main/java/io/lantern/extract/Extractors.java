package io.lantern.extract;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
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

  private static final Set<String> BUILT_IN =
      BuiltInTypes.definitions().stream()
          .map(VocabularyType::name)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());

  private final List<Extractor> extractors;
  private final ExtractionCache cache;
  private final Vocabulary vocabulary;

  /**
   * @param extractors the extractors, in any order
   * @param cache where units' facts are kept between runs
   * @throws IllegalArgumentException if two extractors share a name, a requirement is missing, or
   *     the requirements form a cycle
   */
  public Extractors(Collection<? extends Extractor> extractors, ExtractionCache cache) {
    this.extractors = order(extractors);
    this.cache = Objects.requireNonNull(cache, "cache");
    // The given extractors' vocabularies, and those of the others found, whose types theirs may
    // borrow; a given extractor replaces a found one of its name.
    List<Extractor> every = new ArrayList<>(this.extractors);
    Set<String> names = new HashSet<>();
    every.forEach(e -> names.add(e.info().name()));
    load().stream().filter(e -> !names.contains(e.info().name())).forEach(every::add);
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    vocabularies(every).values().forEach(types::addAll);
    this.vocabulary = new Vocabulary(1, types);
    for (Extractor extractor : this.extractors) {
      for (String borrowed : extractor.info().borrowed()) {
        if (vocabulary.find(borrowed).isEmpty()) {
          throw new IllegalArgumentException(
              extractor.info().name()
                  + " borrows the type '"
                  + borrowed
                  + "', which no vocabulary"
                  + " defines");
        }
      }
    }
  }

  /**
   * The vocabularies of the extractors found through {@link ServiceLoader}, by source, in an order
   * the registry can apply them in: each type's parent is built in or comes from a source before
   * it, or from its own.
   */
  public static SequencedMap<String, List<VocabularyType>> vocabularies() {
    return vocabularies(load());
  }

  /**
   * The extractors' vocabularies by source, each type's parent built in or from a source before.
   *
   * @throws IllegalArgumentException if two extractors share a source, or a parent is defined by no
   *     source
   */
  static SequencedMap<String, List<VocabularyType>> vocabularies(List<Extractor> extractors) {
    Map<String, List<VocabularyType>> pending = new LinkedHashMap<>();
    for (Extractor extractor : extractors) {
      ExtractorInfo info = extractor.info();
      if (info.vocabularySource() != null
          && pending.put(info.vocabularySource(), info.vocabulary()) != null) {
        throw new IllegalArgumentException(
            "Two extractors share the source " + info.vocabularySource());
      }
    }
    Set<String> defined = new HashSet<>();
    BuiltInTypes.definitions().forEach(t -> defined.add(t.name()));
    SequencedMap<String, List<VocabularyType>> sorted = new LinkedHashMap<>();
    while (!pending.isEmpty()) {
      String next = null;
      for (Map.Entry<String, List<VocabularyType>> source : pending.entrySet()) {
        Set<String> own = new HashSet<>();
        source.getValue().forEach(t -> own.add(t.name()));
        if (source.getValue().stream()
            .allMatch(
                t ->
                    t.parent() == null
                        || defined.contains(t.parent())
                        || own.contains(t.parent()))) {
          next = source.getKey();
          break;
        }
      }
      if (next == null) {
        throw new IllegalArgumentException(
            "The types of these vocabularies have parents no source defines first: "
                + pending.keySet());
      }
      List<VocabularyType> types = pending.remove(next);
      types.forEach(t -> defined.add(t.name()));
      sorted.put(next, types);
    }
    return sorted;
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

  /**
   * Checks a fact an extractor emits (ADR 0092, amendment for #551): of its origin, of a predicate
   * its vocabulary defines or borrows or a built-in one, and valid against the vocabulary: entity
   * types known, the object of the predicate's kind, and the attributes as the predicate's schema
   * declares them, every required one present.
   *
   * @throws IllegalArgumentException if it is not
   */
  Fact checked(Extractor extractor, Fact fact) {
    String predicate = fact.predicate();
    if (!extractor.info().declares(predicate) && !BUILT_IN.contains(predicate)) {
      throw new IllegalArgumentException(
          extractor.info().name()
              + " emitted a fact of type '"
              + predicate
              + "', which its vocabulary neither defines nor borrows");
    }
    try {
      vocabulary.validate(fact);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          extractor.info().name() + " emitted an invalid fact " + fact.id() + ": " + e.getMessage(),
          e);
    }
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
