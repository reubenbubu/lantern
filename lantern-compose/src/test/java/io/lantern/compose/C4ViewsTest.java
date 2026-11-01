package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
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
import io.lantern.derive.Derivation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
            Derivation.withModel(facts, 1),
            Map.of());
  }

  private Document system(List<Fact> facts) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
            SNAPSHOT,
            Derivation.withModel(facts, 1),
            Map.of());
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
            "system:snapshot -> external-system:pay.example.com: Calls POST /charges [prod]");
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
                                withPeople.list(),
                                Map.of()),
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
  void aFrontendIsAContainerWithItsTechnologyItsCallsAndThePeopleWithScreensInIt() {
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

    Document document = system(withFrontend.list());
    Diagram diagram = block(section(document, "containers"), Diagram.class);

    assertThat(nodes(diagram))
        .contains(
            "frontend:shop/web \"web\" [Angular 19.2 · nginx] in system:snapshot",
            "person:user \"User\"");
    assertThat(edges(diagram))
        .contains(
            "frontend:shop/web -> service:com.example:app: Calls GET /orders [JSON/HTTP]",
            "person:user -> frontend:shop/web: Uses the screens /registration [Web browser]");
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
            "service:com.example:app -> external-system:pay.example.com: Calls POST /charges"
                + " [HTTPS] [prod]",
            "service:com.example:app -> topic:kafka:orders: Sends messages [Kafka]",
            "service:com.example:app -> database:db:5432/shop: Stores its data in [JDBC]",
            "service:com.example:worker -> service:com.example:app: Calls GET /orders [JSON/HTTP]");
  }

  @Test
  void theSystemScopeShowsEveryUnitAndLine() {
    Document document = system(facts.list());
    Diagram diagram = block(section(document, "containers"), Diagram.class);

    assertThat(edges(diagram))
        .contains("topic:kafka:orders -> service:com.example:worker: Delivers messages [Kafka]");
    // The system's purpose, unknown without the LLM's wording (ADR 0060), then its repositories.
    assertThat(section(document, "overview").blocks()).hasSize(2);
    assertThat(section(document, "overview").blocks().getLast())
        .satisfies(
            b ->
                assertThat(((ListBlock) b).items())
                    .extracting(i -> text(i.content()))
                    .containsExactly(": runs ", ": runs "));
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
            "type:com.example.domain.JpaOrders -> database:db:5432/shop: Reads [JDBC]",
            "type:com.example.web.Controller -> external-system:pay.example.com:"
                + " Calls POST /charges [HTTPS] [prod]",
            "service:com.example:worker -> type:com.example.web.Controller: Calls GET /orders"
                + " [JSON/HTTP]");
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
            "service:com.example:app -> service:com.example:worker: Calls and routes"
                + " /reports/**, /ws/** and 1 more [HTTP, WebSocket]");
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
            "type:com.example.web.Routes -> service:com.example:worker: Routes /ws/** [WebSocket]",
            "module:com.example:app -> service:com.example:worker: Routes /reports/** [HTTP]");
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
                facts.list(),
                summaries);

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
                facts.list(),
                summaries);

    DocumentEntity app =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("service:com.example:app"))
            .findFirst()
            .orElseThrow();
    assertThat(app.description().getFirst())
        .isEqualTo(new TextRun(Assembler.FROM_MODULE_INFERRED, TextRun.Style.EMPHASIS));
  }

  @Test
  void aContainerDiagramOfMoreThanFifteenElementsIsSplitByConcernThenPerUnit() {
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
    for (int i = 0; i < 16; i++) {
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
    // shop-app's own messaging still has more than 15 elements: a quality gap, not hidden.
    QualityReport gaps = block(document.sections().getLast(), QualityReport.class);
    assertThat(gaps.failures())
        .filteredOn(f -> f.check().equals("At most 15 elements"))
        .singleElement()
        .satisfies(
            f ->
                assertThat(f.target())
                    .isEqualTo(
                        CrossReference.Target.block(
                            "containers.messaging.service:com.example:app.diagram")));
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
    for (int i = 0; i < 16; i++) {
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
}
