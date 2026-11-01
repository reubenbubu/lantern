package io.lantern.harness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Packs a role's items into packets (knowledge-build epic, section 10): items sorted by locality,
 * then by fingerprint, filled into packets of at most the role's size, a new packet starting where
 * the repository changes. Deterministic, so the same items always make the same packets.
 */
final class Packer {

  private Packer() {}

  static List<List<Item>> pack(List<Item> items, int size, Function<Item, String> fingerprint) {
    List<Item> sorted = new ArrayList<>(items);
    sorted.sort(
        Comparator.comparing(Item::locality).thenComparing(item -> fingerprint.apply(item)));
    List<List<Item>> packets = new ArrayList<>();
    List<Item> packet = new ArrayList<>();
    String repo = null;
    for (Item item : sorted) {
      String itemRepo = repo(item.locality());
      if (!packet.isEmpty() && (packet.size() >= size || !itemRepo.equals(repo))) {
        packets.add(packet);
        packet = new ArrayList<>();
      }
      packet.add(item);
      repo = itemRepo;
    }
    if (!packet.isEmpty()) {
      packets.add(packet);
    }
    return packets;
  }

  /** The repository part of a locality ({@code repo/unit}). */
  static String repo(String locality) {
    int slash = locality.indexOf('/');
    return slash < 0 ? locality : locality.substring(0, slash);
  }
}
