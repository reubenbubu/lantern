package io.lantern.link;

import io.lantern.link.InvestigatePrompt.Cite;
import io.lantern.link.InvestigatePrompt.Proposal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies an Investigate proposal's citations mechanically (ADR 0084; docs/design.md, Grounding):
 * each cited range must be in a repository of the snapshot, in a file that can be read at the
 * snapshot's commit, within the file's lines and at most {@link #MAX_LINES} long; every name the
 * proposal gives must appear, as the code writes it, in the lines it cites; and every range must
 * hold at least one of the names. The lines are read as the model's tools show them, with secrets
 * masked, so a masked secret is never a name that holds.
 *
 * <p>Thread-safe; files are read once.
 */
final class ProposalCheck {

  /** The most lines one cited range may span. */
  static final int MAX_LINES = 40;

  /** A file as the check reads it: its lines, as the tools show them, and its content's hash. */
  record File(List<String> lines, String hash) {}

  private final GraphTools.RepoFiles files;
  private final Set<String> repos;
  private final Set<String> secretKeys;
  private final Map<String, Optional<File>> read = new ConcurrentHashMap<>();

  /**
   * @param files the snapshot's files
   * @param repos the snapshot's repositories
   * @param secretKeys the config keys whose values the tools mask in files (#348)
   */
  ProposalCheck(GraphTools.RepoFiles files, Set<String> repos, Set<String> secretKeys) {
    this.files = Objects.requireNonNull(files, "files");
    this.repos = Set.copyOf(repos);
    this.secretKeys = Set.copyOf(secretKeys);
  }

  /** Why the proposal fails the check, or null if every citation holds. */
  String problem(Proposal proposal) {
    List<String> cited = new ArrayList<>();
    for (Cite cite : proposal.cites()) {
      String problem = rangeProblem(cite);
      if (problem != null) {
        return problem;
      }
      String text =
          String.join("\n", file(cite).orElseThrow().lines().subList(cite.from() - 1, cite.to()));
      if (proposal.names().stream().noneMatch(text::contains)) {
        return "the lines "
            + cite
            + " hold none of its names ("
            + String.join(", ", proposal.names())
            + "); cite the lines that show them.";
      }
      cited.add(text);
    }
    List<String> missing =
        proposal.names().stream()
            .filter(n -> cited.stream().noneMatch(t -> t.contains(n)))
            .toList();
    if (!missing.isEmpty()) {
      return "the lines it cites do not hold "
          + String.join(", ", missing)
          + "; cite where the code writes "
          + (missing.size() == 1 ? "it" : "them")
          + ", or leave "
          + (missing.size() == 1 ? "it" : "them")
          + " out.";
    }
    return null;
  }

  /** The file a citation is of, if it can be read. */
  Optional<File> file(Cite cite) {
    return read.computeIfAbsent(
        cite.repo() + ":" + cite.path(),
        key -> {
          if (!repos.contains(cite.repo())
              || cite.path().contains("..")
              || cite.path().contains("\\")) {
            return Optional.empty();
          }
          return files
              .read(cite.repo(), cite.path())
              .flatMap(
                  content -> {
                    String text = GraphTools.masked(cite.path(), content, secretKeys);
                    return text == null
                        ? Optional.empty()
                        : Optional.of(new File(text.lines().toList(), sha256(content)));
                  });
        });
  }

  private String rangeProblem(Cite cite) {
    if (!repos.contains(cite.repo())) {
      return "it cites "
          + cite
          + ", but there is no repository "
          + cite.repo()
          + "; the repositories: "
          + String.join(", ", repos.stream().sorted().toList())
          + ".";
    }
    Optional<File> file = file(cite);
    if (file.isEmpty()) {
      return "it cites "
          + cite
          + ", but there is no file "
          + cite.path()
          + " in "
          + cite.repo()
          + " that can be read.";
    }
    int lines = file.get().lines().size();
    if (cite.from() < 1 || cite.to() < cite.from() || cite.to() > lines) {
      return "it cites " + cite + ", but " + cite.path() + " has lines 1 to " + lines + ".";
    }
    if (cite.to() - cite.from() + 1 > MAX_LINES) {
      return "it cites "
          + cite
          + ", more than "
          + MAX_LINES
          + " lines; cite the lines that show it.";
    }
    return null;
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
