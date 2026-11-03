package io.lantern.compose;

import io.lantern.core.document.Citation;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.ElementNames;
import io.lantern.derive.Elements;
import io.lantern.derive.Knowledge;
import io.lantern.derive.attributes.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects what a document refers to while it is composed: the entities, with their names, parents
 * in the navigation tree, facts and summaries, and the facts. Also hands out unique element IDs.
 */
final class Assembler {

  private static final Pattern MARKER = Pattern.compile("\\[(fact|summary):([^\\]\\s]+)\\]");

  /** What opens a summary whose purpose is inferred from the code (ADR 0059). */
  static final String INFERRED = "Purpose inferred from the code:";

  /**
   * What opens a responsibility sentence that cites no README claim (ADR 0065): short, since it is
   * the element's description on diagrams.
   */
  static final String INFERRED_SENTENCE = "Inferred:";

  /** What opens a sentence the support judge found unsupported by its sources (ADR 0101). */
  static final String UNSUPPORTED = "Not supported by its sources:";

  /**
   * What opens a deployable unit's description taken from its module's summary because the unit has
   * no responsibility sentence (ADR 0066), when the summary's purpose is evidenced.
   */
  static final String FROM_MODULE = "From its module's summary:";

  /** As {@link #FROM_MODULE}, when the summary's purpose is inferred from the code. */
  static final String FROM_MODULE_INFERRED = "Inferred, from its module's summary:";

  private static final Pattern NOT_ID_CHAR = Pattern.compile("[^A-Za-z0-9._:-]");

  private final Knowledge knowledge;
  private final ArchitectureModel model;
  private final Set<EntityId> described;
  private final String repo;
  private final String systemName;
  private final ElementNames names;
  private final Set<EntityId> adding = new HashSet<>();
  private final Deque<EntityId> undescribed = new ArrayDeque<>();
  private final Map<EntityId, DocumentEntity> entities = new TreeMap<>(Knowledge.BY_ID);
  private final Set<String> factIds = new TreeSet<>();
  private final Set<String> elementIds = new HashSet<>();

  /**
   * @param model the snapshot's model: its facts, and the responsibility sentences, summaries,
   *     intent phrases and flow narratives the LLM roles stored about them (ADR 0050)
   * @param described the entities whose summaries become their descriptions: those in scope
   * @param repo the repository the document is about, preferred as the parent of a module, service
   *     or library observed in several repositories, or {@code null} for a document of the system
   * @param systemName the name of the software system the document's containers belong to
   */
  Assembler(ArchitectureModel model, Set<EntityId> described, String repo, String systemName) {
    this.model = model;
    this.knowledge = model.knowledge();
    this.described = described;
    this.repo = repo;
    this.systemName = systemName;
    this.names = new ElementNames(knowledge, systemName);
  }

  /** The text of a rich-text element and the facts it cites. */
  record Text(List<Inline> content, List<String> facts) {}

  /**
   * Adds the entity to the document, with its parent; if it is in scope and has a summary or a
   * responsibility sentence, or is a data store or destination with a responsibility sentence, its
   * description is added by {@link #entities()}. Its facts are its attributes, the facts placing it
   * in its parent and, for a service or library, the facts saying which modules it builds. Returns
   * the entity.
   */
  EntityId entity(EntityId id) {
    if (entities.containsKey(id)) {
      return id;
    }
    if (!adding.add(id)) {
      throw new IllegalArgumentException("CONTAINS facts form a cycle at " + id);
    }
    EntityId parent = parent(id);
    List<Fact> facts = new ArrayList<>(knowledge.attributes(id));
    if (id.type().equals(BuiltInTypes.SERVICE) || id.type().equals(BuiltInTypes.LIBRARY)) {
      for (EntityId module : knowledge.parts(id, BuiltInTypes.MODULE)) {
        facts.addAll(knowledge.containsFacts(id, module));
      }
    }
    if (parent != null) {
      entity(parent);
      facts.addAll(knowledge.containsFacts(parent, id));
    }
    entities.put(
        id, new DocumentEntity(id, name(id), parent, List.of(), facts(facts), kind(id), tag(id)));
    adding.remove(id);
    if (described.contains(id)
            && (model.summary(summarized(id)).isPresent() || responsibility(id) != null)
        // A data store or destination has no summary; it is described wherever a diagram draws
        // it, by its responsibility sentence (ADR 0056).
        || (destination(id) && responsibility(id) != null)
        // A person is described by what the code shows of them (ADR 0074).
        || (id.type().equals(Types.PERSON) && personBasis(id) != null)
        // A frontend by its responsibility sentence, or else by what its manifest and Dockerfile
        // say (ADR 0083).
        || id.type().equals(Types.FRONTEND)
        // An element only Investigate found, by the connections it found (ADR 0110).
        || (responsibility(id) == null && foundOnlyByTheInvestigator(id))) {
      undescribed.add(id);
    }
    return id;
  }

