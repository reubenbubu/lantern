package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a repair turn says is wrong with an unreadable answer (#685). */
class JsonFaultsTest {

  @Test
  void aStrayClosingBraceIsNamed() {
    assertThat(JsonFaults.of("{\"items\": {\"I1\": {\"sentences\": []}}}}", "I1"))
        .isEqualTo(
            "a complete object is followed by more text, }; close each bracket once and write"
                + " nothing after the object");
  }

  @Test
  void anEntryKeyedByTheUnitsNameIsNamed() {
    assertThat(JsonFaults.of("{\"items\": {\"com.acme.orders\": {}}}", "I1"))
        .isEqualTo(
            "it has no entry \"I1\" under \"items\"; key each entry by its item ID, not by"
                + " com.acme.orders");
  }

  @Test
  void plainTextAnUnclosedObjectAndAFenceAreTold() {
    assertThat(JsonFaults.of("Issues tokens. [F1]", "I1")).startsWith("it starts with text");
    assertThat(JsonFaults.of("{\"items\": {\"I1\": {", "I1"))
        .startsWith("the object is not closed");
    assertThat(JsonFaults.of("```json\n{\"items\": {\"I1\": {}}}\n```", "I1")).isNull();
    assertThat(JsonFaults.of("{\"items\": {\"I1\": {\"a\": \"}\"}}}", "I1")).isNull();
  }

  @Test
  void theFirstObjectIsReadAndWhatFollowsItIsKept() {
    // A brace inside a string does not close the object (#744).
    JsonFaults.First braces = JsonFaults.first("{\"a\": \"} {\"} trailing words");
    assertThat(braces.object().path("a").asString()).isEqualTo("} {");
    assertThat(braces.rest()).isEqualTo("trailing words");
    // Of two objects, the first is read and the second is what follows.
    JsonFaults.First two = JsonFaults.first("{\"a\": 1}\n{\"b\": 2}");
    assertThat(two.object().path("a").asInt()).isEqualTo(1);
    assertThat(two.rest()).isEqualTo("{\"b\": 2}");
    // In a fence, the object is read, and text after the fence is what follows.
    JsonFaults.First fenced = JsonFaults.first("```json\n{\"a\": [1, 2]}\n```\nHope this helps.");
    assertThat(fenced.object().path("a").size()).isEqualTo(2);
    assertThat(fenced.rest()).isEqualTo("Hope this helps.");
    assertThat(JsonFaults.first("```json\n{\"a\": 1}\n```").rest()).isEmpty();
    // Text before the object, an unclosed object or an array is not an answer.
    assertThat(JsonFaults.first("Here it is: {\"a\": 1}")).isNull();
    assertThat(JsonFaults.first("{\"a\": {\"b\": 1}")).isNull();
    assertThat(JsonFaults.first("[{\"a\": 1}]")).isNull();
  }
}
