package io.lantern.harness.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The tagged blocks of a request (knowledge-build epic, section 11.0). */
class BlocksTest {

  @Test
  void codeShowsItsReferenceFileAndNumberedLines() {
    assertThat(Blocks.code(Refs.token("E1"), "shop", "src/A.java", 9, "class A {\n  int a;\n}\n"))
        .isEqualTo(
            "<code ref=\""
                + Refs.token("E1")
                + "\" repo=\"shop\" path=\"src/A.java\" lines=\"9-11\">\n"
                + " 9  class A {\n"
                + "10    int a;\n"
                + "11  }\n"
                + "</code>");
  }

  @Test
  void aTaggedBlockHoldsItsLines() {
    assertThat(Blocks.tagged("facts", List.of("[F1] a", "[F2] b")))
        .isEqualTo("<facts>\n[F1] a\n[F2] b\n</facts>");
  }
}
