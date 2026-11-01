package io.lantern.harness;

import java.util.Optional;

/** The git blob a file has in the snapshot being analyzed, to revalidate cached answers. */
@FunctionalInterface
public interface BlobLookup {

  /** No file can be looked up, so no answer that read files is reused. */
  BlobLookup NONE = (repo, path) -> Optional.empty();

  Optional<String> blob(String repo, String path);
}
