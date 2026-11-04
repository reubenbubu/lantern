package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.types.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltInTypesTest {

  @Test
  void builtInTypesFormAConsistentVocabulary() {
    List<VocabularyType> types = BuiltInTypes.definitions();
    Vocabulary vocabulary = new Vocabulary(1, types);

    assertThat(vocabulary.types()).hasSameSizeAs(types);
    for (VocabularyType type : types) {
      // A repository is never drawn; it came from the Redis extractor's types unchanged (#518).
      if (!type.name().equals(Types.REPO)) {
        assertThat(vocabulary.shape(type.name())).as(type.name()).isPresent();
      }
      assertThat(type.description()).as(type.name()).isNotNull();
    }
  }

  @Test
  void builtInTypesHaveTheExpectedParents() {
    Vocabulary vocabulary = new Vocabulary(1, BuiltInTypes.definitions());

    assertThat(vocabulary.isA(Types.SERVICE, Types.CONTAINER)).isTrue();
    assertThat(vocabulary.find(Types.LIBRARY).orElseThrow().parent()).isNull();
    assertThat(vocabulary.find(Types.MODULE).orElseThrow().parent()).isNull();
    assertThat(vocabulary.find(Types.PACKAGE).orElseThrow().parent()).isNull();
    assertThat(vocabulary.isA(Types.CALLS, Types.INTERACTS_WITH)).isTrue();
    assertThat(vocabulary.isA(Types.CALLS, Types.DEPENDS_ON)).isFalse();
    assertThat(vocabulary.find(Types.INTERACTS_WITH).orElseThrow().parent()).isNull();
    assertThat(vocabulary.find(Types.DEPENDS_ON).orElseThrow().parent()).isNull();
    assertThat(vocabulary.find(Types.ACTOR).orElseThrow().parent()).isNull();
    assertThat(vocabulary.c4Level(Types.ACTOR)).contains(C4Level.CONTEXT);
    assertThat(vocabulary.c4Level(Types.TYPE)).contains(C4Level.CODE);
    assertThat(vocabulary.c4Level(Types.SYSTEM)).contains(C4Level.CONTEXT);
  }

  /** Only a deployable unit is a container; libraries, modules and packages have no C4 level. */
  @Test
  void onlyTheC4TypesCarryAC4Level() {
    Vocabulary vocabulary = new Vocabulary(1, BuiltInTypes.definitions());

    assertThat(vocabulary.c4Level(Types.SYSTEM)).contains(C4Level.CONTEXT);
    assertThat(vocabulary.c4Level(Types.CONTAINER)).contains(C4Level.CONTAINER);
    assertThat(vocabulary.c4Level(Types.SERVICE)).contains(C4Level.CONTAINER);
    assertThat(vocabulary.c4Level(Types.COMPONENT)).contains(C4Level.COMPONENT);
    assertThat(vocabulary.c4Level(Types.TYPE)).contains(C4Level.CODE);
    assertThat(vocabulary.c4Level(Types.LIBRARY)).isEmpty();
    assertThat(vocabulary.c4Level(Types.MODULE)).isEmpty();
    assertThat(vocabulary.c4Level(Types.PACKAGE)).isEmpty();
    assertThat(vocabulary.shape(Types.LIBRARY)).contains("box");
    assertThat(vocabulary.shape(Types.MODULE)).contains("box");
    assertThat(vocabulary.shape(Types.PACKAGE)).contains("folder");
  }

  /**
   * A vocabulary that holds the built-in types as they were before #90 takes the new ones as one
   * ordinary revision: no alias is needed, and nothing but the three types changes.
   */
  @Test
  void theNewBuiltInTypesReplaceTheOldOnesInOneStep() {
    List<VocabularyType> current = BuiltInTypes.definitions();
    List<VocabularyType> old = new ArrayList<>();
    for (VocabularyType type : current) {
      String parent =
          switch (type.name()) {
            case Types.LIBRARY -> Types.CONTAINER;
            case Types.MODULE, Types.PACKAGE -> Types.COMPONENT;
            default -> type.parent();
          };
      old.add(
          new VocabularyType(
              type.name(),
              type.kind(),
              parent,
              type.c4Level(),
              type.shape(),
              type.icon(),
              type.description(),
              type.attributeSchema()));
    }
    Vocabulary before = new Vocabulary(1, old);

    assertThat(before.changes(current))
        .extracting(VocabularyType::name)
        .containsExactlyInAnyOrder(Types.LIBRARY, Types.MODULE, Types.PACKAGE);
    assertThat(before.c4Level(Types.LIBRARY)).contains(C4Level.CONTAINER);
  }
}
