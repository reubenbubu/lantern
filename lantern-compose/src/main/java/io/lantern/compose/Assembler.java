package io.lantern.compose;

import io.lantern.core.document.Citation;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.summarize.Summary;
import io.lantern.summarize.SummaryLevel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /** The predicate of a README claim (ADR 0033). */
  private static final String CLAIM = "claim";

  /** What opens a summary whose purpose is inferred from the code (ADR 0059). */
  static final String INFERRED = "Purpose inferred from the code:";

  /**
   * What opens a responsibility sentence that cites no README claim (ADR 0065): short, since it is
   * the element's description on diagrams.
   */
  static final String INFERRED_SENTENCE = "Inferred:";

  /**
   * What opens a deployable unit's description taken from its module's summary because the unit has
   * no responsibility sentence (ADR 0066), when the summary's purpose is evidenced.
   */
  static final String FROM_MODULE = "From its module's summary:";

  /** As {@link #FROM_MODULE}, when the summary's purpose is inferred from the code. */
  static final String FROM_MODULE_INFERRED = "Inferred, from its module's summary:";

  private static final Pattern NOT_ID_CHAR = Pattern.compile("[^A-Za-z0-9._:-]");

  private final Knowledge knowledge;
  private final Map<EntityId, Summary> summaries;
  private final Wordings wordings;
  private final Set<EntityId> described;
  private final String repo;
  private final String systemName;
  private final Set<EntityId> adding = new HashSet<>();
  private final Deque<EntityId> undescribed = new ArrayDeque<>();
  private final Map<EntityId, DocumentEntity> entities = new TreeMap<>(Knowledge.BY_ID);
  private final Set<String> factIds = new TreeSet<>();
  private final Set<String> elementIds = new HashSet<>();

  /**
   * @param knowledge the snapshot's facts
   * @param summaries the summaries, by entity
   * @param described the entities whose summaries become their descriptions: those in scope
   * @param repo the repository the document is about, preferred as the parent of a module, service
   *     or library observed in several repositories, or {@code null} for a document of the system
   * @param systemName the name of the software system the document's containers belong to
   */
  Assembler(
      Knowledge knowledge,
      Map<EntityId, Summary> summaries,
      Set<EntityId> described,
      String repo,
      String systemName) {
    this(knowledge, summaries, Wordings.NONE, described, repo, systemName);
  }

  /**
   * @param wordings the responsibility sentences that describe units and components instead of
   *     their summaries, and data stores and destinations (ADR 0050, ADR 0056), and the intent
   *     phrases of links
   */
  Assembler(
      Knowledge knowledge,
      Map<EntityId, Summary> summaries,
      Wordings wordings,
      Set<EntityId> described,
      String repo,
      String systemName) {
    this.knowledge = knowledge;
    this.summaries = summaries;
    this.wordings = wordings;
    this.described = described;
    this.repo = repo;
    this.systemName = systemName;
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
    entities.put(id, new DocumentEntity(id, name(id), parent, List.of(), facts(facts)));
    adding.remove(id);
    if (described.contains(id)
            && (summaries.containsKey(summarized(id)) || responsibility(id) != null)
        // A data store or destination has no summary; it is described wherever a diagram draws
        // it, by its responsibility sentence (ADR 0056).
        || (C4Views.DESTINATIONS.contains(id.type()) && responsibility(id) != null)
        // A person is described by what the code shows of them (ADR 0074).
        || (id.type().equals(C4Views.PERSON) && personBasis(id) != null)
        // A frontend by what its manifest and Dockerfile say (ADR 0083).
        || (id.type().equals(C4Views.FRONTEND) && responsibility(id) == null)) {
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
    for (Fact fact : knowledge.relations(id, C4Views.BUNDLES)) {
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

  /** The entity's display name. */
  String name(EntityId id) {
    String key = id.key();
    return switch (id.type()) {
      case BuiltInTypes.SERVICE -> {
        Optional<String> name = C4Views.applicationName(knowledge, id);
        yield name.orElse(key.substring(key.lastIndexOf(':') + 1));
      }
      case BuiltInTypes.SYSTEM -> systemName;
      case C4Views.DATABASE -> {
        Optional<String> display = knowledge.attribute(id, "display-name");
        if (display.isPresent()) {
          // A unit's own database is named by the linker (#291).
          yield display.get();
        }
        // server/database, the server host:port or the unit an embedded database belongs to.
        int slash = key.lastIndexOf('/');
        if (slash < 0) {
          yield key;
        }
        String server = key.substring(0, slash);
        int colon = server.lastIndexOf(':');
        String host =
            colon > 0 && server.substring(colon + 1).matches("[0-9]+")
                ? server.substring(0, colon)
                : server;
        yield key.substring(slash + 1) + " on " + host;
      }
      case C4Views.TOPIC, C4Views.QUEUE, C4Views.EXCHANGE -> key.substring(key.indexOf(':') + 1);
      case C4Views.EXTERNAL_SYSTEM ->
          knowledge
              .attribute(id, "display-name")
              .or(() -> MailProviders.name(key))
              .or(() -> IdentityProviders.name(key))
              .orElse(key);
      case C4Views.DECISION -> knowledge.attribute(id, "title").orElse(key);
      case C4Views.PERSON -> knowledge.attribute(id, "display-name").orElse(key);
      case C4Views.FRONTEND ->
          knowledge
              .attribute(id, "display-name")
              .or(
                  () ->
                      C4Views.frontendApp(knowledge, id)
                          .map(f -> RepoSections.attribute(f, "name", key)))
              .orElse(key);
      case BuiltInTypes.MODULE, BuiltInTypes.LIBRARY, BuiltInTypes.PACKAGE, "artifact" ->
          key.substring(key.lastIndexOf(':') + 1);
      case BuiltInTypes.TYPE -> key.substring(key.lastIndexOf('.') + 1).replace('$', '.');
      case "method", "field" -> key.substring(key.indexOf('#') + 1);
      default -> key;
    };
  }

  /** The entity the given one belongs under in the navigation tree, or {@code null}. */
  private EntityId parent(EntityId id) {
    return switch (id.type()) {
      case SummaryLevel.REPO_ENTITY_TYPE, "artifact" -> null;
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
      case C4Views.FRONTEND ->
          C4Views.frontendApp(knowledge, id)
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
    return new EntityId(SummaryLevel.REPO_ENTITY_TYPE, name);
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
  Text summary(Summary summary) {
    Text text = rich(summary.entity(), summary.text());
    return summary.purpose() == Summary.Purpose.EVIDENCED ? text : inferred(text);
  }

  /**
   * The entity's responsibility sentence (ADR 0050), with the markers of facts that are not in the
   * snapshot's facts left out, or {@code null} if it has none that still cites something.
   */
  private String responsibility(EntityId id) {
    return known(wordings.responsibilities().get(id));
  }

  /**
   * The LLM's walkthrough and conditions of the flow whose prompt has the key (ADR 0058), or {@code
   * null} if it has none.
   */
  Wordings.Flow flow(String key) {
    return wordings.flows().get(key);
  }

  /**
   * The system's purpose (ADR 0060) as rich text citing the facts that are in the snapshot, or
   * {@code null} if the LLM worded none or none of its citations is in the snapshot. A purpose that
   * cites no README claim is inferred from the code, through the units' sentences, and opens with
   * {@value #INFERRED}, emphasized, as an inferred summary does (ADR 0059).
   */
  Text purpose() {
    String kept = known(wordings.responsibilities().get(RepoSections.SYSTEM));
    return kept == null ? null : purpose(rich(RepoSections.SYSTEM, kept));
  }

  private Text purpose(Text text) {
    return evidenced(text, false) ? text : inferred(text);
  }

  /**
   * A responsibility sentence as rich text (ADR 0050, ADR 0056). One that cites no README claim is
   * inferred from the code and opens with {@value #INFERRED_SENTENCE}, emphasized (ADR 0065).
   */
  private Text responsibility(EntityId about, String sentence) {
    Text text = rich(about, sentence);
    return evidenced(text, true) ? text : opened(INFERRED_SENTENCE, text);
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
                    && (f.predicate().equals(CLAIM)
                        || code && wordings.documented().contains(f.id())));
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
    Wordings.Phrase phrase = wordings.phrases().get(link.id());
    if (phrase == null) {
      return null;
    }
    List<Fact> facts = new ArrayList<>();
    for (String id : phrase.facts()) {
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
          entity.id().type().equals(C4Views.PERSON) && personBasis(entity.id()) != null
              ? person(entity.id()).content()
              : entity.id().type().equals(C4Views.FRONTEND) && responsibility == null
                  ? frontend(entity.id()).content()
                  : entity.id().equals(RepoSections.SYSTEM) && responsibility != null
                      ? purpose().content()
                      : responsibility != null
                          ? responsibility(entity.id(), responsibility).content()
                          : summarizedDescription(entity.id()).content();
      entities.put(
          entity.id(),
          new DocumentEntity(
              entity.id(), entity.name(), entity.parent(), description, entity.facts()));
    }
    return List.copyOf(entities.values());
  }

  /**
   * The description of a frontend without a responsibility sentence: what its manifest and
   * Dockerfile say, citing its {@code frontend-app} fact, and what it calls.
   */
  private Text frontend(EntityId id) {
    Fact app = C4Views.frontendApp(knowledge, id).orElse(null);
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
    for (Fact call : knowledge.relations(id, C4Views.CALLS_HTTP)) {
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

  /** The fact saying what a person rests on, or {@code null}. */
  private Fact personBasis(EntityId id) {
    return knowledge.attributes(id).stream()
        .filter(f -> f.predicate().equals(C4Views.PERSON_BASIS))
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
    for (String factId : C4Views.strings(basis, "cites")) {
      Fact fact = knowledge.fact(factId);
      if (fact != null) {
        cited.add(fact);
      }
    }
    List<String> ids = facts(cited);
    return opened(
        INFERRED_SENTENCE,
        new Text(
            List.of(
                TextRun.of(basis.attributes().path("description").asString()), new Citation(ids)),
            ids));
  }

  /**
   * The description of an entity without a responsibility sentence: its summary, or for a
   * deployable unit its module's summary, which is about the module and less grounded than a
   * sentence about the unit, so it opens with {@value #FROM_MODULE} or, when its purpose is
   * inferred, {@value #FROM_MODULE_INFERRED}, emphasized (ADR 0066).
   */
  private Text summarizedDescription(EntityId id) {
    EntityId source = summarized(id);
    Summary summary = summaries.get(source);
    if (source.equals(id)) {
      return summary(summary);
    }
    return opened(
        summary.purpose() == Summary.Purpose.EVIDENCED ? FROM_MODULE : FROM_MODULE_INFERRED,
        rich(summary.entity(), summary.text()));
  }

  /** The facts the document refers to, including those its entities' descriptions cite. */
  List<Fact> documentFacts() {
    entities();
    return factIds.stream().map(knowledge::fact).toList();
  }
}
