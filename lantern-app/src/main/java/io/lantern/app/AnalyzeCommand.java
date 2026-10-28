package io.lantern.app;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/**
 * The {@code analyze} command: runs the whole pipeline over the repositories named on the command
 * line and prints what it did. Started with any other arguments, or none, the app does nothing
 * here.
 *
 * <p>Usage: {@code analyze [<name>=]<path>...}, where each path is the top of a git working tree.
 * Without a name, a repository is named after its directory.
 */
final class AnalyzeCommand implements ApplicationRunner, ExitCodeGenerator {

  /** The first argument that selects this command. */
  static final String NAME = "analyze";

  /** A repository name before {@code =}; anything else is part of a path. */
  private static final Pattern NAMED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private static final Logger LOG = LoggerFactory.getLogger(AnalyzeCommand.class);

  private final AnalysisRun run;
  private final PrintStream out;
  private volatile int exitCode;

  AnalyzeCommand(AnalysisRun run) {
    this(run, System.out);
  }

  AnalyzeCommand(AnalysisRun run, PrintStream out) {
    this.run = Objects.requireNonNull(run, "run");
    this.out = Objects.requireNonNull(out, "out");
  }

  /** Whether the command line asks for this command. */
  static boolean requested(String... args) {
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        return arg.equals(NAME);
      }
    }
    return false;
  }

  /**
   * The repositories the command's arguments name, by name, in the order given.
   *
   * @throws IllegalArgumentException if there are none, or a name is given twice
   */
  static Map<String, Path> repos(List<String> args) {
    Map<String, Path> repos = new LinkedHashMap<>();
    for (String arg : args) {
      int equals = arg.indexOf('=');
      if (equals >= 0 && !NAMED.matcher(arg.substring(0, equals)).matches()) {
        equals = -1;
      }
      Path path =
          Path.of(equals < 0 ? arg : arg.substring(equals + 1)).toAbsolutePath().normalize();
      Path fileName = path.getFileName();
      String name;
      if (equals >= 0) {
        name = arg.substring(0, equals);
      } else {
        name = fileName == null ? "" : fileName.toString();
      }
      if (repos.put(name, path) != null) {
        throw new IllegalArgumentException("Repository " + name + " is named twice");
      }
    }
    if (repos.isEmpty()) {
      throw new IllegalArgumentException(
          "Usage: " + NAME + " [<name>=]<path>... (each path the top of a git working tree)");
    }
    return repos;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> words = args.getNonOptionArgs();
    if (words.isEmpty() || !words.getFirst().equals(NAME)) {
      return;
    }
    try {
      AnalysisRun.Result result = run.run(repos(words.subList(1, words.size())));
      report(result);
      exitCode = 0;
    } catch (LlmNotConfiguredException | IllegalArgumentException e) {
      // A mistake in the settings or arguments; the message says what to fix.
      out.println("The analysis failed: " + e.getMessage());
      exitCode = 1;
    } catch (RuntimeException e) {
      LOG.error("The analysis failed", e);
      out.println("The analysis failed: " + e.getMessage());
      exitCode = 1;
    }
  }

  private void report(AnalysisRun.Result result) {
    out.println("Snapshot " + result.snapshot().number() + ":");
    result
        .snapshot()
        .commits()
        .forEach(
            (repo, commit) -> {
              var build = result.builds().get(repo);
              if (build == null) {
                out.println("  " + repo + "@" + commit + ": carried forward, not analyzed");
                return;
              }
              out.println(
                  "  "
                      + repo
                      + "@"
                      + commit
                      + ": build "
                      + build.status()
                      + (result.reusedBuilds().contains(repo) ? " (reused)" : "")
                      + (build.failure() == null ? "" : ", degraded mode: " + build.failure()));
            });
    out.println("Facts: " + result.facts() + " (" + result.newFacts() + " new)");
    out.println(
        "Summaries: "
            + result.summariesGenerated()
            + " written by the LLM, "
            + result.summariesReused()
            + " reused");
    if (!result.summariesRejected().isEmpty()) {
      out.println(
          "Without a summary, the LLM's answer rejected twice ("
              + result.summariesRejected().size()
              + "; rerun to try them again):");
      result.summariesRejected().forEach(unit -> out.println("  " + unit));
    }
    out.println(
        "LLM calls: "
            + result.llm().calls()
            + ", tokens: "
            + result.llm().tokens()
            + ", cost: $"
            + result.llm().costUsd().toPlainString());
    for (AnalysisRun.Rendered document : result.documents()) {
      out.println("Wrote " + document.file());
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
