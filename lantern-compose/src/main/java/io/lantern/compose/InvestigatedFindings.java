package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.Elements;
import io.lantern.derive.InvestigatedRisks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What Investigate found (ADR 0110), for the documents of a scope: the risks that concern one of
 * its deployable units, most serious first, and the findings of the fixed checks it worded. Every
 * risk it found opens with {@value #FOUND_BY_LLM}, emphasized, and shows the file:line ranges
 * Lantern verified, so a reader can tell it from what static analysis found; a finding it worded
 * keeps the fixed check as its source and says the wording is the LLM's. The Executive Brief shows
 * them as risk callouts among its own; the other templates in a table of risks and findings. The
 * units Investigate did not look at are on the unknowns page ({@link UnknownsPage}).
 */
final class InvestigatedFindings {

  /** What opens everything Investigate found, in every template (ADR 0110). */
  static final String FOUND_BY_LLM = Elements.FOUND_BY_LLM;

  /** What opens a fixed check's finding the LLM worded. */
  static final String WORDED_BY_LLM = "Found by a fixed check, worded by the LLM:";

  /** The title of the table of risks and findings. */
  static final String TITLE = "Risks and findings";

  private final ArchitectureModel model;
  private final Assembler assembler;
  private final List<InvestigatedRisks.Risk> risks;

  /**
   * @param units the deployable units in scope; a risk is shown if it concerns one of them
   */
  InvestigatedFindings(ArchitectureModel model, Assembler assembler, Set<EntityId> units) {
    this.model = model;
    this.assembler = assembler;
    List<InvestigatedRisks.Risk> found = new ArrayList<>(InvestigatedRisks.inScope(model, units));
    found.sort(
        Comparator.comparing((InvestigatedRisks.Risk r) -> severity(r).ordinal())
            .thenComparing(InvestigatedRisks.Risk::title)
            .thenComparing(r -> r.fact().id()));
    this.risks = List.copyOf(found);
  }

  /** Whether Investigate found any risk in scope. */
  boolean isEmpty() {
    return risks.isEmpty();
  }

  /** The risks as callouts of the Executive Brief's risks section. */
  List<Callout> callouts(String section) {
    List<Callout> callouts = new ArrayList<>();
    for (InvestigatedRisks.Risk risk : risks) {
      List<Inline> content = new ArrayList<>();
      content.add(new TextRun(opening(risk), TextRun.Style.EMPHASIS));
      content.add(TextRun.of(" " + risk.finding() + " Evidence: "));
      content.addAll(evidence(risk));
      callouts.add(
          new Callout(
              assembler.id(
                  section,
                  "investigated",
                  risk.fact().subject() + " " + risk.fact().object().value()),
              Callout.Variant.RISK,
              severity(risk),
              risk.title(),
              content,
              assembler.facts(risk.facts()),
              risk.consequence(),
              confidence(risk)));
    }
    return callouts;
  }

  /**
   * The risks as one table, with a column for impact, finding, consequence, confidence and
   * evidence, and a note of the units Investigate did not look at; or a note that it found none.
   */
  List<Block> table(String section) {
    List<Block> blocks = new ArrayList<>();
    if (risks.isEmpty()) {
      blocks.add(
          new Callout(
              assembler.id(section, "none"),
              Callout.Variant.NOTE,
              null,
              "The LLM found no risks",
              List.of(
                  TextRun.of(
                      "Lantern's LLM investigator found no risks here, or did not run; it"
                          + " proposes risks only with file:line citations Lantern can verify.")),
              List.of()));
    } else {
      List<Table.Row> rows = new ArrayList<>();
      for (InvestigatedRisks.Risk risk : risks) {
        List<Inline> finding = new ArrayList<>();
        finding.add(new TextRun(opening(risk), TextRun.Style.EMPHASIS));
        finding.add(TextRun.of(" "));
        finding.add(new TextRun(risk.title() + ".", TextRun.Style.STRONG));
        finding.add(TextRun.of(" " + risk.finding()));
        rows.add(
            new Table.Row(
                null,
                List.of(
                    List.of(TextRun.of(capitalized(severity(risk).jsonName()))),
                    finding,
                    List.of(TextRun.of(risk.consequence())),
                    List.of(TextRun.of(percent(confidence(risk)))),
                    evidence(risk)),
                assembler.facts(risk.facts())));
      }
      blocks.add(
          new Table(
              assembler.id(section, "risks"),
              TITLE,
              List.of(
                  new Table.Column("Impact"),
                  new Table.Column("Finding"),
                  new Table.Column("Consequence"),
                  new Table.Column("Confidence"),
                  new Table.Column("Evidence")),
              rows,
              List.of()));
    }
    return blocks;
  }

  /** A risk's opening: found by the LLM, or a fixed check's finding the LLM worded. */
  private static String opening(InvestigatedRisks.Risk risk) {
    return risk.wordedByLlm() ? WORDED_BY_LLM : FOUND_BY_LLM;
  }

  /** The verified citations, each as code, separated by semicolons. */
  private static List<Inline> evidence(InvestigatedRisks.Risk risk) {
    List<Inline> content = new ArrayList<>();
    for (String cite : risk.cites()) {
      if (!content.isEmpty()) {
        content.add(TextRun.of("; "));
      }
      content.add(new TextRun(cite, TextRun.Style.CODE));
    }
    return content;
  }

  private static Callout.Severity severity(InvestigatedRisks.Risk risk) {
    String impact = risk.impact();
    try {
      return Callout.Severity.fromJsonName(impact.isEmpty() ? "low" : impact);
    } catch (IllegalArgumentException e) {
      return Callout.Severity.LOW;
    }
  }

  private static BigDecimal confidence(InvestigatedRisks.Risk risk) {
    return BigDecimal.valueOf(risk.fact().confidence()).setScale(2, RoundingMode.HALF_UP);
  }

  private static String percent(BigDecimal confidence) {
    return confidence.movePointRight(2).setScale(0, RoundingMode.HALF_UP) + "%";
  }

  private static String capitalized(String text) {
    return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
  }
}
