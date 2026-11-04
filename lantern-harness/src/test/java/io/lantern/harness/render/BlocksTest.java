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

  @Test
  void textIsOutsideTheDataBlocksOnlyWhereNoBlockHoldsIt() {
    String prompt =
        "Say: AI here.\n<facts>\n[F1] AI here\n</facts>\n<items>\n<item id=\"I1\">\n"
            + "<code ref=\"E1\" repo=\"r\" path=\"p\" lines=\"1-1\">\n1  AI here\n</code>\n"
            + "AI here</item>\n</items>\nAI here, after.";

    assertThat(Blocks.outsideData(prompt, "AI here"))
        .containsExactly(prompt.indexOf("AI here"), prompt.lastIndexOf("AI here"));
    assertThat(Blocks.outsideData("<facts>\nAI here\n</facts>", "AI here")).isEmpty();
    // A tag-like word is no block.
    assertThat(Blocks.outsideData("<factsheet>\nAI here", "AI here")).hasSize(1);
  }
}
