package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Inline;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.Fact;
import io.lantern.derive.Elements;
import io.lantern.derive.ScopeFlows;
import io.lantern.derive.Unknowns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the model of a scope holds as open or unanswered, stated once, plainly, on the decisions and
 * unknowns page (Quality bar, C4 report; knowledge-build epic, section 12; #700): the links Lantern
 * could not make, the leads the LLM investigator could not decide or was not asked about, the units
 * it did not look at, and the elements whose responsibility is not known. Each is a query on the
 * model ({@link Unknowns}); the unknown caller joins them when the model holds it (#365).
 */
final class UnknownsPage {

  private final Unknowns unknowns;
  private final Elements elements;
  private final Assembler assembler;

  UnknownsPage(Unknowns unknowns, Elements elements, Assembler assembler) {
    this.unknowns = unknowns;
    this.elements = elements;
    this.assembler = assembler;
  }

  /** The page's callouts, in the order above; none when the model holds nothing open. */
  List<Block> blocks(String section) {
    List<Block> blocks = new ArrayList<>();
    notLinked(section).ifPresent(blocks::add);
    notDecided(section).ifPresent(blocks::add);
    notInvestigated(section).ifPresent(blocks::add);
    notDescribed(section).ifPresent(blocks::add);
    return blocks;
  }

  /** The calls, sends and datasources of the scope's units that Lantern could not link. */
  private Optional<Callout> notLinked(String section) {
    List<Inline> content = new ArrayList<>();
    List<Fact> facts = unknowns.openLinks();
    for (Fact fact : facts) {
      String reason = Elements.attribute(fact, "reason", null);
      List<String> keys = Elements.strings(fact, "unresolved");
      StringBuilder text =
          new StringBuilder(content.isEmpty() ? "" : " ")
              .append(assembler.name(fact.subject()))
              .append(": ")
              .append(fact.object().value());
      if (reason != null) {
        text.append(" (").append(reason.replace('-', ' '));
        if (!keys.isEmpty()) {
          text.append(" ").append(String.join(", ", keys));
        }
        text.append(")");
      }
      text.append('.');
      content.add(TextRun.of(text.toString()));
    }
    if (content.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Callout(
            assembler.id(section, "unresolved"),
            Callout.Variant.WARNING,
            null,
            "Not linked",
            content,
            assembler.facts(facts)));
  }

  /** The leads the LLM investigator left unclear, each with the units it concerns and why. */
  private Optional<Callout> notDecided(String section) {
    List<Unknowns.Lead> leads = unknowns.unclearLeads();
    if (leads.isEmpty()) {
      return Optional.empty();
    }
    List<Inline> content = new ArrayList<>();
    content.add(
        TextRun.of(
            "The LLM investigator could not say whether "
                + (leads.size() == 1 ? "this lead points" : "these leads point")
                + " to a risk:"));
    // Leads of one kind about the same units, unclear for the same reason, read as one line.
    Map<List<String>, Integer> lines = new LinkedHashMap<>();
    // A note: what the investigator could not decide cites nothing (Quality bar, ADR 0046); its
    // verdicts name the model that was asked, which a report reads the same whichever it was.
    for (Unknowns.Lead lead : leads) {
      String about =
          ScopeFlows.list(lead.units().stream().map(assembler::name).toList())
              + ": "
              + lead.kind().replace('-', ' ');
      lines.merge(List.of(about, lead.reason() == null ? "" : lead.reason()), 1, Integer::sum);
    }
    lines.forEach(
        (line, count) ->
            content.add(
                TextRun.of(
                    " "
                        + line.get(0)
                        + (count == 1 ? "" : ", " + count + " leads")
                        + (line.get(1).isEmpty() ? "" : " (" + line.get(1) + ")")
                        + ".")));
    return Optional.of(
        new Callout(
            assembler.id(section, "unclear-leads"),
            Callout.Variant.NOTE,
            null,
            "Not decided",
            content,
            List.of()));
  }

  /** The units, and the system, the LLM investigator did not look at. */
  private Optional<Callout> notInvestigated(String section) {
    List<String> names = unknowns.notInvestigated().stream().map(assembler::name).sorted().toList();
    if (names.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
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

  /** The elements without a responsibility sentence, whose job the report cannot state. */
  private Optional<Callout> notDescribed(String section) {
    Set<String> names = new LinkedHashSet<>();
    unknowns.containersWithoutResponsibility().forEach(e -> names.add(assembler.name(e)));
    for (Unknowns.Part part : unknowns.componentsWithoutResponsibility()) {
      names.add(componentName(part));
    }
    if (names.isEmpty()) {
      return Optional.empty();
    }
    // A note: what the model does not hold cites no fact (Quality bar, ADR 0046).
    return Optional.of(
        new Callout(
            assembler.id(section, "no-responsibility"),
            Callout.Variant.NOTE,
            null,
            "Responsibility not known",
            List.of(
                TextRun.of(
                    "Lantern holds no responsibility sentence for "
                        + ScopeFlows.list(List.copyOf(names))
                        + ": "
                        + (names.size() == 1 ? "it was" : "they were")
                        + " not described, so what "
                        + (names.size() == 1 ? "it does" : "they do")
                        + " is not known.")),
            List.of()));
  }

  /** A component's name, with the unit it is part of. */
  String componentName(Unknowns.Part part) {
    return elements.componentName(part.component()) + " in " + assembler.name(part.unit());
  }
}
