package io.lantern.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class LiquibaseContextsTest {

  private static Liquibase liquibase(List<String> contexts) {
    return new Liquibase(
        new TreeSet<>(),
        path -> null,
        null,
        1.0,
        new Settings(null, Map.of(), "${", "}", null, contexts, Map.of(), List.of()));
  }

  @Test
  void contextExpressions() {
    Liquibase prod = liquibase(List.of("prod", "eu"));

    assertThat(prod.contexts("prod")).isTrue();
    assertThat(prod.contexts("test")).isFalse();
    assertThat(prod.contexts("test, PROD")).isTrue();
    assertThat(prod.contexts("prod and !eu")).isFalse();
    assertThat(prod.contexts("not test and (eu or us)")).isTrue();
    assertThat(prod.contexts("!prod")).isFalse();
    assertThat(prod.contexts(null)).isTrue();
  }

  @Test
  void withoutContextsEveryChangeSetRunsButMandatoryOnes() {
    assertThat(liquibase(List.of()).contexts("test")).isTrue();
    assertThat(liquibase(List.of()).contexts("@test")).isFalse();
    assertThat(liquibase(List.of("test")).contexts("@test")).isTrue();
  }
}
