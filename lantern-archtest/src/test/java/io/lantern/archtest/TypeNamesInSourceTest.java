package io.lantern.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.vocabulary.TypeKind;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.derive.Vocabularies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The composer's and the renderers' sources write no type's name as a string where they read facts:
 * no relationship type's name anywhere, and no type's name given to a fact query (ADR 0114). A
 * constant would be caught by {@link
 * ArchitectureRules#COMPOSE_NAMES_TYPES_THROUGH_THE_TYPED_LAYER}; a literal is inlined, so only the
 * source shows it.
 */
class TypeNamesInSourceTest {

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** Where code reads facts by a name: a predicate, a query by predicate or type, a case label. */
  private static final Pattern QUERY =
      Pattern.compile(
          "(?:withPredicate|predicate\\(\\)\\.equals|type\\(\\)\\.equals|relations\\([^,()]+,"
              + "|knowledge\\s*\\.attribute\\([^,()]+,|parts\\([^,()]+,|case(?:\\s+[\\w.]+,)*)"
              + "\\s*\\(?\\s*\""
              + "((?:[^\"\\\\]|\\\\.)*)\"");

  @Test
  void composeAndTheRenderersWriteNoTypeNameWhereTheyReadFacts() throws IOException {
    List<VocabularyType> types = Vocabularies.standard().types();
    Set<String> all = types.stream().map(VocabularyType::name).collect(Collectors.toSet());
    Set<String> relationships =
        types.stream()
            .filter(t -> t.kind() == TypeKind.RELATIONSHIP)
            .map(VocabularyType::name)
            .collect(Collectors.toSet());

    List<String> found = new ArrayList<>();
    for (Path file : sources()) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        String where = file.getFileName() + ":" + (i + 1) + " ";
        Matcher literal = LITERAL.matcher(line);
        while (literal.find()) {
          if (relationships.contains(literal.group(1))) {
            found.add(where + literal.group());
          }
        }
        Matcher query = QUERY.matcher(line);
        while (query.find()) {
          if (all.contains(query.group(1)) && !relationships.contains(query.group(1))) {
            found.add(where + query.group());
          }
        }
      }
    }

    assertThat(found).as("type names written as strings; use Types").isEmpty();
  }

  /** The composer's sources, and the app's renderers. */
  private static List<Path> sources() throws IOException {
    List<Path> files = new ArrayList<>();
    try (Stream<Path> compose = Files.walk(Path.of("../lantern-compose/src/main/java"))) {
      compose.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
    }
    try (Stream<Path> app = Files.walk(Path.of("../lantern-app/src/main/java"))) {
      app.filter(p -> p.getFileName().toString().endsWith("Renderer.java")).forEach(files::add);
    }
    assertThat(files).isNotEmpty();
    return files;
  }
}
