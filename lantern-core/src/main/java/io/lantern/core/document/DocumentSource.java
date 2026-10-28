package io.lantern.core.document;

/**
 * What a document was composed from, so it can be reproduced.
 *
 * @param snapshot the snapshot number
 * @param vocabularyRevision the vocabulary revision the snapshot used
 * @param toolVersion the Lantern version that composed the document
 */
public record DocumentSource(long snapshot, long vocabularyRevision, String toolVersion) {

  public DocumentSource {
    if (snapshot < 1) {
      throw new IllegalArgumentException("Snapshot number must be at least 1: " + snapshot);
    }
    if (vocabularyRevision < 0) {
      throw new IllegalArgumentException("Invalid vocabulary revision: " + vocabularyRevision);
    }
    Checks.text(toolVersion, "tool version");
  }
}
