package io.lantern.extract.java;

import io.lantern.core.fact.FactJson;
import io.lantern.extract.java.ModuleStructure.Annotation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Well-known library annotations (Spring's stereotypes, request mappings and scheduling, Jakarta
 * REST, and the JPA and Spring Data annotations the persistence extractor reads) with their
 * meta-annotations, and the enums they take, for when the classpath cannot be read: in degraded
 * mode the parser names an annotation imported by a wildcard import from this list, and {@link
 * JavaAnalysis#metaAnnotations} falls back to it. The compiler path reads the real ones.
 */
final class WellKnownAnnotations {

  private static final String RESOURCE = "/annotations/well-known.json";

  private static final Map<String, List<Annotation>> KNOWN = load();

  /**
   * Enums whose constants well-known annotations take, so the parser can name a constant of one
   * (RequestMethod.GET) when no jar resolves it.
   */
  private static final Set<String> ENUMS =
      Set.of(
          "org.springframework.web.bind.annotation.RequestMethod",
          "java.util.concurrent.TimeUnit",
          "jakarta.persistence.InheritanceType",
          "javax.persistence.InheritanceType");

  private WellKnownAnnotations() {}

  /** Whether the enum is one whose constants well-known annotations take. */
  static boolean isKnownEnum(String binaryName) {
    return ENUMS.contains(binaryName);
  }

  /**
   * String constants well-known annotations take, by type and name, so the parser can fold one
   * ({@code @Exchange(type = ExchangeTypes.TOPIC)}) when no jar resolves it.
   */
  private static final Map<String, Map<String, String>> CONSTANTS =
      Map.of(
          "org.springframework.amqp.core.ExchangeTypes",
          Map.of(
              "DIRECT", "direct",
              "TOPIC", "topic",
              "FANOUT", "fanout",
              "HEADERS", "headers",
              "SYSTEM", "system"));

  /** The value of a well-known string constant, or null if it is not one. */
  static String knownConstant(String type, String name) {
    Map<String, String> constants = CONSTANTS.get(type);
    return constants == null ? null : constants.get(name);
  }

  /** Whether the annotation type is on the list. */
  static boolean isKnown(String binaryName) {
    return KNOWN.containsKey(binaryName);
  }

  /** The meta-annotations of a listed annotation type, or null if it is not listed. */
  static List<Annotation> metaAnnotations(String binaryName) {
    return KNOWN.get(binaryName);
  }

  private static Map<String, List<Annotation>> load() {
    try (InputStream in = WellKnownAnnotations.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode root = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      Map<String, List<Annotation>> known = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> type : root.properties()) {
        List<Annotation> meta = new ArrayList<>();
        for (JsonNode a : type.getValue()) {
          Map<String, List<String>> values = new LinkedHashMap<>();
          JsonNode written = a.get("values");
          if (written != null) {
            for (Map.Entry<String, JsonNode> e : written.properties()) {
              List<String> list = new ArrayList<>();
              e.getValue().forEach(v -> list.add(v.asString()));
              values.put(e.getKey(), List.copyOf(list));
            }
          }
          meta.add(new Annotation(a.get("type").asString(), values, null));
        }
        known.put(type.getKey(), List.copyOf(meta));
      }
      return Map.copyOf(known);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }
}
