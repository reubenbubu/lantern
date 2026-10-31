package io.lantern.extract.persistence;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ModuleValues;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The persistence extractor's facts with the datasources and data stores linking records (ADR 0094,
 * #560), read here from the modules' config files as the extractor read them before, so the tests
 * check them as they checked its facts. {@code ValueViewsTest} checks that linking, from the config
 * facts, gives the same.
 */
final class Resolved {

  private Resolved() {}

  static List<Fact> facts(List<RepoBuild> repos, long snapshot) {
    return facts(JavaExtractor.analyze(repos), snapshot, Set.of());
  }

  static List<Fact> facts(JavaAnalysis code, long snapshot) {
    return facts(code, snapshot, Set.of());
  }

  static List<Fact> facts(JavaAnalysis code, long snapshot, Set<String> secretKeys) {
    List<Fact> written = new PersistenceExtractor().extract(code, snapshot);
    List<Fact> facts = new ArrayList<>();
    for (Fact f : written) {
      if (!f.predicate().equals(PersistenceExtractor.JPA_TABLE)) {
        facts.add(f);
      }
    }
    facts.addAll(PersistenceExtractor.tables(written, configs(code), origin(), snapshot));
    for (Fact f : datasources(code, snapshot, secretKeys)) {
      if (!f.predicate().equals(PersistenceExtractor.HAS_DATASOURCE)
          && !f.predicate().equals(PersistenceExtractor.CONNECTS_TO)) {
        facts.add(f);
      }
    }
    return facts.stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.evidence().path())
                .thenComparing(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /** What linking gives from the config of the code's repositories, read from their files. */
  static List<Fact> datasources(JavaAnalysis code, long snapshot, Set<String> secretKeys) {
    return PersistenceExtractor.datasources(configs(code), secretKeys, origin(), snapshot);
  }

  /** The config of the modules of the code's repositories, read from their files. */
  static List<ModuleValues> configs(JavaAnalysis code) {
    Set<RepoBuild> repos = new LinkedHashSet<>();
    code.modules().forEach(m -> repos.add(m.repo()));
    List<ModuleValues> configs = new ArrayList<>();
    for (RepoBuild repo : repos) {
      ConfigExtractor.read(repo).forEach(c -> configs.add(ModuleValues.of(c)));
    }
    return configs;
  }

  private static Origin origin() {
    return new Origin(Origin.Kind.STATIC, PersistenceExtractor.NAME, PersistenceExtractor.VERSION);
  }
}
