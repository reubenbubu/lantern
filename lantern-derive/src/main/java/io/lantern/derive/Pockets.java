package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pockets of units that work closely together (#764; {@code
 * docs/research/grouping-services-into-pockets.md}): a deterministic, size-capped greedy modularity
 * clustering of the units no explicit signal groups into a domain, as SArF does (Kobayashi et al.,
 * ICSM 2012).
 *
 * <ul>
 *   <li>Each ordered pair of units weighs what kinds of connection it has, not how many: a shared
 *       data store 3, an HTTP call 2, a message through a topic, queue or exchange 1; each weight
 *       is divided by the receiver's fan-in (Dedication), so a unit many others use glues no
 *       pockets together.
 *   <li>Starting from one unit each, the two pockets whose merge gains the most modularity merge,
 *       while the gain is positive and the merged pocket holds no more than the cap; then each unit
 *       moves, in ID order, to the neighbouring pocket that gains the most, within the cap, until
 *       none moves; a pocket no longer connected splits into its parts.
 *   <li>A pocket of one unit is no pocket.
 *   <li>A pocket's central member is the one with the most weight inside it, ties to the most
 *       weight in all, then to the ID.
 * </ul>
 *
 * Nothing is random and every loop runs in ID order, so the same units and connections always give
 * the same pockets.
 */
final class Pockets {

  /** The weight of a data store two units share. */
  static final double SHARED_STORE = 3;

  /** The weight of an HTTP call. */
  static final double CALL = 2;

  /** The weight of a message from a publisher to a consumer of the same destination. */
  static final double MESSAGE = 1;

  /** Gains closer than this are equal. */
  private static final double EPSILON = 1e-12;

  /** A connection from one unit to another: its kind, a weight above, and the facts showing it. */
  record Edge(EntityId from, EntityId to, String kind, double weight, List<Fact> facts) {}

  /** A pocket: its central member, and its members with the facts each joined by. */
  record Pocket(EntityId central, SortedMap<EntityId, List<Fact>> members) {}

  private Pockets() {}

