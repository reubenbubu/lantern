package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.link.CrossRepoLinks;
import io.lantern.link.DeployableUnits;
import io.lantern.link.InMemoryResolutionStore;
import io.lantern.link.LinkResolver;
import io.lantern.link.Linker;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.ModelSettings;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * The linker records its facts for the whole snapshot: a derived fact stops holding when the facts
 * it came from change, even when only another repository was analyzed (ADR 0039). The LLM
 * resolver's links are recorded the same way, with its answers reused (ADR 0045).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LinkerIT {

  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  private Linker linker;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM observations").update();
    jdbc.sql("DELETE FROM claims").update();
    linker = Linker.standard(facts);
  }

  private static Fact fact(
      String extractor,
      String subject,
      String predicate,
      FactObject object,
      String where,
      long snapshot) {
    return fact(extractor, subject, predicate, object, null, where, snapshot);
  }

  private static Fact fact(
      String extractor,
      String subject,
      String predicate,
      FactObject object,
      String attributes,
      String where,
      long snapshot) {
    String repo = where.substring(0, where.indexOf(':'));
    ObjectNode json =
        attributes == null ? FactJson.newObject() : (ObjectNode) FactJson.parse(attributes);
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        json,
        new Origin(Origin.Kind.STATIC, extractor, "1"),
        Evidence.of(repo, where.substring(where.indexOf(':') + 1), null, null),
        1.0,
        Validity.from(snapshot));
  }

  private static FactObject entity(String id) {
    return FactObject.entity(EntityId.parse(id));
  }

  /** The library repository: a library with a Spring Boot application class and its name. */
  private static List<Fact> lib(long snapshot, String name) {
    return List.of(
        fact(
            MavenExtractor.NAME,
            "library:g:lib",
            BuiltInTypes.CONTAINS,
            entity("module:g:lib"),
            "lib:pom.xml",
            snapshot),
        fact(
            JavaExtractor.NAME,
            "module:g:lib",
            BuiltInTypes.CONTAINS,
            entity("type:g.App"),
            "lib:src/main/java/g/App.java",
            snapshot),
        fact(
            SpringExtractor.NAME,
            "type:g.App",
            SpringExtractor.MAIN_CLASS,
            FactObject.string(SpringExtractor.SPRING_BOOT),
            "lib:src/main/java/g/App.java",
            snapshot),
        fact(
            ConfigExtractor.NAME,
            "module:g:lib",
            ConfigExtractor.CONFIG_VALUE,
            FactObject.string(name),
            "{\"key\":\"spring.application.name\"}",
            "lib:src/main/resources/application.yml",
            snapshot));
  }

  /** The service repository: a repackaged jar that bundles the library. */
  private static List<Fact> svc(long snapshot) {
    return List.of(
        fact(
            MavenExtractor.NAME,
            "service:g:svc",
            BuiltInTypes.CONTAINS,
            entity("module:g:svc"),
            "svc:pom.xml",
            snapshot),
        fact(
            MavenExtractor.NAME,
            "module:g:svc",
            MavenExtractor.BUILD_DEPENDS_ON,
            entity("module:g:lib"),
            "{\"scope\":\"compile\"}",
            "svc:pom.xml",
            snapshot));
  }

  /** Records one repository's extracted facts, then links, as an analysis run does. */
  private Linker.Result analyze(long snapshot, String repo, List<Fact> found) {
    facts.recordSnapshot(snapshot, Origin.Kind.STATIC, Set.of(repo), linker.origins(), found);
    return linker.link(snapshot);
  }

  private List<String> linkedAt(long snapshot) {
    List<String> lines = new ArrayList<>();
    for (Fact f : facts.findValidAt(snapshot)) {
      if (f.origin().name().equals(DeployableUnits.NAME)) {
        lines.add(f.subject() + " " + f.predicate() + " " + f.object().value());
      }
    }
    return lines.stream().sorted().toList();
  }

  @Test
  void aLibraryThatIsBundledLaterStopsBeingAUnitOfItsOwn() {
    analyze(1, "lib", lib(1, "lib-app"));
    assertThat(linkedAt(1))
        .contains(
            "service:g:lib deployable-unit spring-boot", "service:g:lib application-name lib-app");

    // Only the service is analyzed; the library is carried forward.
    Linker.Result second = analyze(2, "svc", svc(2));

    assertThat(linkedAt(2))
        .containsExactly(
            "service:g:svc BUNDLES library:g:lib",
            "service:g:svc application-name lib-app",
            "service:g:svc deployable-unit spring-boot",
            "service:g:svc technology Spring Boot");
    assertThat(second.closed()).isEqualTo(4);
    assertThat(linkedAt(1)).noneMatch(l -> l.startsWith("service:g:svc "));
  }

  @Test
  void aNameChangedInACarriedForwardServicesLibraryIsPickedUp() {
    analyze(1, "lib", lib(1, "old-name"));
    analyze(2, "svc", svc(2));
    assertThat(linkedAt(2)).contains("service:g:svc application-name old-name");

    // Only the library is analyzed again; the service, whose facts name it, is carried forward.
    Linker.Result third = analyze(3, "lib", lib(3, "new-name"));

    assertThat(linkedAt(3))
        .contains("service:g:svc application-name new-name")
        .doesNotContain("service:g:svc application-name old-name");
    assertThat(third.added()).isEqualTo(1);
    assertThat(third.closed()).isEqualTo(1);

    // Linking the same snapshot again changes nothing.
    Linker.Result again = linker.link(3);
    assertThat(again.added()).isZero();
    assertThat(again.closed()).isZero();
    assertThat(linkedAt(3)).contains("service:g:svc application-name new-name");

    // Analyzing the service again leaves its linked facts holding, unbroken.
    List<Fact> before = linked(3);
    Linker.Result fourth = analyze(4, "svc", svc(4));
    assertThat(fourth.added()).isZero();
    assertThat(fourth.closed()).isZero();
    assertThat(linked(4)).isEqualTo(before);
  }

  /** A service whose module has a Spring Boot main class, the given name and one more type. */
  private static List<Fact> service(long snapshot, String name, String type) {
    String repo = name + ":";
    String module = "module:g:" + name;
    String app = "type:" + name + ".App";
    return List.of(
        fact(
            MavenExtractor.NAME,
            "service:g:" + name,
            BuiltInTypes.CONTAINS,
            entity(module),
            repo + "pom.xml",
            snapshot),
        fact(
            JavaExtractor.NAME,
            module,
            BuiltInTypes.CONTAINS,
            entity(app),
            repo + "App.java",
            snapshot),
        fact(
            SpringExtractor.NAME,
            app,
            SpringExtractor.MAIN_CLASS,
            FactObject.string(SpringExtractor.SPRING_BOOT),
            repo + "App.java",
            snapshot),
        fact(
            ConfigExtractor.NAME,
            module,
            ConfigExtractor.CONFIG_VALUE,
            FactObject.string(name),
            "{\"key\":\"spring.application.name\"}",
            repo + "application.yml",
            snapshot),
        fact(
            JavaExtractor.NAME,
            module,
            BuiltInTypes.CONTAINS,
            entity("type:" + type),
            repo + type + ".java",
            snapshot));
  }

  /** Orders calls a URL from an environment variable that only tax serves, as HTTP-5 would. */
  private static List<Fact> openCall(long snapshot) {
    List<Fact> all = new ArrayList<>(service(snapshot, "orders", "o.TaxClient"));
    all.add(
        fact(
            HttpClientExtractor.NAME,
            "method:o.TaxClient#quote()",
            HttpClientExtractor.HTTP_CALL,
            FactObject.string("GET ${TAX_SERVICE_URL}/v2/quotes"),
            "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/v2/quotes\","
                + "\"unresolved\":[\"TAX_SERVICE_URL\"]}",
            "orders:o.TaxClient.java",
            snapshot));
    all.addAll(service(snapshot, "tax", "t.TaxController"));
    all.add(
        fact(
            SpringExtractor.NAME,
            "method:t.TaxController#quotes()",
            SpringExtractor.ENDPOINT,
            FactObject.string("GET /v2/quotes"),
            "{\"annotation\":\"GetMapping\",\"controller\":\"t.TaxController\",\"framework\":\"spring-mvc\","
                + "\"httpMethod\":\"GET\",\"path\":\"/v2/quotes\"}",
            "tax:t.TaxController.java",
            snapshot));
    return all;
  }

  @Test
  void theResolversLinksAreRecordedForTheSnapshotAndItsAnswersReused() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                "{\"target\": \"C1\", \"confidence\": \"medium\", \"cites\": [\"C1\"],"
                    + " \"rationale\": \"Only tax serves the path.\"}");
    LlmClient client =
        new LlmClient(
            ModelSettings.single(fake.model("m")),
            List.of(fake),
            BudgetGuard.unlimited("test"),
            new InMemoryLlmCallLog(),
            Clock.systemUTC());
    linker = Linker.standard(facts, new LinkResolver(client, new InMemoryResolutionStore()));

    for (long snapshot : List.of(1L, 2L)) {
      facts.recordSnapshot(
          snapshot,
          Origin.Kind.STATIC,
          Set.of("orders", "tax"),
          linker.origins(),
          openCall(snapshot));
      linker.link(snapshot);
    }

    for (long snapshot : List.of(1L, 2L)) {
      assertThat(facts.findValidAt(snapshot))
          .filteredOn(f -> f.origin().kind() == Origin.Kind.LLM)
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.origin().name()).isEqualTo(LinkResolver.NAME);
                assertThat(f.subject() + " " + f.predicate() + " " + f.object().value())
                    .isEqualTo("service:g:orders " + CrossRepoLinks.CALLS_HTTP + " service:g:tax");
                assertThat(f.confidence()).isEqualTo(0.35);
              });
    }
    assertThat(fake.sent()).hasSize(1);
  }

  private List<Fact> linked(long snapshot) {
    return facts.findValidAt(snapshot).stream()
        .filter(f -> f.origin().name().equals(DeployableUnits.NAME))
        .toList();
  }
}
