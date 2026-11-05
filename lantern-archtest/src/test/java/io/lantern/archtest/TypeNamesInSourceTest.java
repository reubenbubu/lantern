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
 * facts: no relationship type's name anywhere, no other type's name whose words are joined by a
 * hyphen anywhere (such names are never English words), and no type's name given to a fact query
 * (ADR 0114, widened by #763). A constant would be caught by {@link
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
   * document block's kind read from JSON or a section's block ID, an extractor's name, an SQL
   * keyword, a Spring stereotype's value, a finding's kind, a value of an attribute, and an LLM
   * role's name.
   */
  static final Set<String> NOT_TYPE_NAMES =
      Set.of(
          "DocumentJson.java table",
          "DocumentJson.java diagram",
          "SqlScript.java REFERENCES",
          "Components.java service",
          "Components.java entry-point",
          "Findings.java java-release",
          "Findings.java version-skew",
          "Findings.java unrouted-endpoint",
          "Investigator.java version-skew",
          "Investigator.java unrouted-endpoint",
          "FlowJudgements.java flow-significance",
          "FlowJudgements.java flow-narrative",
          "RepoSections.java key-fact",
          "UnknownsPage.java not-investigated",
          "HttpClientExtractor.java http-client");

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** Where code reads facts by a name: a predicate, a query by predicate or type, a case label. */
  private static final Pattern QUERY =
      Pattern.compile(
          "(?:withPredicate|predicate\\(\\)\\.equals|type\\(\\)\\.equals|relations\\([^,()]+,"
              + "|knowledge\\s*\\.attribute\\([^,()]+,|parts\\([^,()]+,|case(?:\\s+[\\w.]+,)*)"
              + "\\s*\\(?\\s*\""
              + "((?:[^\"\\\\]|\\\\.)*)\"");

  /**
   * Where code builds or reads an entity ID with its type written out: {@code new EntityId("x",
   * ...)} or a literal that starts {@code "x:"}, for any type name, one-word names included
   * (decision of 4 Nov 2026 on #763).
   */
  private static final Pattern ENTITY_ID = Pattern.compile("new EntityId\\(\\s*\"([^\"]*)\"");

  /** Literals whose {@code <type>:} is not an entity ID's type, as file and literal, with why. */
  static final Set<String> NOT_ENTITY_IDS =
      Set.of(
          // The word "repo" an LLM wrote in place of a repository's name in a citation (#703).
          "Checks.java repo:",
          // The ID of the system's C4 view, not an entity; its colon keeps it apart from a
          // repository's view.
          "AnalysisRun.java system:c4");

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
            "LinkerComparingPredicates.java:13 \"CALLS_HTTP\"",
            "LinkerComparingPredicates.java:17 \"USES_DATABASE\"",
            "LinkerComparingPredicates.java:21 withPredicate(\"endpoint\"",
            "LinkerComparingPredicates.java:25 \"http-call\"",
            "LinkerComparingPredicates.java:28 new EntityId(\"system\"",
            "LinkerComparingPredicates.java:31 new EntityId(\"repo\"",
            "LinkerComparingPredicates.java:35 \"type:\"",
            "LinkerComparingPredicates.java:39 \"artifact:\"");
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
              && !query.group(1).contains("-")
              && !NOT_TYPE_NAMES.contains(file.getFileName() + " " + query.group(1))) {
            found.add(where + query.group());
          }
        }
        Matcher entity = ENTITY_ID.matcher(line);
        while (entity.find()) {
          if (all.contains(entity.group(1))) {
            found.add(where + entity.group());
          }
        }
        Matcher literal = LITERAL.matcher(line);
        while (literal.find()) {
          String text = literal.group(1);
          int colon = text.indexOf(':');
          if (colon > 0
              && all.contains(text.substring(0, colon))
              && !NOT_ENTITY_IDS.contains(file.getFileName() + " " + text)) {
            found.add(where + literal.group());
            continue;
          }
          if ((relationships.contains(text) || (text.contains("-") && all.contains(text)))
              && !NOT_TYPE_NAMES.contains(file.getFileName() + " " + literal.group(1))) {
            found.add(where + literal.group());
          }
        }
      }
    }
    return found;
  }

  /**
   * The main sources of the modules that read facts, but the typed layer: Types and the records.
   */
  private static List<Path> sources() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String module : MODULES) {
      try (Stream<Path> walk = Files.walk(Path.of("..", module, "src/main/java"))) {
        walk.filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.endsWith(Path.of("io/lantern/core/types/Types.java")))
            .filter(p -> !p.getParent().getFileName().toString().equals("attributes"))
            .forEach(files::add);
      }
    }
    assertThat(files).hasSizeGreaterThan(MODULES.size());
    return files;
  }
}
