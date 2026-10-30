package io.lantern.extract.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pointcut reader, including expressions it cannot read. */
class AspectPointcutsTest {

  private static final AspectPointcuts.Names NAMES =
      new AspectPointcuts.Names() {
        @Override
        public String annotationType(String argument) {
          return argument.contains(".") ? argument : null;
        }

        @Override
        public String pointcut(String name) {
          return name.equals("secured") ? "@annotation(a.B)" : null;
        }
      };

  private static AspectPointcuts.Candidate candidate(String annotation) {
    TypeDecl type =
        new TypeDecl("com.x.Svc", "com.x", null, "class", null, List.of(), List.of(), List.of());
    List<Annotation> annotations =
        annotation == null ? List.of() : List.of(new Annotation(annotation, Map.of(), null));
    return new AspectPointcuts.Candidate(type, List.of(type), annotations, "run");
  }

  @Test
  void readsAnnotationExecutionAndNamedPointcuts() {
    assertThat(AspectPointcuts.selects("@annotation(a.B)", candidate("a.B"), NAMES)).isTrue();
    assertThat(AspectPointcuts.selects("@annotation(a.B)", candidate(null), NAMES)).isFalse();
    assertThat(AspectPointcuts.selects("execution(* com.x..*.run(..))", candidate(null), NAMES))
        .isTrue();
    assertThat(AspectPointcuts.selects("secured() && !within(com.y..*)", candidate("a.B"), NAMES))
        .isTrue();
    assertThat(AspectPointcuts.annotationTypes("secured()", NAMES)).containsExactly("a.B");
  }

  @Test
  void unknownDesignatorsSelectNothingAlone() {
    assertThat(AspectPointcuts.selects("args(..)", candidate(null), NAMES)).isFalse();
    assertThat(AspectPointcuts.selects("@annotation(a.B) && args(..)", candidate("a.B"), NAMES))
        .isTrue();
  }

  @Test
  void malformedExpressionsDoNotThrow() {
    for (String bad :
        List.of(
            "",
            "&&",
            "@annotation(",
            "execution((",
            "((a.B)",
            "!",
            "a.B ||",
            ")",
            "@annotation(a.B",
            "|| ||")) {
      assertThat(AspectPointcuts.selects(bad, candidate("a.B"), NAMES)).isFalse();
      AspectPointcuts.annotationTypes(bad, NAMES);
    }
    assertThat(AspectPointcuts.annotationTypes("@annotation(a.B) && (", NAMES))
        .containsExactly("a.B");
  }
}
