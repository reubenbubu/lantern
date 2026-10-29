package io.lantern.extract.sql;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * A Liquibase changelog read into one shape whatever its format, so the changes are interpreted
 * once: an XML element, or a YAML or JSON object, with its scalar properties as attributes and its
 * nested objects as children. In YAML and JSON, the lists {@code changes} and {@code columns} are
 * flattened into their parent, as XML writes them; other lists (such as {@code rollback}) become a
 * child of that name. An element's text is the attribute {@code #text}.
 *
 * @param name the element or key name, such as {@code createTable}
 * @param attributes the scalar properties in document order
 * @param children the nested elements in document order
 * @param startLine the first line, 1-based
 * @param endLine the last line
 */
record ChangelogNode(
    String name,
    Map<String, String> attributes,
    List<ChangelogNode> children,
    int startLine,
    int endLine) {

  /** The attribute holding an XML element's text, or a YAML scalar given in place of an object. */
  static final String TEXT = "#text";

  private static final Set<String> FLATTENED = Set.of("databaseChangeLog", "changes", "columns");

  ChangelogNode {
    attributes = Map.copyOf(attributes);
    children = List.copyOf(children);
  }

  String attribute(String name) {
    String value = attributes.get(name);
    return value == null || value.isBlank() ? null : value.strip();
  }

  boolean flag(String name) {
    return "true".equalsIgnoreCase(attribute(name));
  }

  List<ChangelogNode> children(String name) {
    return children.stream().filter(c -> c.name.equals(name)).toList();
  }

  ChangelogNode child(String name) {
    return children.stream().filter(c -> c.name.equals(name)).findFirst().orElse(null);
  }

  /**
   * Reads a changelog file by its extension. Returns null if it is not readable or its root is not
   * {@code databaseChangeLog}.
   */
  static ChangelogNode read(String path, byte[] content) {
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    try {
      ChangelogNode root =
          lower.endsWith(".xml")
              ? xml(content)
              : lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json")
                  ? yaml(content)
                  : null;
      return root != null && root.name.equals("databaseChangeLog") ? root : null;
    } catch (IOException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // XML

  /** Parses XML with DTDs and external entities disabled, since repositories are untrusted. */
  static ChangelogNode xml(byte[] content) throws IOException {
    XmlHandler handler = new XmlHandler();
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setXIncludeAware(false);
      SAXParser parser = factory.newSAXParser();
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      parser.parse(new InputSource(new ByteArrayInputStream(content)), handler);
    } catch (SAXException | ParserConfigurationException e) {
      throw new IOException("Not a readable XML document: " + e.getMessage(), e);
    }
    if (handler.root == null) {
      throw new IOException("Not a readable XML document: no root element");
    }
    return handler.root;
  }

  private static final class XmlHandler extends DefaultHandler {

    private record Open(
        String name,
        Map<String, String> attributes,
        int startLine,
        StringBuilder text,
        List<ChangelogNode> children) {}

    private final Deque<Open> open = new ArrayDeque<>();
    private Locator locator;
    private ChangelogNode root;

    @Override
    public void setDocumentLocator(Locator locator) {
      this.locator = locator;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      String name = localName == null || localName.isEmpty() ? qName : localName;
      Map<String, String> values = new LinkedHashMap<>();
      for (int i = 0; i < attributes.getLength(); i++) {
        String key = attributes.getLocalName(i);
        values.put(
            key == null || key.isEmpty() ? attributes.getQName(i) : key, attributes.getValue(i));
      }
      open.push(new Open(name, values, line(), new StringBuilder(), new ArrayList<>()));
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (!open.isEmpty()) {
        open.peek().text().append(ch, start, length);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      Open element = open.pop();
      Map<String, String> attributes = new LinkedHashMap<>(element.attributes());
      String text = element.text().toString().strip();
      if (!text.isEmpty()) {
        attributes.put(TEXT, text);
      }
      ChangelogNode done =
          new ChangelogNode(
              element.name(), attributes, element.children(), element.startLine(), line());
      if (open.isEmpty()) {
        root = done;
      } else {
        open.peek().children().add(done);
      }
    }

    private int line() {
      return locator == null ? 1 : Math.max(1, locator.getLineNumber());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // YAML and JSON

  /**
   * Parses YAML (and so JSON) into nodes only, never into Java objects, so no tag in an untrusted
   * file can construct one; aliases and size are limited.
   */
  static ChangelogNode yaml(byte[] content) throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(50);
    options.setAllowRecursiveKeys(false);
    options.setNestingDepthLimit(100);
    options.setCodePointLimit(content.length + 1);
    Node root;
    try {
      root =
          new Yaml(options)
              .compose(
                  new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
    } catch (YAMLException e) {
      throw new IOException("Not a readable YAML document: " + e.getMessage(), e);
    }
    if (!(root instanceof MappingNode mapping)) {
      return null;
    }
    for (NodeTuple entry : mapping.getValue()) {
      if (entry.getKeyNode() instanceof ScalarNode key
          && key.getValue().equals("databaseChangeLog")) {
        return yaml("databaseChangeLog", entry.getValueNode());
      }
    }
    return null;
  }

  private static ChangelogNode yaml(String name, Node value) {
    Map<String, String> attributes = new LinkedHashMap<>();
    List<ChangelogNode> children = new ArrayList<>();
    switch (value) {
      case ScalarNode scalar -> attributes.put(TEXT, scalar.getValue());
      case MappingNode mapping -> {
        for (NodeTuple entry : mapping.getValue()) {
          if (!(entry.getKeyNode() instanceof ScalarNode keyNode)) {
            continue;
          }
          String key = keyNode.getValue();
          Node v = entry.getValueNode();
          switch (v) {
            case ScalarNode scalar -> attributes.put(key, scalar.getValue());
            case SequenceNode sequence when FLATTENED.contains(key) ->
                children.addAll(items(sequence));
            default -> children.add(yaml(key, v));
          }
        }
      }
      case SequenceNode sequence -> children.addAll(items(sequence));
      default -> {}
    }
    return new ChangelogNode(name, attributes, children, startLine(value), endLine(value));
  }

  /** The items of a list, each a one-key object such as {@code - changeSet: {...}}. */
  private static List<ChangelogNode> items(SequenceNode sequence) {
    List<ChangelogNode> items = new ArrayList<>();
    for (Node item : sequence.getValue()) {
      if (item instanceof MappingNode mapping) {
        for (NodeTuple entry : mapping.getValue()) {
          if (entry.getKeyNode() instanceof ScalarNode key) {
            ChangelogNode node = yaml(key.getValue(), entry.getValueNode());
            items.add(
                new ChangelogNode(
                    node.name,
                    node.attributes,
                    node.children,
                    startLine(entry.getKeyNode()),
                    node.endLine));
          }
        }
      }
    }
    return items;
  }

  private static int startLine(Node node) {
    return node.getStartMark().getLine() + 1;
  }

  /**
   * The last line with content. A block's end mark is where the next token starts, so it is taken
   * from the last scalar inside it instead.
   */
  private static int endLine(Node node) {
    int line =
        switch (node) {
          case ScalarNode scalar -> {
            int end = scalar.getEndMark().getLine() + 1;
            // A block scalar (|, >) ends at the start of the line after it.
            yield scalar.getEndMark().getColumn() == 0 ? end - 1 : end;
          }
          case MappingNode mapping ->
              mapping.getValue().stream()
                  .mapToInt(t -> Math.max(endLine(t.getKeyNode()), endLine(t.getValueNode())))
                  .max()
                  .orElse(startLine(node));
          case SequenceNode sequence ->
              sequence.getValue().stream()
                  .mapToInt(ChangelogNode::endLine)
                  .max()
                  .orElse(startLine(node));
          default -> startLine(node);
        };
    return Math.max(line, startLine(node));
  }
}
