package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.vocabulary.AttributeSchema;
import io.lantern.core.vocabulary.AttributeSchema.Property;
import io.lantern.core.vocabulary.AttributeSchema.ValueType;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.C4Level;
import io.lantern.core.vocabulary.TypeKind;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.core.vocabulary.VocabularyType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the vocabulary registry against the real schema in PostgreSQL. The database is shared with
 * other tests and revisions cannot be deleted, so each test uses type names of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VocabularyRegistryIT {

  @Autowired VocabularyRegistry registry;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  static VocabularyType entity(String name, String parent, String icon, AttributeSchema schema) {
    return new VocabularyType(name, TypeKind.ENTITY, parent, null, null, icon, null, schema);
  }

  @Test
  void builtInTypesAreLoadedOnStartup() {
    Vocabulary current = registry.current();

    assertThat(current.types()).containsAll(BuiltInTypes.definitions());
    assertThat(current.c4Level(BuiltInTypes.SERVICE)).contains(C4Level.CONTAINER);
    assertThat(
            jdbc.sql("SELECT count(*) FROM vocabulary_revisions WHERE source = 'built-in'")
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void loadingTheBuiltInTypesAgainChangesNothing() {
    long before = registry.current().revision();

    assertThat(registry.apply(BuiltInTypes.SOURCE, BuiltInTypes.definitions())).isEqualTo(before);
    assertThat(registry.current().revision()).isEqualTo(before);
  }

  @Test
  void everyChangeCreatesARevisionAndOldRevisionsStayReadable() {
    String name = unique("batch-job");
    VocabularyType first = entity(name, BuiltInTypes.COMPONENT, null, AttributeSchema.NONE);
    VocabularyType second =
        entity(
            name,
            BuiltInTypes.COMPONENT,
            "clock",
            new AttributeSchema(Map.of("cron", Property.of(ValueType.STRING)), Set.of(), false));

    long before = registry.current().revision();
    long added = registry.apply("test", List.of(first));
    long updated = registry.apply("test", List.of(second));

    assertThat(added).isGreaterThan(before);
    assertThat(updated).isGreaterThan(added);
    assertThat(registry.current().revision()).isEqualTo(updated);
    assertThat(registry.current().find(name)).contains(second);
    assertThat(registry.current().c4Level(name)).contains(C4Level.COMPONENT);
    assertThat(registry.at(added).find(name)).contains(first);
    assertThat(registry.at(before).find(name)).isEmpty();
    assertThat(registry.apply("test", List.of(second))).isEqualTo(updated);
    assertThat(
            jdbc.sql("SELECT source FROM vocabulary_revisions WHERE revision = :r")
                .param("r", updated)
                .query(String.class)
                .single())
        .isEqualTo("test");
  }

  @Test
  void rejectedChangesStoreNothing() {
    String name = unique("thing");
    long added =
        registry.apply(
            "test",
            List.of(
                entity(
                    name,
                    null,
                    null,
                    new AttributeSchema(
                        Map.of("a", Property.of(ValueType.STRING)), Set.of(), false))));
    VocabularyType child = entity(unique("child"), name, null, AttributeSchema.NONE);
    VocabularyType removesAttribute = entity(name, null, null, AttributeSchema.NONE);

    assertThatThrownBy(() -> registry.apply("test", List.of(child, removesAttribute)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not additive");
    assertThatThrownBy(
            () ->
                registry.apply(
                    "test",
                    List.of(entity(unique("orphan"), "missing", null, AttributeSchema.NONE))))
        .hasMessageContaining("does not exist");
    assertThat(registry.current().revision()).isEqualTo(added);
    assertThat(registry.current().find(child.name())).isEmpty();
  }

  @Test
  void unknownRevisionsAreRejected() {
    long latest = registry.current().revision();

    assertThat(registry.at(0).types()).isEmpty();
    assertThatThrownBy(() -> registry.at(latest + 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.at(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSecondRegistryOnTheSameDatabaseSeesTheChanges() {
    VocabularyRegistry other =
        new VocabularyRegistry(jdbc, new TransactionTemplate(transactionManager));
    String name = unique("shared");

    other.current();
    long revision = registry.apply("test", List.of(entity(name, null, null, AttributeSchema.NONE)));

    assertThat(other.current().revision()).isEqualTo(revision);
    assertThat(other.current().find(name)).isPresent();
  }
}
