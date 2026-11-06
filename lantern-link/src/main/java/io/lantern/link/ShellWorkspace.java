package io.lantern.link;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The code Investigate's shell reads (#893): each repository of the snapshot written at its
 * analysed commit into a folder of its own, every file as the tools show it, its secrets masked
 * (ADR 0054; what masking takes out goes to the harness's Secrets check, #645), and binary files,
 * keys and keystores left out; then the sealed container the shell's commands run in, started over
 * that folder and removed with it when the run ends. Its files are what the tools' answers come
 * from, so the attribution of an answer's lines to files reads them here.
 */
public final class ShellWorkspace implements AutoCloseable {

  /** A command's run in the container. */
  public record Run(String out, String err, int exitCode, boolean timedOut, boolean overCeiling) {}

  /** The container a run's commands go to; started over the written folder, removed on close. */
  public interface Container extends AutoCloseable {

    /** Runs one command with {@code sh -c} in the working folder that holds the repositories. */
    Run run(String command);

    @Override
    void close();
  }

  /** Starts the container over the folder that holds one folder per repository. */
  @FunctionalInterface
  public interface Starter {
    Container start(Path root, Set<String> repos);
  }

  private final Path root;
  private final Set<String> repos;
  private final Container container;

  private ShellWorkspace(Path root, Set<String> repos, Container container) {
    this.root = root;
    this.repos = repos;
    this.container = container;
  }

  /**
   * Writes the snapshot's repositories into a new folder and starts the container over it.
   *
   * @param graph the snapshot's facts and files, which mask the files as the tools show them
   * @param starter how the container is started
   */
  static ShellWorkspace open(GraphTools.Graph graph, Starter starter) {
    Path root;
    try {
      root = Files.createTempDirectory("lantern-investigate-");
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot make a folder for the Investigate shell", e);
    }
    Set<String> written = new TreeSet<>();
    try {
      for (String repo : graph.repos()) {
        if (write(graph, repo, root.resolve(repo))) {
          written.add(repo);
        }
      }
      return new ShellWorkspace(root, Set.copyOf(written), starter.start(root, written));
    } catch (RuntimeException e) {
      delete(root);
      throw e;
    }
  }

  /** Writes one repository's files as the tools show them; whether it has any. */
  private static boolean write(GraphTools.Graph graph, String repo, Path into) {
    List<String> paths = graph.files().paths(repo);
    boolean any = false;
    for (String path : paths) {
      Path file = into.resolve(path).normalize();
      if (!file.startsWith(into) || path.isBlank()) {
        continue;
      }
      Optional<byte[]> content = graph.files().read(repo, path);
      if (content.isEmpty()) {
        continue;
      }
      String text = graph.shown(path, content.get());
      if (text == null) {
        continue;
      }
      try {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        readable(file);
        any = true;
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot write " + file, e);
      }
    }
    if (any) {
      try (Stream<Path> dirs = Files.walk(into)) {
        for (Path dir : dirs.filter(Files::isDirectory).toList()) {
          readable(dir);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return any;
  }

  /** Readable by the container's user, whatever its id: files 0644, folders 0755. */
  private static void readable(Path path) throws IOException {
    try {
      Files.setPosixFilePermissions(
          path,
          PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwxr-xr-x" : "rw-r--r--"));
    } catch (UnsupportedOperationException e) {
      // Not a POSIX file system: left as created.
    }
  }

  /** Runs a command in the container. */
  Run run(String command) {
    return container.run(Objects.requireNonNull(command, "command"));
  }

  /** The repositories written, each a folder of the working folder. */
  Set<String> repos() {
    return repos;
  }

  /** A file as the shell sees it, its lines; empty if there is none. */
  Optional<List<String>> lines(String repo, String path) {
    if (!repos.contains(repo)) {
      return Optional.empty();
    }
    Path base = root.resolve(repo);
    Path file = base.resolve(path).normalize();
    if (!file.startsWith(base) || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (IOException | UncheckedIOException e) {
      return Optional.empty();
    }
  }

  /** Whether the repository holds the file. */
  boolean has(String repo, String path) {
    if (!repos.contains(repo) || path.isBlank()) {
      return false;
    }
    Path base = root.resolve(repo);
    Path file = base.resolve(path).normalize();
    return file.startsWith(base) && Files.isRegularFile(file);
  }

  /** Removes the container and the written folder. */
  @Override
  public void close() {
    try {
      container.close();
    } finally {
      delete(root);
    }
  }

  private static void delete(Path root) {
    try (Stream<Path> all = Files.walk(root)) {
      List<Path> paths = new ArrayList<>(all.toList());
      paths.sort(Comparator.reverseOrder());
      for (Path p : paths) {
        Files.deleteIfExists(p);
      }
    } catch (IOException | UncheckedIOException e) {
      // Best effort: the folder is under the temporary folder.
    }
  }
}