  /** Adds the entity to the document and returns its parent in the navigation tree. */
  EntityId parentOf(EntityId id) {
    return entities.get(entity(id)).parent();
  }

  /**
   * The entity whose summary describes the entity: for a deployable unit the module of its main
   * code, the main library's if it bundles one, else its own; for anything else itself.
   */
  EntityId summarized(EntityId id) {
    if (!id.type().equals(BuiltInTypes.SERVICE)) {
      return id;
    }
    for (Fact fact : knowledge.relations(id, Types.BUNDLES)) {
      if (RepoSections.attribute(fact, "main", "false").equals("true")) {
        SortedSet<EntityId> modules = knowledge.parts(Knowledge.object(fact), BuiltInTypes.MODULE);
        if (!modules.isEmpty()) {
          return modules.first();
        }
      }
    }
    SortedSet<EntityId> modules = knowledge.parts(id, BuiltInTypes.MODULE);
    return modules.isEmpty() ? id : modules.first();
  }

  /** The name a reader sees for an entity ({@link ElementNames#name}). */
  String name(EntityId id) {
    return names.name(id);
  }

  /** The names of the document's entities. */
  ElementNames names() {
    return names;
  }

  /** The entity the given one belongs under in the navigation tree, or {@code null}. */
  private EntityId parent(EntityId id) {
    return switch (id.type()) {
      case ArchitectureModel.REPO_ENTITY_TYPE, Types.ARTIFACT -> null;
      case BuiltInTypes.MODULE -> {
        SortedSet<EntityId> aggregators = knowledge.wholes(id, BuiltInTypes.MODULE);
        if (!aggregators.isEmpty()) {
          yield aggregators.first();
        }
        yield repoOf(knowledge.repos(id));
      }
      case BuiltInTypes.SERVICE, BuiltInTypes.LIBRARY -> {
        SortedSet<String> repos = new TreeSet<>();
        for (EntityId module : knowledge.parts(id, BuiltInTypes.MODULE)) {
          repos.addAll(knowledge.repos(module));
        }
        yield repoOf(repos);
      }
      case BuiltInTypes.TYPE -> {
        SortedSet<EntityId> wholes = knowledge.wholes(id, BuiltInTypes.PACKAGE);
        wholes.addAll(knowledge.wholes(id, BuiltInTypes.TYPE));
        if (wholes.isEmpty()) {
          wholes = knowledge.wholes(id, BuiltInTypes.MODULE);
        }
        yield wholes.isEmpty() ? null : wholes.first();
      }
      case Types.FRONTEND ->
          Elements.frontendApp(knowledge, id)
              .map(f -> repoOf(new TreeSet<>(Set.of(f.evidence().repo()))))
              .orElse(null);
      default -> {
        SortedSet<EntityId> wholes = knowledge.wholes(id);
        yield wholes.isEmpty() ? null : wholes.first();
      }
    };
  }

  /** The document's repository if it is one of these, else the first, or {@code null}. */
  private EntityId repoOf(SortedSet<String> repos) {
    if (repo != null && repos.contains(repo)) {
      return repo(repo);
    }
    return repos.isEmpty() ? null : repo(repos.first());
  }

  static EntityId repo(String name) {
    return new EntityId(ArchitectureModel.REPO_ENTITY_TYPE, name);
  }

  /** Adds the facts to the document and returns their IDs, sorted and without duplicates. */
  List<String> facts(Collection<Fact> facts) {
    Set<String> ids = new TreeSet<>();
    for (Fact fact : facts) {
      ids.add(fact.id());
    }
    factIds.addAll(ids);
    return List.copyOf(ids);
  }

