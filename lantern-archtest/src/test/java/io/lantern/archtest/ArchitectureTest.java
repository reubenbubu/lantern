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

  @ArchTest
  static final ArchRule stagesDependOnlyOnEarlierStages =
      ArchitectureRules.STAGES_DEPEND_ONLY_ON_EARLIER_STAGES;

  @ArchTest
  static final ArchRule renderersMustNotReadFacts = ArchitectureRules.RENDERERS_MUST_NOT_READ_FACTS;

  @ArchTest
  static final ArchRule linkerReadsTypedFacts = ArchitectureRules.LINKER_READS_TYPED_FACTS;

  @ArchTest
  static final ArchRule matchersReadAddressClaims = ArchitectureRules.MATCHERS_READ_ADDRESS_CLAIMS;

  @ArchTest
  static final ArchRule messagingMatcherReadsRelations =
      ArchitectureRules.MESSAGING_MATCHER_READS_RELATIONS;

  @ArchTest
  static final ArchRule placeholdersResolvedByValueResolution =
      ArchitectureRules.PLACEHOLDERS_RESOLVED_BY_VALUE_RESOLUTION;
}
