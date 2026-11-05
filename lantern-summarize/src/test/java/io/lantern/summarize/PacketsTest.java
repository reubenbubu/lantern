package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.Toml;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Summarize's type requests joined into one and the answer split back (#874). */
class PacketsTest {

  private static final String FIRST =
      Prompt.items(
          """
          [item.I1]
          unit = "type A in package p"
          ask = "Write 2 to 4 sentences of 10 to 30 words each."
          facts = '''
          @ r:A.java
          A
            [F1] kind "class"
            [F2] CONTAINS method run()
          '''
          [[item.I1.code]]
          ref = "E1"
          repo = "r"
          path = "A.java"
          lines = "1-1"
          text = '''
          1  class A { /* [F1] */ }'''
          """);

  private static final String SECOND =
      Prompt.items(
          """
          [item.I1]
          unit = "type B in package p"
          ask = "Write 1 to 3 sentences. It contains only one unit [S1]: say what it adds."
          facts = '''
          @ r:B.java
          B
            [F1] kind "record"
          '''
          summaries = '''
          [S1] type B.Inner: Holds a value.
          '''
          """);

  @Test
  void itemsAreRenamedAndTheirReferencesNumberedAfterTheOnesBefore() {
    String joined = Packets.INSTANCE.join(List.of(FIRST, SECOND));

    JsonNode items = Toml.read(joined.replace("<items>", "").replace("</items>", "")).root();
    assertThat(items.path("item").path("I1").path("facts").asString())
        .contains("[F1] kind \"class\"")
        .contains("[F2] CONTAINS method run()");
    assertThat(items.path("item").path("I1").path("code").get(0).path("ref").asString())
        .isEqualTo("E1");
    // Code text is left as it is.
    assertThat(items.path("item").path("I1").path("code").get(0).path("text").asString())
        .contains("/* [F1] */");
    assertThat(items.path("item").path("I2").path("facts").asString())
        .contains("[F3] kind \"record\"");
    assertThat(items.path("item").path("I2").path("summaries").asString()).contains("[S1] type");
    assertThat(items.path("item").path("I2").path("ask").asString()).contains("[S1]");
    assertThat(joined).startsWith("<items>\n[item.I1]").endsWith("\n</items>");
  }

  @Test
  void theAnswerIsSplitIntoEachItemsOwnAnswerWithItsOwnReferences() {
    String answer =
        """
        [I1]
        [[I1.sentences]]
        text = "Runs the job."
        cites = ["F2", "E1"]

        [I2]
        [[I2.sentences]]
        text = "Holds a value."
        cites = ["F3", "S1", "F1"]
        """;

    List<String> parts = Packets.INSTANCE.split(answer, List.of(FIRST, SECOND));

    JsonNode first = Toml.read(parts.get(0)).root().path("I1").path("sentences").get(0);
    assertThat(first.path("cites").get(0).asString()).isEqualTo("F2");
    assertThat(first.path("cites").get(1).asString()).isEqualTo("E1");
    JsonNode second = Toml.read(parts.get(1)).root().path("I1").path("sentences").get(0);
    assertThat(second.path("text").asString()).isEqualTo("Holds a value.");
    assertThat(second.path("cites").get(0).asString()).isEqualTo("F1");
    assertThat(second.path("cites").get(1).asString()).isEqualTo("S1");
    // A reference of another item is marked, so the checks reject it.
    assertThat(second.path("cites").get(2).asString()).isEqualTo("F1 (another item's)");
  }

  @Test
  void anItemLeftOutOrUnreadableIsAskedAlone() {
    String broken =
        """
        [I1]
        [[I1.sentences]]
        text = "Runs the "job"."
        cites = ["F1"]
        [I2]
        omitted = true
        """;

    List<String> parts = Packets.INSTANCE.split(broken, List.of(FIRST, SECOND, FIRST));

    assertThat(parts.get(0)).isNull();
    assertThat(Toml.read(parts.get(1)).root().path("I1").path("omitted").asBoolean()).isTrue();
    assertThat(parts.get(2)).isNull();
  }

  @Test
  void aMergeIsNotPacked() {
    assertThat(Packets.INSTANCE.packs(FIRST)).isTrue();
    assertThat(Packets.INSTANCE.packs(Prompt.items("[item.I1]\nparts = \"x\"\n"))).isFalse();
  }
}
