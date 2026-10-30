package io.lantern.app;

import io.lantern.compose.SectionKind;
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
import io.lantern.core.fact.Origin;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders a document model into the report spec {@code report-specs/c4-model-sequence-diagrams}:
 * the document becomes the JSON its schema describes, written into the {@code arc-data} element of
 * its HTML template (ADR 0031). Like every renderer it makes no content decisions: what the
 * document holds is shown, what it lacks is left out, and nothing is added.
 *
 * <ul>
 *   <li>A section that holds a C4 or sequence diagram becomes a page, at the level of its first
 *       such diagram; its other blocks, and those of subsections without such a diagram, become the
 *       page's sections, with the leading paragraphs as the page summary. A top-level section after
 *       the first page that holds no such diagram and is not a context, containers, components or
 *       main flows section becomes a documentation page the same way, such as the decisions, the
 *       deployment, the catalog and the glossary: C4 pages hold only C4 content (ADR 0070). Content
 *       on no page, such as the sections before the first page, becomes the index's introduction
 *       where it is text or a list, each later section's title in bold before its first block, and
 *       is left out otherwise, except a quality report: each failure goes to the end of the page
 *       showing what failed, or of the first page.
 *   <li>Elements, relationships and table rows whose facts all come from the LLM are marked as
 *       found by the LLM.
 *   <li>The entities drawn in diagrams become elements, of the kind the diagrams draw them as: a
 *       box on a context diagram is a system, on a container diagram a container, on a component
 *       diagram a component, and a node other nodes are drawn inside is the system or container
 *       around them. An element drawn at several levels takes the widest. Its Lantern type is its
 *       tag when it differs from its kind, so a library is not shown as just a container.
 *   <li>Lantern IDs become the spec's kebab-case IDs ({@link ReportSpecIds}); two that would become
 *       the same are an error.
 *   <li>Citations have no place in the spec and are left out.
 * </ul>
 *
 * <p>Thread-safe.
 */
public final class C4ReportRenderer {

  /** The report spec's name, which is also the template name of documents rendered with it. */
  public static final String SPEC = "c4-model-sequence-diagrams";

  /** The ID of the template's element that holds the document's data. */
  static final String DATA_ID = "arc-data";

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private final ReportSpecTemplate template;

  C4ReportRenderer(ReportSpecTemplate template) {
    this.template = Objects.requireNonNull(template, "template");
  }

  /**
   * A renderer with the template from the class path.
   *
   * @throws IllegalStateException if the template is not on the class path
   */
  public static C4ReportRenderer fromClassPath() {
    return new C4ReportRenderer(ReportSpecTemplate.fromClassPath(SPEC, DATA_ID));
  }

  /**
   * The document as the report spec's HTML file.
   *
   * @throws IllegalArgumentException if two Lantern IDs would become the same report spec ID
   */
  public String render(Document document) {
    return template.render(MAPPER.writeValueAsString(toJson(document).data()));
  }

  /**
   * The report spec's data for a document.
   *
   * @param data the JSON the spec's schema describes
   * @param elements each element's report spec ID with the Lantern entity it came from
   */
  public record Result(ObjectNode data, SortedMap<String, EntityId> elements) {}

  /**
   * The report spec's data for a document.
   *
   * @throws IllegalArgumentException if two Lantern IDs would become the same report spec ID
   */
  public static Result toJson(Document document) {
    return new Mapping(Objects.requireNonNull(document, "document")).run();
  }

  /** Pretty-printed JSON, as written into the template. */
  static String write(ObjectNode data) {
    return MAPPER.writeValueAsString(data);
  }

  // The C4 kinds the diagrams draw elements as, widest first.
  private static final List<String> KINDS = List.of("system", "container", "component");

  /**
   * The kinds Lantern's types of data stores, queues and systems outside the scope are drawn as,
   * whatever level they are drawn at.
   */
  private static final Map<String, String> TYPE_KINDS =
      Map.of(
          "database", "database",
          "topic", "queue",
          "queue", "queue",
          "exchange", "queue",
          "external-system", "external",
          "person", "person");

  /** Lantern types not shown as a tag: a component's main Java type, an external system. */
  private static final Set<String> UNTAGGED = Set.of("type", "external-system");

  /** The catalogues after a diagram: the element kinds each lists, its title and its columns. */
  private record Catalogue(String suffix, Set<String> kinds, String title, List<String> columns) {}

  private static final List<String> DEFAULT_COLUMNS = List.of("name", "description", "technology");

  private static final List<Catalogue> CATALOGUES =
      List.of(
          new Catalogue("people", Set.of("person"), "People", DEFAULT_COLUMNS),
          new Catalogue("systems", Set.of("system"), "Systems", DEFAULT_COLUMNS),
          new Catalogue(
              "containers",
              Set.of("container"),
              "Containers",
              List.of("name", "description", "technology", "zoom")),
          new Catalogue(
              "stores", Set.of("database", "queue"), "Data Stores and Queues", DEFAULT_COLUMNS),
          new Catalogue("components", Set.of("component"), "Components", DEFAULT_COLUMNS),
          new Catalogue("external", Set.of("external"), "External Systems", DEFAULT_COLUMNS));

  /** The sections whose diagrams are at a C4 level or are sequences, as the composer names them. */
  private static final Set<String> C4_SECTIONS =
      Set.of(
          SectionKind.CONTEXT.jsonName(),
          SectionKind.CONTAINERS.jsonName(),
          SectionKind.COMPONENTS.jsonName(),
          SectionKind.SEQUENCES.jsonName());

