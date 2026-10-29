package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The links the linker finds in each view, turned into facts: a link found in the default view
 * holds whatever the profile, and one found only under profiles holds only under each of them,
 * which its {@code profile} attribute names. Each link carries the IDs of the facts it comes from,
 * on both ends and in every view, and a stable link ID that does not change with commits.
 */
final class FoundLinks {

  /**
   * A link found in one view.
   *
   * @param identity what makes it the same link in every view and snapshot: its ends and the code
   *     on both, never a commit
   * @param attributes its attributes, without {@code profile}, {@code facts} and {@code link}
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

  /** Adds a fact that holds whatever the view. */
  void add(Fact fact) {
    plain.putIfAbsent(fact.id(), fact);
  }

  /**
   * The facts: each link once if found in the default view, naming in {@code exceptProfiles} the
   * profiles under which it is not found; else once per profile it is found under.
   *
   * @param profiles every profile the links were looked for under
   */
  List<Fact> facts(List<String> profiles, Origin origin, Validity validity) {
    List<Fact> facts = new ArrayList<>(plain.values());
    for (Map<String, Found> views : found.values()) {
      Found standard = views.get("");
      if (standard != null) {
        Set<String> ids = new TreeSet<>();
        views.values().forEach(f -> ids.addAll(f.facts()));
        List<String> except =
            profiles.stream().filter(p -> !views.containsKey(p)).sorted().toList();
        Fact fact = fact(standard, null, ids, origin, validity);
        if (!except.isEmpty()) {
          ObjectNode attributes = standard.attributes().deepCopy();
          ArrayNode array = attributes.putArray("exceptProfiles");
          except.forEach(array::add);
          fact =
              fact(
                  new Found(
                      standard.identity(),
                      standard.subject(),
                      standard.predicate(),
                      standard.object(),
                      attributes,
                      standard.evidence(),
                      standard.confidence(),
                      standard.facts()),
                  null,
                  ids,
                  origin,
                  validity);
        }
        facts.add(fact);
      } else {
        views.forEach(
            (profile, f) ->
                facts.add(fact(f, profile, new TreeSet<>(f.facts()), origin, validity)));
      }
    }
    return facts;
  }

  private static Fact fact(
      Found found, String profile, Set<String> ids, Origin origin, Validity validity) {
    ObjectNode attributes = found.attributes().deepCopy();
    if (profile != null) {
      attributes.put("profile", profile);
    }
    attributes.put("link", linkId(found.identity(), profile));
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

  /** The first 16 hex digits of the SHA-256 of the identity and profile. */
  static String linkId(String identity, String profile) {
    String text = profile == null ? identity : identity + "|profile=" + profile;
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
