package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.vocabulary.AttributeRecords;
import io.lantern.core.vocabulary.TypeNames;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code io.lantern.core.types.Types} is what {@link TypeNames} generates from every vocabulary
 * (ADR 0114). Run with {@code -Dlantern.records.write=true} to rewrite it after a type is added,
 * renamed or removed.
 */
class TypesTest {

  static final String PACKAGE = "io.lantern.core.types";

  @Test
  void theNamesAreGeneratedFromEveryVocabulary() {
    Vocabulary vocabulary = Vocabularies.standard();
    Path directory = Path.of("../lantern-core/src/main/java", PACKAGE.split("\\."));
    var sources =
        TypeNames.generate(
            vocabulary,
            vocabulary.types().stream().map(VocabularyType::name).toList(),
            PACKAGE,
            "Types");

    assertThat(
            AttributeRecords.check(
                directory, sources, Boolean.getBoolean(AttributeRecords.WRITE_PROPERTY)))
        .as("generated names; rerun with -D" + AttributeRecords.WRITE_PROPERTY + "=true")
        .isEmpty();
  }
}