  /** Element kinds a parent may have, per the schema's rules. */
  private static final Map<String, Set<String>> PARENT_KINDS =
      Map.of(
          "container", Set.of("system"),
          "database", Set.of("system", "container"),
          "queue", Set.of("system", "container"),
          "component", Set.of("container"));

  /** One run of the mapping over one document. */
  private static final class Mapping {

    private final Document document;
    private final Map<EntityId, DocumentEntity> entities = new HashMap<>();
    private final Map<String, Fact> facts = new HashMap<>();
    private final List<PagePlan> pages = new ArrayList<>();
    private final List<Part> loose = new ArrayList<>();
    private final ReportSpecIds pageIds = new ReportSpecIds("page");
    private final ReportSpecIds elementIds = new ReportSpecIds("element");

    /** Where each Lantern section and block went: a page, and a section of it or none. */
    private final Map<String, Location> sections = new HashMap<>();

    private final Map<String, Location> blocks = new HashMap<>();
    private final Map<EntityId, Element> elements = new LinkedHashMap<>();

    /** The first catalogue that lists each element, and the page about each element. */
    private final Map<EntityId, Location> catalogue = new HashMap<>();

    private final Map<EntityId, String> pageAbout = new HashMap<>();

    /** The title of each Lantern section and titled block. */
    private final Map<String, String> titles = new HashMap<>();

    Mapping(Document document) {
      this.document = document;
      document.entities().forEach(e -> entities.put(e.id(), e));
      document.facts().forEach(f -> facts.put(f.id(), f));
    }

    /** Whether there are facts and every one of them comes from the LLM. */
    private boolean foundByLlm(Collection<String> ids) {
      return !ids.isEmpty()
          && ids.stream()
              .allMatch(
                  id -> facts.containsKey(id) && facts.get(id).origin().kind() == Origin.Kind.LLM);
    }

    Result run() {
      document.sections().forEach(section -> plan(section, null));
      pages.forEach(this::placeOnPage);
      pages.forEach(this::collectElements);
      elements.values().forEach(e -> elementIds.id(e.id.toString()));
      pages.forEach(this::catalogue);
      distributeQualityReports();

      ObjectNode data = MAPPER.createObjectNode();
      data.set("document", documentNode());
      ObjectNode elementsNode = data.putObject("elements");
      SortedMap<String, EntityId> ids = new TreeMap<>();
      elements.values().forEach(e -> ids.put(elementIds.find(e.id.toString()), e.id));
      ids.forEach((id, entity) -> elementsNode.set(id, elementNode(elements.get(entity))));
      ArrayNode pagesNode = data.putArray("pages");
      pages.forEach(page -> pagesNode.add(pageNode(page)));
      return new Result(data, ids);
    }

    // Pages: which sections become pages, and which blocks go on each.

    private void plan(Section section, PagePlan enclosing) {
      String level = level(section.blocks());
      PagePlan target = enclosing;
      boolean first = true;
      if (level == null && enclosing == null && !pages.isEmpty() && isDocumentation(section)) {
        level = "documentation";
      }
      if (level != null) {
        target = new PagePlan(section, level, pageIds.id(section.id()));
        pages.add(target);
        if (section.entity() != null) {
          pageAbout.putIfAbsent(section.entity(), target.id);
        }
        first = false;
      }
      sections.put(section.id(), target == null ? null : new Location(target, null));
      titles.put(section.id(), section.title());
      for (Block block : section.blocks()) {
        String title =
            switch (block) {
              case Diagram d -> d.title();
              case SequenceDiagram d -> d.title();
              case Table t -> t.title();
              case Tree t -> t.title();
              case Callout c -> c.title();
              case QualityReport q -> q.title();
              default -> null;
            };
        if (title != null) {
          titles.put(block.id(), title);
        }
        Part part = new Part(section, block, first ? section.title() : null);
        first = false;
        (target == null ? loose : target.parts).add(part);
      }
      for (Section sub : section.sections()) {
        plan(sub, target);
      }
    }

    /**
     * Whether a top-level section without a C4 or sequence diagram is a documentation page: it is
     * not one of the sections whose diagrams the spec draws, whatever they hold when they draw
     * none, and it shows something other than quality reports, which go to the pages they are
     * about.
     */
    private static boolean isDocumentation(Section section) {
      if (C4_SECTIONS.contains(section.id())) {
        return false;
      }
      boolean shows = !section.sections().isEmpty();
      for (Block block : section.blocks()) {
        if (!(block instanceof QualityReport)) {
          shows = true;
        }
      }
      return shows;
    }

    /** The page level of the first C4 or sequence diagram among the blocks, or none. */
    private static String level(List<Block> blocks) {
      for (Block block : blocks) {
        String level =
            switch (block) {
              case SequenceDiagram s -> "sequence";
              case Diagram d ->
                  switch (d.diagramKind()) {
                    case C4_CONTEXT -> "context";
                    case C4_CONTAINER -> "containers";
                    case C4_COMPONENT -> "components";
                    case DEPENDENCY -> null;
                  };
              default -> null;
            };
        if (level != null) {
          return level;
        }
      }
      return null;
    }

