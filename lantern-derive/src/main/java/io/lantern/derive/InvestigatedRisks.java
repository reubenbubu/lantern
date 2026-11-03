package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.attributes.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The risks and findings Investigate stored (ADR 0110): the risks the LLM found, and the fixed
 * checks' findings it worded. The composer draws them; what each says is read here from the model.
 */
public final class InvestigatedRisks {

  private InvestigatedRisks() {}

  /**
   * One risk or finding as stored.
   *
   * @param fact the fact holding it
   * @param wordedByLlm whether it is a fixed check's finding the LLM worded, not a risk it found
   * @param title the title, or the empty text
   * @param finding what the risk is, or the empty text
   * @param consequence what follows if it is not dealt with, or the empty text
   * @param impact the impact as stored, or the empty text
   * @param cites the citations the LLM verified, as {@code file:line}, in order
   * @param facts the facts the risk rests on: the fact itself, and for a worded finding the fixed
   *     check's too
   */
  public record Risk(
      Fact fact,
      boolean wordedByLlm,
      String title,
      String finding,
      String consequence,
      String impact,
      List<String> cites,
      List<Fact> facts) {}

  /**
   * The risks and findings about one of the units, in the order the model holds them: the risks
   * first, then the worded findings.
   *
   * @param units the deployable units in scope
   */
  public static List<Risk> inScope(ArchitectureModel model, Set<EntityId> units) {
    List<Risk> found = new ArrayList<>();
    for (String predicate : List.of(Types.INVESTIGATED_RISK, Types.FINDING_WORDING)) {
      for (Fact fact : model.withPredicate(predicate)) {
        if (units(fact).stream().anyMatch(units::contains)) {
          found.add(risk(model, fact));
        }
      }
    }
    return List.copyOf(found);
  }

  private static Risk risk(ArchitectureModel model, Fact fact) {
    boolean worded = fact.predicate().equals(Types.FINDING_WORDING);
    List<Fact> facts = new ArrayList<>();
    facts.add(fact);
    if (worded) {
      model.about(fact.subject()).stream()
          .filter(f -> f.predicate().equals(Types.FINDING_RAISED))
          .forEach(facts::add);
    }
    List<String> cites = new ArrayList<>();
    for (JsonNode cite : fact.attributes().path("cites")) {
      cites.add(cite.asString());
    }
    return new Risk(
        fact,
        worded,
        text(fact, "title"),
        text(fact, "finding"),
        text(fact, "consequence"),
        text(fact, "impact"),
        cites,
        List.copyOf(facts));
  }

  private static List<EntityId> units(Fact fact) {
    List<EntityId> units = new ArrayList<>();
    if (fact.predicate().equals(Types.INVESTIGATED_RISK)) {
      units.add(fact.subject());
    }
    for (JsonNode unit : fact.attributes().path("units")) {
      units.add(EntityId.parse(unit.asString()));
    }
    return units;
  }

  private static String text(Fact fact, String attribute) {
    JsonNode value = fact.attributes().get(attribute);
    return value == null || !value.isString() ? "" : value.stringValue();
  }
}
