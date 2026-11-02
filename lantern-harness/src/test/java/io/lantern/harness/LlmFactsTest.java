package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.llm.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The facts the roles write (knowledge-build epic, section 11.0, What a role stores). */
class LlmFactsTest {

  private static final RoleSpec ROLE =
      RoleSpec.single("describe-unit", Role.INFER_INTENT, "5", "describe", RunMode.BULK, 100);

  private static Fact cited(String claim, String path) {
    Fact fact =
        Fact.create(
            EntityId.parse("service:com.example:app"),
            "deployable-unit",
            FactObject.string("spring-boot"),
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "maven", "1"),
            Evidence.of("shop", path, null, null),
            1.0,
            Validity.from(1));
    return new Fact(
        fact.id(),
        claim,
        fact.subject(),
        fact.predicate(),
        fact.object(),
        fact.attributes(),
        fact.lineage(),
        fact.origin(),
        fact.evidence(),
        fact.confidence(),
        fact.validity());
  }

  @Test
  void aTextIsAFactOfItsRoleWithItsCitesBasisAndModelAndCitedEvidence() {
    Fact one = cited("a".repeat(64), "pom.xml");
    Fact two = cited("b".repeat(64), "README.md");

    Fact text =
        LlmFacts.text(
            ROLE,
            "fake/fake-model",
            EntityId.parse("service:com.example:app"),
            LlmFacts.RESPONSIBILITY,
            LlmFacts.C4,
            "Takes the shop's orders.",
            List.of(one, two, one),
            LlmFacts.Basis.EVIDENCED,
            7);

    assertThat(text.origin()).isEqualTo(new Origin(Origin.Kind.LLM, "describe-unit", "5"));
    assertThat(text.predicate()).isEqualTo("responsibility");
    assertThat(text.object()).isEqualTo(FactObject.string("c4"));
    assertThat(text.attributes().get("text").asString()).isEqualTo("Takes the shop's orders.");
    assertThat(text.attributes().get("cites").valueStream().map(n -> n.asString()))
        .containsExactly(one.id(), two.id());
    assertThat(text.attributes().get("basis").asString()).isEqualTo("evidenced");
    assertThat(text.attributes().get("model").asString()).isEqualTo("fake/fake-model");
    assertThat(text.lineage()).containsExactly("a".repeat(64), "b".repeat(64));
    assertThat(text.evidence().locations())
        .extracting(Evidence.Location::path, Evidence.Location::role)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("pom.xml", Evidence.Role.CITED),
            org.assertj.core.groups.Tuple.tuple("README.md", Evidence.Role.CITED));
    assertThat(text.confidence()).isEqualTo(LlmFacts.CONFIDENCE);
    assertThat(text.validity()).isEqualTo(Validity.from(7));
  }

  @Test
  void aTextCitingNothingIsNotAFact() {
    assertThatThrownBy(
            () ->
                LlmFacts.text(
                    ROLE,
                    "fake/fake-model",
                    EntityId.parse("service:com.example:app"),
                    LlmFacts.INTENT,
                    "x",
                    "Charges cards",
                    List.of(),
                    LlmFacts.Basis.INFERRED,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theVocabularyHoldsTheRolesTypes() {
    assertThat(LlmFacts.vocabulary())
        .extracting(VocabularyType::name)
        .containsExactlyInAnyOrder(
            "responsibility", "intent", "summary", "flow-significance", "flow-narrative");
  }

  @Test
  void aSummaryIsAFactOfItsLevelWithItsSentencesAndTheFactsEachCites() {
    Fact one = cited("a".repeat(64), "pom.xml");
    Fact two = cited("b".repeat(64), "README.md");
    RoleSpec role =
        RoleSpec.single("summarize", Role.SUMMARIZE, "16", "summarize", RunMode.BULK, 300);

    Fact summary =
        LlmFacts.summary(
            role,
            "fake/fake-model",
            EntityId.parse("module:com.example:app"),
            "module",
            List.of(
                new LlmFacts.Sentence("Takes the shop's orders.", List.of(two)),
                new LlmFacts.Sentence("It stores them.", List.of(one, two))),
            LlmFacts.Basis.EVIDENCED,
            0.25,
            7);

    assertThat(summary.origin()).isEqualTo(new Origin(Origin.Kind.LLM, "summarize", "16"));
    assertThat(summary.predicate()).isEqualTo(LlmFacts.SUMMARY);
    assertThat(summary.object()).isEqualTo(FactObject.string("module"));
    assertThat(summary.attributes().get("sentences").get(1).get("text").asString())
        .isEqualTo("It stores them.");
    assertThat(
            summary
                .attributes()
                .get("sentences")
                .get(1)
                .get("cites")
                .valueStream()
                .map(n -> n.asString()))
        .containsExactly(one.id(), two.id());
    assertThat(summary.attributes().get("basis").asString()).isEqualTo("evidenced");
    assertThat(summary.confidence()).isEqualTo(0.25);
    assertThat(summary.lineage()).containsExactly("b".repeat(64), "a".repeat(64));
    // Its attributes are those its vocabulary type allows.
    LlmFacts.vocabulary().stream()
        .filter(type -> type.name().equals(LlmFacts.SUMMARY))
        .findFirst()
        .orElseThrow()
        .attributeSchema()
        .validate(summary.attributes());
  }

  @Test
  void aSummaryCitingNothingIsNotAFact() {
    RoleSpec role =
        RoleSpec.single("summarize", Role.SUMMARIZE, "16", "summarize", RunMode.BULK, 300);

    assertThatThrownBy(
            () ->
                LlmFacts.summary(
                    role,
                    "fake/fake-model",
                    EntityId.parse("module:com.example:app"),
                    "module",
                    List.of(new LlmFacts.Sentence("Takes orders.", List.of())),
                    LlmFacts.Basis.INFERRED,
                    0.35,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
