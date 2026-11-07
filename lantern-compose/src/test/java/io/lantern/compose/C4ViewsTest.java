package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Metrics;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.core.types.Types;
import io.lantern.core.vocabulary.AttributeSchema;
import io.lantern.core.vocabulary.TypeKind;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.derive.Derivation;
import io.lantern.derive.Vocabularies;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The C4 context, container and component views (docs/design.md, Diagrams; ADR 0044). */
class C4ViewsTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);

  private final TestFacts facts = TestFacts.runtime();

  private Document repo(List<Fact> facts) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
            SNAPSHOT,
            Derivation.withModel(facts, 1));
  }

  private Document system(List<Fact> facts) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
            SNAPSHOT,
            Derivation.withModel(facts, 1));
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static <T extends Block> T block(Section section, Class<T> kind) {
    return section.blocks().stream()
        .filter(kind::isInstance)
        .map(kind::cast)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> nodes(Diagram diagram) {
    return diagram.nodes().stream()
        .map(
            n ->
                n.id()
                    + " \""
                    + n.label()
                    + "\""
                    + (n.technology() == null ? "" : " [" + n.technology() + "]")
                    + (n.parent() == null ? "" : " in " + n.parent()))
        .toList();
  }

  private static List<String> edges(Diagram diagram) {
    return diagram.edges().stream()
        .map(
            e ->
                e.from()
                    + " -> "
                    + e.to()
                    + ": "
                    + e.label()
                    + (e.technology() == null ? "" : " [" + e.technology() + "]")
                    + (e.profiles().isEmpty() ? "" : " " + e.profiles()))
        .toList();
  }

  private static String text(List<Inline> content) {
    return content.stream()
        .map(i -> i instanceof TextRun t ? t.text() : "")
        .collect(Collectors.joining());
  }

  @Test
  void aLineOfMessageKeysWithNoPurposeSaysWhatTheyAreAbout() {
    // #933: never "Delivers order.*, refund.# and 3 more".
    assertThat(C4Views.messages("Delivers", List.of("order.*", "refund.#")))
        .contains("Delivers order and refund messages");
    assertThat(C4Views.messages("Sends", List.of("a.x", "b.x", "c.x", "d.x")))
        .contains("Sends a, b and c and other messages");
    assertThat(C4Views.messages("Publishes to", List.of("orders"))).isEmpty();
    assertThat(C4Views.messages("Sends", List.of("GET /orders"))).isEmpty();
  }

  @Test
  void aRouteSummarySkipsSegmentsThatSayNothing() {
    assertThat(C4Views.resources(List.of("GET /internal/orders/{id}", "POST /api/v2/rest/cases")))
        .containsExactly("orders", "cases");
  }

  @Test
  void aLineOfMethodsWithNoPurposeSaysWhatTheyDoNotTheirNames() {
    // #933: never "Calls approve, complete and 2 more".
    assertThat(C4Views.actions(List.of("approve", "complete", "findOpen", "reviewQueue")))
        .contains("Approves, completes and finds");
    // One verb says what it is done to, never the bare verb (#946's real run).
    assertThat(C4Views.actions(List.of("findByCustomerIdAndActiveTrue", "findByIdAndActiveTrue")))
        .hasValueSatisfying(t -> assertThat(t).startsWith("Finds by ").isNotEqualTo("Finds"));
    assertThat(C4Views.actions(List.of("findOverdue", "findByStatus", "findByCustomer")))
        .contains("Finds overdue, by status and others");
    assertThat(C4Views.actions(List.of("amountReserved", "create", "query")))
        .contains("Creates and queries");
    // None starts with a verb, or a detail is not a method: left to the line's other wording.
    assertThat(C4Views.actions(List.of("summary", "dueAt"))).isEmpty();
    assertThat(C4Views.actions(List.of("GET /orders"))).isEmpty();
  }

  @Test
  void methodsNamedForWhatTheyReturnReadAsGettingIt() {
    // #933's real run: never "Calls amountApplied".
    assertThat(C4Views.accessed(List.of("amountApplied"))).contains("Gets amount applied");
    assertThat(C4Views.accessed(List.of("creditedAmount", "dueAt", "summary")))
        .contains("Gets credited amount, due at and others");
    assertThat(C4Views.accessed(List.of("payments.refunds"))).isEmpty();
    assertThat(C4Views.described("launchManually")).contains("Launches manually");
  }

  @Test
  void aLabelDoesNotNameTheEndItPointsTo() {
    // #933: the line already points to the box that says PostgreSQL, Keycloak or edge-gateway.
    assertThat(
            C4Views.withoutEnd(
                "Keeps invoices and payments in PostgreSQL", List.of("billing", "PostgreSQL")))
        .isEqualTo("Keeps invoices and payments");
    assertThat(C4Views.withoutEnd("Validates caller tokens against Keycloak", List.of("Keycloak")))
        .isEqualTo("Validates caller tokens");
    assertThat(
            C4Views.withoutEnd(
                "Forwards API calls through the edge gateway",
                List.of("edge-gateway", "edge gateway")))
        .isEqualTo("Forwards API calls");
    assertThat(C4Views.withoutEnd("Stores customer sessions in the Redis cache", List.of("Redis")))
        .isEqualTo("Stores customer sessions");
    assertThat(
            C4Views.withoutEnd(
                "Publishes payment outcomes to billing events exchange",
                List.of("billing.events", "billing events")))
        .isEqualTo("Publishes payment outcomes");
    assertThat(
            C4Views.withoutEnd(
                "Fetches the operations summary from the gateway",
                List.of("edge-gateway", "edge gateway", "gateway")))
        .isEqualTo("Fetches the operations summary");
    // Into a topic, queue or exchange, a closing phrase naming one goes.
    assertThat(C4Views.withoutDestination("Publishes payment events to work-order exchange"))
        .isEqualTo("Publishes payment events");
    assertThat(C4Views.withoutDestination("Publishes priority alerts to the headers exchange"))
        .isEqualTo("Publishes priority alerts");
    assertThat(C4Views.withoutDestination("Queues payment confirmations for its own listener"))
        .isEqualTo("Queues payment confirmations for its own listener");
    assertThat(C4Views.withoutDestination("Sends to queue")).isEqualTo("Sends to queue");
    // Too little would be left, or the end is not named last: kept as it is.
    assertThat(C4Views.withoutEnd("Uses Redis", List.of("Redis"))).isEqualTo("Uses Redis");
    assertThat(C4Views.withoutEnd("Reads Redis keys for rate limits", List.of("Redis")))
        .isEqualTo("Reads Redis keys for rate limits");
  }

  @Test
  void contextShowsTheSystemTheExternalSystemsItCallsAndThatItsUsersAreUnknown() {
    Section context = section(repo(facts.list()), "context");
    Diagram diagram = block(context, Diagram.class);

    assertThat(diagram.diagramKind()).isEqualTo(Diagram.Kind.C4_CONTEXT);
    assertThat(nodes(diagram))
        .containsExactly(
            "external-system:pay.example.com \"pay.example.com\"",
            "system:snapshot \"base and shop\"");
    // No technology on a context diagram; the profile marks the line conditional.
    assertThat(edges(diagram))
        .containsExactly(
            "system:snapshot -> external-system:pay.example.com: Works with charges [prod]");
    assertThat(diagram.edges().getFirst().facts())
        .containsExactly(facts.find("service:com.example:app", "CALLS_HTTP").id());
    assertThat(text(block(context, Callout.class).content()))
        .isEqualTo(
            "The code names no people or user roles, so who uses base and shop is not known.");
    assertThat(block(context, Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .containsExactly(
            "Deployable units 1",
            "Databases 1",
            "Topics, queues and exchanges 1",
            "External systems 1");
  }

  @Test
  void contextShowsTheInferredPeopleTheCodeShowsWithTheFrontendTheyUse() {
    TestFacts withPeople = TestFacts.runtime();
    withPeople.attribute(
        "shop",
        "type:com.example.RequiresOrganizationMembership",
        "person-evidence",
        "membership",
        "{\"scope\":\"Organization\",\"type\":\"com.example.RequiresOrganizationMembership\"}");
    Fact evidence =
        withPeople.find("type:com.example.RequiresOrganizationMembership", "person-evidence");
    withPeople.attribute(
        "shop",
        "person:organization-member",
        "person-basis",
        "member",
        "{\"basis\":\"member\",\"description\":\"The code has members of an organization.\","
            + "\"cites\":[\""
            + evidence.id()
            + "\"],\"repos\":[\"shop\"],\"frontends\":[\"web\"]}");
    withPeople.attribute(
        "shop", "person:organization-member", "display-name", "Organization member");
    Fact basis = withPeople.find("person:organization-member", "person-basis");

    Document document = repo(withPeople.list());
    Section context = section(document, "context");
    Diagram diagram = block(context, Diagram.class);

    assertThat(nodes(diagram)).contains("person:organization-member \"Organization member\"");
    assertThat(edges(diagram))
        .contains("person:organization-member -> system:snapshot: Uses, through web");
    assertThat(
            diagram.edges().stream()
                .filter(e -> e.from().type().equals("person"))
                .findFirst()
                .orElseThrow()
                .facts())
        .containsExactlyInAnyOrder(basis.id(), evidence.id());
    DocumentEntity person =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("person:organization-member"))
            .findFirst()
            .orElseThrow();
    assertThat(text(person.description()))
        .startsWith("Inferred: The code has members of an organization.");
    assertThat(person.description()).anyMatch(i -> i instanceof io.lantern.core.document.Citation);
    assertThat(text(block(context, Callout.class).content()))
        .isEqualTo(
            "These people are inferred from the code. Who else uses base and shop is not known.");
    assertThat(block(context, Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .contains("People 1");
    assertThat(
            block(context, Metrics.class).items().stream()
                .filter(i -> i.label().equals("People"))
                .findFirst()
                .orElseThrow()
                .facts())
        .containsExactly(basis.id());
    // A repository the person's files are not in does not show them.
    assertThat(
            nodes(
                block(
                    section(
                        new Composer("1")
                            .compose(
                                ViewDefinition.c4Report("base.c4", "base", TEMPLATE),
                                SNAPSHOT,
                                withPeople.list()),
                        "context"),
                    Diagram.class)))
        .noneMatch(n -> n.startsWith("person:"));
  }

  @Test
  void codeSpansOfADecisionRecordAreCode() {
    assertThat(Assembler.withCodeSpans("Verify old `PROCESSING` records, then `X`"))
        .containsExactly(
            TextRun.of("Verify old "),
            new TextRun("PROCESSING", TextRun.Style.CODE),
            TextRun.of(" records, then "),
            new TextRun("X", TextRun.Style.CODE));
    assertThat(Assembler.withCodeSpans("No code")).containsExactly(TextRun.of("No code"));
  }

  @Test
  void theRoleAPersonRestsOnIsCodeNotAnAcronym() {
    TestFacts withRole = TestFacts.runtime();
    withRole.attribute(
        "shop",
        "type:com.example.AdminController",
        "person-evidence",
        "role",
        "{\"name\":\"ADMIN\",\"type\":\"com.example.AdminController\"}");
    Fact evidence = withRole.find("type:com.example.AdminController", "person-evidence");
    withRole.attribute(
        "shop",
        "person:admin",
        "person-basis",
        "role",
        "{\"basis\":\"role\",\"description\":\"The code checks the role ADMIN"
            + " (@PreAuthorize in AdminController.java).\",\"cites\":[\""
            + evidence.id()
            + "\"],\"repos\":[\"shop\"]}");
    withRole.attribute("shop", "person:admin", "display-name", "Admin");

    Document document = repo(withRole.list());
    DocumentEntity person =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("person:admin"))
            .findFirst()
            .orElseThrow();

    assertThat(person.description())
        .contains(new TextRun("ADMIN", TextRun.Style.CODE))
        .contains(TextRun.of(" (@PreAuthorize in AdminController.java)."));
    assertThat(QualityBar.acronymsUsed(document)).doesNotContain("ADMIN");
  }

  @Test
  void aPersonDescribeWroteASentenceForShowsItAndNotTheLinkersNote() {
    TestFacts withPerson = TestFacts.runtime();
    withPerson.attribute(
        "shop",
        "type:com.example.InviteController",
        "person-evidence",
        "role",
        "{\"name\":\"INVITED\",\"type\":\"com.example.InviteController\"}");
    Fact evidence = withPerson.find("type:com.example.InviteController", "person-evidence");
    withPerson.attribute(
        "shop",
        "person:invited-user",
        "person-basis",
        "role",
        "{\"basis\":\"role\",\"description\":\"The code has invitations.\",\"cites\":[\""
            + evidence.id()
            + "\"],\"repos\":[\"shop\"]}");
    withPerson.attribute("shop", "person:invited-user", "display-name", "Invited user");
    EntityId person = EntityId.parse("person:invited-user");
    List<Fact> all =
        TestTexts.with(
            withPerson.list(),
            new TestTexts.Written(
                Map.of(person, "Accepts an invitation to join. [fact:" + evidence.id() + "]"),
                Map.of()));

    Function<List<Fact>, String> described =
        facts ->
            repo(facts).entities().stream()
                .filter(e -> e.id().equals(person))
                .findFirst()
                .orElseThrow()
                .description()
                .stream()
                .map(i -> i instanceof TextRun run ? run.text() : "")
                .collect(java.util.stream.Collectors.joining())
                .strip();

    // Describe's sentence, not the linker's own note (#767).
    assertThat(described.apply(all))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Accepts an invitation to join.");
    // Without one, the linker's note.
    assertThat(described.apply(withPerson.list()))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " The code has invitations.");
  }

  /** The shop's Angular frontend calling the app, and a user who signs up on its screens. */
  private static TestFacts withFrontendAndUser() {
    TestFacts withFrontend = TestFacts.runtime();
    withFrontend.attribute(
        "shop",
        "frontend:shop/web",
        "frontend-app",
        "angular",
        "{\"name\":\"web\",\"directory\":\"web\",\"framework\":\"Angular\","
            + "\"version\":\"19.2\",\"server\":\"nginx\","
            + "\"technology\":\"Angular 19.2 · nginx\"}");
    withFrontend.relate(
        "shop",
        "frontend:shop/web",
        "CALLS_HTTP",
        "service:com.example:app",
        "{\"httpMethod\":\"GET\",\"path\":\"/orders\",\"protocol\":\"JSON/HTTP\","
            + "\"url\":\"http://shop-app:8080/orders\",\"matchedBy\":\"host\"}");
    Fact route =
        Fact.create(
            EntityId.parse("repo:shop"),
            "person-evidence",
            FactObject.string("route"),
            (ObjectNode) FactJson.parse("{\"path\":\"/registration\",\"framework\":\"angular\"}"),
            new Origin(Origin.Kind.STATIC, "people", "2"),
            Evidence.of("shop", "web/src/app/app.routes.ts", null, null),
            0.8,
            Validity.from(1));
    withFrontend.add(route);
    withFrontend.attribute(
        "shop",
        "person:user",
        "person-basis",
        "account",
        "{\"basis\":\"account\",\"description\":\"The code lets people sign up or in.\","
            + "\"cites\":[\""
            + route.id()
            + "\"],\"repos\":[\"shop\"],\"frontends\":[\"shop\"]}");
    withFrontend.attribute("shop", "person:user", "display-name", "User");
    return withFrontend;
  }

  @Test
  void aPersonWithNoFrontendIsDrawnDoingWhatDescribeSaysTheyDoNotABareUses() {
    TestFacts withPeople = TestFacts.runtime();
    withPeople.attribute(
        "shop",
        "type:com.example.Clerk",
        "person-evidence",
        "role",
        "{\"scope\":\"Role\",\"type\":\"com.example.Clerk\"}");
    Fact evidence = withPeople.find("type:com.example.Clerk", "person-evidence");
    withPeople.attribute(
        "shop",
        "person:billing-clerk",
        "person-basis",
        "role",
        "{\"basis\":\"role\",\"description\":\"The code has a billing clerk role.\","
            + "\"cites\":[\""
            + evidence.id()
            + "\"],\"repos\":[\"shop\"],\"frontends\":[]}");
    withPeople.attribute("shop", "person:billing-clerk", "display-name", "Billing clerk");
    withPeople.add(
        io.lantern.harness.LlmFacts.text(
            io.lantern.summarize.describe.DescribeRole.UNITS,
            "test/test",
            "a".repeat(64),
            EntityId.parse("person:billing-clerk"),
            Types.RESPONSIBILITY,
            io.lantern.harness.LlmFacts.C4,
            "Reviews unpaid invoices and chases arrears, every morning.",
            List.of(evidence),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));

    Diagram diagram = block(section(repo(withPeople.list()), "context"), Diagram.class);

    // #933: the first clause of what the person does, never a bare "Uses".
    assertThat(edges(diagram))
        .contains(
            "person:billing-clerk -> system:snapshot: Reviews unpaid invoices and chases arrears");
  }

  @Test
  void aPersonsLongSentenceIsCutBeforeItsQualifierNeverMidPhrase() {
    assertThat(
            personLine("Reviews and approves repair work orders submitted by field technicians."))
        .isEqualTo("Reviews and approves repair work orders");
    assertThat(personLine("Tracks e.g. overdue invoices for the finance team."))
        .isEqualTo("Tracks e.g. overdue invoices for the finance team");
  }

  private String personLine(String sentence) {
    TestFacts withPeople = TestFacts.runtime();
    withPeople.attribute(
        "shop",
        "type:com.example.Clerk",
        "person-evidence",
        "role",
        "{\"scope\":\"Role\",\"type\":\"com.example.Clerk\"}");
    Fact evidence = withPeople.find("type:com.example.Clerk", "person-evidence");
    withPeople.attribute(
        "shop",
        "person:billing-clerk",
        "person-basis",
        "role",
        "{\"basis\":\"role\",\"description\":\"The code has a billing clerk role.\","
            + "\"cites\":[\""
            + evidence.id()
            + "\"],\"repos\":[\"shop\"],\"frontends\":[]}");
    withPeople.attribute("shop", "person:billing-clerk", "display-name", "Billing clerk");
    withPeople.add(
        io.lantern.harness.LlmFacts.text(
            io.lantern.summarize.describe.DescribeRole.UNITS,
            "test/test",
            "a".repeat(64),
            EntityId.parse("person:billing-clerk"),
            Types.RESPONSIBILITY,
            io.lantern.harness.LlmFacts.C4,
            sentence,
            List.of(evidence),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));
    Diagram diagram = block(section(repo(withPeople.list()), "context"), Diagram.class);
    return edges(diagram).stream()
        .filter(e -> e.startsWith("person:billing-clerk -> "))
        .findFirst()
        .orElseThrow()
        .replaceFirst("^.*?system:snapshot: ", "")
        .replaceAll(" \\[.*$", "");
  }

  @Test
  void aPersonsLinesShowTheIntentDescribeStoredForTheirUseOfAFrontend() {
    TestFacts withFrontend = withFrontendAndUser();
    Fact basis = withFrontend.find("person:user", "person-basis");
    // Describe stores a person's intent under its basis and the frontend (ADR 0103, #739).
    withFrontend.add(
        io.lantern.harness.LlmFacts.text(
            io.lantern.summarize.describe.DescribeRole.UNITS,
            "test/test",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            EntityId.parse("person:user"),
            Types.INTENT,
            basis.id() + "/frontend:shop/web",
            "Signs up and signs in through",
            List.of(basis),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));

    Document document = system(withFrontend.list());

    assertThat(edges(block(section(document, "containers"), Diagram.class)))
        .contains("person:user -> frontend:shop/web: Signs up and signs in through [Web browser]")
        .noneMatch(e -> e.startsWith("person:user -> frontend:shop/web: Uses"));
    assertThat(edges(block(section(document, "context"), Diagram.class)))
        .contains("person:user -> system:snapshot: Signs up and signs in through")
        .noneMatch(e -> e.startsWith("person:user -> system:snapshot: Uses"));
    // Stored for another frontend, it labels nothing here.
    TestFacts elsewhere = withFrontendAndUser();
    elsewhere.add(
        io.lantern.harness.LlmFacts.text(
            io.lantern.summarize.describe.DescribeRole.UNITS,
            "test/test",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            EntityId.parse("person:user"),
            Types.INTENT,
            basis.id() + "/frontend:shop/admin",
            "Signs up and signs in through",
            List.of(basis),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));
    assertThat(edges(block(section(system(elsewhere.list()), "containers"), Diagram.class)))
        .contains("person:user -> frontend:shop/web: Opens the registration screens [Web browser]");
  }

  @Test
  void aFrontendIsAContainerWithItsTechnologyItsCallsAndThePeopleWithScreensInIt() {
    TestFacts withFrontend = withFrontendAndUser();

    Document document = system(withFrontend.list());
    Diagram diagram = block(section(document, "containers"), Diagram.class);

    assertThat(nodes(diagram))
        .contains(
            "frontend:shop/web \"web\" [Angular 19.2 · nginx] in system:snapshot",
            "person:user \"User\"");
    assertThat(edges(diagram))
        .contains(
            "frontend:shop/web -> service:com.example:app: Works with orders [JSON/HTTP]",
            "person:user -> frontend:shop/web: Opens the registration screens [Web browser]");
    DocumentEntity frontend =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("frontend:shop/web"))
            .findFirst()
            .orElseThrow();
    assertThat(text(frontend.description()))
        .isEqualTo(
            "A single-page app built with Angular 19.2, served by nginx; it calls shop-app from"
                + " the browser.");
    assertThat(block(section(document, "context"), Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .contains("Frontends 1");
  }

  @Test
  void aProxyNoCallGoesThroughIsDrawnAsUnusedNeverAsACall() {
    // ADR 0083, amendment for #980: the nginx that serves the app passes /api/ to a gateway no
    // call of the app reaches. The line says so, whatever intent an earlier run stored for it, and
    // the description does not count it as a call.
    TestFacts withFrontend = withFrontendAndUser();
    withFrontend.relate(
        "shop",
        "frontend:shop/web",
        "CALLS_HTTP",
        "service:com.example:worker",
        "{\"protocol\":\"HTTP\",\"url\":\"http://worker:8080/api/\",\"matchedBy\":\"host\","
            + "\"proxy\":\"/api/\",\"request\":\"http://worker:8080/api/\"}");
    Fact proxied =
        withFrontend.list().stream()
            .filter(f -> f.predicate().equals("CALLS_HTTP"))
            .filter(f -> f.object().value().equals("service:com.example:worker"))
            .findFirst()
            .orElseThrow();
    withFrontend.add(
        io.lantern.harness.LlmFacts.text(
            io.lantern.summarize.describe.DescribeRole.UNITS,
            "test/test",
            "a".repeat(64),
            EntityId.parse("frontend:shop/web"),
            Types.INTENT,
            proxied.claimId() != null ? proxied.claimId() : proxied.id(),
            "Forwards API calls",
            List.of(proxied),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));

    Document document = system(withFrontend.list());

    assertThat(edges(block(section(document, "containers"), Diagram.class)))
        .filteredOn(e -> e.startsWith("frontend:shop/web -> "))
        .containsExactlyInAnyOrder(
            "frontend:shop/web -> service:com.example:app: Works with orders [JSON/HTTP]",
            "frontend:shop/web -> service:com.example:worker:"
                + " Passes /api/ on (no call of the page uses it) [HTTP]");
    DocumentEntity frontend =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("frontend:shop/web"))
            .findFirst()
            .orElseThrow();
    assertThat(text(frontend.description()))
        .isEqualTo(
            "A single-page app built with Angular 19.2, served by nginx; it calls shop-app from"
                + " the browser.");
  }

  @Test
  void containersAreTheUnitsWithTheirStoresQueuesAndNeighboursInsideTheSystem() {
    Diagram diagram = block(section(repo(facts.list()), "containers"), Diagram.class);

    assertThat(diagram.diagramKind()).isEqualTo(Diagram.Kind.C4_CONTAINER);
    assertThat(nodes(diagram))
        .containsExactly(
            "database:db:5432/shop \"shop on db\" [PostgreSQL] in system:snapshot",
            "external-system:pay.example.com \"pay.example.com\"",
            "service:com.example:app \"shop-app\" [Spring Boot 3.5.0 / Java 21] in system:snapshot",
            "service:com.example:worker \"worker\" [Spring Boot 3.5.0 / Java 21] in system:snapshot",
            "system:snapshot \"base and shop\"",
            "topic:kafka:orders \"orders\" [Kafka topic] in system:snapshot");
    assertThat(edges(diagram))
        .containsExactly(
            "service:com.example:app -> external-system:pay.example.com: Works with charges"
                + " [HTTPS] [prod]",
            "service:com.example:app -> topic:kafka:orders: Sends messages [Kafka]",
            "service:com.example:app -> database:db:5432/shop: Stores its data in [JDBC]",
            "service:com.example:worker -> service:com.example:app: Works with orders [JSON/HTTP]");
  }

  @Test
  void theSystemScopeShowsEveryUnitAndLine() {
    Document document = system(facts.list());
    Diagram diagram = block(section(document, "containers"), Diagram.class);

    assertThat(edges(diagram))
        .contains("topic:kafka:orders -> service:com.example:worker: Delivers messages [Kafka]");
    // The system's purpose, unknown without the LLM's wording (ADR 0060); its repositories are
    // named with their commits under the report's title, not listed here (#767).
    assertThat(section(document, "overview").blocks()).hasSize(1);
    assertThat(section(document, "overview").entity()).isEqualTo(RepoSections.SYSTEM);
  }

  @Test
  void decisionRecordsAndUnresolvedLinksAreASectionOfTheirOwn() {
    Document document = repo(facts.list());
    // C4 pages hold only C4 content (ADR 0086): the containers section keeps only its diagram.
    assertThat(section(document, "containers").blocks())
        .noneMatch(b -> b instanceof Table || b instanceof Callout);
    Section containers = section(document, "decisions");
    assertThat(containers.title()).isEqualTo("Decisions and unknowns");
    // Without deployment descriptors there is no deployment section.
    assertThat(document.sections()).noneMatch(s -> s.id().equals("deployment"));

    Callout unresolved = block(containers, Callout.class);
    assertThat(unresolved.variant()).isEqualTo(Callout.Variant.WARNING);
    assertThat(text(unresolved.content()))
        .isEqualTo("shop-app: GET ${TAX_URL}/quotes (unresolved placeholder TAX_URL).");
    Table decisions = block(containers, Table.class);
    assertThat(decisions.columns())
        .extracting(Table.Column::label)
        .containsExactly("ID", "Decision", "Status", "Outcome", "Rationale");
    assertThat(decisions.rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.entity())
                  .isEqualTo(EntityId.parse("decision:shop:docs/adr/0001-kafka.md"));
              assertThat(row.cells())
                  .extracting(C4ViewsTest::text)
                  .containsExactly(
                      "ADR 1", "Use Kafka", "Accepted", "Orders go to Kafka.", "Not recorded.");
            });
  }

  @Test
  void aDeploymentTableListsEachEnvironmentsContainersAndWhatRunsInThem() {
    facts
        .attribute(
            "shop",
            "repo:shop",
            "deployment-container",
            "shop-db",
            "{\"platform\":\"compose\",\"environment\":\"local\",\"kind\":\"service\","
                + "\"image\":\"postgres:16.4\",\"product\":\"PostgreSQL\",\"version\":\"16.4\","
                + "\"ports\":[\"5432:5432\"]}")
        .attribute(
            "shop",
            "repo:shop",
            "deployment-container",
            "shop-app",
            "{\"platform\":\"compose\",\"environment\":\"local\",\"kind\":\"service\","
                + "\"build\":\"./app\"}");
    Fact db = facts.raw().get(facts.raw().size() - 2);
    Fact app = facts.raw().getLast();
    facts
        .attribute(
            "shop",
            "database:db:5432/shop",
            "deployed-in",
            "Docker Compose (local)",
            "{\"container\":\"shop-db\",\"fact\":\"" + db.id() + "\"}")
        .attribute(
            "shop",
            "service:com.example:app",
            "deployed-in",
            "Docker Compose (local)",
            "{\"container\":\"shop-app\",\"fact\":\"" + app.id() + "\"}");

    Document document = repo(facts.list());
    assertThat(section(document, "containers").blocks()).noneMatch(b -> b instanceof Table);
    Section containers = section(document, "deployment");

    Table deployment =
        containers.blocks().stream()
            .filter(b -> b instanceof Table t && "Deployment".equals(t.title()))
            .map(Table.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(deployment.columns())
        .extracting(Table.Column::label)
        .containsExactly("Environment", "Container", "Image", "Technology", "Runs", "Ports");
    assertThat(deployment.rows())
        .extracting(row -> row.cells().stream().map(C4ViewsTest::text).toList())
        .containsExactly(
            List.of("Docker Compose (local)", "shop-app", "built from ./app", "", "shop-app", ""),
            List.of(
                "Docker Compose (local)",
                "shop-db",
                "postgres:16.4",
                "PostgreSQL 16.4",
                "shop on db",
                "5432:5432"));
    assertThat(deployment.rows().getLast().facts()).contains(db.id());
  }

  @Test
  void componentsAreFoundByRoleWithSharedBeansOnTheirOwn() {
    Section components = section(repo(facts.list()), "components");

    Section app = components.sections().getFirst();
    assertThat(app.entity()).isEqualTo(EntityId.parse("service:com.example:app"));
    assertThat(app.title()).isEqualTo("shop-app");
    Diagram diagram = block(app, Diagram.class);
    assertThat(diagram.diagramKind()).isEqualTo(Diagram.Kind.C4_COMPONENT);
    assertThat(nodes(diagram))
        .containsExactly(
            "database:db:5432/shop \"shop on db\" [PostgreSQL]",
            "external-system:pay.example.com \"pay.example.com\"",
            "service:com.example:app \"shop-app\" [Spring Boot 3.5.0 / Java 21]",
            "service:com.example:worker \"worker\" [Spring Boot 3.5.0 / Java 21]",
            "topic:kafka:orders \"orders\" [Kafka topic]",
            "type:com.example.domain.JpaOrders \"JpaOrders\" [Spring repository]"
                + " in service:com.example:app",
            "type:com.example.web.Controller \"Controller\" [Spring MVC REST controller]"
                + " in service:com.example:app",
            "type:com.example.web.Jobs \"Jobs\" [Scheduled job] in service:com.example:app");
    assertThat(edges(diagram))
        .containsExactlyInAnyOrder(
            "type:com.example.web.Controller -> type:com.example.domain.JpaOrders: Calls all",
            "type:com.example.web.Jobs -> type:com.example.domain.JpaOrders: Calls all",
            "type:com.example.domain.JpaOrders -> topic:kafka:orders: Sends messages [Kafka]",
            "type:com.example.domain.JpaOrders -> database:db:5432/shop: Reads orders [JDBC]",
            "type:com.example.web.Controller -> external-system:pay.example.com:"
                + " Works with charges [HTTPS] [prod]",
            "service:com.example:worker -> type:com.example.web.Controller: Works with orders"
                + " [JSON/HTTP]");
  }

  @Test
  void linesFromSeveralComponentsIntoOneStoreSayWhatEachTouchesThere() {
    String read = "method:com.example.domain.JdbcInvoices#unpaid()";
    facts
        .attribute("shop", "type:com.example.domain.JdbcInvoices", "stereotype", "repository", "{}")
        .relate(
            "shop",
            "package:com.example:app:com.example.domain",
            "CONTAINS",
            "type:com.example.domain.JdbcInvoices")
        .relate("shop", "type:com.example.domain.JdbcInvoices", "CONTAINS", read)
        .relate("shop", read, "READS_TABLE", "table:com.example:app:unpaid_invoices", "{}")
        .relate("shop", "method:com.example.web.Jobs#run()", "CALLS", read);

    Diagram diagram =
        block(section(repo(facts.list()), "components").sections().getFirst(), Diagram.class);

    // #944: both only read the store; what each reads tells them apart.
    assertThat(edges(diagram))
        .contains(
            "type:com.example.domain.JpaOrders -> database:db:5432/shop: Reads orders [JDBC]",
            "type:com.example.web.Jobs -> database:db:5432/shop: Reads unpaid invoices [JDBC]");
  }

  @Test
  void aComponentsLineGoesOnlyToTheStoresItsCodeTouches() {
    String app = "service:com.example:app";
    String ledger = "database:db:5432/ledger";
    String redis = "database:localhost:6379/redis";
    String list = "method:com.example.web.Controller#list()";
    facts
        // The main datasource holds the unit's own schema; a second one is read for its entries.
        .relate(
            "shop",
            app,
            "USES_DATABASE",
            "database:db:5432/shop",
            "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\","
                + " \"schemas\": [\"schema:com.example:app\"]}")
        .relate("shop", "schema:com.example:app", "CONTAINS", "table:com.example:app:orders")
        .relate(
            "shop",
            app,
            "USES_DATABASE",
            ledger,
            "{\"protocol\": \"JDBC\", \"key\": \"shop.ledger.url\","
                + " \"schemas\": [\"schema:com.example:app\"]}")
        .attribute("shop", ledger, "technology", "PostgreSQL", "{}")
        .relate(
            "shop",
            "method:com.example.web.Jobs#run()",
            "READS_TABLE",
            "table:com.example:app:ledger_entries",
            "{}")
        .relate(
            "shop",
            app,
            "USES_DATABASE",
            redis,
            "{\"server\": \"localhost:6379\", \"uses\": [\"template\"]}")
        .attribute("shop", redis, "technology", "Redis 7.4", "{}")
        .attribute("shop", list, "redis-use", "template", "{}");

    Diagram diagram =
        block(section(repo(facts.list()), "components").sections().getFirst(), Diagram.class);

    // #962: the orders table is in the unit's own schema, so in the main database; the ledger's
    // entries are not, so in the other; only the controller's code uses Redis.
    assertThat(edges(diagram).stream().filter(e -> e.contains(" -> database:")))
        .containsExactlyInAnyOrder(
            "type:com.example.domain.JpaOrders -> database:db:5432/shop: Reads orders [JDBC]",
            "type:com.example.web.Jobs -> database:db:5432/ledger: Reads ledger entries [JDBC]",
            "type:com.example.web.Controller -> database:localhost:6379/redis:"
                + " Reads and writes keys");
  }

  @Test
  void aUnitCarriesTheNameDescribeWroteForItUnlessTheJudgeRejectedIt() {
    Fact packaging = facts.find("service:com.example:app", "application-name");
    Fact named =
        io.lantern.harness.LlmFacts.text(
            io.lantern.summarize.describe.DescribeRole.UNITS,
            "test/test",
            "a".repeat(64),
            EntityId.parse("service:com.example:app"),
            Types.DISPLAY_NAME,
            io.lantern.harness.LlmFacts.C4,
            "Shop orders",
            List.of(packaging),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1);
    List<Fact> held = new ArrayList<>(facts.list());
    held.add(named);
    List<Fact> rejected = new ArrayList<>(facts.list());
    rejected.add(
        io.lantern.harness.LlmFacts.withSupport(
            named, io.lantern.harness.checks.SupportJudge.Support.UNSUPPORTED));

    // #946: the name in words beside the build's, which stays the entity's name.
    DocumentEntity app = entity(system(held), "service:com.example:app");
    assertThat(app.name()).isEqualTo("shop-app");
    assertThat(app.readableName()).isEqualTo("Shop orders");
    assertThat(entity(system(rejected), "service:com.example:app").readableName()).isNull();
  }

  @Test
  void twoBoxesNeverShowOneName() {
    Fact packaging = facts.find("service:com.example:app", "application-name");
    java.util.function.BiFunction<String, String, Fact> named =
        (unit, name) ->
            io.lantern.harness.LlmFacts.text(
                io.lantern.summarize.describe.DescribeRole.UNITS,
                "test/test",
                "a".repeat(64),
                EntityId.parse(unit),
                Types.DISPLAY_NAME,
                io.lantern.harness.LlmFacts.C4,
                name,
                List.of(packaging),
                io.lantern.harness.LlmFacts.Basis.INFERRED,
                1);
    List<Fact> same = new ArrayList<>(facts.list());
    same.add(named.apply("service:com.example:app", "Order handling"));
    same.add(named.apply("service:com.example:worker", "order handling"));
    List<Fact> another = new ArrayList<>(facts.list());
    another.add(named.apply("service:com.example:app", "Worker"));

    // #946: a written name two boxes would show is dropped for both; they read as built.
    Document both = system(same);
    assertThat(entity(both, "service:com.example:app").readableName()).isNull();
    assertThat(entity(both, "service:com.example:worker").readableName()).isNull();
    assertThat(entity(system(another), "service:com.example:app").readableName()).isNull();
  }

  @Test
  void aWrittenNameThatOnlyRepeatsTheBuildsWordsGivesWayToTheReadableOne() {
    Fact packaging = facts.find("service:com.example:app", "application-name");
    List<Fact> held = new ArrayList<>(facts.list());
    held.add(
        io.lantern.harness.LlmFacts.text(
            io.lantern.summarize.describe.DescribeRole.UNITS,
            "test/test",
            "a".repeat(64),
            EntityId.parse("service:com.example:app"),
            Types.DISPLAY_NAME,
            io.lantern.harness.LlmFacts.C4,
            "Shop App",
            List.of(packaging),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));

    // #946: "Shop App" says no more than shop-app; the box title-cases it, acronyms kept.
    assertThat(entity(system(held), "service:com.example:app").readableName()).isNull();
  }

  private static DocumentEntity entity(Document document, String id) {
    return document.entities().stream()
        .filter(e -> e.id().toString().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void linesIntoOneComponentKeepTheIntentDescribeStoredForThem() {
    String count = "method:com.example.domain.JpaOrders#countOverdue()";
    facts
        .relate("shop", "type:com.example.domain.JpaOrders", "CONTAINS", count)
        .relate("shop", "method:com.example.web.Jobs#run()", "CALLS", count);
    List<Fact> derived = new ArrayList<>(io.lantern.derive.Derivation.withModel(facts.list(), 1));
    List<Fact> lines =
        derived.stream()
            .filter(
                f ->
                    f.predicate().equals(Types.COMPONENT_LINK)
                        && f.object().value().endsWith("type:com.example.domain.JpaOrders"))
            .toList();
    assertThat(lines).hasSizeGreaterThan(1);
    // Describe words both lines alike (ADR 0103, #739).
    for (Fact line : lines) {
      derived.add(
          io.lantern.harness.LlmFacts.text(
              io.lantern.summarize.describe.DescribeRole.UNITS,
              "test/test",
              "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              line.subject(),
              Types.INTENT,
              line.claimId() != null ? line.claimId() : line.id(),
              "Looks up the stored orders",
              List.of(line),
              io.lantern.harness.LlmFacts.Basis.INFERRED,
              1));
    }

    Document document =
        new Composer("1")
            .compose(ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE), SNAPSHOT, derived);
    Diagram diagram = block(section(document, "components").sections().getFirst(), Diagram.class);

    // #944: what the model wrote is kept, never replaced to tell the lines apart.
    assertThat(edges(diagram))
        .filteredOn(e -> e.contains("-> type:com.example.domain.JpaOrders:"))
        .hasSizeGreaterThan(1)
        .allMatch(e -> e.contains(": Looks up the stored orders"));
  }

  @Test
  void aMethodsNameSaysWhatItDoes() {
    assertThat(C4Views.described("requestDiagnosticSnapshot"))
        .contains("Requests diagnostic snapshot");
    assertThat(C4Views.described("findByVehicleVinOrderByCreatedAtDesc"))
        .contains("Finds by vehicle vin");
    assertThat(C4Views.described("findTop100ByPublishedAtIsNull"))
        .contains("Finds by published at");
    assertThat(C4Views.described("sendSMSAlert")).contains("Sends SMS alert");
    assertThat(C4Views.described("findOrderByNumber")).contains("Finds order by number");
    assertThat(C4Views.described("markAllAsRead")).contains("Marks all as read");
    assertThat(C4Views.described("findByIdForUpdate")).contains("Finds by id");
    assertThat(C4Views.described("summary")).isEmpty();
  }

  @Test
  void aComponentLineShowsTheIntentDescribeStoredForIt() {
    List<Fact> derived = new ArrayList<>(io.lantern.derive.Derivation.withModel(facts.list(), 1));
    Fact line =
        derived.stream()
            .filter(
                f ->
                    f.predicate().equals(Types.COMPONENT_LINK)
                        && f.subject().toString().endsWith("type:com.example.web.Controller")
                        && f.object().value().endsWith("type:com.example.domain.JpaOrders"))
            .findFirst()
            .orElseThrow();
    // Describe words a component line as it words any interaction (ADR 0103, #739).
    derived.add(
        io.lantern.harness.LlmFacts.text(
            io.lantern.summarize.describe.DescribeRole.UNITS,
            "test/test",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            line.subject(),
            Types.INTENT,
            line.claimId() != null ? line.claimId() : line.id(),
            "Lists the stored orders",
            List.of(line),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));

    Document document =
        new Composer("1")
            .compose(ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE), SNAPSHOT, derived);
    Diagram diagram = block(section(document, "components").sections().getFirst(), Diagram.class);

    assertThat(edges(diagram))
        .contains(
            "type:com.example.web.Controller -> type:com.example.domain.JpaOrders:"
                + " Lists the stored orders")
        .noneMatch(
            e ->
                e.startsWith(
                    "type:com.example.web.Controller -> type:com.example.domain.JpaOrders: Calls"));
  }

  /** The runtime facts plus an aspect advising the controller's {@code list()}. */
  private Diagram componentsWithAspect(String aspect, boolean inUnit, String aspectJson) {
    List<Fact> withAspect = new ArrayList<>(facts.list());
    TestFacts more = new TestFacts();
    if (inUnit) {
      more.relate("shop", "package:com.example:app:com.example.web", "CONTAINS", aspect);
    }
    more.attribute("shop", aspect, "stereotype", "component")
        .attribute("shop", aspect, "aspect", "aspect", aspectJson)
        .relate(
            "shop",
            aspect,
            "ADVISES",
            "method:com.example.web.Controller#list()",
            "{\"advice\":\"method:x.A#check()\",\"kind\":\"before\",\"pointcut\":\"p\"}");
    withAspect.addAll(more.list());
    return block(section(repo(withAspect), "components").sections().getFirst(), Diagram.class);
  }

  private static final String BY_ANNOTATION =
      "{\"advice\":[\"before\"],\"pointcuts\":[\"@annotation(p)\"],"
          + "\"annotations\":[\"com.example.rbac.RequiresPermission\"]}";
  private static final String BY_NAME =
      "{\"advice\":[\"before\"],\"pointcuts\":[\"execution(* *(..))\"]}";

  @Test
  void anAspectThatAdvisesAnEntryPointIsAComponentLinkedToIt() {
    Diagram diagram = componentsWithAspect("type:com.example.web.PermissionAspect", true, BY_NAME);

    assertThat(nodes(diagram))
        .contains(
            "type:com.example.web.PermissionAspect \"PermissionAspect\" [Spring AOP aspect]"
                + " in service:com.example:app");
    assertThat(edges(diagram))
        .contains(
            "type:com.example.web.PermissionAspect -> type:com.example.web.Controller: Advises");
  }

  @Test
  void aLibraryAspectOutsideTheUnitCountsOnlyWhenItSelectsByAnnotation() {
    Diagram selecting = componentsWithAspect("type:com.lib.rbac.LibAspect", false, BY_ANNOTATION);
    assertThat(edges(selecting))
        .contains(
            "type:com.lib.rbac.LibAspect -> type:com.example.web.Controller: Advises"
                + " @RequiresPermission");

    Diagram byName = componentsWithAspect("type:com.lib.rbac.LibAspect", false, BY_NAME);
    assertThat(nodes(byName)).noneMatch(n -> n.contains("LibAspect"));
  }

  @Test
  void gatewayRoutesAndFiltersAreComponentsAndRoutesAreLines() {
    String app = "service:com.example:app";
    String routes = "method:com.example.web.Routes#routes()";
    String web = "package:com.example:app:com.example.web";
    List<Fact> gateway =
        new ArrayList<>(
            facts
                .relate("shop", web, "CONTAINS", "type:com.example.web.Routes")
                .relate("shop", "type:com.example.web.Routes", "CONTAINS", routes)
                .attribute(
                    "shop",
                    routes,
                    "gateway-route",
                    "users",
                    "{\"definedIn\": \"java\", \"uri\": \"lb:ws://worker\", \"paths\":"
                        + " [\"/ws/**\"], \"filterTypes\": [\"com.example.web.AuthFilter\"]}")
                .relate("shop", web, "CONTAINS", "type:com.example.web.AuthFilter")
                .attribute(
                    "shop",
                    "type:com.example.web.AuthFilter",
                    "gateway-filter",
                    "gateway",
                    "{\"implements\": \"org.springframework.cloud.gateway.filter.GatewayFilter\"}")
                .attribute(
                    "shop",
                    "module:com.example:app",
                    "gateway-route",
                    "reports",
                    "{\"definedIn\": \"config\", \"uri\": \"http://localhost:8081\"}")
                .relate(
                    "shop",
                    app,
                    "CALLS_HTTP",
                    "service:com.example:worker",
                    "{\"protocol\": \"WebSocket\", \"route\": \"users\", \"paths\":"
                        + " [\"/ws/**\"], \"source\": \""
                        + routes
                        + "\"}")
                .relate(
                    "shop",
                    app,
                    "CALLS_HTTP",
                    "service:com.example:worker",
                    "{\"protocol\": \"HTTP\", \"route\": \"reports\", \"paths\":"
                        + " [\"/reports/**\"], \"source\": \"module:com.example:app\"}")
                .relate(
                    "shop",
                    app,
                    "CALLS_HTTP",
                    "service:com.example:worker",
                    "{\"protocol\": \"HTTP\", \"httpMethod\": \"GET\", \"path\": \"/jobs\"}")
                .list());

    Diagram containers = block(section(repo(gateway), "containers"), Diagram.class);
    assertThat(edges(containers))
        .contains(
            "service:com.example:app -> service:com.example:worker: Routes reports, ws and jobs"
                + " requests [HTTP, WebSocket]");
    Diagram components =
        block(section(repo(gateway), "components").sections().getFirst(), Diagram.class);
    assertThat(nodes(components))
        .contains(
            "type:com.example.web.Routes \"Routes\" [Gateway routes] in service:com.example:app",
            "type:com.example.web.AuthFilter \"AuthFilter\" [Gateway filter]"
                + " in service:com.example:app",
            "module:com.example:app \"Gateway routes\" [Gateway routes]"
                + " in service:com.example:app");
    assertThat(edges(components))
        .contains(
            "type:com.example.web.Routes -> type:com.example.web.AuthFilter:"
                + " Applies on routes users",
            "type:com.example.web.Routes -> service:com.example:worker: Routes ws requests [WebSocket]",
            "module:com.example:app -> service:com.example:worker: Routes reports requests [HTTP]");
  }

  @Test
  void aUnitWithOneComponentGetsNoComponentDiagram() {
    // Without the scheduled job, the repository's bean is reached only from the controller.
    List<Fact> fewer =
        facts.list().stream().filter(f -> !f.predicate().equals("scheduled")).toList();

    assertThat(section(repo(fewer), "components").sections()).isEmpty();
  }

  @Test
  void unitsAreNamedByTheirApplicationNameAndDescribedByTheirMainModule() {
    EntityId module = EntityId.parse("module:com.example:app");
    String packaging = facts.find("module:com.example:app", "packaging").id();
    Map<EntityId, io.lantern.summarize.Summary> summaries =
        Map.of(
            module,
            ComposerTest.summary(
                module, "Serves orders. [fact:" + packaging + "]", List.of(packaging)));

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                TestTexts.with(facts.list(), summaries));

    DocumentEntity app =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("service:com.example:app"))
            .findFirst()
            .orElseThrow();
    assertThat(app.name()).isEqualTo("shop-app");
    // The unit has no responsibility sentence, so its module's summary stands in, marked as such.
    assertThat(app.description())
        .startsWith(
            new TextRun(Assembler.FROM_MODULE, TextRun.Style.EMPHASIS),
            TextRun.of(" "),
            TextRun.of("Serves orders."));
    assertThat(app.description()).hasSize(4);
  }

  @Test
  void aUnitWhoseSentenceTheJudgeRejectedShowsPlainWordingNotItsModulesSummary() {
    EntityId module = EntityId.parse("module:com.example:app");
    String packaging = facts.find("module:com.example:app", "packaging").id();
    Map<EntityId, io.lantern.summarize.Summary> summaries =
        Map.of(
            module,
            ComposerTest.summary(
                module, "Serves orders. [fact:" + packaging + "]", List.of(packaging)));
    List<io.lantern.core.fact.Fact> held =
        new java.util.ArrayList<>(TestTexts.with(facts.list(), summaries));
    held.add(
        io.lantern.harness.LlmFacts.withSupport(
            io.lantern.harness.LlmFacts.text(
                io.lantern.summarize.describe.DescribeRole.UNITS,
                "test/test",
                "a".repeat(64),
                EntityId.parse("service:com.example:app"),
                io.lantern.core.types.Types.RESPONSIBILITY,
                io.lantern.harness.LlmFacts.C4,
                "Takes the shop's orders.",
                List.of(facts.find("module:com.example:app", "packaging")),
                io.lantern.harness.LlmFacts.Basis.INFERRED,
                1),
            io.lantern.harness.checks.SupportJudge.Support.UNSUPPORTED));

    Document document =
        new Composer("1")
            .compose(ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE), SNAPSHOT, held);

    // Rejected though asked again: Lantern's plain wording, never its module's summary, which may
    // share the rejected claims (#751), and never nothing (#933).
    DocumentEntity app =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("service:com.example:app"))
            .findFirst()
            .orElseThrow();
    assertThat(app.name()).isEqualTo("shop-app");
    assertThat(String.valueOf(app.description()))
        .doesNotContain("Serves orders")
        .startsWith("[TextRun[text=A deployable unit that ");
  }

  @Test
  void aUnitsInferredFallbackSaysBoth() {
    EntityId module = EntityId.parse("module:com.example:app");
    String packaging = facts.find("module:com.example:app", "packaging").id();
    Map<EntityId, io.lantern.summarize.Summary> summaries =
        Map.of(
            module,
            ComposerTest.summary(
                module,
                "Serves orders. [fact:" + packaging + "]",
                List.of(packaging),
                io.lantern.summarize.Summary.Purpose.INFERRED));

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                TestTexts.with(facts.list(), summaries));

    DocumentEntity app =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("service:com.example:app"))
            .findFirst()
            .orElseThrow();
    assertThat(app.description().getFirst())
        .isEqualTo(new TextRun(Assembler.FROM_MODULE_INFERRED, TextRun.Style.EMPHASIS));
  }

  @Test
  void aContainerDiagramAboveTheReadabilityThresholdIsSplitByConcernThenPerUnit() {
    TestFacts many = TestFacts.runtime();
    // A profile alternative, for the note.
    many.relate(
            "shop",
            "service:com.example:app",
            "USES_DATABASE",
            "database:localhost:5432/shop",
            "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\","
                + " \"profile\": \"local\"}")
        .relate(
            "shop",
            "service:com.example:app",
            "USES_DATABASE",
            "database:db:5432/shop",
            "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\","
                + " \"absentIn\": [\"local\"]}");
    for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements(); i++) {
      many.relate(
          "shop",
          "service:com.example:app",
          "PUBLISHES",
          "topic:kafka:t" + i,
          "{\"protocol\": \"Kafka\"}");
    }

    Document document = system(many.list());
    Section containers = section(document, "containers");

    assertThat(containers.blocks()).noneMatch(Diagram.class::isInstance);
    // The requests and data still fit; messaging is drawn per unit, from the entry inward: worker
    // calls shop-app, which nothing calls.
    assertThat(containers.sections())
        .extracting(Section::title)
        .containsExactly(
            "Requests and data", "Messaging around worker", "Messaging around shop-app");
    Diagram requests = block(containers.sections().getFirst(), Diagram.class);
    assertThat(requests.title()).isEqualTo("Containers of base and shop: requests and data");
    assertThat(requests.edges())
        .extracting(Diagram.Edge::relationship)
        .doesNotContain("PUBLISHES", "CONSUMES");
    assertThat(requests.nodes())
        .extracting(n -> n.id().toString())
        .contains("service:com.example:app", "service:com.example:worker");
    Diagram worker = block(containers.sections().get(1), Diagram.class);
    assertThat(worker.title()).isEqualTo("Containers around worker of base and shop: messaging");
    assertThat(worker.edges())
        .extracting(Diagram.Edge::relationship)
        .isNotEmpty()
        .isSubsetOf("PUBLISHES", "CONSUMES", "ROUTES_TO");
    // The profile note stays on a container page: the first view's.
    assertThat(containers.blocks()).isEmpty();
    assertThat(containers.sections().getFirst().blocks())
        .anyMatch(b -> b instanceof Callout c && "Under other profiles".equals(c.title()));
    // shop-app's own messaging is still above the threshold: a quality gap, not hidden.
    QualityReport gaps = block(document.sections().getLast(), QualityReport.class);
    assertThat(gaps.failures())
        .filteredOn(f -> f.check().equals(QualityBar.elements(DiagramLimits.DEFAULT)))
        .singleElement()
        .satisfies(
            f ->
                assertThat(f.target())
                    .isEqualTo(
                        CrossReference.Target.block(
                            "containers.messaging.service:com.example:app.diagram")));
  }

  @Test
  void eachCountOfTheContextIsWhatTheContainerViewsDrawEvenWhenSplitByConcern() {
    // Lines no concern names (#978): what Investigate found, to a store and to a system outside,
    // and an interaction of a type the vocabulary adds under a data store. Enough topics that the
    // container view is split by concern.
    Vocabulary vocabulary = subtypes("graph-database", Types.DATABASE, "USES_GRAPH");
    TestFacts many = TestFacts.runtime();
    many.relate(
            "shop",
            "service:com.example:app",
            Types.INVESTIGATED_CONNECTION,
            "database:search",
            "{\"description\": \"Indexes orders for search\", \"technology\": \"Elasticsearch\"}")
        .relate(
            "shop",
            "service:com.example:app",
            Types.INVESTIGATED_CONNECTION,
            "external-system:objects",
            "{\"description\": \"Stores invoices as files\", \"technology\": \"S3\"}")
        .relate(
            "shop",
            "service:com.example:worker",
            "USES_GRAPH",
            "graph-database:neo4j:7687/shop",
            "{\"protocol\": \"Bolt\"}");
    for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements(); i++) {
      many.relate(
          "shop",
          "service:com.example:app",
          "PUBLISHES",
          "topic:kafka:t" + i,
          "{\"protocol\": \"Kafka\"}");
    }

    Document document =
        new Composer("1", vocabulary)
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                Derivation.withModel(many.list(), 1));
    Section containers = section(document, "containers");
    assertThat(containers.blocks()).noneMatch(Diagram.class::isInstance);

    // Every element the container views draw, across all their pages.
    Set<String> drawn = new HashSet<>();
    List<Section> pages = new ArrayList<>(List.of(containers));
    while (!pages.isEmpty()) {
      Section page = pages.removeLast();
      pages.addAll(page.sections());
      page.blocks().stream()
          .filter(Diagram.class::isInstance)
          .forEach(d -> ((Diagram) d).nodes().forEach(n -> drawn.add(n.id().toString())));
    }
    assertThat(drawn)
        .contains("database:search", "external-system:objects", "graph-database:neo4j:7687/shop");
    Map<String, Long> byKind =
        drawn.stream()
            .collect(
                Collectors.groupingBy(
                    id ->
                        id.startsWith("database:") || id.startsWith("graph-database:")
                            ? "Databases"
                            : id.startsWith("topic:")
                                    || id.startsWith("queue:")
                                    || id.startsWith("exchange:")
                                ? "Topics, queues and exchanges"
                                : id.startsWith("external-system:") ? "External systems" : "other",
                    Collectors.counting()));
    assertThat(block(section(document, "context"), Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .contains(
            "Databases " + byKind.get("Databases"),
            "Topics, queues and exchanges " + byKind.get("Topics, queues and exchanges"),
            "External systems " + byKind.get("External systems"));
  }

  @Test
  void aPerUnitViewThatShowsNothingNewIsLeftOut() {
    TestFacts many = TestFacts.runtime();
    String audit = "service:com.example:audit";
    many.attribute("shop", audit, "deployable-unit", "spring-boot", "{}")
        .attribute("shop", audit, "application-name", "audit", "{}")
        .relate(
            "shop",
            "service:com.example:app",
            "CALLS_HTTP",
            audit,
            "{\"protocol\": \"JSON/HTTP\", \"endpoint\": \"POST /audit\"}");
    for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements(); i++) {
      many.relate(
          "shop",
          "service:com.example:app",
          "CALLS_HTTP",
          "external-system:x" + i + ".example.com",
          "{\"protocol\": \"HTTPS\", \"httpMethod\": \"GET\", \"path\": \"/\"}");
    }

    Document document = system(many.list());
    Section containers = section(document, "containers");

    // Requests are drawn per unit, from the entry inward; audit's view, only shop-app's call to it,
    // repeats what shop-app's shows, so it is left out. The data and messaging fit in a view each.
    assertThat(containers.sections())
        .extracting(Section::title)
        .containsExactly("Requests around worker", "Requests around shop-app", "Data", "Messaging");
    assertThat(block(containers.sections().get(1), Diagram.class).nodes())
        .extracting(n -> n.id().toString())
        .contains(audit);
  }

  @Test
  void anExchangeShowsItsTypeFromTheBindingsThatRouteFromIt() {
    TestFacts more = TestFacts.runtime();
    more.relate(
            "shop",
            "service:com.example:app",
            "PUBLISHES",
            "exchange:rabbitmq:audit",
            "{\"protocol\": \"AMQP\"}")
        .relate(
            "shop",
            "exchange:rabbitmq:audit",
            "ROUTES_TO",
            "queue:rabbitmq:audit.log",
            "{\"protocol\": \"AMQP\", \"exchangeType\": \"fanout\"}");

    Diagram diagram = block(section(repo(more.list()), "containers"), Diagram.class);

    assertThat(nodes(diagram))
        .anyMatch(
            n ->
                n.startsWith("exchange:rabbitmq:audit ")
                    && n.contains("[RabbitMQ fanout exchange]"));
  }

  @Test
  void aNewTypeThatExtendsContainerIsDrawnAsOneWithItsLines() {
    // A vocabulary with a type Lantern's code has never heard of, a container, and an interaction
    // with it: the composer reads what they are from the vocabulary alone (ADR 0114).
    List<VocabularyType> types = new ArrayList<>(Vocabularies.standard().types());
    types.add(
        new VocabularyType(
            "search-index",
            TypeKind.ENTITY,
            Types.CONTAINER,
            null,
            null,
            null,
            "A search index a unit queries.",
            new AttributeSchema(Map.of(), Set.of(), false)));
    types.add(
        new VocabularyType(
            "QUERIES_INDEX",
            TypeKind.RELATIONSHIP,
            Types.INTERACTS_WITH,
            null,
            null,
            null,
            "The subject unit queries the object search index.",
            new AttributeSchema(Map.of(), Set.of(), true)));
    Vocabulary vocabulary = new Vocabulary(2, types);
    TestFacts more = TestFacts.runtime();
    more.relate(
        "shop",
        "service:com.example:app",
        "QUERIES_INDEX",
        "search-index:products",
        "{\"protocol\": \"HTTPS\"}");

    Document document =
        new Composer("1", vocabulary)
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                Derivation.withModel(more.list(), 1));

    Diagram diagram = block(section(document, "containers"), Diagram.class);
    assertThat(nodes(diagram)).contains("search-index:products \"products\" in system:snapshot");
    assertThat(edges(diagram))
        .contains("service:com.example:app -> search-index:products: Uses [HTTPS]");
    DocumentEntity index =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("search-index:products"))
            .findFirst()
            .orElseThrow();
    // Drawn as what the container diagram's level makes it, tagged with its type.
    assertThat(index.kind()).isNull();
    assertThat(index.tag()).isEqualTo("Search index");

    // The same facts with Lantern's own vocabulary, which has no such type, draw nothing of it.
    assertThat(nodes(block(section(repo(more.list()), "containers"), Diagram.class)))
        .noneMatch(n -> n.startsWith("search-index:"));
  }

  @Test
  void anInfrastructureServerAndABrokerStayOffTheContainerDiagram() {
    TestFacts more = TestFacts.runtime();
    more.relate(
            "shop",
            "service:com.example:app",
            "USES_INFRASTRUCTURE",
            "infrastructure:config:8888",
            "{\"protocol\": \"HTTP\"}")
        .relate(
            "shop",
            "service:com.example:app",
            "USES_BROKER",
            "message-broker:kafka:kafka:9092",
            "{}");

    Diagram diagram = block(section(repo(more.list()), "containers"), Diagram.class);

    assertThat(nodes(diagram))
        .noneMatch(n -> n.startsWith("infrastructure:") || n.startsWith("message-broker:"));
  }

  @Test
  void aTypeThatExtendsADataStoreIsDrawnAsOne() {
    List<VocabularyType> types = new ArrayList<>(Vocabularies.standard().types());
    types.add(
        new VocabularyType(
            "graph-database",
            TypeKind.ENTITY,
            "database",
            null,
            null,
            null,
            "A graph database.",
            new AttributeSchema(Map.of(), Set.of(), false)));
    Vocabulary vocabulary = new Vocabulary(2, types);
    TestFacts more = TestFacts.runtime();
    more.relate(
        "shop",
        "service:com.example:app",
        "USES_DATABASE",
        "graph-database:neo4j:7687/shop",
        "{\"protocol\": \"Bolt\"}");

    Document document =
        new Composer("1", vocabulary)
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                Derivation.withModel(more.list(), 1));

    assertThat(edges(block(section(document, "containers"), Diagram.class)))
        .anyMatch(e -> e.startsWith("service:com.example:app -> graph-database:neo4j:7687/shop"));
    assertThat(
            document.entities().stream()
                .filter(e -> e.id().type().equals("graph-database"))
                .map(DocumentEntity::kind))
        .containsExactly(DocumentEntity.Kind.DATABASE);
  }

  @Test
  void aDatabaseWithADisplayNameShowsItAndOneWithoutKeepsItsServerAndDatabase() {
    TestFacts more = TestFacts.runtime();
    String own = "database:com.example:app/sql-server:com.example:app";
    more.relate("shop", "service:com.example:app", "USES_DATABASE", own, "{\"protocol\": \"JDBC\"}")
        .attribute("shop", own, "technology", "SQL Server", "{}")
        .attribute("shop", own, "display-name", "shop-app database", "{}");

    Diagram diagram = block(section(repo(more.list()), "containers"), Diagram.class);

    assertThat(nodes(diagram))
        .contains(
            own + " \"shop-app database\" [SQL Server] in system:snapshot",
            "database:db:5432/shop \"shop on db\" [PostgreSQL] in system:snapshot");
  }

  @Test
  void aProfileOnlyAlternativeOfADefaultLinkIsANoteNotABox() {
    TestFacts more = TestFacts.runtime();
    more.relate(
            "shop",
            "service:com.example:app",
            "USES_DATABASE",
            "database:localhost:5432/shop",
            "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\","
                + " \"profile\": \"local\"}")
        .relate(
            "shop",
            "service:com.example:app",
            "USES_DATABASE",
            "database:db:5432/shop",
            "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\","
                + " \"absentIn\": [\"local\"]}");

    Section containers = section(repo(more.list()), "containers");

    assertThat(nodes(block(containers, Diagram.class)))
        .noneMatch(n -> n.startsWith("database:localhost"));
    Callout note =
        containers.blocks().stream()
            .filter(b -> b instanceof Callout c && "Under other profiles".equals(c.title()))
            .map(Callout.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(text(note.content()))
        .isEqualTo(
            "Under the Spring profile local, shop-app connects to shop on localhost instead of"
                + " shop on db.");
    assertThat(block(section(repo(more.list()), "context"), Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .contains("Databases 1");
  }

  @Test
  void anIdentityProviderIsAnExternalSystemWithALinePerPurpose() {
    TestFacts more = TestFacts.runtime();
    more.relate(
            "shop",
            "service:com.example:app",
            "USES_IDENTITY_PROVIDER",
            "external-system:keycloak",
            "{\"purpose\": \"validates-tokens\", \"protocol\": \"OIDC over HTTP\"}")
        .relate(
            "shop",
            "service:com.example:app",
            "USES_IDENTITY_PROVIDER",
            "external-system:keycloak",
            "{\"purpose\": \"gets-tokens\", \"protocol\": \"OAuth2 over HTTP\"}")
        .attribute("shop", "external-system:keycloak", "display-name", "Keycloak", "{}");

    Diagram containers = block(section(repo(more.list()), "containers"), Diagram.class);

    assertThat(nodes(containers)).contains("external-system:keycloak \"Keycloak\"");
    assertThat(edges(containers))
        .contains(
            "service:com.example:app -> external-system:keycloak: Gets tokens from and validates"
                + " tokens with the keys of [OAuth2 over HTTP, OIDC over HTTP]");
    assertThat(nodes(block(section(repo(more.list()), "context"), Diagram.class)))
        .contains("external-system:keycloak \"Keycloak\"");
  }

  @Test
  void aProfileOnlyMailHostIsANoteNotAnotherSystem() {
    TestFacts more = TestFacts.runtime();
    more.relate(
            "shop",
            "service:com.example:app",
            "SENDS_MAIL",
            "external-system:smtp.dev.example.com",
            "{\"protocol\": \"SMTP\", \"source\": \"method:com.example.Mailer#send()\","
                + " \"profile\": \"staging\"}")
        .relate(
            "shop",
            "service:com.example:app",
            "SENDS_MAIL",
            "external-system:smtp.gmail.com",
            "{\"protocol\": \"SMTP\", \"source\": \"method:com.example.Mailer#send()\","
                + " \"absentIn\": [\"staging\"]}");

    Section context = section(repo(more.list()), "context");

    assertThat(nodes(block(context, Diagram.class)))
        .noneMatch(n -> n.startsWith("external-system:smtp.dev"));
    Callout note =
        context.blocks().stream()
            .filter(b -> b instanceof Callout c && "Under other profiles".equals(c.title()))
            .map(Callout.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(text(note.content()))
        .isEqualTo(
            "Under the Spring profile staging, shop-app sends mail through smtp.dev.example.com"
                + " instead of Gmail SMTP.");
  }

  @Test
  void composesTheSameWhateverTheOrderOfTheFacts() {
    List<Fact> reversed = new ArrayList<>(facts.list());
    Collections.reverse(reversed);

    assertThat(io.lantern.core.document.DocumentJson.write(system(reversed)))
        .isEqualTo(io.lantern.core.document.DocumentJson.write(system(facts.list())));
  }

  @Test
  void aRepositoryWithoutUnitsSaysSo() {
    Section containers = section(repo(TestFacts.shop().list()), "containers");

    assertThat(text(block(containers, Callout.class).content()))
        .isEqualTo("No deployable unit was found in the code.");
  }

  /** A vocabulary with one type under the given parent, and the relation that reaches it. */
  private static Vocabulary subtypes(String name, String parent, String relation) {
    List<VocabularyType> types = new ArrayList<>(Vocabularies.standard().types());
    types.add(
        new VocabularyType(
            name,
            TypeKind.ENTITY,
            parent,
            null,
            null,
            null,
            "A test subtype.",
            new AttributeSchema(Map.of(), Set.of(), false)));
    types.add(
        new VocabularyType(
            relation,
            TypeKind.RELATIONSHIP,
            Types.INTERACTS_WITH,
            null,
            null,
            null,
            "The subject unit reaches the object.",
            new AttributeSchema(Map.of(), Set.of(), true)));
    return new Vocabulary(2, types);
  }

  /** The checks the quality report of a composed document fails, by name. */
  private static List<String> qualityChecks(Document document) {
    QualityReport report = block(document.sections().getLast(), QualityReport.class);
    return report.failures().stream().map(QualityReport.Failure::check).toList();
  }

  @Test
  void aTypeUnderAContainerIsDrawnAtTheContainerLevelWithItsTechnology() {
    Vocabulary vocabulary = subtypes("search-index", Types.CONTAINER, "QUERIES_INDEX");
    TestFacts more = TestFacts.runtime();
    more.relate(
            "shop",
            "service:com.example:app",
            "QUERIES_INDEX",
            "search-index:products",
            "{\"protocol\": \"HTTPS\"}")
        .attribute("shop", "search-index:products", "technology", "Elasticsearch", "{}");

    Document document =
        new Composer("1", vocabulary)
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                Derivation.withModel(more.list(), 1));

    assertThat(nodes(block(section(document, "containers"), Diagram.class)))
        .anyMatch(n -> n.startsWith("search-index:products") && n.contains("[Elasticsearch]"));
    assertThat(qualityChecks(document))
        .doesNotContain(QualityBar.ONE_LEVEL)
        .noneMatch(c -> c.contains("technology"));
  }

  @Test
  void aTypeUnderADataStoreIsCountedAmongTheDatabases() {
    Vocabulary vocabulary = subtypes("graph-database", Types.DATABASE, "USES_GRAPH");
    TestFacts more = TestFacts.runtime();
    more.relate(
            "shop",
            "service:com.example:app",
            "USES_GRAPH",
            "graph-database:neo4j:7687/shop",
            "{\"protocol\": \"Bolt\"}")
        .attribute("shop", "graph-database:neo4j:7687/shop", "technology", "Neo4j", "{}");

    Document document =
        new Composer("1", vocabulary)
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                Derivation.withModel(more.list(), 1));

    assertThat(nodes(block(section(document, "containers"), Diagram.class)))
        .anyMatch(n -> n.startsWith("graph-database:") && n.contains("[Neo4j]"));
    assertThat(qualityChecks(document))
        .doesNotContain(QualityBar.ONE_LEVEL)
        .noneMatch(c -> c.contains("technology"));
    // Counted as a database: the system context's "Databases" count goes from one to two.
    assertThat(block(section(document, "context"), Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .contains("Databases 2");
  }

  @Test
  void aTypeUnderAnExternalSystemIsDrawnOutsideTheSystem() {
    Vocabulary vocabulary = subtypes("payment-gateway", Types.EXTERNAL_SYSTEM, "CALLS_GATEWAY");
    TestFacts more = TestFacts.runtime();
    more.relate(
        "shop",
        "service:com.example:app",
        "CALLS_GATEWAY",
        "payment-gateway:pay.example.com",
        "{\"protocol\": \"HTTPS\"}");

    Document document =
        new Composer("1", vocabulary)
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                Derivation.withModel(more.list(), 1));

    Diagram diagram = block(section(document, "containers"), Diagram.class);
    List<String> nodes = nodes(diagram);
    assertThat(nodes)
        .anyMatch(n -> n.startsWith("payment-gateway:pay.example.com"))
        .noneMatch(n -> n.startsWith("payment-gateway:") && n.contains("in system:"));
    // Drawn as an external system is: the same shape as the fixture's own external system.
    assertThat(style(diagram, "payment-gateway:pay.example.com"))
        .isEqualTo(style(diagram, "external-system:pay.example.com"));
    assertThat(qualityChecks(document))
        .doesNotContain(QualityBar.ONE_LEVEL)
        .noneMatch(c -> c.contains("technology"));
    // Counted as an external system: the context's count goes from one to two.
    assertThat(block(section(document, "context"), Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .contains("External systems 2");
  }

  @Test
  void aTypeUnderAServiceIsDrawnAsAUnitThatUsesItsDatabase() {
    Vocabulary vocabulary = subtypes("batch-service", Types.SERVICE, "RUNS_BATCH");
    String batch = "batch-service:com.example:batch";
    TestFacts more = TestFacts.runtime();
    more.relate("shop", batch, "CONTAINS", "module:com.example:batch")
        .attribute("shop", "module:com.example:batch", "packaging", "jar")
        .attribute("shop", batch, "deployable-unit", "spring-boot", "{}")
        .attribute("shop", batch, "technology", "Spring Batch 5 / Java 21", "{}")
        .relate(
            "shop",
            batch,
            "USES_DATABASE",
            "database:warehouse:5432/batch",
            "{\"protocol\": \"JDBC\"}")
        .attribute("shop", "database:warehouse:5432/batch", "technology", "PostgreSQL", "{}")
        .relate(
            "shop",
            "service:com.example:app",
            "CALLS_HTTP",
            batch,
            "{\"protocol\": \"HTTP\", \"httpMethod\": \"POST\", \"path\": \"/runs\"}");

    Document document =
        new Composer("1", vocabulary)
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                Derivation.withModel(more.list(), 1));

    Diagram diagram = block(section(document, "containers"), Diagram.class);
    // Drawn as a unit inside the system, with its technology, not as a destination outside it.
    assertThat(nodes(diagram))
        .anyMatch(
            n ->
                n.startsWith(batch)
                    && n.contains("[Spring Batch 5 / Java 21]")
                    && n.contains(" in system:"));
    assertThat(edges(diagram))
        .anyMatch(e -> e.startsWith(batch + " -> database:warehouse"))
        .anyMatch(e -> e.startsWith("service:com.example:app -> " + batch));
    assertThat(qualityChecks(document))
        .doesNotContain(QualityBar.ONE_LEVEL)
        .noneMatch(c -> c.contains("technology"));
    // Its database counts: the context's "Databases" count goes from one to two.
    assertThat(block(section(document, "context"), Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .contains("Databases 2");
  }

  @Test
  void aTypeUnderAServiceGetsItsViewFromTheEntryInward() {
    Vocabulary vocabulary = subtypes("batch-service", Types.SERVICE, "RUNS_BATCH");
    String batch = "batch-service:com.example:batch";
    TestFacts many = TestFacts.runtime();
    many.relate("shop", batch, "CONTAINS", "module:com.example:batch")
        .attribute("shop", "module:com.example:batch", "packaging", "jar")
        .attribute("shop", batch, "deployable-unit", "spring-boot", "{}")
        .attribute("shop", batch, "application-name", "batch", "{}")
        .relate(
            "shop",
            "service:com.example:app",
            "CALLS_HTTP",
            batch,
            "{\"protocol\": \"JSON/HTTP\", \"endpoint\": \"POST /runs\"}");
    for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements(); i++) {
      many.relate(
          "shop",
          batch,
          "CALLS_HTTP",
          "external-system:x" + i + ".example.com",
          "{\"protocol\": \"HTTPS\", \"httpMethod\": \"GET\", \"path\": \"/\"}");
    }

    Document document =
        new Composer("1", vocabulary)
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                Derivation.withModel(many.list(), 1));

    // shop-app calls batch, so batch's view comes after shop-app's, as a service's would (worker's
    // view repeats shop-app's and is left out). How batch is named is #864's.
    assertThat(section(document, "containers").sections())
        .extracting(Section::title)
        .filteredOn(t -> t.startsWith("Requests around"))
        .satisfiesExactly(
            t -> assertThat(t).isEqualTo("Requests around shop-app"),
            t -> assertThat(t).startsWith("Requests around").endsWith("batch"));
  }

  private static Diagram.Style style(Diagram diagram, String id) {
    return diagram.nodes().stream()
        .filter(n -> n.id().toString().equals(id))
        .findFirst()
        .orElseThrow()
        .style();
  }
}
