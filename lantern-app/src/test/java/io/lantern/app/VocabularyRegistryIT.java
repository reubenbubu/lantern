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
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.websocket.WebSocketExtractor;
import io.lantern.link.CrossRepoLinks;
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

  @Test
  void aTypeMayChangeItsParentAndOldRevisionsKeepTheOldOne() {
    String name = unique("shelf");
    VocabularyType underContainer =
        entity(name, BuiltInTypes.CONTAINER, null, AttributeSchema.NONE);
    VocabularyType underNothing = entity(name, null, null, AttributeSchema.NONE);

    long added = registry.apply("test", List.of(underContainer));
    long moved = registry.apply("test", List.of(underNothing));

    assertThat(moved).isGreaterThan(added);
    assertThat(registry.current().find(name)).contains(underNothing);
    assertThat(registry.current().c4Level(name)).isEmpty();
    assertThat(registry.at(added).find(name)).contains(underContainer);
    assertThat(registry.at(added).c4Level(name)).contains(C4Level.CONTAINER);
    assertThat(registry.apply("test", List.of(underNothing))).isEqualTo(moved);
  }

  @Test
  void aParentChangeThatBreaksTheVocabularyStoresNothing() {
    String a = unique("outer");
    String b = unique("inner");
    long added =
        registry.apply(
            "test",
            List.of(
                entity(a, null, null, AttributeSchema.NONE),
                entity(b, a, null, AttributeSchema.NONE)));

    assertThatThrownBy(
            () -> registry.apply("test", List.of(entity(a, b, null, AttributeSchema.NONE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
    assertThat(registry.current().revision()).isEqualTo(added);
    assertThat(registry.current().find(a).orElseThrow().parent()).isNull();
  }

  @Test
  void aTypeBelongsToTheSourceThatFirstDefinedIt() {
    String name = unique("vault");
    long added = registry.apply("test-a", List.of(entity(name, null, null, AttributeSchema.NONE)));
    long changed =
        registry.apply("test-a", List.of(entity(name, null, "lock", AttributeSchema.NONE)));

    assertThat(changed).isGreaterThan(added);
    assertThat(registry.owner(name)).contains("test-a");
    assertThat(registry.owner(unique("unknown"))).isEmpty();
  }

  @Test
  void anotherSourceMayNotChangeATypeEvenAdditively() {
    String name = unique("vault");
    VocabularyType original = entity(name, null, null, AttributeSchema.NONE);
    long added = registry.apply("test-a", List.of(original));
    VocabularyType additive = entity(name, null, "lock", AttributeSchema.NONE);
    String other = unique("other");

    assertThatThrownBy(
            () ->
                registry.apply(
                    "test-b", List.of(entity(other, null, null, AttributeSchema.NONE), additive)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'" + name + "' belongs to 'test-a'")
        .hasMessageContaining("'test-b' defines it differently")
        .hasMessageContaining("\"icon\":\"lock\"");
    assertThat(registry.current().revision()).isEqualTo(added);
    assertThat(registry.current().find(name)).contains(original);
    assertThat(registry.current().find(other)).isEmpty();
    assertThat(registry.owner(name)).contains("test-a");
  }

  @Test
  void anotherSourceMayRepeatADefinitionUnchanged() {
    String name = unique("vault");
    VocabularyType type = entity(name, null, null, AttributeSchema.NONE);
    long added = registry.apply("test-a", List.of(type));

    assertThat(registry.apply("test-b", List.of(type))).isEqualTo(added);
    assertThat(registry.current().revision()).isEqualTo(added);
    assertThat(registry.owner(name)).contains("test-a");
  }

  @Test
  void anotherSourceMayNotRedefineABuiltInType() {
    VocabularyType service = registry.current().find(BuiltInTypes.SERVICE).orElseThrow();
    VocabularyType described =
        new VocabularyType(
            service.name(),
            service.kind(),
            service.parent(),
            service.c4Level(),
            service.shape(),
            service.icon(),
            "A service, redefined",
            service.attributeSchema());
    long before = registry.current().revision();

    assertThatThrownBy(() -> registry.apply("test", List.of(described)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'service' belongs to 'built-in'");
    assertThat(registry.current().revision()).isEqualTo(before);
  }

  @Test
  void theAppsSourcesOwnTheirTypesAndLoadingThemAgainChangesNothing() {
    long before = registry.current().revision();

    FactStoreConfiguration.loadVocabulary(registry);

    assertThat(registry.current().revision()).isEqualTo(before);
    BuiltInTypes.definitions()
        .forEach(t -> assertThat(registry.owner(t.name())).contains(BuiltInTypes.SOURCE));
    CrossRepoLinks.vocabulary()
        .forEach(
            t -> assertThat(registry.owner(t.name())).contains(CrossRepoLinks.VOCABULARY_SOURCE));
    MessagingExtractor.vocabulary()
        .forEach(
            t ->
                assertThat(registry.owner(t.name()))
                    .contains(MessagingExtractor.VOCABULARY_SOURCE));
    WebSocketExtractor.vocabulary()
        .forEach(
            t ->
                assertThat(registry.owner(t.name()))
                    .contains(WebSocketExtractor.VOCABULARY_SOURCE));
  }
}
