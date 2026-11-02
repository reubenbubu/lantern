package io.lantern.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every role's prompt text (ADR 0113): read from the repository's {@code prompts/} folder, so the
 * owner can tune a prompt by editing its file, or from the copy built into Lantern when the folder
 * or the file is missing. A role's prompt version takes a digest of the files it uses whenever one
 * of them differs from its built-in copy, so an edit re-asks that role's items and nothing else,
 * and an unedited folder keeps every answer stored before.
 *
 * <p>The folder is the system property {@value #FOLDER_PROPERTY}, or {@value #DEFAULT_FOLDER} in
 * the working directory if it exists. It is read once per file and kept for the life of the
 * process, so a run never mixes two versions of one prompt.
 */
public final class Prompts {

  /** The system property naming the prompts folder. */
  public static final String FOLDER_PROPERTY = "lantern.prompts.dir";

  /** The folder used when the property is not set, relative to the working directory. */
  public static final String DEFAULT_FOLDER = "prompts";

  private static final String BUILT_IN = "/prompts/";

  private static final Map<String, String> TEXTS = new ConcurrentHashMap<>();

  private static volatile boolean resolved;

  private static volatile Path folder;

  private Prompts() {}

  /**
   * Uses the folder, or only the built-in copies when {@code null}, and forgets every text read
   * before; for tests and for a caller that sets the folder before any role is built.
   */
  public static synchronized void useFolder(Path dir) {
    if (dir != null && !Files.isDirectory(dir)) {
      throw new IllegalStateException("The prompts folder " + dir + " does not exist");
    }
    folder = dir;
    resolved = true;
    TEXTS.clear();
  }

  /** The folder prompts are read from, or {@code null} when only the built-in copies are used. */
  public static Path folder() {
    if (!resolved) {
      synchronized (Prompts.class) {
        if (!resolved) {
          String named = System.getProperty(FOLDER_PROPERTY);
          if (named != null && !named.isBlank()) {
            Path dir = Path.of(named.strip());
            if (!Files.isDirectory(dir)) {
              throw new IllegalStateException(
                  "The prompts folder " + dir + " (" + FOLDER_PROPERTY + ") does not exist");
            }
            folder = dir;
          } else {
            Path dir = Path.of(DEFAULT_FOLDER);
            folder = Files.isDirectory(dir) ? dir : null;
          }
          resolved = true;
        }
      }
    }
    return folder;
  }

  /** The prompt file's text: the folder's copy if it has one, else the built-in copy. */
  public static String text(String name) {
    return TEXTS.computeIfAbsent(name, Prompts::read);
  }

  /** The prompt file's built-in copy, as Lantern was built with it. */
  public static String builtIn(String name) {
    String path = BUILT_IN + name;
    try (InputStream in = Prompts.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing prompt " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + path, e);
    }
  }

  /** Whether the folder's copy of the prompt file differs from the built-in one. */
  public static boolean edited(String name) {
    return !text(name).equals(builtIn(name));
  }

  /**
   * A role's prompt version: {@code version} while every file it uses is as built, otherwise {@code
   * version} with a digest of the edited files' names and texts, such as {@code 5+3fa2c91b0e4d}.
   */
  public static String version(String version, String... names) {
    List<String> edited = new ArrayList<>();
    for (String name : names) {
      if (edited(name)) {
        edited.add(name);
      }
    }
    if (edited.isEmpty()) {
      return version;
    }
    StringBuilder all = new StringBuilder();
    for (String name : edited) {
      all.append(name).append('\n').append(text(name)).append('\n');
    }
    return version + "+" + Fingerprints.sha256(all.toString()).substring(0, 12);
  }

  /**
   * A line saying where prompts come from and which files of the folder differ from their built-in
   * copies, for the start of a run.
   */
  public static String describe() {
    Path dir = folder();
    if (dir == null) {
      return "Prompts: built in";
    }
    List<String> edited = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
        String name = file.getFileName().toString();
        if (Prompts.class.getResource(BUILT_IN + name) == null) {
          if (name.endsWith(".txt") || name.endsWith(".json")) {
            unknown.add(name);
          }
        } else if (edited(name)) {
          edited.add(name);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list " + dir, e);
    }
    return "Prompts: "
        + dir.toAbsolutePath().normalize()
        + "; edited: "
        + (edited.isEmpty() ? "none" : String.join(", ", edited))
        + (unknown.isEmpty() ? "" : "; not read by any role: " + String.join(", ", unknown));
  }

  private static String read(String name) {
    Path dir = folder();
    if (dir != null) {
      Path file = dir.resolve(name);
      if (Files.isRegularFile(file)) {
        try {
          return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
          throw new UncheckedIOException("Cannot read " + file, e);
        }
      }
    }
    return builtIn(name);
  }
}
