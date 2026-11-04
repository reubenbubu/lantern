package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.harness.render.Refs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Keeps the prompt's version in step with its text, so cached answers are not reused wrongly. */
class ResolvePromptTest {

  /**
   * The fingerprint of the prompt texts for {@link ResolvePrompt#VERSION}. When this fails, the
   * prompt changed: raise the version, then update the fingerprint and the version here. The way
   * facts and candidates are written into the prompt is checked by {@code LinkResolverTest}.
   */
  @Test
  void theVersionIsRaisedWhenThePromptChanges() throws Exception {
    Fact unresolved = unresolved();
    Map<EntityId, ResolvePrompt.Shown> candidates = new LinkedHashMap<>();
    candidates.put(
        EntityId.parse("service:g:tax"),
        new ResolvePrompt.Shown(List.of(), List.of(), Set.of("t")));
    ResolvePrompt.Question question =
        ResolvePrompt.question(
            unresolved,
            unresolved.subject(),
            Set.of("a"),
            List.of(unresolved),
            List.of(),
            candidates);
    String texts =
        CommonPrompt.SYSTEM
            + "\n--\n"
            + ResolvePrompt.ROLE
            + "\n--\n"
            + Refs.plain(question.rendering())
            + "\n--\n"
            + Refs.plain(
                ResolvePrompt.packet(unresolved.subject(), new Refs(), List.of(unresolved)))
            + "\n--\n"
            + ResolvePrompt.systemMap(
                List.of("service:g:a"), List.of("service:g:a -> service:g:b [HTTP]"))
            + "\n--\n"
            + LinkResolver.MAX_OUTPUT_TOKENS
            + " "
            + LinkResolver.MAX_CANDIDATE_FACTS
            + " "
            + LinkResolver.PACKET_SIZE
            + " "
            // The size the prompt asks for; what the check accepts is the contract's, not part of
            // the prompt (ADR 0118).
            + io.lantern.harness.Contract.of("resolve").requestedWords("reason")
            + "\n--\n"
            + new GraphTools.Graph(List.of(), (repo, path) -> java.util.Optional.empty())
                .tools()
                .tools()
            + "\n--\n"
            + GraphTools.MAX_FACTS
            + " "
            + GraphTools.MAX_LINES
            + " "
            + GraphTools.MAX_CHARS
            + " "
            + GraphTools.MAX_HITS;
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(ResolvePrompt.VERSION + " " + fingerprint)
        .as("The prompt changed: raise ResolvePrompt.VERSION and update this fingerprint")
        .isEqualTo("8 f0bd9dfae7af39be88757e36727c8ea71272da141604a5d38555f7c8826da8db");
  }

  /**
   * Block 2 and the submit schema are the epic's, character for character (knowledge-build epic,
   * section 11.1), after the common system prompt of section 11.0.
   */
  @Test
  void theResolvePromptIsTheEpics() throws Exception {
    String epic = Files.readString(Path.of("../docs/epics/001-knowledge-build.md"));
    String section = epic.substring(epic.indexOf("### 11.1 Resolve"), epic.indexOf("### 11.2"));

    assertThat(section)
        .contains("```text\n" + ResolvePrompt.TASK + "\n```")
        .contains("```json\n" + ResolvePrompt.SCHEMA + "\n```");
    assertThat(ResolvePrompt.TASK).startsWith("Task: resolve open links.");
    assertThat(ResolvePrompt.ROLE)
        .isEqualTo(ResolvePrompt.TASK + "\n\nAnswer schema:\n" + ResolvePrompt.SCHEMA);
    // The submit tool takes the schema as written.
    assertThat(ResolvePrompt.SUBMIT_SCHEMA).isEqualTo(FactJson.parse(ResolvePrompt.SCHEMA));
  }

  /**
   * Candidates are shown by unit ID, not in the order the linker ranks them, since models tend to
   * favour the first option (#199). Unit IDs are stable, so the cache key stays free of fact IDs.
   */
  @Test
  void candidatesAreShownByUnitIdNotByRank() {
    Fact unresolved = unresolved();
    Map<EntityId, ResolvePrompt.Shown> ranked = new LinkedHashMap<>();
    for (String unit : List.of("tax", "billing", "audit")) {
      ranked.put(
          EntityId.parse("service:g:" + unit),
          new ResolvePrompt.Shown(List.of(), List.of(), Set.of()));
    }

    ResolvePrompt.Question question =
        ResolvePrompt.question(
            unresolved, unresolved.subject(), Set.of("a"), List.of(), List.of(), ranked);

    assertThat(question.candidates())
        .extracting(c -> c.ref() + " " + c.unit())
        .containsExactly("K1 service:g:audit", "K2 service:g:billing", "K3 service:g:tax");
    assertThat(Refs.plain(question.rendering()))
        .contains(
            "Candidates:\n[K1] service:g:audit\n[K2] service:g:billing\n[K3] service:g:tax\n");
    assertThat(question.candidate("k2").unit()).isEqualTo(EntityId.parse("service:g:billing"));
    assertThat(question.candidate("K4")).isNull();
  }

  private static Fact unresolved() {
    return Fact.create(
        EntityId.parse("service:g:orders"),
        "unresolved-link",
        FactObject.string("GET ${TAX_SERVICE_URL}/v2/quotes"),
        (ObjectNode) FactJson.parse("{\"kind\":\"http\",\"reason\":\"unresolved-placeholder\"}"),
        new Origin(Origin.Kind.STATIC, "cross-repo-links", "1"),
        Evidence.of("a", "src/main/java/o/TaxClient.java", null, null),
        0.8,
        Validity.from(1));
  }

  @Test
  void anAnswerPassedTodaysChecksFromTheVersionThatAddedThemOn() {
    assertThat(ResolvePrompt.checkedToday("7")).isFalse();
    assertThat(ResolvePrompt.checkedToday("7+f0bd9dfa")).isFalse();
    assertThat(ResolvePrompt.checkedToday(String.valueOf(ResolvePrompt.CHECKED_SINCE))).isTrue();
    assertThat(ResolvePrompt.checkedToday("8+f0bd9dfa")).isTrue();
    assertThat(ResolvePrompt.checkedToday("10")).isTrue();
    // A calibration's tag follows the version after a space (RoleSession.cacheVersion).
    assertThat(ResolvePrompt.checkedToday("8 calibration-a")).isTrue();
    assertThat(ResolvePrompt.checkedToday("7 calibration-a")).isFalse();
    assertThat(ResolvePrompt.checkedToday("calibration")).isFalse();
    assertThat(ResolvePrompt.checkedToday(null)).isFalse();
  }
}