  /**
   * The summary as rich text: {@code [fact:...]} markers become citations (adjacent ones merged)
   * and {@code [summary:<entity>]} markers cross-references to the entity that are marked as
   * citations; the entity is added to the document. A summary whose purpose is inferred from the
   * code rather than taken from what people wrote opens with {@value #INFERRED}, emphasized.
   *
   * @throws IllegalArgumentException if the summary cites a fact that is not in the snapshot
   */
  Text summary(ArchitectureModel.Summary summary) {
    Text text = rich(summary.unit(), summary.marked());
    return summary.evidenced() ? text : inferred(text);
  }

  /**
   * The entity's responsibility sentence (ADR 0050), with the markers of facts that are not in the
   * snapshot's facts left out, or {@code null} if it has none that still cites something.
   */
  private String responsibility(EntityId id) {
    return known(sentence(id).map(ArchitectureModel.Text::marked).orElse(null));
  }

  /** The stored sentence about the element, the system's being its purpose. */
  private java.util.Optional<ArchitectureModel.Text> sentence(EntityId id) {
    return id.equals(RepoSections.SYSTEM) ? model.purpose() : model.responsibility(id);
  }

  /**
   * The LLM's title, walkthrough and conditions of the flow (ADR 0058, ADR 0108), with citation
   * markers, or {@code null} if none holds for its steps as they are.
   *
   * @param content the content hash of the flow's steps
   */
  FlowWording flow(EntityId flow, String content) {
    return model
        .narrative(flow, content)
        .map(
            n ->
                new FlowWording(
                    marked(n.walkthrough()),
                    marked(n.preconditions()),
                    marked(n.postconditions()),
                    n.title()))
        .orElse(null);
  }

  /**
   * A flow's title, walkthrough and conditions as the LLM worded them, each with citation markers
   * {@code [fact:<fact ID>]}, or {@code null} where it left one out.
   */
  record FlowWording(
      String walkthrough, String preconditions, String postconditions, String title) {}

  private static String marked(ArchitectureModel.Text text) {
    return text == null ? null : text.marked();
  }

  /**
   * The flows the model judged significant for a scope, in its order, by flow ID; {@code null} when
   * it did not judge the scope (knowledge-build epic, section 11.5).
   */
  List<String> chosenFlows(String scope) {
    return model.significantFlows(scope).orElse(null);
  }

  /**
   * The system's purpose (ADR 0060) as rich text citing the facts that are in the snapshot, or
   * {@code null} if the LLM worded none or none of its citations is in the snapshot. A purpose that
   * cites no README claim is inferred from the code, through the units' sentences, and opens with
   * {@value #INFERRED}, emphasized, as an inferred summary does (ADR 0059).
   */
  Text purpose() {
    String kept = responsibility(RepoSections.SYSTEM);
    return kept == null ? null : purpose(rich(RepoSections.SYSTEM, kept));
  }

  /**
   * The system's key fact (knowledge-build epic, section 11.6; ADR 0109) as rich text citing the
   * facts that are in the snapshot, or {@code null} if the LLM wrote none or none of its citations
   * is in the snapshot.
   */
  Text keyFact() {
    String kept = known(model.keyFact().map(ArchitectureModel.Text::marked).orElse(null));
    return kept == null ? null : rich(RepoSections.SYSTEM, kept);
  }

  private Text purpose(Text text) {
    return unsupported(RepoSections.SYSTEM, evidenced(text, false) ? text : inferred(text));
  }

  /**
   * A responsibility sentence as rich text (ADR 0050, ADR 0056). One that cites no README claim is
   * inferred from the code and opens with {@value #INFERRED_SENTENCE}, emphasized (ADR 0065).
   */
  private Text responsibility(EntityId about, String sentence) {
    Text text = rich(about, sentence);
    return unsupported(about, evidenced(text, true) ? text : opened(INFERRED_SENTENCE, text));
  }

  /**
   * The text opened with {@value #UNSUPPORTED}, emphasized, when the support judge found the
   * element's sentence unsupported by its cited facts (ADR 0101); otherwise as it is.
   */
  private Text unsupported(EntityId about, Text text) {
    return sentence(about).filter(ArchitectureModel.Text::unsupported).isPresent()
        ? opened(UNSUPPORTED, text)
        : text;
  }

  /**
   * Whether the text cites what people wrote (ADR 0059, ADR 0060): a README claim or, when {@code
   * code}, the fact above code with Javadoc that a unit's prompt showed (ADR 0069). The prompt
   * about the system's purpose shows the facts its units' sentences cite without their code.
   */
  private boolean evidenced(Text text, boolean code) {
    return text.facts().stream()
        .map(knowledge::fact)
        .anyMatch(
            f ->
                f != null
                    && (f.predicate().equals(Types.CLAIM)
                        || code && model.documented().contains(f.id())));
  }

