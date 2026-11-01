package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import java.util.List;
import java.util.Set;

/**
 * A rule of the linker (knowledge-build epic, section 8; ADR 0095): it declares the predicates it
 * reads and the predicates it writes, and derives its facts from those it reads. The linker runs
 * the rules in the order their reads and writes give, a rule after every other rule that writes
 * what it reads, and runs a rule again only when the facts it reads or its version changed since
 * its last run; otherwise its facts keep holding.
 */
public interface LinkRule {

  /**
   * In {@link #reads}: every fact the extractors gave, whatever its predicate, besides those the
   * other rules write that the rule names. For the steps that read across every extractor until
   * #574 and #575 split them.
   */
  String EXTRACTED = "*extracted";

  /** The rule's name, version and origin kind. */
  RuleInfo info();

  /**
   * The predicates the rule reads, and {@link #EXTRACTED} for every extracted fact. Facts of a
   * predicate another rule writes are those of the rules that write it, which run first.
   */
  Set<String> reads();

  /** The predicates of the facts the rule gives. */
  Set<String> writes();

  /**
   * Derives the rule's facts in a view.
   *
   * @param in the facts the rule reads
   * @param out where it puts the facts it derives, each valid from {@link ClaimQuery#snapshot}
   * @param view the view it derives in
   */
  void derive(ClaimQuery in, ClaimSink out, View view);

  /**
   * Whether the linker runs the rule once in each view (knowledge-build epic, section 8): the
   * default view, then each profile its facts name ({@link View#of}). It merges what the rule finds
   * in each into one claim per view, by the link {@link ClaimSink#add(Fact, String)} names. A rule
   * that is not per view runs once, in the default view, and finds what differs between views
   * itself: value resolution and address resolution, whose views include deployment environments,
   * resolve each view in one pass (ADR 0095, amendment for #590).
   */
  default boolean perView() {
    return false;
  }

  /**
   * A fact of a rule that runs per view, once the linker has merged its views.
   *
   * @param link what made it one claim in every view, as the rule gave it to {@link
   *     ClaimSink#add(Fact, String)}; null for a fact that holds whatever the view
   */
  record Merged(Fact fact, String link) {}

  /**
   * The facts of a rule that runs per view, once the linker has merged its views: a rule that
   * describes a claim by what it is in other views does so here; otherwise the facts as merged.
   */
  default List<Fact> merged(List<Merged> facts) {
    return facts.stream().map(Merged::fact).toList();
  }

  /**
   * Whether the rule's facts depend only on what it reads and its version, so it need not run again
   * when neither changed. The LLM resolver also consults its answers and model, and so runs every
   * time (it asks the LLM only about what it has no answer for).
   */
  default boolean pure() {
    return true;
  }

  /** The origin the rule's facts record. */
  default Origin origin() {
    return info().origin();
  }

  /**
   * A rule that derives from all the facts it reads at once, as the linker's steps did before ADR
   * 0095; #574, #575 and #576 port them to queries.
   */
  interface OverFacts extends LinkRule {

    /**
     * Derives the rule's facts.
     *
     * @param facts the facts the rule reads
     * @param snapshot the snapshot number the derived facts hold from
     */
    List<Fact> derive(List<Fact> facts, long snapshot);

    @Override
    default void derive(ClaimQuery in, ClaimSink out, View view) {
      derive(in.facts(), in.snapshot()).forEach(out::add);
    }
  }
}
