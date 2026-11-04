package io.lantern.archtest;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.lantern.core.types.Types;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.derive.Vocabularies;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
   * knowledge-build epic, section 5): extract, link, derive, Investigate, Describe and the other
   * writing roles, compose, render.
   */
  static final List<Stage> STAGES =
      List.of(
          Stage.of("extract", "io.lantern.extract.."),
          new Stage(
              "link",
              resideInAnyPackage("io.lantern.link..")
                  .and(DescribedPredicate.not(investigateCode()))
                  .as("the link stage [io.lantern.link.., Investigate excepted]")),
          Stage.of("derive", "io.lantern.derive.."),
          new Stage("investigate", investigateCode()),
          Stage.of("describe", "io.lantern.summarize.."),
          Stage.of("compose", "io.lantern.compose.."),
          new Stage("render", RENDERERS));

  /**
   * Investigate's code, which comes before Describe (knowledge-build epic, section 5; #554): the
   * package {@code io.lantern.investigate} once it exists, and until then Investigate's classes in
   * the linker, those whose names start with {@code Investigat}.
   */
  static DescribedPredicate<JavaClass> investigateCode() {
    return DescribedPredicate.describe(
        "the investigate stage [io.lantern.investigate.., io.lantern.link.Investigat*]",
        c ->
            c.getPackageName().startsWith("io.lantern.investigate")
                || (c.getPackageName().startsWith("io.lantern.link")
                    && c.getName().startsWith(c.getPackageName() + ".Investigat")));
  }

  /**
   * The composer only reads the stored model: it calls no LLM and runs no role, whose texts are
   * stored before it composes (design principle 10; knowledge-build epic, sections 5 and 12; ADR
   * 0116).
   */
  static final ArchRule COMPOSER_CALLS_NO_LLM =
      noClasses()
          .that()
          .resideInAPackage("io.lantern.compose..")
          .should()
          .dependOnClassesThat(llmCode())
          .because("the composer reads only the stored model, never an LLM, the harness or a role");

  /**
   * LLM code: the LLM client, the harness, the roles of the LLM-roles stage, and the classes of the
   * linker that call the LLM or the harness (Investigate and Resolve, which run in the link stage).
   */
  static DescribedPredicate<JavaClass> llmCode() {
    DescribedPredicate<JavaClass> llmOrHarness =
        resideInAnyPackage("io.lantern.llm..", "io.lantern.harness..");
    return DescribedPredicate.describe(
        "are LLM, harness or role code",
        c ->
            llmOrHarness.test(c)
                || c.getPackageName().startsWith("io.lantern.summarize")
                || (c.getPackageName().startsWith("io.lantern.link")
                    && c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> llmOrHarness.test(d.getTargetClass()))));
  }

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
   * The fact attributes and predicate names are read by the model alone: a composer class reads
   * what the model's typed queries say, never a fact's attributes or predicate, and never the
   * model's index of facts (knowledge-build epic, section 12; design, Composition reads the model;
   * #761).
   */
  static final ArchRule COMPOSER_READS_THE_MODEL =
      noClasses()
          .that()
          .resideInAPackage("io.lantern.compose..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("io.lantern.derive.Knowledge")
          .orShould()
          .accessTargetWhere(
              DescribedPredicate.describe(
                  "Fact.attributes() or Fact.predicate()",
                  (com.tngtech.archunit.core.domain.JavaAccess<?> access) ->
                      access.getTargetOwner().isEquivalentTo(io.lantern.core.fact.Fact.class)
                          && (access.getName().equals("attributes")
                              || access.getName().equals("predicate"))))
          .because(
              "the composer reads typed ArchitectureModel queries, not raw facts (knowledge-build"
                  + " epic, section 12; #761)");

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

  /**
   * The modules that read facts ({@code docs/design.md}, one place reads predicate names): core,
   * the extractors, the linker, derivation, the LLM harness, the LLM roles, the composer and the
   * app with its renderers (#763).
   */
  static final String[] FACT_READERS = {
    "io.lantern.core..",
    "io.lantern.extract..",
    "io.lantern.link..",
    "io.lantern.derive..",
    "io.lantern.harness..",
    "io.lantern.summarize..",
    "io.lantern.compose..",
    "io.lantern.app.."
  };

  /**
   * Constants whose value only happens to equal a type's name: a command option, a field of an LLM
   * role's answer, a value of an attribute ({@code by}, {@code via}, a component's role, a scope) a
   * finding's kind or an extractor's name. Renaming the type must not change them (ADR 0114, #763).
   */
  static final Set<String> NOT_TYPE_NAMES =
      Set.of(
          "io.lantern.app.AnalyzeCommand.SYSTEM",
          "io.lantern.app.AnalyzeCommand.TITLE",
          "io.lantern.summarize.describe.DescribeRole.RESPONSIBILITY_FIELD",
          "io.lantern.summarize.describe.NameGroups.RESPONSIBILITY_FIELD",
          "io.lantern.summarize.describe.PurposeRole.PURPOSE_FIELD",
          "io.lantern.summarize.describe.WordingPrompt.TITLE",
          "io.lantern.summarize.describe.FlowJudgements.TITLE",
          "io.lantern.link.AddressResolution.BY_DATASOURCE",
          "io.lantern.derive.Findings.VIA_JAVA_RELEASE",
          "io.lantern.derive.Components.ENTRY_POINT",
          "io.lantern.derive.Components.ASPECT_ROLE",
          "io.lantern.derive.ArchitectureModel.SYSTEM_SCOPE",
          "io.lantern.derive.Groups.SYSTEM",
          "io.lantern.derive.Findings.VERSION_SKEW",
          "io.lantern.derive.Findings.UNROUTED_ENDPOINT",
          "io.lantern.extract.frontend.FrontendExtractor.NAME",
          "io.lantern.extract.http.HttpClientExtractor.NAME");

  /**
   * No module that reads facts defines a constant holding a vocabulary type's name: each names
   * predicates and entity types through the generated {@code Types}, the typed layer, so a type is
   * named in one place (ADR 0114, widened by #763).
   */
  static final ArchRule NAMES_TYPES_THROUGH_THE_TYPED_LAYER =
      fields()
          .that()
          .areStatic()
          .and()
          .areFinal()
          .and()
          .haveRawType(String.class)
          .and()
          .areDeclaredInClassesThat(
              DescribedPredicate.and(
                  resideInAnyPackage(FACT_READERS),
                  DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(Types.class))))
          .should(
              new ArchCondition<JavaField>("not hold a vocabulary type's name") {
                @Override
                public void check(JavaField field, ConditionEvents events) {
                  String name = field.getOwner().getName() + "." + field.getName();
                  Object value = value(field);
                  if (value instanceof String text
                      && TYPE_NAMES.get().contains(text)
                      && !NOT_TYPE_NAMES.contains(name)
                      && !isGeneratedRecordType(field)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            field,
                            name + " holds the type name '" + text + "'; name it through Types"));
                  }
                }
              })
          .allowEmptyShould(true)
          .because("the typed layer names every vocabulary type in one place (ADR 0114)");

  /** The names of this version of Lantern's vocabulary types, read when first needed. */
  static final Supplier<Set<String>> TYPE_NAMES =
      new Supplier<>() {
        private Set<String> names;

        @Override
        public synchronized Set<String> get() {
          if (names == null) {
            names =
                Vocabularies.standard().types().stream()
                    .map(VocabularyType::name)
                    .collect(Collectors.toUnmodifiableSet());
          }
          return names;
        }
      };

  /**
   * The {@code TYPE} of a generated attribute record (ADR 0090): part of the typed layer, generated
   * from the vocabulary and checked against it as {@code Types} is.
   */
  private static boolean isGeneratedRecordType(JavaField field) {
    JavaClass owner = field.getOwner();
    return field.getName().equals("TYPE")
        && owner.isRecord()
        && owner.getPackageName().endsWith(".attributes")
        && owner.getSimpleName().endsWith("Attributes");
  }

  private static Object value(JavaField field) {
    try {
      Field reflected = field.reflect();
      reflected.setAccessible(true);
      return reflected.get(null);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static boolean isRenderer(JavaClass c) {
    if (c.getSimpleName().endsWith("Renderer")) {
      return true;
    }
    return c.getEnclosingClass().map(ArchitectureRules::isRenderer).orElse(false);
  }

  private ArchitectureRules() {}
}
