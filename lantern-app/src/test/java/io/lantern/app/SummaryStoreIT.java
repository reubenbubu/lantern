package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.ModelSettings;
import io.lantern.summarize.JdbcSummaryStore;
import io.lantern.summarize.Summarizer;
import io.lantern.summarize.Summary;
import io.lantern.summarize.SummaryLevel;
import io.lantern.summarize.SummaryNode;
import io.lantern.summarize.SummaryTree;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Summaries are kept in the database and reused by later runs. The database is shared with other
 * tests, so each test uses a repository name of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SummaryStoreIT {

  @Autowired JdbcSummaryStore store;
  @Autowired JdbcClient jdbc;

  private final FakeLlmProvider fake =
      new FakeLlmProvider()
          .answering(
              request ->
                  request.messages().getFirst().text().contains("[S1]")
                      ? "It holds one part. [S1]"
                      : "It is a class. [F1, F2]");

  private Summarizer summarizer() {
    return new Summarizer(
        new LlmClient(
            ModelSettings.single(fake.model("m")),
            List.of(fake),
            BudgetGuard.unlimited("test"),
            new InMemoryLlmCallLog(),
            Clock.systemUTC()),
        store);
  }

  private static Fact fact(String repo, String subject, String predicate, FactObject object) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        new Evidence(repo, "b".repeat(40), "pom.xml", null, null),
        1.0,
        Validity.from(1));
  }

  @Test
  void laterRunsReuseStoredSummaries() {
    String repo = "repo-" + UUID.randomUUID();
    String module = "module:com.example:" + repo;
    String pkg = "package:com.example:" + repo + ":com.example";
    String type = "type:com.example.A" + repo.replace("-", "");
    List<SummaryNode> tree =
        SummaryTree.fromFacts(
            List.of(
                fact(repo, module, "CONTAINS", FactObject.entity(EntityId.parse(pkg))),
                fact(repo, pkg, "CONTAINS", FactObject.entity(EntityId.parse(type))),
                fact(repo, type, "kind", FactObject.string("class")),
                fact(
                    repo,
                    type,
                    "CONTAINS",
                    FactObject.entity(EntityId.parse("method:" + type.substring(5) + "#run()")))));

    Summarizer.Result first = summarizer().summarize(tree);
    Summarizer.Result second = summarizer().summarize(tree);

    assertThat(first.generated()).isEqualTo(4);
    assertThat(second.generated()).isZero();
    assertThat(second.reused()).isEqualTo(4);
    assertThat(fake.sent()).hasSize(4);
    assertThat(second.summaries()).isEqualTo(first.summaries());

    Summary typeSummary = first.summaries().get(EntityId.parse(type));
    assertThat(store.find(typeSummary.key())).contains(typeSummary);
    Map<String, Object> row =
        jdbc.sql(
                "SELECT level, purpose, confidence, jsonb_array_length(cited_facts) AS cited"
                    + " FROM summaries"
                    + " WHERE cache_key = :key")
            .param("key", typeSummary.key())
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("level", "TYPE")
        .containsEntry("cited", 2)
        .containsEntry("purpose", "INFERRED")
        .containsEntry("confidence", 0.35);
  }

  @Test
  void savingTheSameKeyTwiceKeepsTheFirst() {
    String repo = "repo-" + UUID.randomUUID();
    Summary summary =
        new Summary(
            "d".repeat(56) + repo.substring(5, 13).replace("-", "0"),
            new EntityId("repo", repo),
            SummaryLevel.REPO,
            "First. [summary:module:g:a]",
            Summary.Purpose.EVIDENCED,
            0.25,
            List.of(),
            List.of("e".repeat(64)),
            List.of(),
            List.of("e".repeat(64)),
            "1",
            "fake",
            "m");
    Summary other =
        new Summary(
            summary.key(),
            summary.entity(),
            summary.level(),
            "Second. [summary:module:g:a]",
            Summary.Purpose.INFERRED,
            0.35,
            summary.citedFacts(),
            summary.citedSummaries(),
            summary.inputFacts(),
            summary.inputSummaries(),
            "1",
            "fake",
            "m");

    store.save(summary);
    store.save(other);

    assertThat(store.find(summary.key())).contains(summary);
  }
}
