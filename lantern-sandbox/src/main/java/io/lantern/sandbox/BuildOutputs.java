package io.lantern.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the outputs a build container handed back, laid out by {@code build.sh}: {@code
 * work/<module>/target/{classes,generated-sources,lantern}} for each module and {@code lib/} for
 * the jars on the classpaths. The content came out of the sandbox, so file sizes are bounded and
 * only regular files and directories are trusted.
 */
final class BuildOutputs {

  /** Where the build's modules are, mirroring the repository's layout. */
  static final String WORK = "work";

  /** Where classpath jars are, in the local repository's layout. */
  static final String LIB = "lib";

  /** The container paths that classpath entries start with. */
  static final String CONTAINER_WORK = "/work/";

  static final String CONTAINER_REPOSITORY = "/var/lantern/m2/";

  /** Where other repositories' artifacts are in the container, a tail of the local repository. */
  static final String CONTAINER_UPSTREAM = "/lantern/upstream/";

  private static final String METADATA = "target/lantern/";
  private static final String TREE = "dependency-tree.txt";
  private static final String CLASSPATH = "classpath.txt";
  private static final String EFFECTIVE_POM = "target/lantern/effective-pom.xml";
  private static final long MAX_METADATA_BYTES = 64L * 1024 * 1024;
  private static final Pattern DIRECT_DEPENDENCY = Pattern.compile("^[+\\\\]- (\\S+)");

  private BuildOutputs() {}

  /** The modules in the outputs, ordered by path; rewrites their classpaths to output paths. */
  static List<BuildModule> modules(Path outputs) throws IOException {
    Path work = outputs.resolve(WORK);
    if (!Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<Path> trees;
    try (Stream<Path> paths = Files.walk(work)) {
      trees =
          paths
              .filter(p -> p.getFileName().toString().equals(TREE))
              .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
              .filter(p -> isModuleTree(slashes(work.relativize(p))))
              .toList();
    }
    record Parsed(String path, String[] gav, List<String> direct, List<String> classpath) {}
    List<Parsed> parsed = new ArrayList<>();
    for (Path tree : trees) {
      String relative = slashes(work.relativize(tree));
      String path = relative.substring(0, relative.length() - (METADATA + TREE).length());
      path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
      List<String> lines = read(tree).lines().filter(l -> !l.isBlank()).toList();
      if (lines.isEmpty()) {
        continue;
      }
      String[] gav = lines.getFirst().strip().split(":");
      if (gav.length < 4) {
        continue;
      }
      List<String> direct = new ArrayList<>();
      for (String line : lines.subList(1, lines.size())) {
        Matcher m = DIRECT_DEPENDENCY.matcher(line);
        if (m.find()) {
          String[] parts = m.group(1).split(":");
          if (parts.length >= 2) {
            direct.add(parts[0] + ":" + parts[1]);
          }
        }
      }
      Path classpathFile = tree.resolveSibling(CLASSPATH);
      List<String> classpath = List.of();
      if (Files.isRegularFile(classpathFile, LinkOption.NOFOLLOW_LINKS)) {
        classpath = classpath(outputs, read(classpathFile));
        Files.delete(classpathFile);
      }
      parsed.add(new Parsed(path, gav, direct, classpath));
    }
    parsed.sort(Comparator.comparing(Parsed::path));
    Map<String, String> pathByKey = new HashMap<>();
    for (Parsed p : parsed) {
      pathByKey.putIfAbsent(p.gav()[0] + ":" + p.gav()[1], p.path());
    }
    List<BuildModule> modules = new ArrayList<>();
    for (Parsed p : parsed) {
      List<String> dependsOn =
          p.direct().stream()
              .map(pathByKey::get)
              .filter(other -> other != null && !other.equals(p.path()))
              .distinct()
              .toList();
      String base = WORK + "/" + (p.path().isEmpty() ? "" : p.path() + "/") + "target/";
      String[] gav = p.gav();
      // groupId:artifactId:packaging:version, with a classifier before the version if present.
      modules.add(
          new BuildModule(
              p.path(),
              gav[0],
              gav[1],
              gav[2],
              gav[gav.length - 1],
              dependsOn,
              p.classpath(),
              directory(outputs, base + "classes"),
              directory(outputs, base + "generated-sources"),
              base + "lantern/" + TREE));
    }
    return modules;
  }

  private static boolean isModuleTree(String relative) {
    return relative.equals(METADATA + TREE) || relative.endsWith("/" + METADATA + TREE);
  }

  /** The effective POM's path relative to the outputs, or null if the build left none. */
  static String effectivePom(Path outputs) {
    String path = WORK + "/" + EFFECTIVE_POM;
    return Files.isRegularFile(outputs.resolve(path), LinkOption.NOFOLLOW_LINKS) ? path : null;
  }

  /**
   * Rewrites a classpath Maven wrote inside the container ({@code :}-separated container paths) to
   * paths relative to the outputs, dropping entries the outputs do not hold.
   */
  static List<String> classpath(Path outputs, String containerClasspath) {
    Set<String> entries = new LinkedHashSet<>();
    for (String entry : containerClasspath.strip().split(":")) {
      String base;
      String rest;
      if (entry.startsWith(CONTAINER_REPOSITORY)) {
        base = LIB;
        rest = entry.substring(CONTAINER_REPOSITORY.length());
      } else if (entry.startsWith(CONTAINER_UPSTREAM)) {
        base = LIB;
        rest = entry.substring(CONTAINER_UPSTREAM.length());
      } else if (entry.startsWith(CONTAINER_WORK)) {
        base = WORK;
        rest = entry.substring(CONTAINER_WORK.length());
      } else {
        continue;
      }
      // Each kind of entry must stay inside its own part of the outputs.
      Path root = outputs.resolve(base);
      Path resolved = root.resolve(rest).normalize();
      if (!resolved.startsWith(root)
          || resolved.equals(root)
          || !Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      entries.add(slashes(outputs.relativize(resolved)));
    }
    return List.copyOf(entries);
  }

  private static String directory(Path outputs, String relative) {
    return Files.isDirectory(outputs.resolve(relative), LinkOption.NOFOLLOW_LINKS)
        ? relative
        : null;
  }

  private static String read(Path file) throws IOException {
    if (Files.size(file) > MAX_METADATA_BYTES) {
      throw new IOException("Build metadata file too large: " + file);
    }
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  static String slashes(Path relative) {
    return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
  }
}
