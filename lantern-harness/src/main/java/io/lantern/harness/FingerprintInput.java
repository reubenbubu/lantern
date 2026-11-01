package io.lantern.harness;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import java.util.Comparator;
import java.util.Objects;

/**
 * One input an item's answer may rest on (knowledge-build epic, section 10, "What a fingerprint
 * covers"): a claim shown, a file's text shown (by its blob), an earlier answer shown, or another
 * text. Each says which file it comes from, when it comes from one, so a run can tell which items a
 * changed file touches.
 *
 * @param kind what it is
 * @param id its stable identity: a claim ID, a git blob, an answer's observation hash or a text's
 *     SHA-256
 * @param repo the repository of the file it comes from, or empty
 * @param path the file's path in the repository, or empty
 */
public record FingerprintInput(Kind kind, String id, String repo, String path)
    implements Comparable<FingerprintInput> {

  /** The kinds of input. */
  public enum Kind {
    /**
     * A claim shown (ADR 0091), by its claim ID, which stays the same across commits and extractor
     * versions; what the prompt shows of its sighting is in the item's rendering.
     */
    CLAIM,
    /** A file's text, by its git blob. */
    BLOB,
    /**
     * An earlier accepted answer shown, by the hash of the text shown, such as a contained unit's
     * summary: re-asked to the same text, it leaves the items that show it as they were.
     */
    ANSWER,
    /** A text from nowhere else, by its SHA-256. */
    TEXT
  }

  private static final Comparator<FingerprintInput> ORDER =
      Comparator.comparing(FingerprintInput::kind)
          .thenComparing(FingerprintInput::id)
          .thenComparing(FingerprintInput::repo)
          .thenComparing(FingerprintInput::path);

  public FingerprintInput {
    Objects.requireNonNull(kind, "kind");
    if (id == null || id.isBlank() || id.contains("\n")) {
      throw new IllegalArgumentException("Input id must be one non-blank line: " + id);
    }
    repo = repo == null ? "" : repo;
    path = path == null ? "" : path;
  }

  /**
   * The claim a fact states, from the file of its evidence. A fact not yet stored has no claim ID;
   * it stands for its claim by its subject, predicate and object.
   */
  public static FingerprintInput of(Fact fact) {
    Evidence evidence = fact.evidence();
    String claim =
        fact.claimId() != null
            ? fact.claimId()
            : Fingerprints.sha256(
                fact.subject() + "\n" + fact.predicate() + "\n" + fact.object().value());
    return new FingerprintInput(
        Kind.CLAIM,
        claim,
        evidence == null ? "" : evidence.repo(),
        evidence == null || evidence.path() == null ? "" : evidence.path());
  }

  /** The text of the file of a fact's evidence, by its blob, or empty if it has none. */
  public static java.util.Optional<FingerprintInput> fileOf(Fact fact) {
    Evidence evidence = fact.evidence();
    if (evidence == null || evidence.path() == null || evidence.blob() == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(blob(evidence.repo(), evidence.path(), evidence.blob()));
  }

  /** A file's text as shown, by its blob. */
  public static FingerprintInput blob(String repo, String path, String blob) {
    return new FingerprintInput(Kind.BLOB, blob, repo, path);
  }

  /** An earlier answer shown, by the hash of the text shown. */
  public static FingerprintInput answer(String hash) {
    return new FingerprintInput(Kind.ANSWER, hash, "", "");
  }

  /** A text that comes from no file or answer, by its hash. */
  public static FingerprintInput text(String text) {
    return new FingerprintInput(Kind.TEXT, Fingerprints.sha256(text), "", "");
  }

  /** Whether it comes from the given file. */
  public boolean inFile(String repo, String path) {
    return this.repo.equals(repo) && this.path.equals(path);
  }

  /** Its canonical line in a fingerprint. */
  String line() {
    return kind.name().toLowerCase(java.util.Locale.ROOT) + ' ' + id + ' ' + repo + ':' + path;
  }

  @Override
  public int compareTo(FingerprintInput other) {
    return ORDER.compare(this, other);
  }
}
