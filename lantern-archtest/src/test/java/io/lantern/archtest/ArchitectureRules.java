package io.lantern.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/** Architecture rules shared by the tests. */
final class ArchitectureRules {

  /** Code is the source of truth: extractors work from code alone and never call the LLM. */
  static final ArchRule EXTRACTORS_MUST_NOT_DEPEND_ON_LLM =
      noClasses()
          .that()
          .resideInAPackage("io.lantern.extract..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.lantern.llm..")
          .because("extractors must not depend on lantern-llm");

  private ArchitectureRules() {}
}
