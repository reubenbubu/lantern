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
    // It answers in TOML, as Summarize asks since #872, reading the item's TOML.
    assertThat(
            summarize(
                "[item.I1]\nunit = \"package a.b\"\nsummaries = \'\'\'\n[S1] part\n\'\'\'\n"
                    + "facts = \'\'\'\n[F1] fact\n\'\'\'"))
        .isEqualTo(
            "[I1]\n[[I1.sentences]]\ntext = \"{{placeholder: summary of package a.b from its"
                + " parts}}.\"\ncites = [\"S1\"]\n[[I1.sentences]]\ntext = \"{{placeholder:"
                + " description of package a.b from its facts}}.\"\ncites = [\"F1\"]\n");
    // A type's source code is cited when it has no fact before it; a merge cites what its parts do.
    assertThat(
            summarize(
                "[item.I1]\nunit = \"type Order in package a.b\"\n[[item.I1.code]]\nref ="
                    + " \"E1\"\nrepo = \"r\""))
        .contains("cites = [\"E1\"]");
    assertThat(
            summarize(
                "[item.I1]\nunit = \"type Order\"\nparts = \'\'\'\nPart 1: It is. [F3, E1]\n\'\'\'"))
        .contains("cites = [\"F3\"]");
    assertThat(summarize("[item.I1]\nunit = \"type Order\"\n")).isEqualTo("[I1]\nomitted = true\n");
    // Each answer is one TOML document the role reads.
    assertThat(
            io.lantern.harness.Toml.read(summarize("[item.I1]\nunit = \"type Order\"\n"))
                .root()
                .path("I1")
                .path("omitted")
                .asBoolean())
        .isTrue();
    assertThat(answer(Role.INFER_INTENT, "[F1]")).isEqualTo("{}");
    assertThat(answer(Role.COMPOSE, "[F1]")).isEqualTo("{}");
    // It judges no flow significant, so documents draw their highest ranked, and titles each flow
    // it narrates, citing the item's first fact.
    String flows =
        "<items>\n<item id=\"I1\">\nIt starts when shop receives POST /orders.\n[F2] endpoint\n"
            + "Its steps, in order:\n  - shop to db\n</item>\n</items>";
    assertThat(
            fake.complete(
                    "fake-model",
                    LlmRequest.of(
                        Role.FLOW_SIGNIFICANCE,
                        "test",
                        "Task: choose the flows worth drawing. ...",
                        flows,
                        500))
                .text())
        .isEqualTo("{\"items\": {\"I1\": {\"omitted\": true}}}");
    assertThat(
            fake.complete(
                    "fake-model",
                    LlmRequest.of(Role.COMPOSE, "test", "Task: narrate flows. ...", flows, 500))
                .text())
        .isEqualTo(
            "{\"items\": {\"I1\": {\"title\": {\"text\": \"{{placeholder: scenario title}}\","
                + " \"cites\": [\"F2\"]}}}}");
    assertThat(answer(Role.RESOLVE, "[K1]")).isEqualTo("Done.");
  }

  /**
   * A Resolve conversation's first turn submits unknown for every item, citing the calling unit's
   * first fact and the first candidate's, so the checks accept it and the links stay open.
   */
  @Test
  void submitsUnknownForEveryOpenLinkCitingBothSides() {
    String prompt =
        "System map: ...\n<facts>\n</facts>\n\nThe calling unit: service:g:a\nIts facts that bear"
            + " on addresses:\n<facts>\n[F1] service:g:a deployable-unit \"spring-boot\" (a:pom.xml)\n"
            + "</facts>\n\n<items>\n<item id=\"I1\">\nOpen link: an HTTP call, GET /x\nCandidates:\n"
            + "[K1] service:g:b\n<facts>\n[F2] service:g:b deployable-unit \"spring-boot\" (b:pom.xml)\n"
            + "</facts>\n</item>\n<item id=\"I2\">\nOpen link: a message send, q\nCandidates:\n"
            + "[K1] service:g:c\n</item>\n</items>";
    LlmRequest request = LlmRequest.of(Role.RESOLVE, "test", "system", prompt, 500);

    var calls = FakeLlmConfiguration.toolCalls(request);

    assertThat(calls).singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("submit"));
    assertThat(calls.getFirst().input().toString())
        .isEqualTo(
            "{\"items\":{\"I1\":{\"pick\":\"unknown\",\"confidence\":\"low\","
                + "\"reason\":\""
                + FakeLlmConfiguration.RESOLVE_REASON
                + "\",\"cites\":[\"F1\",\"F2\"]},"
                + "\"I2\":{\"omitted\":true}}}");
    assertThat(FakeLlmConfiguration.resolve(request, "K1").getFirst().input().toString())
        .contains("\"pick\":\"K1\"");
    // Not for another role.
    assertThat(FakeLlmConfiguration.toolCalls(LlmRequest.of(Role.COMPOSE, "t", "s", prompt, 9)))
        .isEmpty();
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
  void nameGroupsGetsAPlaceholderNameResponsibilityAndIntentForEveryGroup() {
    String items =
        """
        <items>
        <item id="I1">
        Group group:system/domain/orders, a domain of the system
        <facts>
        [F1] group:system/domain/orders group-of "domain" (shop:pom.xml)
        </facts>
        Its lines to other groups and elements:
        [R1] to the group group:system/domain/billing (CALLS_HTTP)
        </item>
        <item id="I2">
        Group group:system/domain/empty, a domain of the system
        </item>
        </items>""";

    assertThat(
            fake.complete(
                    "fake-model",
                    LlmRequest.of(
                        Role.NAME_GROUPS, "test", "Rules.\n\nTask: name groups. More.", items, 500))
                .text())
        .isEqualTo(
            "{\"items\": {\"I1\": {\"name\": {\"text\": \"{{placeholder: group name}}\","
                + " \"cites\": [\"F1\"]}, \"responsibility\": {\"text\":"
                + " \""
                + FakeLlmConfiguration.GROUP_RESPONSIBILITY
                + "\", \"cites\": [\"F1\"]},"
                + " \"lines\": {\"R1\": {\"text\": \""
                + FakeLlmConfiguration.INTENT
                + "\","
                + " \"cites\": [\"F1\"]}}}, \"I2\": {\"omitted\": true}}}");
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
            "{\"items\": {\"I1\": {\"unit\": {\"text\": \""
                + FakeLlmConfiguration.RESPONSIBILITY
                + "\","
                + " \"cites\": [\"F1\"]}, \"components\": {\"C1\": {\"text\":"
                + " \""
                + FakeLlmConfiguration.RESPONSIBILITY
                + "\", \"cites\": [\"F1\"]}},"
                + " \"interactions\": {\"R1\": {\"text\": \""
                + FakeLlmConfiguration.INTENT
                + "\","
                + " \"cites\": [\"F1\"]}}}, \"I2\": {}, \"I3\": {\"omitted\": true}}}");
    assertThat(
            describe(
                "Task: describe the elements the units use. More.",
                "<items>\n<item id=\"I1\">\nThe topic orders.\n[F4] fact\n</item>\n</items>"))
        .isEqualTo(
            "{\"items\": {\"I1\": {\"responsibility\": {\"text\":"
                + " \""
                + FakeLlmConfiguration.RESPONSIBILITY
                + "\", \"cites\": [\"F4\"]}}}}");
  }
}
