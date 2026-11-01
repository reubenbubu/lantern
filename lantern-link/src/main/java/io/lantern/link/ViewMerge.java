package io.lantern.link;

import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Merges what a rule found in each view into one claim per view (knowledge-build epic, section 8,
 * Views; ADR 0095, amendment for #590): a claim found in the default view holds in every view but
 * those its {@code absentIn} lists, and cites the facts it came from in every view; one found only
 * in other views is one claim per view, its {@code view} a key attribute. Facts that hold whatever
 * the view are kept once each.
 */
final class ViewMerge {

  private ViewMerge() {}

  /**
   * Runs a rule as the linker does: once in each view if it derives per view, merging its views,
   * else once in the default view.
   *
   * @return the facts it gives
   */
  static List<Fact> derive(LinkRule rule, ClaimQuery query) {
    if (!rule.perView()) {
      ClaimSink sink = new ClaimSink(rule.info(), rule.writes());
      rule.derive(query, sink, View.DEFAULT);
      return sink.facts();
    }
    List<View> views = View.of(query.facts());
    List<ClaimSink> sinks = new ArrayList<>();
    for (View view : views) {
      ClaimSink sink = new ClaimSink(rule.info(), rule.writes());
      rule.derive(query, sink, view);
      sinks.add(sink);
    }
    return rule.merged(merge(views, sinks));
  }

  /**
   * @param views the views the rule derived in, the default view first
   * @param found what it found in each, in the order of {@code views}
   */
  static List<LinkRule.Merged> merge(List<View> views, List<ClaimSink> found) {
    if (views.size() != found.size() || !views.getFirst().isDefault()) {
      throw new IllegalArgumentException("The default view first, then a sink per view");
    }
    Map<String, Fact> plain = new LinkedHashMap<>();
    // By link, then by view index.
    Map<String, Map<Integer, Fact>> links = new LinkedHashMap<>();
    for (int i = 0; i < views.size(); i++) {
      ClaimSink sink = found.get(i);
      List<Fact> facts = sink.facts();
      List<String> ids = sink.links();
      for (int j = 0; j < facts.size(); j++) {
        Fact f = facts.get(j);
        String link = ids.get(j);
        if (link == null) {
          plain.putIfAbsent(f.id(), f);
        } else if (links.computeIfAbsent(link, l -> new LinkedHashMap<>()).putIfAbsent(i, f)
            != null) {
          throw new IllegalStateException("Link " + link + " found twice in view " + views.get(i));
        }
      }
    }
    Map<String, LinkRule.Merged> result = new LinkedHashMap<>();
    plain.forEach((id, f) -> result.put(id, new LinkRule.Merged(f, null)));
    for (Map.Entry<String, Map<Integer, Fact>> entry : links.entrySet()) {
      String link = entry.getKey();
      Map<Integer, Fact> byView = entry.getValue();
      Fact standard = byView.get(0);
      if (standard != null) {
        Set<String> cited = new TreeSet<>();
        byView.values().forEach(f -> cited.addAll(facts(f)));
        ObjectNode attributes = Claims.json(standard);
        List<String> absent = new ArrayList<>();
        for (int i = 1; i < views.size(); i++) {
          if (!byView.containsKey(i)) {
            absent.add(views.get(i).name());
          }
        }
        if (!absent.isEmpty()) {
          ArrayNode array = attributes.putArray("absentIn");
          absent.forEach(array::add);
        }
        if (attributes.has("facts")) {
          ArrayNode array = attributes.putArray("facts");
          cited.forEach(array::add);
        }
        Fact merged = with(standard, attributes);
        result.putIfAbsent(merged.id(), new LinkRule.Merged(merged, link));
      } else {
        byView.forEach(
            (i, f) -> {
              ObjectNode attributes = Claims.json(f);
              attributes.put("view", views.get(i).name());
              Fact merged = with(f, attributes);
              result.putIfAbsent(merged.id(), new LinkRule.Merged(merged, link));
            });
      }
    }
    return List.copyOf(result.values());
  }

  private static List<String> facts(Fact f) {
    return Claims.cited(f);
  }

  private static Fact with(Fact f, ObjectNode attributes) {
    return Fact.create(
        f.subject(),
        f.predicate(),
        f.object(),
        attributes,
        f.origin(),
        f.evidence(),
        f.confidence(),
        f.validity());
  }
}
