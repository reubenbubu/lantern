package io.lantern.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A role's recorded fixture (#768): a folder in the repository holding the recorded requests and
 * answers of one scripted run of the role, and {@code expected.txt}, what the run must produce, one
 * line each. A test replays the folder and compares what the role stores with the expected lines,
 * so a change to the role's prompt, checks or facts shows as a failing test.
 *
 * <p>With {@code -Dlantern.test.rerecord=true} the folder is written again from the scripted fake
 * model, and {@code expected.txt} from what the run produced: no real model is ever called. A
 * person reads the new expected lines before committing them.
 */
public final class RoleFixture {

  /** The system property that writes the fixtures again. */
  public static final String RERECORD = "lantern.test.rerecord";

  /** The file of the expected lines, beside the recordings. */
  public static final String EXPECTED = "expected.txt";

  private RoleFixture() {}

  /** Whether this run writes the fixtures again. */
  public static boolean rerecording() {
    return Boolean.getBoolean(RERECORD);
  }

  /**
   * The provider a fixture test runs the role with: the recordings in the folder, or, when
   * rerecording, the scripted fake recorded into the folder emptied first.
   */
  public static LlmProvider provider(FakeLlmProvider scripted, Path folder) {
    Objects.requireNonNull(scripted, "scripted");
    if (!rerecording()) {
      return RecordReplayLlmProvider.replaying(scripted.name(), folder);
    }
    try {
      Files.createDirectories(folder);
      try (Stream<Path> files = Files.list(folder)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
          Files.delete(file);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return RecordReplayLlmProvider.recording(scripted, folder);
  }

  /**
   * The lines the run must produce: those in the folder's {@code expected.txt}, or, when
   * rerecording, the given ones, written there first.
   */
  public static List<String> expected(Path folder, List<String> produced) {
    Path file = folder.resolve(EXPECTED);
    try {
      if (rerecording()) {
        Files.createDirectories(folder);
        Files.writeString(file, String.join("\n", produced) + "\n", StandardCharsets.UTF_8);
      }
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .filter(l -> !l.isEmpty())
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
