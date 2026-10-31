package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.identity.IdentityExtractor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * The link-side lookup gives what the extractors resolved for themselves (#556): for every text and
 * view, the same value, unresolved keys and masking as {@link CodeConfig}.
 */
class CodeValuesTest {

  private static final String POM =
      """
      <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>
      <artifactId>%s</artifactId><version>1</version>%s</project>
      """;

  /** Texts as code writes them, each using a different feature of the config. */
  private static final List<String> TEXTS =
      List.of(
          "http://${payments.host}/api",
          "${payments.url}",
          "${orders.exchange}",
          "${missing.key}/x",
          "${missing.key:fallback}/x",
          "${nested.outer}",
          "${list.items[0]}-${list.items[1]}",
          "${spring.datasource.password}",
          "${app.jwt.signing}",
          "${with.credentials}",
          "\\${escaped}",
          "plain text",
          "${env.only}");

  @TempDir Path temp;

  @Test
  void theLookupAgreesWithTheExtractorsInEveryViewOfEveryModule() throws IOException {
    RepoBuild multi =
        repo(
            "multi",
            Map.ofEntries(
                Map.entry(
                    "pom.xml",
                    POM.formatted(
                        "parent",
                        "<packaging>pom</packaging><modules><module>a</module>"
                            + "<module>b</module></modules>")),
                Map.entry("a/pom.xml", POM.formatted("a", "")),
                Map.entry(
                    "a/src/main/resources/application.yml",
                    """
                    payments:
                      host: payments:8080
                      url: http://${payments.host}/v1
                    orders:
                      exchange: orders.events
                    nested:
                      outer: ${nested.inner}-x
                      inner: ${nested.deepest:deep}
                    list:
                      items:
                        - one
                        - two
                    spring:
                      datasource:
                        password: hunter2-secret
                    app:
                      jwt:
                        signing: very-secret-signing-key
                    with:
                      credentials: https://user:pw@example.com/path
                    env:
                      only: ${ENV_ONLY}
                    ---
                    spring:
                      config:
                        activate:
                          on-profile: docker
                    payments:
                      host: payments-svc
                    """),
                Map.entry(
                    "a/src/main/resources/application-local.properties",
                    """
                    orders.exchange=orders.local
                    nested.inner=inner-local
                    list.items[0]=only
                    """),
                Map.entry("b/pom.xml", POM.formatted("b", "")),
                Map.entry(
                    "b/src/main/resources/application.properties", "payments.host=b-payments\n")));
    RepoBuild single =
        repo(
            "single",
            Map.of(
                "pom.xml",
                POM.formatted("single", ""),
                "src/main/resources/bootstrap.yml",
                "orders:\n  exchange: from-bootstrap\n"));
    RepoBuild none = repo("none", Map.of("pom.xml", POM.formatted("none", "")));
    List<RepoBuild> repos = List.of(multi, single, none);
    Set<String> secretKeys = Set.of("app.jwt.signing");

    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, 1, secretKeys));
    facts.add(jwtSecretKey("app.jwt.signing"));
    CodeValues values = CodeValues.of(facts);
    CodeConfig config = new CodeConfig(secretKeys);

    Map<String, List<String>> modules = new TreeMap<>();
    modules.put("multi", List.of("a", "b", "c"));
    modules.put("single", List.of(""));
    modules.put("none", List.of(""));
    int compared = 0;
    for (RepoBuild repo : repos) {
      for (String directory : modules.get(repo.repo().name())) {
        String artifact = directory.isEmpty() ? repo.repo().name() : directory;
        CodeConfig.View expected = config.forModule(repo, directory);
        CodeValues.View actual =
            values.forModule(EntityId.parse("module:g:" + artifact), repo.repo().name());
        Set<String> views = new LinkedHashSet<>();
        expected.views().forEach(views::add);
        actual.views().forEach(views::add);
        for (String view : views) {
          for (String text : TEXTS) {
            CodeConfig.CodeValue want = expected.value(expected.resolve(view, text), text);
            CodeValues.Value got = actual.value(view, text);
            String where = repo.repo().name() + "/" + directory + " " + view + " " + text;
            assertThat(got.value()).as(where).isEqualTo(want.value());
            assertThat(got.unresolved()).as(where).isEqualTo(want.unresolved());
            assertThat(got.masked()).as(where).isEqualTo(want.masked());
            compared++;
          }
        }
      }
    }
    assertThat(compared).isGreaterThan(80);
  }

  @Test
  void aModuleWithoutConfigUsesItsRepositorysOnlyConfigElsePlaceholderDefaults()
      throws IOException {
    RepoBuild single =
        repo(
            "single",
            Map.of(
                "pom.xml",
                POM.formatted("single", ""),
                "src/main/resources/application.properties",
                "orders.exchange=orders\n"));
    CodeValues values = CodeValues.of(new ConfigExtractor().extract(List.of(single), 1));

    assertThat(
            values
                .forModule(EntityId.parse("module:g:other"), "single")
                .value(null, "${orders.exchange}")
                .value())
        .isEqualTo("orders");
    CodeValues.Value without =
        values.forModule(EntityId.parse("module:g:other"), "elsewhere").value(null, "${a:b}-${c}");
    assertThat(without.value()).isEqualTo("b-${c}");
    assertThat(without.unresolved()).containsExactly("c");
  }

  private static Fact jwtSecretKey(String key) {
    ObjectNode attributes = FactJson.newObject();
    attributes.putArray("keys").add(key);
    return Fact.create(
        EntityId.parse("type:g.Jwt"),
        "jwt-secret-key",
        FactObject.string("hmac"),
        attributes,
        new Origin(Origin.Kind.STATIC, IdentityExtractor.NAME, "1"),
        Evidence.of("multi", "a/src/main/java/g/Jwt.java", new Evidence.LineRange(1, 1), null),
        1.0,
        Validity.from(1));
  }

  private RepoBuild repo(String name, Map<String, String> files) throws IOException {
    Path dir = temp.resolve(name);
    for (Map.Entry<String, String> file : files.entrySet()) {
      Path path = dir.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, file.getValue());
    }
    git(dir, "init", "--quiet");
    git(dir, "add", "--all");
    git(
        dir,
        "-c",
        "user.name=Lantern Test",
        "-c",
        "user.email=test@lantern.invalid",
        "commit",
        "--quiet",
        "--message",
        "Fixture");
    String head = git(dir, "rev-parse", "HEAD");
    return new RepoBuild(new Repo(name, dir.toRealPath(), RepoSettings.DEFAULT), head, null);
  }

  private static String git(Path dir, String... args) throws IOException {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      if (p.waitFor() != 0) {
        throw new IOException("git " + String.join(" ", args) + ": " + out);
      }
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }
}
