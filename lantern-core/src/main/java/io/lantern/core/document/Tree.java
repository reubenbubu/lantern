package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import java.util.List;

/**
 * A hierarchy, such as a dependency tree or module and package structure.
 *
 * @param id the block ID
 * @param title the title, or {@code null}
 * @param roots the top-level nodes
 * @param facts the IDs of the facts the tree as a whole came from
 */
public record Tree(String id, String title, List<Node> roots, List<String> facts) implements Block {

  public Tree {
    Checks.elementId(id, "block ID");
    Checks.optionalText(title, "tree title");
    roots = Checks.list(roots, "tree roots");
    facts = Checks.factIds(facts, "tree facts");
  }

  /**
   * A tree node.
   *
   * @param label the text shown for the node
   * @param entity the entity the node stands for, or {@code null}
   * @param facts the IDs of the facts the node came from
   * @param children the nodes below this one
   */
  public record Node(String label, EntityId entity, List<String> facts, List<Node> children) {

    public Node {
      Checks.text(label, "tree node label");
      facts = Checks.factIds(facts, "tree node facts");
      children = Checks.list(children, "tree node children");
    }
  }
}
