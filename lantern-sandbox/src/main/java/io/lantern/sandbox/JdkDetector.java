package io.lantern.sandbox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Detects the Java version a Maven project targets from its POM, which comes from an untrusted
 * repository and is parsed with DTDs and external entities disabled.
 *
 * <p>The first of these that gives a version wins: the JDK version the toolchains plugin asks for,
 * the compiler release ({@code maven.compiler.release} or the compiler plugin's {@code release}),
 * the compiler source ({@code maven.compiler.source} or the plugin's {@code source}), and the
 * {@code java.version} property that Spring Boot's parent POM maps to the compiler release.
 * Properties are resolved within the POM; a parent POM outside it is not read.
 */
final class JdkDetector {

  private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
  private static final Pattern VERSION = Pattern.compile("(?:1\\.)?([0-9]{1,3})");
  private static final int MAX_PROPERTY_DEPTH = 10;

  private JdkDetector() {}

  /** A detected Java version and where in the POM it came from. */
  record Detection(int version, String source) {}

  private record Candidate(String value, String source) {}

  /**
   * Detects the Java version.
   *
   * @param pom the POM's bytes
   * @return the version and where it was found, or empty if the POM does not say or cannot be read
   */
  static Optional<Detection> detect(byte[] pom) {
    Element project;
    try {
      project = parse(pom).getDocumentElement();
    } catch (IOException | SAXException | ParserConfigurationException e) {
      return Optional.empty();
    }
    if (project == null || !"project".equals(name(project))) {
      return Optional.empty();
    }
    Map<String, String> properties = new HashMap<>();
    child(project, "properties")
        .ifPresent(
            props -> children(props).forEach(p -> properties.put(name(p), p.getTextContent())));
    List<Element> compilers = plugins(project, "maven-compiler-plugin");
    List<Candidate> candidates = new ArrayList<>();
    for (Element toolchains : plugins(project, "maven-toolchains-plugin")) {
      configuration(toolchains, "toolchains", "jdk", "version")
          .ifPresent(v -> candidates.add(new Candidate(v, "toolchains plugin jdk version")));
    }
    add(candidates, properties.get("maven.compiler.release"), "maven.compiler.release");
    for (Element compiler : compilers) {
      configuration(compiler, "release")
          .ifPresent(v -> candidates.add(new Candidate(v, "compiler plugin release")));
    }
    add(candidates, properties.get("maven.compiler.source"), "maven.compiler.source");
    for (Element compiler : compilers) {
      configuration(compiler, "source")
          .ifPresent(v -> candidates.add(new Candidate(v, "compiler plugin source")));
    }
    add(candidates, properties.get("java.version"), "java.version");
    for (Candidate candidate : candidates) {
      OptionalInt version = version(resolve(candidate.value(), properties));
      if (version.isPresent()) {
        return Optional.of(new Detection(version.getAsInt(), candidate.source()));
      }
    }
    return Optional.empty();
  }

  /**
   * The major version in a version string: {@code 17} for "17", "1.8" gives 8, and a range such as
   * "[17,)" gives its lower bound.
   */
  static OptionalInt version(String text) {
    if (text == null) {
      return OptionalInt.empty();
    }
    String value = text.strip();
    if (value.startsWith("[") || value.startsWith("(")) {
      value = value.substring(1).split(",", -1)[0].strip();
    }
    Matcher m = VERSION.matcher(value);
    if (!m.lookingAt()) {
      return OptionalInt.empty();
    }
    int version = Integer.parseInt(m.group(1));
    if (version < 1) {
      return OptionalInt.empty();
    }
    // Anything after the major version must be a further version part, not more digits or words.
    String rest = value.substring(m.end());
    if (!rest.isEmpty() && !rest.startsWith(".")) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(version);
  }

  private static void add(List<Candidate> candidates, String value, String source) {
    if (value != null) {
      candidates.add(new Candidate(value, source));
    }
  }

  /** Replaces {@code ${name}} with POM properties, or null if a property is unknown. */
  private static String resolve(String value, Map<String, String> properties) {
    String current = value;
    for (int depth = 0; depth < MAX_PROPERTY_DEPTH; depth++) {
      Matcher m = PROPERTY.matcher(current);
      if (!m.find()) {
        return current;
      }
      String replacement = properties.get(m.group(1));
      if (replacement == null) {
        return null;
      }
      current = current.substring(0, m.start()) + replacement + current.substring(m.end());
    }
    return null;
  }

  /** The plugins with the artifact ID in build/plugins and build/pluginManagement/plugins. */
  private static List<Element> plugins(Element project, String artifactId) {
    List<Element> found = new ArrayList<>();
    child(project, "build")
        .ifPresent(
            build -> {
              child(build, "plugins").ifPresent(p -> found.addAll(pluginsIn(p, artifactId)));
              child(build, "pluginManagement")
                  .flatMap(m -> child(m, "plugins"))
                  .ifPresent(p -> found.addAll(pluginsIn(p, artifactId)));
            });
    return found;
  }

  private static List<Element> pluginsIn(Element plugins, String artifactId) {
    return children(plugins).stream()
        .filter(p -> "plugin".equals(name(p)))
        .filter(
            p ->
                child(p, "artifactId")
                    .map(a -> a.getTextContent().strip().equals(artifactId))
                    .orElse(false))
        .toList();
  }

  /** The text at a path under the plugin's configuration. */
  private static Optional<String> configuration(Element plugin, String... path) {
    Optional<Element> current = child(plugin, "configuration");
    for (String step : path) {
      current = current.flatMap(e -> child(e, step));
    }
    return current.map(Element::getTextContent);
  }

  private static Optional<Element> child(Element parent, String name) {
    return children(parent).stream().filter(e -> name.equals(name(e))).findFirst();
  }

  private static List<Element> children(Element parent) {
    List<Element> elements = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        elements.add(e);
      }
    }
    return elements;
  }

  private static String name(Element e) {
    return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
  }

  /** Parses an untrusted XML document with DTDs and external entities off. */
  static Document parse(byte[] xml) throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setErrorHandler(null);
    return builder.parse(new ByteArrayInputStream(xml));
  }
}
