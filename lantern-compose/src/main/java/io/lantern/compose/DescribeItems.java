package io.lantern.compose;

import io.lantern.compose.DescribeRole.Described;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.derive.ComponentInteractions;
import io.lantern.derive.Components;
import io.lantern.derive.Knowledge;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Item;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import io.lantern.harness.render.Refs;
import io.lantern.summarize.Summary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Builds the Describe role's items (knowledge-build epic, section 11.4) from the facts of a
 * snapshot: one per deployable unit, frontends included, split when it has more than {@value
 * DescribeRole#MAX_COMPONENTS} components or {@value DescribeRole#MAX_INTERACTIONS} interactions,
 * and one per data store, destination, external system and person. Each item's rendering numbers
 * its references from 1, so it is the same text whichever items share its request.
 */
final class DescribeItems {

  /** The most facts shown about one component. */
  static final int MAX_COMPONENT_FACTS = 8;

  /** The most characters of one component's code. */
  static final int MAX_CODE_CHARS = 4_000;

  /** The most characters of code one unit's items show, over all their components. */
  static final int MAX_UNIT_CODE_CHARS = 40_000;

  /** The most facts shown about one element. */
  static final int MAX_ELEMENT_FACTS = 8;

  /** The most tables shown of a database. */
  static final int MAX_TABLES = 30;

  /** The most link facts shown of how an element is used. */
  static final int MAX_USES = 40;

  private static final Pattern MARKER = Pattern.compile("\\s*\\[(fact|summary):[^\\]\\s]+\\]");

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final C4Views c4;
  private final Set<Fact> alternatives;
  private final Function<Evidence, Optional<String>> files;
  private final Map<EntityId, Summary> summaries;

  DescribeItems(
      Knowledge knowledge,
      Assembler assembler,
      C4Views c4,
      Function<Evidence, Optional<String>> files,
      Map<EntityId, Summary> summaries) {
    this.knowledge = knowledge;
    this.assembler = assembler;
    this.c4 = c4;
    this.alternatives = c4.alternativeLinks().keySet();
    this.files = files;
    this.summaries = summaries;
  }

  /** The facts a summary cites, for a text that cites the summary. */
  DescribeRole.SummaryFacts summaryFacts() {
    return cited -> {
      Summary summary = summaries.get(cited.entity());
      List<Fact> facts = new ArrayList<>();
      if (summary != null) {
        for (String id : summary.citedFacts()) {
          Fact fact = knowledge.fact(id);
          if (fact != null) {
            facts.add(fact);
          }
        }
      }
      return facts;
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Units

  /** A component of a unit, as its items show it. */
  private record ComponentShown(
      EntityId entity, EntityId main, String name, String line, List<Fact> facts, Code code) {}

  /** The start of a type's code, from the line it starts at. */
  private record Code(Fact declaration, String repo, String path, int first, String text) {}

  /** An interaction of a unit, as its items show it. */
  private record Interaction(EntityId source, String line, Fact fact) {}

  /** The unit's items: one, or several over the same unit context when it is large. */
  List<Described> unit(EntityId unit) {
    List<ComponentShown> components = components(unit);
    List<Interaction> interactions = interactions(unit, components);
    List<Fact> unitFacts = FactLines.sorted(c4.containerFacts(unit));
    List<Fact> claims = Describer.claims(unit, knowledge);
    List<Fact> entryPoints =
        components.size() > 1
            ? List.of()
            : FactLines.sorted(Describer.entryPoints(unit, knowledge));
    if (claims.isEmpty()
        && entryPoints.isEmpty()
        && components.isEmpty()
        && interactions.isEmpty()) {
      return List.of();
    }
    List<Described> items = new ArrayList<>();
    if (components.size() <= DescribeRole.MAX_COMPONENTS
        && interactions.size() <= DescribeRole.MAX_INTERACTIONS) {
      items.add(unitItem(unit, 0, unitFacts, claims, entryPoints, components, interactions));
      return items;
    }
    // Too large for one item: the unit sentence with the first components, the other components
    // in groups of the same size, then the interactions in groups.
    int part = 0;
    for (int c = 0; c == 0 || c < components.size(); c += DescribeRole.MAX_COMPONENTS) {
      items.add(
          unitItem(
              unit,
              part++,
              unitFacts,
              claims,
              entryPoints,
              components.subList(c, Math.min(components.size(), c + DescribeRole.MAX_COMPONENTS)),
              List.of()));
    }
    for (int r = 0; r < interactions.size(); r += DescribeRole.MAX_INTERACTIONS) {
      items.add(
          unitItem(
              unit,
              part++,
              unitFacts,
              claims,
              entryPoints,
              List.of(),
              interactions.subList(
                  r, Math.min(interactions.size(), r + DescribeRole.MAX_INTERACTIONS))));
    }
    return items;
  }

  private Described unitItem(
      EntityId unit,
      int part,
      List<Fact> unitFacts,
      List<Fact> claims,
      List<Fact> entryPoints,
      List<ComponentShown> components,
      List<Interaction> interactions) {
    Refs refs = new Refs();
    List<FingerprintInput> inputs = new ArrayList<>();
    Set<String> evidenced = new LinkedHashSet<>();
    StringBuilder text = new StringBuilder();
    String name = assembler.name(unit);
    String technology = c4.technology(unit);
    text.append("The deployable unit ").append(FactLines.oneLine(name));
    if (technology != null) {
      text.append(", built with ").append(FactLines.oneLine(technology));
    }
    text.append(".\n");
    if (part > 0) {
      text.append("Part ")
          .append(part + 1)
          .append(" of the unit: say nothing about the unit itself.\n");
    }
    text.append("Facts that make it a unit:\n")
        .append(facts(unitFacts, refs, inputs, null))
        .append('\n');
    if (!claims.isEmpty()) {
      text.append("What its README claims:\n")
          .append(facts(claims, refs, inputs, evidenced))
          .append('\n');
    }
    if (!entryPoints.isEmpty()) {
      text.append("Its entry points:\n")
          .append(facts(entryPoints, refs, inputs, null))
          .append('\n');
    }
    Map<String, EntityId> componentRefs = new LinkedHashMap<>();
    Map<String, EntityId> mains = new LinkedHashMap<>();
    Map<String, String> names = new LinkedHashMap<>();
    List<String> summaryLines = new ArrayList<>();
    unitSummaries(unit)
        .forEach(s -> summaryLines.add(summaryLine(s, "the unit", refs, inputs, evidenced)));
    if (!components.isEmpty()) {
      text.append("Components:\n");
      for (int i = 0; i < components.size(); i++) {
        ComponentShown component = components.get(i);
        String ref = "C" + (i + 1);
        componentRefs.put(ref, component.entity());
        mains.put(ref, component.main());
        names.put(ref, component.name());
        text.append('[').append(Refs.token(ref)).append("] ").append(component.line()).append('\n');
        if (!component.facts().isEmpty()) {
          text.append(facts(component.facts(), refs, inputs, null)).append('\n');
        }
        Code code = component.code();
        if (code != null) {
          String token = refs.add('E', code.declaration().id(), code.declaration(), code.text());
          if (WordingPrompt.javadoc(code.text())) {
            evidenced.add(local(token));
          }
          inputs.add(FingerprintInput.of(code.declaration()));
          FingerprintInput.fileOf(code.declaration()).ifPresent(inputs::add);
          text.append(Blocks.code(token, code.repo(), code.path(), code.first(), code.text()))
              .append('\n');
        }
        Summary summary = summaries.get(component.main());
        if (summary != null) {
          summaryLines.add(summaryLine(summary, ref, refs, inputs, evidenced));
        }
      }
    }
    Map<String, Fact> interactionRefs = new LinkedHashMap<>();
    Map<String, EntityId> sources = new LinkedHashMap<>();
    Map<String, String> lines = new LinkedHashMap<>();
    if (!interactions.isEmpty()) {
      text.append("Interactions:\n");
      for (int i = 0; i < interactions.size(); i++) {
        Interaction interaction = interactions.get(i);
        String ref = "R" + (i + 1);
        interactionRefs.put(ref, interaction.fact());
        sources.put(ref, interaction.source());
        lines.put(ref, interaction.line());
        text.append('[')
            .append(Refs.token(ref))
            .append("] ")
            .append(interaction.line())
            .append('\n');
        text.append(facts(List.of(interaction.fact()), refs, inputs, null)).append('\n');
      }
    }
    if (!summaryLines.isEmpty()) {
      text.append("Summaries of its code:\n")
          .append(Blocks.tagged("summaries", summaryLines))
          .append('\n');
    }
    String subject = part == 0 ? unit.toString() : unit + "#" + (part + 1);
    Item item = new Item(subject, unit.toString(), inputs, text.toString().strip());
    return new Described(
        DescribeRole.Kind.UNIT,
        unit,
        name,
        part == 0,
        refs,
        componentRefs,
        mains,
        names,
        interactionRefs,
        sources,
        lines,
        evidenced,
        item);
  }

  /** The unit's components, as stored (#523), each with its facts and code. */
  private List<ComponentShown> components(EntityId unit) {
    List<ComponentShown> result = new ArrayList<>();
    int codeLeft = MAX_UNIT_CODE_CHARS;
    for (C4Views.Component component : c4.components(unit)) {
      List<Fact> facts = FactLines.sorted(c4.componentFacts(component));
      Code code = codeLeft > 0 ? code(component.main(), Math.min(MAX_CODE_CHARS, codeLeft)) : null;
      if (code != null) {
        codeLeft -= code.text().length();
      }
      String name = c4.componentName(component);
      String line = FactLines.oneLine(name);
      if (component.technology() != null && !component.technology().isEmpty()) {
        line += ", " + FactLines.oneLine(component.technology());
      }
      result.add(
          new ComponentShown(
              Components.id(unit, component.main()),
              component.main(),
              name,
              line,
              facts.subList(0, Math.min(MAX_COMPONENT_FACTS, facts.size())),
              code));
    }
    return result;
  }

  /**
   * The unit's interactions: its links to other containers, the lines between its components, and
   * the people who use it.
   */
  private List<Interaction> interactions(EntityId unit, List<ComponentShown> components) {
    List<Interaction> result = new ArrayList<>();
    for (String predicate : Describer.LINKS) {
      for (Fact link : FactLines.sorted(knowledge.relations(unit, predicate))) {
        if (alternatives.contains(link)) {
          continue;
        }
        EntityId other = Knowledge.object(link);
        result.add(
            new Interaction(
                unit,
                Describer.verb(predicate)
                    + ' '
                    + FactLines.oneLine(assembler.name(other))
                    + " ("
                    + WordingPrompt.kind(other)
                    + ")",
                link));
      }
    }
    Map<EntityId, String> componentNames = new TreeMap<>(Knowledge.BY_ID);
    components.forEach(c -> componentNames.put(c.entity(), c.name()));
    for (ComponentShown component : components) {
      for (Fact line :
          FactLines.sorted(
              knowledge.relations(component.entity(), ComponentInteractions.COMPONENT_LINK))) {
        if ("code-end".equals(RepoSections.attribute(line, "kind", ""))) {
          continue;
        }
        String other = componentNames.get(Knowledge.object(line));
        if (other == null) {
          continue;
        }
        result.add(
            new Interaction(
                component.entity(),
                FactLines.oneLine(component.name())
                    + ' '
                    + RepoSections.attribute(line, "kind", "calls")
                    + ' '
                    + FactLines.oneLine(other)
                    + " (a component)",
                line));
      }
    }
    SortedMap<EntityId, Fact> people = c4.people();
    String unitName = assembler.name(unit);
    for (Map.Entry<EntityId, Fact> person : people.entrySet()) {
      if (C4Views.strings(person.getValue(), "frontends").contains(unitName)) {
        result.add(
            new Interaction(
                person.getKey(),
                FactLines.oneLine(assembler.name(person.getKey())) + " uses it (a person)",
                person.getValue()));
      }
    }
    return result;
  }

  private List<Summary> unitSummaries(EntityId unit) {
    List<Summary> result = new ArrayList<>();
    for (EntityId module : C4Views.codeModules(knowledge, unit)) {
      Summary summary = summaries.get(module);
      if (summary != null) {
        result.add(summary);
      }
    }
    return result;
  }

  private String summaryLine(
      Summary summary, String of, Refs refs, List<FingerprintInput> inputs, Set<String> evidenced) {
    String text = FactLines.oneLine(MARKER.matcher(summary.text()).replaceAll(""));
    String token =
        refs.add(
            'S',
            summary.key(),
            new DescribeRole.SummaryFacts.Cited(summary.key(), summary.entity()),
            text);
    if (summary.purpose() == Summary.Purpose.EVIDENCED) {
      evidenced.add(local(token));
    }
    inputs.add(FingerprintInput.answer(summary.key()));
    return "[" + token + "] " + of + ": " + text;
  }

  /**
   * The start of the type's code from the file its {@code kind} fact was observed in, after its
   * package declaration and imports, with the number of its first line; null when none can be read.
   */
  private Code code(EntityId type, int max) {
    List<Fact> declarations =
        knowledge.attributes(type).stream()
            .filter(f -> f.predicate().equals("kind") && f.evidence().path() != null)
            .sorted(
                java.util.Comparator.comparing((Fact f) -> f.evidence().repo())
                    .thenComparing(f -> f.evidence().path())
                    .thenComparing(Fact::id))
            .toList();
    for (Fact declaration : declarations) {
      Optional<String> file = files.apply(declaration.evidence());
      if (file.isEmpty()) {
        continue;
      }
      List<String> lines = file.get().lines().toList();
      int start = codeStart(lines);
      StringBuilder kept = new StringBuilder();
      for (int i = start; i < lines.size(); i++) {
        if (kept.length() + lines.get(i).length() + 1 > max && kept.length() > 0) {
          break;
        }
        kept.append(lines.get(i)).append('\n');
      }
      if (kept.isEmpty()) {
        continue;
      }
      return new Code(
          declaration,
          declaration.evidence().repo(),
          declaration.evidence().path(),
          start + 1,
          kept.toString().stripTrailing());
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Elements

  /** The elements to describe: data stores, destinations, external systems, then people. */
  List<EntityId> elements() {
    List<EntityId> result = new ArrayList<>(c4.destinations());
    result.addAll(c4.people().keySet());
    return result;
  }

  /**
   * The element's item.
   *
   * @param responsibilities the responsibility of each unit, as earlier answers it may cite
   */
  Described element(EntityId element, Map<EntityId, DescribeRole.Answered> responsibilities) {
    Refs refs = new Refs();
    List<FingerprintInput> inputs = new ArrayList<>();
    Set<String> evidenced = new LinkedHashSet<>();
    StringBuilder text = new StringBuilder();
    String name = assembler.name(element);
    String kind = Describer.noun(element);
    text.append("The ").append(kind).append(' ').append(FactLines.oneLine(name));
    String technology = element.type().equals(C4Views.PERSON) ? null : c4.technology(element);
    if (technology != null) {
      text.append(", ").append(FactLines.oneLine(technology));
    }
    text.append(".\n");
    List<Fact> own = FactLines.sorted(knowledge.attributes(element));
    Fact basis = c4.people().get(element);
    if (basis != null && !own.contains(basis)) {
      own = FactLines.sorted(new ArrayList<>(List.of(basis)));
    }
    own = own.subList(0, Math.min(MAX_ELEMENT_FACTS, own.size()));
    if (!own.isEmpty()) {
      text.append("Facts about it:\n").append(facts(own, refs, inputs, null)).append('\n');
    }
    List<Fact> tables = tables(element);
    if (!tables.isEmpty()) {
      text.append("What is built in it:\n").append(facts(tables, refs, inputs, null)).append('\n');
    }
    List<String> answers = new ArrayList<>();
    Map<String, List<Fact>> uses = uses(element);
    if (!uses.isEmpty()) {
      text.append("How the units use it:\n");
      int shown = 0;
      for (Map.Entry<String, List<Fact>> use : uses.entrySet()) {
        List<Fact> links = FactLines.sorted(new LinkedHashSet<>(use.getValue()));
        links = links.subList(0, Math.min(links.size(), MAX_USES - shown));
        if (links.isEmpty()) {
          break;
        }
        shown += links.size();
        text.append(FactLines.oneLine(use.getKey())).append(":\n");
        text.append(facts(links, refs, inputs, null)).append('\n');
      }
    }
    for (EntityId unit : users(element)) {
      DescribeRole.Answered answered = responsibilities.get(unit);
      if (answered != null) {
        String token = refs.add('A', unit.toString(), answered, answered.text());
        inputs.add(
            FingerprintInput.answer(io.lantern.harness.Fingerprints.sha256(answered.text())));
        answers.add(
            "[" + token + "] " + FactLines.oneLine(assembler.name(unit)) + ": " + answered.text());
      }
    }
    if (!answers.isEmpty()) {
      text.append("What the units that use it do:\n")
          .append(Blocks.tagged("answers", answers))
          .append('\n');
    }
    Item item = new Item(element.toString(), element.type(), inputs, text.toString().strip());
    return new Described(
        DescribeRole.Kind.ELEMENT,
        element,
        name,
        true,
        refs,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        evidenced,
        item);
  }

  /** The units at the other end of the element's lines, or the frontends a person uses. */
  private SortedSet<EntityId> users(EntityId element) {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    if (element.type().equals(C4Views.PERSON)) {
      Fact basis = c4.people().get(element);
      if (basis != null) {
        List<String> names = C4Views.strings(basis, "frontends");
        for (EntityId frontend : c4.frontends()) {
          if (names.contains(assembler.name(frontend))) {
            result.add(frontend);
          }
        }
      }
      return result;
    }
    for (C4Views.Neighbour neighbour : c4.neighbours(element)) {
      result.add(neighbour.other());
    }
    return result;
  }

  private Map<String, List<Fact>> uses(EntityId element) {
    Map<String, List<Fact>> uses = new TreeMap<>();
    if (element.type().equals(C4Views.PERSON)) {
      return uses;
    }
    for (C4Views.Neighbour neighbour : c4.neighbours(element)) {
      String use =
          assembler.name(neighbour.other())
              + " ("
              + WordingPrompt.kind(neighbour.other())
              + ") "
              + Describer.use(neighbour);
      uses.computeIfAbsent(use, u -> new ArrayList<>()).addAll(neighbour.facts());
    }
    return uses;
  }

  private List<Fact> tables(EntityId element) {
    SortedSet<EntityId> schemas = new TreeSet<>(Knowledge.BY_ID);
    if (element.type().equals(C4Views.PERSON)) {
      return List.of();
    }
    for (C4Views.Neighbour neighbour : c4.neighbours(element)) {
      for (Fact fact : neighbour.facts()) {
        if (fact.predicate().equals(C4Views.USES_DATABASE)) {
          C4Views.strings(fact, "schemas").forEach(id -> schemas.add(EntityId.parse(id)));
        }
      }
    }
    List<Fact> tables = new ArrayList<>();
    for (EntityId schema : schemas) {
      for (EntityId table : knowledge.parts(schema, "table")) {
        tables.addAll(knowledge.containsFacts(schema, table));
      }
    }
    tables = FactLines.sorted(tables);
    return tables.subList(0, Math.min(MAX_TABLES, tables.size()));
  }

  /**
   * The index of a file's first line of code: after its package declaration and imports, or after a
   * leading comment that is not Javadoc when it has none, blank lines skipped.
   */
  static int codeStart(List<String> lines) {
    int start = 0;
    int last = -1;
    for (int i = 0; i < lines.size(); i++) {
      String bare = lines.get(i).strip();
      if (bare.startsWith("package ") || bare.startsWith("import ")) {
        last = i;
      } else if (!bare.isEmpty()
          && !bare.startsWith("/*")
          && !bare.startsWith("*")
          && !bare.startsWith("//")) {
        break;
      }
    }
    if (last >= 0) {
      start = last + 1;
    } else {
      while (start < lines.size() && lines.get(start).isBlank()) {
        start++;
      }
      if (start < lines.size()
          && lines.get(start).strip().startsWith("/*")
          && !WordingPrompt.javadoc(lines.get(start))) {
        while (start < lines.size() && !lines.get(start).contains("*/")) {
          start++;
        }
        start++;
      }
    }
    while (start < lines.size() && lines.get(start).isBlank()) {
      start++;
    }
    return Math.min(start, lines.size());
  }

  // ---------------------------------------------------------------------------------------------

  /** The facts as a {@code <facts>} block, each added to the item's references. */
  private static String facts(
      List<Fact> facts, Refs refs, List<FingerprintInput> inputs, Set<String> evidenced) {
    List<String> lines = new ArrayList<>();
    for (Fact fact : facts) {
      String line = FactLines.line(fact);
      String token = refs.add('F', fact.claimId() != null ? fact.claimId() : fact.id(), fact, line);
      if (evidenced != null) {
        evidenced.add(local(token));
      }
      inputs.add(FingerprintInput.of(fact));
      lines.add("[" + token + "] " + line);
    }
    return Blocks.tagged("facts", lines);
  }

  /** The item-local reference a token stands for. */
  private static String local(String token) {
    return Refs.plain(token);
  }
}
