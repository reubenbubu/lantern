package io.lantern.core.document;

import java.util.List;

/**
 * The Quality bar checks a document failed ({@code docs/design.md}, Quality bar). No failures means
 * every check the composer makes passed.
 *
 * @param id the block ID
 * @param title the title, or {@code null}
 * @param failures the failed checks
 * @param facts the IDs of the facts the report as a whole came from
 */
public record QualityReport(String id, String title, List<Failure> failures, List<String> facts)
    implements Block {

  public QualityReport {
    Checks.elementId(id, "block ID");
    Checks.optionalText(title, "quality report title");
    failures = Checks.list(failures, "failures");
    facts = Checks.factIds(facts, "quality report facts");
  }

  /**
   * One failed check. The document checks that the target exists.
   *
   * @param check the check that failed, as the Quality bar words it
   * @param target the section, block or entity that failed it, or {@code null} for the document as
   *     a whole
   * @param detail how it failed, or empty
   * @param facts the IDs of the facts the failure came from
   */
  public record Failure(
      String check, CrossReference.Target target, List<Inline> detail, List<String> facts) {

    public Failure {
      Checks.text(check, "check");
      detail = Checks.list(detail, "failure detail");
      facts = Checks.factIds(facts, "failure facts");
    }
  }
}
