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
  private static final Pattern NOT_ID_CHAR = Pattern.compile("[^A-Za-z0-9._:-]");

  private final Knowledge knowledge;
  private final Map<EntityId, Summary> summaries;
  private final Set<EntityId> described;
  private final String repo;
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
   *     or library observed in several repositories
   */
  Assembler(
      Knowledge knowledge, Map<EntityId, Summary> summaries, Set<EntityId> described, String repo) {
    this.knowledge = knowledge;
    this.summaries = summaries;
    this.described = described;
    this.repo = repo;
  }

  /** The text of a rich-text element and the facts it cites. */
  record Text(List<Inline> content, List<String> facts) {}

  /**
   * Adds the entity to the document, with its parent; if it is in scope and has a summary, its
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
    if (described.contains(id) && summaries.containsKey(id)) {
      undescribed.add(id);
    }
    return id;
  }

  /** Adds the entity to the document and returns its parent in the navigation tree. */
  EntityId parentOf(EntityId id) {
    return entities.get(entity(id)).parent();
  }

  /** The entity's display name. */
  String name(EntityId id) {
    String key = id.key();
    return switch (id.type()) {
      case BuiltInTypes.MODULE,
          BuiltInTypes.SERVICE,
          BuiltInTypes.LIBRARY,
          BuiltInTypes.PACKAGE,
          "artifact" ->
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
      default -> {
        SortedSet<EntityId> wholes = knowledge.wholes(id);
        yield wholes.isEmpty() ? null : wholes.first();
      }
    };
  }

  /** The document's repository if it is one of these, else the first, or {@code null}. */
  private EntityId repoOf(SortedSet<String> repos) {
    if (repos.contains(repo)) {
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
   * citations; the entity is added to the document.
   *
   * @throws IllegalArgumentException if the summary cites a fact that is not in the snapshot
   */
  Text summary(Summary summary) {
    RichText out = new RichText();
    Matcher matcher = MARKER.matcher(summary.text());
    int last = 0;
    while (matcher.find()) {
      String between = summary.text().substring(last, matcher.start());
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
                  + summary.entity()
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
    out.text(summary.text().substring(last));
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
      List<Inline> description = summary(summaries.get(entity.id())).content();
      entities.put(
          entity.id(),
          new DocumentEntity(
              entity.id(), entity.name(), entity.parent(), description, entity.facts()));
    }
    return List.copyOf(entities.values());
  }

  /** The facts the document refers to, including those its entities' descriptions cite. */
  List<Fact> documentFacts() {
    entities();
    return factIds.stream().map(knowledge::fact).toList();
  }
}
