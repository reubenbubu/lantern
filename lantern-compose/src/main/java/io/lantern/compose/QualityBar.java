package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Citation;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Metrics;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.document.SequenceDiagram;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.document.Tree;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The Quality bar's checks that need no review ({@code docs/design.md}, Quality bar), made on a
 * composed document: every diagram's, every document's and those of the document's type, the
 * Architecture Explorer or the Executive Brief, told apart by their template. Each failure names
 * the check as the Quality bar words it and, where it can, the section, block or entity that failed
 * it (ADR 0028). What each check looks at is in ADR 0046.
 */
final class QualityBar {

  /** The template of the Architecture Explorer, whose own checks are made on it. */
  static final String EXPLORER = "architecture-explorer";

  /** The template of the Executive Brief, whose own checks are made on it. */
  static final String BRIEF = "executive-brief";

  /** The templates that draw a legend of the element kinds and line styles their diagrams use. */
  private static final Set<String> LEGEND_TEMPLATES = Set.of("c4-model-sequence-diagrams");

  /** The top-level sections the Brief's rows need that no section kind composes yet (#164). */
  static final String BLAST_RADIUS = "blast_radius";

  static final String TECHNOLOGY_CURRENCY = "technology_currency";
  static final String QUALITY_EVIDENCE = "quality_evidence";

  static final String TITLE = "A title naming its type and scope";
  static final String LEGEND = "A legend for every shape, color, line style and arrowhead used";
  static final String ONE_LEVEL = "One abstraction level";
  static final String ELEMENTS = "At most " + C4Views.MAX_ELEMENTS + " elements";
  static final String TECHNOLOGY = "Containers and components have a technology";
  static final String LABELLED = "Every line is labelled with an intent phrase";
  static final String LINE_FACTS = "Every line links to the facts behind it";
  static final String CONDITIONAL = "A conditional line names its profile";
  static final String PROTOCOL = "Lines between containers name a protocol";
  static final String FRAGMENTS = "At most one level of fragments";
  static final String ACRONYMS = "Acronyms are explained in the glossary";
  static final String READER = "It states its reader";
  static final String PINNED = "It pins its snapshot (commits) and date";
  static final String CLAIMS = "Every specific claim links to a fact or source";
  static final String REPEATED = "No diagram repeated across sections";
  static final String ORIENTATION = "An orientation section of at most 300 words";
  static final String START_HERE =
      "A \"start here\" list of the entry points and files to read first";
  static final String CONTAINER_FIRST = "The container diagram first";
  static final String FLOWS = "The main flows as sequence diagrams";
  static final String CATALOG =
      "A catalog of containers, components, endpoints, queues, tables and config";
  static final String DECISIONS = "Observed decisions, with rationale or marked unknown";
  static final String BLAST = "Who depends on it and what it depends on (blast radius)";
  static final String CURRENCY = "Technology currency";
  static final String RISKS = "Risks ranked by impact";
  static final String EVIDENCE = "Quality evidence";

  private static final int ORIENTATION_WORDS = 300;

  /** Labels that say only that there is a relationship, not what it is for. */
  private static final Set<String> GENERIC_LABELS =
      Set.of(
          "uses",
          "use",
          "depends on",
          "calls",
          "connects to",
          "talks to",
          "communicates with",
          "interacts with",
          "accesses",
          "sends",
          "sends to",
          "sends messages",
          "reads from",
          "writes to",
          "reads",
          "writes",
          "reads and writes");

  /** The entity types each diagram kind may show, besides the boundaries it draws others in. */
  private static final Set<String> CONTEXT_TYPES =
      Set.of(BuiltInTypes.SYSTEM, C4Views.EXTERNAL_SYSTEM, "person");

  private static final Set<String> CONTAINER_TYPES =
      Set.of(
          BuiltInTypes.SERVICE,
          C4Views.DATABASE,
          C4Views.TOPIC,
          C4Views.QUEUE,
          C4Views.EXCHANGE,
          C4Views.EXTERNAL_SYSTEM,
          "person");

  /** Element types that are not containers, so need no technology and draw no protocol. */
  private static final Set<String> OUTSIDE = Set.of(C4Views.EXTERNAL_SYSTEM, "person");

