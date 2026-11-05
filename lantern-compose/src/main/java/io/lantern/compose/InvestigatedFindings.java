package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.Elements;
import io.lantern.derive.InvestigatedRisks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What Investigate found (ADR 0110), for the documents of a scope: the risks that concern one of
 * its deployable units, most serious first, and the findings of the fixed checks it worded. Every
 * risk it found opens with {@value #FOUND_BY_LLM}, emphasized, and shows the file:line ranges
 * Lantern verified, so a reader can tell it from what static analysis found; a finding it worded
 * keeps the fixed check as its source and says the wording is the LLM's. The Executive Brief shows
 * them as risk callouts among its own; the other templates in a table of risks and findings. The
 * risks of one lead kind fold under one parent, a callout or an expandable row naming the kind,
 * their count and the most serious of them, grouped here from the stored risks (#747). The units
 * Investigate did not look at are on the unknowns page ({@link UnknownsPage}).
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
    for (List<InvestigatedRisks.Risk> group : groups()) {
      if (group.size() > 1) {
        callouts.add(parentCallout(section, group));
        continue;
      }
      InvestigatedRisks.Risk risk = group.getFirst();
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
      for (List<InvestigatedRisks.Risk> group : groups()) {
        if (group.size() == 1) {
          rows.add(row(group.getFirst()));
          continue;
        }
        InvestigatedRisks.Risk first = group.getFirst();
        List<Inline> finding = new ArrayList<>();
        finding.add(new TextRun(FOUND_BY_LLM, TextRun.Style.EMPHASIS));
        finding.add(TextRun.of(" "));
        finding.add(new TextRun(count(group) + ".", TextRun.Style.STRONG));
        rows.add(
            new Table.Row(
                null,
                List.of(
                    List.of(TextRun.of(capitalized(severity(first).jsonName()))),
                    finding,
                    List.of(),
                    List.of(),
                    List.of()),
                facts(group),
                group.stream().map(this::row).toList()));
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

  /** A risk's row: its impact, title and finding, consequence, confidence and places. */
  private Table.Row row(InvestigatedRisks.Risk risk) {
    List<Inline> finding = new ArrayList<>();
    finding.add(new TextRun(opening(risk), TextRun.Style.EMPHASIS));
    finding.add(TextRun.of(" "));
    finding.add(new TextRun(risk.title() + ".", TextRun.Style.STRONG));
    finding.add(TextRun.of(" " + risk.finding()));
    return new Table.Row(
        null,
        List.of(
            List.of(TextRun.of(capitalized(severity(risk).jsonName()))),
            finding,
            List.of(TextRun.of(risk.consequence())),
            List.of(TextRun.of(percent(confidence(risk)))),
            evidence(risk)),
        assembler.facts(risk.facts()));
  }

  /**
   * The risks in groups, most serious first: the risks of confirmed leads of one kind are one
   * group, ordered as its most serious risk; every other risk or worded finding is a group of its
   * own.
   */
  private List<List<InvestigatedRisks.Risk>> groups() {
    Map<String, List<InvestigatedRisks.Risk>> groups = new LinkedHashMap<>();
    for (InvestigatedRisks.Risk risk : risks) {
      String kind = leadKind(risk);
      groups
          .computeIfAbsent(
              kind.isEmpty() ? risk.fact().id() : "kind " + kind, k -> new ArrayList<>())
          .add(risk);
    }
    return List.copyOf(groups.values());
  }

  private static String leadKind(InvestigatedRisks.Risk risk) {
    return risk.leadKind();
  }

  /** A group's kind and count, such as "Send before commit: 13 risks". */
  private static String count(List<InvestigatedRisks.Risk> group) {
    return capitalized(leadKind(group.getFirst()).replace('-', ' '))
        + ": "
        + group.size()
        + " risks";
  }

  /** The facts of every risk in the group, each once. */
  private List<String> facts(List<InvestigatedRisks.Risk> group) {
    List<Fact> all = new ArrayList<>();
    group.forEach(r -> r.facts().stream().filter(f -> !all.contains(f)).forEach(all::add));
    return assembler.facts(all);
  }

  /**
   * A group as one callout: its kind and count, the most serious risk's finding, places,
   * consequence and confidence, and every risk's facts.
   */
  private Callout parentCallout(String section, List<InvestigatedRisks.Risk> group) {
    InvestigatedRisks.Risk first = group.getFirst();
    List<Inline> content = new ArrayList<>();
    content.add(new TextRun(FOUND_BY_LLM, TextRun.Style.EMPHASIS));
    content.add(
        TextRun.of(" The most serious: " + first.title() + ". " + first.finding() + " Evidence: "));
    content.addAll(evidence(first));
    return new Callout(
        assembler.id(section, "investigated", "kind " + leadKind(first)),
        Callout.Variant.RISK,
        severity(first),
        count(group),
        content,
        facts(group),
        first.consequence(),
        confidence(first));
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
