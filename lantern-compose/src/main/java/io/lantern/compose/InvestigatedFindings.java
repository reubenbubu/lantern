package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.Knowledge;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * What Investigate found (ADR 0110), for the documents of a scope: the risks that concern one of
 * its deployable units, most serious first, and the findings of the fixed checks it worded. Every
 * risk it found opens with {@value #FOUND_BY_LLM}, emphasized, and shows the file:line ranges
 * Lantern verified, so a reader can tell it from what static analysis found; a finding it worded
 * keeps the fixed check as its source and says the wording is the LLM's. The Executive Brief shows
 * them as risk callouts among its own; the other templates in a table of risks and findings, which
 * also names the units Investigate did not look at.
 */
final class InvestigatedFindings {

  /** What opens everything Investigate found, in every template (ADR 0110). */
  static final String FOUND_BY_LLM = "Found by the LLM:";

  /** What opens a fixed check's finding the LLM worded. */
  static final String WORDED_BY_LLM = "Found by a fixed check, worded by the LLM:";

  /** Investigate's risk facts (lantern-link's {@code Investigator.RISK}). */
  static final String RISK = "investigated-risk";

  /** Investigate's wordings of findings (lantern-link's {@code Investigator.FINDING_WORDING}). */
  static final String WORDING = "finding-wording";

  /** Investigate's connection facts (lantern-link's {@code Investigator.CONNECTION}). */
  static final String CONNECTION = "INVESTIGATED_CONNECTION";

  /** What Investigate left out (lantern-link's {@code Investigator.NOT_INVESTIGATED}). */
  static final String NOT_INVESTIGATED = "not-investigated";

  /** The title of the table of risks and findings. */
  static final String TITLE = "Risks and findings";

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final List<Fact> risks;
  private final Set<EntityId> notInvestigated;

  /**
   * @param units the deployable units in scope; a risk is shown if it concerns one of them
   */
  InvestigatedFindings(Knowledge knowledge, Assembler assembler, Set<EntityId> units) {
    this.knowledge = knowledge;
    this.assembler = assembler;
    List<Fact> found = new ArrayList<>();
    for (String predicate : List.of(RISK, WORDING)) {
      for (Fact fact : knowledge.withPredicate(predicate)) {
        if (units(fact).stream().anyMatch(units::contains)) {
          found.add(fact);
        }
      }
    }
    found.sort(
        Comparator.comparing((Fact f) -> severity(f).ordinal())
            .thenComparing(f -> text(f, "title"))
            .thenComparing(Fact::id));
    this.risks = List.copyOf(found);
    Set<EntityId> left = new TreeSet<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.withPredicate(NOT_INVESTIGATED)) {
      if (units.contains(fact.subject())) {
        left.add(fact.subject());
      }
    }
    this.notInvestigated = left;
  }

  /** Whether Investigate found any risk in scope. */
  boolean isEmpty() {
    return risks.isEmpty();
  }

  /** The risks as callouts of the Executive Brief's risks section. */
  List<Callout> callouts(String section) {
    List<Callout> callouts = new ArrayList<>();
    for (Fact risk : risks) {
      List<Inline> content = new ArrayList<>();
      content.add(new TextRun(opening(risk), TextRun.Style.EMPHASIS));
      content.add(TextRun.of(" " + text(risk, "finding") + " Evidence: "));
      content.addAll(evidence(risk));
      callouts.add(
          new Callout(
              assembler.id(section, "investigated", risk.object().value()),
              Callout.Variant.RISK,
              severity(risk),
              text(risk, "title"),
              content,
              assembler.facts(facts(risk)),
              text(risk, "consequence"),
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
      for (Fact risk : risks) {
        List<Inline> finding = new ArrayList<>();
        finding.add(new TextRun(opening(risk), TextRun.Style.EMPHASIS));
        finding.add(TextRun.of(" "));
        finding.add(new TextRun(text(risk, "title") + ".", TextRun.Style.STRONG));
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
                assembler.facts(facts(risk))));
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
    if (!notInvestigated.isEmpty()) {
      List<String> names = notInvestigated.stream().map(assembler::name).sorted().toList();
      blocks.add(
          new Callout(
              assembler.id(section, "not-investigated"),
              Callout.Variant.NOTE,
              null,
              "Not investigated",
              List.of(
                  TextRun.of(
                      "The LLM investigator did not look at "
                          + String.join(", ", names)
                          + ": a budget or the model left "
                          + (names.size() == 1 ? "it" : "them")
                          + " out, so risks there are unknown.")),
              List.of()));
    }
    return blocks;
  }

  /** A risk's opening: found by the LLM, or a fixed check's finding the LLM worded. */
  private static String opening(Fact fact) {
    return fact.predicate().equals(WORDING) ? WORDED_BY_LLM : FOUND_BY_LLM;
  }

  /**
   * The facts a row or callout rests on: a worded finding's also the fixed check's, so it is not
   * marked as the LLM's alone.
   */
  private List<Fact> facts(Fact fact) {
    List<Fact> facts = new ArrayList<>();
    facts.add(fact);
    if (fact.predicate().equals(WORDING)) {
      knowledge.about(fact.subject()).stream()
          .filter(f -> f.predicate().equals("finding-raised"))
          .forEach(facts::add);
    }
    return facts;
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
    if (fact.predicate().equals(RISK)) {
      units.add(fact.subject());
    }
    for (JsonNode unit : fact.attributes().path("units")) {
      units.add(EntityId.parse(unit.asString()));
    }
    return units;
  }

  private static Callout.Severity severity(Fact fact) {
    String impact = text(fact, "impact");
    try {
      return Callout.Severity.fromJsonName(impact.isEmpty() ? "low" : impact);
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
    return value == null || !value.isString() ? "" : value.stringValue();
  }

  private static String capitalized(String text) {
    return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
  }
}
