package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SummaryTreeTest {

  private static List<String> facts(SummaryNode node) {
    return node.facts().stream()
        .map(f -> f.subject() + " " + f.predicate() + " " + f.object().value())
        .toList();
  }

  private static SummaryNode only(List<SummaryNode> nodes) {
    assertThat(nodes).hasSize(1);
    return nodes.getFirst();
  }

  @Test
  void buildsRepoModulePackageTypeHierarchy() {
    SummaryNode repo = only(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(repo.entity()).isEqualTo(new EntityId("repo", "shop"));
    assertThat(repo.level()).isEqualTo(SummaryLevel.REPO);
    assertThat(repo.facts()).isEmpty();

    SummaryNode aggregator = only(repo.children());
    assertThat(aggregator.entity().toString()).isEqualTo("module:com.example:shop");
    assertThat(aggregator.level()).isEqualTo(SummaryLevel.MODULE);

    SummaryNode core = only(aggregator.children());
    assertThat(core.entity().toString()).isEqualTo("module:com.example:shop-core");
    // The library that builds the module is not a unit of its own; its facts go to the module.
    assertThat(facts(core))
        .containsExactlyInAnyOrder(
            "module:com.example:shop-core packaging jar",
            "module:com.example:shop-core BUILD_DEPENDS_ON artifact:org.slf4j:slf4j-api",
            "module:com.example:shop-core CONTAINS package:com.example:shop-core:com.example.shop",
            "library:com.example:shop-core CONTAINS module:com.example:shop-core");

    SummaryNode pkg = only(core.children());
    assertThat(pkg.level()).isEqualTo(SummaryLevel.PACKAGE);
    assertThat(pkg.children())
        .extracting(n -> n.entity().toString())
        .containsExactly("type:com.example.shop.Order", "type:com.example.shop.PriceService");

    SummaryNode order = pkg.children().getFirst();
    assertThat(order.level()).isEqualTo(SummaryLevel.TYPE);
    // Methods are part of their type's facts; the call into PriceService is a fact of Order only.
    assertThat(facts(order))
        .containsExactlyInAnyOrder(
            "type:com.example.shop.Order kind class",
            "type:com.example.shop.Order CONTAINS type:com.example.shop.Order$Line",
            "type:com.example.shop.Order CONTAINS method:com.example.shop.Order#total()",
            "method:com.example.shop.Order#total() CALLS method:com.example.shop.PriceService#price()");
    assertThat(order.children())
        .extracting(n -> n.entity().toString())
        .containsExactly("type:com.example.shop.Order$Line");

    SummaryNode priceService = pkg.children().get(1);
    assertThat(facts(priceService))
        .containsExactlyInAnyOrder(
            "type:com.example.shop.PriceService kind interface",
            "type:com.example.shop.PriceService CONTAINS method:com.example.shop.PriceService#price()");
  }

  @Test
  void factsAreSortedByIdSoTheOrderOfInputDoesNotMatter() {
    List<Fact> facts = TestFacts.shop().list();
    List<Fact> reversed = facts.reversed();

    assertThat(SummaryTree.fromFacts(reversed)).isEqualTo(SummaryTree.fromFacts(facts));
  }

  @Test
  void oneTreePerRepositorySortedByName() {
    List<Fact> facts =
        new TestFacts()
            .attribute("zeta", "module:z:z", "packaging", "jar")
            .attribute("alpha", "module:a:a", "packaging", "jar")
            .list();

    assertThat(SummaryTree.fromFacts(facts))
        .extracting(n -> n.entity().toString())
        .containsExactly("repo:alpha", "repo:zeta");
  }

  @Test
  void containsCyclesAreRefused() {
    List<Fact> facts =
        new TestFacts()
            .relate("r", "module:g:a", "CONTAINS", "package:g:a:p")
            .relate("r", "package:g:a:p", "CONTAINS", "type:p.A")
            .relate("r", "type:p.A", "CONTAINS", "type:p.B")
            .relate("r", "type:p.B", "CONTAINS", "type:p.A")
            .list();

    assertThatThrownBy(() -> SummaryTree.fromFacts(facts))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void aRepositoryHasTheFirstClaimsOfItsTopReadmeInTheOrderWritten() {
    List<Fact> facts = new ArrayList<>(TestFacts.shop().list());
    for (int line = 30; line > 0; line -= 3) {
      facts.add(TestFacts.claim("shop", "README.md", line, "Claim on line " + line));
    }
    facts.add(TestFacts.claim("shop", "docs/README.md", 1, "A deeper README"));
    facts.add(TestFacts.claim("other", "README.md", 1, "Another repository's README"));

    assertThat(SummaryTree.readmeClaims(facts, "shop"))
        .extracting(f -> f.object().value())
        .containsExactly(
            "Claim on line 3",
            "Claim on line 6",
            "Claim on line 9",
            "Claim on line 12",
            "Claim on line 15",
            "Claim on line 18",
            "Claim on line 21",
            "Claim on line 24");
    SummaryNode repo =
        SummaryTree.fromFacts(facts).stream()
            .filter(node -> node.entity().key().equals("shop"))
            .findFirst()
            .orElseThrow();
    assertThat(repo.facts())
        .containsExactlyInAnyOrderElementsOf(SummaryTree.readmeClaims(facts, "shop"));
    // Modules and the rest of the tree do not get README claims.
    assertThat(repo.children().getFirst().facts()).noneMatch(f -> f.predicate().equals("claim"));
  }
}
