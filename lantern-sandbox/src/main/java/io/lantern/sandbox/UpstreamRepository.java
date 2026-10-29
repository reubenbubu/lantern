package io.lantern.sandbox;

import io.lantern.core.repo.Git;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes the artifacts of other repositories' builds as a Maven repository, which a build gets as a
 * read-only tail of its local repository (ADR 0031). Each module gets its POM as written at the
 * build's commit, and each module that is not a {@code pom} gets a jar of its compiled classes, so
 * a build can use the libraries and parent POMs other repositories of the snapshot build without
 * them being installed into the shared Maven cache.
 */
final class UpstreamRepository {

  /** A group ID, artifact ID or version that can become part of a path. */
  private static final Pattern PART = Pattern.compile("[A-Za-z0-9_+-][A-Za-z0-9_.+-]*");

  private UpstreamRepository() {}

  /**
   * Writes the artifacts of the successful builds into the directory, in Maven repository layout.
   * When two builds have a module with the same coordinates, the first one's is used.
   *
   * @return notes for the build log: which builds and modules were given, and which not and why
   */
  static List<String> write(List<UpstreamBuild> upstream, Path into) throws IOException {
    List<String> notes = new ArrayList<>();
    Set<String> written = new HashSet<>();
    for (UpstreamBuild one : upstream) {
      BuildResult build = one.build();
      String name = build.repo() + "@" + build.commit();
      if (build.degraded()) {
        notes.add("not using the artifacts of " + name + ": its build failed");
        continue;
      }
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
        if (!module.packaging().equals("pom")) {
          Path classes = module.classes() == null ? null : build.resolve(module.classes());
          jar(classes, directory.resolve(base + ".jar"));
        }
        written.add(gav);
        given.add(gav);
      }
      notes.add("artifacts of " + name + ": " + (given.isEmpty() ? "none" : given));
    }
    return notes;
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
