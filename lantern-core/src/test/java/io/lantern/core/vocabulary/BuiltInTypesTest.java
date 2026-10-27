package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
      if (type.kind() == TypeKind.ENTITY) {
        assertThat(vocabulary.c4Level(type.name())).as(type.name()).isPresent();
      }
      assertThat(vocabulary.shape(type.name())).as(type.name()).isPresent();
      assertThat(type.description()).as(type.name()).isNotNull();
    }
  }

  @Test
  void builtInTypesHaveTheExpectedParents() {
    Vocabulary vocabulary = new Vocabulary(1, BuiltInTypes.definitions());

    assertThat(vocabulary.isA(BuiltInTypes.SERVICE, BuiltInTypes.CONTAINER)).isTrue();
    assertThat(vocabulary.isA(BuiltInTypes.LIBRARY, BuiltInTypes.CONTAINER)).isTrue();
    assertThat(vocabulary.isA(BuiltInTypes.MODULE, BuiltInTypes.COMPONENT)).isTrue();
    assertThat(vocabulary.isA(BuiltInTypes.PACKAGE, BuiltInTypes.COMPONENT)).isTrue();
    assertThat(vocabulary.isA(BuiltInTypes.CALLS, BuiltInTypes.DEPENDS_ON)).isTrue();
    assertThat(vocabulary.c4Level(BuiltInTypes.TYPE)).contains(C4Level.CODE);
    assertThat(vocabulary.c4Level(BuiltInTypes.SYSTEM)).contains(C4Level.CONTEXT);
  }
}
