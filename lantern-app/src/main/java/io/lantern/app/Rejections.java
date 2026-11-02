package io.lantern.app;

import io.lantern.harness.RejectedAnswer;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes every answer the checks rejected in a run, and every item dropped, as {@code
 * rejections.jsonl.gz} beside the documents, one JSON object a line, so the owner can read what the
 * model answered and why each answer was refused (#682). A drop has {@code attempt} 0 and no
 * answer.
 */
final class Rejections {

  static final String FILE = "rejections.jsonl.gz";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Rejections() {}

  static Path write(Path directory, List<RejectedAnswer> rejections) {
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(FILE);
      try (Writer out =
          new OutputStreamWriter(
              new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
        for (RejectedAnswer rejection : rejections) {
          ObjectNode line = JSON.createObjectNode();
          line.put("role", rejection.role());
          line.put("subject", rejection.subject());
          line.put("kind", rejection.drop() ? "dropped" : "rejected");
          line.put("attempt", rejection.attempt());
          if (rejection.answer() != null) {
            line.put("answer", rejection.answer());
          }
          rejection.reasons().forEach(line.putArray("reasons")::add);
          out.write(line.toString());
          out.write('\n');
        }
      }
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the rejections in " + directory, e);
    }
  }
}
