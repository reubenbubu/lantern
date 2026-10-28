package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A format-neutral document: sections of paragraphs, lists, callouts, tables, diagrams, sequence
 * diagrams, trees, headline numbers and quality reports, with cross-references, each element linked
 * to the fact IDs it came from. The composer builds it and renderers format it. The JSON form is
 * defined by {@code schema/document.schema.json} and written and read by {@link DocumentJson}.
 *
 * <p>A document is self-contained: every fact ID it mentions is in {@link #facts}, every entity it
 * mentions is in {@link #entities}, and every cross-reference points to a section, block or entity
 * of the same document. Section and block IDs are unique across the document. Entities and facts
 * are held sorted by ID, so the same content always has the same form.
 *
 * @param id the document ID, stable across regenerations of the same view
 * @param title the document title
 * @param audience the audience profile the document was composed for, or {@code null}
 * @param template the template the document is meant to be rendered with
 * @param source what the document was composed from
 * @param entities the entities the document refers to
 * @param facts the facts the document refers to
 * @param sections the top-level sections, in reading order
 */
public record Document(
    String id,
    String title,
    String audience,
    TemplateRef template,
    DocumentSource source,
    List<DocumentEntity> entities,
    List<Fact> facts,
    List<Section> sections) {

  /** The version of {@code schema/document.schema.json} this type implements. */
  public static final int SCHEMA_VERSION = 1;

  public Document {
    Checks.elementId(id, "document ID");
    Checks.text(title, "document title");
    Checks.optionalText(audience, "audience");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(source, "source");
    entities = sorted(Checks.list(entities, "entities"), e -> e.id().toString(), "entity");
    facts = sorted(Checks.list(facts, "facts"), Fact::id, "fact");
    sections = Checks.list(sections, "sections");
    new References(entities, facts, sections).check();
  }

  private static <T> List<T> sorted(List<T> values, Function<T, String> key, String what) {
    List<T> copy = new ArrayList<>(values);
    copy.sort(Comparator.comparing(key));
    for (int i = 1; i < copy.size(); i++) {
      if (key.apply(copy.get(i - 1)).equals(key.apply(copy.get(i)))) {
        throw new IllegalArgumentException("Duplicate " + what + " " + key.apply(copy.get(i)));
      }
    }
    return List.copyOf(copy);
  }

  /** Checks that what the document mentions is in the document. */
  private static final class References {

    private final Map<EntityId, DocumentEntity> entities = new HashMap<>();
    private final Set<String> factIds = new HashSet<>();
    private final List<Section> sections;
    private final Set<String> sectionIds = new HashSet<>();
    private final Set<String> blockIds = new HashSet<>();
    private final List<CrossReference.Target> targets = new ArrayList<>();

    References(List<DocumentEntity> entities, List<Fact> facts, List<Section> sections) {
      for (DocumentEntity entity : entities) {
        this.entities.put(entity.id(), entity);
      }
      for (Fact fact : facts) {
        factIds.add(fact.id());
      }
      this.sections = sections;
    }

    void check() {
      for (DocumentEntity entity : entities.values()) {
        checkParents(entity);
        content(entity.description());
        facts(entity.facts());
      }
      sections.forEach(this::section);
      for (CrossReference.Target target : targets) {
        boolean found =
            switch (target.kind()) {
              case SECTION -> sectionIds.contains(target.id());
              case BLOCK -> blockIds.contains(target.id());
              case ENTITY -> entities.containsKey(EntityId.parse(target.id()));
            };
        if (!found) {
          throw new IllegalArgumentException(
              "Cross-reference to "
                  + target.kind().jsonName()
                  + " "
                  + target.id()
                  + ", which is not in the document");
        }
      }
    }

    private void checkParents(DocumentEntity entity) {
      Set<EntityId> seen = new HashSet<>();
      for (DocumentEntity at = entity; at.parent() != null; at = entities.get(at.parent())) {
        entity(at.parent());
        if (!seen.add(at.id())) {
          throw new IllegalArgumentException("Entity parents form a cycle at " + at.id());
        }
      }
    }

    private void section(Section section) {
      elementId(section.id());
      sectionIds.add(section.id());
      entity(section.entity());
      facts(section.facts());
      section.blocks().forEach(this::block);
      section.sections().forEach(this::section);
    }

    private void block(Block block) {
      elementId(block.id());
      blockIds.add(block.id());
      facts(block.facts());
      switch (block) {
        case Paragraph p -> content(p.content());
        case ListBlock l ->
            l.items()
                .forEach(
                    item -> {
                      content(item.content());
                      facts(item.facts());
                    });
        case Callout c -> content(c.content());
        case Table t ->
            t.rows()
                .forEach(
                    row -> {
                      entity(row.entity());
                      row.cells().forEach(this::content);
                      facts(row.facts());
                    });
        case Diagram d -> {
          for (Diagram.Node node : d.nodes()) {
            entity(node.id());
            facts(node.facts());
          }
          d.edges().forEach(edge -> facts(edge.facts()));
        }
        case SequenceDiagram d -> {
          for (SequenceDiagram.Participant participant : d.participants()) {
            entity(participant.id());
            facts(participant.facts());
          }
          d.steps().forEach(this::step);
        }
        case Tree t -> t.roots().forEach(this::treeNode);
        case Metrics m ->
            m.items()
                .forEach(
                    item -> {
                      entity(item.entity());
                      facts(item.facts());
                    });
        case QualityReport q ->
            q.failures()
                .forEach(
                    failure -> {
                      if (failure.target() != null) {
                        targets.add(failure.target());
                      }
                      content(failure.detail());
                      facts(failure.facts());
                    });
      }
    }

    private void step(SequenceDiagram.Step step) {
      facts(step.facts());
      switch (step) {
        case SequenceDiagram.Message m -> content(m.info());
        case SequenceDiagram.Note n -> content(n.content());
        case SequenceDiagram.Frame f -> f.operands().forEach(o -> o.steps().forEach(this::step));
      }
    }

    private void treeNode(Tree.Node node) {
      entity(node.entity());
      facts(node.facts());
      node.children().forEach(this::treeNode);
    }

    private void content(List<Inline> content) {
      for (Inline inline : content) {
        switch (inline) {
          case TextRun t -> {}
          case CrossReference r -> targets.add(r.target());
          case Citation c -> facts(c.facts());
        }
      }
    }

    private void elementId(String id) {
      if (sectionIds.contains(id) || blockIds.contains(id)) {
        throw new IllegalArgumentException("Duplicate section or block ID " + id);
      }
    }

    private void entity(EntityId id) {
      if (id != null && !entities.containsKey(id)) {
        throw new IllegalArgumentException("Entity " + id + " is not in the document's entities");
      }
    }

    private void facts(List<String> ids) {
      for (String id : ids) {
        if (!factIds.contains(id)) {
          throw new IllegalArgumentException("Fact " + id + " is not in the document's facts");
        }
      }
    }
  }
}
