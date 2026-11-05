package io.lantern.app;

import io.lantern.derive.FlowSelection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Which flows each scope draws, by the model's judgement and by rank on the same candidates, with
 * the drawn flows that overlap, written beside {@code run.json} as {@code flows.json} for the
 * scorecard's flow selection row (ADR 0108, amendment for #743; ADR 0072).
 */
final class FlowSelectionFile {

  /** The file name, in the snapshot's documents directory. */
  static final String FILE = "flows.json";

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private FlowSelectionFile() {}

  /** The scopes as JSON: {@code {"scopes": [{scope, candidates, judged, rank, overlaps}]}}. */
  static ObjectNode json(List<FlowSelection.Scope> scopes) {
    ObjectNode root = MAPPER.createObjectNode();
    ArrayNode array = root.putArray("scopes");
    for (FlowSelection.Scope scope : scopes) {
      ObjectNode node = array.addObject();
      node.put("scope", scope.scope());
      strings(node.putArray("candidates"), scope.candidates());
      if (scope.judged() == null) {
        node.putNull("judged");
      } else {
        strings(node.putArray("judged"), scope.judged());
      }
      strings(node.putArray("rank"), scope.rank());
      strings(node.putArray("overlaps"), scope.overlaps());
    }
    return root;
  }

  /** Writes the scopes to {@value #FILE} in the directory and returns the file. */
  static Path write(Path directory, List<FlowSelection.Scope> scopes) {
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(FILE);
      Files.writeString(file, MAPPER.writeValueAsString(json(scopes)) + "\n");
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the flow selection in " + directory, e);
    }
  }

  private static void strings(ArrayNode array, List<String> values) {
    values.forEach(array::add);
  }
}
