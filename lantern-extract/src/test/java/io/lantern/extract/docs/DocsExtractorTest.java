package io.lantern.extract.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Golden-fact tests of the docs extractor on fixture repositories. */
class DocsExtractorTest {

  private static final long SNAPSHOT = 5;

  @TempDir Path temp;

  private List<Fact> extract(List<Repo> repos) throws IOException {
    List<RepoBuild> inputs =
        repos.stream().map(r -> new RepoBuild(r, Fixtures.head(r), null)).toList();
    List<Fact> facts = new DocsExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(facts, repos, SNAPSHOT, DocsExtractor.NAME, DocsExtractor.VERSION);
    assertThat(facts).allMatch(f -> f.origin().kind() == Origin.Kind.README);
    assertThat(facts).allMatch(f -> f.evidence().contentHash() != null);
    return facts;
  }

  /**
   * READMEs in common shapes, and decision records in Nygard's format (adr-tools), MADR 3 with
   * front matter, MADR 2 with a status list, and a {@code **Status:**} line; an index, a template
   * and a page that is not a decision record are left out.
   */
  @Test
  void readmesAndDecisionRecords() throws IOException {
    Repo repo = Fixtures.repo("docs", temp.resolve("orders"), "orders");

    Fixtures.assertGolden("docs/docs.txt", extract(List.of(repo)));
  }

  @Test
  void readsTheCommitNotTheWorkingTree() throws IOException {
    Repo repo =
        Fixtures.repo(temp.resolve("orders"), "orders", Map.of("README.md", "Committed claim.\n"));
    java.nio.file.Files.writeString(repo.path().resolve("README.md"), "Uncommitted claim.\n");

    List<Fact> facts =
        new DocsExtractor().extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), 1);

    assertThat(Fixtures.render(facts))
        .isEqualTo("readme:orders:README.md claim Committed claim. @orders:README.md#1-1 0.5\n");
  }

  @Test
  void repositoryWithoutDocsHasNoFacts() throws IOException {
    Repo repo = Fixtures.repo(temp.resolve("orders"), "orders", Map.of("pom.xml", "<project/>\n"));

    assertThat(extract(List.of(repo))).isEmpty();
  }

  @Test
  void masksSecretsInClaims() {
    assertThat(DocsExtractor.maskSecrets("Run with `DB_PASSWORD=s3cr3t` and password: \"a b\"."))
        .isEqualTo("Run with `DB_PASSWORD=******` and password: ******.");
    assertThat(DocsExtractor.maskSecrets("Set api-key: abc123, spring.datasource.password=x"))
        .isEqualTo("Set api-key: ******, spring.datasource.password=******");
    assertThat(DocsExtractor.maskSecrets("password: ${DB_PASSWORD}; routing key: order.created"))
        .isEqualTo("password: ${DB_PASSWORD}; routing key: order.created");
    assertThat(DocsExtractor.maskSecrets("Status: accepted. Port=8080"))
        .isEqualTo("Status: accepted. Port=8080");
  }

  @Test
  void choosesFiles() {
    assertThat(DocsExtractor.isDecision("docs/adr/0001-use-kafka.md")).isTrue();
    assertThat(DocsExtractor.isDecision("doc/architecture/decisions/0001-x.md")).isTrue();
    assertThat(DocsExtractor.isDecision("docs/ADR/0001-x.markdown")).isTrue();
    assertThat(DocsExtractor.isDecision("ADR-012.md")).isTrue();
    assertThat(DocsExtractor.isDecision("docs/adr/README.md")).isFalse();
    assertThat(DocsExtractor.isDecision("docs/adr/index.md")).isFalse();
    assertThat(DocsExtractor.isDecision("docs/adr/adr-template.md")).isFalse();
    assertThat(DocsExtractor.isDecision("docs/adr/0001-x.txt")).isFalse();
    assertThat(DocsExtractor.isDecision("docs/0001-x.md")).isFalse();
    assertThat(DocsExtractor.isReadme("README.md")).isTrue();
    assertThat(DocsExtractor.isReadme("orders/readme.markdown")).isTrue();
    assertThat(DocsExtractor.isReadme("README")).isTrue();
    assertThat(DocsExtractor.isReadme("README.txt")).isFalse();
    assertThat(DocsExtractor.isReadme("docs/decisions/README.md")).isFalse();
  }
}
