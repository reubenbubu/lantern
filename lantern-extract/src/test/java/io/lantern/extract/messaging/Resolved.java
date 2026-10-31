package io.lantern.extract.messaging;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The messaging facts linking records (ADR 0094, #559), resolved here against each module's config
 * files as the extractor resolved them before it kept values as written, so the tests check them as
 * they checked its facts. {@code ValueViewsTest} checks that linking, from the config facts, gives
 * the same.
 */
final class Resolved {

  private Resolved() {}

  static List<Fact> facts(List<RepoBuild> repos, long snapshot) {
    return facts(JavaExtractor.analyze(repos), snapshot);
  }

  static List<Fact> facts(JavaAnalysis code, long snapshot) {
    return facts(code, snapshot, Set.of());
  }

  /**
   * @param secretKeys config keys known to hold a secret, such as {@link
   *     io.lantern.extract.config.ConfigExtractor#jwtSecretKeys}
   */
  static List<Fact> facts(JavaAnalysis code, long snapshot, Set<String> secretKeys) {
    return resolve(code, new MessagingExtractor().extract(code, snapshot), snapshot, secretKeys);
  }

  /** The written facts of the code resolved against its modules' config files. */
  static List<Fact> resolve(
      JavaAnalysis code, List<Fact> written, long snapshot, Set<String> secretKeys) {
    CodeConfig configs = new CodeConfig(secretKeys);
    Map<String, JavaAnalysis.Module> modules = new HashMap<>();
    code.modules().forEach(m -> modules.put(m.id().toString(), m));
    return MessagingExtractor.resolve(
        written,
        (module, repo) -> {
          JavaAnalysis.Module m = modules.get(module.toString());
          return configs.forModule(m.repo(), m.directory());
        },
        new Origin(Origin.Kind.STATIC, MessagingExtractor.NAME, MessagingExtractor.VERSION),
        snapshot);
  }
}
