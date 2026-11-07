package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.types.Types;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Which of a unit's stores its code uses (#962). */
class DataStoresTest {

  private static final EntityId UNIT = EntityId.parse("service:com.example:billing");
  private static final EntityId BILLING = EntityId.parse("database:db:5432/billing");
  private static final EntityId WORK_ORDERS = EntityId.parse("database:db:5432/workorder");
  private static final EntityId REDIS = EntityId.parse("database:localhost:6379/redis");
  private static final EntityId SCHEMA = EntityId.parse("schema:com.example:billing");
  private static final EntityId PAYMENTS = EntityId.parse("table:com.example:billing:payments");
  private static final EntityId ORDERS = EntityId.parse("table:com.example:billing:work_orders");

  private final TestModel model = new TestModel();

  private void relational(EntityId database, String key, boolean withSchema) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("protocol", "JDBC");
    attributes.put("key", key);
    if (withSchema) {
      attributes.putArray("schemas").add(SCHEMA.toString());
    }
    model.add(UNIT, Types.USES_DATABASE, FactObject.entity(database), attributes);
  }

  /** Billing's ledger on its main datasource, and a second one it reads work orders from. */
  private void twoDatabases(boolean withSchema) {
    relational(BILLING, "spring.datasource.url", withSchema);
    relational(WORK_ORDERS, "billing.datasource.workorders.url", withSchema);
    model.add(SCHEMA, Types.CONTAINS, PAYMENTS);
  }

  private DataStores stores() {
    return new DataStores(new Knowledge(model.facts));
  }

  @Test
  void aTableOfTheUnitsOwnSchemaIsInTheMainDatabaseAndAnotherInTheOther() {
    twoDatabases(true);

    assertThat(stores().tableStores(UNIT, PAYMENTS)).containsExactly(BILLING);
    assertThat(stores().tableStores(UNIT, ORDERS)).containsExactly(WORK_ORDERS);
  }

  @Test
  void aClassRunsOnTheDatasourceSpringWiresIntoItWhateverItsTablesAre() {
    twoDatabases(false);
    EntityId reader = EntityId.parse("type:com.example.WorkOrderReader");
    EntityId ledger = EntityId.parse("type:com.example.LedgerDao");
    model.add(reader, Types.DATASOURCE_USE, FactObject.string("billing.datasource.workorders"));
    model.add(ledger, Types.DATASOURCE_USE, FactObject.string("spring.datasource"));

    assertThat(stores().tableStores(UNIT, reader, PAYMENTS)).containsExactly(WORK_ORDERS);
    assertThat(stores().tableStores(UNIT, ledger, ORDERS)).containsExactly(BILLING);
    // A class Spring wires nothing into is placed by the schemas, here none.
    assertThat(stores().tableStores(UNIT, EntityId.parse("type:com.example.Other"), ORDERS))
        .containsExactly(BILLING, WORK_ORDERS);
  }

  @Test
  void springBootsOwnDatasourceUnsetInConfigIsTheOneTheCodeDeclares() {
    relational(WORK_ORDERS, "billing.datasource.workorders.url", true);
    relational(BILLING, "billing.datasource.ledger.url", true);
    EntityId dao = EntityId.parse("type:com.example.Dao");
    ObjectNode declared = FactJson.newObject();
    declared.putArray("declared").add("billing.datasource.workorders");
    model.add(dao, Types.DATASOURCE_USE, FactObject.string("spring.datasource"), declared);

    assertThat(stores().wired(UNIT, dao)).containsExactly(WORK_ORDERS);
  }

  @Test
  void withoutSchemasEveryRelationalDatabaseIsACandidate() {
    twoDatabases(false);

    assertThat(stores().tableStores(UNIT, PAYMENTS)).containsExactly(BILLING, WORK_ORDERS);
  }

  @Test
  void aUnitWithOneDatabaseAndRedisReachesRedisOnlyThroughItsRedisUse() {
    relational(BILLING, "spring.datasource.url", true);
    ObjectNode redis = FactJson.newObject();
    redis.put("server", "localhost:6379");
    model.add(UNIT, Types.USES_DATABASE, FactObject.entity(REDIS), redis);
    model.add(REDIS, Types.TECHNOLOGY, FactObject.string("Redis 7.4"));
    EntityId directory = EntityId.parse("type:com.example.Directory");
    EntityId next = EntityId.parse("method:com.example.Directory#nextAvailable()");
    EntityId find = EntityId.parse("method:com.example.Directory#find()");
    model.add(directory, Types.CONTAINS, next);
    model.add(directory, Types.CONTAINS, find);
    model.add(next, Types.REDIS_USE, FactObject.string("template"));
    model.add(find, Types.READS_TABLE, PAYMENTS);
    EntityId absences = EntityId.parse("method:com.example.Absences#list()");
    EntityId absencesType = EntityId.parse("type:com.example.Absences");
    model.add(absencesType, Types.CONTAINS, absences);
    model.add(absences, Types.READS_TABLE, ORDERS);

    var directoryAccess = stores().access(UNIT, List.of(directory));
    assertThat(directoryAccess).containsOnlyKeys(BILLING, REDIS);
    assertThat(directoryAccess.get(BILLING).verb()).isEqualTo("Reads");
    assertThat(directoryAccess.get(BILLING).touched()).containsExactly("payments");
    assertThat(directoryAccess.get(REDIS).verb()).isEqualTo("Reads and writes keys");
    // Tables are never a cache's: code with only table access does not reach Redis.
    assertThat(stores().access(UNIT, List.of(absencesType))).containsOnlyKeys(BILLING);
  }

  @Test
  void aRepositoryGoesToTheStoreOfItsKind() {
    twoDatabases(true);
    EntityId search = EntityId.parse("type:com.example.PartSearchRepository");
    ObjectNode elastic = FactJson.newObject();
    elastic.put(
        "base", "org.springframework.data.elasticsearch.repository.ElasticsearchRepository");
    Fact searchFor =
        model.add(
            search,
            Types.REPOSITORY_FOR,
            FactObject.entity(EntityId.parse("type:com.example.PartDocument")),
            elastic);
    EntityId order = EntityId.parse("type:com.example.WorkOrder");
    model.add(order, Types.MAPS_TO, ORDERS);
    ObjectNode jpa = FactJson.newObject();
    jpa.put("base", "org.springframework.data.jpa.repository.JpaRepository");
    Fact ordersFor =
        model.add(
            EntityId.parse("type:com.example.WorkOrderRepository"),
            Types.REPOSITORY_FOR,
            FactObject.entity(order),
            jpa);
    Fact paymentsFor =
        model.add(
            EntityId.parse("type:com.example.PaymentRepository"),
            Types.REPOSITORY_FOR,
            FactObject.entity(EntityId.parse("type:com.example.Payment")),
            jpa.deepCopy());

    // Elasticsearch is not drawn yet (#466): no line, never one to a relational database.
    assertThat(stores().repositoryStores(UNIT, searchFor)).isEmpty();
    assertThat(stores().repositoryStores(UNIT, ordersFor)).containsExactly(WORK_ORDERS);
    // An entity that maps to no known table is the unit's own, on its main datasource.
    assertThat(stores().repositoryStores(UNIT, paymentsFor)).containsExactly(BILLING);
  }

  @Test
  void whatCodeDoesInRedisIsSaidByItsKindsOfUse() {
    assertThat(DataStores.redisVerb(List.of("lock", "template")))
        .isEqualTo("Reads and writes keys and takes locks");
    assertThat(DataStores.redisVerb(List.of("cache"))).isEqualTo("Caches results");
  }
}
