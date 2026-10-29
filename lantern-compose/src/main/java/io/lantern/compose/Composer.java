package io.lantern.compose;

import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentSource;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.summarize.Summary;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides what a document says: applies a view definition to the knowledge of one snapshot and
 * builds the document model, every element linked to the facts it came from.
 *
 * <p>Composing is deterministic: facts and entities are visited in ID order and nothing random or
 * time-dependent is used (technology currency is judged as of the snapshot's date), so the same
 * view, snapshot, facts, summaries and wordings always give the same document. The composer makes
 * no LLM calls; narrative comes from the summaries and wordings it is given.
 */
public final class Composer {

  /** The ID of the last section, the quality report, unless a section already has it. */
  static final String QUALITY = "quality";

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
   *     cites a fact that does not hold in it; a system view covers every repository of the
   *     snapshot
   */
  public Document compose(
      ViewDefinition view,
      Snapshot snapshot,
      Collection<Fact> facts,
      Map<EntityId, Summary> summaries) {
    return compose(view, snapshot, facts, summaries, Wordings.NONE);
  }

  /**
   * Composes the document of a view, describing deployable units and components by their
   * responsibility sentences and labelling lines and messages with their intent phrases where the
   * wordings have them (ADR 0050); elsewhere it is as {@link #compose(ViewDefinition, Snapshot,
   * Collection, Map)}. Citations of facts that do not hold in the snapshot are left out.
   *
   * @param wordings what {@link Describer} worded
   */
  public Document compose(
      ViewDefinition view,
      Snapshot snapshot,
      Collection<Fact> facts,
      Map<EntityId, Summary> summaries,
      Wordings wordings) {
    return compose(view, snapshot, facts, summaries, wordings, null);
  }

  /**
   * Composes the document of a view as {@link #compose(ViewDefinition, Snapshot, Collection, Map,
   * Wordings)} does, naming the software system its containers belong to (ADR 0071).
   *
   * @param systemName the system's title, or {@code null} to name it after the snapshot's
   *     repositories (ADR 0044)
   */
  public Document compose(
      ViewDefinition view,
      Snapshot snapshot,
      Collection<Fact> facts,
      Map<EntityId, Summary> summaries,
      Wordings wordings,
      String systemName) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(wordings, "wordings");
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(summaries, "summaries");
    String repo = view.scope().repo();
    if (repo != null && !snapshot.commits().containsKey(repo)) {
      throw new IllegalArgumentException(
          "Repository " + repo + " is not in snapshot " + snapshot.number());
    }
    List<Fact> valid =
        facts.stream().filter(f -> f.validity().contains(snapshot.number())).toList();
    RepoSections composition =
        new RepoSections(
            new Knowledge(valid),
            summaries,
            wordings,
            repo,
            new TreeSet<>(snapshot.commits().keySet()),
            systemName,
            LocalDate.ofInstant(snapshot.createdAt(), ZoneOffset.UTC));
    List<Section> sections = new ArrayList<>();
    int glossary = -1;
    for (SectionKind kind : view.sections()) {
      if (kind == SectionKind.GLOSSARY) {
        glossary = sections.size();
      } else {
        sections.add(composition.section(kind));
      }
    }
    List<QualityReport.Failure> claimFailures = composition.claimFailures();
    DocumentSource source =
        new DocumentSource(
            snapshot.number(),
            snapshot.vocabularyRevision(),
            toolVersion,
            repo == null ? snapshot.commits() : Map.of(repo, snapshot.commits().get(repo)),
            LocalDate.ofInstant(snapshot.createdAt(), ZoneOffset.UTC));
    Document composed = document(view, source, composition, sections);
    // The glossary explains what the other sections use, so it is built from them (ADR 0063).
    if (glossary >= 0) {
      sections.add(glossary, composition.glossary(composed));
      composed = document(view, source, composition, sections);
    }
    sections.add(qualitySection(composed, claimFailures));
    return document(view, source, composition, sections);
  }

  private static Document document(
      ViewDefinition view,
      DocumentSource source,
      RepoSections composition,
      List<Section> sections) {
    return new Document(
        view.id(),
        view.title(),
        view.audience(),
        view.template(),
        source,
        composition.entities(),
        composition.facts(),
        sections);
  }

  /**
   * The last section: the Quality bar checks the document fails (ADR 0028, ADR 0046), an empty
   * report when it passes every check the composer makes; then the README claims the code
   * contradicts (ADR 0051).
   */
  private static Section qualitySection(Document document, List<QualityReport.Failure> claims) {
    Set<String> ids = new HashSet<>();
    collectIds(document.sections(), ids);
    String id = QUALITY;
    for (int n = 2; ids.contains(id) || ids.contains(id + ".report"); n++) {
      id = QUALITY + "-" + n;
    }
    List<QualityReport.Failure> failures = new ArrayList<>(QualityBar.failures(document));
    failures.addAll(claims);
    QualityReport report = new QualityReport(id + ".report", null, failures, List.of());
    return new Section(id, "Quality report", Set.of(), null, List.of(), List.of(report), List.of());
  }

  private static void collectIds(List<Section> sections, Set<String> ids) {
    for (Section section : sections) {
      ids.add(section.id());
      section.blocks().forEach(block -> ids.add(block.id()));
      collectIds(section.sections(), ids);
    }
  }
}
