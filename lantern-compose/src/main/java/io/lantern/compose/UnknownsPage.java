package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Inline;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.Components;
import io.lantern.derive.Elements;
import io.lantern.derive.ScopeFlows;
import io.lantern.derive.UnitInteractions;
import io.lantern.derive.UnknownCallers;
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
 * model ({@link Unknowns}); and the units the Unknown caller calls (#766).
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
    unknownCaller(section).ifPresent(blocks::add);
    notDecided(section).ifPresent(blocks::add);
    notInvestigated(section).ifPresent(blocks::add);
    notDescribed(section).ifPresent(blocks::add);
    intentNotKnown(section).ifPresent(blocks::add);
    notAsked(section).ifPresent(blocks::add);
    return blocks;
  }

  /** The calls, sends and datasources of the scope's units that Lantern could not link. */
  private Optional<Callout> notLinked(String section) {
    List<Inline> content = new ArrayList<>();
    List<Fact> facts = unknowns.openLinks();
    for (Fact fact : facts) {
      String reason = Unknowns.reason(fact);
      List<String> keys = Unknowns.unresolvedKeys(fact);
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

  /**
   * The units whose entry points meant to be called nothing in the set reaches and no person uses
   * (ADR 0074, amendment for #365; #766): who calls them is unknown.
   */
  private Optional<Callout> unknownCaller(String section) {
    List<Unknowns.Caller> lines = unknowns.unknownCallers();
    if (lines.isEmpty()) {
      return Optional.empty();
    }
    List<Inline> content = new ArrayList<>();
    content.add(
        TextRun.of(
            "Nothing in the analysed repositories reaches these entry points and no person is"
                + " shown to use them, so who calls them is unknown; the diagrams draw an "
                + UnknownCallers.CALLER_NAME
                + " for them."));
    List<Fact> cited = new ArrayList<>();
    for (Unknowns.Caller line : lines) {
      cited.add(line.line());
      cited.addAll(line.entries());
      List<String> entries = line.entries().stream().map(e -> e.object().value()).toList();
      content.add(
          TextRun.of(" " + assembler.name(line.unit()) + ": " + String.join(", ", entries) + "."));
    }
    return Optional.of(
        new Callout(
            assembler.id(section, "unknown-caller"),
            Callout.Variant.NOTE,
            null,
            "Unknown caller",
            content,
            assembler.facts(cited)));
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
          ScopeFlows.list(lead.about().stream().map(assembler::name).toList())
              + ": "
              + lead.kind().replace('-', ' ');
      lines.merge(List.of(about, reason(lead.reason())), 1, Integer::sum);
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

  /**
   * Why a lead is unclear, as the reader needs it: a lead not asked is not asked, whatever error or
   * budget the run recorded after the colon, which varies from run to run.
   */
  private static String reason(String reason) {
    if (reason == null) {
      return "";
    }
    int colon = reason.indexOf(':');
    return (reason.startsWith("not asked") || reason.startsWith("the request failed")) && colon > 0
        ? reason.substring(0, colon)
        : reason;
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

  /**
   * The interactions with no stored intent (#741), each from where it starts to where it ends, so
   * what they are for is not known.
   */
  private Optional<Callout> intentNotKnown(String section) {
    List<UnitInteractions.Held> interactions = unknowns.interactionsWithoutIntent();
    if (interactions.isEmpty()) {
      return Optional.empty();
    }
    List<Inline> content = new ArrayList<>();
    content.add(
        TextRun.of(
            "Lantern holds no intent for "
                + (interactions.size() == 1 ? "this interaction" : "these interactions")
                + ", so what "
                + (interactions.size() == 1 ? "it is" : "they are")
                + " for is not known:"));
    // Each with what its line shows besides its ends, such as an endpoint, so two interactions
    // between the same ends read apart.
    Map<Fact, String> details = new java.util.HashMap<>();
    for (Elements.Line line : elements.lines()) {
      if (line.detail() != null && !line.detail().isBlank()) {
        line.facts().forEach(f -> details.putIfAbsent(f, line.detail()));
      }
    }
    for (UnitInteractions.Held interaction : interactions) {
      String detail = details.get(interaction.fact());
      content.add(
          TextRun.of(
              " "
                  + end(interaction.unit(), interaction.from())
                  + " to "
                  + end(interaction.unit(), interaction.to())
                  + (detail == null ? "" : " (" + detail + ")")
                  + "."));
    }
    // A note: what the model does not hold cites no fact (Quality bar, ADR 0046).
    return Optional.of(
        new Callout(
            assembler.id(section, "intent-not-known"),
            Callout.Variant.NOTE,
            null,
            "Intent not known",
            content,
            List.of()));
  }

  /**
   * The items a role did not ask the model about, or got no answer for (#741), each with the role
   * and why, so what it would have written is not known.
   */
  private Optional<Callout> notAsked(String section) {
    List<Unknowns.NotAsked> items = unknowns.notAsked();
    if (items.isEmpty()) {
      return Optional.empty();
    }
    List<Inline> content = new ArrayList<>();
    content.add(
        TextRun.of(
            "These items were not asked of the LLM, or got no answer, so what it would have"
                + " written of them is not known; the next run asks again:"));
    // Items of one role not asked for the same reason about the same thing read as one line.
    Map<List<String>, Integer> lines = new LinkedHashMap<>();
    for (Unknowns.NotAsked item : items) {
      String about = item.item() != null ? item.item() : assembler.name(item.subject());
      lines.merge(List.of(item.role(), about, reason(item.reason())), 1, Integer::sum);
    }
    lines.forEach(
        (line, count) ->
            content.add(
                TextRun.of(
                    " "
                        + line.get(0)
                        + ": "
                        + line.get(1)
                        + (count == 1 ? "" : ", " + count + " items")
                        + (line.get(2).isEmpty() ? "" : " (" + line.get(2) + ")")
                        + ".")));
    return Optional.of(
        new Callout(
            assembler.id(section, "unasked"),
            Callout.Variant.NOTE,
            null,
            "Not asked",
            content,
            List.of()));
  }

  /** An interaction's end as the reader knows it: a component with its unit, else its name. */
  private String end(EntityId unit, EntityId end) {
    for (Elements.Component component : elements.components(unit)) {
      if (Components.id(unit, component.main()).equals(end)) {
        return componentName(new Unknowns.Part(unit, component));
      }
    }
    return assembler.name(end);
  }

  /** A component's name, with the unit it is part of. */
  String componentName(Unknowns.Part part) {
    return elements.componentName(part.component()) + " in " + assembler.name(part.unit());
  }
}
