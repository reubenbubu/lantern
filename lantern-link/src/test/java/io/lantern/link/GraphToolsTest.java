package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The tools that let the model look further than its prompt (ADR 0081). */
class GraphToolsTest {

  private static final String COMMIT = "c".repeat(40);

  private static final String CONFIG =
      """
      spring:
        application:
          name: orders
        datasource:
          url: jdbc:postgresql://app:s3cret@db:5432/orders
          password: hunter2
      clients:
        tax:
          url: ${TAX_URL}
          api-key: ${TAX_KEY}
      """;

  private final List<Fact> facts = new ArrayList<>();

  private Fact fact(String subject, String predicate, FactObject object, String where) {
    Fact fact =
        Fact.create(
            EntityId.parse(subject),
            predicate,
            object,
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "test", "1"),
            Evidence.of("a", where, null, null),
            1.0,
            Validity.from(1));
    facts.add(fact);
    return fact;
  }

  private GraphTools tools() {
    fact(
        "service:g:orders",
        "application-name",
        FactObject.string("orders"),
        "src/main/resources/application.yml");
    fact(
        "method:o.TaxClient#quote()",
        "http-call",
        FactObject.string("GET ${clients.tax.url}/v2/quotes"),
        "src/main/java/o/TaxClient.java");
    fact(
        "module:g:orders",
        "contains",
        FactObject.entity(EntityId.parse("type:o.TaxClient")),
        "src/main/java/o/TaxClient.java");
    return new GraphTools.Graph(
            facts,
            (repo, path) ->
                path.equals("src/main/resources/application.yml")
                    ? Optional.of(CONFIG.getBytes(StandardCharsets.UTF_8))
                    : Optional.empty())
        .tools();
  }

  private static LlmToolCall call(String name, String input) {
    return new LlmToolCall("id-" + name, name, FactJson.parse(input));
  }

  @Test
  void offersThreeToolsWithObjectSchemas() {
    assertThat(tools().tools())
        .extracting(LlmTool::name)
        .containsExactly("facts_about", "search_facts", "read_file", "find_in_code");
    assertThat(new GraphTools.Graph(List.of(), GraphTools.RepoFiles.NONE).tools().tools())
        .extracting(LlmTool::name)
        .containsExactly("facts_about", "search_facts");
  }

  @Test
  void theLinesReadFileShowedAndTheExcerptsOfTheRequestCountAsShown() {
    // A cited range holds only if the conversation was shown every line of it (#715).
    GraphTools tools = tools();
    String file = "src/main/resources/application.yml";

    tools.run(
        call("read_file", "{\"repo\": \"a\", \"path\": \"" + file + "\", \"from\": 2, \"to\": 4}"));
    tools.supplied(
        "Code it points at:\n<code repo=\"a\" path=\"src/main/java/o/TaxClient.java\""
            + " lines=\"10-12\">\n10  class TaxClient {\n</code>");

    assertThat(tools.showed("a", file, 2, 4)).isTrue();
    assertThat(tools.showed("a", file, 3, 3)).isTrue();
    assertThat(tools.showed("a", file, 1, 2)).isFalse();
    assertThat(tools.showed("a", file, 4, 5)).isFalse();
    assertThat(tools.showed("b", file, 2, 4)).isFalse();
    assertThat(tools.showed("a", "src/main/java/o/TaxClient.java", 10, 12)).isTrue();
    assertThat(tools.showed("a", "src/main/java/o/TaxClient.java", 12, 13)).isFalse();
  }

  @Test
  void searchFindsFactsByTextAndKeepsTheirReferences() {
    GraphTools tools = tools();

    LlmToolResult first = tools.run(call("search_facts", "{\"text\": \"TAXCLIENT\"}"));
    LlmToolResult again =
        tools.run(call("facts_about", "{\"entity\": \"method:o.TaxClient#quote()\"}"));

    assertThat(first.error()).isFalse();
    assertThat(first.content())
        .isEqualTo(
            """
            Facts that hold "TAXCLIENT":
            [F1] method:o.TaxClient#quote() http-call "GET ${clients.tax.url}/v2/quotes" \
            (a:src/main/java/o/TaxClient.java)
            [F2] module:g:orders contains type:o.TaxClient (a:src/main/java/o/TaxClient.java)
            """);
    assertThat(again.content()).contains("[F1] method:o.TaxClient#quote() http-call");
    assertThat(tools.shown()).containsOnlyKeys("F1", "F2");
    assertThat(tools.shown().get("F2").predicate()).isEqualTo("contains");
  }

  @Test
  void factsAboutAnEntityIncludeThoseThatPointToIt() {
    GraphTools tools = tools();

    String about = tools.run(call("facts_about", "{\"entity\": \"type:o.TaxClient\"}")).content();
    String none = tools.run(call("facts_about", "{\"entity\": \"type:o.Nothing\"}")).content();
    String filtered =
        tools
            .run(
                call(
                    "facts_about",
                    "{\"entity\": \"service:g:orders\", \"predicate\": \"endpoint\"}"))
            .content();

    assertThat(about).contains("[F1] module:g:orders contains type:o.TaxClient");
    assertThat(none).startsWith("No facts about type:o.Nothing.");
    assertThat(filtered).contains("with the predicate endpoint");
  }

  @Test
  void smallAnswersAreCutToWholeLinesAndTheOutlineIsOffered() {
    GraphTools small = tools().small(80);

    assertThat(small.tools())
        .extracting(LlmTool::name)
        .containsExactly("facts_about", "search_facts", "read_file", "find_in_code", "outline");
    LlmToolResult read =
        small.run(
            call(
                "read_file",
                "{\"repo\": \"a\", \"path\": \"src/main/resources/application.yml\"}"));

    // A header and as many whole lines as fit under 80 characters, then how to ask for the rest.
    assertThat(read.content())
        .startsWith("a:src/main/resources/application.yml, lines 1 to 1 of 10:\n1: spring:\n")
        .contains("... cut at 80 characters: lines ")
        .contains("left out; read them with from ");
    assertThat(small.run(call("outline", "{\"repo\": \"a\", \"path\": \"x.yml\"}")).error())
        .isTrue();
  }

  @Test
  void aLongLineIsShownInPartAndASearchNamesTheMethodAroundAHit() {
    String longLine = "  String sql = \"select " + "a, ".repeat(400) + "tenant_id from orders\";";
    String java =
        String.join(
            "\n",
            "package o;",
            "class Repo {",
            "  void load() {",
            longLine,
            "    run(\"x-tenant\");",
            "  }",
            "}");
    fact("type:o.Repo", "declared-in", FactObject.string("o"), "src/Repo.java");
    GraphTools.RepoFiles files =
        new GraphTools.RepoFiles() {
          @Override
          public Optional<byte[]> read(String repo, String path) {
            return path.equals("src/Repo.java")
                ? Optional.of(java.getBytes(StandardCharsets.UTF_8))
                : Optional.empty();
          }

          @Override
          public List<GraphTools.Hit> find(String repo, String text, int max) {
            return List.of(new GraphTools.Hit("src/Repo.java", 4));
          }
        };
    GraphTools small = new GraphTools.Graph(facts, files).tools().small(GraphTools.SMALL_CHARS);

    String found =
        small.run(call("find_in_code", "{\"text\": \"tenant_id\", \"repo\": \"a\"}")).content();
    String read =
        small.run(call("read_file", "{\"repo\": \"a\", \"path\": \"src/Repo.java\"}")).content();

    // The hit shows the part of its line around the text, and where it is.
    assertThat(found)
        .contains("a:src/Repo.java:4: [...] ", "tenant_id from orders", "characters; find_in_code")
        .contains("(in load, lines 3-6)");
    assertThat(found.length()).isLessThan(GraphTools.SMALL_CHARS);
    // The read shows every line, the long one cut, and its header the lines it shows.
    assertThat(read)
        .startsWith("a:src/Repo.java, lines 1 to 7 of 7:\n")
        .contains("4:   String sql = \"select a, ", "    run(\"x-tenant\");")
        .doesNotContain("tenant_id from orders");
  }

  @Test
  void readsLinesWithSecretsMasked() {
    LlmToolResult read =
        tools()
            .run(
                call(
                    "read_file",
                    "{\"repo\": \"a\", \"path\": \"/src/main/resources/application.yml\","
                        + " \"from\": 4, \"to\": 10}"));

    assertThat(read.error()).isFalse();
    assertThat(read.content())
        .isEqualTo(
            """
            a:src/main/resources/application.yml, lines 4 to 10 of 10:
            4:   datasource:
            5:     url: jdbc:postgresql://db:5432/orders
            6:     password: ******
            7: clients:
            8:   tax:
            9:     url: ${TAX_URL}
            10:     api-key: ${TAX_KEY}
            """);
  }

  @Test
  void aBadCallAnswersWithAnErrorTheModelCanActOn() {
    GraphTools tools = tools();

    assertThat(tools.run(call("read_file", "{\"repo\": \"b\", \"path\": \"x\"}")).content())
        .isEqualTo("No repository named b. The repositories: a");
    assertThat(tools.run(call("read_file", "{\"repo\": \"a\", \"path\": \"../etc/passwd\"}")))
        .satisfies(r -> assertThat(r.error()).isTrue());
    assertThat(tools.run(call("read_file", "{\"repo\": \"a\", \"path\": \"missing.txt\"}")))
        .satisfies(r -> assertThat(r.content()).isEqualTo("No file missing.txt in a."));
    assertThat(
            tools
                .run(
                    call(
                        "read_file",
                        "{\"repo\": \"a\", \"path\": \"src/main/resources/application.yml\","
                            + " \"from\": 99}"))
                .content())
        .contains("has 10 lines");
    assertThat(tools.run(call("facts_about", "{}")).error()).isTrue();
    assertThat(tools.run(call("search_facts", "{\"text\": \"\"}")).error()).isTrue();
    assertThat(tools.run(call("drop_tables", "{}")).content())
        .isEqualTo("There is no tool named drop_tables.");
  }

  @Test
  void listsAtMostSoManyFactsAndSaysHowManyMore() {
    for (int i = 0; i < GraphTools.MAX_FACTS + 5; i++) {
      fact(
          "type:o.T" + i,
          "stereotype",
          FactObject.string("component"),
          "src/main/java/o/T" + i + ".java");
    }
    GraphTools tools = new GraphTools.Graph(facts, GraphTools.RepoFiles.NONE).tools();

    String found = tools.run(call("search_facts", "{\"text\": \"component\"}")).content();

    assertThat(found.lines().filter(l -> l.startsWith("[F"))).hasSize(GraphTools.MAX_FACTS);
    assertThat(found).endsWith("... and 5 more; narrow the call, for example with a predicate.\n");
  }

  private static String masked(String path, String text) {
    return GraphTools.masked(path, text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void masksSecretsInEveryKindOfFileAndKeepsLineNumbers() {
    assertThat(
            masked(
                "deploy/tls.txt",
                "a\n-----BEGIN RSA PRIVATE KEY-----\nMIIEabc\ndef==\n-----END RSA PRIVATE KEY-----\nb"))
        .isEqualTo(
            "a\n-----BEGIN RSA PRIVATE KEY-----\n******\n******\n-----END RSA PRIVATE KEY-----\nb");
    assertThat(masked(".env", "export API_KEY=abc123\nexport HOST=db"))
        .isEqualTo("export API_KEY=******\nexport HOST=db");
    assertThat(masked("k8s/secret.yml", "data:\n  password: |\n    hunter2\n    more\n  user: app"))
        .isEqualTo("data:\n  password: |\n    ******\n    ******\n  user: app");
    assertThat(
            masked(
                "settings.xml",
                "<server><id>x</id><password>hunter2</password></server>\n"
                    + "<property name=\"db.password\" value=\"s3cret\"/>\n"
                    + "<datasource password=\"pw\" user=\"app\"/>"))
        .isEqualTo(
            "<server><id>x</id><password>******</password></server>\n"
                + "<property name=\"db.password\" value=\"******\"/>\n"
                + "<datasource user=\"app\"/>");
    assertThat(masked("src/main/kotlin/A.kt", "val password = \"hunter2\""))
        .doesNotContain("hunter2");
  }

  @Test
  void keysKeystoresAndBinaryFilesAreNotShown() {
    assertThat(masked("certs/server.key", "x")).isNull();
    assertThat(masked("src/main/resources/keystore.p12", "x")).isNull();
    assertThat(masked("home/.ssh/id_rsa", "x")).isNull();
    assertThat(GraphTools.masked("logo.png", new byte[] {(byte) 0x89, 0, 1})).isNull();
  }

  @Test
  void aFactCutOffForLengthGetsNoReference() {
    for (int i = 0; i < 30; i++) {
      fact(
          "type:o.T" + i,
          "stereotype",
          FactObject.string("component " + "x".repeat(2_000)),
          "src/main/java/o/T" + i + ".java");
    }
    GraphTools tools = new GraphTools.Graph(facts, GraphTools.RepoFiles.NONE).tools();

    String found = tools.run(call("search_facts", "{\"text\": \"component\"}")).content();

    long listed = found.lines().filter(l -> l.startsWith("[F")).count();
    assertThat(listed).isLessThan(30);
    assertThat(tools.shown()).hasSize((int) listed);
    assertThat(tools.shown().keySet()).first().isEqualTo("F1");
  }

  @Test
  void readFileMasksValuesUnderTheSnapshotsKnownJwtSecretKeys() {
    var attributes = FactJson.newObject();
    attributes.putArray("keys").add("jwt.key");
    facts.add(
        Fact.create(
            EntityId.parse("method:o.Jwt#key()"),
            "jwt-secret-key",
            FactObject.string("jwt"),
            attributes,
            new Origin(Origin.Kind.STATIC, "test", "1"),
            Evidence.of("a", "src/main/java/o/Jwt.java", null, null),
            1.0,
            Validity.from(1)));
    String java = "class Jwt { @Value(\"${jwt.key:literal-secret}\") String k; }";
    String properties = "jwt.key=literal-secret\napp.name=x";
    GraphTools tools =
        new GraphTools.Graph(
                facts,
                (repo, path) ->
                    Optional.of(
                        (path.endsWith(".java") ? java : properties)
                            .getBytes(StandardCharsets.UTF_8)))
            .tools();

    String fromJava =
        tools.run(call("read_file", "{\"repo\": \"a\", \"path\": \"src/Jwt.java\"}")).content();
    String fromProperties =
        tools
            .run(call("read_file", "{\"repo\": \"a\", \"path\": \"application.properties\"}"))
            .content();

    assertThat(fromJava).contains("${jwt.key:******}").doesNotContain("literal-secret");
    assertThat(fromProperties).contains("jwt.key=******", "app.name=x");
  }
}
