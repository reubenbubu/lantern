package io.lantern.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.frontend.FrontendExtractor;
import io.lantern.extract.identity.IdentityExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.extract.test.TestExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * The extractors run through one interface, in the order of their requirements, each unit read from
 * the cache when nothing it depends on changed (ADR 0092).
 */
class ExtractorsTest {

  @TempDir Path temp;

  @Test
  void everyExtractorIsFoundAndRunsAfterWhatItRequires() {
    List<Extractor> loaded = Extractors.load();
    List<String> names = loaded.stream().map(e -> e.info().name()).toList();

    assertThat(names)
        .containsExactlyInAnyOrder(
            "config",
            "deployment",
            "docs",
            "frontend",
            "gateway",
            "http-client",
            "identity",
            "java",
            "mail",
            "maven",
            "messaging",
            "people",
            "persistence",
            "redis",
            "spring",
            "sql",
            "test",
            "websocket");
    for (Extractor extractor : loaded) {
      for (String required : extractor.requires()) {
        assertThat(names.indexOf(required)).isLessThan(names.indexOf(extractor.info().name()));
      }
    }
    assertThat(loaded.stream().filter(e -> e.unit() == CacheUnit.FILE))
        .extracting(e -> e.info().name())
        .containsExactly("docs");
  }

