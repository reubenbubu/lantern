package io.lantern.harness;

import java.util.List;
import java.util.Objects;

/**
 * An accepted answer to one item, as a role's {@link Asker} returns it.
 *
 * @param json the answer as the role stores it, or {@code null} for an omitted item
 * @param filesRead the files the item's tools read, with their blobs
 */
public record Answer(String json, List<FileRead> filesRead) {

  public Answer {
    filesRead = List.copyOf(Objects.requireNonNull(filesRead, "filesRead"));
  }

  /** An answer without tools. */
  public static Answer of(String json) {
    return new Answer(Objects.requireNonNull(json, "json"), List.of());
  }

  /** The answer that the item's inputs show nothing for it. */
  public static Answer omitted() {
    return new Answer(null, List.of());
  }
}
