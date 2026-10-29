package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import java.util.List;

/**
 * One step of the linker stage: it derives facts from the facts that hold in a whole snapshot, not
 * from files. Its facts are recorded for the whole snapshot at once (see {@link Linker}).
 */
public interface LinkStep {

  /** The origin of the step's facts; its kind and name select the facts it owns in the store. */
  Origin origin();

  /**
   * Derives the step's facts.
   *
   * @param facts the facts that hold in the snapshot, without those of this or later steps
   * @param snapshot the snapshot number the derived facts hold from
   * @return the derived facts, each of the step's origin
   */
  List<Fact> derive(List<Fact> facts, long snapshot);
}
