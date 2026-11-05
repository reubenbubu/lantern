package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.summarize.SupportChecks;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Which rejected answers a pass asks again, and what the run record counts (ADR 0101, the owner's
 * decision of 4 Nov 2026 on #751): each rejected answer once, a text first rejected in a later pass
 * included, and no pass after the last.
 */
class SupportRetriesTest {

  @Test
  void aTextFirstRejectedInTheSecondPassGetsItsOneRetryInTheThirdAndARetriedOneNone() {
    // The first pass rejects a responsibility's answer.
    assertThat(AnalysisRun.toRetry(1, Map.of("unit", "\"r\""), Set.of(), Set.of()))
        .containsOnlyKeys("unit");
    // The second rejects its retry, and a group name worded anew from it: only the group name is
    // asked again.
    assertThat(
            AnalysisRun.toRetry(
                2,
                Map.of("unit", "\"r\"", "unit-retried", "\"r2\"", "group", "\"g\""),
                Set.of("unit"),
                Set.of("unit-retried")))
        .containsOnlyKeys("group");
    // After the last pass, none.
    assertThat(AnalysisRun.toRetry(3, Map.of("other", "\"o\""), Set.of(), Set.of())).isEmpty();
  }

  @Test
  void aRetriedTextFallsBackWhenItsReplacementIsRejectedOrMissingAndNotWhenItHolds() {
    Fact kept = text("service:g:a", null);
    Fact rejectedAgain = text("service:g:b", "unsupported");
    List<SupportChecks.Rejected> retried =
        List.of(
            new SupportChecks.Rejected(text("service:g:a", null), "\"x\""),
            new SupportChecks.Rejected(text("service:g:b", null), "\"x\""),
            new SupportChecks.Rejected(text("service:g:c", null), "\"x\""));

    // b is held rejected again; c gave no new text; a's new text holds.
    assertThat(AnalysisRun.fellBack(List.of(kept, rejectedAgain), retried))
        .containsExactly(Map.entry("describe-unit", 2));
    assertThat(AnalysisRun.retried(retried)).containsExactly(Map.entry("describe-unit", 3));
  }

  private static Fact text(String subject, String support) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("text", "Takes orders.");
    attributes.put("basis", "inferred");
    if (support != null) {
      attributes.put("support", support);
    }
    return Fact.create(
        EntityId.parse(subject),
        Types.RESPONSIBILITY,
        FactObject.string("c4"),
        attributes,
        new Origin(Origin.Kind.LLM, "describe-unit", "1"),
        Evidence.of("shop", "pom.xml", new Evidence.LineRange(1, 2), null),
        0.6,
        Validity.from(1));
  }
}
