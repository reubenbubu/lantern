package io.lantern.harness;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * One question about one subject (knowledge-build epic, section 10, Items): verified and cached on
 * its own, so a changed input re-asks only its own item.
 *
 * @param subject what it is about, unique among a role's items in a run, such as an entity ID
 * @param locality where its subject sits, such as {@code repo/unit}; packets group items by it
 * @param inputs what its answer may rest on, each once, in canonical order
 * @param rendering the canonical text the item's request shows of it: for a role that asks one item
 *     a request, its whole prompt; for a packed role, its own entry and the packet context it needs
 */
public record Item(
    String subject, String locality, List<FingerprintInput> inputs, String rendering) {

  public Item {
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("Subject must not be blank");
    }
    locality = locality == null ? "" : locality;
    inputs = List.copyOf(new TreeSet<>(Objects.requireNonNull(inputs, "inputs")));
    Objects.requireNonNull(rendering, "rendering");
  }

  /** Whether any of its inputs comes from the given file. */
  public boolean readsFile(String repo, String path) {
    return inputs.stream().anyMatch(input -> input.inFile(repo, path));
  }
}
