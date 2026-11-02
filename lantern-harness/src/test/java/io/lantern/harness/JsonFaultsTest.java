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
}
