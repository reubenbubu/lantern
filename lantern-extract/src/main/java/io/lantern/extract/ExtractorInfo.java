package io.lantern.extract;

import io.lantern.core.fact.FactJson;
import io.lantern.core.vocabulary.VocabularyType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * An extractor's name and version, the origin name and version of its facts, and the vocabulary it
 * emits facts of (ADR 0092, amendment for #551).
 *
 * <p>An extractor may emit facts whose predicate is a type of its own vocabulary, a built-in type,
 * or a type another extractor's vocabulary defines that it names in {@code borrowed}, as the Config
 * and Deployment extractors record the Spring vocabulary's {@code cors-policy}. Each type carries
 * its key attributes.
 *
 * @param name the extractor's name, such as {@code maven}
 * @param version its version, raised when what it extracts changes
 * @param vocabularySource the source its types are registered under, such as {@code
 *     maven-extractor}; {@code null} for an extractor without a vocabulary of its own
 * @param vocabulary the types it defines, each with its key attributes; empty without a source
 * @param borrowed the types other extractors' vocabularies define that it also emits facts of
 */
public record ExtractorInfo(
    String name,
    String version,
    String vocabularySource,
    List<VocabularyType> vocabulary,
    Set<String> borrowed) {

  public ExtractorInfo {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    if (name.isBlank() || version.isBlank()) {
      throw new IllegalArgumentException("An extractor needs a name and a version");
    }
    vocabulary = List.copyOf(vocabulary == null ? List.of() : vocabulary);
    borrowed = Set.copyOf(borrowed == null ? Set.of() : borrowed);
    if (vocabularySource == null ? !vocabulary.isEmpty() : vocabularySource.isBlank()) {
      throw new IllegalArgumentException(
          "Extractor " + name + " needs a vocabulary source exactly when it defines types");
    }
    for (VocabularyType type : vocabulary) {
      if (borrowed.contains(type.name())) {
        throw new IllegalArgumentException(
            "Extractor " + name + " both defines and borrows type '" + type.name() + "'");
      }
    }
  }

  /** An extractor that emits facts only of built-in types, as in tests. */
  public ExtractorInfo(String name, String version) {
    this(name, version, null, List.of(), Set.of());
  }

  /**
   * An extractor whose vocabulary is a JSON resource of {@code lantern-extract}.
   *
   * @param resource the resource's absolute path, such as {@code /vocabulary/maven-types.json}
   */
  public static ExtractorInfo of(
      String name, String version, String vocabularySource, String resource, Set<String> borrowed) {
    return new ExtractorInfo(name, version, vocabularySource, load(resource), borrowed);
  }

  /**
   * The types of a vocabulary resource of {@code lantern-extract}, in the order written: an array
   * of types as {@code schema/vocabulary-type.schema.json} defines them.
   */
  public static List<VocabularyType> load(String resource) {
    try (InputStream in = ExtractorInfo.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + resource);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + resource, e);
    }
  }

  /** Whether facts of this predicate are the extractor's to emit, built-in types aside. */
  public boolean declares(String predicate) {
    return borrowed.contains(predicate)
        || vocabulary.stream().anyMatch(type -> type.name().equals(predicate));
  }
}
