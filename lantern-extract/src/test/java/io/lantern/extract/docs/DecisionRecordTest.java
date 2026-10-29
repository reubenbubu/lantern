package io.lantern.extract.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.extract.docs.DecisionRecord.Section;
import io.lantern.extract.docs.DecisionRecord.SectionText;
import io.lantern.extract.docs.DecisionRecord.Value;
import org.junit.jupiter.api.Test;

class DecisionRecordTest {

  private static DecisionRecord read(String name, String content) {
    return DecisionRecord.read(name, Markdown.parse(content));
  }

  @Test
  void numberAndTitleFromTheHeading() {
    DecisionRecord r = read("use-kafka.md", "# ADR 12: Use Kafka\n\nStatus: Accepted\n");

    assertThat(r.title).isEqualTo(new Value<>("Use Kafka", 1, 1));
    assertThat(r.number).isEqualTo(new Value<>(12L, 1, 1));
    assertThat(r.status).isEqualTo(new Value<>("Accepted", 3, 3));
  }

  @Test
  void numberFromTheFileNameFirst() {
    DecisionRecord r = read("0007-use-kafka.md", "# 3. Use Kafka\n\n## Status\n\n*Accepted*\n");

    assertThat(r.number).isEqualTo(new Value<>(7L, 0, 0));
    assertThat(r.title.value()).isEqualTo("Use Kafka");
    assertThat(r.status).isEqualTo(new Value<>("Accepted", 5, 5));
  }

  @Test
  void aNumberThatStartsAWordIsPartOfTheTitle() {
    DecisionRecord r = read("x.md", "# 2FA for admins\n\n## Decision\n\nYes.\n");

    assertThat(r.title.value()).isEqualTo("2FA for admins");
    assertThat(r.number).isNull();
  }

  @Test
  void dateFromADateNamedFile() {
    DecisionRecord r = read("2024-05-06-use-kafka.md", "## Decision\n\nKafka.\n");

    assertThat(r.number).isNull();
    assertThat(r.date).isEqualTo(new Value<>("2024-05-06", 0, 0));
    assertThat(r.title).isEqualTo(new Value<>("Use kafka", 0, 0));
    assertThat(r.sections).containsExactly(new SectionText(Section.OUTCOME, "Kafka.", 3, 3));
  }

  @Test
  void titleFromTheFileNameWithoutAHeading() {
    DecisionRecord r = read("0003-use-kafka.md", "Status: accepted\n");

    assertThat(r.title).isEqualTo(new Value<>("Use kafka", 0, 0));
  }

  @Test
  void notADecisionRecordWithoutStatusOrSections() {
    assertThat(read("0001-notes.md", "# Notes\n\nSome notes.\n")).isNull();
  }

  @Test
  void sectionKeepsItsSubsectionsAndMarkdown() {
    DecisionRecord r =
        read(
            "0001-x.md",
            """
            # X

            ## 1. Context:

            Why.


            ### Detail

            * one
            <!-- hidden -->
            * two

            ## Options
            """);

    assertThat(r.sections)
        .containsExactly(
            new SectionText(Section.CONTEXT, "Why.\n\n### Detail\n\n* one\n* two", 5, 12));
  }
}