  /** The text opened with {@value #INFERRED}, emphasized. */
  private static Text inferred(Text text) {
    return opened(INFERRED, text);
  }

  /** The text opened with the label, emphasized. */
  private static Text opened(String label, Text text) {
    List<Inline> content = new ArrayList<>();
    content.add(new TextRun(label, TextRun.Style.EMPHASIS));
    content.add(TextRun.of(" "));
    content.addAll(text.content());
    return new Text(List.copyOf(content), text.facts());
  }

  /**
   * A text the LLM worded, with citation markers, as rich text citing the facts that are in the
   * snapshot, or {@code null} if it is {@code null} or none of its citations is in the snapshot.
   */
  Text worded(EntityId about, String text) {
    String kept = known(text);
    return kept == null ? null : rich(about, kept);
  }

  /**
   * The text with the markers of facts that are not in the snapshot's facts left out, or {@code
   * null} if it is {@code null} or has none left that cites something.
   */
  private String known(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = MARKER.matcher(text);
    StringBuilder kept = new StringBuilder();
    boolean cites = false;
    int last = 0;
    while (matcher.find()) {
      boolean known = !matcher.group(1).equals("fact") || knowledge.fact(matcher.group(2)) != null;
      cites |= known;
      kept.append(text, last, matcher.start());
      last = matcher.end();
      if (known) {
        kept.append(matcher.group());
      } else {
        dropped(kept, text, last);
      }
    }
    kept.append(text, last, text.length());
    return cites ? kept.toString().strip() : null;
  }

  /**
   * Called when a marker was left out after {@code kept}: removes the space before it when what
   * follows is the end, a space or punctuation, or when nothing precedes it, so that no leading,
   * trailing or doubled space remains.
   */
  private static void dropped(StringBuilder kept, String text, int next) {
    boolean atEnd = next >= text.length();
    boolean spaceAfter = !atEnd && Character.isWhitespace(text.charAt(next));
    boolean punctuationAfter =
        !atEnd
            && !Character.isLetterOrDigit(text.charAt(next))
            && "[({\"`".indexOf(text.charAt(next)) < 0;
    if (atEnd || spaceAfter || punctuationAfter || kept.toString().isBlank()) {
      int end = kept.length();
      while (end > 0 && Character.isWhitespace(kept.charAt(end - 1))) {
        end--;
      }
      kept.setLength(end);
    }
  }

  /**
   * The intent phrase of a link (ADR 0050) and the facts it cites that are in the snapshot, or
   * {@code null} if the link has none. A phrase none of whose cited facts are in the snapshot is
   * still used: its line or message cites the link fact it words.
   */
  Phrased phrase(Fact link) {
    ArchitectureModel.Text phrase = model.intent(link).orElse(null);
    if (phrase == null) {
      return null;
    }
    List<Fact> facts = new ArrayList<>();
    for (String id : phrase.cites()) {
      Fact fact = knowledge.fact(id);
      if (fact != null) {
        facts.add(fact);
      }
    }
    return new Phrased(phrase.text(), facts);
  }

  /** An intent phrase and the facts it cites. */
  record Phrased(String text, List<Fact> facts) {}

  /** Text with citation markers as rich text; see {@link #summary}. */
  private Text rich(EntityId about, String text) {
    RichText out = new RichText();
    Matcher matcher = MARKER.matcher(text);
    int last = 0;
    while (matcher.find()) {
      String between = text.substring(last, matcher.start());
      last = matcher.end();
      boolean fact = matcher.group(1).equals("fact");
      if (!(fact && out.citing() && between.isBlank())) {
        out.flushCitation();
        out.text(between);
      }
      if (fact) {
        Fact cited = knowledge.fact(matcher.group(2));
        if (cited == null) {
          throw new IllegalArgumentException(
              "The summary of "
                  + about
                  + " cites fact "
                  + matcher.group(2)
                  + ", which is not in the snapshot's facts");
        }
        out.cite(cited.id());
      } else {
        EntityId target = entity(EntityId.parse(matcher.group(2)));
        out.reference(new CrossReference(name(target), CrossReference.Target.entity(target), true));
      }
    }
    out.flushCitation();
    out.text(text.substring(last));
    out.flushText();
    return new Text(out.content, facts(out.cited.stream().map(knowledge::fact).toList()));
  }

