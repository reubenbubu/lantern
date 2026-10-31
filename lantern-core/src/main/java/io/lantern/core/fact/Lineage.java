package io.lantern.core.fact;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The lineage of a fact whose attributes name the facts it was derived from by their observation
 * IDs (ADR 0091): the claim IDs of those facts. The attributes stay until their readers follow the
 * lineage instead (1C); until then this is how the linker and the store's migration fill it.
 */
public final class Lineage {

  /**
   * The attributes that name source facts, in the order their claims are listed: {@code claim} and
   * {@code fact} (one fact), {@code facts} and {@code cites} (lists), and the {@code fact} of each
   * entry of {@code contradictedBy}.
   */
  public static final List<String> ATTRIBUTES =
      List.of("claim", "fact", "facts", "cites", "contradictedBy");

  private Lineage() {}

  /**
   * The claim IDs of the facts the attributes name, in order and each once; a fact the function
   * does not know is left out.
   *
   * @param attributes the fact's attributes
   * @param claimOf the claim ID of an observation ID, or {@code null} when it is not known
   */
  public static List<String> of(ObjectNode attributes, Function<String, String> claimOf) {
    List<String> ids = new ArrayList<>();
    add(attributes.get("claim"), ids);
    add(attributes.get("fact"), ids);
    add(attributes.get("facts"), ids);
    add(attributes.get("cites"), ids);
    JsonNode by = attributes.get("contradictedBy");
    if (by != null && by.isArray()) {
      by.forEach(entry -> add(entry.get("fact"), ids));
    }
    List<String> claims = new ArrayList<>();
    for (String id : ids) {
      String claim = claimOf.apply(id);
      if (claim != null && !claims.contains(claim)) {
        claims.add(claim);
      }
    }
    return claims;
  }

  private static void add(JsonNode node, List<String> ids) {
    if (node == null) {
      return;
    }
    if (node.isString()) {
      ids.add(node.asString());
    } else if (node.isArray()) {
      node.forEach(element -> add(element, ids));
    }
  }
}
