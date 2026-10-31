package io.lantern.extract.spring;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Spring extractor's facts with its endpoints and schedules as linking records them (ADR 0094,
 * #519), resolved here against each module's config files as the extractor resolved them before, so
 * the tests check them as they checked its facts. {@code ValueResolutionTest} checks that linking,
 * from the config facts, gives the same.
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
    List<Fact> written = new SpringExtractor().extract(code, snapshot);
    List<Fact> facts = new ArrayList<>();
    for (Fact f : written) {
      if (!f.predicate().equals(SpringExtractor.HANDLED_BY)
          && !f.predicate().equals(SpringExtractor.SCHEDULED_WRITTEN)) {
        facts.add(f);
      }
    }
    CodeConfig configs = new CodeConfig(secretKeys);
    Map<String, JavaAnalysis.Module> modules = new HashMap<>();
    code.modules().forEach(m -> modules.put(m.id().toString(), m));
    facts.addAll(
        SpringExtractor.resolve(
            written,
            (module, repo) -> {
              JavaAnalysis.Module m = modules.get(module.toString());
              return configs.forModule(m.repo(), m.directory());
            },
            new Origin(Origin.Kind.STATIC, SpringExtractor.NAME, SpringExtractor.VERSION),
            snapshot));
    return facts.stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(f -> f.evidence().path())
                .thenComparing(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(Fact::id))
        .toList();
  }
}