  /** Builds rich text from text, citations and cross-references. */
  private static final class RichText {
    private final List<Inline> content = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final Set<String> citation = new TreeSet<>();
    private final Set<String> cited = new TreeSet<>();

    boolean citing() {
      return !citation.isEmpty();
    }

    /** Adds text; text after markers that open the summary starts without a space. */
    void text(String value) {
      boolean onlyMarkers =
          text.isEmpty()
              && !content.isEmpty()
              && content.stream().noneMatch(inline -> inline instanceof TextRun);
      text.append(onlyMarkers ? value.stripLeading() : value);
    }

    /** Cites a fact for the text before it, with no space between them. */
    void cite(String factId) {
      if (!citing()) {
        text.setLength(text.toString().stripTrailing().length());
        flushText();
      }
      citation.add(factId);
      cited.add(factId);
    }

    /**
     * Cites a summary with a cross-reference marked as a citation, which follows the text before it
     * with no space between them, as a citation of facts does.
     */
    void reference(CrossReference reference) {
      flushCitation();
      text.setLength(text.toString().stripTrailing().length());
      flushText();
      content.add(reference);
    }

    void flushCitation() {
      if (citing()) {
        content.add(new Citation(List.copyOf(citation)));
        citation.clear();
      }
    }

    void flushText() {
      if (!text.isEmpty()) {
        content.add(TextRun.of(text.toString()));
        text.setLength(0);
      }
    }
  }

  /**
   * An element ID made from the parts: characters the document model does not allow in IDs become
   * {@code -}, and a suffix keeps the ID unique within the document.
   */
  String id(String... parts) {
    return unique(elementIds, String.join(".", parts));
  }

  /** An edge ID unique within one diagram. */
  static String edgeId(Set<String> used, EntityId from, String relationship, EntityId to) {
    return unique(used, from + "--" + relationship + "--" + to);
  }

  private static String unique(Set<String> used, String text) {
    String base = NOT_ID_CHAR.matcher(text).replaceAll("-");
    String id = base;
    for (int n = 2; !used.add(id); n++) {
      id = base + "-" + n;
    }
    return id;
  }

  /**
   * The document's entities, with descriptions. Descriptions are built last, since a summary's
   * cross-references may add entities, whose own descriptions are then built in turn.
   */
  List<DocumentEntity> entities() {
    while (!undescribed.isEmpty()) {
      DocumentEntity entity = entities.get(undescribed.removeFirst());
      String responsibility = responsibility(entity.id());
      List<Inline> description =
          entity.id().type().equals(Types.PERSON) && personBasis(entity.id()) != null
              ? person(entity.id()).content()
              : entity.id().type().equals(Types.FRONTEND) && responsibility == null
                  ? frontend(entity.id()).content()
                  : responsibility == null && foundOnlyByTheInvestigator(entity.id())
                      ? investigated(entity.id()).content()
                      : entity.id().equals(RepoSections.SYSTEM) && responsibility != null
                          ? purpose().content()
                          : responsibility != null
                              ? responsibility(entity.id(), responsibility).content()
                              : summarizedDescription(entity.id()).content();
      entities.put(
          entity.id(),
          new DocumentEntity(
              entity.id(),
              entity.name(),
              entity.parent(),
              description,
              entity.facts(),
              entity.kind(),
              entity.tag()));
    }
    return List.copyOf(entities.values());
  }

  /** The vocabulary the model was recorded with. */
  Vocabulary vocabulary() {
    return model.vocabulary();
  }

  /**
   * Whether the entity is one the container diagrams draw besides units ({@link
   * Elements#isDestination}).
   */
  boolean destination(EntityId id) {
    return Elements.isDestination(model.vocabulary(), id);
  }

