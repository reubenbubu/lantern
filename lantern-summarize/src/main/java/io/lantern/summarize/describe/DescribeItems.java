package io.lantern.summarize.describe;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import io.lantern.derive.Components;
import io.lantern.derive.ElementNames;
import io.lantern.derive.Elements;
import io.lantern.derive.Knowledge;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Item;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import io.lantern.harness.render.Refs;
import io.lantern.summarize.Summary;
import io.lantern.summarize.describe.DescribeRole.Described;
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
public final class DescribeItems {

  /** The most facts shown about one component. */
  static final int MAX_COMPONENT_FACTS = 8;

  /** The most characters of one component's code. */
  public static final int MAX_CODE_CHARS = 4_000;

  /** The most facts shown about one element. */
  static final int MAX_ELEMENT_FACTS = 8;

  /** The most tables shown of a database. */
  static final int MAX_TABLES = 30;

  /** The most link facts shown of how an element is used. */
  static final int MAX_USES = 40;

  private static final Pattern MARKER = Pattern.compile("\\s*\\[(fact|summary):[^\\]\\s]+\\]");

  private final Knowledge knowledge;
  private final ElementNames assembler;
  private final Elements elements;
  private final Set<Fact> alternatives;
  private final Function<Evidence, Optional<String>> files;
  private final Map<EntityId, Summary> summaries;
  private Map<EntityId, List<Elements.Line>> otherLines;
  private Set<EntityId> unitsAndFrontends;

