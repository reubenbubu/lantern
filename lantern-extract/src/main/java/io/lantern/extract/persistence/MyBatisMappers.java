package io.lantern.extract.persistence;

import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The SQL statements of MyBatis XML mappers: each {@code <select>}, {@code <insert>}, {@code
 * <update>} and {@code <delete>} of a {@code <mapper namespace="...">} file, with its text as the
 * statement would run if every condition held (see {@link Renderer}), {@code <include refid>}
 * replaced by the mapper's {@code <sql>} fragment, and parameters ({@code #{...}}) as {@code ?}. A
 * statement with text substitution ({@code ${...}}) is left out, since its tables may be made at
 * run time.
 */
final class MyBatisMappers {

  /** The most bytes of a mapper file read. */
  private static final long MAX_BYTES = 2_000_000;

  private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete");

  /** Elements a mapper's root may be called. */
  private static final String MAPPER = "mapper";

  private static final Pattern PARAMETER = Pattern.compile("#\\{[^}]*}");

  private static final Pattern SPACE = Pattern.compile("\\s+");

  private MyBatisMappers() {}

  /**
   * A statement of a mapper.
   *
   * @param namespace the mapper's namespace, the binary name of its interface by convention
   * @param id the statement's id, the name of the interface's method
   * @param kind {@code select}, {@code insert}, {@code update} or {@code delete}
   * @param sql the text as it would run, parameters as {@code ?}
   * @param path the file's path relative to the top of the repository
   * @param start the statement's first line
   * @param end its last line
   * @param contentHash the SHA-256 of the file's content
   */
  record Statement(
      String namespace,
      String id,
      String kind,
      String sql,
      String path,
      int start,
      int end,
      String contentHash) {}

  /** MyBatis statement text with its parameters ({@code #{...}}) as JDBC's {@code ?}. */
  static String parameters(String text) {
    return PARAMETER.matcher(text).replaceAll("?");
  }

  /** The statements of every MyBatis mapper file of a repository's sources. */
  static List<Statement> read(RepoBuild repo) {
    List<Statement> found = new ArrayList<>();
    for (String path : GitFiles.list(repo, "")) {
      if (!path.toLowerCase(Locale.ROOT).endsWith(".xml")
          || path.contains("/target/")
          || path.startsWith("target/")) {
        continue;
      }
      byte[] content = GitFiles.read(repo, path, MAX_BYTES);
      if (content == null || !looksLikeMapper(content)) {
        continue;
      }
      found.addAll(parse(path, content));
    }
    return found;
  }

  private static boolean looksLikeMapper(byte[] content) {
    String head = new String(content, 0, Math.min(content.length, 4096), StandardCharsets.UTF_8);
    return head.contains("<mapper") && head.contains("namespace");
  }

  /** The statements of one mapper file; none if it is not one or cannot be read. */
  static List<Statement> parse(String path, byte[] content) {
    Handler handler = new Handler(path, sha256(content));
    handler.lines = new String(content, StandardCharsets.UTF_8).split("\n", -1);
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setValidating(false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      SAXParser parser = factory.newSAXParser();
      parser.parse(new InputSource(new ByteArrayInputStream(content)), handler);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      return List.of();
    }
    return handler.statements();
  }

  /** An element of a mapper, with its text and child elements in order. */
  private static final class Node {
    final String name;
    final Map<String, String> attributes = new HashMap<>();
    final List<Object> children = new ArrayList<>();
    final int start;
    int end;

    Node(String name, Attributes attributes, int start) {
      this.name = name;
      for (int i = 0; i < attributes.getLength(); i++) {
        this.attributes.put(attributes.getQName(i), attributes.getValue(i));
      }
      this.start = start;
      this.end = start;
    }

    String attribute(String name) {
      String value = attributes.get(name);
      return value == null ? null : value.strip();
    }
  }

  /** Reads a mapper into a tree of elements. */
  private static final class Handler extends DefaultHandler {

    private final String path;
    private final String contentHash;
    private String[] lines = new String[0];
    private Locator locator;
    private Node root;
    private final Deque<Node> open = new ArrayDeque<>();

    /**
     * The line a start tag opens on: the locator gives the line it ends on, so this goes back to
     * the nearest line with {@code <name}.
     */
    private int opening(String name) {
      int line = locator == null ? 0 : locator.getLineNumber();
      for (int at = line; at > 0 && at <= lines.length; at--) {
        if (lines[at - 1].contains("<" + name)) {
          return at;
        }
      }
      return line;
    }

    private Handler(String path, String contentHash) {
      this.path = path;
      this.contentHash = contentHash;
    }

    @Override
    public void setDocumentLocator(Locator locator) {
      this.locator = locator;
    }

    @Override
    public void startElement(String uri, String local, String name, Attributes attributes) {
      Node node = new Node(name, attributes, opening(name));
      if (open.isEmpty()) {
        root = node;
      } else {
        open.peek().children.add(node);
      }
      open.push(node);
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (!open.isEmpty()) {
        open.peek().children.add(new String(ch, start, length));
      }
    }

    @Override
    public void endElement(String uri, String local, String name) {
      Node node = open.pop();
      node.end = locator == null ? node.start : locator.getLineNumber();
    }