  /**
   * What the entity's type makes it, whatever diagram shows it (ADR 0114): a data store or a queue
   * by the shape its type is drawn with, a person by descending from {@code actor}, a system
   * outside the one documented by descending from {@code system}, and a decision record; else
   * {@code null}, for what the diagram's level makes it.
   */
  DocumentEntity.Kind kind(EntityId id) {
    Vocabulary vocabulary = model.vocabulary();
    String type = id.type();
    if (type.equals(Types.DECISION)) {
      return DocumentEntity.Kind.DECISION;
    }
    if (vocabulary.find(type).isEmpty()) {
      return null;
    }
    String shape = vocabulary.shape(type).orElse("");
    if (shape.equals(CYLINDER)) {
      return DocumentEntity.Kind.DATABASE;
    }
    if (shape.equals(PIPE)) {
      return DocumentEntity.Kind.QUEUE;
    }
    if (vocabulary.isA(type, BuiltInTypes.ACTOR)) {
      return DocumentEntity.Kind.PERSON;
    }
    if (!type.equals(BuiltInTypes.SYSTEM) && vocabulary.isA(type, BuiltInTypes.SYSTEM)) {
      return DocumentEntity.Kind.EXTERNAL;
    }
    return null;
  }

  /**
   * The name of the entity's type a reader sees beside its name: none for a component's main Java
   * type, a system outside the one documented, or a type named as what it is drawn as; a topic,
   * queue or exchange says which it is.
   */
  static String tag(EntityId id) {
    String type = id.type();
    if (UNTAGGED.contains(type)) {
      return null;
    }
    if (type.equals(BuiltInTypes.REPO)) {
      return "Repository";
    }
    if (type.equals(Types.FRONTEND)) {
      return "Single-Page App";
    }
    String words = type.replace('-', ' ');
    return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
  }

  /** The types {@link #tag} names no type for. */
  private static final Set<String> UNTAGGED =
      Set.of(
          BuiltInTypes.TYPE,
          Types.EXTERNAL_SYSTEM,
          BuiltInTypes.SYSTEM,
          BuiltInTypes.CONTAINER,
          BuiltInTypes.COMPONENT,
          Types.DATABASE,
          Types.PERSON);

  /** The shape the vocabulary draws data stores with. */
  private static final String CYLINDER = "cylinder";

  /** The shape the vocabulary draws topics, queues and exchanges with. */
  private static final String PIPE = "pipe";

  /** The connections Investigate found to an entity (ADR 0110). */
  private List<Fact> investigatedTo(EntityId id) {
    return knowledge.withPredicate(Types.INVESTIGATED_CONNECTION).stream()
        .filter(f -> Knowledge.isEntity(f) && Knowledge.object(f).equals(id))
        .toList();
  }

  /** Whether only Investigate's connections know the entity: no fact is about it. */
  private boolean foundOnlyByTheInvestigator(EntityId id) {
    return destination(id) && knowledge.about(id).isEmpty() && !investigatedTo(id).isEmpty();
  }

  /**
   * The description of an element only Investigate found: what its units use it for, as the
   * connections it found say, opened with {@value InvestigatedFindings#FOUND_BY_LLM}, emphasized,
   * and citing those connections (ADR 0110).
   */
  private Text investigated(EntityId id) {
    List<Fact> found = investigatedTo(id);
    StringBuilder text = new StringBuilder();
    for (Fact fact : found) {
      text.append(text.isEmpty() ? "" : " ")
          .append(name(fact.subject()))
          .append(": ")
          .append(fact.attributes().path("description").asString(""))
          .append('.');
    }
    List<String> ids = facts(found);
    return opened(
        InvestigatedFindings.FOUND_BY_LLM,
        new Text(List.of(TextRun.of(text.toString()), new Citation(ids)), ids));
  }

  /**
   * The description of a frontend without a responsibility sentence: what its manifest and
   * Dockerfile say, citing its {@code frontend-app} fact, and what it calls.
   */
  private Text frontend(EntityId id) {
    Fact app = Elements.frontendApp(knowledge, id).orElse(null);
    if (app == null) {
      return new Text(List.of(), List.of());
    }
    StringBuilder text = new StringBuilder("A single-page app built with ");
    text.append(RepoSections.attribute(app, "framework", "a framework"));
    String version = RepoSections.attribute(app, "version", null);
    if (version != null) {
      text.append(' ').append(version);
    }
    String server = RepoSections.attribute(app, "server", null);
    if (server != null) {
      text.append(", served by ").append(server);
    }
    List<Fact> cited = new ArrayList<>(List.of(app));
    SortedSet<String> called = new TreeSet<>();
    for (Fact call : knowledge.relations(id, Types.CALLS_HTTP)) {
      called.add(name(Knowledge.object(call)));
      if (cited.size() < 6) {
        cited.add(call);
      }
    }
    if (!called.isEmpty()) {
      text.append("; it calls ").append(String.join(", ", called)).append(" from the browser");
    }
    text.append('.');
    List<String> ids = facts(cited);
    return new Text(List.of(TextRun.of(text.toString()), new Citation(ids)), ids);
  }