    /**
     * Puts each failure of a quality report on no page (the document's own, ADR 0046) on the page
     * that shows what failed, or the first page when that is the document or on no page, as a list
     * at the end of the page. A report without failures is a success callout on the first page.
     */
    private void distributeQualityReports() {
      if (pages.isEmpty()) {
        return;
      }
      for (Part part : loose) {
        if (!(part.block instanceof QualityReport report)) {
          continue;
        }
        Map<PagePlan, List<QualityReport.Failure>> byPage = new LinkedHashMap<>();
        if (report.failures().isEmpty()) {
          byPage.put(pages.getFirst(), new ArrayList<>());
        }
        for (QualityReport.Failure failure : report.failures()) {
          PagePlan page = failure.target() == null ? null : pageOf(failure.target());
          byPage
              .computeIfAbsent(page == null ? pages.getFirst() : page, p -> new ArrayList<>())
              .add(failure);
        }
        String title = report.title() == null ? "Quality gaps" : report.title();
        byPage.forEach(
            (page, failures) ->
                page.quality.add(
                    new PageReport(
                        page.sectionIds.id(report.id()),
                        new QualityReport(report.id(), title, failures, report.facts()))));
        if (!blocks.containsKey(report.id())) {
          blocks.put(report.id(), new Location(pages.getFirst(), null));
        }
      }
    }

    /** The page a cross-reference target is on, or null. */
    private PagePlan pageOf(CrossReference.Target target) {
      Location location =
          switch (target.kind()) {
            case SECTION -> sections.get(target.id());
            case BLOCK -> blocks.get(target.id());
            case ENTITY -> {
              EntityId entity = EntityId.parse(target.id());
              String about = pageAbout.get(entity);
              if (about != null) {
                yield pages.stream()
                    .filter(p -> p.id.equals(about))
                    .findFirst()
                    .map(p -> new Location(p, null))
                    .orElse(null);
              }
              yield catalogue.get(entity);
            }
          };
      return location == null ? null : location.page;
    }

    /** Gives each block on the page its section ID; leading paragraphs form the summary. */
    /**
     * Gives each block on the page its section ID; leading paragraphs form the summary. A block
     * that shows nothing gets no section: links to it go to the next section shown, which also
     * takes its heading if it comes from the same Lantern section.
     */
    private void placeOnPage(PagePlan page) {
      boolean leading = true;
      Set<Section> placed = new HashSet<>();
      List<Part> shown = new ArrayList<>();
      List<String> pending = new ArrayList<>();
      Part pendingHeading = null;
      for (Part part : page.parts) {
        if (leading && part.block instanceof Paragraph && part.owner == page.section) {
          page.summary.add(part);
          shown.add(part);
          blocks.put(part.block.id(), new Location(page, null));
          continue;
        }
        leading = false;
        if (showsNothing(part.block)) {
          pending.add(part.block.id());
          if (part.heading != null) {
            pendingHeading = part;
          }
          continue;
        }
        if (pendingHeading != null) {
          if (part.heading == null && part.owner == pendingHeading.owner) {
            part = new Part(part.owner, part.block, pendingHeading.heading);
          }
          pendingHeading = null;
        }
        shown.add(part);
        String id = page.sectionIds.id(part.block.id());
        Location location = new Location(page, id);
        blocks.put(part.block.id(), location);
        pending.forEach(block -> blocks.put(block, location));
        pending.clear();
        if (part.owner != page.section && placed.add(part.owner)) {
          // A subsection placed on this page is linked to as its first block shown.
          sections.put(part.owner.id(), location);
        }
      }
      pending.forEach(block -> blocks.put(block, new Location(page, null)));
      page.parts.clear();
      page.parts.addAll(shown);
    }

    /** Whether a block maps to no section at all: an empty list or tree, or an empty sequence. */
    private boolean showsNothing(Block block) {
      return switch (block) {
        case ListBlock l -> l.items().isEmpty();
        case Tree t -> t.roots().isEmpty();
        case SequenceDiagram s -> {
          if (s.participants().size() >= 2) {
            yield false;
          }
          List<String> items = new ArrayList<>();
          s.steps().forEach(step -> stepItems(step, items));
          yield items.isEmpty();
        }
        default -> false;
      };
    }

    // Elements: the entities the diagrams draw, as the kind they are drawn as.

    private void collectElements(PagePlan page) {
      for (Part part : page.parts) {
        switch (part.block) {
          case Diagram d when d.diagramKind() != Diagram.Kind.DEPENDENCY -> {
            Set<EntityId> parents = parents(d);
            for (Diagram.Node node : d.nodes()) {
              int rank = rank(d.diagramKind(), parents.contains(node.id()));
              element(node.id()).seen(rank, node.parent(), node.technology());
              element(node.id()).facts.addAll(node.facts());
            }
          }
          case SequenceDiagram s -> {
            if (s.participants().size() >= 2) {
              for (SequenceDiagram.Participant participant : s.participants()) {
                element(participant.id()).seen(-1, null, participant.technology());
              }
            }
          }
          default -> {}
        }
      }
    }

    private Element element(EntityId id) {
      return elements.computeIfAbsent(id, Element::new);
    }

    private static Set<EntityId> parents(Diagram diagram) {
      Set<EntityId> parents = new HashSet<>();
      diagram.nodes().stream()
          .map(Diagram.Node::parent)
          .filter(Objects::nonNull)
          .forEach(parents::add);
      return parents;
    }

