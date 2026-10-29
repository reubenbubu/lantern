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
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.link.DeployableUnits;
import io.lantern.link.Linker;
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
 * it came from change, even when only another repository was analyzed (ADR 0039).
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
    jdbc.sql("DELETE FROM facts").update();
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
        new Evidence(repo, COMMIT, where.substring(where.indexOf(':') + 1), null, null),
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

  private List<Fact> linked(long snapshot) {
    return facts.findValidAt(snapshot).stream()
        .filter(f -> f.origin().name().equals(DeployableUnits.NAME))
        .toList();
  }
}
