package io.lantern.compose;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Citation;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Inline;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.derive.ArchitectureModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;

/**
 * The README claims of the scope's repositories that the code contradicts, as the claim checks
 * record them (ADR 0043): a warning callout each where the README's units appear, quoting the claim
 * and citing both sides, and a failure each in the quality report (ADR 0051).
 */
final class ClaimContradictions {

  /** The title of the callout showing a contradicted claim. */
  static final String TITLE = "Claim the code contradicts";

  /** The check a contradicted claim fails in the quality report. */
  static final String CHECK = "README claims agree with the code";

  /** The longest quote of a claim a quality report failure shows, in characters. */
  private static final int SHORT_QUOTE = 160;

  private final ArchitectureModel model;

  private final Assembler assembler;
  private final List<Flag> flags = new ArrayList<>();

  /** A contradicted claim, the facts on the code's side and, once shown, its callout's ID. */
  private static final class Flag {
    final Fact contradiction;
    final String kind;
    final Fact claim;
    final String claimed;
    final List<Fact> contradictedBy;
    final String stated;
    final List<String> found;
    String callout;

    Flag(ArchitectureModel.Contradiction contradiction) {
      this.contradiction = contradiction.fact();
      this.kind = contradiction.kind();
      this.claim = contradiction.claim();
      this.claimed = contradiction.claimed();
      this.contradictedBy = contradiction.contradictedBy();
      this.stated = contradiction.stated();
      this.found = contradiction.found();
    }

    List<Fact> facts() {
      List<Fact> facts = new ArrayList<>();
      facts.add(contradiction);
      facts.add(claim);
      facts.addAll(contradictedBy);
      return facts;
    }
  }

  /**
   * @param repos the repositories in scope, whose READMEs' contradicted claims are shown
   */
  ClaimContradictions(ArchitectureModel model, Assembler assembler, SortedSet<String> repos) {
    this.model = model;
    this.assembler = assembler;
    for (ArchitectureModel.Contradiction contradiction : model.contradictions(repos)) {
      flags.add(new Flag(contradiction));
    }
    flags.sort(
        Comparator.comparing((Flag f) -> f.claim.evidence().repo())
            .thenComparing(f -> String.valueOf(f.claim.evidence().path()))
            .thenComparingInt(f -> line(f.claim.evidence()))
            .thenComparing(f -> f.kind)
            .thenComparing(f -> f.contradiction.id()));
  }

  /** One warning callout per contradicted claim, for the section with the ID. */
  List<Callout> callouts(String section) {
    List<Callout> callouts = new ArrayList<>();
    for (Flag flag : flags) {
      flag.callout = assembler.id(section, "contradicted-claim");
      List<Inline> content = new ArrayList<>();
      content.addAll(where(flag.claim.evidence()));
      content.add(TextRun.of(" says: “" + quote(flag) + "”"));
      content.add(new Citation(assembler.facts(List.of(flag.claim))));
      content.add(TextRun.of("."));
      if (flag.stated != null && !flag.found.isEmpty()) {
        content.add(
            TextRun.of(
                " It states "
                    + flag.stated
                    + "; the code shows "
                    + String.join(", ", flag.found)
                    + "."));
      }
      content.add(TextRun.of(" The code shows otherwise: "));
      for (int i = 0; i < flag.contradictedBy.size(); i++) {
        if (i > 0) {
          content.add(TextRun.of("; "));
        }
        Fact fact = flag.contradictedBy.get(i);
        content.addAll(reads(fact));
        content.add(TextRun.of(" ("));
        content.addAll(where(fact.evidence()));
        content.add(TextRun.of(")"));
        content.add(new Citation(assembler.facts(List.of(fact))));
      }
      content.add(TextRun.of("."));
      callouts.add(
          new Callout(
              flag.callout,
              Callout.Variant.WARNING,
              null,
              TITLE,
              content,
              assembler.facts(flag.facts())));
    }
    return callouts;
  }

  /**
   * One quality report failure per contradicted claim, pointing at its callout when the document
   * shows one. Call after the sections are built and before the document's facts are taken.
   */
  List<QualityReport.Failure> failures() {
    List<QualityReport.Failure> failures = new ArrayList<>();
    for (Flag flag : flags) {
      String quote = quote(flag);
      if (quote.length() > SHORT_QUOTE) {
        quote = quote.substring(0, SHORT_QUOTE).strip() + "…";
      }
      int count = flag.contradictedBy.size();
      List<Inline> detail = new ArrayList<>(where(flag.claim.evidence()));
      detail.add(
          TextRun.of(
              ": “"
                  + quote
                  + "” is contradicted by "
                  + count
                  + (count == 1 ? " fact" : " facts")
                  + " of the code."));
      failures.add(
          new QualityReport.Failure(
              CHECK,
              flag.callout == null ? null : CrossReference.Target.block(flag.callout),
              detail,
              assembler.facts(flag.facts())));
    }
    return failures;
  }

  private static String quote(Flag flag) {
    return flag.claimed.strip().replaceAll("\\s+", " ");
  }

  /** How a fact on the code's side reads: what relates to what, or what an entity has. */
  private List<Inline> reads(Fact fact) {
    ArchitectureModel.Reading reading = ArchitectureModel.reading(fact);
    List<Inline> content = new ArrayList<>();
    content.add(reference(reading.subject()));
    if (reading.entity() != null) {
      content.add(TextRun.of(" " + reading.verb() + " "));
      content.add(reference(reading.entity()));
    } else {
      content.add(TextRun.of(reading.rest()));
    }
    String profile = reading.view();
    if (profile != null) {
      content.add(TextRun.of(" under the " + profile + " profile"));
    }
    return content;
  }

  private Inline reference(EntityId entity) {
    return new CrossReference(
        assembler.name(assembler.entity(entity)), CrossReference.Target.entity(entity));
  }

  /** Where a fact is written: the file, as code, and line in its repository. */
  private static List<Inline> where(Evidence evidence) {
    if (evidence.path() == null) {
      return List.of(TextRun.of("in " + evidence.repo()));
    }
    String line = evidence.lines() == null ? "" : ", line " + evidence.lines().start();
    return List.of(
        new TextRun(evidence.path(), TextRun.Style.CODE),
        TextRun.of(line + ", in " + evidence.repo()));
  }

  private static int line(Evidence evidence) {
    return evidence.lines() == null ? 0 : evidence.lines().start();
  }
}
