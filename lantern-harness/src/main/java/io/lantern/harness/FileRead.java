package io.lantern.harness;

/**
 * A file a tool read for an item, at the blob it read; a cached answer is reused only while every
 * file it read still has that blob (knowledge-build epic, section 10).
 */
public record FileRead(String repo, String path, String blob) {
  public FileRead {
    Text.nonBlank("Repository", repo);
    Text.nonBlank("Path", path);
    Text.nonBlank("Blob", blob);
  }
}
