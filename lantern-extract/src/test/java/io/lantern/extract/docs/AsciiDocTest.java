package io.lantern.extract.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.extract.docs.Markdown.Block;
import io.lantern.extract.docs.Markdown.Field;
import io.lantern.extract.docs.Markdown.Kind;
import org.junit.jupiter.api.Test;

class AsciiDocTest {

  @Test
  void blocksWithLines() {
    Markdown doc =
        AsciiDoc.parse(
            """
            = Payments
            :status: Accepted
            // a comment

            First line
            second line.

            == Modules ==
            * one
            continued
            . two
            Provider:: Stripe

            ____
            Quoted.
            ____
            """);

    assertThat(doc.frontMatter).containsEntry("status", new Field("Accepted", 2));
    assertThat(doc.skipped(3)).isTrue();
    assertThat(doc.blocks)
        .containsExactly(
            new Block(Kind.HEADING, 1, "Payments", 1, 1),
            new Block(Kind.PARAGRAPH, 0, "First line second line.", 5, 6),
            new Block(Kind.HEADING, 2, "Modules", 8, 8),
            new Block(Kind.LIST_ITEM, 0, "one continued", 9, 10),
            new Block(Kind.LIST_ITEM, 0, "two", 11, 11),
            new Block(Kind.LIST_ITEM, 0, "Provider: Stripe", 12, 12),
            new Block(Kind.PARAGRAPH, 0, "Quoted.", 15, 15));
  }

  @Test
  void listingsAreCodeOrDiagrams() {
    Markdown doc =
        AsciiDoc.parse(
            """
            .Building
            [source,bash]
            ----
            ./mvnw verify
            ----

            [mermaid]
            ....
            flowchart LR
              a --> b
            ....

            ////
            Not a claim.
            ////
            ++++
            <div>raw</div>
            ++++
            include::other.adoc[]
            """);

    assertThat(doc.blocks)
        .containsExactly(
            new Block(Kind.CODE, 0, "", 3, 5),
            new Block(Kind.DIAGRAM, 0, "flowchart LR\n  a --> b", 8, 11, "mermaid"),
            new Block(Kind.HTML, 0, "", 16, 18));
    assertThat(doc.skipped(13)).isTrue();
    assertThat(doc.skipped(14)).isTrue();
  }

  @Test
  void tables() {
    Markdown doc =
        AsciiDoc.parse(
            """
            |===
            |Module |Database

            |orders
            |PostgreSQL
            |payments |MySQL
            |===

            [cols="1,1"]
            |===
            |a |b
            |c |d
            |===
            """);

    assertThat(doc.blocks)
        .containsExactly(
            new Block(Kind.TABLE_ROW, 0, "Module: orders; Database: PostgreSQL", 4, 5),
            new Block(Kind.TABLE_ROW, 0, "Module: payments; Database: MySQL", 6, 6),
            new Block(Kind.TABLE_ROW, 0, "a | b", 11, 11),
            new Block(Kind.TABLE_ROW, 0, "c | d", 12, 12));
  }
}
