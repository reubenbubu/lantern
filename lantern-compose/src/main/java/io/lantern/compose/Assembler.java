package io.lantern.compose;

import io.lantern.core.document.Citation;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.ElementNames;
import io.lantern.derive.Elements;
import io.lantern.derive.Groups;
import io.lantern.derive.UnknownCallers;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

  private final ArchitectureModel model;
  private final Set<EntityId> described;
  private final String repo;
  private final String systemName;
  private final ElementNames names;
  private final Set<EntityId> adding = new HashSet<>();
  private final Deque<EntityId> undescribed = new ArrayDeque<>();
  private final Map<EntityId, DocumentEntity> entities = new TreeMap<>(ArchitectureModel.BY_ID);
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
    this.described = described;
    this.repo = repo;
    this.systemName = systemName;
    this.names = new ElementNames(model, systemName);
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
    List<Fact> facts = new ArrayList<>(model.describing(id));
    if (id.type().equals(Types.SERVICE) || id.type().equals(Types.LIBRARY)) {
      for (EntityId module : model.parts(id, Types.MODULE)) {
        facts.addAll(model.containsFacts(id, module));
      }
    }
    if (parent != null) {
      entity(parent);
      facts.addAll(model.containsFacts(parent, id));
    }
    // An element only Investigate's connections show cites them, so it is marked as the LLM's
    // finding wherever it appears, Describe's sentence or not (#749).
    if (knownOnlyFromTheInvestigator(id)) {
      facts.addAll(investigatedTo(id));
    }
    entities.put(
        id,
        new DocumentEntity(
            id,
            name(id),
            parent,
            List.of(),
            facts(facts),
            kind(id),
            groupTag(id),
            groupOf.get(id),
            readableName(id)));
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
        || (responsibility(id) == null && foundOnlyByTheInvestigator(id))
        // A domain or feature area a diagram draws, by what Name groups wrote of it (ADR 0107),
        // else by its members (#933).
        || id.type().equals(Types.GROUP)
        // A unit whose sentence the support judge rejected, by Lantern's plain wording (#933).
        || (described.contains(id) && model.rejected(id, Types.RESPONSIBILITY))
        // The Unknown caller, marked unknown (#766).
        || id.equals(UnknownCallers.CALLER)) {
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
    if (!id.type().equals(Types.SERVICE)) {
      return id;
    }
    Optional<EntityId> main = model.mainLibraryModule(id);
    if (main.isPresent()) {
      return main.get();
    }
    SortedSet<EntityId> modules = model.parts(id, Types.MODULE);
    return modules.isEmpty() ? id : modules.first();
  }

  /**
   * The name in words Describe wrote for a unit or frontend named as its build names it, unless the
   * support judge rejected it (#946); {@code null} for none, when the report title-cases.
   */
  String readableName(EntityId id) {
    if (!(id.type().equals(Types.SERVICE) || id.type().equals(Types.FRONTEND))) {
      return null;
    }
    String name = written(id);
    // Two boxes never share a name: a written name another unit's box would also show is dropped
    // for each of them, which then read as their builds name them (#946).
    return name == null || shownBy(name.toLowerCase(Locale.ROOT)) > 1 ? null : name;
  }

  /** The name Describe wrote for a unit, unless rejected or its build's name; or null. */
  private String written(EntityId id) {
    String name = model.displayName(id).orElse(null);
    if (name == null || name.isBlank() || model.rejected(id, Types.DISPLAY_NAME)) {
      return null;
    }
    // A name of the build's own words only ("Api gateway" for api-gateway) says nothing more, and
    // title-casing writes them better, acronyms kept (#946).
    return words(name).equals(words(names.name(id))) ? null : name;
  }

  private static String words(String name) {
    return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
  }

  /** How many service and frontend boxes would show the name, written or title-cased. */
  private long shownBy(String name) {
    if (shownNames == null) {
      shownNames = new HashMap<>();
      for (String type : List.of(Types.SERVICE, Types.FRONTEND)) {
        for (EntityId unit : model.entities(type)) {
          String written = written(unit);
          shownNames.merge(
              (written != null ? written : ReadableNames.of(names.name(unit)))
                  .toLowerCase(Locale.ROOT),
              1L,
              Long::sum);
        }
      }
    }
    return shownNames.getOrDefault(name, 0L);
  }

  private Map<String, Long> shownNames;

  /** The name a unit's box shows: the name Describe wrote, else its name made readable. */
  String shownName(EntityId id) {
    String readable = readableName(id);
    return readable != null ? readable : ReadableNames.of(name(id));
  }

  /** What follows the name of a group no role named. */
  static final String NOT_NAMED = " (not named)";

  /** The name a reader sees for an entity ({@link ElementNames#name}). */
  String name(EntityId id) {
    if (id.type().equals(Types.GROUP)) {
      // The name Name groups gave it (ADR 0107), else its key's last part, marked as not named
      // (knowledge-build epic, section 11.8). A name the support judge rejected, though asked
      // again, gives way to the name the code gives the group, its key's last part (#751).
      return model
          .displayName(id)
          .orElseGet(
              () -> names.name(id) + (model.rejected(id, Types.DISPLAY_NAME) ? "" : NOT_NAMED));
    }
    return names.name(id);
  }

  /** The names of the document's entities. */
  ElementNames names() {
    return names;
  }

  /** The entity the given one belongs under in the navigation tree, or {@code null}. */
  private EntityId parent(EntityId id) {
    return switch (id.type()) {
      case Types.REPO, Types.ARTIFACT -> null;
      case Types.MODULE -> {
        SortedSet<EntityId> aggregators = model.wholes(id, Types.MODULE);
        if (!aggregators.isEmpty()) {
          yield aggregators.first();
        }
        yield repoOf(model.repos(id));
      }
      case Types.SERVICE, Types.LIBRARY -> {
        SortedSet<String> repos = new TreeSet<>();
        for (EntityId module : model.parts(id, Types.MODULE)) {
          repos.addAll(model.repos(module));
        }
        yield repoOf(repos);
      }
      case Types.TYPE -> {
        SortedSet<EntityId> wholes = model.wholes(id, Types.PACKAGE);
        wholes.addAll(model.wholes(id, Types.TYPE));
        if (wholes.isEmpty()) {
          wholes = model.wholes(id, Types.MODULE);
        }
        yield wholes.isEmpty() ? null : wholes.first();
      }
      // A feature area is under the unit whose components it groups; a domain under none.
      case Types.GROUP -> {
        int area = id.key().indexOf("/area/");
        yield area < 0 ? null : EntityId.parse(id.key().substring(0, area));
      }
      case Types.FRONTEND ->
          Elements.frontendApp(model, id)
              .map(f -> repoOf(new TreeSet<>(Set.of(f.evidence().repo()))))
              .orElse(null);
      default -> {
        SortedSet<EntityId> wholes = model.wholes(id);
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
    return new EntityId(Types.REPO, name);
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
    return evidenced(RepoSections.SYSTEM) ? text : inferred(text);
  }

  /**
   * What the documents say of the system in place of a purpose the support judge rejected, though
   * asked again (the decision of 4 Nov 2026 on #751): the first paragraph of a README that reads as
   * a description, quoted as its authors wrote it; else a plain line of what the system is made of;
   * {@code null} when the judge rejected none, or the model holds neither.
   */
  Text plainPurpose() {
    if (!model.rejected(RepoSections.SYSTEM, Types.PURPOSE)) {
      return null;
    }
    java.util.Optional<ArchitectureModel.ReadmeParagraph> readme = model.firstReadmeDescription();
    if (readme.isPresent()) {
      factIds.add(readme.get().fact());
      return new Text(
          List.of(TextRun.of("Its README says: \u201c" + readme.get().text() + "\u201d")),
          List.of(readme.get().fact()));
    }
    List<String> parts = new ArrayList<>();
    count(parts, io.lantern.derive.Elements.units(model).size(), "service", "services");
    count(parts, io.lantern.derive.Elements.frontends(model).size(), "frontend", "frontends");
    count(parts, model.entities(Types.DATABASE).size(), "database", "databases");
    if (parts.isEmpty()) {
      return null;
    }
    String made =
        parts.size() == 1
            ? parts.getFirst()
            : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    String name =
        systemName.isEmpty()
            ? "The system"
            : Character.toUpperCase(systemName.charAt(0)) + systemName.substring(1);
    return new Text(List.of(TextRun.of(name + " is made of " + made + ".")), List.of());
  }

  private static void count(List<String> parts, int n, String one, String many) {
    if (n > 0) {
      parts.add(n + " " + (n == 1 ? one : many));
    }
  }

  /**
   * A responsibility sentence as rich text (ADR 0050, ADR 0056). One that cites no README claim is
   * inferred from the code and opens with {@value #INFERRED_SENTENCE}, emphasized (ADR 0065).
   */
  private Text responsibility(EntityId about, String sentence) {
    Text text = rich(about, sentence);
    return evidenced(about) ? text : opened(INFERRED_SENTENCE, text);
  }

  /**
   * Whether the stored sentence about the element rests on what people wrote (ADR 0059, ADR 0060,
   * ADR 0069), as the role that wrote it stored it in its {@code basis}; read, not derived again
   * from the citations that are in the snapshot (#767).
   */
  private boolean evidenced(EntityId about) {
    return sentence(about).map(ArchitectureModel.Text::evidenced).orElse(false);
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
      boolean known = !matcher.group(1).equals("fact") || model.fact(matcher.group(2)) != null;
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
    return phrased(model.intent(link).orElse(null));
  }

  /**
   * The intent Describe stored for a container line that merges links whose intents differ, or
   * {@code null} (ADR 0103, amendment for #810).
   */
  Phrased linePhrase(EntityId from, EntityId to, String relationship) {
    return phrased(model.lineIntent(from, to, relationship).orElse(null));
  }

  /** The intent Describe stored for a person's use of a frontend, or {@code null} (#739). */
  Phrased personPhrase(Fact basis, EntityId frontend) {
    return phrased(model.personIntent(basis, frontend).orElse(null));
  }

  /**
   * What a person does, as a line's purpose (#933): the first clause of Describe's sentence about
   * them, at most eight words, citing what it cites; or {@code null} if it wrote none.
   */
  private static final int MOST_PURPOSE_WORDS = 8;

  /** Words that open a qualifier after what a person does. */
  private static final Set<String> QUALIFIERS =
      Set.of(
          "submitted",
          "by",
          "for",
          "with",
          "that",
          "which",
          "who",
          "through",
          "from",
          "to",
          "in",
          "on",
          "using",
          "via",
          "across",
          "when",
          "while",
          "so",
          "as",
          "and",
          "or");

  Phrased personPurpose(EntityId person) {
    ArchitectureModel.Text sentence = model.responsibility(person).orElse(null);
    if (sentence == null || sentence.text() == null || sentence.text().isBlank()) {
      return null;
    }
    String text = sentence.text().strip().replaceAll("\\b(e\\.g|i\\.e|etc)\\.", "$1");
    String clause = text.split("[;,:(]|\\.(?:\\s|$)", 2)[0].strip();
    List<String> words = new ArrayList<>(List.of(clause.split("\\s+")));
    // Too long for a line: up to the first word that opens a qualifier ("submitted by …"),
    // never cut mid-phrase; none such within reach, no purpose.
    if (words.size() > MOST_PURPOSE_WORDS) {
      int cut = -1;
      for (int i = 3; i < words.size() && i <= MOST_PURPOSE_WORDS; i++) {
        if (QUALIFIERS.contains(words.get(i).toLowerCase(Locale.ROOT))) {
          cut = i;
          break;
        }
      }
      if (cut < 0) {
        return null;
      }
      words = words.subList(0, cut);
    }
    clause = String.join(" ", words).replaceAll("\\b(e\\.g|i\\.e)\\b(?!\\.)", "$1.");
    if (clause.isEmpty()) {
      return null;
    }
    Phrased cited = phrased(sentence);
    return new Phrased(clause, cited == null ? List.of() : cited.facts());
  }

  /**
   * The intent Name groups stored for a line leaving a group (ADR 0107), and the facts it cites
   * that are in the snapshot, or {@code null} if it stored none.
   *
   * @param otherEnd what the line leads to, with its relationship: {@code "<other end> <type>"}
   */
  Phrased groupIntent(EntityId group, String otherEnd) {
    return phrased(model.groupIntent(group, otherEnd).orElse(null));
  }

  private Phrased phrased(ArchitectureModel.Text phrase) {
    if (phrase == null) {
      return null;
    }
    List<Fact> facts = new ArrayList<>();
    for (String id : phrase.cites()) {
      Fact fact = model.fact(id);
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
        Fact cited = model.fact(matcher.group(2));
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
    return new Text(out.content, facts(out.cited.stream().map(model::fact).toList()));
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
          // A person is described by Describe's sentence where it wrote one, else by the
          // linker's own note (#767).
          entity.id().equals(UnknownCallers.CALLER)
              ? unknownCaller().content()
              : entity.id().type().equals(Types.PERSON)
                      && personBasis(entity.id()) != null
                      && responsibility == null
                  ? person(entity.id()).content()
                  : entity.id().type().equals(Types.FRONTEND) && responsibility == null
                      ? frontend(entity.id()).content()
                      : responsibility == null && foundOnlyByTheInvestigator(entity.id())
                          ? investigated(entity.id()).content()
                          : entity.id().equals(RepoSections.SYSTEM) && responsibility != null
                              ? purpose().content()
                              : responsibility != null
                                  ? responsibility(entity.id(), responsibility).content()
                                  // A box whose sentence the support judge rejected, though
                                  // asked again, or that has none, shows Lantern's plain
                                  // wording, never nothing (#933).
                                  : plain(entity.id()).content();
      entities.put(
          entity.id(),
          new DocumentEntity(
              entity.id(),
              entity.name(),
              entity.parent(),
              description,
              entity.facts(),
              entity.kind(),
              entity.tag(),
              entity.group(),
              entity.readableName()));
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
    if (vocabulary.isA(type, Types.ACTOR)) {
      return DocumentEntity.Kind.PERSON;
    }
    if (!type.equals(Types.SYSTEM) && vocabulary.isA(type, Types.SYSTEM)) {
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
    if (type.equals(Types.REPO)) {
      return "Repository";
    }
    if (type.equals(Types.FRONTEND)) {
      return "Single-Page App";
    }
    if (type.equals(Types.GROUP)) {
      return id.key().contains("/" + Groups.DOMAIN + "/") ? "Domain" : "Feature area";
    }
    String words = type.replace('-', ' ');
    return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
  }

  /**
   * The groups a box says it belongs to (#764): each unit of a domain, and each component's main
   * type of a feature area, that holds at least the limits' smallest group; empty until {@link
   * #groups} is called.
   */
  private Map<EntityId, EntityId> groupOf = Map.of();

  /**
   * Names, on each box of a group's member, the group it belongs to, as the views draw no frame
   * around a group's members (owner's decision of 5 Nov 2026 on #764; #897). Called before any
   * entity is added.
   *
   * @param smallestGroup the fewest members a group holds to be named
   */
  void groups(int smallestGroup) {
    Map<EntityId, EntityId> result = new HashMap<>();
    for (String kind : List.of(Groups.DOMAIN, Groups.FEATURE_AREA)) {
      for (Groups.Group group : Groups.of(model, kind)) {
        if (group.members().size() < smallestGroup) {
          continue;
        }
        for (EntityId member : group.members()) {
          result.putIfAbsent(boxOf(member), group.id());
        }
      }
    }
    groupOf = result;
  }

  /** The box a group member is drawn as: a component by its main type, else itself. */
  private static EntityId boxOf(EntityId member) {
    if (member.type().equals(Types.COMPONENT)) {
      int anchor = member.key().indexOf("/" + Types.TYPE + ":");
      if (anchor >= 0) {
        return EntityId.parse(member.key().substring(anchor + 1));
      }
    }
    return member;
  }

  /** The entity's {@link #tag}, followed by the group it belongs to, if any. */
  private String groupTag(EntityId id) {
    String tag = tag(id);
    EntityId group = groupOf.get(id);
    if (group == null) {
      return tag;
    }
    String what = tag != null ? tag : id.type().equals(Types.TYPE) ? "Component" : "Container";
    return what + " · " + name(group);
  }

  /** The types {@link #tag} names no type for. */
  private static final Set<String> UNTAGGED =
      Set.of(
          Types.TYPE,
          Types.EXTERNAL_SYSTEM,
          Types.SYSTEM,
          Types.CONTAINER,
          Types.COMPONENT,
          Types.DATABASE,
          Types.PERSON);

  /** The shape the vocabulary draws data stores with. */
  private static final String CYLINDER = "cylinder";

  /** The shape the vocabulary draws topics, queues and exchanges with. */
  private static final String PIPE = "pipe";

  /** The connections Investigate found to an entity (ADR 0110). */
  private List<Fact> investigatedTo(EntityId id) {
    return model.investigatedTo(id);
  }

  /**
   * Whether only what the LLM found shows the element: Investigate's connections end at it, every
   * fact about it comes from the LLM (such as Describe's sentence), and no fact of static analysis
   * or derivation relates anything to it.
   */
  private boolean knownOnlyFromTheInvestigator(EntityId id) {
    if (!destination(id) || investigatedTo(id).isEmpty()) {
      return false;
    }
    return model.knownOnlyToTheLlm(id);
  }

  /** Whether only Investigate's connections know the entity: no fact is about it. */
  private boolean foundOnlyByTheInvestigator(EntityId id) {
    return destination(id) && !model.hasFactsAbout(id) && !investigatedTo(id).isEmpty();
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
          .append(ArchitectureModel.description(fact))
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
    Elements.FrontendApp app = Elements.frontendAppOf(model, id).orElse(null);
    if (app == null) {
      return new Text(List.of(), List.of());
    }
    StringBuilder text = new StringBuilder("A single-page app built with ");
    text.append(app.framework());
    if (app.version() != null) {
      text.append(' ').append(app.version());
    }
    if (app.server() != null) {
      text.append(", served by ").append(app.server());
    }
    List<Fact> cited = new ArrayList<>(List.of(app.fact()));
    SortedSet<String> called = new TreeSet<>();
    for (Fact call : model.linksOf(id, Types.CALLS_HTTP)) {
      called.add(name(ArchitectureModel.object(call)));
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
    return Elements.personBasis(model, id);
  }

  /**
   * What the Unknown caller is (ADR 0074, amendment for #365; #766): marked unknown, citing the
   * lines that make it.
   */
  private Text unknownCaller() {
    List<String> ids = facts(model.unknownCallerLines());
    return new Text(
        List.of(
            TextRun.of(
                UNKNOWN_SENTENCE
                    + " Something outside the analysed repositories calls these entry points:"
                    + " nothing in them reaches them and no person is shown to use them."),
            new Citation(ids)),
        ids);
  }

  /** What opens the Unknown caller's description. */
  static final String UNKNOWN_SENTENCE = "Unknown:";

  /**
   * The description of a person: the sentence the linker wrote from the code's own words, opened
   * with {@value #INFERRED_SENTENCE} since a person is always inferred (ADR 0074), citing the facts
   * it rests on.
   */
  private Text person(EntityId id) {
    Fact basis = personBasis(id);
    List<Fact> cited = new ArrayList<>(List.of(basis));
    for (String factId : ArchitectureModel.cites(basis)) {
      Fact fact = model.fact(factId);
      if (fact != null) {
        cited.add(fact);
      }
    }
    List<String> ids = facts(cited);
    List<Inline> content =
        new ArrayList<>(asCode(ArchitectureModel.description(basis), roles(cited)));
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
      Elements.roleEvidence(fact).filter(name -> !name.isBlank()).ifPresent(roles::add);
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
   * Lantern's plain wording for a box with no sentence that holds (#933; ADR 0101, amendment for
   * #751): a group by its members; a unit by its module's summary, else by what it calls, uses and
   * sends; never nothing a diagram could show as a blank box.
   */
  private Text plain(EntityId id) {
    if (id.type().equals(Types.GROUP)) {
      List<Fact> members = model.groupMembers(id);
      SortedSet<String> names = new TreeSet<>();
      members.forEach(f -> names.add(shownName(ArchitectureModel.object(f))));
      if (names.isEmpty()) {
        return new Text(List.of(), List.of());
      }
      List<String> ids = facts(members);
      return new Text(
          List.of(TextRun.of("Holds " + listedAtMost(names) + "."), new Citation(ids)), ids);
    }
    // A module's summary stands in for a unit with no sentence, never for one the judge rejected,
    // whose claims it may share (#751).
    if (!model.rejected(id, Types.RESPONSIBILITY) && model.summary(summarized(id)).isPresent()) {
      return summarizedDescription(id);
    }
    if (!UNIT_TYPES.contains(id.type())) {
      return new Text(List.of(), List.of());
    }
    List<String> parts = new ArrayList<>();
    List<Fact> cited = new ArrayList<>();
    for (String[] kind :
        new String[][] {
          {Types.CALLS_HTTP, "calls"},
          {Types.USES_DATABASE, "uses"},
          {Types.PUBLISHES, "sends to"},
          {Types.CONSUMES, "receives from"}
        }) {
      SortedSet<String> to = new TreeSet<>();
      for (Fact link : model.linksOf(id, kind[0])) {
        EntityId end = ArchitectureModel.object(link);
        to.add(UNIT_TYPES.contains(end.type()) ? shownName(end) : name(end));
        cited.add(link);
      }
      if (!to.isEmpty()) {
        parts.add(kind[1] + " " + listedAtMost(to));
      }
    }
    if (parts.isEmpty()) {
      // Nothing it reaches: still not a blank box (#933).
      return new Text(
          List.of(TextRun.of("A deployable unit; what it does is not known.")), List.of());
    }
    List<String> ids = facts(cited);
    return new Text(
        List.of(
            TextRun.of("A deployable unit that " + listed(new ArrayList<>(parts)) + "."),
            new Citation(ids)),
        ids);
  }

  /** The types of a box the plain wording calls a deployable unit. */
  private static final Set<String> UNIT_TYPES = Set.of(Types.SERVICE, Types.FRONTEND);

  /** At most four names, else three and the rest counted: "a, b, c and 2 others". */
  private static String listedAtMost(java.util.Collection<String> names) {
    List<String> list = new ArrayList<>(names);
    if (list.size() <= 4) {
      return listed(list);
    }
    return String.join(", ", list.subList(0, 3)) + " and " + (list.size() - 3) + " others";
  }

  /** The names as a sentence list: "a", "a and b", "a, b and c". */
  private static String listed(java.util.Collection<String> names) {
    List<String> list = new ArrayList<>(names);
    if (list.size() == 1) {
      return list.getFirst();
    }
    String last = list.removeLast();
    return String.join(", ", list) + " and " + last;
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
    return factIds.stream().map(model::fact).toList();
  }
}
