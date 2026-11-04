package io.lantern.core.vocabulary;

import io.lantern.core.fact.FactJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The built-in vocabulary types, defined in {@code vocabulary/built-in-types.json} and loaded into
 * the registry on startup. Code names them through {@link io.lantern.core.types.Types}, as it names
 * every other vocabulary type (ADR 0114, #763).
 */
public final class BuiltInTypes {

  /** The source recorded on vocabulary revisions that load the built-in types. */
  public static final String SOURCE = "built-in";

  private static final String RESOURCE = "/vocabulary/built-in-types.json";

  private BuiltInTypes() {}

  /** The built-in type definitions, read from {@code vocabulary/built-in-types.json}. */
  public static List<VocabularyType> definitions() {
    try (InputStream in = BuiltInTypes.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      if (!array.isArray()) {
        throw new IllegalStateException(RESOURCE + " must hold a JSON array");
      }
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }
}
