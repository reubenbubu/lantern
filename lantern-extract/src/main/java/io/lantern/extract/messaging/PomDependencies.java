package io.lantern.extract.messaging;

import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The artifact IDs of the dependencies a module's POM declares, and those its parent POMs in the
 * same repository declare (which it inherits), read from git at the commit: for telling Spring
 * Cloud Stream's binder when there is no build. Dependency management (a BOM) only manages
 * versions, so it adds none. See {@code docs/adr/0040-spring-cloud-stream.md}.
 */
final class PomDependencies {

  private static final long MAX_BYTES = 4L * 1024 * 1024;

  /** How many parents are followed up the repository. */
  private static final int MAX_PARENTS = 8;

  private PomDependencies() {}

  /**
   * The artifact IDs declared, in the module's POM (and its profiles) and its in-repository
   * parents'; empty if there is no POM or it cannot be read.
   */
  static List<String> artifactIds(RepoBuild repo, String directory) {
    List<String> ids = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String pom = directory.isEmpty() ? "pom.xml" : directory + "/pom.xml";
    for (int i = 0; i <= MAX_PARENTS && pom != null && seen.add(pom); i++) {
      byte[] content = GitFiles.read(repo, pom, MAX_BYTES);
      if (content == null) {
        break;
      }
      Handler h = parse(content);
      if (h == null) {
        break;
      }
      ids.addAll(h.dependencies);
      pom = h.hasParent ? parentPath(pom, h.relativePath) : null;
    }
    return ids;
  }

  /** The repository path of the parent POM, or null if it lies outside the repository. */
  private static String parentPath(String pom, String relativePath) {
    String relative =
        relativePath == null ? "../pom.xml" : relativePath.isBlank() ? null : relativePath.trim();
    if (relative == null) {
      return null;
    }
    Path dir = Path.of(pom).getParent();
    Path resolved = (dir == null ? Path.of(relative) : dir.resolve(relative)).normalize();
    if (resolved.startsWith("..") || resolved.toString().isEmpty()) {
      return null;
    }
    String path = resolved.toString().replace('\\', '/');
    return path.endsWith(".xml") ? path : path + "/pom.xml";
  }

  private static Handler parse(byte[] xml) {
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      Handler handler = new Handler();
      factory.newSAXParser().parse(new ByteArrayInputStream(xml), handler);
      return handler;
    } catch (ParserConfigurationException | SAXException | IOException e) {
      return null;
    }
  }

  private static final class Handler extends DefaultHandler {

    private final Deque<String> open = new ArrayDeque<>();
    private final StringBuilder text = new StringBuilder();
    final List<String> dependencies = new ArrayList<>();
    boolean hasParent;
    String relativePath;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      open.push(qName);
      text.setLength(0);
      if (open.size() == 2 && qName.equals("parent")) {
        hasParent = true;
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      text.append(ch, start, length);
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      List<String> path = new ArrayList<>(open);
      // path is innermost first: artifactId, dependency, dependencies, (project | profile ...)
      if (qName.equals("artifactId")
          && path.size() >= 4
          && path.get(1).equals("dependency")
          && path.get(2).equals("dependencies")
          && (path.size() == 4 || path.get(3).equals("profile"))) {
        dependencies.add(text.toString().trim());
      }
      if (qName.equals("relativePath") && path.size() == 3 && path.get(1).equals("parent")) {
        relativePath = text.toString();
      }
      open.pop();
      text.setLength(0);
    }
  }
}
