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
    assertThat(answer(Role.SUMMARIZE, "[S1] part [F1] fact"))
        .isEqualTo(
            "{{placeholder: summary of the unit from its parts}}. [S1]"
                + " {{placeholder: description of the unit from its facts}}. [F1]");
    assertThat(
            answer(
                Role.SUMMARIZE,
                "Summarize the class Order in package a.b in 1 to 3 sentences and at most 50 words,"
                    + " covering x.\n[F1] fact"))
        .isEqualTo(
            "{{placeholder: description of class Order in package a.b from its facts}}. [F1]");
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
