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
 * of them differs from the text pinned for it in {@code prompts.sha256}, so an edit re-asks that
 * role's items and nothing else, also after a rebuild, and an unedited folder keeps every answer
 * stored before. Whoever changes a prompt for good raises the role's version and the file's pin
 * together; {@code PromptsTest} fails until the pin matches.
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

  private static volatile Map<String, String> PINS;

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
    Contract.forget();
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

  /**
   * Whether the prompt file's text differs from the text its role's version stands for: the SHA-256
   * pinned for it in {@code prompts.sha256}, not the built-in copy, which is built from the same
   * folder and so would match an edit once Lantern is rebuilt.
   */
  public static boolean edited(String name) {
    String pinned = pins().get(name);
    if (pinned == null) {
      throw new IllegalStateException("No pinned SHA-256 for the prompt " + name);
    }
    return !Fingerprints.sha256(text(name)).equals(pinned);
  }

  /** The SHA-256 each prompt file's text had when its role's version was last set, by file name. */
  public static Map<String, String> pins() {
    Map<String, String> pins = PINS;
    if (pins == null) {
      try (InputStream in = Prompts.class.getResourceAsStream("/prompts.sha256")) {
        if (in == null) {
          throw new IllegalStateException("Missing /prompts.sha256");
        }
        Map<String, String> read = new java.util.TreeMap<>();
        for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
          String[] parts = line.strip().split("\\s+");
          if (parts.length == 2) {
            read.put(parts[0], parts[1]);
          }
        }
        pins = Map.copyOf(read);
        PINS = pins;
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read /prompts.sha256", e);
      }
    }
    return pins;
  }

  /**
   * A role's prompt version: {@code version} while every file it uses has its pinned text,
   * otherwise {@code version} with a digest of the edited files' names and texts, such as {@code
   * 5+3fa2c91b0e4d}.
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
   * A line saying where prompts come from and which files of the folder differ from their pinned
   * text, for the start of a run.
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
