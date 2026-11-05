package io.lantern.harness;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * Why the harness asked an item rather than reuse a stored answer (#755): what changed since the
 * answer it stored last for the same role and subject. The run's record lists it for every item
 * asked, so a one-file change can be checked against the gate of the knowledge-build epic, section
 * 13: an item is re-asked only for inputs from the changed file, or for an earlier answer it shows
 * that was worded differently.
 *
 * @param isNew no answer was stored for the role and subject before
 * @param unheld the answer stored under the item's fingerprint was refused: a fact it cites no
 *     longer holds
 * @param files the files, as {@code repo:path}, of the claims and file texts that changed, and of
 *     the files a tool-using item read that changed since
 * @param answers how many earlier answers the item shows changed
 * @param changedAnswers the items the changed answers it shows come from, each "{@code <role>
 *     <subject>}" or a role alone, as the answer names them ({@link FingerprintInput#from}); empty
 *     when none changed; one that names none counts as {@value #UNKNOWN_SOURCE}
 * @param other how many other inputs changed: texts from no file
 * @param prompt the role's prompt version changed
 * @param model the role's provider or model changed
 */
public record AskedWhy(
    boolean isNew,
    boolean unheld,
    SortedSet<String> files,
    int answers,
    SortedSet<String> changedAnswers,
    int other,
    boolean prompt,
    boolean model) {

  public AskedWhy {
    files = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(files));
    changedAnswers = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(changedAnswers));
  }

  /** The source of a changed answer that names none. */
  public static final String UNKNOWN_SOURCE = "?";

  /** An item never asked before. */
  public static final AskedWhy NEW =
      new AskedWhy(true, false, new TreeSet<>(), 0, new TreeSet<>(), 0, false, false);

  /** An item whose stored answer, its inputs unchanged, cites a fact that no longer holds. */
  public static final AskedWhy UNHELD =
      new AskedWhy(false, true, new TreeSet<>(), 0, new TreeSet<>(), 0, false, false);

  /**
   * Why the item is asked, against the answer stored last for its subject.
   *
   * @param previous the answer stored last for the role and subject, if any
   * @param inputs the item's inputs now
   * @param promptVersion the role's prompt version now
   * @param provider the role's provider now
   * @param modelName the role's model now
   * @param blobNow the blob of a file now, by repository and path, if it exists
   */
  public static AskedWhy of(
      Optional<StoredAnswer> previous,
      List<FingerprintInput> inputs,
      String promptVersion,
      String provider,
      String modelName,
      BiFunction<String, String, Optional<String>> blobNow) {
    if (previous.isEmpty()) {
      return NEW;
    }
    StoredAnswer was = previous.get();
    Set<FingerprintInput> before = new HashSet<>(was.inputs());
    Set<FingerprintInput> now = new HashSet<>(inputs);
    Set<FingerprintInput> changed = new HashSet<>(before);
    changed.addAll(now);
    Set<FingerprintInput> same = new HashSet<>(before);
    same.retainAll(now);
    changed.removeAll(same);
    SortedSet<String> files = new TreeSet<>();
    int answers = 0;
    SortedSet<String> changedAnswers = new TreeSet<>();
    // Where each answer shown now that was not shown before comes from (#722).
    for (FingerprintInput input : inputs) {
      if (input.kind() == FingerprintInput.Kind.ANSWER && !before.contains(input)) {
        if (input.from().isEmpty()) {
          changedAnswers.add(UNKNOWN_SOURCE);
        }
        changedAnswers.addAll(input.from());
      }
    }
    int other = 0;
    for (FingerprintInput input : changed) {
      if (input.kind() == FingerprintInput.Kind.ANSWER) {
        answers++;
      } else if (!input.path().isEmpty()) {
        files.add(input.repo() + ":" + input.path());
      } else {
        other++;
      }
    }
    for (FileRead read : was.filesRead()) {
      Optional<String> blob = blobNow.apply(read.repo(), read.path());
      if (blob.isEmpty() || !blob.get().equals(read.blob())) {
        files.add(read.repo() + ":" + read.path());
      }
    }
    return new AskedWhy(
        false,
        false,
        files,
        answers,
        changedAnswers,
        other,
        !promptVersion.equals(was.promptVersion()),
        !provider.equals(was.provider()) || !modelName.equals(was.model()));
  }
}
