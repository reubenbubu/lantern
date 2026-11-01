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
          .resideInAnyPackage("io.lantern.llm..", "io.lantern.harness..")
          .because("extractors must not depend on lantern-llm or the LLM harness");

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
              "io.lantern.harness..",
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
   * name; the data store matchers join with their slice (#596).
   */
  static final List<String> MATCHERS_READING_ADDRESS_CLAIMS =
      List.of(
          "HttpLinker",
          "RouteLinker",
          "FrontendLinker",
          "IdentityLinker",
          "MailLinker",
          "DataLinker",
          "RedisLinker");

  /**
   * Those matchers call no address helper: where an address leads, or where a deployment puts a
   * datasource ({@code Deployments.place}, #596), is address resolution's to decide, and theirs to
   * read (knowledge-build epic, section 8, rule 3; ADR 0042, amendment for #589).
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
                  "Addresses or Deployments.place",
                  (com.tngtech.archunit.core.domain.JavaAccess<?> access) ->
                      access.getTargetOwner().getPackageName().startsWith("io.lantern.link")
                          && (access.getTargetOwner().getSimpleName().equals("Addresses")
                              || (access.getTargetOwner().getSimpleName().equals("Deployments")
                                  && access.getName().equals("place")))))
          .because("address resolution decides where addresses lead; matchers read its claims");

  /**
   * The messaging matcher reads value resolution's relations, not its message facts (#597): it uses
   * none of their attribute records ({@code MessageSendAttributes} and the others). The predicates'
   * names are constants the compiler inlines, so the records are what shows a read.
   */
  static final ArchRule MESSAGING_MATCHER_READS_RELATIONS =
      noClasses()
          .that()
          .resideInAPackage("io.lantern.link..")
          .and()
          .haveSimpleName("MessageLinker")
          .should()
          .dependOnClassesThat(
              DescribedPredicate.describe(
                  "are the attribute records of message facts",
                  (JavaClass c) ->
                      c.getSimpleName()
                          .matches("Message(Send|Listener|Binding|Destination)Attributes")))
          .because("the messaging matcher joins on value resolution's relations (#597)");

  /**
   * The code that may resolve placeholders: value resolution (knowledge-build epic, section 7; ADR
   * 0094) with its helpers, the config extractor reading its own files, and the SQL extractor,
   * which still reads config to find its migrations (ADR 0094, amendment for #569).
   */
  static final DescribedPredicate<JavaClass> VALUE_RESOLUTION =
      DescribedPredicate.describe(
          "value resolution (ValueResolution, CodeValues, Literals, EnvironmentValues), the config"
              + " extractor or the SQL extractor",
          c -> {
            String name = c.getName().replaceAll("\\$.*", "");
            return c.getPackageName().startsWith("io.lantern.extract.config")
                || List.of(
                        "io.lantern.link.ValueResolution",
                        "io.lantern.link.CodeValues",
                        "io.lantern.link.Literals",
                        "io.lantern.link.EnvironmentValues",
                        "io.lantern.extract.sql.SqlExtractor",
                        "io.lantern.extract.sql.Settings")
                    .contains(name);
          });

  /** The calls that resolve placeholders against config, or read config to resolve with. */
  private static final List<String> RESOLVING_CALLS =
      List.of(
          "io.lantern.extract.config.Placeholders.resolve",
          "io.lantern.extract.config.ConfigExtractor.resolve",
          "io.lantern.extract.config.ConfigExtractor.resolveWithoutConfig",
          "io.lantern.extract.config.ConfigExtractor.read",
          "io.lantern.extract.config.ModuleConfig.resolve",
          "io.lantern.extract.config.ModuleConfig.values",
          "io.lantern.extract.config.ModuleValues.of",
          "io.lantern.extract.config.CodeConfig.forModule",
          "io.lantern.extract.config.CodeConfig$View.resolve");

  /**
   * Placeholders are resolved only by value resolution (#591; #522): no other code calls what
   * resolves them against config, nor gives the extractors' resolving functions a config of its own
   * ({@code ValueLookup}, {@code GatewayExtractor.Resolver}), so an extractor keeps values as
   * written and a change to one config file re-runs only value resolution and what reads it.
   */
  static final ArchRule PLACEHOLDERS_RESOLVED_BY_VALUE_RESOLUTION =
      com.tngtech.archunit.lang.CompositeArchRule.of(
              noClasses()
                  .that(DescribedPredicate.not(VALUE_RESOLUTION))
                  .should()
                  .accessTargetWhere(
                      DescribedPredicate.describe(
                          "a call that resolves placeholders " + RESOLVING_CALLS,
                          (com.tngtech.archunit.core.domain.JavaAccess<?> access) ->
                              RESOLVING_CALLS.contains(
                                  access.getTargetOwner().getName() + "." + access.getName())))
                  .because("only value resolution resolves placeholders (ADR 0094)"))
          .and(
              noClasses()
                  .that(DescribedPredicate.not(VALUE_RESOLUTION))
                  .should()
                  .implement("io.lantern.extract.config.ValueLookup")
                  .orShould()
                  .implement("io.lantern.extract.gateway.GatewayExtractor$Resolver")
                  .because(
                      "only value resolution gives the extractors' resolving functions a config"
                          + " (ADR 0094)"));

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
