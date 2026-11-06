package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.harness.Harness;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Investigate's shell, search and outline tools (#893), over a stand-in for the container that runs
 * each command with {@code sh -c} in the written folder on this machine, as the container does in
 * its {@code /work}: answers cut to whole lines with the footer naming the call for the rest,
 * secrets masked, the declaration around a hit, and the lines an answer showed.
 */
class ShellToolsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String CONTROLLER =
      """
      package o;

      @RestController
      public class OrderController {
        @GetMapping("/orders/{id}")
        public Order get(@PathVariable String id) {
          return orders.find(id);
        }

        @PostMapping("/orders")
        public Order create(@RequestBody Order order) {
          return orders.save(order);
        }
      }
      """;

  private static final String CONFIG =
      """
      spring:
        datasource:
          password: hunter2
      partner:
        api-key: ${PARTNER_KEY:changeme-not-a-real-key}
      """;

  private final Map<String, Map<String, String>> files = new LinkedHashMap<>();
  private final List<String> masked = new ArrayList<>();
  private ShellWorkspace workspace;
  private ShellTools tools;
  private boolean closed;

  @BeforeEach
  void write() {
    files.put(
        "orders",
        Map.of(
            "src/main/java/o/OrderController.java",
            CONTROLLER,
            "src/main/resources/application.yml",
            CONFIG,
            "long.txt",
            IntStream.rangeClosed(1, 40)
                .mapToObj(i -> "line " + i + " " + "x".repeat(40))
                .collect(Collectors.joining("\n", "", "\n")),
            "src/main/java/o/PartnerClient.java",
            "package o;\n\npublic class PartnerClient {\n  @Value(\"${partner.api-key:hunter3}\")\n"
                + "  void setKey(String key) {}\n}\n",
            "wide.txt",
            "w".repeat(300) + "\n"));
    files.put("gateway", Map.of("routes.yml", "- Path=/orders/**\n- Path=/orders/{id}\n"));
    List<Fact> facts = new ArrayList<>();
    for (String repo : files.keySet()) {
      facts.add(
          Fact.create(
              EntityId.parse("module:g:" + repo),
              "application-name",
              FactObject.string(repo),
              FactJson.newObject(),
              new Origin(Origin.Kind.STATIC, "test", "1"),
              Evidence.of(repo, "pom.xml", null, null),
              1.0,
              Validity.from(1)));
    }
    GraphTools.RepoFiles repoFiles =
        new GraphTools.RepoFiles() {
          @Override
          public Optional<byte[]> read(String repo, String path) {
            return Optional.ofNullable(files.getOrDefault(repo, Map.of()).get(path))
                .map(t -> t.getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public List<String> paths(String repo) {
            return List.copyOf(files.getOrDefault(repo, Map.of()).keySet());
          }

          @Override
          public void masked(String value) {
            masked.add(value);
          }
        };
    GraphTools.Graph graph = new GraphTools.Graph(facts, repoFiles);
    workspace = ShellWorkspace.open(graph, (root, repos) -> new HostShell(root));
    tools = new ShellTools(workspace, graph);
  }

  @AfterEach
  void close() {
    workspace.close();
    assertThat(closed).isTrue();
  }

  /** Runs commands on this machine in the written folder, as the container does in /work. */
  private final class HostShell implements ShellWorkspace.Container {
    private final Path root;

    HostShell(Path root) {
      this.root = root;
    }

    @Override
    public ShellWorkspace.Run run(String command) {
      try {
        Process p = new ProcessBuilder("sh", "-c", command).directory(root.toFile()).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(20, TimeUnit.SECONDS);
        return new ShellWorkspace.Run(out, err, p.exitValue(), false, false);
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private String call(String tool, String input) {
    LlmToolResult result = tools.run(new LlmToolCall("c1", tool, JSON.readTree(input)));
    return result.content();
  }

  @Test
  void aLongAnswerIsCutToWholeLinesWithTheCommandForTheRest() {
    String answer = call("shell", "{\"command\":\"cat orders/long.txt\"}");
    List<String> lines = answer.lines().toList();
    assertThat(lines.getFirst()).isEqualTo("line 1 " + "x".repeat(40));
    assertThat(answer.length()).isLessThan(ShellTools.CHARS + 120);
    int shown = lines.size() - 1;
    assertThat(lines.getLast())
        .isEqualTo(
            "[showing the first "
                + shown
                + " of 40 lines; the rest: cat orders/long.txt | sed -n '"
                + (shown + 1)
                + ",40p']");
  }

  @Test
  void aLineOverTheWidthIsCutAndTheFooterSaysSo() {
    String answer = call("shell", "{\"command\":\"cat orders/wide.txt\"}");
    assertThat(answer.lines().toList().getFirst()).hasSize(ShellTools.WIDTH).endsWith("…");
    assertThat(answer).endsWith("[long lines cut at 160 characters]");
    String many = call("shell", "{\"command\":\"cat orders/wide.txt orders/long.txt\"}");
    assertThat(many).endsWith(",41p' | cut -c1-160]");
  }

  @Test
  void anExitStatusAndAnEmptyOutputAreSaid() {
    assertThat(call("shell", "{\"command\":\"grep -c nothing orders/long.txt\"}"))
        .isEqualTo("0\n[exit status 1]");
    assertThat(call("shell", "{\"command\":\"true\"}")).isEqualTo("(no output)");
    assertThat(call("shell", "{\"command\":\"ls nothing-here\"}")).contains("[exit status");
  }

  @Test
  void theErrorOutputIsShownWhenThereIsNoOutput() {
    assertThat(call("shell", "{\"command\":\"echo denied >&2; exit 2\"}"))
        .isEqualTo("denied\n[exit status 2]");
  }

  @Test
  void everyFolderIsReadableByTheContainersUserWhoeverThatIs() throws IOException {
    try (java.util.stream.Stream<Path> all = java.nio.file.Files.walk(workspace.root())) {
      for (Path path : all.toList()) {
        String others = java.nio.file.Files.isDirectory(path) ? "r-x" : "r--";
        assertThat(
                java.nio.file.attribute.PosixFilePermissions.toString(
                        java.nio.file.Files.getPosixFilePermissions(path))
                    .substring(6))
            .as(path.toString())
            .isEqualTo(others);
      }
    }
  }

  @Test
  void cdPipesLoopsAndSubstitutionRun() {
    assertThat(call("shell", "{\"command\":\"cd gateway && grep -n orders routes.yml | head -1\"}"))
        .isEqualTo("1:- Path=/orders/**");
    assertThat(call("shell", "{\"command\":\"for r in $(ls); do echo $r; done\"}"))
        .isEqualTo("gateway\norders");
  }

  @Test
  void secretsAreMaskedInEveryTool() {
    // A masked value goes to the Secrets check only once an answer shows its file.
    assertThat(masked).isEmpty();
    call("shell", "{\"command\":\"cat orders/long.txt | head -2\"}");
    assertThat(masked).isEmpty();
    assertThat(call("shell", "{\"command\":\"cat orders/src/main/resources/application.yml\"}"))
        .doesNotContain("hunter2");
    assertThat(call("find_in_code", "{\"text\":\"password\"}")).doesNotContain("hunter2");
    assertThat(call("find_in_code", "{\"text\":\"hunter2\"}")).startsWith("No line");
    assertThat(masked).contains("hunter2");
    assertThat(
            call(
                "outline", "{\"repo\":\"orders\",\"path\":\"src/main/java/o/PartnerClient.java\"}"))
        .contains("setKey")
        .doesNotContain("hunter3");
  }

  @Test
  void findGivesTheDeclarationAroundEachHitAndCountsPerRepository() {
    String answer = call("find_in_code", "{\"text\":\"orders\"}");
    assertThat(answer)
        .contains(
            "orders:src/main/java/o/OrderController.java:5:"
                + " @GetMapping(\"/orders/{id}\") (in get, lines 5-8)")
        .contains("gateway:routes.yml:1: - Path=/orders/**");
    assertThat(call("find_in_code", "{\"text\":\"orders\",\"repo\":\"nope\"}"))
        .startsWith("No repository named nope.");
  }

  @Test
  void findCutsItsHitsWithANarrowerCall() {
    Map<String, String> many = new LinkedHashMap<>();
    for (int i = 1; i <= 40; i++) {
      many.put("f" + i + ".txt", "needle in file " + i + " " + "y".repeat(60) + "\n");
    }
    files.put("big", many);
    close();
    closed = false;
    write();
    files.get("orders"); // the files above are written again with the new repository
    String answer = call("find_in_code", "{\"text\":\"needle\"}");
    assertThat(answer).contains("[showing ").contains(" of 40 hits; per repository: big 40;");
  }

  @Test
  void theOutlineListsDeclarationsWithTheirLines() {
    String answer =
        call("outline", "{\"repo\":\"orders\",\"path\":\"src/main/java/o/OrderController.java\"}");
    assertThat(answer)
        .startsWith("orders:src/main/java/o/OrderController.java, 14 lines")
        .contains("3-14: @RestController public class OrderController")
        .contains("5-8: @GetMapping(\"/orders/{id}\") public Order get(@PathVariable String id)")
        .contains("10-13: @PostMapping(\"/orders\") public Order create(@RequestBody Order order)");
    assertThat(call("outline", "{\"repo\":\"orders\",\"path\":\"long.txt\"}"))
        .startsWith("outline reads Java files");
  }

  @Test
  void theLinesAnAnswerShowedAreTheOnesItMayCite() {
    String file = "src/main/java/o/OrderController.java";
    assertThat(tools.showed("orders", file, 5, 8)).isFalse();
    call("shell", "{\"command\":\"nl -ba orders/" + file + " | sed -n '5,8p'\"}");
    assertThat(tools.showed("orders", file, 5, 8)).isTrue();
    assertThat(tools.showed("orders", file, 10, 13)).isFalse();
    // A grep with numbers, from inside the repository.
    call("shell", "{\"command\":\"cd orders && grep -n save " + file + "\"}");
    assertThat(tools.showed("orders", file, 12, 12)).isTrue();
    // A line shown by its text alone, the blank and brace lines of a range counting with it.
    call("shell", "{\"command\":\"sed -n '10,11p' orders/" + file + "\"}");
    assertThat(tools.showed("orders", file, 9, 12)).isTrue();
    assertThat(tools.filesRead()).extracting(f -> f.path()).contains(file);
    // From a folder inside the repository, numbered lines of one of several files named.
    String config = "src/main/resources/application.yml";
    call(
        "shell",
        "{\"command\":\"cd orders/src/main && nl -ba resources/application.yml | sed -n '4,5p';"
            + " nl -ba java/o/OrderController.java | sed -n '1,1p'\"}");
    assertThat(tools.showed("orders", config, 4, 5)).isTrue();
    assertThat(tools.showed("orders", config, 1, 3)).isFalse();
    // Lines numbered afresh by cat -n, after going back up with ..
    call(
        "shell",
        "{\"command\":\"cd gateway && cd ../orders && sed -n '1,3p' " + config + " | cat -n\"}");
    assertThat(tools.showed("orders", config, 1, 3)).isTrue();
    assertThat(ShellTools.folders("cd orders/src; cd ../..; cd /work/gateway; cd /"))
        .containsExactly("", "orders/src", "gateway");
    assertThat(Set.of(tools.leadTools().getFirst().name())).containsExactly("shell");
  }

  @Test
  void theRestOfACompoundCommandIsAskedForAsAWhole() {
    assertThat(ShellTools.whole("cat a | grep b")).isEqualTo("cat a | grep b");
    assertThat(ShellTools.whole("ls a; ls b")).isEqualTo("sh -c 'ls a; ls b'");
    assertThat(ShellTools.whole("grep x f || true")).startsWith("sh -c ");
    assertThat(ShellTools.whole("cat f # all")).startsWith("sh -c ");
    assertThat(ShellTools.whole("grep 'a;b' f")).isEqualTo("grep 'a;b' f");
    String answer = call("shell", "{\"command\":\"echo top; cat orders/long.txt\"}");
    assertThat(answer.lines().toList().getLast())
        .contains("the rest: sh -c 'echo top; cat orders/long.txt' | sed -n '");
  }

  @Test
  void aLineTheCommandCutIsStillCreditedAndScreened() {
    String file = "src/main/java/o/OrderController.java";
    call("shell", "{\"command\":\"cd orders && grep -n PathVariable " + file + " | cut -c1-40\"}");
    assertThat(tools.showed("orders", file, 6, 6)).isTrue();
    // Shown cut and credited to no file, its file is still screened.
    call("shell", "{\"command\":\"grep -rh RequestBody orders | cut -c1-40\"}");
    assertThat(tools.showed("orders", file, 11, 11)).isFalse();
    assertThat(tools.textsShown()).extracting(Harness.Shown::path).contains(file);
  }

  @Test
  void anOutlinedFileIsScreened() {
    call("outline", "{\"repo\":\"orders\",\"path\":\"src/main/java/o/PartnerClient.java\"}");
    assertThat(tools.textsShown())
        .extracting(Harness.Shown::path)
        .contains("src/main/java/o/PartnerClient.java");
  }

  @Test
  void aLineNoFileIsCreditedWithIsScreenedButNotCitable() {
    String file = "src/main/java/o/OrderController.java";
    assertThat(call("shell", "{\"command\":\"grep -rh orders.save orders\"}"))
        .contains("return orders.save(order);");
    assertThat(tools.showed("orders", file, 12, 12)).isFalse();
    assertThat(tools.textsShown()).extracting(Harness.Shown::path).contains(file);
    // A numbered line whose text the command changed is not credited to that number.
    call("shell", "{\"command\":\"cd orders && grep -n save " + file + " | sed 's/save/keep/'\"}");
    assertThat(tools.showed("orders", file, 12, 12)).isFalse();
  }

  @Test
  void linesAreCreditedHoweverTheCommandGotToTheirFile() {
    String file = "src/main/java/o/OrderController.java";
    String config = "src/main/resources/application.yml";
    // A file named before the command went back up, its path read from where it was named.
    call(
        "shell",
        "{\"command\":\"cd orders/src/main/resources; cat -n application.yml | sed -n '4,5p';"
            + " cd ../../..; ls\"}");
    assertThat(tools.showed("orders", config, 4, 5)).isTrue();
    assertThat(tools.showed("orders", config, 1, 3)).isFalse();
    // grep's context lines.
    call("shell", "{\"command\":\"cd orders && grep -n -A2 GetMapping " + file + "\"}");
    assertThat(tools.showed("orders", file, 5, 7)).isTrue();
    assertThat(tools.showed("orders", file, 10, 11)).isFalse();
    // A numbering of the command's own before each line.
    call("shell", "{\"command\":\"sed -n '30,31p' orders/long.txt | cat -n | sed 's/^/+29 /'\"}");
    assertThat(tools.showed("orders", "long.txt", 30, 31)).isTrue();
    assertThat(tools.showed("orders", "long.txt", 29, 29)).isFalse();
  }
}
