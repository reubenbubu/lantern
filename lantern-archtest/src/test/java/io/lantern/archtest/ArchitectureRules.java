package io.lantern.archtest;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

  /**
   * A renderer: a class of the app, nested ones included, whose name ends in {@code Renderer}
   * ({@code docs/design.md}, Rendering and templates).
   */
  static final DescribedPredicate<JavaClass> RENDERERS =
      DescribedPredicate.describe(
          "renderers",
          c -> c.getPackageName().startsWith("io.lantern.app") && ArchitectureRules.isRenderer(c));

  /**
   * The stages in the order they build the knowledge ({@code docs/design.md}, Overview; the
   * knowledge-build epic, section 5): extract, link, derive, the LLM roles, compose, render.
   */
  static final List<Stage> STAGES =
      List.of(
          Stage.of("extract", "io.lantern.extract.."),
          Stage.of("link", "io.lantern.link.."),
          Stage.of("derive", "io.lantern.derive.."),
          Stage.of("LLM roles", "io.lantern.summarize..", "io.lantern.investigate.."),
          Stage.of("compose", "io.lantern.compose.."),
          new Stage("render", RENDERERS));

  /** No stage depends on a later one, so one stage can change without breaking an earlier one. */
  static final ArchRule STAGES_DEPEND_ONLY_ON_EARLIER_STAGES = stageOrder();

  /**
   * Renderers only format the document model: they read no facts from the store and call no stage
   * that makes them.
   */
  static final ArchRule RENDERERS_MUST_NOT_READ_FACTS =
      noClasses()
          .that(RENDERERS)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.lantern.extract..",
              "io.lantern.link..",
              "io.lantern.derive..",
              "io.lantern.summarize..",
              "io.lantern.investigate..",
              "io.lantern.llm..")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("io.lantern.core.fact.FactRepository")
          .because("renderers format the document model and make no content decisions");

  /**
   * The linker's query layer: the classes that read a fact's attributes (ADR 0095, amendments for
   * #580 and #576).
   */
  static final DescribedPredicate<JavaClass> LINK_QUERY_LAYER =
      DescribedPredicate.describe(
          "the query layer (Claims, ClaimQuery)",
          c ->
              List.of("io.lantern.link.Claims", "io.lantern.link.ClaimQuery")
                  .contains(c.getName().replaceAll("\\$.*", "")));

  /**
   * Linker code reads facts as their typed records, through the query layer, never attribute JSON
   * by name (knowledge-build epic, section 8, Rules).
   */
  static final ArchRule LINKER_READS_TYPED_FACTS =
      noClasses()
          .that()
          .resideInAPackage("io.lantern.link..")
          .and(DescribedPredicate.not(LINK_QUERY_LAYER))
          .should()
          .accessTargetWhere(
              DescribedPredicate.describe(
                  "Fact.attributes()",
                  (com.tngtech.archunit.core.domain.JavaAccess<?> access) ->
                      access.getTargetOwner().isEquivalentTo(io.lantern.core.fact.Fact.class)
                          && access.getName().equals("attributes")))
          .because("linker code reads typed records through the query layer (ADR 0095)");

  /**
   * The matchers that read where addresses lead from address resolution's claims (#589), by simple
   * name; the others join as their slices land (#594, #595, #596).
   */
  static final List<String> MATCHERS_READING_ADDRESS_CLAIMS = List.of("HttpLinker");

  /**
   * Those matchers call no address helper: where an address leads is address resolution's to
   * decide, and theirs to read (knowledge-build epic, section 8, rule 3; ADR 0042, amendment for
   * #589).
   */
  static final ArchRule MATCHERS_READ_ADDRESS_CLAIMS =
      noClasses()
          .that()
          .resideInAPackage("io.lantern.link..")
          .and(
              DescribedPredicate.describe(
                  "are matchers that read address claims " + MATCHERS_READING_ADDRESS_CLAIMS,
                  (JavaClass c) ->
                      MATCHERS_READING_ADDRESS_CLAIMS.contains(
                          c.getName()
                              .substring(c.getName().lastIndexOf('.') + 1)
                              .replaceAll("\\$.*", ""))))
          .should()
          .accessTargetWhere(
              DescribedPredicate.describe(
                  "Addresses",
                  (com.tngtech.archunit.core.domain.JavaAccess<?> access) ->
                      access.getTargetOwner().getSimpleName().equals("Addresses")
                          && access
                              .getTargetOwner()
                              .getPackageName()
                              .startsWith("io.lantern.link")))
          .because("address resolution decides where addresses lead; matchers read its claims");

  /** One stage and the classes in it. */
  record Stage(String name, DescribedPredicate<JavaClass> classes) {

    static Stage of(String name, String... packages) {
      return new Stage(
          name,
          resideInAnyPackage(packages).as("the " + name + " stage " + Arrays.toString(packages)));
    }
  }

  private static ArchRule stageOrder() {
    List<ArchRule> rules = new ArrayList<>();
    for (int i = 0; i < STAGES.size() - 1; i++) {
      Stage stage = STAGES.get(i);
      DescribedPredicate<JavaClass> later = STAGES.get(i + 1).classes();
      List<String> names = new ArrayList<>(List.of(STAGES.get(i + 1).name()));
      for (Stage next : STAGES.subList(i + 2, STAGES.size())) {
        later = later.or(next.classes());
        names.add(next.name());
      }
      rules.add(
          noClasses()
              .that(stage.classes())
              .should()
              .dependOnClassesThat(later.as("are in a later stage " + names))
              .because("the " + stage.name() + " stage comes before " + String.join(", ", names))
              .allowEmptyShould(true));
    }
    CompositeArchRule all = CompositeArchRule.of(rules.getFirst());
    for (ArchRule rule : rules.subList(1, rules.size())) {
      all = all.and(rule);
    }
    return all;
  }

  private static boolean isRenderer(JavaClass c) {
    if (c.getSimpleName().endsWith("Renderer")) {
      return true;
    }
    return c.getEnclosingClass().map(ArchitectureRules::isRenderer).orElse(false);
  }

  private ArchitectureRules() {}
}
