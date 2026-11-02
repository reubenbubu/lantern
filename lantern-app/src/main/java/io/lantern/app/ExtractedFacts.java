package io.lantern.app;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * The facts of a run written next to its documents, one fact a line in the core envelope's JSON,
 * gzipped: the facts the extractors found, before linking, as {@code extracted.jsonl.gz}, which the
 * extraction coverage report reads to tell which reference items the facts support (ADR 0089); and
 * the facts the linker derived, as {@code linked.jsonl.gz}, which the scorer also reads for
 * Resolve's answers; and every fact of origin llm that holds, as {@code llm.jsonl.gz}, for the rows
 * per role (ADR 0072).
 */
final class ExtractedFacts {

  /** The file name of the extracted facts, in the snapshot's documents directory. */
  static final String FILE = "extracted.jsonl.gz";

  /** The file name of the linker's facts, in the snapshot's documents directory. */
  static final String LINKED_FILE = "linked.jsonl.gz";

  /** The file name of the facts of origin llm, in the snapshot's documents directory. */
  static final String LLM_FILE = "llm.jsonl.gz";

  private ExtractedFacts() {}

  /**
   * Writes the facts, in the order given, each once: two extractors can find the same fact.
   *
   * @param directory the snapshot's documents directory, created if missing
   * @param facts the extractors' facts
   * @return the file written
   */
  static Path write(Path directory, Collection<Fact> facts) {
    return write(directory, FILE, facts);
  }

  /**
   * Writes the facts, in the order given, each once, to a file of the given name.
   *
   * @param directory the snapshot's documents directory, created if missing
   * @param name the file name, {@link #FILE}, {@link #LINKED_FILE} or {@link #LLM_FILE}
   * @param facts the facts
   * @return the file written
   */
  static Path write(Path directory, String name, Collection<Fact> facts) {
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(name);
      try (Writer out =
          new OutputStreamWriter(
              new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
        Set<String> written = new HashSet<>();
        for (Fact fact : facts) {
          if (written.add(fact.id())) {
            out.write(FactJson.toJson(fact).toString());
            out.write('\n');
          }
        }
      }
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the facts " + name + " in " + directory, e);
    }
  }
}
