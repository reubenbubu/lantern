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
 * The sources of every module that reads facts write no type's name as a string where they read
 * facts: no relationship type's name anywhere, and no type's name given to a fact query (ADR 0114,
 * widened by #763). A constant would be caught by {@link
 * ArchitectureRules#NAMES_TYPES_THROUGH_THE_TYPED_LAYER}; a literal is inlined, so only the source
 * shows it.
 */
class TypeNamesInSourceTest {

  /**
   * The modules that read facts, as {@link ArchitectureRules#FACT_READERS} lists their packages.
   */
  static final List<String> MODULES =
      List.of(
          "lantern-core",
          "lantern-extract",
          "lantern-link",
          "lantern-derive",
          "lantern-harness",
          "lantern-summarize",
          "lantern-compose",
          "lantern-app");

  /**
   * Literals that only happen to equal a type's name where the scan looks, as file and literal: a
   * document block's kind read from JSON, an SQL keyword and a Spring stereotype's value.
   */
  static final Set<String> NOT_TYPE_NAMES =
      Set.of(
          "DocumentJson.java table",
          "DocumentJson.java diagram",
          "SqlScript.java REFERENCES",
          "Components.java service");

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** Where code reads facts by a name: a predicate, a query by predicate or type, a case label. */
  private static final Pattern QUERY =
      Pattern.compile(
          "(?:withPredicate|predicate\\(\\)\\.equals|type\\(\\)\\.equals|relations\\([^,()]+,"
              + "|knowledge\\s*\\.attribute\\([^,()]+,|parts\\([^,()]+,|case(?:\\s+[\\w.]+,)*)"
              + "\\s*\\(?\\s*\""
              + "((?:[^\"\\\\]|\\\\.)*)\"");

  @Test
  void theModulesThatReadFactsWriteNoTypeNameWhereTheyReadFacts() throws IOException {
    assertThat(found(sources())).as("type names written as strings; use Types").isEmpty();
  }

  @Test
  void aLinkerComparingAPredicateToALiteralIsRejected() throws IOException {
    Path fixture =
        Path.of("src/test/java/io/lantern/link/archfixture/LinkerComparingPredicates.java");

    assertThat(found(List.of(fixture)))
        .containsExactly(
            "LinkerComparingPredicates.java:12 \"CALLS_HTTP\"",
            "LinkerComparingPredicates.java:16 \"USES_DATABASE\"",
            "LinkerComparingPredicates.java:20 withPredicate(\"endpoint\"");
  }

  /** Each type name the files write where they read facts, as {@code file:line text}. */
  static List<String> found(List<Path> files) throws IOException {
    List<VocabularyType> types = Vocabularies.standard().types();
    Set<String> all = types.stream().map(VocabularyType::name).collect(Collectors.toSet());
    Set<String> relationships =
        types.stream()
            .filter(t -> t.kind() == TypeKind.RELATIONSHIP)
            .map(VocabularyType::name)
            .collect(Collectors.toSet());

    List<String> found = new ArrayList<>();
    for (Path file : files) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        String where = file.getFileName() + ":" + (i + 1) + " ";
        Matcher query = QUERY.matcher(line);
        while (query.find()) {
          if (all.contains(query.group(1))
              && !relationships.contains(query.group(1))
              && !NOT_TYPE_NAMES.contains(file.getFileName() + " " + query.group(1))) {
            found.add(where + query.group());
          }
        }
        Matcher literal = LITERAL.matcher(line);
        while (literal.find()) {
          if (relationships.contains(literal.group(1))
              && !NOT_TYPE_NAMES.contains(file.getFileName() + " " + literal.group(1))) {
            found.add(where + literal.group());
          }
        }
      }
    }
    return found;
  }

  /** The main sources of the modules that read facts, but the typed layer itself. */
  private static List<Path> sources() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String module : MODULES) {
      try (Stream<Path> walk = Files.walk(Path.of("..", module, "src/main/java"))) {
        walk.filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.endsWith(Path.of("io/lantern/core/types/Types.java")))
            .forEach(files::add);
      }
    }
    assertThat(files).hasSizeGreaterThan(MODULES.size());
    return files;
  }
}
