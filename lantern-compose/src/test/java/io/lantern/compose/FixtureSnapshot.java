package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.link.CrossRepoLinks;
import io.lantern.link.DeployableUnits;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelSettings;
import io.lantern.summarize.GitSourceFiles;
import io.lantern.summarize.InMemorySummaryStore;
import io.lantern.summarize.Summarizer;
import io.lantern.summarize.Summary;
import io.lantern.summarize.SummaryTree;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A snapshot of the fixture projects, extracted by the real Maven and Java extractors (without
 * builds, so modules are read from their POMs as written and parsed), summarized by a fake model,
 * with the linker's deployable units and links. Commits are made with fixed authors and dates, so
 * their hashes, and with them every fact ID, are the same on every run.
 *
 * @param snapshot the snapshot of the fixture repositories
 * @param facts the extracted facts
 * @param summaries the summaries, by entity
 * @param requests what the summarizer sent the model, in order
 */
record FixtureSnapshot(
    Snapshot snapshot,
    List<Fact> facts,
    Map<EntityId, Summary> summaries,
    List<LlmRequest> requests) {

  static final long NUMBER = 1;

  /** Repository name to fixture directory. */
  static final Map<String, String> REPOS =
      Map.of(
          "shop", "multi-module",
          "orders", "lombok",
          "greeting-lib", "lib-and-service/lib",
          "greeting-service", "lib-and-service/service");

  static FixtureSnapshot create(Path temp) throws IOException {
    String location = System.getProperty("lantern.test.fixtures");
    if (location == null) {
      throw new IllegalStateException(
          "Set lantern.test.fixtures to lantern-extract/src/test/resources/fixtures (Maven does)");
    }
    Path fixtures = Path.of(location);
    List<RepoBuild> repos = new ArrayList<>();
    Map<String, String> commits = new TreeMap<>();
    for (Map.Entry<String, String> entry : new TreeMap<>(REPOS).entrySet()) {
      Path dir = temp.resolve(entry.getKey());
      copy(fixtures.resolve(entry.getValue()), dir);
      String commit = commit(dir);
      repos.add(
          new RepoBuild(
              new Repo(entry.getKey(), dir.toRealPath(), RepoSettings.DEFAULT), commit, null));
      commits.put(entry.getKey(), commit);
    }
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, NUMBER));
    facts.addAll(new JavaExtractor().extract(repos, NUMBER));
    Map<String, Path> directories = new TreeMap<>();
    repos.forEach(repo -> directories.put(repo.repo().name(), repo.repo().path()));
    FakeLlmProvider fake = fake();
    Summarizer summarizer =
        new Summarizer(fakeClient(fake), new InMemorySummaryStore(), sources(directories));
    Summarizer.Result result = summarizer.summarize(SummaryTree.fromFacts(facts));
    if (!result.rejected().isEmpty()) {
      throw new IllegalStateException("Fixture summaries were rejected: " + result.rejected());
    }
    Map<EntityId, Summary> summaries = result.summaries();
    // The linker's deployable units and links, derived after summarizing, as the summarizer's
    // golden prompts were written without them.
    facts.addAll(new DeployableUnits().derive(facts, NUMBER));
    facts.addAll(new CrossRepoLinks().derive(facts, NUMBER));
    Snapshot snapshot = new Snapshot(NUMBER, commits, 3, "test", Instant.EPOCH);
    return new FixtureSnapshot(
        snapshot,
        List.copyOf(facts),
        summaries,
        fake.sent().stream().map(FakeLlmProvider.Sent::request).toList());
  }

  /** Reads the fixture repositories' files from git. */
  static GitSourceFiles sources(Map<String, Path> directories) {
    return new GitSourceFiles(name -> Optional.ofNullable(directories.get(name)));
  }

  /**
   * A client of a fake model that cites the first fact and the first part's summary it is given.
   */
  static LlmClient fakeClient() {
    return fakeClient(fake());
  }

  private static LlmClient fakeClient(FakeLlmProvider fake) {
    return new LlmClient(
        ModelSettings.single(fake.model("fake-model")),
        List.of(fake),
        BudgetGuard.unlimited("test"),
        new InMemoryLlmCallLog(),
        Clock.systemUTC());
  }

  /** A fake model that cites the first fact and the first part's summary it is given. */
  private static FakeLlmProvider fake() {
    return new FakeLlmProvider()
        .answering(
            request -> {
              String prompt = request.messages().getFirst().text();
              List<String> sentences = new ArrayList<>();
              if (prompt.contains("[S1]")) {
                sentences.add("It builds on its parts. [S1]");
              }
              if (prompt.contains("[F1]")) {
                sentences.add("It is described by its facts. [F1]");
              }
              return String.join(" ", sentences);
            });
  }

  private static void copy(Path source, Path target) throws IOException {
    try (Stream<Path> paths = Files.walk(source)) {
      for (Path from : paths.sorted().toList()) {
        Path to = target.resolve(source.relativize(from).toString());
        if (Files.isDirectory(from)) {
          Files.createDirectories(to);
        } else {
          Files.copy(from, to);
        }
      }
    }
  }

  /** Commits everything in the directory as a new repository, with a fixed author and date. */
  private static String commit(Path dir) throws IOException {
    git(dir, "init", "--quiet");
    git(dir, "add", "--all");
    git(
        dir,
        "-c",
        "commit.gpgsign=false",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "--quiet",
        "--message",
        "Fixture");
    return git(dir, "rev-parse", "HEAD");
  }

  private static String git(Path dir, String... args) throws IOException {
    ProcessBuilder builder = Git.command(dir, args).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    // Only the fixed settings below, so the commit hash cannot depend on the machine's git config.
    env.put("GIT_CONFIG_GLOBAL", "/dev/null");
    env.put("GIT_CONFIG_NOSYSTEM", "1");
    for (String who : List.of("AUTHOR", "COMMITTER")) {
      env.put("GIT_" + who + "_NAME", "Lantern Test");
      env.put("GIT_" + who + "_EMAIL", "test@lantern.invalid");
      env.put("GIT_" + who + "_DATE", "2026-01-01T00:00:00Z");
    }
    Process process = builder.start();
    process.getOutputStream().close();
    String out;
    try (InputStream in = process.getInputStream()) {
      out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
    try {
      if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IOException("git " + String.join(" ", args) + " failed: " + out);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
    return out;
  }
}
