package io.lantern.app;

import io.lantern.link.GraphTools;
import io.lantern.summarize.GitSourceFiles;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The files the LLM roles read: each repository at the snapshot's commit, and each release a unit
 * bundles, {@code repo@version}, at the commit of the tag that names it (#910).
 */
final class SnapshotRepoFiles implements GraphTools.RepoFiles {

  private final Map<String, String> commits;
  private final GitSourceFiles sources;
  private final Consumer<String> masked;
  private final Map<String, Optional<String>> tagged = new ConcurrentHashMap<>();

  /**
   * @param commits each repository's commit in the snapshot
   * @param sources reads a repository's files at a commit
   * @param masked takes what masking took out of a file shown (#645)
   */
  SnapshotRepoFiles(Map<String, String> commits, GitSourceFiles sources, Consumer<String> masked) {
    this.commits = commits;
    this.sources = sources;
    this.masked = masked;
  }

  @Override
  public void masked(String value) {
    masked.accept(value);
  }

  /**
   * The repository a name reads and the commit it reads it at: a repository at the snapshot's
   * commit, a release at its tag's when that is not the snapshot's; null if none.
   */
  private String[] at(String name) {
    int release = name.indexOf(GraphTools.RELEASE);
    if (release < 0) {
      String commit = commits.get(name);
      return commit == null ? null : new String[] {name, commit};
    }
    String repo = name.substring(0, release);
    String version = name.substring(release + GraphTools.RELEASE.length());
    String analysed = commits.get(repo);
    if (analysed == null) {
      return null;
    }
    return tagged
        .computeIfAbsent(name, n -> sources.tagged(repo, version))
        .filter(commit -> !commit.equals(analysed))
        .map(commit -> new String[] {repo, commit})
        .orElse(null);
  }

  @Override
  public boolean release(String repo, String version) {
    return at(repo + GraphTools.RELEASE + version) != null;
  }

  @Override
  public Optional<byte[]> read(String repo, String path) {
    String[] at = at(repo);
    return at == null ? Optional.empty() : sources.read(at[0], at[1], path);
  }

  // The Investigate shell's copy of each repository at the snapshot's commit (#893).
  @Override
  public List<String> paths(String repo) {
    String[] at = at(repo);
    return at == null ? List.of() : sources.paths(at[0], at[1]);
  }

  // find_in_code searches each repository at the snapshot's commit (ADR 0105).
  @Override
  public List<GraphTools.Hit> find(String repo, String text, int max) {
    String[] at = at(repo);
    return at == null
        ? List.of()
        : sources.grep(at[0], at[1], text, max).stream()
            .map(match -> new GraphTools.Hit(match.path(), match.line()))
            .toList();
  }
}
