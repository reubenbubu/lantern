package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.vocabulary.AttributeRecords;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.ExtractorVocabularies;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The records in {@code io.lantern.link.attributes} are what {@link AttributeRecords} generates
 * from the linker's types (ADR 0090). Run with {@code -Dlantern.records.write=true} to rewrite them
 * after a type changes.
 */
class AttributeRecordsTest {

  static final String PACKAGE = "io.lantern.link.attributes";

  @Test
  void recordsAreGeneratedFromTheLinkersTypes() {
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    List<String> names = new ArrayList<>();
    ExtractorVocabularies.bySource().values().forEach(types::addAll);
    LinkVocabularies.bySource()
        .values()
        .forEach(
            list -> {
              types.addAll(list);
              list.forEach(t -> names.add(t.name()));
            });
    Vocabulary vocabulary = new Vocabulary(1, types);

    assertThat(
            AttributeRecords.check(
                Path.of("src/main/java", PACKAGE.split("\\.")),
                AttributeRecords.generate(vocabulary, names, PACKAGE),
                Boolean.getBoolean(AttributeRecords.WRITE_PROPERTY)))
        .as("generated records; rerun with -D" + AttributeRecords.WRITE_PROPERTY + "=true")
        .isEmpty();
  }
}
