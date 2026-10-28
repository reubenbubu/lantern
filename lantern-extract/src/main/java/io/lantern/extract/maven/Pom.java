package io.lantern.extract.maven;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Maven extractor reads from one {@code <project>} element, either a POM as written in a
 * repository or a project of an effective POM. Values are as written; {@link #interpolate} resolves
 * {@code ${...}} references. Profiles are not read.
 *
 * @param xml the project element
 * @param parent the parent's coordinates, or null if the project has no parent
 * @param groupId the group ID as written, or null if inherited from the parent
 * @param artifactId the artifact ID as written
 * @param version the version as written, or null if inherited from the parent
 * @param packaging the packaging as written, or null for the default ({@code jar})
 * @param properties the properties the project declares
 * @param modules the {@code <module>} elements
 * @param dependencies the declared dependencies
 * @param managedDependencies the dependencies in {@code <dependencyManagement>}
 * @param plugins the plugins of {@code <build><plugins>}
 */
record Pom(
    XmlElement xml,
    Coordinates parent,
    String groupId,
    String artifactId,
    String version,
    String packaging,
    Map<String, String> properties,
    List<XmlElement> modules,
    List<Dependency> dependencies,
    List<Dependency> managedDependencies,
    List<XmlElement> plugins) {

  static final String SPRING_BOOT_GROUP = "org.springframework.boot";
  static final String SPRING_BOOT_PLUGIN = "spring-boot-maven-plugin";

  private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
  private static final int MAX_DEPTH = 10;

  /** A group ID, artifact ID and version, any of which may be null if not given. */
  record Coordinates(String groupId, String artifactId, String version) {}

  /**
   * A dependency as written.
   *
   * @param element the {@code <dependency>} element
   */
  record Dependency(
      String groupId,
      String artifactId,
      String version,
      String type,
      String classifier,
      String scope,
      String optional,
      XmlElement element) {

    static Dependency of(XmlElement e) {
      return new Dependency(
          e.text("groupId"),
          e.text("artifactId"),
          e.text("version"),
          e.text("type"),
          e.text("classifier"),
          e.text("scope"),
          e.text("optional"),
          e);
    }

    /** What identifies a dependency within a project: group, artifact, type and classifier. */
    String managementKey() {
      return groupId
          + ":"
          + artifactId
          + ":"
          + (type == null ? "jar" : type)
          + ":"
          + (classifier == null ? "" : classifier);
    }
  }

  /** Reads a {@code <project>} element. */
  static Pom of(XmlElement project) {
    Coordinates parent =
        project
            .child("parent")
            .map(p -> new Coordinates(p.text("groupId"), p.text("artifactId"), p.text("version")))
            .orElse(null);
    Map<String, String> properties = new LinkedHashMap<>();
    project
        .child("properties")
        .ifPresent(p -> p.children().forEach(c -> properties.putIfAbsent(c.name(), c.text())));
    return new Pom(
        project,
        parent,
        project.text("groupId"),
        project.text("artifactId"),
        project.text("version"),
        project.text("packaging"),
        properties,
        project.list("modules", "module"),
        project.list("dependencies", "dependency").stream().map(Dependency::of).toList(),
        project
            .child("dependencyManagement")
            .map(m -> m.list("dependencies", "dependency"))
            .orElse(List.of())
            .stream()
            .map(Dependency::of)
            .toList(),
        project.child("build").map(b -> b.list("plugins", "plugin")).orElse(List.of()));
  }

  /** The group ID, inherited from the parent if not given. */
  String effectiveGroupId() {
    return groupId != null ? groupId : parent == null ? null : parent.groupId();
  }

  /** The version, inherited from the parent if not given. */
  String effectiveVersion() {
    return version != null ? version : parent == null ? null : parent.version();
  }

  /** The packaging, {@code jar} if not given. */
  String effectivePackaging() {
    return packaging == null || packaging.isEmpty() ? "jar" : packaging;
  }

  /**
   * Whether the project's build makes something deployable: a war or ear, or an executable jar
   * repackaged by Spring Boot's Maven plugin.
   *
   * @param bareBootPluginCounts whether a Spring Boot plugin declared without a {@code repackage}
   *     goal counts. In an effective POM every execution is spelled out, so it does not; in a POM
   *     as written the parent may bind the goal, so it does.
   */
  boolean deployable(String packaging, boolean bareBootPluginCounts) {
    if (packaging.equals("war") || packaging.equals("ear")) {
      return true;
    }
    for (XmlElement plugin : plugins) {
      String group = plugin.text("groupId");
      if (!SPRING_BOOT_PLUGIN.equals(plugin.text("artifactId"))
          || !SPRING_BOOT_GROUP.equals(group)) {
        continue;
      }
      if (skipped(plugin)) {
        continue;
      }
      List<XmlElement> executions = plugin.list("executions", "execution");
      if (executions.isEmpty() && bareBootPluginCounts) {
        return true;
      }
      for (XmlElement execution : executions) {
        boolean repackage =
            execution.list("goals", "goal").stream().anyMatch(g -> g.text().equals("repackage"));
        if (repackage && !skipped(execution)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean skipped(XmlElement element) {
    return element.child("configuration").map(c -> "true".equals(c.text("skip"))).orElse(false);
  }

  /**
   * Resolves {@code ${...}} references in a value from the given properties and the project's
   * coordinates. Returns null if the value is null or a reference cannot be resolved.
   */
  static String interpolate(String value, Map<String, String> properties) {
    if (value == null) {
      return null;
    }
    String current = value;
    for (int depth = 0; depth < MAX_DEPTH; depth++) {
      Matcher m = PROPERTY.matcher(current);
      if (!m.find()) {
        return current;
      }
      StringBuilder out = new StringBuilder();
      m.reset();
      while (m.find()) {
        String replacement = properties.get(m.group(1));
        if (replacement == null) {
          return null;
        }
        m.appendReplacement(out, Matcher.quoteReplacement(replacement));
      }
      m.appendTail(out);
      current = out.toString();
    }
    return PROPERTY.matcher(current).find() ? null : current;
  }
}
