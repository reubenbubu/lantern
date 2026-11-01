package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Lineage;
import io.lantern.core.fact.Origin;
import io.lantern.extract.LanternCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The linker stage: runs its rules, in the order their reads and writes give, on the facts that
 * hold in a snapshot and records what each derives for the whole snapshot (ADR 0039, 0095). A
 * rule's facts that it does not derive again stop holding, whichever of the snapshot's repositories
 * their evidence names, so a derived fact never outlives the facts it came from, even when only
 * another repository was analyzed; another system's facts are neither read nor ended (ADR 0071). A
 * rule whose read facts and version are the same as at its last run for the system is not run
 * again: its facts keep holding as they are.
 *
 * <p>Run {@link #link} in the transaction that recorded the snapshot's extracted facts, after
 * recording them with {@link FactRepository#recordSnapshot(long, io.lantern.core.fact.Origin.Kind,
 * Set, Set, java.util.Collection)} and {@link #origins()} as the derived origins, so that recording
 * leaves the rules' facts alone.
 */
public final class Linker {

  /**
   * What linking recorded.
   *
   * @param facts the derived facts as stored, of every rule, those of rules not run again included
   * @param added how many of them did not hold before
   * @param changed how many held with another confidence
   * @param closed how many derived facts that held before were not derived again
   * @param ran the names of the rules that ran, in the order they ran; the others' input and
   *     version were the same as at their last run
   */
  public record Result(List<Fact> facts, int added, int changed, int closed, List<String> ran) {

    public Result {
      facts = List.copyOf(facts);
      ran = List.copyOf(ran);
    }
  }

  /**
   * The names of rules that are gone (ADR 0095): {@code cross-repo-links}, split into the protocol
   * rules by #575. Their facts in a store are neither read nor kept: each link ends them.
   */
  static final Set<String> RETIRED = Set.of(CrossRepoLinks.NAME);

  private final FactRepository store;
  private final List<LinkRule> rules;
  private final RuleRuns runs;
  private final String code;

  /**
   * @param store where the facts are read and recorded
   * @param rules the rules; each runs after the rules that write what it reads, and otherwise in
   *     this order
   * @param runs what is remembered of the rules' last runs
   * @throws IllegalArgumentException if two rules share a name or the rules read each other's facts
   *     in a cycle
   */
  public Linker(FactRepository store, List<? extends LinkRule> rules, RuleRuns runs) {
    this(store, rules, runs, LanternCode.digest());
  }

  /**
   * A linker as {@link #Linker(FactRepository, List, RuleRuns)}, with the digest of the code that
   * runs it given, so a test can stand for a change to Lantern's code.
   */
  public Linker(FactRepository store, List<? extends LinkRule> rules, RuleRuns runs, String code) {
    this.store = Objects.requireNonNull(store, "store");
    this.rules = RuleOrder.of(List.copyOf(rules));
    this.runs = Objects.requireNonNull(runs, "runs");
    this.code = Objects.requireNonNull(code, "code");
  }

  /** A linker that runs every rule every time. */
  public Linker(FactRepository store, List<? extends LinkRule> rules) {
    this(store, rules, RuleRuns.NONE);
  }

  /**
   * The linker of this version of Lantern: values kept as written resolved per view (ADR 0094),
   * deployable units, the links between them, then the README claims they contradict.
   */
  public static Linker standard(FactRepository store) {
    return standard(store, RuleRuns.NONE);
  }

  /** The linker of this version of Lantern, remembering its rules' runs in {@code runs}. */
  public static Linker standard(FactRepository store, RuleRuns runs) {
    return new Linker(store, Rules.standard(), runs);
  }

  /**
   * The linker of this version of Lantern with the LLM resolver, picking among the candidates of
   * the links the other rules leave open (ADR 0045).
   */
  public static Linker standard(FactRepository store, LinkResolver resolver) {
    return standard(store, resolver, RuleRuns.NONE);
  }

  /** The linker with the LLM resolver, remembering its rules' runs in {@code runs}. */
  public static Linker standard(FactRepository store, LinkResolver resolver, RuleRuns runs) {
    List<LinkRule> rules = new ArrayList<>(Rules.standard());
    rules.add(resolver);
    return new Linker(store, rules, runs);
  }

  /**
   * The origin names of the rules' facts, and of retired rules', which recording the extracted
   * facts must leave alone.
   */
  public Set<String> origins() {
    Set<String> names = new LinkedHashSet<>();
    rules.forEach(r -> names.add(r.info().name()));
    names.addAll(RETIRED);
    return names;
  }

  /** The rules' names, in the order they run. */
  public List<String> order() {
    return rules.stream().map(r -> r.info().name()).toList();
  }

  /**
   * Derives and records the rules' facts for a snapshot whose other facts are recorded, from every
   * fact that holds in it, whichever repository its evidence names.
   *
   * @param snapshot the snapshot number
   */
  public Result link(long snapshot) {
    return link(snapshot, null);
  }

  /**
   * Derives and records the rules' facts for the snapshot of one system, whose other facts are
   * recorded: from the facts of the snapshot's repositories only, and ending only derived facts
   * about them, so another system's facts that hold at the same number are neither linked to nor
   * ended (ADR 0071).
   *
   * @param snapshot the snapshot number
   * @param repos the snapshot's repositories, or {@code null} for every repository
   */
  public Result link(long snapshot, Set<String> repos) {
    Set<String> origins = origins();
    Set<String> derivedPredicates = new java.util.HashSet<>();
    rules.forEach(r -> derivedPredicates.addAll(r.writes()));
    List<Fact> extracted = new ArrayList<>();
    Map<String, List<Fact>> held = new HashMap<>();
    for (Fact fact :
        repos == null ? store.findValidAt(snapshot) : store.findValidAt(snapshot, repos)) {
      String origin = fact.origin().name();
      if (origins.contains(origin)) {
        held.computeIfAbsent(origin, o -> new ArrayList<>()).add(fact);
      } else {
        extracted.add(fact);
      }
    }
    String scope = repos == null ? "*" : String.join(",", new TreeSet<>(repos));
    Map<String, List<Fact>> derivedBy = new HashMap<>();
    List<Fact> derived = new ArrayList<>();
    List<String> ran = new ArrayList<>();
    int added = 0;
    int changed = 0;
    int closed = 0;
    Map<String, String> claims = new HashMap<>();
    extracted.forEach(f -> claims.put(f.id(), f.claimId()));
    for (String retired : RETIRED) {
      // A retired rule's facts stop holding, as a rule's do that it no longer gives.
      closed +=
          store
              .recordDerived(
                  snapshot, new Origin(Origin.Kind.STATIC, retired, null), repos, List.of())
              .closed();
    }
    for (LinkRule rule : rules) {
      RuleInfo info = rule.info();
      List<Fact> input = input(rule, extracted, derivedBy);
      String inputs = digest(input);
      List<Fact> before = held.getOrDefault(info.name(), List.of());
      Optional<RuleRuns.Run> last = runs.last(scope, info.name());
      List<Fact> facts;
      if (rule.pure()
          && last.isPresent()
          && last.get().version().equals(info.version())
          && last.get().code().equals(code)
          && last.get().inputs().equals(inputs)
          && last.get().outputs().equals(digest(before))) {
        facts = before;
      } else {
        ClaimQuery query = new ClaimQuery(info, rule.reads(), derivedPredicates, input, snapshot);
        // Once in each view if the rule derives per view, merged into one claim per view (#590).
        List<Fact> given = ViewMerge.derive(rule, query);
        // A derived fact's lineage is the claims of the facts it names (ADR 0091).
        List<Fact> found =
            given.stream()
                .map(f -> f.withLineage(Lineage.of(Claims.json(f), claims::get)))
                .toList();
        FactRepository.SnapshotUpdate update =
            store.recordDerived(snapshot, info.origin(), repos, found);
        facts = update.facts();
        runs.record(
            scope,
            info.name(),
            new RuleRuns.Run(info.version(), code, inputs, digest(facts), snapshot));
        ran.add(info.name());
        added += update.added();
        changed += update.changed();
        closed += update.closed();
      }
      // In ID order, as the store gives facts, so a rule not run again hands on its facts in the
      // order a run would have.
      facts = facts.stream().sorted(Comparator.comparing(Fact::id)).toList();
      facts.forEach(f -> claims.put(f.id(), f.claimId()));
      derivedBy.put(info.name(), facts);
      derived.addAll(facts);
    }
    return new Result(derived, added, changed, closed, ran);
  }

  /**
   * The facts a rule reads: the extracted ones if it reads them all, else those of the predicates
   * it reads; then those of the predicates it reads that the rules before it gave, rule by rule in
   * the order they ran, each rule's in ID order.
   */
  private List<Fact> input(LinkRule rule, List<Fact> extracted, Map<String, List<Fact>> derivedBy) {
    Set<String> reads = rule.reads();
    List<Fact> input = new ArrayList<>();
    boolean all = reads.contains(LinkRule.EXTRACTED);
    for (Fact f : extracted) {
      if (all || reads.contains(f.predicate())) {
        input.add(f);
      }
    }
    for (LinkRule before : rules) {
      List<Fact> facts = derivedBy.get(before.info().name());
      if (facts == null) {
        continue;
      }
      for (Fact f : facts) {
        if (reads.contains(f.predicate())) {
          input.add(f);
        }
      }
    }
    return input;
  }

  /**
   * A digest of facts: their IDs, claim IDs and confidences, in any order. The claim ID changes
   * when a type's key attributes do (ADR 0090), which the fact ID does not show.
   */
  private static String digest(List<Fact> facts) {
    List<String> lines = new ArrayList<>();
    facts.forEach(f -> lines.add(f.id() + " " + f.claimId() + " " + f.confidence()));
    lines.sort(null);
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      for (String line : lines) {
        sha.update(line.getBytes(StandardCharsets.UTF_8));
        sha.update((byte) '\n');
      }
      return HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
