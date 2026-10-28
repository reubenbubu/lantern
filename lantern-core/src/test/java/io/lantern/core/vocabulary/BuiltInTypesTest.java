package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuiltInTypesTest {

  @Test
  void constantsNameExactlyTheBuiltInTypes() throws IllegalAccessException {
    Set<String> constants = new HashSet<>();
    for (Field field : BuiltInTypes.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers())
          && field.getType() == String.class
          && !field.getName().equals("SOURCE")) {
        constants.add((String) field.get(null));
      }
    }

    assertThat(constants)
        .isEqualTo(
            Set.copyOf(BuiltInTypes.definitions().stream().map(VocabularyType::name).toList()));
  }

  @Test
  void builtInTypesFormAConsistentVocabulary() {
    List<VocabularyType> types = BuiltInTypes.definitions();
    Vocabulary vocabulary = new Vocabulary(1, types);

    assertThat(vocabulary.types()).hasSameSizeAs(types);
    for (VocabularyType type : types) {
      assertThat(vocabulary.shape(type.name())).as(type.name()).isPresent();
      assertThat(type.description()).as(type.name()).isNotNull();
    }
  }

  @Test
  void builtInTypesHaveTheExpectedParents() {
    Vocabulary vocabulary = new Vocabulary(1, BuiltInTypes.definitions());

    assertThat(vocabulary.isA(BuiltInTypes.SERVICE, BuiltInTypes.CONTAINER)).isTrue();
    assertThat(vocabulary.find(BuiltInTypes.LIBRARY).orElseThrow().parent()).isNull();
    assertThat(vocabulary.find(BuiltInTypes.MODULE).orElseThrow().parent()).isNull();
    assertThat(vocabulary.find(BuiltInTypes.PACKAGE).orElseThrow().parent()).isNull();
    assertThat(vocabulary.isA(BuiltInTypes.CALLS, BuiltInTypes.DEPENDS_ON)).isTrue();
    assertThat(vocabulary.c4Level(BuiltInTypes.TYPE)).contains(C4Level.CODE);
    assertThat(vocabulary.c4Level(BuiltInTypes.SYSTEM)).contains(C4Level.CONTEXT);
  }

  /** Only a deployable unit is a container; libraries, modules and packages have no C4 level. */
  @Test
  void onlyTheC4TypesCarryAC4Level() {
    Vocabulary vocabulary = new Vocabulary(1, BuiltInTypes.definitions());

    assertThat(vocabulary.c4Level(BuiltInTypes.SYSTEM)).contains(C4Level.CONTEXT);
    assertThat(vocabulary.c4Level(BuiltInTypes.CONTAINER)).contains(C4Level.CONTAINER);
    assertThat(vocabulary.c4Level(BuiltInTypes.SERVICE)).contains(C4Level.CONTAINER);
    assertThat(vocabulary.c4Level(BuiltInTypes.COMPONENT)).contains(C4Level.COMPONENT);
    assertThat(vocabulary.c4Level(BuiltInTypes.TYPE)).contains(C4Level.CODE);
    assertThat(vocabulary.c4Level(BuiltInTypes.LIBRARY)).isEmpty();
    assertThat(vocabulary.c4Level(BuiltInTypes.MODULE)).isEmpty();
    assertThat(vocabulary.c4Level(BuiltInTypes.PACKAGE)).isEmpty();
    assertThat(vocabulary.shape(BuiltInTypes.LIBRARY)).contains("box");
    assertThat(vocabulary.shape(BuiltInTypes.MODULE)).contains("box");
    assertThat(vocabulary.shape(BuiltInTypes.PACKAGE)).contains("folder");
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
            case BuiltInTypes.LIBRARY -> BuiltInTypes.CONTAINER;
            case BuiltInTypes.MODULE, BuiltInTypes.PACKAGE -> BuiltInTypes.COMPONENT;
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
        .containsExactlyInAnyOrder(BuiltInTypes.LIBRARY, BuiltInTypes.MODULE, BuiltInTypes.PACKAGE);
    assertThat(before.c4Level(BuiltInTypes.LIBRARY)).contains(C4Level.CONTAINER);
  }
}
