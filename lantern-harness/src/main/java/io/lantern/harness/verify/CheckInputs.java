package io.lantern.harness.verify;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * What the checks of one item compare its answer with.
 *
 * @param schema the item's answer schema (the role's first branch; omitted is always allowed)
 * @param references the text of every reference the item's request or tool results showed, by its
 *     local name, such as {@code F1}
 * @param citable the reference letters the role may cite, such as {@code F} and {@code E}; {@code
 *     repo:path} ranges are citable when {@code ranges} is set
 * @param ranges whether the role may cite file ranges it read ({@code repo:path:from-to})
 * @param files the lines of a file of the snapshot, at its blob, or empty if not of the snapshot
 * @param staticClaims the objects static claims give each subject and predicate, joined by {@code "
 *     "}, for the Conflict check
 * @param secrets values masking marked as secret
 */
public record CheckInputs(
    JsonNode schema,
    Map<String, String> references,
    Set<Character> citable,
    boolean ranges,
    Files files,
    Map<String, Set<String>> staticClaims,
    Set<String> secrets) {

  /** The lines of the snapshot's files. */
  @FunctionalInterface
  public interface Files {
    Optional<List<String>> lines(String repo, String path);
  }

  public CheckInputs {
    Objects.requireNonNull(schema, "schema");
    references = Map.copyOf(references);
    citable = Set.copyOf(citable);
    Objects.requireNonNull(files, "files");
    staticClaims = Map.copyOf(staticClaims);
    secrets = Set.copyOf(secrets);
  }

  /** These inputs with more secret values, such as the run's. */
  public CheckInputs withSecrets(java.util.Collection<String> more) {
    Set<String> all = new java.util.LinkedHashSet<>(secrets);
    all.addAll(more);
    return new CheckInputs(schema, references, citable, ranges, files, staticClaims, all);
  }

  /** The key of a subject and predicate in {@link #staticClaims}. */
  public static String claimKey(String subject, String predicate) {
    return subject + " " + predicate;
  }
}
