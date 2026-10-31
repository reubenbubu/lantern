package io.lantern.core.repo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** The files of a commit with their git blob SHAs, read with {@code git ls-tree} (ADR 0091). */
public final class GitTree {

  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

  private GitTree() {}

  /**
   * The blob SHA of every file in the commit, by its path relative to the repository root; empty if
   * the folder is not a git repository holding that commit.
   *
   * @param dir the repository's folder
   * @param commit the full commit hash
   */
  public static Optional<Map<String, String>> blobs(Path dir, String commit) {
    if (dir == null || !COMMIT.matcher(commit).matches() || !Files.isDirectory(dir)) {
      return Optional.empty();
    }
    Git.Result result = Git.run(dir, "ls-tree", "-r", "-z", "--full-tree", commit);
    if (!result.ok()) {
      return Optional.empty();
    }
    Map<String, String> blobs = new HashMap<>();
    for (String entry : result.out().split("\0")) {
      // <mode> SP <type> SP <object> TAB <path>
      int tab = entry.indexOf('\t');
      if (tab < 0) {
        continue;
      }
      String[] fields = entry.substring(0, tab).strip().split(" ");
      if (fields.length == 3 && fields[1].equals("blob")) {
        blobs.put(entry.substring(tab + 1), fields[2]);
      }
    }
    return Optional.of(Map.copyOf(blobs));
  }
}
