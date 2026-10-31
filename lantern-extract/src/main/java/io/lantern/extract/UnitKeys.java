package io.lantern.extract;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.GitTree;
import io.lantern.sandbox.BuildModule;
import io.lantern.sandbox.BuildResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The cache keys of extraction units (ADR 0092): the SHA-256 of the canonical JSON of the
 * extractor, a digest of its code, the unit's inputs and the IDs of the required extractors' facts.
 * Git is asked once per repository and the code digested once per jar or class folder.
 */
final class UnitKeys {

  private static final Map<Path, String> CODE = new ConcurrentHashMap<>();

  private final Map<String, String> trees = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> blobs = new ConcurrentHashMap<>();
  private final Map<String, String> builds = new ConcurrentHashMap<>();

  /** The key of one unit of the extractor, given the facts of the extractors it requires. */
  String key(
      Extractor extractor, ExtractionUnit unit, List<RepoBuild> snapshot, List<Fact> required) {
    ObjectNode key = FactJson.newObject();
    key.put("extractor", extractor.info().name());
    key.put("version", extractor.info().version());
    key.put("code", code());
    ArrayNode repos = key.putArray("repos");
    // A snapshot unit is keyed on every repository of the snapshot, which its extractor can read
    // through the context, not only those it applies to.
    List<RepoBuild> keyed = extractor.unit() == CacheUnit.SNAPSHOT ? snapshot : unit.repos();
    for (RepoBuild repo : keyed) {
      ObjectNode node = repos.addObject();
      node.put("name", repo.repo().name());
      if (unit.path() != null) {
        node.put("path", unit.path());
        node.put("blob", blob(repo, unit.path()));
      } else {
        node.put("tree", tree(repo));
        node.put("build", build(repo));
      }
    }
    if (extractor.unit() == CacheUnit.REPOSITORY) {
      // What the extractor reads of the other repositories (ADR 0092, amendment for #547).
      ArrayNode elsewhere = key.putArray("elsewhere");
      for (RepoBuild other : snapshot) {
        if (unit.repos().contains(other)) {
          continue;
        }
        ObjectNode node = elsewhere.addObject();
        node.put("name", other.repo().name());
        ArrayNode files = node.putArray("files");
        new java.util.TreeMap<>(blobs(other))
            .forEach(
                (path, blob) -> {
                  if (extractor.readsElsewhere(path)) {
                    files.add(path + "\0" + blob);
                  } else if (extractor.readsPathElsewhere(path)) {
                    files.add(path);
                  }
                });
        if (extractor.readsOtherBuilds()) {
          node.put("build", build(other));
        }
      }
    }
    ArrayNode ids = key.putArray("required");
    required.stream().map(Fact::id).sorted().distinct().forEach(ids::add);
    return sha256(key.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** The repository's git tree at its commit: unlike the commit, unchanged by an empty commit. */
  private String tree(RepoBuild repo) {
    return trees.computeIfAbsent(
        repo.repo().name() + "@" + repo.commit(),
        k -> {
          Git.Result result = Git.run(repo.repo().path(), "rev-parse", repo.commit() + "^{tree}");
          if (!result.ok()) {
            throw new IllegalStateException(
                "git rev-parse failed in " + repo.repo().name() + ": " + result.err());
          }
          return result.out().strip();
        });
  }

  /** The file's blob at the repository's commit, or a hash of its absence. */
  private String blob(RepoBuild repo, String path) {
    return blobs(repo).getOrDefault(path, "none");
  }

  /** Every file's blob at the repository's commit, by path. */
  private Map<String, String> blobs(RepoBuild repo) {
    return blobs.computeIfAbsent(
        repo.repo().name() + "@" + repo.commit(),
        k ->
            GitTree.blobs(repo.repo().path(), repo.commit())
                .orElseThrow(
                    () ->
                        new IllegalStateException("git ls-tree failed in " + repo.repo().name())));
  }

  /**
   * The build's fingerprint: what extraction reads of it. Its status, JDK and image, the effective
   * POM's content, and each module's coordinates, dependencies, classpath, dependency tree and the
   * content of its generated sources. The compiled classes, which change with every build, are left
   * out.
   */
  private String build(RepoBuild repo) {
    BuildResult build = repo.build();
    if (build == null) {
      return "none";
    }
    return builds.computeIfAbsent(
        repo.repo().name() + "@" + repo.commit(),
        k -> {
          ObjectNode node = FactJson.newObject();
          node.put("status", String.valueOf(build.status()));
          node.put("jdk", build.jdk());
          node.put("image", build.image());
          node.put("effectivePom", fileDigest(resolve(build, build.effectivePom())));
          ArrayNode modules = node.putArray("modules");
          for (BuildModule module : build.modules()) {
            ObjectNode m = modules.addObject();
            m.put("path", module.path());
            m.put(
                "coordinates",
                module.groupId() + ":" + module.artifactId() + ":" + module.version());
            m.put("packaging", module.packaging());
            module.dependsOn().forEach(m.putArray("dependsOn")::add);
            module.classpath().forEach(m.putArray("classpath")::add);
            m.put("dependencyTree", module.dependencyTree());
            m.put("generatedSources", treeDigest(resolve(build, module.generatedSources())));
          }
          ArrayNode upstream = node.putArray("upstream");
          build.upstream().stream().sorted().forEach(upstream::add);
          return sha256(node.toString().getBytes(StandardCharsets.UTF_8));
        });
  }

  private static Path resolve(BuildResult build, String path) {
    if (path == null || build.directory() == null) {
      return null;
    }
    return build.directory().resolve(path);
  }

  /**
   * A digest of the code extraction runs: the JDK's version and every entry of the class path, a
   * class folder by its content and a jar by its path, size and modification time. Under {@code
   * java -jar} the class path is the one application jar, holding every module and library, so any
   * change to Lantern's code or its libraries changes the key, not only a change to an extractor.
   */
  static String code() {
    return CODE.computeIfAbsent(
        Path.of(""),
        k -> {
          StringBuilder all = new StringBuilder(Runtime.version().toString()).append('\n');
          String classPath = System.getProperty("java.class.path", "");
          for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
              continue;
            }
            Path path = Path.of(entry);
            all.append(entry).append('\0');
            try {
              if (Files.isDirectory(path)) {
                all.append(treeDigest(path));
              } else if (Files.isRegularFile(path)) {
                all.append(Files.size(path)).append('\0').append(Files.getLastModifiedTime(path));
              }
            } catch (IOException | RuntimeException e) {
              all.append("unreadable");
            }
            all.append('\n');
          }
          return sha256(all.toString().getBytes(StandardCharsets.UTF_8));
        });
  }

  private static String fileDigest(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return "none";
    }
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536];
      for (int n; (n = in.read(buffer)) > 0; ) {
        digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A digest of every file under the folder, by relative path and content. */
  private static String treeDigest(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) {
      return "none";
    }
    StringBuilder all = new StringBuilder();
    try (Stream<Path> files = Files.walk(dir)) {
      files
          .filter(Files::isRegularFile)
          .map(f -> dir.relativize(f).toString().replace('\\', '/') + "\0" + fileDigest(f))
          .sorted()
          .forEach(line -> all.append(line).append('\n'));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sha256(all.toString().getBytes(StandardCharsets.UTF_8));
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
