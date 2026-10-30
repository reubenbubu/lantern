package io.lantern.extract.maven;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

  /** What makes a module deployable, as the {@code deployable-by} attribute names it. */
  static final String WAR = "war";

  static final String EAR = "ear";
  static final String SPRING_BOOT = "spring-boot";
  static final String SPRING_BOOT_IMAGE = "spring-boot-build-image";
  static final String QUARKUS = "quarkus";
  static final String JIB = "jib";
  static final String JKUBE = "jkube";
  static final String DOCKER = "docker";
  static final String SHADE = "shade";
  static final String ASSEMBLY = "assembly";

  private static final String FABRIC8_DOCKER_PLUGIN = "io.fabric8:docker-maven-plugin";

  /** Image plugins, by key, with the user property that skips each. */
  private static final Map<String, String[]> IMAGE_PLUGINS =
      Map.of(
          "com.google.cloud.tools:jib-maven-plugin",
          new String[] {JIB, "jib.skip"},
          "org.eclipse.jkube:kubernetes-maven-plugin",
          new String[] {JKUBE, "jkube.skip"},
          "org.eclipse.jkube:openshift-maven-plugin",
          new String[] {JKUBE, "jkube.skip"},
          "com.spotify:dockerfile-maven-plugin",
          new String[] {DOCKER, "dockerfile.skip"},
          FABRIC8_DOCKER_PLUGIN,
          new String[] {DOCKER, "docker.skip"});

  /**
   * What makes the project's build deployable, or null for a library: a {@code war} or {@code ear};
   * Quarkus ({@code quarkus} packaging, or its plugin's {@code build} goal); an executable jar
   * repackaged by Spring Boot's Maven plugin; an image built by Spring Boot's {@code build-image}
   * goal, Jib, Eclipse JKube or a Docker plugin; or a jar with a main class made by the shade or
   * assembly plugin. A plugin or execution skipped with {@code <skip>}, its skip property or {@code
   * <phase>none</phase>} does not count. Plugins under {@code pluginManagement} are not in {@code
   * plugins}, so they never count.
   *
   * @param properties the project's resolved properties, for {@code ${...}} references
   * @param barePluginCounts whether a Spring Boot or Quarkus plugin declared without its goal
   *     counts. In an effective POM every execution is spelled out, so it does not; in a POM as
   *     written a parent's plugin or {@code pluginManagement} may bind the goal, so it does.
   */
  String deployedBy(String packaging, Map<String, String> properties, boolean barePluginCounts) {
    if (packaging.equals("pom")) {
      return null;
    }
    if (packaging.equals(WAR) || packaging.equals(EAR)) {
      return packaging;
    }
    if (packaging.equals(QUARKUS)) {
      return QUARKUS;
    }
    String found = null;
    for (XmlElement plugin : plugins) {
      String by = pluginDeploys(plugin, properties, barePluginCounts);
      // An executable jar says more than an image of it.
      if (by != null && (found == null || rank(by) < rank(found))) {
        found = by;
      }
    }
    return found;
  }

  private static int rank(String by) {
    return List.of(SPRING_BOOT, QUARKUS, SHADE, ASSEMBLY, SPRING_BOOT_IMAGE, JIB, JKUBE, DOCKER)
        .indexOf(by);
  }

  /** What a plugin makes deployable, or null. */
  private static String pluginDeploys(
      XmlElement plugin, Map<String, String> properties, boolean barePluginCounts) {
    if (skipped(plugin, properties)) {
      return null;
    }
    String key = pluginKey(plugin);
    List<XmlElement> executions =
        plugin.list("executions", "execution").stream()
            .filter(e -> !skipped(e, properties))
            .filter(e -> !"none".equals(interpolate(e.text("phase"), properties)))
            .toList();
    if (isBootPlugin(plugin)) {
      boolean repackage =
          !"true".equals(interpolate(properties.get("spring-boot.repackage.skip"), properties))
              && (executions.stream().anyMatch(e -> hasGoal(e, "repackage"))
                  || barePluginCounts && plugin.list("executions", "execution").isEmpty());
      if (repackage) {
        return SPRING_BOOT;
      }
      boolean image =
          !"true".equals(interpolate(properties.get("spring-boot.build-image.skip"), properties))
              && executions.stream()
                  .anyMatch(e -> hasGoal(e, "build-image") || hasGoal(e, "build-image-no-fork"));
      return image ? SPRING_BOOT_IMAGE : null;
    }
    if (plugin.text("artifactId") != null
        && plugin.text("artifactId").equals("quarkus-maven-plugin")) {
      boolean build =
          !"true".equals(interpolate(properties.get("quarkus.build.skip"), properties))
              && (executions.stream().anyMatch(e -> hasGoal(e, "build"))
                  || barePluginCounts && plugin.list("executions", "execution").isEmpty());
      return build ? QUARKUS : null;
    }
    String[] image = IMAGE_PLUGINS.get(key);
    if (image != null) {
      if ("true".equals(interpolate(properties.get(image[1]), properties))) {
        return null;
      }
      boolean declaresExecutions = !plugin.list("executions", "execution").isEmpty();
      if (key.equals(FABRIC8_DOCKER_PLUGIN)) {
        // Often declared only to start and stop containers for integration tests.
        boolean builds =
            executions.stream().anyMatch(e -> hasGoal(e, "build"))
                || !declaresExecutions
                    && plugin
                        .child("configuration")
                        .map(c -> c.list("images", "image"))
                        .orElse(List.of())
                        .stream()
                        .anyMatch(i -> i.child("build").isPresent());
        return builds ? image[0] : null;
      }
      // Run from the command line when it declares no executions; else one must remain.
      return declaresExecutions && executions.isEmpty() ? null : image[0];
    }
    if (key.equals("org.apache.maven.plugins:maven-shade-plugin")) {
      for (XmlElement e : executions) {
        boolean mainClass =
            hasGoal(e, "shade")
                && configurations(e, plugin)
                    .flatMap(c -> c.list("transformers", "transformer").stream())
                    .anyMatch(t -> resolved(t.text("mainClass"), properties));
        if (mainClass) {
          return SHADE;
        }
      }
    }
    if (key.equals("org.apache.maven.plugins:maven-assembly-plugin")) {
      for (XmlElement e : executions) {
        if (!hasGoal(e, "single")) {
          continue;
        }
        boolean mainClass =
            configurations(e, plugin)
                .flatMap(c -> c.child("archive").stream())
                .flatMap(a -> a.child("manifest").stream())
                .anyMatch(m -> resolved(m.text("mainClass"), properties));
        if (mainClass) {
          return ASSEMBLY;
        }
      }
    }
    return null;
  }

  /**
   * An execution's configuration, then its plugin's: in a POM as written they are not merged yet.
   */
  private static Stream<XmlElement> configurations(XmlElement execution, XmlElement plugin) {
    return Stream.of(execution, plugin).flatMap(x -> x.child("configuration").stream());
  }

  private static boolean hasGoal(XmlElement execution, String goal) {
    return execution.list("goals", "goal").stream().anyMatch(g -> g.text().equals(goal));
  }

  /** Whether a value is given and every reference in it resolves. */
  private static boolean resolved(String value, Map<String, String> properties) {
    String v = interpolate(value, properties);
    return v != null && !v.isBlank() && !v.contains("${");
  }

  /** Whether the plugin element is Spring Boot's Maven plugin. */
  static boolean isBootPlugin(XmlElement plugin) {
    return SPRING_BOOT_PLUGIN.equals(plugin.text("artifactId"))
        && SPRING_BOOT_GROUP.equals(plugin.text("groupId"));
  }

  /** A plugin's key, {@code groupId:artifactId}, with Maven's default group. */
  static String pluginKey(XmlElement plugin) {
    String group = plugin.text("groupId");
    return (group == null || group.isEmpty() ? "org.apache.maven.plugins" : group)
        + ":"
        + plugin.text("artifactId");
  }

  private static boolean skipped(XmlElement element, Map<String, String> properties) {
    return element
        .child("configuration")
        .map(c -> "true".equals(interpolate(c.text("skip"), properties)))
        .orElse(false);
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