  /**
   * Two or more capitals, digits between them, and a plural s: AMQP, JDBC, APIs, B2B; not part of a
   * constant or version such as TAX_SERVICE_URL or 1.0-SNAPSHOT.
   */
  private static final Pattern ACRONYM =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_-])(\\p{Lu}[\\p{Lu}\\p{N}]*\\p{Lu})s?(?![\\p{L}\\p{N}_-])");

  /** Capitalised words that are not acronyms: HTTP methods. */
  private static final Set<String> NOT_ACRONYMS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

  private static final Pattern WORD = Pattern.compile("\\S+");

  private final Document document;
  private final Map<EntityId, DocumentEntity> entities = new HashMap<>();
  private final Map<String, Fact> facts = new HashMap<>();
  private final List<QualityReport.Failure> failures = new ArrayList<>();

  private QualityBar(Document document) {
    this.document = document;
    document.entities().forEach(e -> entities.put(e.id(), e));
    document.facts().forEach(f -> facts.put(f.id(), f));
  }

  /** The checks the document fails, in the order of the document, its own checks last. */
  static List<QualityReport.Failure> failures(Document document) {
    QualityBar bar = new QualityBar(Objects.requireNonNull(document, "document"));
    bar.check();
    return List.copyOf(bar.failures);
  }

  private void check() {
    List<Diagram> diagrams = new ArrayList<>();
    List<Block> all = new ArrayList<>();
    forEachBlock(document.sections(), all::add);
    Map<Set<String>, Block> seen = new HashMap<>();
    for (Block block : all) {
      switch (block) {
        case Diagram d -> {
          diagram(d);
          diagrams.add(d);
          repeated(d, shape(d), seen);
        }
        case SequenceDiagram s -> {
          sequence(s);
          repeated(s, shape(s), seen);
        }
        default -> {}
      }
      claims(block);
    }
    acronyms();
    if (document.audience() == null) {
      fail(
          READER,
          null,
          "The document names no audience; the view definition it was composed from has none.");
    }
    fail(
        PINNED,
        null,
        "The document names snapshot "
            + document.source().snapshot()
            + " but not the commits it is of or a date; the document model holds neither.");
    switch (document.template().name()) {
      case EXPLORER -> explorer(all);
      case BRIEF -> brief(all);
      default -> {}
    }
  }

  // Every diagram.

  private void diagram(Diagram diagram) {
    CrossReference.Target target = CrossReference.Target.block(diagram.id());
    Set<EntityId> boundaries = boundaries(diagram);
    title(diagram, target, boundaries);
    if (!LEGEND_TEMPLATES.contains(document.template().name())) {
      fail(LEGEND, target, "The template draws no legend.");
    }
    if (diagram.diagramKind() == Diagram.Kind.DEPENDENCY) {
      return;
    }
    List<String> offLevel = new ArrayList<>();
    List<String> withoutTechnology = new ArrayList<>();
    int elements = 0;
    for (Diagram.Node node : diagram.nodes()) {
      if (boundaries.contains(node.id())) {
        continue;
      }
      elements++;
      String type = node.id().type();
      boolean atLevel =
          switch (diagram.diagramKind()) {
            case C4_CONTEXT -> CONTEXT_TYPES.contains(type);
            case C4_CONTAINER -> CONTAINER_TYPES.contains(type);
            case C4_COMPONENT -> type.equals(BuiltInTypes.TYPE) || CONTAINER_TYPES.contains(type);
            case DEPENDENCY -> true;
          };
      if (!atLevel) {
        offLevel.add(node.label() + " (" + type + ")");
      }
      if (diagram.diagramKind() != Diagram.Kind.C4_CONTEXT
          && !OUTSIDE.contains(type)
          && node.technology() == null) {
        withoutTechnology.add(node.label());
      }
    }
    if (!offLevel.isEmpty()) {
      fail(ONE_LEVEL, target, "It also shows " + names(offLevel) + ".");
    }
    if (elements > C4Views.MAX_ELEMENTS) {
      fail(ELEMENTS, target, "It shows " + elements + " elements.");
    }
    if (!withoutTechnology.isEmpty()) {
      fail(TECHNOLOGY, target, "No technology for " + names(withoutTechnology) + ".");
    }
    Map<EntityId, String> labels = new HashMap<>();
    diagram.nodes().forEach(n -> labels.put(n.id(), n.label()));
    List<String> unlabelled = new ArrayList<>();
    List<String> unfounded = new ArrayList<>();
    List<String> unconditional = new ArrayList<>();
    List<String> noProtocol = new ArrayList<>();
    for (Diagram.Edge edge : diagram.edges()) {
      String line = labels.get(edge.from()) + " to " + labels.get(edge.to());
      if (isGeneric(edge.label())) {
        unlabelled.add(
            edge.label() == null ? line + " (no label)" : line + " (\"" + edge.label() + "\")");
      }
      if (edge.facts().isEmpty()) {
        unfounded.add(line);
      }
      missingProfiles(edge.facts(), edge.profiles()).ifPresent(p -> unconditional.add(line + p));
      if (diagram.diagramKind() == Diagram.Kind.C4_CONTAINER
          && !OUTSIDE.contains(edge.from().type())
          && !OUTSIDE.contains(edge.to().type())
          && edge.technology() == null) {
        noProtocol.add(line);
      }
    }
    lines(target, unlabelled, unfounded, unconditional);
    if (!noProtocol.isEmpty()) {
      fail(PROTOCOL, target, "No protocol on " + names(noProtocol) + ".");
    }
  }

  private void title(Diagram diagram, CrossReference.Target target, Set<EntityId> boundaries) {
    String title = diagram.title();
    if (title == null) {
      fail(TITLE, target, "It has no title.");
      return;
    }
    String lower = title.toLowerCase(Locale.ROOT);
    String type =
        switch (diagram.diagramKind()) {
          case C4_CONTEXT -> "context";
          case C4_CONTAINER -> "container";
          case C4_COMPONENT -> "component";
          case DEPENDENCY -> "dependenc";
        };
    // The scope is what the others are drawn inside, or with no boundary what the diagram shows.
    boolean scoped =
        diagram.nodes().stream()
            .filter(n -> boundaries.isEmpty() || boundaries.contains(n.id()))
            .anyMatch(n -> title.contains(n.label()));
    if (!lower.contains(type)) {
      fail(TITLE, target, "\"" + title + "\" does not name the diagram's type.");
    } else if (!scoped) {
      fail(TITLE, target, "\"" + title + "\" does not name the diagram's scope.");
    }
  }

  private void sequence(SequenceDiagram diagram) {
    CrossReference.Target target = CrossReference.Target.block(diagram.id());
    if (diagram.title() == null) {
      fail(TITLE, target, "It has no title.");
    }
    boolean async = hasAsync(diagram.steps());
    if (!LEGEND_TEMPLATES.contains(document.template().name())) {
      fail(LEGEND, target, "The template draws no legend.");
    } else if (async) {
      fail(LEGEND, target, "The template's legend has no arrowhead for asynchronous messages.");
    }
    if (diagram.participants().size() > C4Views.MAX_ELEMENTS) {
      fail(ELEMENTS, target, "It shows " + diagram.participants().size() + " participants.");
    }
    if (depth(diagram.steps()) > 1) {
      fail(FRAGMENTS, target, "Its fragments nest " + depth(diagram.steps()) + " levels deep.");
    }
    Map<EntityId, String> labels = new HashMap<>();
    diagram.participants().forEach(p -> labels.put(p.id(), participantName(p)));
    List<String> unlabelled = new ArrayList<>();
    List<String> unfounded = new ArrayList<>();
    List<String> unconditional = new ArrayList<>();
    forEachMessage(
        diagram.steps(),
        m -> {
          String line =
              labels.getOrDefault(m.from(), "unknown")
                  + " to "
                  + labels.getOrDefault(m.to(), "unknown");
          if (m.messageKind() != SequenceDiagram.MessageKind.RETURN && isGeneric(m.label())) {
            unlabelled.add(
                m.label() == null ? line + " (no label)" : line + " (\"" + m.label() + "\")");
          }
          if (m.facts().isEmpty()) {
            unfounded.add(line);
          }
          missingProfiles(m.facts(), m.profiles()).ifPresent(p -> unconditional.add(line + p));
        });
    lines(target, unlabelled, unfounded, unconditional);
  }

  private String participantName(SequenceDiagram.Participant participant) {
    return participant.label() != null
        ? participant.label()
        : entities.get(participant.id()).name();
  }

  private void lines(
      CrossReference.Target target,
      List<String> unlabelled,
      List<String> unfounded,
      List<String> unconditional) {
    if (!unlabelled.isEmpty()) {
      fail(
          LABELLED,
          target,
          "Labelled only with a generic verb or not at all: " + names(unlabelled) + ".");
    }
    if (!unfounded.isEmpty()) {
      fail(LINE_FACTS, target, "No facts behind " + names(unfounded) + ".");
    }
    if (!unconditional.isEmpty()) {
      fail(
          CONDITIONAL,
          target,
          "Holds only under a profile it does not name: " + names(unconditional) + ".");
    }
  }

  /**
   * The profiles a line's facts hold under that it does not name, when every fact holds under a
   * profile; otherwise the line holds by default and needs none.
   */
  private Optional<String> missingProfiles(List<String> lineFacts, List<String> named) {
    SortedSet<String> profiles = new TreeSet<>();
    for (String id : lineFacts) {
      Fact fact = facts.get(id);
      JsonNode profile = fact == null ? null : fact.attributes().get("profile");
      if (profile == null || profile.isNull()) {
        return Optional.empty();
      }
      profiles.add(profile.asString());
    }
    profiles.removeAll(named);
    return profiles.isEmpty()
        ? Optional.empty()
        : Optional.of(" (" + String.join(", ", profiles) + ")");
  }

  private static boolean isGeneric(String label) {
    if (label == null) {
      return true;
    }
    String plain = label.strip().toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "");
    return plain.isEmpty() || GENERIC_LABELS.contains(plain);
  }

  private static Set<EntityId> boundaries(Diagram diagram) {
    Set<EntityId> result = new HashSet<>();
    diagram
        .nodes()
        .forEach(
            n -> {
              if (n.parent() != null) {
                result.add(n.parent());
              }
            });
    return result;
  }

  private static boolean hasAsync(List<SequenceDiagram.Step> steps) {
    boolean[] found = {false};
    forEachMessage(steps, m -> found[0] |= m.messageKind() == SequenceDiagram.MessageKind.ASYNC);
    return found[0];
  }

  private static int depth(List<SequenceDiagram.Step> steps) {
    int depth = 0;
    for (SequenceDiagram.Step step : steps) {
      if (step instanceof SequenceDiagram.Frame frame) {
        for (SequenceDiagram.Operand operand : frame.operands()) {
          depth = Math.max(depth, 1 + depth(operand.steps()));
        }
      }
    }
    return depth;
  }

  private static void forEachMessage(
      List<SequenceDiagram.Step> steps, Consumer<SequenceDiagram.Message> action) {
    for (SequenceDiagram.Step step : steps) {
      switch (step) {
        case SequenceDiagram.Message m -> action.accept(m);
        case SequenceDiagram.Frame f ->
            f.operands().forEach(o -> forEachMessage(o.steps(), action));
        case SequenceDiagram.Note n -> {}
      }
    }
  }

  // Every document.

  /** What a diagram shows, whatever its kind: its elements and the lines between them. */
  private static Set<String> shape(Diagram diagram) {
    Set<EntityId> boundaries = boundaries(diagram);
    Set<String> shape = new HashSet<>();
    diagram.nodes().stream()
        .filter(n -> !boundaries.contains(n.id()))
        .forEach(n -> shape.add(n.id().toString()));
    diagram.edges().forEach(e -> shape.add(e.from() + " -> " + e.to()));
    return shape;
  }

  private static Set<String> shape(SequenceDiagram diagram) {
    Set<String> shape = new HashSet<>();
    diagram.participants().forEach(p -> shape.add(p.id().toString()));
    List<String> messages = new ArrayList<>();
    forEachMessage(
        diagram.steps(), m -> messages.add(m.from() + " -> " + m.to() + ": " + m.label()));
    shape.add(String.join("\n", messages));
    return shape;
  }

  private void repeated(Block diagram, Set<String> shape, Map<Set<String>, Block> seen) {
    if (shape.isEmpty()) {
      return;
    }
    Block earlier = seen.putIfAbsent(shape, diagram);
    if (earlier != null) {
      String title =
          switch (earlier) {
            case Diagram d -> d.title();
            case SequenceDiagram s -> s.title();
            default -> null;
          };
      fail(
          REPEATED,
          CrossReference.Target.block(diagram.id()),
          "It shows what "
              + (title == null ? "an earlier diagram" : "\"" + title + "\"")
              + " does.");
    }
  }

  /**
   * The elements of a block that state something specific and link to no fact: neither their own
   * nor, for an element about an entity, the entity's. A count of nothing and a note (which states
   * what is not known) cite nothing; the system in scope is Lantern's, not a claim.
   */
  private void claims(Block block) {
    List<String> uncited = new ArrayList<>();
    switch (block) {
      case Paragraph p -> {
        if (p.facts().isEmpty() && p.content().stream().noneMatch(QualityBar::cites)) {
          uncited.add("its text");
        }
      }
      case ListBlock l -> {
        for (ListBlock.Item item : l.items()) {
          if (item.facts().isEmpty() && item.content().stream().noneMatch(QualityBar::cites)) {
            uncited.add(oneLine(plain(item.content())));
          }
        }
      }
      case Callout c -> {
        if (c.variant() != Callout.Variant.NOTE
            && c.facts().isEmpty()
            && c.content().stream().noneMatch(QualityBar::cites)) {
          uncited.add(c.title() == null ? "its text" : c.title());
        }
      }
      case Table t -> {
        for (Table.Row row : t.rows()) {
          if (row.facts().isEmpty() && !hasFacts(row.entity())) {
            uncited.add(row.cells().isEmpty() ? "a row" : oneLine(plain(row.cells().getFirst())));
          }
        }
      }
      case Metrics m -> {
        for (Metrics.Item item : m.items()) {
          if (item.facts().isEmpty()
              && item.value().compareTo(BigDecimal.ZERO) != 0
              && !hasFacts(item.entity())) {
            uncited.add(item.label());
          }
        }
      }
      case Tree t -> t.roots().forEach(root -> uncitedNodes(root, uncited));
      case Diagram d -> {
        // A box drawn only as the end of a line, such as an external system, rests on its facts.
        Set<EntityId> ends = new HashSet<>();
        for (Diagram.Edge edge : d.edges()) {
          if (!edge.facts().isEmpty()) {
            ends.add(edge.from());
            ends.add(edge.to());
          }
        }
        for (Diagram.Node node : d.nodes()) {
          if (node.facts().isEmpty()
              && !node.id().type().equals(BuiltInTypes.SYSTEM)
              && !ends.contains(node.id())
              && !hasFacts(node.id())) {
            uncited.add(node.label());
          }
        }
      }
      case SequenceDiagram s -> {}
      case QualityReport q -> {}
    }
    if (!uncited.isEmpty()) {
      fail(CLAIMS, CrossReference.Target.block(block.id()), "No fact for " + names(uncited) + ".");
    }
  }

  private void uncitedNodes(Tree.Node node, List<String> uncited) {
    if (node.facts().isEmpty() && !hasFacts(node.entity())) {
      uncited.add(node.label());
    }
    node.children().forEach(child -> uncitedNodes(child, uncited));
  }

  private boolean hasFacts(EntityId entity) {
    return entity != null
        && entities.containsKey(entity)
        && !entities.get(entity).facts().isEmpty();
  }

  private static boolean cites(Inline inline) {
    return switch (inline) {
      case Citation c -> !c.facts().isEmpty();
      case CrossReference r -> r.citation();
      case TextRun t -> false;
    };
  }

  /**
   * The acronyms the document uses outside code that its glossary does not explain: a top-level
   * section titled Glossary, which none composes yet.
   */
  private void acronyms() {
    SortedSet<String> used = new TreeSet<>();
    StringBuilder glossary = new StringBuilder();
    Consumer<String> scan = text -> acronyms(text, used);
    scan.accept(document.title());
    for (Section section : document.sections()) {
      if (section.title().strip().equalsIgnoreCase("glossary")) {
        texts(List.of(section), text -> glossary.append(text).append(' '));
      } else {
        texts(List.of(section), scan);
      }
    }
    // Templates show every entity of the document, with its description, in their navigation.
    for (DocumentEntity entity : document.entities()) {
      scan.accept(entity.name());
      entity.description().forEach(inline -> inlineText(inline, scan));
    }
    if (glossary.isEmpty()) {
      if (!used.isEmpty()) {
        fail(ACRONYMS, null, "The document has no glossary; it uses " + names(used) + ".");
      }
      return;
    }
    SortedSet<String> explained = new TreeSet<>();
    acronyms(glossary.toString(), explained);
    used.removeAll(explained);
    if (!used.isEmpty()) {
      fail(ACRONYMS, null, "The glossary does not explain " + names(used) + ".");
    }
  }

  private static void acronyms(String text, Set<String> into) {
    Matcher matcher = ACRONYM.matcher(text);
    while (matcher.find()) {
      if (!NOT_ACRONYMS.contains(matcher.group(1))) {
        into.add(matcher.group(1));
      }
    }
  }

  /** Feeds every text a reader sees in the sections, outside code runs, to the consumer. */
  private static void texts(List<Section> sections, Consumer<String> text) {
    for (Section section : sections) {
      text.accept(section.title());
      for (Block block : section.blocks()) {
        switch (block) {
          case Diagram d -> {
            accept(d.title(), text);
            for (Diagram.Node node : d.nodes()) {
              text.accept(node.label());
              accept(node.technology(), text);
            }
            for (Diagram.Edge edge : d.edges()) {
              accept(edge.label(), text);
              accept(edge.technology(), text);
            }
          }
          case SequenceDiagram s -> {
            accept(s.title(), text);
            for (SequenceDiagram.Participant p : s.participants()) {
              accept(p.label(), text);
              accept(p.technology(), text);
            }
            forEachMessage(s.steps(), m -> accept(m.label(), text));
          }
          case Table t -> {
            accept(t.title(), text);
            t.columns().forEach(c -> text.accept(c.label()));
          }
          // A tree's labels are Maven coordinates and package names, which are code.
          case Tree t -> accept(t.title(), text);
          case Callout c -> accept(c.title(), text);
          case Metrics m -> m.items().forEach(item -> text.accept(item.label()));
          default -> {}
        }
        contents(block, content -> content.forEach(inline -> inlineText(inline, text)));
      }
      texts(section.sections(), text);
    }
  }

  private static void accept(String value, Consumer<String> text) {
    if (value != null) {
      text.accept(value);
    }
  }

  private static void inlineText(Inline inline, Consumer<String> text) {
    switch (inline) {
      case TextRun t -> {
        if (t.style() != TextRun.Style.CODE) {
          text.accept(t.text());
        }
      }
      case CrossReference r -> text.accept(r.text());
      case Citation c -> {}
    }
  }

  /** Feeds each piece of rich text in the block to the consumer. */
  private static void contents(Block block, Consumer<List<Inline>> content) {
    switch (block) {
      case Paragraph p -> content.accept(p.content());
      case ListBlock l -> l.items().forEach(item -> content.accept(item.content()));
      case Callout c -> content.accept(c.content());
      case Table t -> t.rows().forEach(row -> row.cells().forEach(content));
      case SequenceDiagram s -> {
        forEachStep(
            s.steps(),
            step -> {
              switch (step) {
                case SequenceDiagram.Message m -> content.accept(m.info());
                case SequenceDiagram.Note n -> content.accept(n.content());
                case SequenceDiagram.Frame f -> {}
              }
            });
      }
      case QualityReport q -> q.failures().forEach(f -> content.accept(f.detail()));
      default -> {}
    }
  }

  private static void forEachStep(
      List<SequenceDiagram.Step> steps, Consumer<SequenceDiagram.Step> action) {
    for (SequenceDiagram.Step step : steps) {
      action.accept(step);
      if (step instanceof SequenceDiagram.Frame f) {
        f.operands().forEach(o -> forEachStep(o.steps(), action));
      }
    }
  }

  // The Architecture Explorer.

  private void explorer(List<Block> all) {
    List<Section> sections = document.sections();
    if (sections.isEmpty()) {
      return;
    }
    Section orientation = sections.getFirst();
    int[] words = {0};
    countWords(orientation, words);
    if (words[0] > ORIENTATION_WORDS) {
      fail(
          ORIENTATION,
          CrossReference.Target.section(orientation.id()),
          "It has " + words[0] + " words.");
    }
    boolean startHere =
        orientation.sections().stream().anyMatch(s -> titled(s.title(), "start here"))
            || (sections.size() > 1 && titled(sections.get(1).title(), "start here"));
    if (!startHere) {
      fail(START_HERE, CrossReference.Target.section(orientation.id()), "There is none.");
    }
    Block first =
        all.stream()
            .filter(b -> b instanceof Diagram || b instanceof SequenceDiagram)
            .findFirst()
            .orElse(null);
    if (first == null) {
      fail(CONTAINER_FIRST, null, "The document has no diagram.");
    } else if (!(first instanceof Diagram d && d.diagramKind() == Diagram.Kind.C4_CONTAINER)) {
      fail(
          CONTAINER_FIRST,
          CrossReference.Target.block(first.id()),
          "The first diagram is not the container diagram.");
    }
    if (all.stream().noneMatch(b -> b instanceof SequenceDiagram)) {
      fail(FLOWS, null, "The document has no sequence diagram.");
    }
    if (sections.stream().noneMatch(s -> titled(s.title(), "catalog"))) {
      fail(CATALOG, null, "The document has no catalog section.");
    }
    boolean decisions =
        all.stream()
            .anyMatch(
                b ->
                    (b instanceof Table t && titled(t.title(), "decisions"))
                        || (b instanceof Callout c && titled(c.title(), "decisions")));
    if (!decisions) {
      fail(DECISIONS, null, "No decision records are listed, and none are marked unknown.");
    }
  }

  private static boolean titled(String title, String expected) {
    return title != null && title.strip().toLowerCase(Locale.ROOT).startsWith(expected);
  }

  private static void countWords(Section section, int[] words) {
    for (Block block : section.blocks()) {
      if (block instanceof Paragraph || block instanceof ListBlock || block instanceof Callout) {
        contents(
            block,
            content ->
                content.forEach(
                    inline ->
                        inlineText(
                            inline,
                            text -> {
                              Matcher matcher = WORD.matcher(text);
                              while (matcher.find()) {
                                words[0]++;
                              }
                            })));
      }
    }
    section.sections().forEach(sub -> countWords(sub, words));
  }

  // The Executive Brief.

  private void brief(List<Block> all) {
    Set<String> ids = new HashSet<>();
    document.sections().forEach(s -> ids.add(s.id()));
    if (!ids.contains(BLAST_RADIUS)) {
      fail(BLAST, null, "The brief has no section on who depends on it and what it depends on.");
    }
    if (!ids.contains(TECHNOLOGY_CURRENCY)) {
      fail(CURRENCY, null, "The brief has no section on runtime and framework versions.");
    }
    if (!ids.contains(QUALITY_EVIDENCE)) {
      fail(EVIDENCE, null, "The brief has no section on tests, architecture tests or coverage.");
    }
    Section risks =
        document.sections().stream()
            .filter(s -> s.id().equals(SectionKind.RISKS.jsonName()))
            .findFirst()
            .orElse(null);
    if (risks == null) {
      fail(RISKS, null, "The brief has no risks section.");
      return;
    }
    Callout.Severity last = Callout.Severity.HIGH;
    List<String> unranked = new ArrayList<>();
    boolean ordered = true;
    for (Block block : risks.blocks()) {
      if (block instanceof Callout c && c.variant() == Callout.Variant.RISK) {
        if (c.severity() == null) {
          unranked.add(c.title() == null ? oneLine(plain(c.content())) : c.title());
        } else {
          ordered &= c.severity().compareTo(last) >= 0;
          last = c.severity();
        }
      }
    }
    if (!unranked.isEmpty()) {
      fail(
          RISKS,
          CrossReference.Target.section(risks.id()),
          "No impact for " + names(unranked) + ".");
    }
    if (!ordered) {
      fail(
          RISKS,
          CrossReference.Target.section(risks.id()),
          "The risks are not in order of impact.");
    }
  }

  // Helpers.

  private static void forEachBlock(List<Section> sections, Consumer<Block> action) {
    for (Section section : sections) {
      section.blocks().forEach(action);
      forEachBlock(section.sections(), action);
    }
  }

  private void fail(String check, CrossReference.Target target, String detail) {
    failures.add(new QualityReport.Failure(check, target, List.of(TextRun.of(detail)), List.of()));
  }

  private static String plain(List<Inline> content) {
    StringBuilder text = new StringBuilder();
    content.forEach(inline -> inlineText(inline, text::append));
    return text.toString();
  }

  private static String oneLine(String text) {
    String line = text.strip().replaceAll("\\s+", " ");
    return line.length() > 60 ? line.substring(0, 59) + "…" : line;
  }

  /** The names as a sentence list: "a", "a and b", "a, b and c". */
  private static String names(Collection<String> names) {
    List<String> list = new ArrayList<>(new LinkedHashSet<>(names));
    if (list.size() == 1) {
      return list.getFirst();
    }
    String last = list.removeLast();
    return String.join(", ", list) + " and " + last;
  }
}
