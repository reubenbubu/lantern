package io.lantern.harness.verify;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One item's entry in an answer, as the checks see it: the raw entry for the Schema check, its
 * texts, the file ranges the model wrote and the claims it proposes.
 *
 * @param item the item's local ID
 * @param raw the entry as the model wrote it
 * @param texts its texts, each with its citations
 * @param claims the claims it proposes
 */
public record ItemAnswer(
    String item, JsonNode raw, List<CheckedText> texts, List<ProposedClaim> claims) {

  public ItemAnswer {
    texts = List.copyOf(texts);
    claims = List.copyOf(claims);
  }

  /** Whether the entry is {@code {"omitted": true}}. */
  public boolean omitted() {
    return raw != null
        && raw.isObject()
        && raw.size() == 1
        && raw.has("omitted")
        && raw.get("omitted").isBoolean()
        && raw.get("omitted").asBoolean();
  }
}
