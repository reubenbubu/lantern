package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * An external system or database Investigate proposes is drawn as the one already known that it
 * names (#976): by host, by name, or by technology and unit; one it does not name stays its own.
 */
class ProposedSystemsTest {

  private static final EntityId NOTIFIER = EntityId.parse("service:g:notifier");
  private static final EntityId BILLING = EntityId.parse("service:g:billing");
  private static final EntityId SMS = EntityId.parse("external-system:api.smsly.example");

  private final TestModel model = new TestModel();

  private Elements elements() {
    Knowledge knowledge = new Knowledge(model.facts);
    return new Elements(
        knowledge, Vocabularies.standard(), new ElementNames(knowledge, "Shop system"), Set.of());
  }

  private void units(EntityId... units) {
    for (EntityId unit : units) {
      model.add(unit, Types.DEPLOYABLE_UNIT, FactObject.string("spring-boot"));
    }
  }

  private void calls(EntityId unit, EntityId system) {
    ObjectNode call = FactJson.newObject();
    call.put("protocol", "HTTPS");
    model.add(unit, Types.CALLS_HTTP, FactObject.entity(system), call);
  }

  /** A connection Investigate found, as the Investigator stores one. */
  private Fact investigated(EntityId unit, String to, String technology, String... names) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("interaction", "calls");
    attributes.put("description", "Sends text messages through the provider");
    attributes.put("technology", technology);
    var list = attributes.putArray("names");
    for (String name : names) {
      list.add(name);
    }
    Fact fact =
        Fact.create(
                unit,
                Types.INVESTIGATED_CONNECTION,
                FactObject.entity(EntityId.parse(to)),
                attributes,
                new Origin(Origin.Kind.LLM, "investigate", "1"),
                Evidence.of(
                    "shop",
                    "src/main/resources/application.yml",
                    new Evidence.LineRange(3, 3),
                    null),
                0.6,
                Validity.from(1))
            .stored(TestModel.claim("llm:" + unit + ":" + to), Validity.from(1));
    model.facts.add(fact);
    return fact;
  }

  private List<String> lines() {
    return elements().lines().stream()
        .map(l -> l.from() + " " + l.relationship() + " " + l.to())
        .toList();
  }

  @Test
  void aProposalNamingAKnownSystemByItsNamesMainWordRepeatsTheUnitsLinkAndIsLeftOut() {
    units(NOTIFIER);
    calls(NOTIFIER, SMS);
    investigated(NOTIFIER, "external-system:smsly-api", "HTTP via OkHttp", "send", "base-url");

    assertThat(elements().destinations()).containsExactly(SMS);
    assertThat(lines()).containsExactly(NOTIFIER + " " + Types.CALLS_HTTP + " " + SMS);
  }

  @Test
  void aProposalNamingAKnownSystemFromAnotherUnitIsDrawnToItKeepingItsClaim() {
    units(NOTIFIER, BILLING);
    calls(NOTIFIER, SMS);
    Fact proposal = investigated(BILLING, "external-system:smsly", "HTTP");

    Knowledge knowledge = new Knowledge(model.facts);
    assertThat(elements().destinations()).containsExactly(SMS);
    assertThat(lines())
        .containsExactlyInAnyOrder(
            NOTIFIER + " " + Types.CALLS_HTTP + " " + SMS,
            BILLING + " " + Types.INVESTIGATED_CONNECTION + " " + SMS);
    Fact redrawn = knowledge.relations(BILLING, Types.INVESTIGATED_CONNECTION).getFirst();
    assertThat(redrawn.claimId()).isEqualTo(proposal.claimId());
    assertThat(redrawn.origin().kind()).isEqualTo(Origin.Kind.LLM);
  }

  @Test
  void aProposalNamingAKnownSystemsHostIsThatSystem() {
    units(NOTIFIER, BILLING);
    calls(NOTIFIER, SMS);
    investigated(
        BILLING, "external-system:text-messages", "HTTP", "https://api.smsly.example/v2/send");

    assertThat(elements().destinations()).containsExactly(SMS);
  }

  @Test
  void aProposedDatabaseNamedAsAKnownOnesNameIsThatDatabase() {
    EntityId orders = EntityId.parse("database:orders-db:5432/orders");
    units(BILLING);
    model.add(BILLING, Types.USES_DATABASE, FactObject.entity(orders), FactJson.newObject());
    investigated(BILLING, "database:orders", "PostgreSQL via R2DBC", "url", "r2dbc:postgresql");

    assertThat(elements().destinations()).containsExactly(orders);
  }

  @Test
  void aProposalNamedOnlyByItsKindIsTheOneStoreOfItsTechnologyItsUnitUses() {
    EntityId archive = EntityId.parse("database:minio:9000/telemetry");
    EntityId db = EntityId.parse("database:pg:5432/billing");
    units(BILLING);
    ObjectNode s3 = FactJson.newObject();
    s3.put("protocol", "S3");
    model.add(BILLING, Types.USES_DATABASE, FactObject.entity(archive), s3);
    model.add(BILLING, Types.USES_DATABASE, FactObject.entity(db), FactJson.newObject());
    investigated(BILLING, "external-system:object-storage", "S3 API via AWS SDK", "S3Client");

    assertThat(elements().destinations()).containsExactlyInAnyOrder(db, archive);
  }

  @Test
  void aProposalWithADistinctiveNameIsNotMatchedByItsTechnologyAlone() {
    EntityId archive = EntityId.parse("database:minio:9000/telemetry");
    units(BILLING);
    ObjectNode s3 = FactJson.newObject();
    s3.put("protocol", "S3");
    model.add(BILLING, Types.USES_DATABASE, FactObject.entity(archive), s3);
    investigated(BILLING, "external-system:invoice-archive", "S3", "S3Client");

    assertThat(elements().destinations())
        .containsExactly(archive, EntityId.parse("external-system:invoice-archive"));
  }

  @Test
  void ofAKnownSystemsHostsTheProposalIsTheOneItsUnitCallsByDefault() {
    units(BILLING);
    EntityId sandbox = EntityId.parse("external-system:sandbox.paygate.example.com");
    EntityId live = EntityId.parse("external-system:api.paygate.example.com");
    calls(BILLING, sandbox);
    ObjectNode prod = FactJson.newObject();
    prod.put("protocol", "HTTPS");
    prod.put("profile", "prod");
    model.add(BILLING, Types.CALLS_HTTP, FactObject.entity(live), prod);
    EntityId other = EntityId.parse("service:g:other");
    units(other);
    investigated(other, "external-system:paygate-api", "HTTPS", "paygate.url");

    assertThat(lines()).contains(other + " " + Types.INVESTIGATED_CONNECTION + " " + sandbox);
  }

  @Test
  void aProposalTwoKnownSystemsShareItsNameWithStaysItsOwn() {
    units(NOTIFIER, BILLING);
    EntityId alpha = EntityId.parse("external-system:payments.alpha.example");
    EntityId beta = EntityId.parse("external-system:payments.beta.example");
    calls(NOTIFIER, alpha);
    calls(NOTIFIER, beta);
    investigated(BILLING, "external-system:payments-api", "HTTP");

    assertThat(elements().destinations())
        .containsExactlyInAnyOrder(alpha, beta, EntityId.parse("external-system:payments-api"));
  }

  @Test
  void aProposalNamingNothingKnownIsANewSystem() {
    units(NOTIFIER);
    calls(NOTIFIER, SMS);
    investigated(NOTIFIER, "external-system:directory-lookup", "Feign client", "DirectoryClient");

    assertThat(elements().destinations())
        .containsExactly(SMS, EntityId.parse("external-system:directory-lookup"));
  }

  @Test
  void hostsAndWordsAreReadAsWritten() {
    assertThat(ProposedSystems.host("https://api.smsly.example:443/v2"))
        .isEqualTo("api.smsly.example");
    assertThat(ProposedSystems.host("api.smsly.example")).isEqualTo("api.smsly.example");
    assertThat(ProposedSystems.host("send")).isNull();
    assertThat(ProposedSystems.words("smsly-api", ProposedSystems.GENERIC_NAME_WORDS))
        .containsExactly("smsly");
    assertThat(ProposedSystems.words("orders-db:5432/orders", ProposedSystems.GENERIC_NAME_WORDS))
        .containsExactly("orders");
  }
}