    List<Statement> statements() {
      List<Statement> result = new ArrayList<>();
      if (root == null || !root.name.equals(MAPPER) || root.attribute("namespace") == null) {
        return result;
      }
      String namespace = root.attribute("namespace");
      Map<String, Node> fragments = new HashMap<>();
      for (Object child : root.children) {
        if (child instanceof Node n && n.name.equals("sql") && n.attribute("id") != null) {
          fragments.put(n.attribute("id"), n);
        }
      }
      Renderer renderer = new Renderer(namespace, fragments);
      for (Object child : root.children) {
        if (child instanceof Node n && STATEMENTS.contains(n.name) && n.attribute("id") != null) {
          String sql = renderer.text(n);
          if (sql == null || sql.isEmpty() || sql.contains("${")) {
            continue;
          }
          sql = parameters(sql);
          result.add(
              new Statement(
                  namespace, n.attribute("id"), n.name, sql, path, n.start, n.end, contentHash));
        }
      }
      return result;
    }
  }

  /**
   * Renders a statement as it would run if every condition held: {@code <where>} and {@code <set>}
   * add their keyword and drop a leading {@code AND}/{@code OR} or a trailing comma, {@code <trim>}
   * its prefix and suffix and the overrides it names, {@code <foreach>} its content once between
   * {@code open} and {@code close}, and {@code <choose>} its first {@code <when>}.
   */
  private record Renderer(String namespace, Map<String, Node> fragments) {

    /** The statement's text, or null if it includes a fragment that is not there. */
    String text(Node statement) {
      StringBuilder b = new StringBuilder();
      if (!render(statement.children, b, 0)) {
        return null;
      }
      return SPACE.matcher(b).replaceAll(" ").strip();
    }

    private boolean render(List<Object> children, StringBuilder b, int depth) {
      for (Object child : children) {
        if (child instanceof String text) {
          b.append(text);
        } else if (child instanceof Node n && !render(n, b, depth)) {
          return false;
        }
      }
      return true;
    }

    private boolean render(Node n, StringBuilder b, int depth) {
      switch (n.name) {
        case "include" -> {
          String refid = n.attribute("refid");
          if (refid != null && refid.startsWith(namespace + ".")) {
            refid = refid.substring(namespace.length() + 1);
          }
          Node fragment = refid == null ? null : fragments.get(refid);
          if (fragment == null || depth > 4) {
            return false;
          }
          StringBuilder inner = new StringBuilder();
          if (!render(fragment.children, inner, depth + 1)) {
            return false;
          }
          // The include's properties fill the fragment's ${...} when the mapper is read.
          String text = inner.toString();
          for (Object child : n.children) {
            if (child instanceof Node p
                && p.name.equals("property")
                && p.attribute("name") != null
                && p.attribute("value") != null) {
              text = text.replace("${" + p.attribute("name") + "}", p.attribute("value"));
            }
          }
          b.append(' ').append(text).append(' ');
          return true;
        }
        case "where" -> {
          return trimmed(n, "WHERE", null, "AND |OR ", null, b, depth);
        }
        case "set" -> {
          return trimmed(n, "SET", null, ",", ",", b, depth);
        }
        case "trim" -> {
          return trimmed(
              n,
              n.attribute("prefix"),
              n.attribute("suffix"),
              n.attribute("prefixOverrides"),
              n.attribute("suffixOverrides"),
              b,
              depth);
        }
        case "foreach" -> {
          StringBuilder inner = new StringBuilder();
          if (!render(n.children, inner, depth)) {
            return false;
          }
          b.append(' ').append(Objects.requireNonNullElse(n.attribute("open"), ""));
          b.append(inner);
          b.append(Objects.requireNonNullElse(n.attribute("close"), "")).append(' ');
          return true;
        }
        case "choose" -> {
          for (Object child : n.children) {
            if (child instanceof Node c && (c.name.equals("when") || c.name.equals("otherwise"))) {
              b.append(' ');
              boolean ok = render(c.children, b, depth);
              b.append(' ');
              return ok;
            }
          }
          return true;
        }
        case "bind", "selectKey" -> {
          return true;
        }
        default -> {
          b.append(' ');
          boolean ok = render(n.children, b, depth);
          b.append(' ');
          return ok;
        }
      }
    }

    /** Content with a prefix and suffix, without the overrides at its start and end. */
    private boolean trimmed(
        Node n,
        String prefix,
        String suffix,
        String prefixOverrides,
        String suffixOverrides,
        StringBuilder b,
        int depth) {
      StringBuilder inner = new StringBuilder();
      if (!render(n.children, inner, depth)) {
        return false;
      }
      String content = SPACE.matcher(inner).replaceAll(" ").strip();
      if (content.isEmpty()) {
        return true;
      }
      content = strip(content, prefixOverrides, true);
      content = strip(content, suffixOverrides, false);
      b.append(' ');
      if (prefix != null) {
        b.append(prefix).append(' ');
      }
      b.append(content);
      if (suffix != null) {
        b.append(' ').append(suffix);
      }
      b.append(' ');
      return true;
    }

    private static String strip(String content, String overrides, boolean start) {
      if (overrides == null) {
        return content;
      }
      String upper = content.toUpperCase(Locale.ROOT);
      for (String o : overrides.split("\\|")) {
        String override = o.toUpperCase(Locale.ROOT);
        String bare = override.strip();
        if (bare.isEmpty()) {
          continue;
        }
        if (start && (upper.startsWith(override) || upper.equals(bare))) {
          return content.substring(Math.min(content.length(), bare.length())).strip();
        }
        if (!start && upper.endsWith(bare)) {
          return content.substring(0, content.length() - bare.length()).strip();
        }
      }
      return content;
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