  /**
   * The pockets of the given units, each of at least 2 and at most {@code cap} units.
   *
   * @param units the units to group
   * @param edges the connections between them
   * @param cap the most members a pocket holds
   */
  static List<Pocket> of(Collection<EntityId> units, List<Edge> edges, int cap) {
    SortedSet<EntityId> sorted = new TreeSet<>(Knowledge.BY_ID);
    sorted.addAll(units);
    List<EntityId> nodes = new ArrayList<>(sorted);
    int n = nodes.size();
    if (n < 2 || cap < 2) {
      return List.of();
    }
    Map<EntityId, Integer> index = new HashMap<>();
    for (int i = 0; i < n; i++) {
      index.put(nodes.get(i), i);
    }
    double[][] w = weights(index, edges);
    int[] pocket = new int[n];
    for (int i = 0; i < n; i++) {
      pocket[i] = i;
    }
    double total = 0;
    double[] degree = new double[n];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        degree[i] += w[i][j];
      }
      total += degree[i];
    }
    if (total <= 0) {
      return List.of();
    }
    merge(w, degree, total, pocket, cap);
    refine(w, degree, total, pocket, cap);
    List<Pocket> result = new ArrayList<>();
    for (SortedSet<Integer> members : connectedParts(w, pocket)) {
      if (members.size() < 2) {
        continue;
      }
      SortedMap<EntityId, List<Fact>> joined = new TreeMap<>(Knowledge.BY_ID);
      members.forEach(i -> joined.put(nodes.get(i), new ArrayList<>()));
      // Each member joined by its connections with the others.
      for (Edge edge : edges) {
        if (joined.containsKey(edge.from()) && joined.containsKey(edge.to())) {
          joined.get(edge.from()).addAll(edge.facts());
          joined.get(edge.to()).addAll(edge.facts());
        }
      }
      result.add(new Pocket(nodes.get(central(w, degree, members)), joined));
    }
    return result;
  }

  /**
   * The symmetric weights between the units: each ordered pair's kinds of connection, each counted
   * once and divided by the receiver's fan-in, then added both ways.
   */
  static double[][] weights(Map<EntityId, Integer> index, List<Edge> edges) {
    int n = index.size();
    Map<Long, Double> directed = new TreeMap<>();
    Map<Long, Set<String>> kinds = new HashMap<>();
    for (Edge edge : edges) {
      Integer from = index.get(edge.from());
      Integer to = index.get(edge.to());
      if (from == null || to == null || from.equals(to)) {
        continue;
      }
      long key = (long) from * n + to;
      if (kinds.computeIfAbsent(key, k -> new HashSet<>()).add(edge.kind())) {
        directed.merge(key, edge.weight(), Double::sum);
      }
    }
    int[] fanIn = new int[n];
    directed.keySet().forEach(key -> fanIn[(int) (key % n)]++);
    double[][] w = new double[n][n];
    directed.forEach(
        (key, weight) -> {
          int a = (int) (key / n);
          int b = (int) (key % n);
          double dedicated = weight / fanIn[b];
          w[a][b] += dedicated;
          w[b][a] += dedicated;
        });
    return w;
  }

  /**
   * Merges the two pockets whose merge gains the most modularity, while that gain is positive and
   * the merged pocket fits the cap; ties to the pair whose first members come first.
   */
  private static void merge(double[][] w, double[] degree, double total, int[] pocket, int cap) {
    int n = pocket.length;
    while (true) {
      SortedMap<Integer, Double> strength = new TreeMap<>();
      SortedMap<Integer, Integer> size = new TreeMap<>();
      for (int i = 0; i < n; i++) {
        strength.merge(pocket[i], degree[i], Double::sum);
        size.merge(pocket[i], 1, Integer::sum);
      }
      double[][] between = new double[n][n];
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
          if (pocket[i] != pocket[j]) {
            between[pocket[i]][pocket[j]] += w[i][j];
          }
        }
      }
      int bestA = -1;
      int bestB = -1;
      double best = EPSILON;
      for (int a : size.keySet()) {
        for (int b : size.keySet()) {
          if (b <= a || between[a][b] <= 0 || size.get(a) + size.get(b) > cap) {
            continue;
          }
          double gain = between[a][b] / total - strength.get(a) * strength.get(b) / (total * total);
          if (gain > best + EPSILON) {
            best = gain;
            bestA = a;
            bestB = b;
          }
        }
      }
      if (bestA < 0) {
        return;
      }
      for (int i = 0; i < n; i++) {
        if (pocket[i] == bestB) {
          pocket[i] = bestA;
        }
      }
    }
  }

  /**
   * Moves each unit, in ID order, to the neighbouring pocket that gains the most modularity within
   * the cap, until none moves (at most one pass per unit).
   */
  private static void refine(double[][] w, double[] degree, double total, int[] pocket, int cap) {
    int n = pocket.length;
    for (int pass = 0; pass < n; pass++) {
      boolean moved = false;
      for (int v = 0; v < n; v++) {
        Map<Integer, Double> toPocket = new TreeMap<>();
        Map<Integer, Double> strength = new HashMap<>();
        Map<Integer, Integer> size = new HashMap<>();
        for (int u = 0; u < n; u++) {
          if (u != v) {
            strength.merge(pocket[u], degree[u], Double::sum);
            if (w[v][u] > 0) {
              toPocket.merge(pocket[u], w[v][u], Double::sum);
            }
          }
          size.merge(pocket[u], 1, Integer::sum);
        }
        int own = pocket[v];
        double stay =
            toPocket.getOrDefault(own, 0.0) - degree[v] * strength.getOrDefault(own, 0.0) / total;
        int target = own;
        double best = stay;
        for (Map.Entry<Integer, Double> e : toPocket.entrySet()) {
          int other = e.getKey();
          if (other == own || size.get(other) + 1 > cap) {
            continue;
          }
          double gain = e.getValue() - degree[v] * strength.getOrDefault(other, 0.0) / total;
          if (gain > best + EPSILON) {
            best = gain;
            target = other;
          }
        }
        if (target != own) {
          pocket[v] = target;
          moved = true;
        }
      }
      if (!moved) {
        return;
      }
    }
  }

  /** Each pocket's connected parts, in the order of their first members. */
  private static List<SortedSet<Integer>> connectedParts(double[][] w, int[] pocket) {
    int n = pocket.length;
    boolean[] seen = new boolean[n];
    List<SortedSet<Integer>> parts = new ArrayList<>();
    for (int start = 0; start < n; start++) {
      if (seen[start]) {
        continue;
      }
      SortedSet<Integer> part = new TreeSet<>();
      List<Integer> todo = new ArrayList<>(List.of(start));
      seen[start] = true;
      while (!todo.isEmpty()) {
        int v = todo.removeLast();
        part.add(v);
        for (int u = 0; u < n; u++) {
          if (!seen[u] && pocket[u] == pocket[v] && w[v][u] > 0) {
            seen[u] = true;
            todo.add(u);
          }
        }
      }
      parts.add(part);
    }
    return parts;
  }

  /** The member with the most weight inside the pocket, ties to the most in all, then the first. */
  private static int central(double[][] w, double[] degree, SortedSet<Integer> members) {
    int best = -1;
    double bestInside = -1;
    for (int v : members) {
      double inside = 0;
      for (int u : members) {
        inside += w[v][u];
      }
      if (best < 0
          || inside > bestInside + EPSILON
          || Math.abs(inside - bestInside) <= EPSILON && degree[v] > degree[best] + EPSILON) {
        best = v;
        bestInside = inside;
      }
    }
    return best;
  }
}
