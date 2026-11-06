package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Document;
import io.lantern.core.document.Section;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Git;
import io.lantern.core.types.Types;
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

  /**
   * How the first message's question opens for each kind in {@link #CONFIRMED}
   * (prompts/investigate-questions.toml), since the message names the kind no other way (#860).
   */
  private static final Map<String, String> QUESTIONS =
      Map.of(
          "identity-from-request", "question = \"The code of ",
          "send-before-commit", "question = \"A database transaction runs through ");

  /** A numbered line of a piece of code in the first message. */
  private static final Pattern CODE_LINE = Pattern.compile("^(\\d+)  (.*)$");

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
   * A model that, in a lead conversation's first turn, answers its lead through the answer tool:
   * confirmed for the kinds in {@link #CONFIRMED}, unclear for the others, each citing the first
   * code the message shows, quoting its first line that holds an identifier and naming it; every
   * other request is answered as {@link Project0EndToEndIT}'s fake answers it.
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
    if (!request.system().contains("Task: check one lead.") || request.messages().size() != 1) {
      return List.of();
    }
    String text = request.messages().getFirst().text();
    ASKED.add(text);
    boolean confirmed = QUESTIONS.values().stream().anyMatch(text::contains);
    JsonNode message = io.lantern.harness.Toml.read(text).root();
    for (JsonNode piece : message.path("evidence")) {
      if (!piece.path("code").isString()) {
        continue;
      }
      List<String[]> lines = new ArrayList<>();
      for (String line : piece.get("code").asString().split("\n")) {
        Matcher numbered = CODE_LINE.matcher(line);
        if (numbered.matches()) {
          lines.add(new String[] {numbered.group(1), numbered.group(2)});
        }
      }
      String name = null;
      String quote = null;
      for (String[] line : lines) {
        Matcher identifier = IDENTIFIER.matcher(line[1]);
        while (name == null && identifier.find()) {
          String lower = identifier.group().toLowerCase(Locale.ROOT);
          if (!NOT_NAMES.contains(lower) && !javax.lang.model.SourceVersion.isKeyword(lower)) {
            name = identifier.group();
            quote = line[1].strip();
          }
        }
        if (name != null) {
          break;
        }
      }
      if (name == null) {
        continue;
      }
      int from = Integer.parseInt(lines.getFirst()[0]);
      int to = Math.min(Integer.parseInt(lines.getLast()[0]), from + 39);
      String toml =
          "verdict = \""
              + (confirmed ? "confirmed" : "unclear")
              + "\"\n"
              + (confirmed
                  ? "title = \"Confirmed by the scripted model here\"\n"
                      + "finding = \"The code shown does what the lead says, on a path a caller can"
                      + " take, and nothing on that path stops it.\"\n"
                      + "consequence = \"A caller or a failure can act on it, and the system then"
                      + " does what the lead warns of.\"\n"
                      + "impact = \"high\"\nconfidence = \"medium\"\n"
                  : "reason = \"The scripted model judges only the kinds it confirms.\"\n")
              + "\n[[evidence]]\nlines = "
              + io.lantern.harness.Toml.string(
                  piece.path("where").asString() + ":" + from + "-" + to)
              + "\nquote = "
              + io.lantern.harness.Toml.string(quote)
              + "\nshows = ["
              + io.lantern.harness.Toml.string(name)
              + "]\n";
      return List.of(
          new LlmToolCall("scripted-answer", "answer", JSON.createObjectNode().put("toml", toml)));
    }
    return List.of();
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
        held.stream().filter(f -> f.predicate().equals(Types.LEAD_VERDICT)).toList();
    List<Fact> risks =
        held.stream().filter(f -> f.predicate().equals(Types.INVESTIGATED_RISK)).toList();
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
    List<Fact> wordings =
        held.stream().filter(f -> f.predicate().equals(Types.FINDING_WORDING)).toList();
    wordings.forEach(
        w ->
            out.append("worded ")
                .append(w.subject())
                .append(' ')
                .append(w.attributes().path("lead").asString())
                .append('\n'));
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
    // The header-trust lead of the file service and every before-commit lead are risks of their
    // own, which the LLM badge marks: no fixed check finds them, so none is a finding's wording.
    assertThat(risks)
        .as("the file service's header-trust risk")
        .anyMatch(
            r ->
                r.attributes().path("lead").asString().startsWith("lead:identity-from-request/")
                    && r.attributes()
                        .path("lead")
                        .asString()
                        .toLowerCase(Locale.ROOT)
                        .contains("file"));
    assertThat(wordings)
        .as("wordings of the confirmed header-trust and before-commit leads")
        .noneMatch(
            w ->
                CONFIRMED.stream()
                    .anyMatch(
                        k -> w.attributes().path("lead").asString().startsWith("lead:" + k + "/")));

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
    long worded = wordings.size();
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