  private static final String ROLE = "role";

  /** The fact saying what a person rests on, or {@code null}. */
  private Fact personBasis(EntityId id) {
    return knowledge.attributes(id).stream()
        .filter(f -> f.predicate().equals(Types.PERSON_BASIS))
        .findFirst()
        .orElse(null);
  }

  /**
   * The description of a person: the sentence the linker wrote from the code's own words, opened
   * with {@value #INFERRED_SENTENCE} since a person is always inferred (ADR 0074), citing the facts
   * it rests on.
   */
  private Text person(EntityId id) {
    Fact basis = personBasis(id);
    List<Fact> cited = new ArrayList<>(List.of(basis));
    for (String factId : Elements.strings(basis, "cites")) {
      Fact fact = knowledge.fact(factId);
      if (fact != null) {
        cited.add(fact);
      }
    }
    List<String> ids = facts(cited);
    List<Inline> content =
        new ArrayList<>(asCode(basis.attributes().path("description").asString(), roles(cited)));
    content.add(new Citation(ids));
    return opened(INFERRED_SENTENCE, new Text(content, ids));
  }

  /**
   * The names of the roles the code checks, among the facts: identifiers in the code, such as the
   * enum constant {@code ADMIN}, not acronyms (#229).
   */
  private static Set<String> roles(List<Fact> facts) {
    Set<String> roles = new HashSet<>();
    for (Fact fact : facts) {
      if (fact.predicate().equals(Types.PERSON_EVIDENCE) && fact.object().value().equals(ROLE)) {
        String name = fact.attributes().path("name").asString("");
        if (!name.isBlank()) {
          roles.add(name);
        }
      }
    }
    return roles;
  }

  private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]+)`");

  /**
   * Markdown text, such as a decision record's, with its code spans ({@code `PROCESSING`}) as code,
   * so that what the documentation marks as code is code in the document too (#229).
   */
  static List<Inline> withCodeSpans(String text) {
    Matcher m = CODE_SPAN.matcher(text);
    List<Inline> content = new ArrayList<>();
    int at = 0;
    while (m.find()) {
      if (m.start() > at) {
        content.add(TextRun.of(text.substring(at, m.start())));
      }
      content.add(new TextRun(m.group(1), TextRun.Style.CODE));
      at = m.end();
    }
    if (at < text.length()) {
      content.add(TextRun.of(text.substring(at)));
    }
    return content;
  }

  /** The text with each whole-word occurrence of the identifiers as code. */
  static List<Inline> asCode(String text, Set<String> identifiers) {
    if (identifiers.isEmpty()) {
      return List.of(TextRun.of(text));
    }
    StringBuilder alternatives = new StringBuilder();
    identifiers.stream()
        .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(i -> i))
        .forEach(
            i -> alternatives.append(alternatives.isEmpty() ? "" : "|").append(Pattern.quote(i)));
    Matcher m =
        Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alternatives + ")(?![\\p{L}\\p{N}_])")
            .matcher(text);
    List<Inline> content = new ArrayList<>();
    int at = 0;
    while (m.find()) {
      if (m.start() > at) {
        content.add(TextRun.of(text.substring(at, m.start())));
      }
      content.add(new TextRun(m.group(), TextRun.Style.CODE));
      at = m.end();
    }
    if (at < text.length()) {
      content.add(TextRun.of(text.substring(at)));
    }
    return content;
  }

  /**
   * The description of an entity without a responsibility sentence: its summary, or for a
   * deployable unit its module's summary, which is about the module and less grounded than a
   * sentence about the unit, so it opens with {@value #FROM_MODULE} or, when its purpose is
   * inferred, {@value #FROM_MODULE_INFERRED}, emphasized (ADR 0066).
   */
  private Text summarizedDescription(EntityId id) {
    EntityId source = summarized(id);
    ArchitectureModel.Summary summary = model.summary(source).orElseThrow();
    if (source.equals(id)) {
      return summary(summary);
    }
    return opened(
        summary.evidenced() ? FROM_MODULE : FROM_MODULE_INFERRED,
        rich(summary.unit(), summary.marked()));
  }

  /** The facts the document refers to, including those its entities' descriptions cite. */
  List<Fact> documentFacts() {
    entities();
    return factIds.stream().map(knowledge::fact).toList();
  }
}
