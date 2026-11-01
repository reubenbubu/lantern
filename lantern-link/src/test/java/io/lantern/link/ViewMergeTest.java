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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** What a rule finds in each view merged into one claim per view (ADR 0095, amendment for #590). */
class ViewMergeTest {

  private static final RuleInfo RULE = new RuleInfo("links", "1", Origin.Kind.STATIC);
  private static final List<View> VIEWS =
      List.of(View.DEFAULT, new View("docker"), new View("local"), new View("prod"));

  private static Fact link(String to, String attributes) {
    return Fact.create(
        EntityId.parse("service:g:a"),
        CrossRepoLinks.CALLS_HTTP,
        FactObject.entity(EntityId.parse("service:g:" + to)),
        (ObjectNode) FactJson.parse(attributes),
        new Origin(Origin.Kind.STATIC, "links", "1"),
        Evidence.of("a", "A.java", null, null),
        1.0,
        Validity.from(1));
  }

  private static ClaimSink sink() {
    return new ClaimSink(
        RULE,
        Set.of(CrossRepoLinks.CALLS_HTTP, DataLinker.DISPLAY_NAME, DeployableUnits.TECHNOLOGY));
  }

  private static List<String> lines(List<LinkRule.Merged> merged) {
    return merged.stream()
        .map(m -> m.fact().object().value() + " " + m.fact().attributes() + " " + m.group())
        .toList();
  }

  @Test
  void aDefaultClaimListsTheViewsItIsAbsentInAndCitesEveryViewsFacts() {
    ClaimSink byDefault = sink();
    ClaimSink docker = sink();
    ClaimSink local = sink();
    ClaimSink prod = sink();
    byDefault.add(link("b", "{\"url\":\"http://b\",\"facts\":[\"f1\"]}"), "x", "call-1");
    // Found in docker with other descriptive attributes: still the default claim.
    docker.add(link("b", "{\"url\":\"http://b-docker\",\"facts\":[\"f2\"]}"), "x", "call-2");
    // Found only under prod: a claim of its own, naming its view.
    prod.add(link("c", "{\"url\":\"http://c\",\"facts\":[\"f3\"]}"), "y", "call-1");

    assertThat(lines(ViewMerge.merge(VIEWS, List.of(byDefault, docker, local, prod))))
        .containsExactly(
            "service:g:b {\"absentIn\":[\"local\",\"prod\"],\"facts\":[\"f1\",\"f2\"],"
                + "\"url\":\"http://b\"} call-1",
            "service:g:c {\"facts\":[\"f3\"],\"url\":\"http://c\",\"view\":\"prod\"} call-1");
  }

  @Test
  void aClaimFoundInSomeViewsOnlyIsOneClaimPerView() {
    ClaimSink byDefault = sink();
    ClaimSink docker = sink();
    ClaimSink local = sink();
    ClaimSink prod = sink();
    docker.add(link("b", "{\"facts\":[\"f1\"]}"), "x", null);
    local.add(link("b", "{\"facts\":[\"f2\"]}"), "x", null);

    assertThat(lines(ViewMerge.merge(VIEWS, List.of(byDefault, docker, local, prod))))
        .containsExactly(
            "service:g:b {\"facts\":[\"f1\"],\"view\":\"docker\"} null",
            "service:g:b {\"facts\":[\"f2\"],\"view\":\"local\"} null");
  }

  @Test
  void aFactWithoutALinkHoldsWhateverTheViewAndIsKeptOnce() {
    ClaimSink byDefault = sink();
    ClaimSink docker = sink();
    Fact plain =
        Fact.create(
            EntityId.parse("service:g:a"),
            DataLinker.DISPLAY_NAME,
            FactObject.string("A"),
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "links", "1"),
            Evidence.of("a", "A.java", null, null),
            1.0,
            Validity.from(1));
    byDefault.add(plain);
    docker.add(plain);

    List<LinkRule.Merged> merged =
        ViewMerge.merge(List.of(View.DEFAULT, new View("docker")), List.of(byDefault, docker));

    assertThat(merged).extracting(LinkRule.Merged::fact).containsExactly(plain);
    assertThat(merged.getFirst().link()).isNull();
  }

  @Test
  void aLinkFoundTwiceInOneViewFails() {
    ClaimSink byDefault = sink();
    byDefault.add(link("b", "{\"facts\":[\"f1\"]}"), "x");
    byDefault.add(link("b", "{\"facts\":[\"f2\"]}"), "x");

    assertThatThrownBy(() -> ViewMerge.merge(List.of(View.DEFAULT), List.of(byDefault)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("found twice");
  }

  @Test
  void aRuleMayNotNameTheViewOfAClaimItGivesWithALink() {
    assertThatThrownBy(() -> sink().add(link("b", "{\"view\":\"prod\"}"), "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("named the view");
    assertThatThrownBy(() -> sink().add(link("b", "{\"absentIn\":[\"prod\"]}"), "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("named the view");
  }

  @Test
  void theViewsAreTheDefaultThenEachProfileTheFactsNameSorted() {
    Fact gate =
        Fact.create(
            EntityId.parse("type:g.A"),
            io.lantern.extract.spring.SpringExtractor.PROFILE_GATE,
            FactObject.string("!local & cloud"),
            (ObjectNode) FactJson.parse("{\"profiles\":[\"!local & cloud\"]}"),
            new Origin(Origin.Kind.STATIC, "spring", "1"),
            Evidence.of("a", "A.java", null, null),
            1.0,
            Validity.from(1));
    Fact config =
        Fact.create(
            EntityId.parse("module:g:a"),
            io.lantern.extract.config.ConfigExtractor.CONFIG_VALUE,
            FactObject.string("x"),
            (ObjectNode) FactJson.parse("{\"key\":\"k\",\"profile\":\"docker\"}"),
            new Origin(Origin.Kind.STATIC, "config", "1"),
            Evidence.of("a", "application-docker.yml", null, null),
            1.0,
            Validity.from(1));

    assertThat(View.of(List.of(gate, config)))
        .containsExactly(View.DEFAULT, new View("cloud"), new View("docker"), new View("local"));
  }
}
