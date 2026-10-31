package io.lantern.extract.java;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.Values.Part;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shared value follower (#283): constants, {@code @Value} and the one hop through a field. */
class ValuesTest {

  private static final String OWNER = "com.example.Client";

  @TempDir Path temp;

  private Values values;
  private EntityId constructor;

  @BeforeEach
  void analyze() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("values"),
            "values",
            Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>values</artifactId><version>1</version></project>",
                "src/main/java/com/example/Client.java",
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Value;

                public class Client {
                  @Value("${a.url}")
                  private String annotated;

                  private String given;
                  private String same;
                  private String disagree;
                  private String passed;

                  public Client(@Value("${b.url}") String b, String plain) {
                    this.given = b;
                    this.same = "http://same";
                    this.disagree = "one";
                    this.passed = plain;
                  }

                  public Client(int other) {
                    this.same = "http://same";
                    this.disagree = "two";
                  }
                }
                """));
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));
    values = code.values();
    constructor =
        code.type(OWNER).members().stream()
            .map(ModuleStructure.Member::id)
            .filter(id -> id.key().contains("<init>(java.lang.String"))
            .findFirst()
            .orElseThrow();
  }

  @Test
  void aConstantIsWrittenAndAValueAnnotationGivesItsTextAsWritten() {
    assertThat(values.follow(new Expression.Literal("/orders"), constructor, true).parts())
        .containsExactly(new Part.Written("/orders"));

    Values.Value annotated = values.follow(field("annotated"), constructor, false);
    assertThat(annotated.parts()).containsExactly(new Part.Annotated("${a.url}"));
    assertThat(annotated.via()).containsExactly(fieldId("annotated"));

    Values.Value parameter = values.follow(new Expression.Param(0), constructor, false);
    assertThat(parameter.text()).isEqualTo("${b.url}");
    assertThat(parameter.via()).containsExactly(constructor);
  }

  @Test
  void aFieldIsFollowedOneHopWhenEveryValueGivenToItReadsTheSame() {
    Values.Value given = values.follow(field("given"), constructor, true);
    assertThat(given.parts()).containsExactly(new Part.Annotated("${b.url}"));
    assertThat(given.via()).containsExactly(fieldId("given"), constructor);
    assertThat(values.follow(field("same"), constructor, true).text()).isEqualTo("http://same");

    // Without the hop, and where constructors give different values, the field stays open.
    assertThat(values.follow(field("given"), constructor, false).parts())
        .containsExactly(new Part.Open(field("given")));
    assertThat(values.follow(field("disagree"), constructor, true).resolved()).isFalse();
  }

  @Test
  void aConcatenationKeepsItsPartsAndNamesTheParametersLeftOpen() {
    Values.Value url =
        values.follow(
            new Expression.Concat(
                List.of(field("annotated"), new Expression.Literal("/x/"), field("passed"))),
            constructor,
            true);

    assertThat(url.parts())
        .containsExactly(
            new Part.Annotated("${a.url}"),
            new Part.Written("/x/"),
            new Part.Open(field("passed")));
    assertThat(url.text()).isNull();
    assertThat(url.openParams()).containsExactly(constructor);
  }

  private static Expression.FieldRef field(String name) {
    return new Expression.FieldRef(OWNER, name);
  }

  private static EntityId fieldId(String name) {
    return new EntityId(ModuleStructure.FIELD, OWNER + "#" + name);
  }
}
