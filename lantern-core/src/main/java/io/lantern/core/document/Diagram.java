package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A graph of entities. Renderers lay it out, keeping stored positions.
 *
 * <p>Nodes are held sorted by entity ID and edges by edge ID, so layouts that sort their inputs by
 * stable ID come out the same each run. Node IDs and edge IDs are unique within the diagram, a
 * node's parent is another node of the diagram and parents do not form a cycle, and edges connect
 * nodes of the diagram.
 *
 * @param id the block ID
 * @param diagramKind what kind of diagram it is
 * @param title the title, or {@code null}
 * @param nodes the nodes
 * @param edges the edges
 * @param facts the IDs of the facts the diagram as a whole came from
 */
public record Diagram(
    String id,
    Kind diagramKind,
    String title,
    List<Node> nodes,
    List<Edge> edges,
    List<String> facts)
    implements Block {

  public Diagram {
    Checks.elementId(id, "block ID");
    Objects.requireNonNull(diagramKind, "diagramKind");
    Checks.optionalText(title, "diagram title");
    nodes = sorted(Checks.list(nodes, "nodes"), Comparator.comparing(n -> n.id().toString()));
    edges = sorted(Checks.list(edges, "edges"), Comparator.comparing(Edge::id));
    facts = Checks.factIds(facts, "diagram facts");

    Map<EntityId, Node> byId = new HashMap<>();
    for (Node node : nodes) {
      if (byId.put(node.id(), node) != null) {
        throw new IllegalArgumentException("Duplicate node " + node.id() + " in diagram " + id);
      }
    }
    for (Node node : nodes) {
      Set<EntityId> seen = new HashSet<>();
      for (Node at = node; at.parent() != null; at = byId.get(at.parent())) {
        if (!byId.containsKey(at.parent())) {
          throw new IllegalArgumentException(
              "Node "
                  + at.id()
                  + " in diagram "
                  + id
                  + " has parent "
                  + at.parent()
                  + ", which is not a node of the diagram");
        }
        if (!seen.add(at.id())) {
          throw new IllegalArgumentException(
              "Node parents form a cycle at " + at.id() + " in diagram " + id);
        }
      }
    }
    Set<String> edgeIds = new HashSet<>();
    for (Edge edge : edges) {
      if (!edgeIds.add(edge.id())) {
        throw new IllegalArgumentException("Duplicate edge " + edge.id() + " in diagram " + id);
      }
      if (!byId.containsKey(edge.from()) || !byId.containsKey(edge.to())) {
        throw new IllegalArgumentException(
            "Edge " + edge.id() + " in diagram " + id + " connects a node the diagram lacks");
      }
    }
  }

  private static <T> List<T> sorted(List<T> values, Comparator<T> order) {
    List<T> copy = new ArrayList<>(values);
    copy.sort(order);
    return List.copyOf(copy);
  }

  /** What kind of diagram it is. */
  public enum Kind {
    C4_CONTEXT("c4-context"),
    C4_CONTAINER("c4-container"),
    C4_COMPONENT("c4-component"),
    DEPENDENCY("dependency");

    private final String jsonName;

    Kind(String jsonName) {
      this.jsonName = jsonName;
    }

    public String jsonName() {
      return jsonName;
    }

    public static Kind fromJsonName(String name) {
      Objects.requireNonNull(name, "diagramKind");
      for (Kind kind : values()) {
        if (kind.jsonName.equals(name)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown diagram kind: '" + name + "'");
    }
  }

  /**
   * A diagram node, which shows an entity.
   *
   * @param id the entity the node shows
   * @param label the text shown on the node
   * @param technology the technology line under the label, as in C4 diagrams, or {@code null}
   * @param parent the node this one is drawn inside, as a boundary, or {@code null}
   * @param style the resolved presentation, or {@code null} for the renderer's defaults
   * @param position the stored position, or {@code null} when the renderer places the node
   * @param facts the IDs of the facts the node came from
   */
  public record Node(
      EntityId id,
      String label,
      String technology,
      EntityId parent,
      Style style,
      Position position,
      List<String> facts) {

    public Node {
      Objects.requireNonNull(id, "id");
      Checks.text(label, "node label");
      Checks.optionalText(technology, "node technology");
      if (id.equals(parent)) {
        throw new IllegalArgumentException("Node " + id + " cannot be its own parent");
      }
      facts = Checks.factIds(facts, "node facts");
    }
  }

  /**
   * The resolved presentation of a node. Absent properties use the renderer's defaults.
   *
   * @param shape the shape name, or {@code null}
   * @param icon the icon name, or {@code null}
   * @param color the fill color as {@code #rrggbb} in lowercase hex, or {@code null}
   */
  public record Style(String shape, String icon, String color) {

    private static final Pattern COLOR = Pattern.compile("#[0-9a-f]{6}");

    public Style {
      Checks.optionalText(shape, "shape");
      Checks.optionalText(icon, "icon");
      if (color != null && !COLOR.matcher(color).matches()) {
        throw new IllegalArgumentException("Invalid color: '" + color + "'");
      }
    }
  }

  /**
   * A stored node position: the top-left corner in layout units.
   *
   * @param x the horizontal position
   * @param y the vertical position
   * @param pinned whether a user placed the node; pinned nodes never move
   */
  public record Position(long x, long y, boolean pinned) {}

  /**
   * A diagram edge between two nodes.
   *
   * @param id the edge ID, unique within the diagram
   * @param from the node the edge starts at
   * @param to the node the edge ends at
   * @param label the text shown on the edge, or {@code null}
   * @param relationship the relationship type the edge shows, such as {@code CALLS}, or {@code
   *     null}
   * @param facts the IDs of the facts the edge came from
   */
  public record Edge(
      String id,
      EntityId from,
      EntityId to,
      String label,
      String relationship,
      List<String> facts) {

    public Edge {
      Checks.elementId(id, "edge ID");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Checks.optionalText(label, "edge label");
      if (relationship != null && !Fact.isValidPredicate(relationship)) {
        throw new IllegalArgumentException("Invalid relationship: '" + relationship + "'");
      }
      facts = Checks.factIds(facts, "edge facts");
    }
  }
}
