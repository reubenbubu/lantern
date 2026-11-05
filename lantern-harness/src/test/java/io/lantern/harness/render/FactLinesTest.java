package io.lantern.harness.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Fact lines (knowledge-build epic, section 11.0, How inputs are rendered). */
class FactLinesTest {

  private static Fact fact(String subject, String predicate, FactObject object, String json) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        (ObjectNode) FactJson.parse(json),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        Evidence.of("shop", "src/A.java", null, null),
        1.0,
        Validity.from(1));
  }

  @Test
  void aLineShowsSubjectPredicateObjectSortedAttributesAndFileWithoutIdsOrLines() {
    Fact fact =
        fact(
            "service:com.example:app",
            "CALLS_HTTP",
            FactObject.entity(EntityId.parse("external-system:pay.example.com")),
            "{\"path\": \"/charges\", \"facts\": [\"abc\"], \"lines\": \"1-2\", \"httpMethod\": \"POST\"}");

    assertThat(FactLines.line(fact))
        .isEqualTo(
            "service:com.example:app CALLS_HTTP external-system:pay.example.com"
                + " {\"httpMethod\":\"POST\",\"path\":\"/charges\"} (shop:src/A.java)");
  }

  @Test
  void aClaimIdObjectIsShownByWhatItRefersTo() {
    String claim = "a".repeat(64);
    Fact fact = fact("service:com.example:app", "intent", FactObject.string(claim), "{}");

    assertThat(FactLines.line(fact)).contains("\"" + claim + "\"");
    assertThat(FactLines.line(fact, id -> "app → pay [CALLS_HTTP]"))
        .isEqualTo("service:com.example:app intent \"app → pay [CALLS_HTTP]\" (shop:src/A.java)");
  }

  @Test
  void textIsKeptOnOneLineAndFactsSortByTheirLines() {
    Fact b = fact("service:b:b", "name", FactObject.string("two\nlines"), "{}");
    Fact a = fact("service:a:a", "name", FactObject.string("x\ty"), "{}");

    assertThat(FactLines.line(b)).isEqualTo("service:b:b name \"two lines\" (shop:src/A.java)");
    assertThat(FactLines.sorted(List.of(b, a))).containsExactly(a, b);
  }

  @Test
  void attributesHoldingIdsAreLeftOutWhateverTheirNameAndReadableValuesAreKept() {
    String id = "b".repeat(64);
    String other = "c".repeat(64);
    Fact triggered =
        fact(
            "flow:service:com.example:app/x",
            "TRIGGERED_BY",
            FactObject.string("browser"),
            "{\"trigger\": \""
                + id
                + "\", \"route\": \""
                + other
                + "\", \"routed\": \""
                + id
                + "\", \"filledBy\": [\""
                + id
                + "\"], \"fieldCites\": {\"title\": [\""
                + other
                + "\"]}, \"person\": \"person:user\"}");
    Fact scheduled =
        fact(
            "service:com.example:app",
            "SCHEDULED",
            FactObject.string("job"),
            "{\"trigger\": \"cron\", \"route\": \"admin-service\"}");

    assertThat(FactLines.line(triggered))
        .isEqualTo(
            "flow:service:com.example:app/x TRIGGERED_BY \"browser\""
                + " {\"person\":\"person:user\"} (shop:src/A.java)");
    assertThat(FactLines.line(scheduled))
        .isEqualTo(
            "service:com.example:app SCHEDULED \"job\""
                + " {\"route\":\"admin-service\",\"trigger\":\"cron\"} (shop:src/A.java)");
  }
}
