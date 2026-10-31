package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.link.attributes.CallsHttpAttributes;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The order of link rules, and what a rule may read and write (ADR 0095). */
class LinkRuleTest {

  private record Rule(String name, Set<String> reads, Set<String> writes) implements LinkRule {

    @Override
    public RuleInfo info() {
      return new RuleInfo(name, "1", Origin.Kind.STATIC);
    }

    @Override
    public void derive(ClaimQuery in, ClaimSink out, View view) {}
  }

  private static List<String> names(List<LinkRule> rules) {
    return rules.stream().map(r -> r.info().name()).toList();
  }

  @Test
  void aRuleRunsAfterTheRulesThatWriteWhatItReadsAndOtherwiseInTheOrderGiven() {
    Rule links = new Rule("links", Set.of("unit"), Set.of("link"));
    Rule units = new Rule("units", Set.of(LinkRule.EXTRACTED), Set.of("unit"));
    Rule people = new Rule("people", Set.of(LinkRule.EXTRACTED), Set.of("person"));
    Rule checks = new Rule("checks", Set.of("link", "person"), Set.of("check"));

    assertThat(names(RuleOrder.of(List.of(checks, links, units, people))))
        .containsExactly("units", "links", "people", "checks");
  }

  @Test
  void aRuleDoesNotWaitForItself() {
    Rule tech = new Rule("tech", Set.of("technology"), Set.of("technology"));

    assertThat(names(RuleOrder.of(List.of(tech)))).containsExactly("tech");
  }

  @Test
  void noRuleIsNamedAsAnExtractor() {
    // The linker tells its facts from the extractors' by origin name.
    Set<String> extractors =
        new java.util.HashSet<>(io.lantern.extract.ExtractorVocabularies.bySource().keySet());
    extractors.add(io.lantern.extract.people.PeopleExtractor.NAME);
    List<String> rules = new java.util.ArrayList<>(names(Rules.standard()));
    rules.add(LinkResolver.NAME);

    assertThat(rules).doesNotContainAnyElementsOf(extractors);
  }

  @Test
  void aCycleFailsNamingItsRules() {
    Rule a = new Rule("a", Set.of("q"), Set.of("p"));
    Rule b = new Rule("b", Set.of("p"), Set.of("q"));
    Rule c = new Rule("c", Set.of(LinkRule.EXTRACTED), Set.of("r"));

    assertThatThrownBy(() -> RuleOrder.of(List.of(a, b, c)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle: a, b");
  }

  @Test
  void twoRulesWithOneNameFail() {
    Rule a = new Rule("a", Set.of(), Set.of("p"));

    assertThatThrownBy(() -> RuleOrder.of(List.of(a, new Rule("a", Set.of(), Set.of("q")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("named a");
  }

  @Test
  void theStandardRulesRunValuesUnitsLinksTheResolverThenClaimChecks() {
    Rule resolver =
        new Rule(
            "link-resolver",
            Set.of(CrossRepoLinks.UNRESOLVED_LINK),
            Set.of(CrossRepoLinks.CALLS_HTTP, CrossRepoLinks.SENDS_MESSAGES_TO));

    assertThat(
            names(
                RuleOrder.of(
                    List.of(
                        new ValueViews(),
                        new DeployableUnits(),
                        new CrossRepoLinks(),
                        new ClaimChecks(),
                        resolver))))
        .containsExactly(
            ValueViews.NAME,
            DeployableUnits.NAME,
            CrossRepoLinks.NAME,
            "link-resolver",
            ClaimChecks.NAME);
  }

  private static Fact call(String predicate, String attributes) {
    return Fact.create(
        EntityId.parse("service:g:a"),
        predicate,
        FactObject.entity(EntityId.parse("service:g:b")),
        attributes == null
            ? FactJson.newObject()
            : (tools.jackson.databind.node.ObjectNode) FactJson.parse(attributes),
        new Origin(Origin.Kind.STATIC, "links", "1"),
        Evidence.of("a", "pom.xml", null, null),
        1.0,
        Validity.from(1));
  }

  @Test
  void aQueryReadsFactsAsTheirRecords() {
    RuleInfo rule = new RuleInfo("checks", "1", Origin.Kind.STATIC);
    ClaimQuery in =
        new ClaimQuery(
            rule,
            Set.of(CrossRepoLinks.CALLS_HTTP),
            Set.of(),
            List.of(call(CrossRepoLinks.CALLS_HTTP, "{\"endpoint\":\"GET /a\",\"path\":\"/a\"}")),
            1);

    List<ClaimQuery.Claim<CallsHttpAttributes>> calls = in.all(CallsHttpAttributes.class);

    assertThat(calls)
        .singleElement()
        .satisfies(c -> assertThat(c.attributes().path()).isEqualTo("/a"));
    assertThat(in.from(EntityId.parse("service:g:a"), CallsHttpAttributes.class)).hasSize(1);
    assertThat(in.from(EntityId.parse("service:g:b"), CallsHttpAttributes.class)).isEmpty();
  }

  @Test
  void aQueryOfAPredicateTheRuleDoesNotReadFails() {
    ClaimQuery in =
        new ClaimQuery(
            new RuleInfo("checks", "1", Origin.Kind.STATIC),
            Set.of("person"),
            Set.of(),
            List.of(),
            1);

    assertThatThrownBy(() -> in.all(CallsHttpAttributes.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not declare");
  }

  @Test
  void readingEveryExtractedFactDoesNotCoverAnotherRulesPredicates() {
    ClaimQuery in =
        new ClaimQuery(
            new RuleInfo("checks", "1", Origin.Kind.STATIC),
            Set.of(LinkRule.EXTRACTED),
            Set.of(CrossRepoLinks.CALLS_HTTP),
            List.of(),
            1);

    assertThat(in.all("config-value")).isEmpty();
    assertThatThrownBy(() -> in.all(CrossRepoLinks.CALLS_HTTP))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not declare");
  }

  @Test
  void aSinkTakesOnlyTheRulesOriginAndWrites() {
    ClaimSink out =
        new ClaimSink(
            new RuleInfo("links", "1", Origin.Kind.STATIC), Set.of(CrossRepoLinks.CALLS_HTTP));

    out.add(call(CrossRepoLinks.CALLS_HTTP, null));

    assertThat(out.facts()).hasSize(1);
    assertThatThrownBy(() -> out.add(call(CrossRepoLinks.PUBLISHES, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not one of the predicates it writes");
    ClaimSink other =
        new ClaimSink(
            new RuleInfo("other", "1", Origin.Kind.STATIC), Set.of(CrossRepoLinks.CALLS_HTTP));
    assertThatThrownBy(() -> other.add(call(CrossRepoLinks.CALLS_HTTP, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("of origin");
  }
}
