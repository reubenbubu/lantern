package io.lantern.archtest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.lantern.extract.archfixture.ExtractorCallingLlm;
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
}
