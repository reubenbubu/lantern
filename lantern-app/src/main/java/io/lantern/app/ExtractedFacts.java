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
import java.util.zip.GZIPOutputStream;

/**
 * The facts the extractors found in a run, before linking, written as {@code extracted.jsonl.gz}
 * next to its documents: one fact a line in the core envelope's JSON, gzipped. The extraction
 * coverage report reads it to tell which reference items the facts support (ADR 0089).
 */
final class ExtractedFacts {

  /** The file name, in the snapshot's documents directory. */
  static final String FILE = "extracted.jsonl.gz";

  private ExtractedFacts() {}

  /**
   * Writes the facts, in the order given.
   *
   * @param directory the snapshot's documents directory, created if missing
   * @param facts the extractors' facts
   * @return the file written
   */
  static Path write(Path directory, Collection<Fact> facts) {
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(FILE);
      try (Writer out =
          new OutputStreamWriter(
              new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
        for (Fact fact : facts) {
          out.write(FactJson.toJson(fact).toString());
          out.write('\n');
        }
      }
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the extracted facts in " + directory, e);
    }
  }
}
