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
import java.util.ArrayList;
import java.util.Collection;
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
  private final Map<EntityId, DocumentEntity> entities = new TreeMap<>(Knowledge.BY_ID);
  private final Set<String> factIds = new TreeSet<>();
  private final Set<String> elementIds = new HashSet<>();

  /**
   * @param knowledge the snapshot's facts
   * @param summaries the summaries, by entity
   * @param described the entities whose summaries become their descriptions: those in scope
   */
  Assembler(Knowledge knowledge, Map<EntityId, Summary> summaries, Set<EntityId> described) {
    this.knowledge = knowledge;
    this.summaries = summaries;
    this.described = described;
  }

  /** The text of a rich-text element and the facts it cites. */
  record Text(List<Inline> content, List<String> facts) {}

  /**
   * Adds the entity to the document, with its parent and, if it is in scope and has a summary, its
   * description. Its facts are its attributes, the facts placing it in its parent and, for a
   * service or library, the facts saying which modules it builds. Returns the entity.
   */
  EntityId entity(EntityId id) {
    if (entities.containsKey(id)) {
      return id;
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
    // Registered before its description is built, which may refer back to it.
    entities.put(id, new DocumentEntity(id, name(id), parent, List.of(), facts(facts)));
    Summary summary = described.contains(id) ? summaries.get(id) : null;
    if (summary != null) {
      List<Inline> description = summary(summary).content();
      entities.put(id, new DocumentEntity(id, name(id), parent, description, facts(facts)));
    }
    return id;
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
        SortedSet<String> repos = knowledge.repos(id);
        yield repos.isEmpty() ? null : repo(repos.first());
      }
      case BuiltInTypes.SERVICE, BuiltInTypes.LIBRARY -> {
        for (EntityId module : knowledge.parts(id, BuiltInTypes.MODULE)) {
          SortedSet<String> repos = knowledge.repos(module);
          if (!repos.isEmpty()) {
            yield repo(repos.first());
          }
        }
        yield null;
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
   * and {@code [summary:<entity>]} markers cross-references to the entity, which is added to the
   * document.
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
        out.reference(new CrossReference(name(target), CrossReference.Target.entity(target)));
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

    void text(String value) {
      text.append(value);
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

    /** Adds a cross-reference, separated by a space from what comes before. */
    void reference(CrossReference reference) {
      flushCitation();
      boolean spaced =
          text.isEmpty()
              ? content.isEmpty()
                  || (content.getLast() instanceof TextRun run
                      && Character.isWhitespace(run.text().charAt(run.text().length() - 1)))
              : Character.isWhitespace(text.charAt(text.length() - 1));
      if (!spaced) {
        text.append(' ');
      }
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
    String base = NOT_ID_CHAR.matcher(String.join(".", parts)).replaceAll("-");
    String id = base;
    for (int n = 2; !elementIds.add(id); n++) {
      id = base + "-" + n;
    }
    return id;
  }

  /** An edge ID unique within one diagram. */
  static String edgeId(Set<String> used, EntityId from, String relationship, EntityId to) {
    String base = NOT_ID_CHAR.matcher(from + "--" + relationship + "--" + to).replaceAll("-");
    String id = base;
    for (int n = 2; !used.add(id); n++) {
      id = base + "-" + n;
    }
    return id;
  }

  List<DocumentEntity> entities() {
    return List.copyOf(entities.values());
  }

  List<Fact> documentFacts() {
    return factIds.stream().map(knowledge::fact).toList();
  }
}
