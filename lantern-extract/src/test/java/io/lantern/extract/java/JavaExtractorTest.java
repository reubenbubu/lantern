package io.lantern.extract.java;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.sandbox.BuildModule;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildStatus;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker: golden facts for the fixtures without builds, so every module is
 * parsed (degraded mode), and the compiler path on builds made up here with the JDK and Lombok as
 * the only classpath. {@link JavaExtractorIT} builds the fixtures in the sandbox.
 */
class JavaExtractorTest {

  private static final long SNAPSHOT = 4;

  @TempDir Path temp;

  private List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = new JavaExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        JavaExtractor.NAME,
        JavaExtractor.VERSION);
    return facts;
  }

  private static RepoBuild unbuilt(Repo repo) {
    return new RepoBuild(repo, Fixtures.head(repo), null);
  }

  /** A successful build of a single-module repository with the given classpath. */
  private RepoBuild built(Repo repo, String groupId, String artifactId, List<String> classpath)
      throws IOException {
    String commit = Fixtures.head(repo);
    Path directory = Files.createDirectories(temp.resolve("builds").resolve(repo.name()));
    BuildModule module =
        new BuildModule(
            "", groupId, artifactId, "jar", "1.0.0", List.of(), classpath, null, null, null);
    BuildResult build =
        new BuildResult(
            repo.name(),
            commit,
            BuildStatus.SUCCEEDED,
            null,
            21,
            "test",
            "test",
            0,
            Instant.EPOCH,
            Duration.ZERO,
            null,
            List.of(module),
            directory);
    return new RepoBuild(repo, commit, build);
  }

  @Test
  void multiModuleParsed() throws IOException {
    Repo repo = Fixtures.repo("multi-module", temp.resolve("shop"), "shop");

    Fixtures.assertGolden("java/multi-module-parsed.txt", extract(List.of(unbuilt(repo))));
  }

  @Test
  void lombokParsed() throws IOException {
    Repo repo = Fixtures.repo("lombok", temp.resolve("orders"), "orders");

    // Without the compiler, Lombok's members are missing and calls to them do not resolve.
    Fixtures.assertGolden("java/lombok-parsed.txt", extract(List.of(unbuilt(repo))));
  }

  @Test
  void libAndServiceParsed() throws IOException {
    Repo lib = Fixtures.repo("lib-and-service/lib", temp.resolve("lib"), "greeting-lib");
    Repo service =
        Fixtures.repo("lib-and-service/service", temp.resolve("service"), "greeting-service");

    // The service's call into the library resolves against the library's sources.
    Fixtures.assertGolden(
        "java/lib-and-service-parsed.txt", extract(List.of(unbuilt(lib), unbuilt(service))));
  }

  @Test
  void lombokMembersAreSeenThroughTheCompiler() throws IOException, URISyntaxException {
    Repo repo = Fixtures.repo("lombok", temp.resolve("orders"), "orders");
    Path lombok =
        Path.of(lombok.Value.class.getProtectionDomain().getCodeSource().getLocation().toURI());

    List<Fact> facts =
        extract(List.of(built(repo, "com.example.orders", "orders", List.of(lombok.toString()))));

    Set<String> lines = triples(facts);
    assertThat(lines)
        .contains(
            "type:com.example.orders.Order CONTAINS method:com.example.orders.Order#getId()",
            "type:com.example.orders.Order CONTAINS type:com.example.orders.Order$OrderBuilder",
            "method:com.example.orders.OrderService#total(com.example.orders.Order) CALLS"
                + " method:com.example.orders.OrderLine#getQuantity()",
            "type:com.example.orders.OrderService CONTAINS"
                + " method:com.example.orders.OrderService#<init>(java.lang.String)");
    assertThat(facts).allMatch(f -> f.confidence() == JavaExtractor.COMPILED_CONFIDENCE);
  }

  /** Code using most of what Java declares, with only the JDK on the classpath. */
  private static final Map<String, String> SAMPLE =
      Map.of(
          "pom.xml",
          """
          <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>sample</artifactId>
            <version>1.0.0</version>
          </project>
          """,
          "src/main/java/com/example/sample/Base.java",
          """
          package com.example.sample;

          public abstract class Base {
            protected void base() {}
          }
          """,
          "src/main/java/com/example/sample/Named.java",
          """
          package com.example.sample;

          public interface Named extends java.io.Serializable {
            String name();
          }
          """,
          "src/main/java/com/example/sample/Shapes.java",
          """
          package com.example.sample;

          import java.util.List;
          import java.util.function.Supplier;

          public class Shapes<T extends Comparable<T>> extends Base implements Supplier<String>, Named {
            static final int LIMIT = compute();
            private final List<T> items;
            private int a, b;

            public Shapes(List<T> items) {
              this.items = items;
            }

            public Shapes() {
              this(List.of());
            }

            static int compute() {
              return 3;
            }

            @Override
            public String get() {
              return describe("x", 1, 2);
            }

            @Override
            public String name() {
              return "shapes";
            }

            String describe(String format, int... values) {
              Runnable r = new Runnable() {
                public void run() {
                  compute();
                }
              };
              Supplier<Integer> s = Shapes::compute;
              class Local {
                void go() {
                  helper();
                }
              }
              return format + values.length + new Inner().size() + Kind.ROUND.label();
            }

            void helper() {
              super.base();
            }

            <E> E first(List<E> list, T[] array) {
              return list.get(0);
            }

            T top(T t) {
              return t;
            }

            class Inner {
              int size() {
                return items.size();
              }
            }

            static class Nested extends Base {
              Nested() {
                super();
              }
            }

            enum Kind {
              ROUND("r"),
              SQUARE("s") {
                @Override
                String label() {
                  return compute() + "";
                }
              };

              private final String label;

              Kind(String label) {
                this.label = label;
              }

              String label() {
                return label;
              }
            }

            record Point(int x, int y) {
              Point {
                compute();
              }

              int sum() {
                return x + y;
              }
            }

            @interface Marker {
              String value() default "";
            }
          }
          """);

  @Test
  void parsingFindsWhatTheCompilerFinds() throws IOException {
    Repo compiled = Fixtures.repo(temp.resolve("compiled"), "compiled", SAMPLE);
    Repo parsed = Fixtures.repo(temp.resolve("parsed"), "parsed", SAMPLE);

    List<Fact> viaCompiler = extract(List.of(built(compiled, "com.example", "sample", List.of())));
    List<Fact> viaParser = extract(List.of(unbuilt(parsed)));

    assertThat(viaCompiler).allMatch(f -> f.confidence() == JavaExtractor.COMPILED_CONFIDENCE);
    assertThat(viaParser).allMatch(f -> f.confidence() == JavaExtractor.PARSED_CONFIDENCE);
    assertThat(triples(viaParser)).isEqualTo(triples(viaCompiler));
    Fixtures.assertGolden("java/sample.txt", viaCompiler);
  }

  @Test
  void runningTwiceGivesTheSameFacts() throws IOException, URISyntaxException {
    Repo repo = Fixtures.repo("lombok", temp.resolve("orders"), "orders");
    Path lombok =
        Path.of(lombok.Value.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    RepoBuild input = built(repo, "com.example.orders", "orders", List.of(lombok.toString()));

    assertThat(extract(List.of(input))).isEqualTo(extract(List.of(input)));
    assertThat(extract(List.of(unbuilt(repo)))).isEqualTo(extract(List.of(unbuilt(repo))));
  }

  @Test
  void removesItsWorkingCopy() throws IOException {
    Repo repo = Fixtures.repo("multi-module", temp.resolve("shop"), "shop");
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    Set<Path> before = workingCopies(tmp);

    extract(List.of(unbuilt(repo)));

    assertThat(workingCopies(tmp)).isEqualTo(before);
  }

  private static Set<Path> workingCopies(Path tmp) throws IOException {
    try (var list = Files.list(tmp)) {
      return list.filter(p -> p.getFileName().toString().startsWith("lantern-java-"))
          .collect(Collectors.toSet());
    }
  }

  /** Subject, predicate and object of each fact, without evidence or confidence. */
  private static Set<String> triples(List<Fact> facts) {
    return facts.stream()
        .map(f -> f.subject() + " " + f.predicate() + " " + f.object().value())
        .collect(Collectors.toCollection(java.util.TreeSet::new));
  }
}
