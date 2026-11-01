package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.link.AddressResolution;
import io.lantern.link.ClaimQuery;
import io.lantern.link.ClaimSink;
import io.lantern.link.DeployableUnits;
import io.lantern.link.JdbcRuleRuns;
import io.lantern.link.LinkRule;
import io.lantern.link.Linker;
import io.lantern.link.People;
import io.lantern.link.RuleInfo;
import io.lantern.link.ValueResolution;
import io.lantern.link.View;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * The linker runs a rule again only when the facts it reads, directly or through another rule's
 * facts, or its version changed since its last run; otherwise the rule's facts keep holding (ADR
 * 0095).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LinkRulesIT {

  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM observations").update();
    jdbc.sql("DELETE FROM claims").update();
    jdbc.sql("DELETE FROM link_rule_runs").update();
  }

  /**
   * A rule that gives, for each fact of the predicate it reads, a fact of the predicate it writes
   * between the same entities, with evidence of its own, so its facts do not change when only the
   * evidence of what it reads does.
   */
  private record Copy(String name, String version, String from, String to) implements LinkRule {

    @Override
    public RuleInfo info() {
      return new RuleInfo(name, version, Origin.Kind.STATIC);
    }

    @Override
    public Set<String> reads() {
      return Set.of(from);
    }

    @Override
    public Set<String> writes() {
      return Set.of(to);
    }

    @Override
    public void derive(ClaimQuery in, ClaimSink out, View view) {
      for (Fact f : in.all(from)) {
        EntityId object =
            f.object().kind() == FactObject.Kind.ENTITY
                ? f.object().entityId()
                : EntityId.parse("type:g." + f.object().value());
        out.add(
            Fact.create(
                f.subject(),
                to,
                FactObject.entity(object),
                FactJson.newObject(),
                origin(),
                Evidence.of("lib", "rule", null, null),
                1.0,
                Validity.from(in.snapshot())));
      }
    }
  }

  private static final Copy CONTAINS_TO_CALLS =
      new Copy("calls", "1", BuiltInTypes.CONTAINS, BuiltInTypes.CALLS);
  private static final Copy CALLS_TO_IMPLEMENTS =
      new Copy("implements", "1", BuiltInTypes.CALLS, BuiltInTypes.IMPLEMENTS);
  private static final Copy CONFIG_TO_EXTENDS =
      new Copy("extends", "1", ConfigExtractor.CONFIG_VALUE, BuiltInTypes.EXTENDS);

  private Linker linker(LinkRule... rules) {
    return new Linker(facts, List.of(rules), new JdbcRuleRuns(jdbc));
  }

  private static Fact extracted(
      String extractor, String subject, String predicate, FactObject object, int line, long at) {
    ObjectNode attributes = FactJson.newObject();
    if (predicate.equals(ConfigExtractor.CONFIG_VALUE)) {
      attributes.put("key", "spring.application.name");
    }
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        attributes,
        new Origin(Origin.Kind.STATIC, extractor, "1"),
        Evidence.of("lib", "pom.xml", new Evidence.LineRange(line, line), null),
        1.0,
        Validity.from(at));
  }

  /** The library's facts: a module with a type, and its application's name. */
  private static List<Fact> lib(long at, String name, int line) {
    return List.of(
        extracted(
            MavenExtractor.NAME,
            "module:g:lib",
            BuiltInTypes.CONTAINS,
            FactObject.entity(EntityId.parse("type:g.App")),
            line,
            at),
        extracted(
            ConfigExtractor.NAME,
            "module:g:lib",
            ConfigExtractor.CONFIG_VALUE,
            FactObject.string(name),
            1,
            at));
  }

  private Linker.Result link(Linker linker, long at, List<Fact> found) {
    facts.recordSnapshot(at, Origin.Kind.STATIC, Set.of("lib"), linker.origins(), found);
    return linker.link(at, Set.of("lib"));
  }

  private List<String> derivedAt(long at) {
    return facts.findValidAt(at, Set.of("lib")).stream()
        .filter(f -> !f.origin().name().equals(MavenExtractor.NAME))
        .filter(f -> !f.origin().name().equals(ConfigExtractor.NAME))
        .map(f -> f.origin().name() + ": " + f.predicate() + " " + f.object().value())
        .sorted()
        .toList();
  }

  @Test
  void rulesRunAfterTheRulesWhoseFactsTheyRead() {
    Linker linker = linker(CALLS_TO_IMPLEMENTS, CONFIG_TO_EXTENDS, CONTAINS_TO_CALLS);

    assertThat(linker.order()).containsExactly("extends", "calls", "implements");
  }

  @Test
  void aChangeToOnePredicateRunsOnlyTheRulesThatReadIt() {
    Linker linker = linker(CONTAINS_TO_CALLS, CALLS_TO_IMPLEMENTS, CONFIG_TO_EXTENDS);
    List<String> all =
        List.of(
            "calls: CALLS type:g.App",
            "extends: EXTENDS type:g.name",
            "implements: IMPLEMENTS type:g.App");

    assertThat(link(linker, 1, lib(1, "name", 10)).ran())
        .containsExactly("calls", "implements", "extends");
    assertThat(derivedAt(1)).isEqualTo(all);

    // Nothing changed: no rule runs, and their facts keep holding.
    Linker.Result same = link(linker, 2, lib(2, "name", 10));
    assertThat(same.ran()).isEmpty();
    assertThat(same.facts()).hasSize(3);
    assertThat(derivedAt(2)).isEqualTo(all);

    // Only the config changed: only the rule that reads it runs.
    assertThat(link(linker, 3, lib(3, "other", 10)).ran()).containsExactly("extends");
    assertThat(derivedAt(3))
        .containsExactly(
            "calls: CALLS type:g.App",
            "extends: EXTENDS type:g.other",
            "implements: IMPLEMENTS type:g.App");

    // The containment moved in its file: the rule that reads it runs, but gives the same facts, so
    // the rule that reads those does not.
    assertThat(link(linker, 4, lib(4, "other", 12)).ran()).containsExactly("calls");
  }

  @Test
  void aChangeReachesTheRulesThatReadItThroughAnotherRulesFacts() {
    Linker linker = linker(CONTAINS_TO_CALLS, CALLS_TO_IMPLEMENTS, CONFIG_TO_EXTENDS);
    link(linker, 1, lib(1, "name", 10));

    List<Fact> moved =
        List.of(
            extracted(
                MavenExtractor.NAME,
                "module:g:lib",
                BuiltInTypes.CONTAINS,
                FactObject.entity(EntityId.parse("type:g.Other")),
                10,
                2),
            lib(2, "name", 10).get(1));

    assertThat(link(linker, 2, moved).ran()).containsExactly("calls", "implements");
    assertThat(derivedAt(2))
        .containsExactly(
            "calls: CALLS type:g.Other",
            "extends: EXTENDS type:g.name",
            "implements: IMPLEMENTS type:g.Other");
  }

  @Test
  void aNewVersionOfARuleRunsIt() {
    link(linker(CONTAINS_TO_CALLS, CALLS_TO_IMPLEMENTS, CONFIG_TO_EXTENDS), 1, lib(1, "name", 10));

    Linker newer =
        linker(
            CONTAINS_TO_CALLS,
            CALLS_TO_IMPLEMENTS,
            new Copy("extends", "2", ConfigExtractor.CONFIG_VALUE, BuiltInTypes.EXTENDS));

    assertThat(link(newer, 2, lib(2, "name", 10)).ran()).containsExactly("extends");
  }

  @Test
  void aChangeToLanternsCodeRunsEveryRule() {
    List<LinkRule> rules = List.of(CONTAINS_TO_CALLS, CALLS_TO_IMPLEMENTS, CONFIG_TO_EXTENDS);
    link(new Linker(facts, rules, new JdbcRuleRuns(jdbc), "code-1"), 1, lib(1, "name", 10));

    assertThat(
            link(new Linker(facts, rules, new JdbcRuleRuns(jdbc), "code-1"), 2, lib(2, "name", 10))
                .ran())
        .isEmpty();
    assertThat(
            link(new Linker(facts, rules, new JdbcRuleRuns(jdbc), "code-2"), 3, lib(3, "name", 10))
                .ran())
        .containsExactly("calls", "implements", "extends");
  }

  @Test
  void aRetiredRulesFactsAreNeitherReadNorKept() {
    Linker linker = linker(CONTAINS_TO_CALLS, CALLS_TO_IMPLEMENTS, CONFIG_TO_EXTENDS);
    // A link the cross-repo links step recorded before #575 split it.
    Fact old =
        Fact.create(
            EntityId.parse("module:g:lib"),
            BuiltInTypes.CALLS,
            FactObject.entity(EntityId.parse("type:g.Old")),
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "cross-repo-links", "18"),
            Evidence.of("lib", "pom.xml", null, null),
            1.0,
            Validity.from(1));
    facts.recordDerived(1, old.origin(), Set.of("lib"), List.of(old));

    link(linker, 2, lib(2, "name", 10));

    assertThat(derivedAt(2))
        .containsExactly(
            "calls: CALLS type:g.App",
            "extends: EXTENDS type:g.name",
            "implements: IMPLEMENTS type:g.App");
  }

  @Test
  void aRuleWhoseFactsChangedElsewhereRunsAgain() {
    Linker linker = linker(CONTAINS_TO_CALLS, CALLS_TO_IMPLEMENTS, CONFIG_TO_EXTENDS);
    link(linker, 1, lib(1, "name", 10));
    // Another linker without the rule ends its facts, as an older Lantern would.
    link(linker(CONTAINS_TO_CALLS, CONFIG_TO_EXTENDS), 2, lib(2, "name", 10));

    assertThat(link(linker, 3, lib(3, "name", 10)).ran()).containsExactly("implements");
    assertThat(derivedAt(3)).contains("implements: IMPLEMENTS type:g.App");
  }

  /** A setting of the library's {@code application.yml}. */
  private static Fact setting(String key, String value, int line, long at) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("key", key);
    return Fact.create(
        EntityId.parse("module:g:lib"),
        ConfigExtractor.CONFIG_VALUE,
        FactObject.string(value),
        attributes,
        new Origin(Origin.Kind.STATIC, ConfigExtractor.NAME, "1"),
        Evidence.of(
            "lib", "src/main/resources/application.yml", new Evidence.LineRange(line, line), null),
        1.0,
        Validity.from(at));
  }

  private static List<Fact> configured(long at, String name, String ordersUrl) {
    return List.of(
        lib(at, "unused", 10).getFirst(),
        setting("spring.application.name", name, 1, at),
        setting("orders.url", ordersUrl, 2, at));
  }

  /**
   * #591: a change to one setting of an {@code application.yml} runs value resolution and the rules
   * that read config or its facts again, and not the units (which read only the application's name
   * and which modules have config) or the people (who read no config).
   */
  @Test
  void aChangeToOneConfigSettingRunsOnlyValueResolutionAndWhatReadsIt() {
    Linker linker = Linker.standard(facts, new JdbcRuleRuns(jdbc));
    List<String> all = linker.order();

    assertThat(link(linker, 1, configured(1, "shop", "http://orders:8080")).ran())
        .containsExactlyElementsOf(all);
    assertThat(link(linker, 2, configured(2, "shop", "http://orders:8080")).ran()).isEmpty();

    List<String> downstream =
        all.stream()
            .filter(r -> !r.equals(DeployableUnits.NAME) && !r.equals(People.NAME))
            .toList();
    assertThat(downstream).contains(ValueResolution.NAME, AddressResolution.NAME, "http-links");
    assertThat(link(linker, 3, configured(3, "shop", "http://orders:9090")).ran())
        .containsExactlyElementsOf(downstream);

    // The application's name is what the units read: they run again.
    assertThat(link(linker, 4, configured(4, "store", "http://orders:9090")).ran())
        .contains(DeployableUnits.NAME)
        .doesNotContain(People.NAME);
  }
}
