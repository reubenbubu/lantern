package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.persistence.PersistenceExtractor;
import java.util.List;
import org.junit.jupiter.api.Test;

class AddressResolutionTest {

  private static final String URL = "jdbc:postgresql://${DB_HOST}:5432/orders";

  /**
   * A datasource's placement claims are keyed by its unit and value (#596): a second datasource
   * with that value is answered by the same claims when it reads the same, and fails the run when
   * it does not, rather than take the other's answer.
   */
  @Test
  void twoDatasourcesWithOneValueShareTheirClaimsOnlyWhenTheyReadTheSame() {
    AddressResolution.Questions questions = new AddressResolution.Questions();
    Deployments.Place place =
        new Deployments.Place("${DB_HOST}:5432", "orders", "PostgreSQL", List.of("DB_HOST"));
    questions.datasource(null, datasource("spring.datasource.url"), place);

    assertThatCode(() -> questions.datasource(null, datasource("spring.flyway.url"), place))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                questions.datasource(
                    null,
                    datasource("app.reporting.url"),
                    new Deployments.Place(
                        "${DB_HOST}:5432", "orders", "PostgreSQL", List.of("REPORTING"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(URL);
  }

  private static Fact datasource(String key) {
    return Fact.create(
        EntityId.parse("module:g:orders"),
        PersistenceExtractor.DATASOURCE,
        FactObject.string(URL),
        (tools.jackson.databind.node.ObjectNode) FactJson.parse("{\"key\":\"" + key + "\"}"),
        new Origin(Origin.Kind.STATIC, "persistence", "1"),
        Evidence.of("orders", "src/main/resources/application.yml", null, null),
        1.0,
        Validity.from(1));
  }
}
