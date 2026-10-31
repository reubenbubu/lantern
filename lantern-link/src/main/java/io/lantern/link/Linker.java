package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Lineage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The linker stage: runs its steps, in order, on the facts that hold in a snapshot and records what
 * each derives for the whole snapshot (ADR 0039). A step's facts that it does not derive again stop
 * holding, whichever of the snapshot's repositories their evidence names, so a derived fact never
 * outlives the facts it came from, even when only another repository was analyzed; another system's
 * facts are neither read nor ended (ADR 0071).
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

  /**
   * The linker of this version of Lantern: values kept as written resolved per view (ADR 0094),
   * deployable units, the links between them, then the README claims they contradict.
   */
  public static Linker standard(FactRepository store) {
    return new Linker(
        store,
        List.of(new ValueViews(), new DeployableUnits(), new CrossRepoLinks(), new ClaimChecks()));
  }

  /**
   * The linker of this version of Lantern with the LLM resolver as its last step, picking among the
   * candidates of the links the steps before it leave open (ADR 0045).
   */
  public static Linker standard(FactRepository store, LinkResolver resolver) {
    return new Linker(
        store,
        List.of(
            new ValueViews(),
            new DeployableUnits(),
            new CrossRepoLinks(),
            new ClaimChecks(),
            resolver));
  }

  /** The origin names of the steps' facts. */
  public Set<String> origins() {
    Set<String> names = new LinkedHashSet<>();
    steps.forEach(s -> names.add(s.origin().name()));
    return names;
  }

  /**
   * Derives and records the steps' facts for a snapshot whose other facts are recorded, from every
   * fact that holds in it, whichever repository its evidence names.
   *
   * @param snapshot the snapshot number
   */
  public Result link(long snapshot) {
    return link(snapshot, null);
  }

  /**
   * Derives and records the steps' facts for the snapshot of one system, whose other facts are
   * recorded: from the facts of the snapshot's repositories only, and ending only derived facts
   * about them, so another system's facts that hold at the same number are neither linked to nor
   * ended (ADR 0071).
   *
   * @param snapshot the snapshot number
   * @param repos the snapshot's repositories, or {@code null} for every repository
   */
  public Result link(long snapshot, Set<String> repos) {
    Set<String> origins = origins();
    List<Fact> facts = new ArrayList<>();
    for (Fact fact :
        repos == null ? store.findValidAt(snapshot) : store.findValidAt(snapshot, repos)) {
      if (!origins.contains(fact.origin().name())) {
        facts.add(fact);
      }
    }
    List<Fact> derived = new ArrayList<>();
    int added = 0;
    int changed = 0;
    int closed = 0;
    Map<String, String> claims = new HashMap<>();
    facts.forEach(f -> claims.put(f.id(), f.claimId()));
    for (LinkStep step : steps) {
      // A derived fact's lineage is the claims of the facts it names (ADR 0091).
      List<Fact> found =
          step.derive(List.copyOf(facts), snapshot).stream()
              .map(f -> f.withLineage(Lineage.of(f.attributes(), claims::get)))
              .toList();
      FactRepository.SnapshotUpdate update =
          store.recordDerived(snapshot, step.origin(), repos, found);
      update.facts().forEach(f -> claims.put(f.id(), f.claimId()));
      facts.addAll(update.facts());
      derived.addAll(update.facts());
      added += update.added();
      changed += update.changed();
      closed += update.closed();
    }
    return new Result(derived, added, changed, closed);
  }
}
