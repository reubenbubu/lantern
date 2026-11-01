package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The findings the fixed checks raise, stored with lineage (#619). */
class FindingsTest {

  private static final EntityId SHOP = EntityId.parse("service:com.example:shop");
  private static final EntityId API = EntityId.parse("module:com.example:api");
  private static final EntityId CORE = EntityId.parse("module:com.example:core");
  private static final EntityId LIB = EntityId.parse("artifact:com.acme:lib");
  private static final EntityId JUNIT = EntityId.parse("artifact:org.junit:junit");

  private final TestModel model = new TestModel();

  @Test
  void dependenciesOnUnreleasedAndDriftingVersionsAreFindingsPerRepositoryAndTarget() {
    Fact snapshot = depends(API, LIB, "1.1-SNAPSHOT", null);
    Fact released = depends(CORE, LIB, "1.0", null);
    depends(CORE, JUNIT, "4.0-SNAPSHOT", "test");
    // shop builds core itself: a dependency on it is not one on something else.
    depends(API, CORE, "0.1-SNAPSHOT", null);

    List<Fact> derived = model.derived(Findings.NAME);

    Fact unreleased =
        only(
            TestModel.about(
                derived,
                Findings.id(Findings.SNAPSHOT_DEPENDENCY, "shop/" + LIB),
                Findings.RAISED));
    assertThat(unreleased.object().value()).isEqualTo(Findings.SNAPSHOT_DEPENDENCY);
    assertThat(Findings.strings(unreleased, "versions")).containsExactly("1.1-SNAPSHOT");
    assertThat(Findings.attribute(unreleased, "rule")).isEqualTo("findings/snapshot-dependency");
    assertThat(unreleased.lineage()).containsExactly(snapshot.claimId());

    Fact drift =
        only(
            TestModel.about(
                derived, Findings.id(Findings.DEPENDENCY_DRIFT, "shop/" + LIB), Findings.RAISED));
    assertThat(Findings.strings(drift, "versions")).containsExactly("1.0", "1.1-SNAPSHOT");
    assertThat(drift.lineage()).containsExactlyInAnyOrder(snapshot.claimId(), released.claimId());

    // Neither the test-scoped dependency nor the repository's own module is a finding.
    assertThat(derived).hasSize(2);
  }

  @Test
  void packagesDependingOnEachOtherAreACycleOfTheirService() {
    EntityId orders = EntityId.parse("package:com.example.orders");
    EntityId billing = EntityId.parse("package:com.example.billing");
    EntityId order = EntityId.parse("type:com.example.orders.Order");
    EntityId invoice = EntityId.parse("type:com.example.billing.Invoice");
    model.add(SHOP, "deployable-unit", FactObject.string("spring-boot"));
    model.add(SHOP, BuiltInTypes.CONTAINS, API);
    model.add(API, BuiltInTypes.CONTAINS, orders);
    model.add(API, BuiltInTypes.CONTAINS, billing);
    model.add(orders, BuiltInTypes.CONTAINS, order);
    model.add(billing, BuiltInTypes.CONTAINS, invoice);
    Fact there = model.add(order, BuiltInTypes.CALLS, invoice);
    Fact back = model.add(invoice, BuiltInTypes.EXTENDS, order);

    List<Fact> derived = model.derived(Findings.NAME);

    Fact cycle =
        only(
            derived.stream()
                .filter(f -> f.object().value().equals(Findings.PACKAGE_CYCLE))
                .toList());
    assertThat(cycle.subject())
        .isEqualTo(Findings.id(Findings.PACKAGE_CYCLE, SHOP + "/" + billing));
    assertThat(Findings.attribute(cycle, "about")).isEqualTo(SHOP.toString());
    assertThat(Findings.strings(cycle, "packages"))
        .containsExactly(billing.toString(), orders.toString());
    assertThat(cycle.lineage()).containsExactlyInAnyOrder(there.claimId(), back.claimId());
  }

  @Test
  void aVersionTheTableKnowsIsAnEndOfLifeFindingJudgedLater() {
    model.add(SHOP, "deployable-unit", FactObject.string("spring-boot"));
    ObjectNode technology = FactJson.newObject();
    technology.put("framework", "Spring Boot");
    technology.put("frameworkVersion", "2.7.18");
    technology.put("java", "1.8");
    Fact fact = model.add(SHOP, "technology", FactObject.string("Spring Boot"), technology);

    List<Fact> derived = model.derived(Findings.NAME);

    List<Fact> ends =
        derived.stream().filter(f -> f.object().value().equals(Findings.END_OF_LIFE)).toList();
    assertThat(ends)
        .extracting(
            f ->
                Findings.attribute(f, "product")
                    + " "
                    + Findings.attribute(f, "version")
                    + " "
                    + Findings.attribute(f, "support")
                    + " "
                    + Findings.attribute(f, "line")
                    + " "
                    + Findings.attribute(f, "ends")
                    + " "
                    + Findings.attribute(f, "via"))
        .containsExactlyInAnyOrder(
            "JAVA 1.8 until 8 2030-12-31 unit", "SPRING_BOOT 2.7.18 until 2.7 2023-06-30 unit");
    ends.forEach(f -> assertThat(f.lineage()).containsExactly(fact.claimId()));
  }

  @Test
  void theLinkersCandidateRisksAreFindingsAsTheyStand() {
    ObjectNode skew = FactJson.newObject();
    skew.putArray("versions").add("1.0").add("2.0");
    Fact fact = model.add(LIB, "version-skew", FactObject.string("1.0, 2.0"), skew);

    List<Fact> derived = model.derived(Findings.NAME);

    Fact finding = only(derived);
    assertThat(finding.object().value()).isEqualTo(Findings.VERSION_SKEW);
    assertThat(finding.subject())
        .isEqualTo(Findings.id(Findings.VERSION_SKEW, LIB + "/" + fact.claimId()));
    assertThat(finding.lineage()).containsExactly(fact.claimId());
  }

  @Test
  void aFindingKeepsItsIdWhenItsVersionsChange() {
    depends(API, LIB, "1.1-SNAPSHOT", null);
    depends(CORE, LIB, "1.0", null);
    EntityId before = only(drifts(model.derived(Findings.NAME))).subject();

    depends(CORE, LIB, "1.2", null);

    Fact after = only(drifts(model.derived(Findings.NAME)));
    assertThat(after.subject()).isEqualTo(before);
    assertThat(Findings.strings(after, "versions")).containsExactly("1.0", "1.1-SNAPSHOT", "1.2");
  }

  private static List<Fact> drifts(List<Fact> derived) {
    return derived.stream()
        .filter(f -> f.object().value().equals(Findings.DEPENDENCY_DRIFT))
        .toList();
  }

  private Fact depends(EntityId module, EntityId target, String version, String scope) {
    model.add(module, "packaging", FactObject.string("jar"));
    ObjectNode attributes = FactJson.newObject();
    attributes.put("version", version);
    if (scope != null) {
      attributes.put("scope", scope);
    }
    return model.add(module, "BUILD_DEPENDS_ON", FactObject.entity(target), attributes);
  }

  private static <T> T only(List<T> list) {
    assertThat(list).hasSize(1);
    return list.getFirst();
  }
}
