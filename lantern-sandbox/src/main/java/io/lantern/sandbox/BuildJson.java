package io.lantern.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes and reads {@code build.json}, the stored form of a {@link BuildResult}. */
final class BuildJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private BuildJson() {}

  static String write(BuildResult result) {
    ObjectNode json = MAPPER.createObjectNode();
    json.put("repo", result.repo());
    json.put("commit", result.commit());
    json.put("status", result.status().name());
    json.put("failure", result.failure());
    json.put("jdk", result.jdk());
    json.put("jdkSource", result.jdkSource());
    json.put("image", result.image());
    if (result.mavenExitCode() == null) {
      json.putNull("mavenExitCode");
    } else {
      json.put("mavenExitCode", result.mavenExitCode().intValue());
    }
    json.put("startedAt", result.startedAt().toString());
    json.put("duration", result.duration().toString());
    json.put("effectivePom", result.effectivePom());
    ArrayNode modules = json.putArray("modules");
    for (BuildModule module : result.modules()) {
      ObjectNode m = modules.addObject();
      m.put("path", module.path());
      m.put("groupId", module.groupId());
      m.put("artifactId", module.artifactId());
      m.put("packaging", module.packaging());
      m.put("version", module.version());
      module.dependsOn().forEach(m.putArray("dependsOn")::add);
      module.classpath().forEach(m.putArray("classpath")::add);
      m.put("classes", module.classes());
      m.put("generatedSources", module.generatedSources());
      m.put("dependencyTree", module.dependencyTree());
    }
    result.upstream().stream().sorted().forEach(json.putArray("upstream")::add);
    result.shadowed().forEach(json.putArray("shadowed")::add);
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n";
  }

  static BuildResult read(String text, Path directory) {
    JsonNode json = MAPPER.readTree(text);
    List<BuildModule> modules =
        json.get("modules")
            .valueStream()
            .map(
                m ->
                    new BuildModule(
                        m.get("path").stringValue(),
                        m.get("groupId").stringValue(),
                        m.get("artifactId").stringValue(),
                        m.get("packaging").stringValue(),
                        m.get("version").stringValue(),
                        strings(m.get("dependsOn")),
                        strings(m.get("classpath")),
                        string(m.get("classes")),
                        string(m.get("generatedSources")),
                        string(m.get("dependencyTree"))))
            .toList();
    JsonNode exit = json.get("mavenExitCode");
    return new BuildResult(
        json.get("repo").stringValue(),
        json.get("commit").stringValue(),
        BuildStatus.valueOf(json.get("status").stringValue()),
        string(json.get("failure")),
        json.get("jdk").intValue(),
        json.get("jdkSource").stringValue(),
        string(json.get("image")),
        exit == null || exit.isNull() ? null : exit.intValue(),
        Instant.parse(json.get("startedAt").stringValue()),
        Duration.parse(json.get("duration").stringValue()),
        string(json.get("effectivePom")),
        modules,
        directory,
        // Builds stored before ADR 0041 record neither.
        Set.copyOf(strings(json.get("upstream"))),
        strings(json.get("shadowed")));
  }

  private static String string(JsonNode node) {
    return node == null || node.isNull() ? null : node.stringValue();
  }

  private static List<String> strings(JsonNode array) {
    return array == null ? List.of() : array.valueStream().map(JsonNode::stringValue).toList();
  }
}
