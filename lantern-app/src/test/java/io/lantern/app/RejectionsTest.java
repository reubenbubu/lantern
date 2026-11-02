package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.RejectedAnswer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RejectionsTest {

  @TempDir Path out;

  @Test
  void everyRejectedAnswerAndDropIsALine() throws Exception {
    Path file =
        Rejections.write(
            out,
            List.of(
                new RejectedAnswer(
                    "summarize-type",
                    "type:a.B",
                    1,
                    "{\"sentences\":[]}",
                    List.of("Cites nothing.")),
                new RejectedAnswer(
                    "summarize-type", "type:a.B", 0, null, List.of("rejected twice"))));

    String text;
    try (var in = new GZIPInputStream(Files.newInputStream(file))) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(file.getFileName().toString()).isEqualTo("rejections.jsonl.gz");
    assertThat(text.lines().toList())
        .containsExactly(
            "{\"role\":\"summarize-type\",\"subject\":\"type:a.B\",\"kind\":\"rejected\","
                + "\"attempt\":1,\"answer\":\"{\\\"sentences\\\":[]}\","
                + "\"reasons\":[\"Cites nothing.\"]}",
            "{\"role\":\"summarize-type\",\"subject\":\"type:a.B\",\"kind\":\"dropped\","
                + "\"attempt\":0,\"reasons\":[\"rejected twice\"]}");
  }
}