    /** The kind, as an index into {@link #KINDS}, that a diagram draws a node as. */
    private static int rank(Diagram.Kind kind, boolean around) {
      return switch (kind) {
        case C4_CONTEXT -> 0;
        case C4_CONTAINER -> around ? 0 : 1;
        default -> around ? 1 : 2;
      };
    }

    /** Notes the first catalogue section that lists each element. */
    private void catalogue(PagePlan page) {
      for (Part part : page.parts) {
        if (part.block instanceof Diagram d && d.diagramKind() != Diagram.Kind.DEPENDENCY) {
          for (EntityId box : boxes(d)) {
            Catalogue group = group(elements.get(box));
            catalogue.putIfAbsent(box, new Location(page, catalogueId(page, d, group)));
          }
        }
      }
    }

    private static Catalogue group(Element element) {
      String kind = element.kind();
      return CATALOGUES.stream().filter(c -> c.kinds().contains(kind)).findFirst().orElseThrow();
    }

    private String catalogueId(PagePlan page, Diagram diagram, Catalogue group) {
      return page.sectionIds.id(diagram.id() + "-" + group.suffix());
    }

    /** The diagram's boundary: the node others are drawn inside, if one is the section's entity. */
    private EntityId boundary(Diagram diagram, Section owner) {
      Set<EntityId> parents = parents(diagram);
      EntityId candidate = null;
      if (owner.entity() != null && parents.contains(owner.entity())) {
        candidate = owner.entity();
      } else if (parents.size() == 1) {
        candidate = parents.iterator().next();
      }
      if (candidate == null) {
        return null;
      }
      for (Diagram.Node node : diagram.nodes()) {
        if (candidate.equals(shownParent(elements.get(node.id())))) {
          return candidate;
        }
      }
      return null;
    }

    /** The element's parent, if it is an element of a kind the schema allows as its parent. */
    private EntityId shownParent(Element element) {
      EntityId parent = element.parent();
      if (parent == null || !elements.containsKey(parent)) {
        return null;
      }
      String parentKind = elements.get(parent).kind();
      return PARENT_KINDS.getOrDefault(element.kind(), Set.of()).contains(parentKind)
          ? parent
          : null;
    }

    /** The nodes drawn as boxes: all but those other nodes are drawn inside. */
    private static List<EntityId> boxes(Diagram diagram) {
      Set<EntityId> parents = parents(diagram);
      return diagram.nodes().stream()
          .map(Diagram.Node::id)
          .filter(id -> !parents.contains(id))
          .toList();
    }

    // JSON.

    private ObjectNode documentNode() {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("id", document.id());
      node.put("title", document.title());
      // A later section's title heads its first block, as on a page, unless the block has its own.
      Section first = document.sections().isEmpty() ? null : document.sections().getFirst();
      String intro =
          loose.stream()
              .map(
                  part -> {
                    String markdown = looseMarkdown(part.block);
                    return markdown.isEmpty()
                            || markdown.startsWith("**")
                            || part.heading == null
                            || part.owner == first
                        ? markdown
                        : "**" + oneLine(part.heading) + "**\n" + markdown;
                  })
              .filter(s -> !s.isEmpty())
              .collect(Collectors.joining("\n\n"));
      String pinned = pinnedMarkdown();
      node.put("subtitle", intro.isEmpty() ? pinned : intro + "\n\n" + pinned);
      node.put("version", Long.toString(document.source().snapshot()));
      node.put("updated", document.source().date().toString());
      return node;
    }

    /** Who the document is for and the commits it is pinned to, as Lantern's templates say it. */
    private String pinnedMarkdown() {
      StringBuilder text = new StringBuilder();
      if (document.audience() != null) {
        text.append("For ").append(oneLine(document.audience())).append(". ");
      }
      Map<String, String> commits = document.source().commits();
      text.append("Pinned to ").append(commits.size() == 1 ? "commit " : "commits ");
      text.append(
          commits.entrySet().stream()
              .map(c -> c.getKey() + " `" + c.getValue().substring(0, 12) + "`")
              .collect(Collectors.joining(", ")));
      return text.append(", as of ").append(document.source().date()).append('.').toString();
    }

    private String looseMarkdown(Block block) {
      return switch (block) {
        case Paragraph p -> markdown(p.content());
        case ListBlock l ->
            l.items().stream()
                .map(item -> "- " + oneLine(markdown(item.content())))
                .collect(Collectors.joining("\n"));
        case Callout c -> calloutMarkdown(c);
        default -> "";
      };
    }

    private ObjectNode elementNode(Element element) {
      DocumentEntity entity = entities.get(element.id);
      ObjectNode node = MAPPER.createObjectNode();
      String kind = element.kind();
      node.put("kind", kind);
      node.put("name", entity.name());
      EntityId parent = shownParent(element);
      if (parent != null) {
        node.put("parent", elementIds.find(parent.toString()));
      }
      String type = element.id.type();
      // A queue element is a topic, queue or exchange: the tag says which.
      if ((!type.equals(kind) || kind.equals("queue")) && !UNTAGGED.contains(type)) {
        node.put("tag", tag(type));
      }
      if (element.technology != null) {
        node.put("technology", element.technology);
      }
      String description = plain(entity.description());
      if (!description.isBlank()) {
        node.put("description", description);
      }
      Set<String> cited = new HashSet<>(entity.facts());
      cited.addAll(element.facts);
      if (foundByLlm(cited)) {
        node.put("llm", true);
      }
      return node;
    }

