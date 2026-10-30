package io.lantern.extract.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code frontend} fixture: an Angular app in {@code web/} whose default build replaces its
 * environment file, served by nginx with proxies and with a development proxy; a React app in
 * {@code shop-ui/} built with Vite, calling through axios, fetch and a WebSocket with dotenv
 * values; and a package that is no frontend. Needs no Docker.
 */
class FrontendExtractorTest {

  private static final long SNAPSHOT = 4;

  @TempDir Path temp;

  @Test
  void fixture() throws IOException {
    Repo repo = Fixtures.repo("frontend", temp.resolve("shop"), "shop");
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));

    List<Fact> facts = new FrontendExtractor().extract(inputs, SNAPSHOT);

    Fixtures.checkEnvelope(
        facts, List.of(repo), SNAPSHOT, FrontendExtractor.NAME, FrontendExtractor.VERSION);
    assertThat(new FrontendExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    Fixtures.assertGolden("frontend/frontend.txt", facts);
    // Tests, comments and a map's get are not calls; a package without a framework is no app.
    assertThat(Fixtures.render(facts))
        .doesNotContain("test-only", "/nowhere", "frontend:shop/tools", "{key}");
  }

  @Test
  void aFrontendWithoutABuildAtTheTopOfItsRepositoryIsNamedByItsManifest() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("CRM-Frontend"),
            "CRM-Frontend",
            Map.of(
                "package.json",
                "{\"name\": \"crm-frontend\", \"dependencies\": {\"vue\": \"^3.4.21\"}}\n",
                "src/api.js",
                "export const me = () => fetch('https://api.crm.example.com/users/me')\n"));

    List<Fact> facts =
        new FrontendExtractor()
            .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT);

    assertThat(Fixtures.render(facts))
        .isEqualTo(
            """
            frontend:CRM-Frontend frontend-app vue {"directory":"","framework":"Vue","name":"crm-frontend","package":"crm-frontend","technology":"Vue 3.4","version":"3.4"} @CRM-Frontend:package.json#1-1 1.0
            frontend:CRM-Frontend frontend-call GET https://api.crm.example.com/users/me {"httpMethod":"GET","protocol":"HTTP","url":"https://api.crm.example.com/users/me","written":"'https://api.crm.example.com/users/me'"} @CRM-Frontend:src/api.js#1-1 0.9
            """);
  }

  @Test
  void brokenFilesAreSkippedWithoutFailing() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("broken"),
            "broken",
            Map.of(
                "bad/package.json",
                "{\"name\": \"bad\", \"dependencies\": {\"react\": ",
                "app/package.json",
                "{\"dependencies\": {\"react\": \"18\"}}\n",
                "app/angular.json",
                "{ not json",
                "app/src/a.ts",
                "fetch(`/api/${x(`\nconst ws = new WebSocket('ws://h:1'\nfetch(((("));

    List<Fact> facts =
        new FrontendExtractor()
            .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT);

    assertThat(Fixtures.render(facts))
        // An unterminated template literal runs to the end of the file, so nothing after it counts.
        .contains("frontend:broken/app frontend-app react")
        .doesNotContain("frontend:broken/bad");
  }

  @Test
  void expressionsAreReadWithTheirStringsAndBrackets() {
    String code = "get(`${a}/x/${f(b, c)}`, { p: 1 }), rest";
    assertThat(FrontendExtractor.argument(code, 4)).isEqualTo("`${a}/x/${f(b, c)}`");
    assertThat(FrontendExtractor.concatenated("a + 'b+c' + `${d + e}`"))
        .containsExactly("a", "'b+c'", "`${d + e}`");
    assertThat(FrontendExtractor.uncommented("x('http://a'); // y('b')\n/* z */ w"))
        .isEqualTo("x('http://a');" + " ".repeat(10) + "\n" + " ".repeat(8) + "w");
    assertThat(FrontendExtractor.url("api/x?q=1#top")).isEqualTo("/api/x");
    assertThat(FrontendExtractor.url("http://h:1//a//b")).isEqualTo("http://h:1/a/b");
    assertThat(FrontendExtractor.version("^19.2.0")).isEqualTo("19.2");
    assertThat(FrontendExtractor.version("7.x")).isEqualTo("7");
    assertThat(FrontendExtractor.unscoped("@acme/web")).isEqualTo("web");
  }
}
