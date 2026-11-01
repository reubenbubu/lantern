package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The deployment instances of the architecture model and what runs in them (#617). */
class DeploymentInstancesTest {

  private static final EntityId ENVIRONMENT = EntityId.parse("repo:infra");
  private static final EntityId DATABASE = EntityId.parse("datastore:postgres:shop");

  private final TestModel model = new TestModel();

  @Test
  void anInstanceIsKeyedOnItsEnvironmentAndNameAndRestsOnEveryEntryNamingIt() {
    Fact infra = container("infra");
    Fact shop = container("shop");
    ObjectNode in = FactJson.newObject();
    in.put("fact", infra.id());
    Fact runs = model.add(DATABASE, "deployed-in", FactObject.string("postgres"), in);

    List<Fact> derived = model.derived(DeploymentInstances.NAME);

    EntityId instance = DeploymentInstances.id(infra);
    assertThat(instance.toString()).isEqualTo("deployment-instance:compose:local/postgres");
    List<Fact> as = TestModel.about(derived, instance, DeploymentInstances.DEPLOYED_AS);
    assertThat(as).hasSize(1);
    assertThat(as.getFirst().attributes().get("image").asString()).isEqualTo("postgres:16");
    assertThat(as.getFirst().lineage()).containsExactlyInAnyOrder(infra.claimId(), shop.claimId());
    List<Fact> running = TestModel.about(derived, instance, DeploymentInstances.RUNS);
    assertThat(running).hasSize(1);
    assertThat(running.getFirst().object().entityId()).isEqualTo(DATABASE);
    assertThat(running.getFirst().lineage()).containsExactly(runs.claimId());
  }

  private Fact container(String repo) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("platform", "compose");
    attributes.put("environment", "local");
    attributes.put("image", "postgres:16");
    attributes.putArray("ports").add("5432");
    return model.add(
        ENVIRONMENT, "deployment-container", FactObject.string("postgres"), attributes, repo);
  }
}
