package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    String texts =
        ResolvePrompt.SYSTEM
            + "\n--\n"
            + ResolvePrompt.RETRY
            + "\n--\n"
            + LinkResolver.MAX_OUTPUT_TOKENS
            + "\n--\n"
            + LinkResolver.MAX_CANDIDATE_FACTS;
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(ResolvePrompt.VERSION + " " + fingerprint)
        .as("The prompt changed: raise ResolvePrompt.VERSION and update this fingerprint")
        .isEqualTo("2 a55a44b897152341843a1244f2d05afa9430c80f04558e0f93c190cd39dad715");
  }

  /**
   * Candidates are shown by unit ID, not in the order the linker ranks them, since models tend to
   * favour the first option (#199). Unit IDs are stable, so the cache key stays free of fact IDs.
   */
  @Test
  void candidatesAreShownByUnitIdNotByRank() {
    Fact unresolved =
        Fact.create(
            EntityId.parse("service:g:orders"),
            "unresolved-link",
            FactObject.string("GET ${TAX_SERVICE_URL}/v2/quotes"),
            (ObjectNode)
                FactJson.parse("{\"kind\":\"http\",\"reason\":\"unresolved-placeholder\"}"),
            new Origin(Origin.Kind.STATIC, "cross-repo-links", "1"),
            new Evidence("a", "a".repeat(40), "src/main/java/o/TaxClient.java", null, null),
            0.8,
            Validity.from(1));
    Map<EntityId, List<Fact>> ranked = new LinkedHashMap<>();
    ranked.put(EntityId.parse("service:g:tax"), List.of());
    ranked.put(EntityId.parse("service:g:billing"), List.of());
    ranked.put(EntityId.parse("service:g:audit"), List.of());

    ResolvePrompt.Question question = ResolvePrompt.question(unresolved, List.of(), ranked);

    assertThat(question.candidates())
        .extracting(c -> c.ref() + " " + c.unit())
        .containsExactly("C1 service:g:audit", "C2 service:g:billing", "C3 service:g:tax");
    assertThat(question.user())
        .contains("[C1] service:g:audit\n[C2] service:g:billing\n[C3] service:g:tax\n");
  }
}
