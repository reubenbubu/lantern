package io.lantern.extract.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.extract.docs.Markdown.Block;
import io.lantern.extract.docs.Markdown.Field;
import io.lantern.extract.docs.Markdown.Kind;
import org.junit.jupiter.api.Test;

class MarkdownTest {

  @Test
  void blocksWithLines() {
    Markdown doc =
        Markdown.parse(
            """
            Title
            =====

            First line
            second line.

            ## Section ##
            - one
              continued
            2. two
            > quoted
            > more

            | A | B |
            |---|---|
            | 1 | 2 |
            """);

    assertThat(doc.blocks)
        .containsExactly(
            new Block(Kind.HEADING, 1, "Title", 1, 2),
            new Block(Kind.PARAGRAPH, 0, "First line second line.", 4, 5),
            new Block(Kind.HEADING, 2, "Section", 7, 7),
            new Block(Kind.LIST_ITEM, 0, "one continued", 8, 9),
            new Block(Kind.LIST_ITEM, 0, "two", 10, 10),
            new Block(Kind.QUOTE, 0, "quoted more", 11, 12),
            new Block(Kind.TABLE_ROW, 0, "A: 1; B: 2", 16, 16));
  }

  @Test
  void codeAndCommentsAreNotText() {
    Markdown doc =
        Markdown.parse(
            """
            ```
            # not a heading
            ```
            <!-- a comment
            # still a comment -->
            ~~~~
            unclosed
            """);

    assertThat(doc.blocks)
        .containsExactly(new Block(Kind.CODE, 0, "", 1, 3), new Block(Kind.CODE, 0, "", 6, 7));
    assertThat(doc.skipped(4)).isTrue();
    assertThat(doc.skipped(5)).isTrue();
    assertThat(doc.skipped(6)).isFalse();
  }

  @Test
  void frontMatter() {
    Markdown doc =
        Markdown.parse(
            """
            ---
            status: 'accepted'
            Date: 2024-01-02
            empty:
            ---
            # Title
            """);

    assertThat(doc.frontMatter)
        .containsOnly(
            java.util.Map.entry("status", new Field("accepted", 2)),
            java.util.Map.entry("date", new Field("2024-01-02", 3)));
    assertThat(doc.blocks).containsExactly(new Block(Kind.HEADING, 1, "Title", 6, 6));
    assertThat(doc.skipped(1)).isTrue();
    assertThat(doc.skipped(6)).isFalse();
  }

  @Test
  void thematicBreakIsNotFrontMatterWithoutAnEnd() {
    Markdown doc = Markdown.parse("---\nText\n");

    assertThat(doc.frontMatter).isEmpty();
    assertThat(doc.blocks).containsExactly(new Block(Kind.PARAGRAPH, 0, "Text", 2, 2));
  }

  @Test
  void inlineCodeWithThreeBackticksIsNotAFence() {
    Markdown doc = Markdown.parse("```foo``` is code.\n\nNext.\n");

    assertThat(doc.blocks)
        .containsExactly(
            new Block(Kind.PARAGRAPH, 0, "```foo``` is code.", 1, 1),
            new Block(Kind.PARAGRAPH, 0, "Next.", 3, 3));
  }

  @Test
  void aLeadingThematicBreakIsNotFrontMatter() {
    Markdown doc = Markdown.parse("---\nIntro text.\n\n---\n\nMore.\n");

    assertThat(doc.frontMatter).isEmpty();
    assertThat(doc.skipped(2)).isFalse();
    assertThat(doc.blocks)
        .containsExactly(
            new Block(Kind.PARAGRAPH, 0, "Intro text.", 2, 2),
            new Block(Kind.PARAGRAPH, 0, "More.", 6, 6));
  }

  @Test
  void frontMatterWithNestedValues() {
    Markdown doc =
        Markdown.parse("---\nstatus: accepted\n# a comment\ndeciders:\n  - Ann\n---\n# T\n");

    assertThat(doc.frontMatter).containsOnlyKeys("status");
    assertThat(doc.blocks).containsExactly(new Block(Kind.HEADING, 1, "T", 7, 7));
  }

  @Test
  void windowsLineEndings() {
    Markdown doc = Markdown.parse("# A\r\n\r\nB\r\n");

    assertThat(doc.blocks)
        .containsExactly(
            new Block(Kind.HEADING, 1, "A", 1, 1), new Block(Kind.PARAGRAPH, 0, "B", 3, 3));
  }
}
