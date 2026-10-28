package io.lantern.sandbox;

import java.util.List;
import java.util.Objects;

/**
 * A Maven module of a build, with the outputs it left. Paths are relative to the build's directory
 * and use {@code /}; a path is null when the build left nothing there.
 *
 * @param path the module's directory relative to the top of the repository, {@code ""} for the top
 * @param groupId the module's group ID
 * @param artifactId the module's artifact ID
 * @param packaging the module's packaging, such as {@code jar} or {@code pom}
 * @param version the module's version
 * @param dependsOn the paths of the other modules of the build this module depends on directly
 * @param classpath the module's compile classpath: jars under {@code lib/} and the class
 *     directories of other modules, in Maven's order
 * @param classes the compiled classes directory
 * @param generatedSources the generated sources directory, holding one directory per generator
 * @param dependencyTree Maven's dependency tree, as text
 */
public record BuildModule(
    String path,
    String groupId,
    String artifactId,
    String packaging,
    String version,
    List<String> dependsOn,
    List<String> classpath,
    String classes,
    String generatedSources,
    String dependencyTree) {

  public BuildModule {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(groupId, "groupId");
    Objects.requireNonNull(artifactId, "artifactId");
    Objects.requireNonNull(packaging, "packaging");
    Objects.requireNonNull(version, "version");
    dependsOn = List.copyOf(dependsOn);
    classpath = List.copyOf(classpath);
  }
}
