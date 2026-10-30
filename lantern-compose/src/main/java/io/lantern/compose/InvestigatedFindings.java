package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The risks the Investigate role found (ADR 0084), for the documents of a scope: those that concern
 * one of its deployable units, most serious first. Every one is marked {@value #FOUND_BY_LLM},
 * emphasized, and shows the file:line ranges Lantern verified, so a reader can tell it from what
 * static analysis found. The Executive Brief shows them as risk callouts among its own; the other
 * templates in a table of risks and findings.
 */
final class InvestigatedFindings {

  /** What opens everything the Investigate role found, in every template (ADR 0084). */
  static final String FOUND_BY_LLM = "Found by the LLM:";

  /** The investigator's risk facts (lantern-link's {@code Investigator.RISK}). */
  static final String RISK = "investigated-risk";

  /** The investigator's connection facts (lantern-link's {@code Investigator.CONNECTION}). */
  static final String CONNECTION = "INVESTIGATED_CONNECTION";

  /** The title of the table of risks and findings. */
  static final String TITLE = "Risks and findings";

  private final Assembler assembler;
  private final List<Fact> risks;

  /**
   * @param units the deployable units in scope; a risk is shown if it concerns one of them
   */
  InvestigatedFindings(Knowledge knowledge, Assembler assembler, Set<EntityId> units) {
    this.assembler = assembler;
    List<Fact> found = new ArrayList<>();
    for (Fact fact : knowledge.withPredicate(RISK)) {
      if (units(fact).stream().anyMatch(units::contains)) {
        found.add(fact);
      }
    }
    found.sort(
        Comparator.comparing((Fact f) -> severity(f).ordinal())
            .thenComparing(f -> f.object().value())
            .thenComparing(Fact::id));
    this.risks = List.copyOf(found);
  }

  /** Whether the investigator found any risk in scope. */
  boolean isEmpty() {
    return risks.isEmpty();
  }

  /** The risks as callouts of the Executive Brief's risks section. */
  List<Callout> callouts(String section) {
    List<Callout> callouts = new ArrayList<>();
    for (Fact risk : risks) {
      List<Inline> content = new ArrayList<>();
      content.add(new TextRun(FOUND_BY_LLM, TextRun.Style.EMPHASIS));
      content.add(TextRun.of(" " + text(risk, "finding") + " Evidence: "));
      content.addAll(evidence(risk));
      callouts.add(
          new Callout(
              assembler.id(section, "investigated", risk.object().value()),
              Callout.Variant.RISK,
              severity(risk),
              risk.object().value(),
              content,
              assembler.facts(List.of(risk)),
              text(risk, "consequence"),
              confidence(risk)));
    }
    return callouts;
  }

  /**
   * The risks as one table, with a column for impact, finding, consequence, confidence and
   * evidence; or a note that the investigator found none.
   */
  List<Block> table(String section) {
    if (risks.isEmpty()) {
      return List.of(
          new Callout(
              assembler.id(section, "none"),
              Callout.Variant.NOTE,
              null,
              "The LLM investigator found no risks",
              List.of(
                  TextRun.of(
                      "Lantern's LLM investigator found no risks here, or did not run; it"
                          + " proposes risks only with file:line citations Lantern can verify.")),
              List.of()));
    }
    List<Table.Row> rows = new ArrayList<>();
    for (Fact risk : risks) {
      List<Inline> finding = new ArrayList<>();
      finding.add(new TextRun(FOUND_BY_LLM, TextRun.Style.EMPHASIS));
      finding.add(TextRun.of(" "));
      finding.add(new TextRun(risk.object().value() + ".", TextRun.Style.STRONG));
      finding.add(TextRun.of(" " + text(risk, "finding")));
      rows.add(
          new Table.Row(
              null,
              List.of(
                  List.of(TextRun.of(capitalized(severity(risk).jsonName()))),
                  finding,
                  List.of(TextRun.of(text(risk, "consequence"))),
                  List.of(TextRun.of(percent(confidence(risk)))),
                  evidence(risk)),
              assembler.facts(List.of(risk))));
    }
    return List.of(
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

  /** The verified citations, each as code, separated by semicolons. */
  private static List<Inline> evidence(Fact risk) {
    List<Inline> content = new ArrayList<>();
    for (JsonNode cite : risk.attributes().path("cites")) {
      if (!content.isEmpty()) {
        content.add(TextRun.of("; "));
      }
      content.add(new TextRun(cite.asString(), TextRun.Style.CODE));
    }
    return content;
  }

  private static List<EntityId> units(Fact fact) {
    List<EntityId> units = new ArrayList<>();
    units.add(fact.subject());
    for (JsonNode unit : fact.attributes().path("units")) {
      units.add(EntityId.parse(unit.asString()));
    }
    return units;
  }

  private static Callout.Severity severity(Fact fact) {
    String impact = text(fact, "impact");
    try {
      return Callout.Severity.fromJsonName(impact == null ? "low" : impact);
    } catch (IllegalArgumentException e) {
      return Callout.Severity.LOW;
    }
  }

  private static BigDecimal confidence(Fact fact) {
    return BigDecimal.valueOf(fact.confidence()).setScale(2, RoundingMode.HALF_UP);
  }

  private static String percent(BigDecimal confidence) {
    return confidence.movePointRight(2).setScale(0, RoundingMode.HALF_UP) + "%";
  }

  private static String text(Fact fact, String attribute) {
    JsonNode value = fact.attributes().get(attribute);
    return value == null || !value.isString() ? null : value.stringValue();
  }

  private static String capitalized(String text) {
    return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
  }
}
