package io.lantern.sandbox;

import io.lantern.core.repo.Git;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes the artifacts of other repositories' builds as a Maven repository, which a build gets as a
 * read-only tail of its local repository (ADR 0041). Each module gets its POM as written at the
 * build's commit, and each module that is not a {@code pom} gets a jar of its compiled classes, so
 * a build can use the libraries and parent POMs other repositories of the snapshot build without
 * them being installed into the shared Maven cache.
 */
final class UpstreamRepository {

  /** A group ID, artifact ID or version that can become part of a path. */
  private static final Pattern PART = Pattern.compile("[A-Za-z0-9_+-][A-Za-z0-9_.+-]*");

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

  private UpstreamRepository() {}

  /**
   * What was written.
   *
   * @param notes for the build log: which builds and modules were given, and which not and why
   * @param builds the successful builds given, as {@code repo@commit}
   * @param artifacts the paths of the written artifacts' files in Maven repository layout, by
   *     {@code groupId:artifactId:version}
   */
  record Written(List<String> notes, Set<String> builds, Map<String, List<String>> artifacts) {}

  /**
   * Writes the artifacts of the successful builds into the directory, in Maven repository layout.
   * When two builds have a module with the same coordinates, the first one's is used. A {@code
   * -SNAPSHOT} version also gets the metadata Maven writes when it installs one locally, updated
   * now, so Maven takes it as newer than a snapshot of the same version in a remote repository.
   */
  static Written write(List<UpstreamBuild> upstream, Path into, Instant now) throws IOException {
    List<String> notes = new ArrayList<>();
    Set<String> builds = new TreeSet<>();
    Map<String, List<String>> artifacts = new LinkedHashMap<>();
    Set<String> written = new HashSet<>();
    for (UpstreamBuild one : upstream) {
      BuildResult build = one.build();
      String name = build.repo() + "@" + build.commit();
      if (build.degraded()) {
        notes.add("not using the artifacts of " + name + ": its build failed");
        continue;
      }
      builds.add(name);
      List<String> given = new ArrayList<>();
      for (BuildModule module : build.modules()) {
        String gav = module.groupId() + ":" + module.artifactId() + ":" + module.version();
        if (!PART.matcher(module.groupId()).matches()
            || !PART.matcher(module.artifactId()).matches()
            || !PART.matcher(module.version()).matches()
            || gav.contains("..")) {
          notes.add("not using " + gav + " of " + name + ": not a plain Maven coordinate");
          continue;
        }
        if (written.contains(gav)) {
          notes.add("not using " + gav + " of " + name + ": an earlier repository builds it");
          continue;
        }
        String pomPath = module.path().isEmpty() ? "pom.xml" : module.path() + "/pom.xml";
        Git.Result pom = Git.run(one.repo().path(), "show", build.commit() + ":" + pomPath);
        if (!pom.ok()) {
          notes.add("not using " + gav + " of " + name + ": cannot read " + pomPath);
          continue;
        }
        Path directory =
            into.resolve(module.groupId().replace('.', '/'))
                .resolve(module.artifactId())
                .resolve(module.version());
        Files.createDirectories(directory);
        String base = module.artifactId() + "-" + module.version();
        Files.writeString(directory.resolve(base + ".pom"), pom.out(), StandardCharsets.UTF_8);
        String layout =
            module.groupId().replace('.', '/')
                + "/"
                + module.artifactId()
                + "/"
                + module.version()
                + "/";
        List<String> files = new ArrayList<>(List.of(layout + base + ".pom"));
        List<String> extensions = new ArrayList<>(List.of("pom"));
        if (!module.packaging().equals("pom")) {
          Path classes = module.classes() == null ? null : build.resolve(module.classes());
          jar(classes, directory.resolve(base + ".jar"));
          files.add(layout + base + ".jar");
          extensions.add("jar");
        }
        if (module.version().endsWith("-SNAPSHOT")) {
          Files.writeString(
              directory.resolve("maven-metadata-local.xml"),
              snapshotMetadata(module, extensions, now),
              StandardCharsets.UTF_8);
        }
        artifacts.put(gav, List.copyOf(files));
        written.add(gav);
        given.add(gav);
      }
      notes.add("artifacts of " + name + ": " + (given.isEmpty() ? "none" : given));
    }
    return new Written(List.copyOf(notes), builds, artifacts);
  }

  /** The metadata of a locally installed snapshot, as Maven writes it, updated at the time. */
  static String snapshotMetadata(BuildModule module, List<String> extensions, Instant now) {
    String updated = TIMESTAMP.format(now);
    StringBuilder versions = new StringBuilder();
    for (String extension : extensions) {
      versions
          .append("      <snapshotVersion>\n")
          .append("        <extension>")
          .append(extension)
          .append("</extension>\n")
          .append("        <value>")
          .append(module.version())
          .append("</value>\n")
          .append("        <updated>")
          .append(updated)
          .append("</updated>\n")
          .append("      </snapshotVersion>\n");
    }
    // The coordinates are plain path parts (checked above), so they need no escaping.
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata modelVersion="1.1.0">
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>
          <versioning>
            <snapshot>
              <localCopy>true</localCopy>
            </snapshot>
            <lastUpdated>%s</lastUpdated>
            <snapshotVersions>
        %s    </snapshotVersions>
          </versioning>
        </metadata>
        """
        .formatted(
            module.groupId(), module.artifactId(), module.version(), updated, versions.toString());
  }

  /** A jar of the class directory's regular files, empty but for the manifest if there is none. */
  private static void jar(Path classes, Path jar) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file, manifest)) {
      if (classes == null || !Files.isDirectory(classes, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      List<Path> paths;
      try (Stream<Path> walk = Files.walk(classes)) {
        paths = walk.filter(p -> !p.equals(classes)).sorted(Comparator.naturalOrder()).toList();
      }
      for (Path path : paths) {
        String entry = BuildOutputs.slashes(classes.relativize(path));
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          out.putNextEntry(new JarEntry(entry + "/"));
          out.closeEntry();
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && !entry.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
          out.putNextEntry(new JarEntry(entry));
          Files.copy(path, out);
          out.closeEntry();
        }
      }
    }
  }
}
