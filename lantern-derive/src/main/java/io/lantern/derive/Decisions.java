package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import java.util.ArrayList;
import java.util.List;

/** The decision records the model holds, read for the composer (#761). */
public final class Decisions {

  private Decisions() {}

  /**
   * A decision record.
   *
   * @param id the decision, {@code decision:<repo>:<path>}
   * @param repo the repository it is in
   * @param title the fact holding its title
   * @param number its number, or {@code null}
   * @param status its status, or {@code null}
   * @param outcome its outcome, or {@code null}
   * @param context its context section, or {@code null}
   * @param facts the facts stating what it is
   */
  public record Decision(
      EntityId id,
      String repo,
      Fact title,
      String number,
      String status,
      String outcome,
      String context,
      List<Fact> facts) {}

  /** The decision records, in the order the model holds their titles. */
  public static List<Decision> of(ArchitectureModel model) {
    List<Decision> result = new ArrayList<>();
    for (Fact title : model.withPredicate(Types.TITLE)) {
      EntityId id = title.subject();
      String key = id.key();
      if (!id.type().equals(Types.DECISION) || !key.contains(":")) {
        continue;
      }
      result.add(
          new Decision(
              id,
              key.substring(0, key.indexOf(':')),
              title,
              model.attribute(id, Types.DECISION_NUMBER).orElse(null),
              model.attribute(id, Types.DECISION_STATUS).orElse(null),
              model.attribute(id, Types.DECISION_OUTCOME).orElse(null),
              model.attribute(id, Types.DECISION_CONTEXT).orElse(null),
              model.attributes(id)));
    }
    return result;
  }
}
