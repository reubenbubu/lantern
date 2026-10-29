package io.lantern.app;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/**
 * The {@code analyze} and {@code remove} commands. {@code analyze} runs the whole pipeline over the
 * repositories named on the command line, as one named system, and prints what it did; {@code
 * remove} removes repositories from a system (ADR 0071). Started with any other arguments, or none,
 * the app does nothing here.
 *
 * <p>Usage: {@code analyze [--system=<name>] [--title=<title>] [<name>=]<path>...}, where each path
 * is the top of a git working tree. Without a name, a repository is named after its directory.
 * Without {@code --system}, the system is named after the folder the repositories are in, which
 * must be the same for all. {@code --title} stores the system's title for this and later runs; a
 * blank one clears it.
 *
 * <p>Usage: {@code remove --system=<name> <repo>...}.
 */
final class AnalyzeCommand implements ApplicationRunner, ExitCodeGenerator {

  /** The first argument that selects this command. */
  static final String NAME = "analyze";

  /** The first argument that selects the command removing repositories from a system. */
  static final String REMOVE = "remove";

  /** The option naming the system. */
  static final String SYSTEM = "system";

  /** The option giving the system's title. */
  static final String TITLE = "title";

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
        return arg.equals(NAME) || arg.equals(REMOVE);
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
          "Usage: "
              + NAME
              + " [--system=<name>] [--title=<title>] [<name>=]<path>... (each path the top of a"
              + " git working tree)");
    }
    return repos;
  }

  /**
   * The system a run analyzes: the one {@code --system} names, else the folder all the repositories
   * are in.
   *
   * @param given the value of {@code --system}, or {@code null}
   * @throws IllegalArgumentException if none is given and the repositories are not all in one
   *     folder, or that folder's name is not a valid system name
   */
  static String system(String given, Map<String, Path> repos) {
    if (given != null) {
      return given;
    }
    Set<Path> parents = new TreeSet<>();
    repos.values().forEach(path -> parents.add(path.getParent()));
    Path parent = parents.size() == 1 ? parents.iterator().next() : null;
    Path folder = parent == null ? null : parent.getFileName();
    if (folder == null) {
      throw new IllegalArgumentException(
          "The repositories are not all in one folder, so name their system with --"
              + SYSTEM
              + "=<name>");
    }
    String name = folder.toString();
    if (!NAMED.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "The folder name '"
              + name
              + "' is not a valid system name, so name the system with --"
              + SYSTEM
              + "=<name>");
    }
    return name;
  }

  /** The single value of an option, or {@code null} if it is not given. */
  private static String option(ApplicationArguments args, String name) {
    List<String> values = args.getOptionValues(name);
    if (values == null) {
      return null;
    }
    if (values.size() != 1) {
      throw new IllegalArgumentException("Give --" + name + "=<value> once");
    }
    return values.getFirst();
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> words = args.getNonOptionArgs();
    if (words.isEmpty() || !(words.getFirst().equals(NAME) || words.getFirst().equals(REMOVE))) {
      return;
    }
    try {
      if (words.getFirst().equals(REMOVE)) {
        remove(option(args, SYSTEM), words.subList(1, words.size()));
        exitCode = 0;
        return;
      }
      Map<String, Path> repos = repos(words.subList(1, words.size()));
      AnalysisRun.Result result =
          run.run(system(option(args, SYSTEM), repos), option(args, TITLE), repos);
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

  private void remove(String system, List<String> repos) {
    if (system == null || repos.isEmpty()) {
      throw new IllegalArgumentException("Usage: " + REMOVE + " --" + SYSTEM + "=<name> <repo>...");
    }
    run.remove(system, repos);
    out.println("Removed from " + system + ": " + String.join(", ", repos));
  }

  private void report(AnalysisRun.Result result) {
    out.println("System " + result.system() + " (" + result.title() + ")");
    out.println("Snapshot " + result.snapshot().number() + ":");
    result
        .snapshot()
        .commits()
        .forEach(
            (repo, commit) -> {
              if (result.withoutBuild().contains(repo)) {
                out.println("  " + repo + "@" + commit + ": analyzed without a build (no pom.xml)");
                return;
              }
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
        "Links left open: "
            + result.linksAsked()
            + " asked of the LLM, "
            + result.linksReused()
            + " reused");
    out.println(
        "Summaries: "
            + result.summariesGenerated()
            + " written by the LLM, "
            + result.summariesReused()
            + " reused");
    if (!result.summariesRejected().isEmpty()) {
      out.println(
          "Units without a summary, the LLM's answer rejected twice ("
              + result.summariesRejected().size()
              + "); rerun to try them again:");
      result.summariesRejected().forEach(unit -> out.println("  " + unit));
    }
    out.println(
        "Responsibilities, intent phrases, flows and purpose: "
            + result.wordingsAsked()
            + " units, data stores, destinations, flows and the system worded by the LLM, "
            + result.wordingsReused()
            + " reused");
    out.println(
        "LLM calls: "
            + result.llm().calls()
            + byRole(result.llm())
            + ", tokens: "
            + result.llm().tokens()
            + ", cost: $"
            + result.llm().costUsd().toPlainString());
    for (AnalysisRun.Rendered document : result.documents()) {
      out.println("Wrote " + document.file());
    }
    if (result.runRecord() != null) {
      out.println("Wrote " + result.runRecord());
    }
  }

  /** The calls under each role, such as " (SUMMARIZE=5, COMPOSE=2)", or nothing without calls. */
  private static String byRole(JdbcLlmCallLog.Spending spending) {
    if (spending.callsByRole().isEmpty()) {
      return "";
    }
    return spending.callsByRole().entrySet().stream()
        .map(entry -> entry.getKey().name() + "=" + entry.getValue())
        .collect(Collectors.joining(", ", " (", ")"));
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