    private static String tag(String type) {
      if (type.equals("repo")) {
        return "Repository";
      }
      if (type.equals("frontend")) {
        return "Single-Page App";
      }
      String words = type.replace('-', ' ');
      return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    private ObjectNode pageNode(PagePlan page) {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("id", page.id);
      node.put("level", page.level);
      node.put("title", page.section.title());
      if (!page.summary.isEmpty()) {
        node.put(
            "summary",
            page.summary.stream()
                .map(part -> markdown(((Paragraph) part.block).content()))
                .collect(Collectors.joining("\n\n")));
      }
      EntityId about = page.section.entity();
      // A documentation page is about the whole document, not the element its section names.
      if (about != null && elements.containsKey(about) && !page.level.equals("documentation")) {
        node.put("element", elementIds.find(about.toString()));
      }
      ArrayNode sectionsNode = node.putArray("sections");
      for (Part part : page.parts) {
        if (page.summary.contains(part)) {
          continue;
        }
        List<ObjectNode> made = sections(page, part);
        if (!made.isEmpty() && part.heading != null && !made.getFirst().has("title")) {
          made.getFirst().put("title", part.heading);
        }
        made.forEach(sectionsNode::add);
      }
      for (PageReport report : page.quality) {
        sectionsNode.add(qualityReport(report.sectionId, report.report));
      }
      return node;
    }

    private List<ObjectNode> sections(PagePlan page, Part part) {
      String id = blocks.get(part.block.id()).sectionId;
      return switch (part.block) {
        case Paragraph p -> List.of(section("text", id, null).put("body", markdown(p.content())));
        case ListBlock l -> List.of(list(id, null, listItems(l)));
        case Callout c ->
            List.of(
                section("callout", id, null)
                    .put("body", calloutMarkdown(c))
                    .put("tone", c.variant() == Callout.Variant.NOTE ? "info" : "warning"));
        case Table t when isDecisions(t) -> List.of(decisions(id, t));
        case Table t -> List.of(table(id, t));
        case Metrics m -> List.of(stats(id, m));
        case Tree t -> List.of(list(id, t.title(), treeItems(t)));
        case QualityReport q -> List.of(qualityReport(id, q));
        case Diagram d when d.diagramKind() == Diagram.Kind.DEPENDENCY ->
            List.of(dependencyGraph(id, d, part.owner));
        case Diagram d -> diagram(page, id, d, part.owner);
        case SequenceDiagram s -> sequence(page, id, s, part.owner);
      };
    }

    private static ObjectNode section(String type, String id, String title) {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("type", type);
      node.put("id", id);
      if (title != null) {
        node.put("title", title);
      }
      return node;
    }

    private ObjectNode list(String id, String title, List<String> items) {
      ObjectNode node = section("list", id, title);
      ArrayNode array = node.putArray("items");
      items.forEach(array::add);
      return node;
    }

    private List<String> listItems(ListBlock list) {
      List<String> items = new ArrayList<>();
      for (int i = 0; i < list.items().size(); i++) {
        String text = oneLine(markdown(list.items().get(i).content()));
        items.add(list.ordered() ? (i + 1) + ". " + text : text);
      }
      return items;
    }

    private String calloutMarkdown(Callout callout) {
      String title = callout.title();
      if (callout.severity() != null) {
        String severity = callout.severity().jsonName();
        title = (title == null ? "Risk" : title) + " (" + severity + ")";
      }
      String body = markdown(callout.content());
      // A risk's consequence and confidence (ADR 0052) follow its text.
      if (callout.consequence() != null) {
        body += "\n" + markdown(List.of(TextRun.of("Consequence: " + callout.consequence())));
      }
      if (callout.confidence() != null) {
        body +=
            "\nConfidence: "
                + callout.confidence().movePointRight(2).setScale(0, RoundingMode.HALF_UP)
                + "%";
      }
      return title == null ? body : "**" + oneLine(title) + "**\n" + body;
    }

    private ObjectNode table(String id, Table table) {
      ObjectNode node = section("table", id, table.title());
      ArrayNode columns = node.putArray("columns");
      table.columns().forEach(c -> columns.add(c.label()));
      ArrayNode rows = node.putArray("rows");
      List<Integer> llmRows = new ArrayList<>();
      for (Table.Row row : table.rows()) {
        if (foundByLlm(row.facts())) {
          llmRows.add(rows.size());
        }
        ArrayNode cells = rows.addArray();
        row.cells().forEach(cell -> cells.add(oneLine(markdown(cell))));
      }
      if (!llmRows.isEmpty()) {
        ArrayNode marked = node.putArray("llmRows");
        llmRows.forEach(marked::add);
      }
      return node;
    }

    /** Whether every row of a table is about a decision record, so it is a decisions section. */
    private static boolean isDecisions(Table table) {
      return !table.rows().isEmpty()
          && table.rows().stream()
              .allMatch(row -> row.entity() != null && row.entity().type().equals("decision"))
          && table.columns().stream().anyMatch(c -> c.label().equals("Decision"));
    }

    /**
     * Decision records: each row's cells by column, ID, Decision (the title, else the entity's
     * name), Status, and Outcome and Rationale (the body, the rationale under its label).
     */
    private ObjectNode decisions(String id, Table table) {
      ObjectNode node = section("decisions", id, table.title());
      ArrayNode items = node.putArray("items");
      for (Table.Row row : table.rows()) {
        Map<String, String> cells = new HashMap<>();
        for (int i = 0; i < table.columns().size(); i++) {
          cells.put(table.columns().get(i).label(), markdown(row.cells().get(i)));
        }
        ObjectNode item = items.addObject();
        putNonEmpty(item, "id", oneLine(cells.getOrDefault("ID", "")));
        String title = oneLine(cells.getOrDefault("Decision", ""));
        item.put("title", title.isEmpty() ? entities.get(row.entity()).name() : title);
        String outcome = cells.getOrDefault("Outcome", "");
        String rationale = cells.getOrDefault("Rationale", "");
        String body =
            rationale.isEmpty()
                ? outcome
                : (outcome.isEmpty() ? "" : outcome + "\n\n") + "**Rationale:** " + rationale;
        putNonEmpty(item, "body", body);
        putNonEmpty(item, "status", oneLine(cells.getOrDefault("Status", "")));
      }
      return node;
    }

    private static void putNonEmpty(ObjectNode node, String field, String value) {
      if (!value.isEmpty()) {
        node.put(field, value);
      }
    }

    private ObjectNode stats(String id, Metrics metrics) {
      ObjectNode node = section("stats", id, null);
      ArrayNode items = node.putArray("items");
      for (Metrics.Item item : metrics.items()) {
        ObjectNode tile = items.addObject();
        tile.put("label", item.label());
        tile.put(
            "value", item.value().toPlainString() + (item.unit() == null ? "" : " " + item.unit()));
      }
      return node;
    }

    private List<String> treeItems(Tree tree) {
      List<String> items = new ArrayList<>();
      tree.roots().forEach(root -> treeItems(root, 0, items));
      return items;
    }

    private void treeItems(Tree.Node node, int depth, List<String> items) {
      items.add("  ".repeat(depth) + node.label());
      node.children().forEach(child -> treeItems(child, depth + 1, items));
    }

    private ObjectNode qualityReport(String id, QualityReport report) {
      String title = report.title() == null ? "Quality report" : report.title();
      if (report.failures().isEmpty()) {
        return section("callout", id, title)
            .put("body", "Every check Lantern makes on this document passed.")
            .put("tone", "success");
      }
      List<String> items = new ArrayList<>();
      for (QualityReport.Failure failure : report.failures()) {
        StringBuilder item = new StringBuilder("**").append(oneLine(failure.check())).append("**");
        if (failure.target() != null) {
          String target = link(failure.target(), targetName(failure.target()));
          item.append(" (").append(target).append(")");
        }
        String detail = oneLine(markdown(failure.detail()));
        if (!detail.isEmpty()) {
          item.append(": ").append(detail);
        }
        items.add(item.toString());
      }
      return list(id, title, items);
    }

    /** What a cross-reference target is called: an entity's name, a section's or block's title. */
    private String targetName(CrossReference.Target target) {
      return switch (target.kind()) {
        case ENTITY -> entities.get(EntityId.parse(target.id())).name();
        case SECTION, BLOCK -> titles.getOrDefault(target.id(), target.id());
      };
    }

    private ObjectNode dependencyGraph(String id, Diagram diagram, Section owner) {
      String title = diagram.title() == null ? owner.title() : diagram.title();
      if (diagram.edges().isEmpty()) {
        return list(id, title, diagram.nodes().stream().map(Diagram.Node::label).toList());
      }
      ObjectNode node = section("table", id, title);
      ArrayNode columns = node.putArray("columns");
      columns.add("From").add("To").add("Relationship");
      ArrayNode rows = node.putArray("rows");
      Map<EntityId, String> labels = new HashMap<>();
      diagram.nodes().forEach(n -> labels.put(n.id(), n.label()));
      for (Diagram.Edge edge : diagram.edges()) {
        rows.addArray()
            .add(labels.getOrDefault(edge.from(), edge.from().toString()))
            .add(labels.getOrDefault(edge.to(), edge.to().toString()))
            .add(edge.label() == null ? "" : edge.label());
      }
      return node;
    }

    private List<ObjectNode> diagram(PagePlan page, String id, Diagram diagram, Section owner) {
      String title = diagram.title() == null ? owner.title() : diagram.title();
      ObjectNode node = section("diagram", id, title);
      if (diagram.title() != null) {
        node.put("caption", diagram.title());
      }
      EntityId boundary = boundary(diagram, owner);
      if (boundary != null) {
        node.put("boundary", elementIds.find(boundary.toString()));
      }
      if (diagram.diagramKind() == Diagram.Kind.C4_CONTEXT
          && owner.entity() != null
          && diagram.nodes().stream().anyMatch(n -> n.id().equals(owner.entity()))
          && elements.get(owner.entity()).kind().equals("system")) {
        node.put("focus", elementIds.find(owner.entity().toString()));
      }
      List<EntityId> boxes = boxes(diagram);
      Set<EntityId> related = new HashSet<>();
      ArrayNode relationships = MAPPER.createArrayNode();
      for (Diagram.Edge edge : diagram.edges()) {
        ObjectNode relationship = relationships.addObject();
        relationship.put("from", elementIds.find(edge.from().toString()));
        relationship.put("to", elementIds.find(edge.to().toString()));
        String label = edge.label();
        if (!edge.profiles().isEmpty()) {
          String only =
              "only with the Spring profile"
                  + (edge.profiles().size() > 1 ? "s " : " ")
                  + String.join(", ", edge.profiles());
          label = label == null ? "Only " + only.substring(5) : label + " (" + only + ")";
        }
        if (label != null) {
          relationship.put("label", label);
        }
        if (edge.technology() != null) {
          relationship.put("technology", edge.technology());
        }
        if (foundByLlm(edge.facts())) {
          relationship.put("llm", true);
        }
        related.add(edge.from());
        related.add(edge.to());
      }
      List<EntityId> alone = boxes.stream().filter(b -> !related.contains(b)).toList();
      if (!alone.isEmpty()) {
        ArrayNode extra = node.putArray("elements");
        alone.forEach(b -> extra.add(elementIds.find(b.toString())));
      }
      node.set("relationships", relationships);
      List<ObjectNode> result = new ArrayList<>(List.of(node));
      for (Catalogue group : CATALOGUES) {
        List<EntityId> listed =
            boxes.stream().filter(b -> group.kinds().contains(elements.get(b).kind())).toList();
        if (listed.isEmpty()) {
          continue;
        }
        ObjectNode catalogueNode =
            section("elements", catalogueId(page, diagram, group), group.title());
        catalogueNode.put("eyebrow", "Catalogue");
        ArrayNode ids = catalogueNode.putObject("select").putArray("ids");
        listed.forEach(b -> ids.add(elementIds.find(b.toString())));
        if (!group.columns().equals(DEFAULT_COLUMNS)) {
          ArrayNode columns = catalogueNode.putArray("columns");
          group.columns().forEach(columns::add);
        }
        result.add(catalogueNode);
      }
      return result;
    }

    private List<ObjectNode> sequence(
        PagePlan page, String id, SequenceDiagram diagram, Section owner) {
      String title = diagram.title() == null ? owner.title() : diagram.title();
      List<EntityId> participants =
          diagram.participants().stream().map(SequenceDiagram.Participant::id).toList();
      if (participants.size() < 2) {
        // The spec draws a sequence between two participants or more; fewer is a list of steps.
        List<String> items = new ArrayList<>();
        diagram.steps().forEach(step -> stepItems(step, items));
        return List.of(list(id, title, items));
      }
      ObjectNode node = section("sequence", id, title);
      ArrayNode participantsNode = node.putArray("participants");
      participants.forEach(p -> participantsNode.add(elementIds.find(p.toString())));
      ArrayNode steps = node.putArray("steps");
      diagram.steps().forEach(step -> step(step, participants, steps));
      ObjectNode walkthrough =
          section("steps", page.sectionIds.id(diagram.id() + "-steps"), "Steps");
      walkthrough.put("of", id);
      return List.of(node, walkthrough);
    }

    private void step(SequenceDiagram.Step step, List<EntityId> participants, ArrayNode steps) {
      switch (step) {
        case SequenceDiagram.Message m when m.from() == null || m.to() == null -> {
          ObjectNode note = steps.addObject();
          note.put("note", messageText(m));
          ArrayNode over = note.putArray("over");
          participants.forEach(p -> over.add(elementIds.find(p.toString())));
        }
        case SequenceDiagram.Message m -> {
          ObjectNode message = steps.addObject();
          message.put("from", elementIds.find(m.from().toString()));
          message.put("to", elementIds.find(m.to().toString()));
          message.put("message", m.label() == null ? "" : m.label());
          if (m.code() != null) {
            message.put("code", m.code());
          }
          if (m.messageKind() != null && m.messageKind() != SequenceDiagram.MessageKind.SYNC) {
            message.put("type", m.messageKind().jsonName());
          }
          if (m.activate() != null) {
            message.put("activate", m.activate());
          }
          if (m.deactivate() != null) {
            message.put("deactivate", m.deactivate());
          }
          String info = oneLine(markdown(m.info()));
          if (!m.profiles().isEmpty()) {
            String profiles =
                m.profiles().stream().map(p -> "`" + p + "`").collect(Collectors.joining(", "));
            info =
                (info.isEmpty() ? "" : info + " ")
                    + "Only with the Spring profile"
                    + (m.profiles().size() > 1 ? "s " : " ")
                    + profiles
                    + ".";
          }
          if (!info.isEmpty()) {
            message.put("info", info);
          }
        }
        case SequenceDiagram.Note n -> {
          ObjectNode note = steps.addObject();
          note.put("note", plain(n.content()));
          ArrayNode over = note.putArray("over");
          (n.over().isEmpty() ? participants : n.over())
              .forEach(p -> over.add(elementIds.find(p.toString())));
        }
        case SequenceDiagram.Frame f -> {
          for (int i = 0; i < f.operands().size(); i++) {
            SequenceDiagram.Operand operand = f.operands().get(i);
            if (i == 0) {
              ObjectNode frame = steps.addObject();
              frame.put("frame", f.operator().name().toLowerCase(Locale.ROOT));
              if (operand.guard() != null) {
                frame.put("label", operand.guard());
              }
            } else {
              steps.addObject().put("else", operand.guard() == null ? "" : operand.guard());
            }
            operand.steps().forEach(s -> step(s, participants, steps));
          }
          steps.addObject().put("end", true);
        }
      }
    }

    private void stepItems(SequenceDiagram.Step step, List<String> items) {
      switch (step) {
        case SequenceDiagram.Message m -> items.add(messageText(m));
        case SequenceDiagram.Note n -> items.add(plain(n.content()));
        case SequenceDiagram.Frame f ->
            f.operands().forEach(o -> o.steps().forEach(s -> stepItems(s, items)));
      }
    }

    /** A message as text: who sends it to whom, then what it is. An unknown end says so. */
    private String messageText(SequenceDiagram.Message message) {
      String what =
          message.label() == null
              ? (message.code() == null ? "" : message.code())
              : message.label() + (message.code() == null ? "" : " [" + message.code() + "]");
      return name(message.from())
          + " → "
          + name(message.to())
          + (what.isEmpty() ? "" : ": " + what);
    }

    private String name(EntityId entity) {
      return entity == null ? "unknown" : entities.get(entity).name();
    }

    // Rich text.

    /** Rich text as the spec's Markdown-lite; citations are left out. */
    private String markdown(List<Inline> content) {
      StringBuilder text = new StringBuilder();
      for (Inline inline : content) {
        switch (inline) {
          case TextRun run -> text.append(run(run));
          case CrossReference reference when reference.citation() -> {}
          case CrossReference reference -> text.append(link(reference.target(), reference.text()));
          case Citation citation -> {}
        }
      }
      return text.toString().strip();
    }

    private static String run(TextRun run) {
      if (run.style() == null) {
        return run.text();
      }
      String mark =
          switch (run.style()) {
            case STRONG -> "**";
            case EMPHASIS -> "*";
            case CODE -> "`";
          };
      String text = run.text();
      if (text.isBlank() || text.contains(mark.substring(0, 1)) || !text.strip().equals(text)) {
        return text;
      }
      return mark + text + mark;
    }

    /** A link to where a cross-reference's target went, or the plain text if it is on no page. */
    private String link(CrossReference.Target target, String text) {
      String href =
          switch (target.kind()) {
            case SECTION -> href(sections.get(target.id()));
            case BLOCK -> href(blocks.get(target.id()));
            case ENTITY -> {
              EntityId entity = EntityId.parse(target.id());
              Location row = catalogue.get(entity);
              if (row != null) {
                yield "#/" + row.page.id + "/el-" + elementIds.find(entity.toString());
              }
              String page = pageAbout.get(entity);
              yield page == null ? null : "#/" + page;
            }
          };
      String label = text.replace('[', '(').replace(']', ')');
      return href == null ? text : "[" + label + "](" + href + ")";
    }

    private static String href(Location location) {
      if (location == null) {
        return null;
      }
      return "#/" + location.page.id + (location.sectionId == null ? "" : "/" + location.sectionId);
    }

    /** Rich text as plain text, for fields the spec shows without Markdown. */
    private static String plain(List<Inline> content) {
      StringBuilder text = new StringBuilder();
      for (Inline inline : content) {
        switch (inline) {
          case TextRun run -> text.append(run.text());
          case CrossReference reference when reference.citation() -> {}
          case CrossReference reference -> text.append(reference.text());
          case Citation citation -> {}
        }
      }
      return text.toString().strip();
    }

    private static String oneLine(String text) {
      return text.replaceAll("\\s*\\n\\s*", " ").strip();
    }
  }

