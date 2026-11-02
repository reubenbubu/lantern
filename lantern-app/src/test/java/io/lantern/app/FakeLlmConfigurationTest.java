package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import org.junit.jupiter.api.Test;

class FakeLlmConfigurationTest {

  private final FakeLlmProvider fake = new FakeLlmConfiguration().fakeLlmProvider();

  private String answer(Role role, String prompt) {
    return fake.complete("fake-model", LlmRequest.of(role, "test", "system", prompt, 500)).text();
  }

  @Test
  void citesWhatItIsGivenAndWordsNothing() {
    assertThat(fake.name()).isEqualTo(FakeLlmProvider.NAME);
    assertThat(summarize("Unit: package a.b\n<summaries>\n[S1] part\n</summaries>\n[F1] fact"))
        .isEqualTo(
            "{\"items\": {\"I1\": {\"sentences\": [{\"text\": \"{{placeholder: summary of package"
                + " a.b from its parts}}.\", \"cites\": [\"S1\"]}, {\"text\": \"{{placeholder:"
                + " description of package a.b from its facts}}.\", \"cites\": [\"F1\"]}]}}}");
    // A type's source code is cited when it has no fact before it; a merge cites what its parts do.
    assertThat(summarize("Unit: type Order in package a.b\n<code ref=\"E1\" repo=\"r\">"))
        .contains("\"cites\": [\"E1\"]");
    assertThat(summarize("Unit: type Order\n<summaries>\nPart 1: It is. [F3, E1]\n</summaries>"))
        .contains("\"cites\": [\"F3\"]");
    assertThat(summarize("Unit: type Order\n"))
        .isEqualTo("{\"items\": {\"I1\": {\"omitted\": true}}}");
    assertThat(answer(Role.INFER_INTENT, "[F1]")).isEqualTo("{}");
    assertThat(answer(Role.COMPOSE, "[F1]")).isEqualTo("{}");
    assertThat(
            answer(
                Role.COMPOSE,
                "The flow \"POST /orders on shop\".\nIt starts when shop receives POST /orders.\n"
                    + "[F1] endpoint\n\nIts steps, in order:\n- shop to db\n"))
        .isEqualTo(
            "{\"title\": {\"text\": \"{{placeholder: scenario title}}\", \"cites\": [\"F1\"]}}");
    assertThat(answer(Role.RESOLVE, "[C1]")).contains("\"target\": \"unknown\"");
  }

  /** The fake's answer to a Summarize request whose item holds the text. */
  private String summarize(String item) {
    return fake.complete(
            "fake-model",
            LlmRequest.of(
                Role.SUMMARIZE_TYPES,
                "summarize",
                "Rules.\n\nTask: summarise code units.",
                "<items>\n<item id=\"I1\">\n" + item + "\n</item>\n</items>",
                500))
        .text();
  }

  private String describe(String task, String items) {
    return fake.complete(
            "fake-model", LlmRequest.of(Role.INFER_INTENT, "test", "Rules.\n\n" + task, items, 500))
        .text();
  }

  @Test
  void describeGetsAPlaceholderForEveryUnitComponentInteractionAndElement() {
    String units =
        """
        <items>
        <item id="I1">
        The deployable unit shop.
        <facts>
        [F1] service:a:shop deployable-unit "spring-boot" (shop:pom.xml)
        </facts>
        Components:
        [C1] Orders
        Interactions:
        [R1] calls pay (an external system)
        <facts>
        [F2] service:a:shop CALLS_HTTP external-system:pay (shop:pom.xml)
        </facts>
        </item>
        <item id="I2">
        The deployable unit shop.
        Part 2 of the unit: say nothing about the unit itself.
        <facts>
        [F3] service:a:shop deployable-unit "spring-boot" (shop:pom.xml)
        </facts>
        </item>
        <item id="I3">
        The deployable unit empty.
        </item>
        </items>""";

    assertThat(describe("Task: describe deployable units. More.", units))
        .isEqualTo(
            "{\"items\": {\"I1\": {\"unit\": {\"text\": \"{{placeholder: responsibility}}\","
                + " \"cites\": [\"F1\"]}, \"components\": {\"C1\": {\"text\":"
                + " \"{{placeholder: responsibility}}\", \"cites\": [\"F1\"]}},"
                + " \"interactions\": {\"R1\": {\"text\": \"{{placeholder: intent}}\","
                + " \"cites\": [\"F1\"]}}}, \"I2\": {}, \"I3\": {\"omitted\": true}}}");
    assertThat(
            describe(
                "Task: describe the elements the units use. More.",
                "<items>\n<item id=\"I1\">\nThe topic orders.\n[F4] fact\n</item>\n</items>"))
        .isEqualTo(
            "{\"items\": {\"I1\": {\"responsibility\": {\"text\":"
                + " \"{{placeholder: responsibility}}\", \"cites\": [\"F4\"]}}}}");
  }
}
