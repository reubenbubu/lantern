package io.lantern.compose;

import io.lantern.core.document.TemplateRef;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A persisted spec of one document: what it covers, who it is for, the template it is rendered with
 * and which sections it has, in order. Composing the same view definition on a new snapshot gives
 * the same structure, whatever the knowledge says.
 *
 * @param id the view ID, which is also the ID of the documents composed from it
 * @param title the document title
 * @param scope what the document covers
 * @param audience the audience profile name, or {@code null}
 * @param template the template the document is rendered with
 * @param sections the sections, in reading order, at least one and each at most once
 */
public record ViewDefinition(
    String id,
    String title,
    Scope scope,
    String audience,
    TemplateRef template,
    List<SectionKind> sections) {

  /** The form of document IDs (and section and block IDs) the document model allows. */
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

  public ViewDefinition {
    Objects.requireNonNull(id, "id");
    if (!ID.matcher(id).matches()) {
      throw new IllegalArgumentException("Invalid view ID: '" + id + "'");
    }
    Objects.requireNonNull(title, "title");
    if (title.isBlank()) {
      throw new IllegalArgumentException("View title must not be blank");
    }
    Objects.requireNonNull(scope, "scope");
    if (audience != null && audience.isBlank()) {
      throw new IllegalArgumentException("Audience must be null or not blank");
    }
    Objects.requireNonNull(template, "template");
    sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
    if (sections.isEmpty()) {
      throw new IllegalArgumentException("A view must have at least one section");
    }
    Set<SectionKind> seen = EnumSet.noneOf(SectionKind.class);
    for (SectionKind section : sections) {
      if (!seen.add(section)) {
        throw new IllegalArgumentException("Section " + section.jsonName() + " is listed twice");
      }
    }
  }

  /**
   * A view of one repository with the sections of the Architecture Explorer, in the order the
   * Quality bar asks: the overview as its orientation, where to start reading, the containers (the
   * first diagram), context, components, main flows, the catalog, change guidance, then the
   * dependencies and structure (ADR 0048).
   */
  public static ViewDefinition perRepo(String id, String repo, TemplateRef template) {
    return new ViewDefinition(
        id,
        repo,
        Scope.repo(repo),
        null,
        template,
        List.of(
            SectionKind.OVERVIEW,
            SectionKind.START_HERE,
            SectionKind.CONTAINERS,
            SectionKind.CONTEXT,
            SectionKind.COMPONENTS,
            SectionKind.SEQUENCES,
            SectionKind.CATALOG,
            SectionKind.CHANGE_GUIDANCE,
            SectionKind.DEPENDENCIES,
            SectionKind.STRUCTURE));
  }

  /**
   * A view of one repository for a C4 report: overview, where to start reading, context,
   * containers, components and main flows, the sections whose diagrams the C4 report spec draws
   * (ADR 0031), the first two in its introduction.
   */
  public static ViewDefinition c4Report(String id, String repo, TemplateRef template) {
    return new ViewDefinition(
        id,
        repo,
        Scope.repo(repo),
        null,
        template,
        List.of(
            SectionKind.OVERVIEW,
            SectionKind.START_HERE,
            SectionKind.CONTEXT,
            SectionKind.CONTAINERS,
            SectionKind.COMPONENTS,
            SectionKind.SEQUENCES));
  }

  /**
   * A view of the whole system for a C4 report: overview, where to start reading, context,
   * containers, components and main flows (ADR 0031).
   */
  public static ViewDefinition c4System(String id, String title, TemplateRef template) {
    return new ViewDefinition(
        id,
        title,
        Scope.system(),
        null,
        template,
        List.of(
            SectionKind.OVERVIEW,
            SectionKind.START_HERE,
            SectionKind.CONTEXT,
            SectionKind.CONTAINERS,
            SectionKind.COMPONENTS,
            SectionKind.SEQUENCES));
  }

  /**
   * A view of one repository with the sections of the Executive Brief: overview, headline numbers,
   * risks, blast radius, technology currency, and the context and container diagrams (ADR 0015, ADR
   * 0052).
   */
  public static ViewDefinition executiveBrief(String id, String repo, TemplateRef template) {
    return new ViewDefinition(
        id,
        repo,
        Scope.repo(repo),
        null,
        template,
        List.of(
            SectionKind.OVERVIEW,
            SectionKind.HEADLINES,
            SectionKind.RISKS,
            SectionKind.BLAST_RADIUS,
            SectionKind.TECHNOLOGY_CURRENCY,
            SectionKind.CONTEXT,
            SectionKind.CONTAINERS));
  }
}
