package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.Elements;
import io.lantern.derive.Findings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The findings stored for the risks and findings page besides Investigate's (knowledge-build epic,
 * sections 11.7 and 11.8): text the injection screen found addressed to an AI (ADR 0101), and the
 * merges of two groups Name groups suggested and Lantern never applies (ADR 0107). A system's
 * documents show all of them; a repository's the injections in its files and the suggestions that
 * name a feature area of one of its units.
 */
final class StoredFindings {

  /** The title of the table. */
  static final String TITLE = "Other findings";

  /** What a row for a merge suggestion says it is. */
  static final String MERGE = "Groups that may be one";

  /** What a row for an injection finding says it is. */
  static final String INJECTION = "Text addressed to an AI";

  private final Assembler assembler;
  private final List<Findings.Injection> injections;
  private final List<Findings.GroupMerge> merges;

  /**
   * @param repo the repository of the documents, or {@code null} for the system's
   * @param units the deployable units in scope
   */
  StoredFindings(ArchitectureModel model, Assembler assembler, String repo, Set<EntityId> units) {
    this.assembler = assembler;
    this.injections = repo == null ? Findings.injections(model) : Findings.injections(model, repo);
    this.merges =
        Findings.groupMerges(model).stream()
            .filter(m -> repo == null || inScope(m.first(), units) || inScope(m.second(), units))
            .toList();
  }

  /** Whether a group is a feature area of one of the units: {@code group:<unit>/area/...}. */
  private static boolean inScope(EntityId group, Set<EntityId> units) {
    int area = group.key().indexOf("/area/");
    return area > 0 && units.contains(EntityId.parse(group.key().substring(0, area)));
  }

  /** The table of the findings, with what each is, what it says and where; none when empty. */
  List<Block> table(String section) {
    List<Table.Row> rows = new ArrayList<>();
    for (Findings.Injection injection : injections) {
      Fact finding = injection.fact();
      rows.add(
          row(
              INJECTION,
              List.of(
                  TextRun.of(
                      "Lines "
                          + injection.lines()
                          + " read as addressed to an AI model: “"
                          + injection.quote()
                          + "”. A person should check what they ask for and why.")),
              List.of(new TextRun(finding.evidence().path(), TextRun.Style.CODE)),
              List.of(finding)));
    }
    for (Findings.GroupMerge merge : merges) {
      List<Fact> facts = new ArrayList<>(List.of(merge.fact()));
      rows.add(
          row(
              MERGE,
              List.of(
                  new TextRun(Elements.FOUND_BY_LLM, TextRun.Style.EMPHASIS),
                  TextRun.of(
                      " "
                          + assembler.name(merge.first())
                          + " and "
                          + assembler.name(merge.second())
                          + " may be one area: "
                          + merge.reason()
                          + " Lantern keeps them apart.")),
              List.of(TextRun.of(assembler.name(merge.first()))),
              facts));
    }
    if (rows.isEmpty()) {
      return List.of();
    }
    return List.of(
        new Table(
            assembler.id(section, "stored"),
            TITLE,
            List.of(
                new Table.Column("Kind"), new Table.Column("Finding"), new Table.Column("Where")),
            rows,
            List.of()));
  }

  private Table.Row row(String kind, List<Inline> finding, List<Inline> where, List<Fact> facts) {
    return new Table.Row(
        null, List.of(List.of(TextRun.of(kind)), finding, where), assembler.facts(facts));
  }
}
