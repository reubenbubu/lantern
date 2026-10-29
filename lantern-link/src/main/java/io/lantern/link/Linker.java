package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The linker stage: runs its steps, in order, on the facts that hold in a snapshot and records what
 * each derives for the whole snapshot (ADR 0039). A step's facts that it does not derive again stop
 * holding, whichever repository their evidence names, so a derived fact never outlives the facts it
 * came from, even when only another repository was analyzed.
 *
 * <p>Run {@link #link} in the transaction that recorded the snapshot's extracted facts, after
 * recording them with {@link FactRepository#recordSnapshot(long, io.lantern.core.fact.Origin.Kind,
 * Set, Set, java.util.Collection)} and {@link #origins()} as the derived origins, so that recording
 * leaves the steps' facts alone.
 */
public final class Linker {

  /**
   * What linking recorded.
   *
   * @param facts the derived facts as stored, of every step
   * @param added how many of them did not hold before
   * @param changed how many held with another confidence
   * @param closed how many derived facts that held before were not derived again
   */
  public record Result(List<Fact> facts, int added, int changed, int closed) {

    public Result {
      facts = List.copyOf(facts);
    }
  }

  private final FactRepository store;
  private final List<LinkStep> steps;

  /**
   * @param store where the facts are read and recorded
   * @param steps the steps, in the order they run; a step sees the facts of the steps before it
   */
  public Linker(FactRepository store, List<LinkStep> steps) {
    this.store = Objects.requireNonNull(store, "store");
    this.steps = List.copyOf(steps);
  }

  /** The linker of this version of Lantern: deployable units, then the links between them. */
  public static Linker standard(FactRepository store) {
    return new Linker(store, List.of(new DeployableUnits(), new CrossRepoLinks()));
  }

  /** The origin names of the steps' facts. */
  public Set<String> origins() {
    Set<String> names = new LinkedHashSet<>();
    steps.forEach(s -> names.add(s.origin().name()));
    return names;
  }

  /**
   * Derives and records the steps' facts for a snapshot whose other facts are recorded.
   *
   * @param snapshot the snapshot number
   */
  public Result link(long snapshot) {
    Set<String> origins = origins();
    List<Fact> facts = new ArrayList<>();
    for (Fact fact : store.findValidAt(snapshot)) {
      if (!origins.contains(fact.origin().name())) {
        facts.add(fact);
      }
    }
    List<Fact> derived = new ArrayList<>();
    int added = 0;
    int changed = 0;
    int closed = 0;
    for (LinkStep step : steps) {
      FactRepository.SnapshotUpdate update =
          store.recordDerived(snapshot, step.origin(), step.derive(List.copyOf(facts), snapshot));
      facts.addAll(update.facts());
      derived.addAll(update.facts());
      added += update.added();
      changed += update.changed();
      closed += update.closed();
    }
    return new Result(derived, added, changed, closed);
  }
}
