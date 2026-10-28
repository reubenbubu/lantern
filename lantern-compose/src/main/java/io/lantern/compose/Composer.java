package io.lantern.compose;

import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentSource;
import io.lantern.core.document.Section;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.summarize.Summary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides what a document says: applies a view definition to the knowledge of one snapshot and
 * builds the document model, every element linked to the facts it came from.
 *
 * <p>Composing is deterministic: facts and entities are visited in ID order and nothing random or
 * time-dependent is used, so the same view, snapshot, facts and summaries always give the same
 * document. The composer makes no LLM calls; narrative comes from the summaries it is given.
 */
public final class Composer {

  private final String toolVersion;

  /**
   * @param toolVersion the Lantern version, recorded in each document's source
   */
  public Composer(String toolVersion) {
    this.toolVersion = Objects.requireNonNull(toolVersion, "toolVersion");
    if (toolVersion.isBlank()) {
      throw new IllegalArgumentException("Tool version must not be blank");
    }
  }

  /**
   * Composes the document of a view.
   *
   * @param view the view definition
   * @param snapshot the snapshot the document is of
   * @param facts the facts; those that do not hold in the snapshot are ignored
   * @param summaries the summaries of the snapshot's units, by entity, as {@link
   *     io.lantern.summarize.Summarizer} returns them; units without a summary get no narrative
   * @throws IllegalArgumentException if the view's repository is not in the snapshot, or a summary
   *     cites a fact that does not hold in it
   */
  public Document compose(
      ViewDefinition view,
      Snapshot snapshot,
      Collection<Fact> facts,
      Map<EntityId, Summary> summaries) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(summaries, "summaries");
    String repo = view.scope().repo();
    if (!snapshot.commits().containsKey(repo)) {
      throw new IllegalArgumentException(
          "Repository " + repo + " is not in snapshot " + snapshot.number());
    }
    List<Fact> valid =
        facts.stream().filter(f -> f.validity().contains(snapshot.number())).toList();
    RepoSections composition = new RepoSections(new Knowledge(valid), summaries, repo);
    List<Section> sections = new ArrayList<>();
    for (SectionKind kind : view.sections()) {
      sections.add(composition.section(kind));
    }
    return new Document(
        view.id(),
        view.title(),
        view.audience(),
        view.template(),
        new DocumentSource(snapshot.number(), snapshot.vocabularyRevision(), toolVersion),
        composition.entities(),
        composition.facts(),
        sections);
  }
}
