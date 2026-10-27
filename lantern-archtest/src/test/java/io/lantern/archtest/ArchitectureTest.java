package io.lantern.archtest;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Checks the architecture rules against the production code of all modules. */
@AnalyzeClasses(packages = "io.lantern", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule extractorsMustNotDependOnLlm =
      ArchitectureRules.EXTRACTORS_MUST_NOT_DEPEND_ON_LLM;
}
