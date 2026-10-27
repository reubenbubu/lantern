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
 * the registry on startup. The constants name them for typed access from code; a test keeps them in
 * line with the file. Types added at runtime have no constants and are used by name.
 */
public final class BuiltInTypes {

  /** The source recorded on vocabulary revisions that load the built-in types. */
  public static final String SOURCE = "built-in";

  // Entity types
  public static final String SYSTEM = "system";
  public static final String CONTAINER = "container";
  public static final String COMPONENT = "component";
  public static final String SERVICE = "service";
  public static final String LIBRARY = "library";
  public static final String MODULE = "module";
  public static final String PACKAGE = "package";
  public static final String TYPE = "type";

  // Relationship types
  public static final String CONTAINS = "CONTAINS";
  public static final String DEPENDS_ON = "DEPENDS_ON";
  public static final String CALLS = "CALLS";
  public static final String EXTENDS = "EXTENDS";
  public static final String IMPLEMENTS = "IMPLEMENTS";

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
