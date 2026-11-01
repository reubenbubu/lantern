package io.lantern.archtest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.lantern.app.archfixture.FactReadingRenderer;
import io.lantern.compose.archfixture.FakeComposer;
import io.lantern.extract.archfixture.ExtractorCallingLlm;
import io.lantern.link.archfixture.FakeLinker;
import io.lantern.link.archfixture.LinkerReadingAttributes;
import io.lantern.link.archfixture.LinkerReadingComposer;
import io.lantern.llm.archfixture.FakeLlmClient;
import org.junit.jupiter.api.Test;

/** Proves the rules catch violations, using deliberately bad fixture classes. */
class ArchitectureRulesTest {

  @Test
  void extractorDependingOnLlmIsRejected() {
    JavaClasses classes =
        new ClassFileImporter().importClasses(ExtractorCallingLlm.class, FakeLlmClient.class);

    assertThatThrownBy(() -> ArchitectureRules.EXTRACTORS_MUST_NOT_DEPEND_ON_LLM.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("ExtractorCallingLlm");
  }

  @Test
  void aStageDependingOnALaterStageIsRejected() {
    JavaClasses classes =
        new ClassFileImporter().importClasses(LinkerReadingComposer.class, FakeComposer.class);

    assertThatThrownBy(() -> ArchitectureRules.STAGES_DEPEND_ONLY_ON_EARLIER_STAGES.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("LinkerReadingComposer")
        .hasMessageContaining("the link stage comes before");
  }

  @Test
  void aLaterStageMayDependOnAnEarlierOne() {
    JavaClasses classes =
        new ClassFileImporter().importClasses(FakeComposer.class, FakeLinker.class);

    assertThatCode(() -> ArchitectureRules.STAGES_DEPEND_ONLY_ON_EARLIER_STAGES.check(classes))
        .doesNotThrowAnyException();
  }

  @Test
  void aRendererReadingFactsIsRejected() {
    JavaClasses classes =
        new ClassFileImporter()
            .importClasses(FactReadingRenderer.class, FactReadingRenderer.Page.class);

    assertThatThrownBy(() -> ArchitectureRules.RENDERERS_MUST_NOT_READ_FACTS.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("FactReadingRenderer.<init>")
        .hasMessageContaining("FactReadingRenderer$Page.render()");
  }

  @Test
  void linkerCodeReadingAttributesByNameIsRejected() {
    JavaClasses classes = new ClassFileImporter().importClasses(LinkerReadingAttributes.class);

    assertThatThrownBy(() -> ArchitectureRules.LINKER_READS_TYPED_FACTS.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("LinkerReadingAttributes.path(io.lantern.core.fact.Fact)")
        .hasMessageContaining("LinkerReadingAttributes.all(java.util.List)");
  }

  @Test
  void linkerCodeWithoutAttributeReadsIsAccepted() {
    JavaClasses classes = new ClassFileImporter().importClasses(FakeLinker.class);

    assertThatCode(() -> ArchitectureRules.LINKER_READS_TYPED_FACTS.check(classes))
        .doesNotThrowAnyException();
  }

  @Test
  void aMatcherThatResolvesAddressesItselfIsRejected() {
    JavaClasses classes =
        new ClassFileImporter()
            .importClasses(
                io.lantern.link.archfixture.matchers.HttpLinker.class,
                io.lantern.link.archfixture.matchers.UnlistedLinker.class,
                io.lantern.link.archfixture.matchers.Addresses.class);

    assertThatThrownBy(() -> ArchitectureRules.MATCHERS_READ_ADDRESS_CLAIMS.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("HttpLinker.link(java.lang.String)")
        .hasMessageNotContaining("UnlistedLinker");
  }

  @Test
  void aMatcherThatPlacesDatasourcesItselfIsRejected() {
    JavaClasses classes =
        new ClassFileImporter()
            .importClasses(
                io.lantern.link.archfixture.matchers.DataLinker.class,
                io.lantern.link.archfixture.matchers.RedisLinker.class,
                io.lantern.link.archfixture.matchers.Deployments.class);

    assertThatThrownBy(() -> ArchitectureRules.MATCHERS_READ_ADDRESS_CLAIMS.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("DataLinker.link(java.lang.String)")
        .hasMessageContaining("(1 times)")
        .hasMessageNotContaining("RedisLinker.link");
  }
}
