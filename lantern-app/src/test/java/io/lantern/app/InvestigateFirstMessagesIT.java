package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.harness.Toml;
import io.lantern.link.GraphTools;
import io.lantern.link.Investigator;
import io.lantern.summarize.GitSourceFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Every lead's first message on the test sets (#860): built from a fake-model run's facts and the
 * repositories at the commits they are checked out at, each reads back as TOML and holds at most
 * {@code InvestigateLeadMessage.MAX_CHARS} characters. The messages are written to {@code
 * target/first-messages/<set>/} for reading. Sets whose run or repositories are not here are left
 * out; with none, the test is skipped.
 *
 * <p>{@code -Dlantern.test.first-messages=<run folder>=<repositories folder>,...} names the sets;
 * by default the main checkout's {@code workspace/llm-logs/inputs/project-<n>-fake} for project-0
 * to project-4 and project-8.
 */
class InvestigateFirstMessagesIT {

  private static final int MAX_CHARS = 6_000;

  @Test
  void everyFirstMessageReadsAsTomlAndStaysSmall() throws IOException {
    Map<String, Path[]> sets = sets();
    Assumptions.assumeFalse(sets.isEmpty(), "no fake-model run and repositories here");
    Path out = Path.of("target", "first-messages");
    List<String> over = new ArrayList<>();
    StringBuilder sizes = new StringBuilder("set\tlead\tcharacters\n");
    for (Map.Entry<String, Path[]> set : sets.entrySet()) {
      Path run = set.getValue()[0];
      Path reposFolder = set.getValue()[1];
      List<Fact> facts =
          new ArrayList<>(SummarizeOpenRouterIT.read(run.resolve(ExtractedFacts.FILE)));
      facts.addAll(SummarizeOpenRouterIT.read(run.resolve(ExtractedFacts.LINKED_FILE)));
      Map<String, Path> repos = SummarizeOpenRouterIT.repositories(reposFolder);
      Map<String, String> commits = new TreeMap<>();
      for (Map.Entry<String, Path> repo : repos.entrySet()) {
        commits.put(repo.getKey(), head(repo.getValue()));
      }
      GitSourceFiles sources = new GitSourceFiles(name -> Optional.ofNullable(repos.get(name)));
      GraphTools.RepoFiles files =
          new GraphTools.RepoFiles() {
            @Override
            public Optional<byte[]> read(String repo, String path) {
              return Optional.ofNullable(commits.get(repo))
                  .flatMap(commit -> sources.read(repo, commit, path));
            }
          };
      Map<String, String> messages = Investigator.firstMessages(facts, files);
      Path folder = out.resolve(set.getKey());
      Files.createDirectories(folder);
      int n = 0;
      for (Map.Entry<String, String> message : messages.entrySet()) {
        n++;
        String text = message.getValue();
        Files.writeString(
            folder.resolve("%03d.toml".formatted(n)), "# " + message.getKey() + "\n" + text);
        sizes.append(set.getKey()).append('\t').append(message.getKey()).append('\t');
        sizes.append(text.length()).append('\n');
        Toml.read(text);
        if (text.length() > MAX_CHARS) {
          over.add(set.getKey() + " " + message.getKey() + ": " + text.length());
        }
      }
    }
    Files.writeString(out.resolve("sizes.tsv"), sizes);
    assertThat(over).as("first messages over %d characters", MAX_CHARS).isEmpty();
  }

  private static Map<String, Path[]> sets() {
    Map<String, Path[]> sets = new LinkedHashMap<>();
    String named = System.getProperty("lantern.test.first-messages", "");
    if (!named.isBlank()) {
      for (String pair : named.split(",")) {
        String[] parts = pair.split("=", 2);
        Path run = Path.of(parts[0].strip());
        sets.put(run.getFileName().toString(), new Path[] {run, Path.of(parts[1].strip())});
      }
      return sets;
    }
    Path home = Path.of(System.getProperty("user.home"), "workspace");
    Path inputs = home.resolve("lantern/workspace/llm-logs/inputs");
    for (String set :
        List.of("project-0", "project-1", "project-2", "project-3", "project-4", "project-8")) {
      Path repos = home.resolve("lantern-test-projects").resolve(set);
      Path run = latest(inputs, set);
      if (run != null && Files.isDirectory(repos)) {
        sets.put(set, new Path[] {run, repos});
      }
    }
    return sets;
  }

  /** The set's fake-model run: {@code <set>-fake}, else the last named {@code <set>-fake-*}. */
  private static Path latest(Path inputs, String set) {
    Path plain = inputs.resolve(set + "-fake");
    if (Files.isRegularFile(plain.resolve(ExtractedFacts.FILE))) {
      return plain;
    }
    try (var all = Files.list(inputs)) {
      return all.filter(p -> p.getFileName().toString().startsWith(set + "-fake-"))
          .filter(p -> Files.isRegularFile(p.resolve(ExtractedFacts.FILE)))
          .sorted()
          .reduce((a, b) -> b)
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  private static String head(Path repo) throws IOException {
    Process git = new ProcessBuilder("git", "-C", repo.toString(), "rev-parse", "HEAD").start();
    try {
      String commit = new String(git.getInputStream().readAllBytes()).strip();
      assertThat(git.waitFor()).as("git rev-parse HEAD in %s", repo).isZero();
      return commit;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }
}
