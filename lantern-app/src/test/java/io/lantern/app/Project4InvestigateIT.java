package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Document;
import io.lantern.core.document.Section;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Git;
import io.lantern.link.Investigator;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.Role;
import io.lantern.sandbox.MavenMirror;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The Investigate role on the real test set project-4 (APA-CRM, #269, ADR 0110), with scripted
 * answers: the whole pipeline runs on its repositories at their pins with a fake model for every
 * other role, and a scripted model answers Investigate's lead conversations. It confirms each lead
 * of the two kinds the issue names, identity taken from the request and a send before commit,
 * citing the code each lead shows, and leaves the others unclear. So this shows that a verdict is
 * checked, stored and marked end to end on real code, not what a model finds: that is the
 * recordings' (#529). It checks that the header-trust and before-commit leads are confirmed, that
 * every citation of a stored risk holds in git at the pins, and that the system's report, the
 * Explorer and the Brief mark the risks as found by the LLM.
 *
 * <p>Runs only when {@code lantern.test.project4} names the folder {@code
 * testsets/project-4/setup.sh} set project-4 up in. Writes the stored verdicts and risks to {@code
 * target/project-4-investigate}, to look at.
 */
@SpringBootTest(
    properties = {
      "lantern.llm.models.fake.provider=fake",
      "lantern.llm.models.fake.id=fake-model",
      "lantern.llm.models.fake.context-window=1000000",
      "lantern.llm.models.fake.capabilities=tool-use",
      "lantern.llm.models.fake.prices.input=0",
      "lantern.llm.models.fake.prices.output=0",
      "lantern.llm.tiers.high-volume=fake",
      "lantern.llm.tiers.reasoning=fake",
      "lantern.llm.anthropic.api-key="
    })
@Import({TestcontainersConfiguration.class, Project4InvestigateIT.Scripted.class})
@EnabledIfSystemProperty(
    named = "lantern.test.project4",
    matches = ".+",
    disabledReason = "lantern.test.project4 names no folder project-4 is set up in")
class Project4InvestigateIT {

  /** The workspace; JUnit removes it after the class. */
  @TempDir static Path WORKSPACE;

  private static final Path OUT = Path.of("target", "project-4-investigate");

  /** The kinds of lead the scripted model confirms. */
  static final Set<String> CONFIRMED = Set.of("identity-from-request", "send-before-commit");

  private static final JsonMapper JSON = JsonMapper.builder().build();

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
  // The scripted model

  private static final Pattern ITEM =
      Pattern.compile("<item id=\"(I[0-9]+)\">\n(.*?)\n</item>", Pattern.DOTALL);
  private static final Pattern KIND =
      Pattern.compile("^Lead \\S+, of kind (\\S+)\\.$", Pattern.MULTILINE);
  private static final Pattern CODE =
      Pattern.compile(
          "<code repo=\"([^\"]+)\" path=\"([^\"]+)\" lines=\"(\\d+)-(\\d+)\">\n(.*?)</code>",
          Pattern.DOTALL);
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
  private static final Set<String> NOT_NAMES =
      Set.of(
          "public",
          "private",
          "protected",
          "static",
          "final",
          "class",
          "return",
          "import",
          "package",
          "void",
          "this",
          "null",
          "true",
          "false",
          "string",
          "object",
          "list",
          "value");

  /**
   * A model that, in a lead conversation's first turn, submits a verdict for every item: confirmed
   * for the kinds in {@link #CONFIRMED}, unclear for the others, each citing the first code excerpt
   * the item shows and naming an identifier of its first line; every other request is answered as
   * {@link Project0EndToEndIT}'s fake answers it.
   */
  static FakeLlmProvider scripted() {
    LlmProvider others = new Project0EndToEndIT.Fake().fakeLlmProvider();
    return new FakeLlmProvider()
        .callingTools(
            request ->
                request.role() == Role.INVESTIGATE
                    ? verdicts(request)
                    : FakeLlmConfiguration.toolCalls(request))
        .answering(
            request ->
                request.role() == Role.INVESTIGATE
                    ? "Done."
                    : others.complete("fake-model", request).text());
  }

  /** The lead conversations' first requests, written out to look at. */
  static final List<String> ASKED = java.util.Collections.synchronizedList(new ArrayList<>());

  private static List<LlmToolCall> verdicts(LlmRequest request) {
    if (!request.system().contains("Task: check leads.") || request.messages().size() != 1) {
      return List.of();
    }
    ASKED.add(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    Matcher item = ITEM.matcher(request.messages().getFirst().text());
    while (item.find()) {
      String text = item.group(2);
      Matcher kind = KIND.matcher(text);
      Matcher code = CODE.matcher(text);
      if (!kind.find() || !code.find()) {
        continue;
      }
      int from = Integer.parseInt(code.group(3));
      int to = Math.min(Integer.parseInt(code.group(4)), from + 39);
      String name = null;
      for (String line : code.group(5).lines().toList()) {
        String source = line.replaceFirst("^\\s*\\d+  ", "");
        Matcher identifier = IDENTIFIER.matcher(source);
        while (name == null && identifier.find()) {
          String lower = identifier.group().toLowerCase(Locale.ROOT);
          if (!NOT_NAMES.contains(lower) && !javax.lang.model.SourceVersion.isKeyword(lower)) {
            name = identifier.group();
          }
        }
        if (name != null) {
          break;
        }
      }
      if (name == null) {
        continue;
      }
      String cite = code.group(1) + ":" + code.group(2) + ":" + from + "-" + to;
      boolean confirmed = CONFIRMED.contains(kind.group(1));
      entries.add(
          "{\"lead\": \""
              + item.group(1)
              + "\", \"verdict\": \""
              + (confirmed ? "confirmed" : "unclear")
              + "\", \"kind\": \"risk\","
              + (confirmed
                  ? " \"title\": \"Confirmed by the scripted model\", \"finding\": \"The code"
                      + " shown does what the lead says, on a path a caller can take.\","
                      + " \"consequence\": \"A caller or a failure can act on it.\","
                      + " \"impact\": \"high\", \"confidence\": \"medium\","
                  : "")
              + " \"names\": [\""
              + name
              + "\"], \"cites\": [\""
              + cite
              + "\"]}");
    }
    return List.of(
        new LlmToolCall(
            "scripted-submit",
            "submit",
            JSON.readTree("{\"items\": [" + String.join(", ", entries) + "]}")));
  }

  // ---------------------------------------------------------------------------------------------
  // The case

  @Test
  void theHeaderTrustAndBeforeCommitLeadsAreConfirmedWithCitationsThatHoldAndMarked()
      throws IOException {
    Path folder = Path.of(System.getProperty("lantern.test.project4"));
    Map<String, Path> crm = new TreeMap<>();
    try (Stream<Path> repos = Files.list(folder)) {
      repos
          .filter(repo -> Files.exists(repo.resolve(".git")))
          .forEach(repo -> crm.put(repo.getFileName().toString(), repo));
    }

    AnalysisRun.Result result = run.run("project-4", null, crm);

    List<Fact> held =
        facts.findValidAt(result.snapshot().number(), result.snapshot().commits().keySet());
    List<Fact> verdicts =
        held.stream().filter(f -> f.predicate().equals(Investigator.LEAD_VERDICT)).toList();
    List<Fact> risks = held.stream().filter(f -> f.predicate().equals(Investigator.RISK)).toList();
    Files.createDirectories(OUT);
    StringBuilder out = new StringBuilder();
    verdicts.forEach(
        v ->
            out.append(v.subject())
                .append(' ')
                .append(v.object().value())
                .append(' ')
                .append(v.attributes())
                .append('\n'));
    Files.writeString(OUT.resolve("asked.txt"), String.join("\n\n=====\n\n", ASKED));
    risks.forEach(r -> out.append(r.subject()).append(' ').append(r.attributes()).append('\n'));
    Files.writeString(OUT.resolve("investigated.txt"), out.toString());

    // The header-trust and before-commit leads are confirmed, and each gives a risk.
    for (String kind : CONFIRMED) {
      assertThat(verdicts)
          .as("a confirmed %s lead", kind)
          .anyMatch(
              v ->
                  v.subject().toString().startsWith("lead:" + kind + "/")
                      && v.object().value().equals("confirmed"));
    }
    assertThat(verdicts)
        .as("the header-trust lead of the file service")
        .anyMatch(
            v ->
                v.subject().toString().startsWith("lead:identity-from-request/")
                    && v.subject().toString().toLowerCase(Locale.ROOT).contains("file")
                    && v.object().value().equals("confirmed"));
    assertThat(risks).isNotEmpty();

    // Every citation of a stored risk holds in git at the pins: in a file of the repository at
    // its commit, within its lines, holding the risk's name.
    for (Fact risk : risks) {
      List<String> names = new ArrayList<>();
      risk.attributes().path("names").forEach(n -> names.add(n.asString()));
      for (JsonNode cite : risk.attributes().path("cites")) {
        String[] parts = cite.asString().split(":");
        String[] range = parts[2].split("-");
        Path repo = crm.get(parts[0]);
        assertThat(repo).as(cite.asString()).isNotNull();
        String commit = result.snapshot().commits().get(parts[0]);
        List<String> lines = Git.run(repo, "show", commit + ":" + parts[1]).out().lines().toList();
        int from = Integer.parseInt(range[0]);
        int to = Integer.parseInt(range[1]);
        assertThat(to).as(cite.asString()).isLessThanOrEqualTo(lines.size());
        String cited = String.join("\n", lines.subList(from - 1, to));
        assertThat(names).as(cite.asString()).anyMatch(cited::contains);
      }
    }

    // Every template marks them as found by the LLM.
    Document system =
        result.documents().stream()
            .map(AnalysisRun.Rendered::document)
            .filter(d -> d.id().equals(AnalysisRun.SYSTEM_C4))
            .findFirst()
            .orElseThrow();
    JsonNode findings =
        C4ReportRenderer.toJson(system)
            .data()
            .get("pages")
            .valueStream()
            .filter(p -> p.path("id").asString().equals("findings"))
            .findFirst()
            .orElseThrow();
    JsonNode table =
        findings
            .path("sections")
            .valueStream()
            .filter(s -> s.path("type").asString().equals("table"))
            .findFirst()
            .orElseThrow();
    // A risk it found is badged; a finding of a fixed check it only worded is not.
    long worded =
        held.stream().filter(f -> f.predicate().equals(Investigator.FINDING_WORDING)).count();
    assertThat(table.path("rows").size()).isEqualTo(risks.size() + (int) worded);
    assertThat(table.path("llmRows").size()).isEqualTo(risks.size());
    String riskUnit = risks.getFirst().subject().toString();
    boolean briefMarked =
        result.documents().stream()
            .map(AnalysisRun.Rendered::document)
            .filter(d -> d.id().endsWith(".brief"))
            .flatMap(d -> d.sections().stream())
            .filter(s -> s.id().equals("risks"))
            .flatMap(s -> s.blocks().stream())
            .filter(Callout.class::isInstance)
            .map(Callout.class::cast)
            .anyMatch(
                c ->
                    !c.content().isEmpty()
                        && c.content().getFirst() instanceof TextRun run
                        && run.text().equals("Found by the LLM:"));
    assertThat(briefMarked).as("a Brief marks a risk of " + riskUnit).isTrue();
    boolean explorerMarked =
        result.documents().stream()
            .map(AnalysisRun.Rendered::document)
            .filter(d -> !d.id().contains("."))
            .flatMap(d -> d.sections().stream())
            .filter(s -> s.id().equals("findings"))
            .map(Section::blocks)
            .anyMatch(
                blocks ->
                    blocks.stream().anyMatch(b -> b.toString().contains("Found by the LLM:")));
    assertThat(explorerMarked).as("an Explorer lists the risks").isTrue();
  }
}
