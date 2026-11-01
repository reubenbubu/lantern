package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The links a protocol rule's matchers find in the view they link in, turned into facts. Each link
 * carries the IDs of the facts it comes from, on both ends, and an identity that is the same in
 * every view and snapshot, by which the linker merges the views into one claim per view (ADR 0095,
 * amendment for #590).
 */
final class FoundLinks {

  /**
   * A link found in one view.
   *
   * @param identity what makes it the same link in every view and snapshot: its ends and the code
   *     on both, never a commit
   * @param attributes its attributes, without {@code view}, {@code absentIn} and {@code facts}
   * @param evidence where it is seen: the fact on its subject's side
   * @param facts the IDs of the facts it comes from
   */
  record Found(
      String identity,
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      Evidence evidence,
      double confidence,
      Collection<String> facts) {}

  /** The links by identity, then by view ("" for the default view), in the order found. */
  private final Map<String, Map<String, Found>> found = new LinkedHashMap<>();

  /** Facts that are not links (attributes of entities the linker names), by ID. */
  private final Map<String, Fact> plain = new LinkedHashMap<>();

  /** The group each link was first found in, by identity ({@link #add(String, Found, String)}). */
  private final Map<String, String> groups = new java.util.HashMap<>();

  /**
   * Adds a link found in a view, in a group a rule describes its links by once the views are merged
   * ({@link LinkRule#merged}): the first group a link is found in counts.
   */
  void add(String view, Found link, String group) {
    groups.putIfAbsent(link.identity(), group);
    add(view, link);
  }

  /** Adds a link found in a view (null for the default view); the first one found counts. */
  void add(String view, Found link) {
    Map<String, Found> views = found.computeIfAbsent(link.identity(), i -> new TreeMap<>());
    Found earlier = views.get(view == null ? "" : view);
    if (earlier == null) {
      views.put(view == null ? "" : view, link);
    } else {
      Set<String> facts = new TreeSet<>(earlier.facts());
      facts.addAll(link.facts());
      views.put(
          view == null ? "" : view,
          new Found(
              earlier.identity(),
              earlier.subject(),
              earlier.predicate(),
              earlier.object(),
              earlier.attributes(),
              earlier.evidence(),
              Math.max(earlier.confidence(), link.confidence()),
              facts));
    }
  }

  /** The links found so far in a view (null for the default view), in the order found. */
  List<Found> found(String view) {
    List<Found> result = new ArrayList<>();
    for (Map<String, Found> views : found.values()) {
      Found f = views.get(view == null ? "" : view);
      if (f != null) {
        result.add(f);
      }
    }
    return result;
  }

  /** Whether a link of the predicate was found, in any view. */
  boolean has(String predicate) {
    return found.values().stream()
        .flatMap(views -> views.values().stream())
        .anyMatch(f -> f.predicate().equals(predicate));
  }

  /** Adds a fact that holds whatever the view. */
  void add(Fact fact) {
    plain.putIfAbsent(fact.id(), fact);
  }

  /**
   * Gives what was found in a view to a rule's sink: each link as found in it, with its identity as
   * the link that is the same in every view, for the linker to merge into one claim per view (ADR
   * 0095, amendment for #590); then the facts that hold whatever the view.
   *
   * @throws IllegalStateException if a link was found in another view: the matchers link in the
   *     views the index is narrowed to
   */
  void to(ClaimSink out, View view, Origin origin, Validity validity) {
    to(out, view, origin, validity, predicate -> true);
  }

  /**
   * As {@link #to(ClaimSink, View, Origin, Validity)}, giving only the links and facts of the
   * predicates a rule keeps: the protocol rules all but the links they leave open, the open-links
   * rule only those (#583). What is not given still counts while matching.
   */
  void to(
      ClaimSink out,
      View view,
      Origin origin,
      Validity validity,
      java.util.function.Predicate<String> kept) {
    String name = view.isDefault() ? "" : view.name();
    for (Map<String, Found> views : found.values()) {
      for (Map.Entry<String, Found> e : views.entrySet()) {
        if (!e.getKey().equals(name)) {
          throw new IllegalStateException(
              "A link was found in view " + e.getKey() + " while linking in view " + view.name());
        }
        Found f = e.getValue();
        if (!kept.test(f.predicate())) {
          continue;
        }
        out.add(
            fact(f, new TreeSet<>(f.facts()), origin, validity),
            f.identity(),
            groups.get(f.identity()));
      }
    }
    plain.values().stream().filter(f -> kept.test(f.predicate())).forEach(out::add);
  }

  private static Fact fact(Found found, Set<String> ids, Origin origin, Validity validity) {
    ObjectNode attributes = found.attributes().deepCopy();
    ArrayNode facts = attributes.putArray("facts");
    ids.forEach(facts::add);
    return Fact.create(
        found.subject(),
        found.predicate(),
        found.object(),
        attributes,
        origin,
        found.evidence(),
        found.confidence(),
        validity);
  }
}
