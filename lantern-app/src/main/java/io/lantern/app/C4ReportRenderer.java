package io.lantern.app;

import io.lantern.compose.DiagramLimits;
import io.lantern.compose.PageTitles;
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
import io.lantern.core.types.Types;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *       deployment, the catalog and the glossary: C4 pages hold only C4 content (ADR 0086). Content
 *       on no page, such as the sections before the first page, becomes the index's introduction
 *       where it is text or a list, each later section's title in bold before its first block, and
 *       is left out otherwise, except a quality report: each failure goes to the end of the page
 *       showing what failed, or of the first page.
 *   <li>Elements, relationships and table rows whose evidence all comes from the LLM are marked as
 *       found by the LLM: their facts, leaving out the texts an LLM role only worded (a summary,
 *       responsibility, intent, narrative, purpose or name), are all LLM findings. Where the mark
 *       shows, the text does not open with "Found by the LLM:" as well (#749).
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
  private final DiagramLimits limits;

  C4ReportRenderer(ReportSpecTemplate template) {
    this(template, DiagramLimits.DEFAULT);
  }

  C4ReportRenderer(ReportSpecTemplate template, DiagramLimits limits) {
    this.template = Objects.requireNonNull(template, "template");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /**
   * A renderer with the template from the class path.
   *
   * @throws IllegalStateException if the template is not on the class path
   */
  public static C4ReportRenderer fromClassPath() {
    return fromClassPath(DiagramLimits.DEFAULT);
  }

  /**
   * A renderer with the template from the class path that writes the diagram limits into each
   * report, which the template and the scorer judge its views by (#836).
   *
   * @throws IllegalStateException if the template is not on the class path
   */
  public static C4ReportRenderer fromClassPath(DiagramLimits limits) {
    return new C4ReportRenderer(ReportSpecTemplate.fromClassPath(SPEC, DATA_ID), limits);
  }

  /** The diagram limits the reports it renders were composed with (#836). */
  public DiagramLimits limits() {
    return limits;
  }

  /**
   * The document as the report spec's HTML file, with the diagram limits it was composed with as
   * its document's {@code readable} (#836).
   *
   * @throws IllegalArgumentException if two Lantern IDs would become the same report spec ID
   */
  public String render(Document document) {
    return template.render(MAPPER.writeValueAsString(data(document)));
  }

  /**
   * The report spec's data for a document as {@link #render} writes it: {@link #toJson}'s, with the
   * diagram limits as its document's {@code readable} (#836).
   *
   * @throws IllegalArgumentException if two Lantern IDs would become the same report spec ID
   */
  public ObjectNode data(Document document) {
    ObjectNode data = toJson(document).data();
    ObjectNode readable = data.withObject("document").putObject("readable");
    readable.put("target", limits.target());
    readable.put("tolerance", limits.tolerance());
    readable.put("linesPerElement", limits.linesPerElement());
    readable.put("scale", limits.scale());
    readable.put("crossings", limits.crossings());
    return data;
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

  /** The catalogues after a diagram: the element kinds each lists, its title and its columns. */
  private record Catalogue(String suffix, Set<String> kinds, String title, List<String> columns) {}

  /** The entity types drawn as containers whose build names may be technical (#933). */
  private static final Set<String> CONTAINER_TYPES = Set.of("service", "frontend");

  /**
   * The name a service's or frontend's box and page show: the one Describe wrote in words, else its
   * name title-cased (#946); any other entity's name as it is.
   */
  static String shownName(DocumentEntity entity, String kind) {
    if (!CONTAINER_TYPES.contains(entity.id().type())) {
      return entity.name();
    }
    return entity.readableName() != null ? entity.readableName() : readable(kind, entity.name());
  }

  /** A container's name in words ({@link io.lantern.compose.ReadableNames}); any other as it is. */
  static String readable(String kind, String name) {
    return "container".equals(kind) ? io.lantern.compose.ReadableNames.of(name) : name;
  }

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

    /** What an LLM role only words about what is there: no finding of its own (#749). */
    private static final Set<String> WORDINGS =
        Set.of(
            Types.SUMMARY,
            Types.RESPONSIBILITY,
            Types.INTENT,
            Types.FLOW_NARRATIVE,
            Types.FLOW_SIGNIFICANCE,
            Types.PURPOSE,
            Types.KEY_FACT,
            Types.DISPLAY_NAME,
            Types.FINDING_WORDING);

    /**
     * What an LLM role records about its own run, not about the system: what it did not ask, what
     * Investigate did not look at, and Investigate's verdicts on leads. None is a finding (#749).
     */
    private static final Set<String> BOOKKEEPING =
        Set.of(Types.NOT_ASKED, Types.NOT_INVESTIGATED, Types.LEAD_VERDICT);

    /** What opens a text the LLM found, plain or emphasized, which the mark replaces. */
    private static final java.util.regex.Pattern FOUND =
        java.util.regex.Pattern.compile(
            "^\\*?"
                + java.util.regex.Pattern.quote(io.lantern.derive.Elements.FOUND_BY_LLM)
                + "\\*?\\s*");

    /** What opens a text inferred from the code rather than written by people (ADR 0059). */
    private static final java.util.regex.Pattern INFERRED =
        java.util.regex.Pattern.compile(
            "^\\*?(?:Purpose inferred from the code:|Inferred, from its module's summary:|Inferred:)"
                + "\\*?\\s*");

    /** The text without its opening "Found by the LLM:", where the mark says it. */
    private static String unmarked(String text) {
      return FOUND.matcher(text).replaceFirst("");
    }

    Mapping(Document document) {
      this.document = document;
      document.entities().forEach(e -> entities.put(e.id(), e));
      document.facts().forEach(f -> facts.put(f.id(), f));
    }

    /**
     * Whether the facts show something only the LLM found: leaving out what an LLM role only worded
     * and what it records about its own run, there are facts, and every one comes from the LLM, so
     * at least one is a finding of the LLM's: a risk or connection Investigate found, or a link
     * Resolve picked (#749).
     */
    private boolean foundByLlm(Collection<String> ids) {
      List<Fact> evidence = new ArrayList<>();
      for (String id : ids) {
        Fact fact = facts.get(id);
        if (fact == null) {
          return false;
        }
        boolean llm = fact.origin().kind() == Origin.Kind.LLM;
        if (!(llm
            && (WORDINGS.contains(fact.predicate()) || BOOKKEEPING.contains(fact.predicate())))) {
          evidence.add(fact);
        }
      }
      return !evidence.isEmpty()
          && evidence.stream().allMatch(f -> f.origin().kind() == Origin.Kind.LLM);
    }

    Result run() {
      document.sections().forEach(section -> plan(section, null));
      nestUnderGroups();
      rehome();
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
        // A components page inside another, such as a feature area's in its unit's, is nested
        // under it in the menu (#931).
        if (enclosing != null && level.equals("components") && enclosing.level.equals(level)) {
          target.parent = enclosing;
        }
        pages.add(target);
        // A documentation page is about the whole document, so no entity's links point to it.
        if (section.entity() != null && !level.equals("documentation")) {
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
     * Nests each top components page about an element of a domain under the domain's page (#931),
     * so the menu reads domain, then its units, then their areas.
     */
    private void nestUnderGroups() {
      Map<EntityId, PagePlan> about = new HashMap<>();
      for (PagePlan page : pages) {
        if (page.section.entity() != null && !page.level.equals("documentation")) {
          about.putIfAbsent(page.section.entity(), page);
        }
      }
      for (PagePlan page : pages) {
        EntityId subject = page.section.entity();
        if (page.parent != null || subject == null || !page.level.equals("components")) {
          continue;
        }
        DocumentEntity entity = entities.get(subject);
        PagePlan group =
            entity == null || entity.group() == null ? null : about.get(entity.group());
        if (group != null && group.level.equals("containers")) {
          page.parent = group;
        } else if (entity != null && entity.group() == null) {
          // A unit in no domain sits under the page of the units in no domain, or of those no line
          // reaches, that draws it, not on its own at the end of the menu (#953).
          pages.stream()
              .filter(p -> p.level.equals("containers") && p.section.entity() == null)
              .filter(
                  p ->
                      p.section.title().equals(PageTitles.NO_DOMAIN)
                          || p.section.title().equals(PageTitles.ALONE))
              .filter(p -> draws(p, subject))
              .findFirst()
              .ifPresent(p -> page.parent = p);
        }
      }
    }

    /** Whether a diagram on the page draws the element. */
    private static boolean draws(PagePlan page, EntityId element) {
      return page.parts.stream()
          .map(Part::block)
          .filter(Diagram.class::isInstance)
          .map(Diagram.class::cast)
          .anyMatch(d -> d.nodes().stream().anyMatch(n -> n.id().equals(element)));
    }

    /**
     * Moves the blocks of a later section that is not a page itself, such as the note above the
     * main flows, from the introduction to a page (#681): after the summary of the first page among
     * its subsections, or else to the end of the first page. The introduction keeps the first
     * section's blocks, and quality reports stay for {@link #distributeQualityReports}.
     */
    private void rehome() {
      if (pages.isEmpty() || document.sections().isEmpty()) {
        return;
      }
      Section first = document.sections().getFirst();
      Map<PagePlan, List<Part>> moved = new LinkedHashMap<>();
      for (Iterator<Part> it = loose.iterator(); it.hasNext(); ) {
        Part part = it.next();
        if (part.owner == first || part.block instanceof QualityReport) {
          continue;
        }
        it.remove();
        PagePlan page =
            pages.stream().filter(p -> within(p.section, part.owner)).findFirst().orElse(null);
        if (page != null) {
          moved.computeIfAbsent(page, p -> new ArrayList<>()).add(part);
        } else {
          pages.getFirst().parts.add(part);
        }
      }
      // After the page's leading paragraphs, which stay its summary.
      moved.forEach(
          (page, parts) -> {
            int at = 0;
            while (at < page.parts.size()
                && page.parts.get(at).block instanceof Paragraph
                && page.parts.get(at).owner == page.section) {
              at++;
            }
            page.parts.addAll(at, parts);
          });
    }

    /** Whether the section is the ancestor or one of its subsections, at any depth. */
    private static boolean within(Section section, Section ancestor) {
      return section == ancestor || ancestor.sections().stream().anyMatch(s -> within(section, s));
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
      return elements.computeIfAbsent(
          id,
          e -> {
            DocumentEntity entity = entities.get(e);
            return new Element(e, entity == null ? null : entity.kind());
          });
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

    /**
     * On the page of a group drawn as a box elsewhere, the boxes inside it: its members, and each
     * data store, topic, queue or exchange that only the group uses, on every view of the report
     * (every line to it, anywhere, is from the group or one of its members). Empty on any other
     * page.
     */
    private List<EntityId> insideGroup(Diagram diagram, Section owner) {
      EntityId group = owner.entity();
      if (group == null || !group.type().equals(Types.GROUP) || !elements.containsKey(group)) {
        return List.of();
      }
      Set<EntityId> members = new LinkedHashSet<>();
      for (Diagram.Node node : diagram.nodes()) {
        DocumentEntity entity = entities.get(node.id());
        if (entity != null && group.equals(entity.group())) {
          members.add(node.id());
        }
      }
      if (members.isEmpty()) {
        return List.of();
      }
      List<EntityId> inside = new ArrayList<>(members);
      for (Diagram.Node node : diagram.nodes()) {
        if (!GROUP_DETAIL.contains(node.id().type())) {
          continue;
        }
        Set<EntityId> others = usersEverywhere().getOrDefault(node.id(), Set.of());
        if (!others.isEmpty() && others.stream().allMatch(o -> belongsTo(o, group))) {
          inside.add(node.id());
        }
      }
      return inside;
    }

    /**
     * Whether the element is the group, one of its members, or what holds the group: a feature
     * area's container, whose line to a store on a container view stands for its areas' lines.
     */
    private boolean belongsTo(EntityId element, EntityId group) {
      DocumentEntity entity = entities.get(element);
      DocumentEntity holder = entities.get(group);
      return element.equals(group)
          || entity != null && group.equals(entity.group())
          || holder != null
              && element.equals(holder.parent())
              && !Types.SYSTEM.equals(element.type());
    }

    private Map<EntityId, Set<EntityId>> usersEverywhere;

    /** For each element, what the lines of every diagram in the document join it to. */
    private Map<EntityId, Set<EntityId>> usersEverywhere() {
      if (usersEverywhere == null) {
        usersEverywhere = new HashMap<>();
        java.util.ArrayDeque<Section> todo = new java.util.ArrayDeque<>(document.sections());
        while (!todo.isEmpty()) {
          Section section = todo.pop();
          todo.addAll(section.sections());
          for (Block block : section.blocks()) {
            if (block instanceof Diagram d) {
              for (Diagram.Edge e : d.edges()) {
                usersEverywhere.computeIfAbsent(e.from(), k -> new HashSet<>()).add(e.to());
                usersEverywhere.computeIfAbsent(e.to(), k -> new HashSet<>()).add(e.from());
              }
            }
          }
        }
      }
      return usersEverywhere;
    }

    /** What may sit inside a group's frame beside its members: its own stores and channels. */
    private static final Set<String> GROUP_DETAIL =
        Set.of(Types.DATABASE, Types.TOPIC, Types.QUEUE, Types.EXCHANGE);

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
      // Under the title, as in the spec's sample: the first section's own text, the system's
      // purpose and key fact, not its lists or other sections' blocks (#681).
      Section first = document.sections().isEmpty() ? null : document.sections().getFirst();
      String intro =
          loose.stream()
              .filter(part -> part.owner == first)
              .filter(part -> part.block instanceof Paragraph || part.block instanceof Callout)
              .map(part -> looseMarkdown(part.block))
              .filter(s -> !s.isEmpty())
              .collect(Collectors.joining("\n\n"));
      // The opening is about the system; who it is for is not shown, and the commits are listed
      // apart, behind a short "Analyzed" line the template draws (#934).
      java.util.regex.Matcher inferred = INFERRED.matcher(intro);
      if (inferred.find()) {
        node.put("subtitleInferred", true);
        intro = intro.substring(inferred.end());
      }
      if (!intro.isEmpty()) {
        node.put("subtitle", intro);
      }
      ObjectNode commits = node.putObject("commits");
      document
          .source()
          .commits()
          .forEach((repository, commit) -> commits.put(repository, commit.substring(0, 12)));
      node.put("version", Long.toString(document.source().snapshot()));
      node.put("updated", document.source().date().toString());
      return node;
    }

    private String looseMarkdown(Block block) {
      return switch (block) {
        case Paragraph p -> markdown(p.content());
        case Callout c -> calloutMarkdown(c);
        default -> "";
      };
    }

    private ObjectNode elementNode(Element element) {
      DocumentEntity entity = entities.get(element.id);
      ObjectNode node = MAPPER.createObjectNode();
      String kind = element.kind();
      node.put("kind", kind);
      // A container named as its build names it reads as words, with that name small beneath
      // (#933).
      String readable = shownName(entity, kind);
      node.put("name", readable);
      if (!readable.equals(entity.name())) {
        node.put("technicalName", entity.name());
      }
      EntityId parent = shownParent(element);
      if (parent != null) {
        node.put("parent", elementIds.find(parent.toString()));
      }
      // The composer names the type, if a reader is to see it: a queue element is a topic, queue
      // or exchange, and its tag says which.
      if (entity.tag() != null) {
        node.put("tag", entity.tag());
      }
      if (element.technology != null) {
        node.put("technology", element.technology);
      }
      Set<String> cited = new HashSet<>(entity.facts());
      cited.addAll(element.facts);
      // A group the LLM formed is how the report is arranged, not something it found (#934), and
      // the system in focus is the one the user named (#960).
      boolean llm =
          !element.id.type().equals(Types.GROUP)
              && !element.id.type().equals(Types.SYSTEM)
              && foundByLlm(cited);
      String description = plain(entity.description());
      if (llm) {
        description = unmarked(description);
      }
      // An inferred text is flagged, which the template draws as a small icon (#934, ADR 0059).
      java.util.regex.Matcher inferred = INFERRED.matcher(description);
      if (inferred.find()) {
        node.put("inferred", true);
        description = description.substring(inferred.end());
      }
      if (!description.isBlank()) {
        node.put("description", description);
      }
      if (llm) {
        node.put("llm", true);
      }
      return node;
    }

    /** A page's title: a container's page named as its box is (#933). */
    private String pageTitle(PagePlan page) {
      String title = page.section.title();
      EntityId about = page.section.entity();
      if (about != null && entities.containsKey(about)) {
        DocumentEntity entity = entities.get(about);
        if (title.equals(entity.name()) && CONTAINER_TYPES.contains(about.type())) {
          return shownName(entity, "container");
        }
      }
      return title;
    }

    private ObjectNode pageNode(PagePlan page) {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("id", page.id);
      node.put("level", page.level);
      node.put("title", pageTitle(page));
      if (page.parent != null) {
        node.put("parent", page.parent.id);
      }
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
      return title == null ? body : "**" + literal(oneLine(title)) + "**\n" + body;
    }

    private ObjectNode table(String id, Table table) {
      ObjectNode node = section("table", id, table.title());
      ArrayNode columns = node.putArray("columns");
      table.columns().forEach(c -> columns.add(c.label()));
      ArrayNode rows = node.putArray("rows");
      List<Integer> llmRows = rows(table.rows(), rows);
      if (!llmRows.isEmpty()) {
        ArrayNode marked = node.putArray("llmRows");
        llmRows.forEach(marked::add);
      }
      // A parent row's folded rows are its subRows, listed by its index (#747).
      ArrayNode subRows = null;
      for (int i = 0; i < table.rows().size(); i++) {
        Table.Row row = table.rows().get(i);
        if (row.children().isEmpty()) {
          continue;
        }
        if (subRows == null) {
          subRows = node.putArray("subRows");
        }
        ObjectNode group = subRows.addObject();
        group.put("row", i);
        List<Integer> llm = rows(row.children(), group.putArray("rows"));
        if (!llm.isEmpty()) {
          ArrayNode marked = group.putArray("llmRows");
          llm.forEach(marked::add);
        }
      }
      return node;
    }

    /** Writes the rows' cells into {@code rows}; returns the indices of those the LLM found. */
    private List<Integer> rows(List<Table.Row> from, ArrayNode rows) {
      List<Integer> llmRows = new ArrayList<>();
      for (Table.Row row : from) {
        boolean llm = foundByLlm(row.facts());
        if (llm) {
          llmRows.add(rows.size());
        }
        ArrayNode cells = rows.addArray();
        row.cells()
            .forEach(
                cell -> {
                  String text = oneLine(markdown(cell));
                  cells.add(llm ? unmarked(text) : text);
                });
      }
      return llmRows;
    }

    /** Whether every row of a table is about a decision record, so it is a decisions section. */
    private boolean isDecisions(Table table) {
      return !table.rows().isEmpty()
          && table.rows().stream()
              .allMatch(
                  row ->
                      row.entity() != null
                          && entities.containsKey(row.entity())
                          && entities.get(row.entity()).kind() == DocumentEntity.Kind.DECISION)
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
        // The ID and status are shown as plain text, so they are written unescaped.
        Map<String, String> plainCells = new HashMap<>();
        for (int i = 0; i < table.columns().size(); i++) {
          cells.put(table.columns().get(i).label(), markdown(row.cells().get(i)));
          plainCells.put(table.columns().get(i).label(), plain(row.cells().get(i)));
        }
        ObjectNode item = items.addObject();
        putNonEmpty(item, "id", oneLine(plainCells.getOrDefault("ID", "")));
        String title = oneLine(cells.getOrDefault("Decision", ""));
        item.put("title", title.isEmpty() ? literal(entities.get(row.entity()).name()) : title);
        String outcome = cells.getOrDefault("Outcome", "");
        String rationale = cells.getOrDefault("Rationale", "");
        String body =
            rationale.isEmpty()
                ? outcome
                : (outcome.isEmpty() ? "" : outcome + "\n\n") + "**Rationale:** " + rationale;
        putNonEmpty(item, "body", body);
        putNonEmpty(item, "status", oneLine(plainCells.getOrDefault("Status", "")));
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
      items.add("  ".repeat(depth) + literal(node.label()));
      node.children().forEach(child -> treeItems(child, depth + 1, items));
    }

    private ObjectNode qualityReport(String id, QualityReport report) {
      String title = report.title() == null ? "Quality report" : report.title();
      if (report.failures().isEmpty()) {
        // Marked so the page adds the gaps it measures after layout (#366).
        return section("callout", id, title)
            .put("body", "Every check Lantern makes on this document passed.")
            .put("tone", "success")
            .put("quality", true);
      }
      List<String> items = new ArrayList<>();
      for (QualityReport.Failure failure : report.failures()) {
        StringBuilder item =
            new StringBuilder("**").append(literal(oneLine(failure.check()))).append("**");
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
      return list(id, title, items).put("quality", true);
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
        return list(id, title, diagram.nodes().stream().map(n -> literal(n.label())).toList());
      }
      ObjectNode node = section("table", id, title);
      ArrayNode columns = node.putArray("columns");
      columns.add("From").add("To").add("Relationship");
      ArrayNode rows = node.putArray("rows");
      Map<EntityId, String> labels = new HashMap<>();
      diagram.nodes().forEach(n -> labels.put(n.id(), n.label()));
      for (Diagram.Edge edge : diagram.edges()) {
        rows.addArray()
            .add(literal(labels.getOrDefault(edge.from(), edge.from().toString())))
            .add(literal(labels.getOrDefault(edge.to(), edge.to().toString())))
            .add(edge.label() == null ? "" : literal(edge.label()));
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
      // A domain's or feature area's page is framed by the group, its own boxes inside (#957).
      List<EntityId> inside = insideGroup(diagram, owner);
      if (!inside.isEmpty()) {
        boundary = owner.entity();
        ArrayNode contains = node.putArray("boundaryContains");
        inside.forEach(e -> contains.add(elementIds.find(e.toString())));
      }
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
          if (label != null && !unmarked(label).isBlank()) {
            relationship.put("label", unmarked(label));
          } else if (label != null) {
            relationship.remove("label");
          }
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
        case SequenceDiagram.Message m -> items.add(literal(messageText(m)));
        case SequenceDiagram.Note n -> items.add(literal(plain(n.content())));
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
        return literal(run.text());
      }
      String mark =
          switch (run.style()) {
            case STRONG -> "**";
            case EMPHASIS -> "*";
            case CODE -> "`";
          };
      String text = literal(run.text());
      if (text.isBlank() || text.contains(mark.substring(0, 1)) || !text.strip().equals(text)) {
        return text;
      }
      return mark + text + mark;
    }

    /**
     * A text as the spec's Markdown-lite shows it as written: its link and emphasis syntax escaped
     * with a backslash, so a link or bold an LLM wrote (or a path such as {@code /files/**})
     * renders as plain text (knowledge-build epic, section 10, Security; #752). Links the renderer
     * makes itself, from cross-references to facts, are written after it and stay links. Backticks
     * are left: a code span shows its text as written, links and emphasis included.
     */
    static String literal(String text) {
      StringBuilder out = new StringBuilder(text.length());
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '\\' || c == '*' || c == '[' || c == ']') {
          out.append('\\');
        }
        out.append(c);
      }
      return out.toString();
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
      String label = literal(text.replace('[', '(').replace(']', ')'));
      return href == null ? literal(text) : "[" + label + "](" + href + ")";
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

    /** The page this one is nested under in the menu, or null (#931). */
    PagePlan parent;

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

    /** What the composer says its type makes it, whatever it is drawn as, or null. */
    private final DocumentEntity.Kind fixed;

    Element(EntityId id, DocumentEntity.Kind fixed) {
      this.id = id;
      this.fixed = fixed;
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
      if (fixed != null && fixed != DocumentEntity.Kind.DECISION) {
        return fixed.jsonName();
      }
      return rank == Integer.MAX_VALUE ? "container" : KINDS.get(rank);
    }

    /** Its parent where it is drawn as its kind. */
    EntityId parent() {
      return parentByRank.get(rank);
    }
  }
}