  /** A section that becomes a page, and the blocks on it. */
  private static final class PagePlan {
    final Section section;
    final String level;
    final String id;
    final List<Part> parts = new ArrayList<>();
    final List<Part> summary = new ArrayList<>();
    final ReportSpecIds sectionIds = new ReportSpecIds("section");

    /** The failures of the document's quality report shown on this page. */
    final List<PageReport> quality = new ArrayList<>();

    PagePlan(Section section, String level, String id) {
      this.section = section;
      this.level = level;
      this.id = id;
    }
  }

  /**
   * A block, the Lantern section it is in, and the heading it starts with when it is the first
   * block of a subsection placed on another section's page.
   */
  private record Part(Section owner, Block block, String heading) {}

  /** The part of a quality report shown on one page, and its section ID there. */
  private record PageReport(String sectionId, QualityReport report) {}

  /** A page, and the section of it, or none for the page itself. */
  private record Location(PagePlan page, String sectionId) {}

  /** An entity drawn in diagrams, and how. */
  private static final class Element {
    final EntityId id;

    /** The facts its diagram nodes cite. */
    final Set<String> facts = new HashSet<>();

    private int rank = Integer.MAX_VALUE;
    private final Map<Integer, EntityId> parentByRank = new HashMap<>();
    String technology;

    Element(EntityId id) {
      this.id = id;
    }

    /**
     * Notes one appearance: the kind it is drawn as (an index into {@link #KINDS}, or -1 for a
     * sequence participant, which says nothing about the kind), its parent and its technology.
     */
    void seen(int rank, EntityId parent, String technology) {
      if (rank >= 0) {
        this.rank = Math.min(this.rank, rank);
        if (parent != null) {
          parentByRank.putIfAbsent(rank, parent);
        }
      }
      if (this.technology == null && technology != null) {
        this.technology = technology;
      }
    }

    /**
     * The kind its Lantern type is always drawn as (a data store, queue, external system or
     * person), else the widest kind it is drawn as; a sequence participant drawn in no diagram is a
     * container.
     */
    String kind() {
      String byType = TYPE_KINDS.get(id.type());
      if (byType != null) {
        return byType;
      }
      return rank == Integer.MAX_VALUE ? "container" : KINDS.get(rank);
    }

    /** Its parent where it is drawn as its kind. */
    EntityId parent() {
      return parentByRank.get(rank);
    }
  }
}
