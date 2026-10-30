package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Git;
import io.lantern.link.Investigator;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.Role;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.MavenMirror;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The Investigate role on the real test set project-4 (APA-CRM, #269, ADR 0082), with recorded
 * answers: the whole pipeline runs on its eight repositories at their pins with a fake model for
 * every other role, and the Investigate role's calls are answered from the recordings in {@code
 * testsets/project-4/recordings} ({@code lantern.llm.recordings}, mode replay). Checks that the
 * investigator adds the reference's risks static extraction misses (the version skew of
 * crm-shared-lib, the identity headers the services trust and the event published before its
 * transaction commits) and the gateway's line to Eureka, each with citations that hold at the pins,
 * and that every template marks them as found by the LLM.
 *
 * <p>The recordings are hand-written: a scripted model ({@link #scripted()}) reads the code with
 * the tools and proposes these items. {@code -Dlantern.test.rerecord=true} rewrites them from it,
 * never from a real model; a real recording is made with the scoring harness (ADR 0082). Runs only
 * when {@code lantern.test.project4} names the folder {@code testsets/project-4/setup.sh} set
 * project-4 up in. Writes the system's report, a repository's three documents and the facts to
 * {@code target/project-4-investigate}, to look at.
 */
@SpringBootTest(
    properties = {
      "lantern.llm.models.fake.provider=fake",
      "lantern.llm.models.fake.id=fake-model",
      "lantern.llm.models.fake.context-window=1000000",
      "lantern.llm.models.fake.prices.input=0",
      "lantern.llm.models.fake.prices.output=0",
      "lantern.llm.models.fake.capabilities=tool-use",
      "lantern.llm.tiers.high-volume=fake",
      "lantern.llm.tiers.reasoning=fake",
      "lantern.llm.anthropic.api-key=",
      "lantern.llm.recordings.roles=investigate"
    })
@Import({TestcontainersConfiguration.class, Project4InvestigateIT.Scripted.class})
@EnabledIfSystemProperty(
    named = "lantern.test.project4",
    matches = ".+",
    disabledReason = "lantern.test.project4 names no folder project-4 is set up in")
class Project4InvestigateIT {

  /** The workspace; JUnit removes it after the class. */
  @TempDir static Path WORKSPACE;

  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();

  /** The recorded answers of the Investigate role on project-4, at its pins. */
  static final Path RECORDINGS = REPOSITORY.resolve("testsets/project-4/recordings");

  private static final boolean RERECORD = Boolean.getBoolean("lantern.test.rerecord");

  private static final Path OUT = Path.of("target", "project-4-investigate");

  @TestConfiguration(proxyBeanMethods = false)
  static class Scripted {
    @Bean
    FakeLlmProvider fakeLlmProvider() {
      return scripted();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    registry.add("lantern.workspace", WORKSPACE::toString);
    registry.add("lantern.llm.recordings.directory", RECORDINGS::toString);
    registry.add("lantern.llm.recordings.mode", () -> RERECORD ? "record" : "replay");
    String file = System.getProperty("lantern.test.properties");
    if (file == null || file.isBlank()) {
      file = System.getenv("LANTERN_TEST_PROPERTIES");
    }
    MavenMirror mirror = file == null || file.isBlank() ? null : MavenMirror.from(Path.of(file));
    if (mirror != null) {
      registry.add(MavenMirror.PROPERTY + ".id", mirror::id);
      registry.add(MavenMirror.PROPERTY + ".url", () -> mirror.url().toString());
    }
  }

  @Autowired AnalysisRun run;
  @Autowired FactRepository facts;

  // ---------------------------------------------------------------------------------------------
  // The scripted model, which the recordings were made from

  /** A unit's line in the prompt: its ID, then its name. */
  private static final Pattern UNIT =
      Pattern.compile("^- (service:\\S+), named ([^,]+),", Pattern.MULTILINE);

  /**
   * A model that, for the system, reads the gateway's filter, the shared permission aspect, the
   * organization's creation and the services' POMs and gateway properties, then proposes the
   * version skew, the trusted identity headers, the event published before commit and the gateway's
   * line to Eureka; for each unit it proposes nothing. Every other role is answered as {@link
   * Project0EndToEndIT}'s fake answers it.
   */
  static FakeLlmProvider scripted() {
    LlmProvider others = new Project0EndToEndIT.Fake().fakeLlmProvider();
    return new FakeLlmProvider()
        .answering(
            request -> {
              if (request.role() != Role.INVESTIGATE) {
                return others.complete("fake-model", request).text();
              }
              if (!request.purpose().startsWith("investigate the system")) {
                return "{\"proposals\": []}";
              }
              return proposals(request);
            })
        .callingTools(
            request ->
                request.role() == Role.INVESTIGATE
                        && request.purpose().startsWith("investigate the system")
                        && request.messages().size() == 1
                    ? List.of(
                        read(
                            1,
                            "Discovery-and-Gateway",
                            "api-gateway/src/main/java/com/crm/gateway/filters/AuthorizeAndCheckAccessFilter.java",
                            40,
                            66),
                        read(
                            2,
                            "crm-shared-lib",
                            "rbac/src/main/java/com/crm/sharedlib/rbac/aspectj/PermissionControlAspect.java",
                            20,
                            40),
                        read(
                            3,
                            "Main-Service",
                            "src/main/java/com/crm/main/service/operation/OrganizationCreatorService.java",
                            40,
                            70),
                        read(4, "Auth-Service", "pom.xml", 25, 40),
                        read(5, "Main-Service", "pom.xml", 25, 40),
                        read(
                            6,
                            "Discovery-and-Gateway",
                            "api-gateway/src/main/resources/application.properties",
                            1,
                            10))
                    : List.of());
  }

  private static LlmToolCall read(int n, String repo, String path, int from, int to) {
    return new LlmToolCall(
        "toolu_0" + n,
        "read_file",
        FactJson.parse(
            "{\"repo\":\""
                + repo
                + "\",\"path\":\""
                + path
                + "\",\"from\":"
                + from
                + ",\"to\":"
                + to
                + "}"));
  }

  /** The scripted proposals, naming the units by the IDs the prompt gives them. */
  private static String proposals(LlmRequest request) {
    Map<String, String> units = new LinkedHashMap<>();
    Matcher m = UNIT.matcher(request.messages().getFirst().text());
    while (m.find()) {
      units.put(m.group(2).strip(), m.group(1));
    }
    String gateway = units.get("api-gateway");
    String eureka = units.get("eureka-service");
    String main = units.get("main-service");
    String auth = units.get("auth-service");
    return """
        {"proposals": [
        {"kind": "risk", "title": "Identity headers trusted from any caller",
         "finding": "The gateway adds User-Id and User-Permissions headers to the requests it authorizes, and the shared permission aspect reads User-Permissions from whatever request reaches a service; nothing strips the headers a client sends.",
         "consequence": "Anyone who reaches a service directly, or through a route the gateway does not authorize, can act as any user with any permission.",
         "impact": "high", "confidence": "medium", "units": ["%1$s", "%2$s"],
         "names": ["USER_ID_HEADER_NAME", "USER_PERMISSIONS_HEADER_NAME", "getHeader"],
         "cites": ["Discovery-and-Gateway:api-gateway/src/main/java/com/crm/gateway/filters/AuthorizeAndCheckAccessFilter.java:53-55",
                   "crm-shared-lib:rbac/src/main/java/com/crm/sharedlib/rbac/aspectj/PermissionControlAspect.java:29-29"]},
        {"kind": "risk", "title": "Events published before the transaction commits",
         "finding": "createOrganization is @Transactional and sends the role-changed and organization-created messages inside the transaction, with no outbox.",
         "consequence": "Consumers can act on an organization before it is committed, or on one whose transaction rolls back.",
         "impact": "medium", "confidence": "high", "units": ["%2$s"],
         "names": ["Transactional", "sendOrgUserRoleChanged", "sendOrganizationCreatedEvent"],
         "cites": ["Main-Service:src/main/java/com/crm/main/service/operation/OrganizationCreatorService.java:46-64"]},
        {"kind": "risk", "title": "crm-shared-lib versions differ between services",
         "finding": "Each service takes crm-shared-lib as a SNAPSHOT at its own version: auth-service 0.2.0, main-service 0.3.0.",
         "consequence": "Builds are not reproducible, and the message classes the services share may not match between sender and receiver.",
         "impact": "medium", "confidence": "high", "units": ["%3$s", "%2$s"],
         "names": ["crm-shared-lib", "0.2.0-SNAPSHOT", "0.3.0-SNAPSHOT"],
         "cites": ["Auth-Service:pom.xml:30-33", "Main-Service:pom.xml:31-34"]},
        {"kind": "connection", "from": "%1$s", "to": "%4$s", "interaction": "http",
         "description": "Resolves service instances in", "technology": "Eureka REST",
         "confidence": "high", "names": ["eureka.client.serviceUrl.defaultZone"],
         "cites": ["Discovery-and-Gateway:api-gateway/src/main/resources/application.properties:4-4"]}
        ]}
        """
        .formatted(gateway, main, auth, eureka);
  }

  // ---------------------------------------------------------------------------------------------
  // The test

  @Test
  void theInvestigatorAddsTheRisksStaticExtractionMissesWithVerifiedCitations() throws Exception {
    Path setFolder = Path.of(System.getProperty("lantern.test.project4")).toAbsolutePath();
    Map<String, Path> repos = new LinkedHashMap<>();
    Map<String, String> pins = new LinkedHashMap<>();
    for (String line : Files.readAllLines(REPOSITORY.resolve("testsets/project-4/pins.txt"))) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.trim().split("\\s+");
      repos.put(parts[0], setFolder.resolve(parts[0]));
      pins.put(parts[0], parts[2]);
    }
    repos.forEach(
        (name, path) ->
            assertThat(Git.run(path, "rev-parse", "HEAD").out())
                .as(name)
                .isEqualTo(pins.get(name)));

    AnalysisRun.Result result = run.run("project-4", null, repos);

    result
        .builds()
        .forEach((name, b) -> assertThat(b.status()).as(name).isEqualTo(BuildStatus.SUCCEEDED));
    // Six units and the system, each asked once, all answered from the recordings.
    assertThat(result.investigationsAsked()).isEqualTo(7);
    List<Fact> found =
        facts.findValidAt(result.snapshot().number(), repos.keySet()).stream()
            .filter(f -> f.origin().name().equals(Investigator.NAME))
            .toList();
    assertThat(found)
        .filteredOn(f -> f.predicate().equals(Investigator.RISK))
        .extracting(f -> f.object().value())
        .containsExactlyInAnyOrder(
            "Identity headers trusted from any caller",
            "Events published before the transaction commits",
            "crm-shared-lib versions differ between services");
    assertThat(found)
        .filteredOn(f -> f.predicate().equals(Investigator.CONNECTION))
        .singleElement()
        .satisfies(f -> assertThat(f.object().value()).contains("eureka-service"));
    // Every citation holds at the pins, checked here again from git.
    for (Fact fact : found) {
      assertThat(fact.evidence().commit()).isEqualTo(pins.get(fact.evidence().repo()));
      List<String> names = strings(fact.attributes().get("names"));
      List<String> cited = new ArrayList<>();
      for (String cite : strings(fact.attributes().get("cites"))) {
        String[] parts = cite.split(":");
        String[] range = parts[2].split("-");
        List<String> lines =
            Git.run(repos.get(parts[0]), "show", pins.get(parts[0]) + ":" + parts[1])
                .out()
                .lines()
                .toList();
        String text =
            String.join(
                "\n", lines.subList(Integer.parseInt(range[0]) - 1, Integer.parseInt(range[1])));
        assertThat(names).as(cite).anyMatch(text::contains);
        cited.add(text);
      }
      assertThat(names).allMatch(n -> cited.stream().anyMatch(t -> t.contains(n)));
    }

    // Every template marks them as found by the LLM.
    Files.createDirectories(OUT);
    for (AnalysisRun.Rendered rendered : result.documents()) {
      String name = rendered.file().getFileName().toString();
      if (name.startsWith("_system") || name.startsWith("Main-Service")) {
        Files.copy(rendered.file(), OUT.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        assertThat(Files.readString(rendered.file()))
            .as(name)
            .contains("Found by the LLM:")
            .contains("Events published before the transaction commits");
      }
    }
    StringBuilder json = new StringBuilder("[\n");
    found.forEach(f -> json.append(FactJson.write(f)).append(",\n"));
    Files.writeString(OUT.resolve("investigator-facts.json"), json.append("]\n").toString());
  }

  private static List<String> strings(JsonNode array) {
    List<String> result = new ArrayList<>();
    array.forEach(n -> result.add(n.asString()));
    return result;
  }
}