  @Test
  void aMissingRequirementOrACycleIsRefused() {
    assertThatThrownBy(() -> new Extractors(List.of(fake("a", "b")), ExtractionCache.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing extractor b");
    assertThatThrownBy(
            () -> new Extractors(List.of(fake("a", "b"), fake("b", "a")), ExtractionCache.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void theRunGivesTheFactsEachExtractorGivesOnItsOwn() throws IOException {
    List<RepoBuild> repos =
        List.of(
            build(Fixtures.repo("spring", temp.resolve("spring"), "spring")),
            build(Fixtures.repo("docs", temp.resolve("docs"), "docs")),
            build(Fixtures.repo("deployment", temp.resolve("deployment"), "deployment")),
            build(Fixtures.repo("sql", temp.resolve("sql"), "sql")),
            build(Fixtures.repo("frontend", temp.resolve("frontend"), "frontend")));

    Extractors.Result result =
        new Extractors(Extractors.load(), ExtractionCache.NONE).run(3, repos);

    JavaAnalysis code = JavaExtractor.analyze(repos);
    List<Fact> identity = new IdentityExtractor().extract(code, repos, 3);
    Set<String> keys = ConfigExtractor.jwtSecretKeys(identity);
    assertThat(result.facts("maven")).isEqualTo(new MavenExtractor().extract(repos, 3));
    assertThat(result.facts("java")).isEqualTo(new JavaExtractor().extract(code, 3));
    assertThat(result.facts("identity")).isEqualTo(identity);
    assertThat(result.facts("spring")).isEqualTo(new SpringExtractor().extract(code, 3));
    assertThat(result.facts("docs")).isEqualTo(new DocsExtractor().extract(repos, 3, keys));
    assertThat(result.facts("deployment")).isEqualTo(new DeploymentExtractor().extract(repos, 3));
    assertThat(result.facts("config")).isEqualTo(new ConfigExtractor().extract(repos, 3, keys));
    assertThat(result.facts("sql")).isEqualTo(new SqlExtractor().extract(repos, 3));
    assertThat(result.facts("test")).isEqualTo(new TestExtractor().extract(repos, 3));
    assertThat(result.facts("frontend")).isEqualTo(new FrontendExtractor().extract(repos, 3));
    assertThat(result.facts("sql")).isNotEmpty();
    assertThat(result.facts("frontend")).isNotEmpty();
    assertThat(result.facts("docs")).isNotEmpty();
    assertThat(result.facts("deployment")).isNotEmpty();
    assertThat(result.facts("spring")).isNotEmpty();
  }

  @Test
  void anUnchangedSnapshotIsReadFromTheCacheAndAChangedFileExtractedAlone() throws IOException {
    Path dir = temp.resolve("shop");
    Repo repo =
        Fixtures.repo(
            dir,
            "shop",
            Map.of(
                "README.md", "# Shop\n\nSells things.\n",
                "docs/architecture.md", "# Architecture\n\nOne service.\n",
                "Dockerfile", "FROM eclipse-temurin:21\n"));
    Repo other =
        Fixtures.repo(
            temp.resolve("billing"),
            "billing",
            Map.of(
                "README.md", "# Billing\n\nBills.\n", "Dockerfile", "FROM eclipse-temurin:21\n"));
    ExtractionCache cache = ExtractionCache.inMemory();
    Extractors extractors = new Extractors(Extractors.load(), cache);
    long snapshotUnits =
        extractors.extractors().stream().filter(e -> e.unit() == CacheUnit.SNAPSHOT).count();
    long repositoryUnits =
        extractors.extractors().stream().filter(e -> e.unit() == CacheUnit.REPOSITORY).count();

    Extractors.Result first = extractors.run(1, List.of(build(repo), build(other)));
    commit(dir, "empty", true);
    Extractors.Result again = extractors.run(2, List.of(build(repo), build(other)));
    Files.writeString(dir.resolve("README.md"), "# Shop\n\nSells more things.\n");
    commit(dir, "readme", false);
    Extractors.Result changed = extractors.run(3, List.of(build(repo), build(other)));

    assertThat(first.cached()).isZero();
    assertThat(first.extracted()).isEqualTo(snapshotUnits + 2 * repositoryUnits + 3);
    // An empty commit changes no file, so every unit is read from the cache, valid from the new
    // snapshot.
    assertThat(again.extracted()).isZero();
    assertThat(again.facts())
        .extracting(Fact::id)
        .containsExactlyElementsOf(first.facts().stream().map(Fact::id).toList());
    assertThat(again.facts()).allSatisfy(f -> assertThat(f.validity()).isEqualTo(Validity.from(2)));
    // A changed README is extracted again, and so are the snapshot units and the changed
    // repository's units, whose inputs include it; the other document and every unit of the other
    // repository are read from the cache.
    assertThat(changed.extracted()).isEqualTo(snapshotUnits + repositoryUnits + 1);
    assertThat(changed.cached()).isEqualTo(repositoryUnits + 2);
    assertThat(changed.facts("docs")).isNotEqualTo(again.facts("docs"));
  }

  @Test
  void mavenAndConfigReExtractOnlyTheChangedRepositoryUnlessItsPomOrConfigChanged()
      throws IOException {
    Path shopDir = temp.resolve("shop");
    String pom =
        "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
            + "<artifactId>%s</artifactId><version>1.0</version>%s</project>";
    Repo shop =
        Fixtures.repo(
            shopDir,
            "shop",
            Map.of(
                "pom.xml",
                pom.formatted(
                    "shop",
                    "<dependencies><dependency><groupId>com.example</groupId>"
                        + "<artifactId>lib</artifactId><version>1.0</version></dependency>"
                        + "</dependencies>"),
                "src/main/resources/application.yml",
                "spring:\n  application:\n    name: shop\n",
                "src/main/java/com/example/Shop.java",
                "package com.example;\nclass Shop {}\n"));
    Path libDir = temp.resolve("lib");
    Repo lib =
        Fixtures.repo(
            libDir,
            "lib",
            Map.of(
                "pom.xml",
                pom.formatted("lib", ""),
                "src/main/java/com/example/Lib.java",
                "package com.example;\nclass Lib {}\n"));
    List<Extractor> moved =
        Extractors.load().stream()
            .filter(e -> List.of("maven", "config", "identity").contains(e.info().name()))
            .toList();
    assertThat(moved)
        .filteredOn(e -> !e.info().name().equals("identity"))
        .allSatisfy(e -> assertThat(e.unit()).isEqualTo(CacheUnit.REPOSITORY));
    Extractors extractors = new Extractors(moved, ExtractionCache.inMemory());

    Extractors.Result first = extractors.run(1, List.of(build(shop), build(lib)));
    Files.writeString(
        libDir.resolve("src/main/java/com/example/Lib.java"),
        "package com.example;\nclass Lib { int x; }\n");
    commit(libDir, "code", false);
    Extractors.Result code = extractors.run(2, List.of(build(shop), build(lib)));
    Files.writeString(
        libDir.resolve("src/main/java/com/example/Extra.java"),
        "package com.example;\nclass Extra {}\n");
    commit(libDir, "new file", false);
    Extractors.Result added = extractors.run(3, List.of(build(shop), build(lib)));
    Files.createDirectories(libDir.resolve("src/main/resources"));
    Files.writeString(
        libDir.resolve("src/main/resources/application.yml"),
        "spring:\n  application:\n    name: lib\n");
    commit(libDir, "config", false);
    Extractors.Result config = extractors.run(4, List.of(build(shop), build(lib)));
    Files.writeString(
        libDir.resolve("pom.xml"), pom.formatted("lib", "<packaging>pom</packaging>"));
    commit(libDir, "pom", false);
    Extractors.Result pomChanged = extractors.run(5, List.of(build(shop), build(lib)));

    // Identity, which config requires, is a snapshot unit: extracted on every change.
    assertThat(first.extracted()).isEqualTo(1 + 4);
    // The dependency on lib is to lib's module, decided with lib's POM.
    assertThat(first.facts("maven"))
        .anySatisfy(
            f -> {
              assertThat(f.predicate()).isEqualTo("BUILD_DEPENDS_ON");
              assertThat(f.object().value().toString()).isEqualTo("module:com.example:lib");
            });
    // A code change in lib re-extracts only lib's units.
    assertThat(code.extracted()).isEqualTo(1 + 2);
    assertThat(code.cached()).isEqualTo(2);
    // A new source file in lib re-extracts lib's units and shop's Maven unit, since Maven compares
    // modules' source counts across repositories; shop's config unit is read from the cache.
    assertThat(added.extracted()).isEqualTo(1 + 3);
    assertThat(added.cached()).isEqualTo(1);
    // A change to lib's Spring config re-extracts lib's units and shop's config unit, which reads
    // other repositories' config; shop's Maven unit is read from the cache.
    assertThat(config.extracted()).isEqualTo(1 + 3);
    assertThat(config.cached()).isEqualTo(1);
    // A change to lib's POM, which shop's units read, re-extracts both repositories' Maven units;
    // Config reads POMs too.
    assertThat(pomChanged.extracted()).isEqualTo(1 + 4);
    assertThat(pomChanged.facts()).isNotEmpty();
  }

  @Test
  void everyExtractorDeclaresItsVocabularyAndTheSourcesComeParentsFirst() {
    for (Extractor extractor : Extractors.load()) {
      assertThat(extractor.info().vocabularySource()).as(extractor.info().name()).isNotNull();
      assertThat(extractor.info().vocabulary()).as(extractor.info().name()).isNotEmpty();
    }
    Set<String> defined = new HashSet<>();
    BuiltInTypes.definitions().forEach(t -> defined.add(t.name()));
    Extractors.vocabularies()
        .forEach(
            (source, types) -> {
              types.forEach(t -> defined.add(t.name()));
              types.forEach(
                  t -> assertThat(t.parent() == null || defined.contains(t.parent())).isTrue());
            });
    assertThat(Extractors.vocabularies()).hasSize(Extractors.load().size());
  }

  @Test
  void theSinkRefusesAFactOfAnUndeclaredTypeOrWithoutARequiredKeyAttribute() {
    EntityId method = EntityId.parse("method:com.example:shop:com.example.Mailer#send()");
    ObjectNode full = FactJson.newObject();
    full.put("template", "org.springframework.mail.javamail.JavaMailSender");
    full.put("method", "send");
    ObjectNode keyMissing = FactJson.newObject();
    keyMissing.put("method", "send");

    assertThat(sinkRun(mailFact("mail-send", method, full))).isEqualTo(1);
    assertThatThrownBy(() -> sinkRun(mailFact("mail-host", method, full)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("type 'mail-host', which its vocabulary neither defines nor borrows");
    assertThatThrownBy(() -> sinkRun(mailFact("mail-send", method, keyMissing)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("template");
    assertThatThrownBy(
            () ->
                new Extractors(
                    List.of(sinkFake(Set.of("no-such-type"), List.of())), ExtractionCache.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("borrows the type 'no-such-type'");
  }

  private static Fact mailFact(String predicate, EntityId subject, ObjectNode attributes) {
    return Fact.create(
        subject,
        predicate,
        FactObject.string("smtp"),
        attributes,
        new Origin(Origin.Kind.STATIC, "mailer", "1"),
        Evidence.of("shop", "src/Mailer.java", new Evidence.LineRange(3, 3), null),
        1.0,
        Validity.from(1));
  }

  /** Runs a fake extractor that borrows mail-send and emits the fact; the facts it gave. */
  private static int sinkRun(Fact fact) {
    Extractors run =
        new Extractors(List.of(sinkFake(Set.of("mail-send"), List.of(fact))), ExtractionCache.NONE);
    return run.run(1, List.of()).facts("mailer").size();
  }

  private static Extractor sinkFake(Set<String> borrowed, List<Fact> facts) {
    return new Extractor() {
      @Override
      public ExtractorInfo info() {
        return new ExtractorInfo("mailer", "1", null, List.of(), borrowed);
      }

      @Override
      public CacheUnit unit() {
        return CacheUnit.SNAPSHOT;
      }

      @Override
      public void extract(ExtractionContext context, ExtractionUnit unit, ClaimSink sink) {
        sink.emitAll(facts);
      }
    };
  }

  private static RepoBuild build(Repo repo) {
    return new RepoBuild(repo, Fixtures.head(repo), null);
  }

  private static void commit(Path dir, String message, boolean empty) {
    git(dir, "add", "--all");
    git(
        dir,
        "-c",
        "user.name=Lantern Test",
        "-c",
        "user.email=test@lantern.invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--quiet",
        empty ? "--allow-empty" : "--no-verify",
        "-m",
        message);
  }

  private static void git(Path dir, String... args) {
    Git.Result result = Git.run(dir, args);
    assertThat(result.ok()).as(result.err()).isTrue();
  }

  private static Extractor fake(String name, String requires) {
    return new Extractor() {
      @Override
      public ExtractorInfo info() {
        return new ExtractorInfo(name, "1");
      }

      @Override
      public Set<String> requires() {
        return Set.of(requires);
      }

      @Override
      public CacheUnit unit() {
        return CacheUnit.SNAPSHOT;
      }

      @Override
      public void extract(ExtractionContext context, ExtractionUnit unit, ClaimSink sink) {}
    };
  }
}