  DescribeItems(
      Knowledge knowledge,
      ElementNames assembler,
      Elements elements,
      Function<Evidence, Optional<String>> files,
      Map<EntityId, Summary> summaries) {
    this.knowledge = knowledge;
    this.assembler = assembler;
    this.elements = elements;
    this.alternatives = elements.alternativeLinks().keySet();
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

  /** The facts of the interactions the unit's items ask about, in the order they show them. */
  List<Fact> interactionFacts(EntityId unit) {
    return interactions(unit, components(unit)).stream().map(Interaction::fact).toList();
  }

  /** The unit's items: one, or several over the same unit context when it is large. */
  List<Described> unit(EntityId unit) {
    List<ComponentShown> components = components(unit);
    List<Interaction> interactions = interactions(unit, components);
    List<Fact> unitFacts = FactLines.sorted(elements.containerFacts(unit));
    List<Fact> claims = FactLines.sorted(Describer.claims(unit, knowledge));
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
    String technology = elements.technology(unit);
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
    for (Elements.Component component : elements.components(unit)) {
      List<Fact> facts = FactLines.sorted(elements.componentFacts(component));
      // Every component shows the start of its own code, whatever the others show, so a
      // component's excerpt never depends on another's length.
      Code code = code(component.main(), MAX_CODE_CHARS);
      String name = elements.componentName(component);
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
   * The unit's interactions: its links to other containers, every other line of the diagrams it
   * holds (#738), the lines between its components, and the people who use it.
   */
  private List<Interaction> interactions(EntityId unit, List<ComponentShown> components) {
    List<Interaction> result = new ArrayList<>();
    Set<Fact> asked = new java.util.HashSet<>();
    for (String predicate : Describer.LINKS) {
      for (Fact link : FactLines.sorted(knowledge.relations(unit, predicate))) {
        if (alternatives.contains(link)) {
          continue;
        }
        EntityId other = Knowledge.object(link);
        asked.add(link);
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
    for (Elements.Line line : otherLines().getOrDefault(unit, List.of())) {
      Fact fact = line.facts().getFirst();
      if (asked.add(fact)) {
        result.add(otherInteraction(unit, line, fact));
      }
    }
    // Every other interaction between two units or frontends, which the flows draw: messages one
    // sends that reach a listener of another, through a queue or topic.
    for (Fact link : FactLines.sorted(knowledge.about(unit))) {
      if (Knowledge.isEntity(link)
          && elements.vocabulary().isA(link.predicate(), Types.INTERACTS_WITH)
          && !alternatives.contains(link)
          && unitsAndFrontends().contains(Knowledge.object(link))
          && asked.add(link)) {
        EntityId other = Knowledge.object(link);
        result.add(
            new Interaction(
                unit,
                (link.predicate().equals(Types.SENDS_MESSAGES_TO)
                        ? "sends messages, through a queue or topic, to"
                        : "interacts with")
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
          FactLines.sorted(knowledge.relations(component.entity(), Types.COMPONENT_LINK))) {
        if ("code-end".equals(Elements.attribute(line, "kind", ""))) {
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
                    + Elements.attribute(line, "kind", "calls")
                    + ' '
                    + FactLines.oneLine(other)
                    + " (a component)",
                line));
      }
    }
    SortedMap<EntityId, Fact> people = elements.people();
    String unitName = assembler.name(unit);
    for (Map.Entry<EntityId, Fact> person : people.entrySet()) {
      if (Elements.strings(person.getValue(), "frontends").contains(unitName)) {
        result.add(
            new Interaction(
                person.getKey(),
                FactLines.oneLine(assembler.name(person.getKey())) + " uses it (a person)",
                person.getValue()));
      }
    }
    return result;
  }

  /**
   * Every line of the diagrams that Describe's fixed links leave out, other than a person's, by the
   * unit or frontend whose item asks about it (#738, ADR 0103): what Investigate found, a unit's
   * other interactions, the unknown caller's lines and routes between exchanges and queues. A line
   * is held by the end its link starts from when that end runs code, else by the other end; a route
   * between two destinations by the first unit, in ID order, with a line to either of its ends.
   */
  private Map<EntityId, List<Elements.Line>> otherLines() {
    if (otherLines != null) {
      return otherLines;
    }
    Set<EntityId> units = unitsAndFrontends();
    Map<EntityId, List<Elements.Line>> result = new LinkedHashMap<>();
    List<Elements.Line> lines = elements.lines();
    for (Elements.Line line : lines) {
      if (Describer.LINKS.contains(line.relationship())
          || line.from().type().equals(Types.PERSON)
          || line.facts().isEmpty()) {
        continue;
      }
      EntityId owner =
          units.contains(line.facts().getFirst().subject())
              ? line.facts().getFirst().subject()
              : units.contains(line.from())
                  ? line.from()
                  : units.contains(line.to()) ? line.to() : null;
      if (owner == null) {
        owner =
            lines.stream()
                .filter(
                    l ->
                        Set.of(line.from(), line.to()).contains(l.to())
                            || Set.of(line.from(), line.to()).contains(l.from()))
                .flatMap(l -> java.util.stream.Stream.of(l.from(), l.to()))
                .filter(units::contains)
                .min(Knowledge.BY_ID)
                .orElse(null);
      }
      if (owner != null) {
        result.computeIfAbsent(owner, u -> new ArrayList<>()).add(line);
      }
    }
    otherLines = result;
    return result;
  }

  /** The units and frontends, which Describe has an item for. */
  private Set<EntityId> unitsAndFrontends() {
    if (unitsAndFrontends == null) {
      Set<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
      result.addAll(Elements.units(knowledge));
      result.addAll(Elements.frontends(knowledge));
      unitsAndFrontends = result;
    }
    return unitsAndFrontends;
  }

  /** How one of the {@link #otherLines()} reads in the item of the unit that holds it. */
  private Interaction otherInteraction(EntityId unit, Elements.Line line, Fact fact) {
    String plain =
        switch (line.relationship()) {
          case Types.INVESTIGATED_CONNECTION -> "connects, as the LLM found, to";
          case Types.ROUTES_TO -> "routes messages to";
          case Types.CALLS_UNREACHED -> "calls entry points that nothing in the set reaches of";
          default ->
              line.plain().isEmpty()
                  ? "uses"
                  : Character.toLowerCase(line.plain().charAt(0)) + line.plain().substring(1);
        };
    if (line.from().equals(unit)) {
      EntityId other = line.to();
      return new Interaction(
          unit,
          plain
              + ' '
              + FactLines.oneLine(assembler.name(other))
              + " ("
              + WordingPrompt.kind(other)
              + ")",
          fact);
    }
    // A line the unit holds without starting it: the unknown caller's, or a route between two
    // destinations, read from the end it starts at.
    String to =
        line.to().equals(unit)
            ? "it"
            : FactLines.oneLine(assembler.name(line.to()))
                + " ("
                + WordingPrompt.kind(line.to())
                + ")";
    return new Interaction(
        line.from(),
        FactLines.oneLine(assembler.name(line.from()))
            + " ("
            + WordingPrompt.kind(line.from())
            + ") "
            + plain
            + ' '
            + to,
        fact);
  }

  private List<Summary> unitSummaries(EntityId unit) {
    List<Summary> result = new ArrayList<>();
    for (EntityId module : Elements.codeModules(knowledge, unit)) {
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
    inputs.add(FingerprintInput.answer(io.lantern.harness.Fingerprints.sha256(text)));
    return "[" + token + "] " + of + ": " + text;
  }

  /**
   * The start of the type's code from the file its {@code kind} fact was observed in, after its
   * package declaration and imports, with the number of its first line; null when none can be read.
   */
  private Code code(EntityId type, int max) {
    List<Fact> declarations =
        knowledge.attributes(type).stream()
            .filter(f -> f.predicate().equals(Types.KIND) && f.evidence().path() != null)
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
    List<EntityId> result = new ArrayList<>(elements.destinations());
    result.addAll(elements.people().keySet());
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
    String technology = element.type().equals(Types.PERSON) ? null : elements.technology(element);
    if (technology != null) {
      text.append(", ").append(FactLines.oneLine(technology));
    }
    text.append(".\n");
    List<Fact> own = FactLines.sorted(knowledge.attributes(element));
    Fact basis = elements.people().get(element);
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
    if (element.type().equals(Types.PERSON)) {
      Fact basis = elements.people().get(element);
      if (basis != null) {
        List<String> names = Elements.strings(basis, "frontends");
        for (EntityId frontend : elements.frontends()) {
          if (names.contains(assembler.name(frontend))) {
            result.add(frontend);
          }
        }
      }
      return result;
    }
    for (Elements.Neighbour neighbour : elements.neighbours(element)) {
      result.add(neighbour.other());
    }
    return result;
  }

  private Map<String, List<Fact>> uses(EntityId element) {
    Map<String, List<Fact>> uses = new TreeMap<>();
    if (element.type().equals(Types.PERSON)) {
      return uses;
    }
    for (Elements.Neighbour neighbour : elements.neighbours(element)) {
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
    if (element.type().equals(Types.PERSON)) {
      return List.of();
    }
    for (Elements.Neighbour neighbour : elements.neighbours(element)) {
      for (Fact fact : neighbour.facts()) {
        if (fact.predicate().equals(Types.USES_DATABASE)) {
          Elements.strings(fact, "schemas").forEach(id -> schemas.add(EntityId.parse(id)));
        }
      }
    }
    List<Fact> tables = new ArrayList<>();
    for (EntityId schema : schemas) {
      for (EntityId table : knowledge.parts(schema, Types.TABLE)) {
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
