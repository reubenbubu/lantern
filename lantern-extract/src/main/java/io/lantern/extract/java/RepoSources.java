package io.lantern.extract.java;

import io.lantern.core.fact.EntityId;
import io.lantern.core.repo.Git;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.ModuleStructure.SourceFile;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.sandbox.BuildModule;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The main Java sources of a repository's Maven modules at a commit, copied from git to a working
 * directory so that the compiler, the parser and Lombok (which reads {@code lombok.config} files
 * next to the sources) can read them, and the generated sources its build left.
 */
final class RepoSources {

  private static final String MAIN_JAVA = "src/main/java/";
  private static final String LOMBOK_CONFIG = "lombok.config";
  private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
  private static final long GIT_TIMEOUT_SECONDS = 120;

  /**
   * The sources of one module.
   *
   * @param directory the module's directory relative to the top of the repository, {@code ""} for
   *     the top
   * @param id the module's entity ID
   * @param build the module in the repository's build, or null if the build has no outputs for it
   * @param sourceRoot where the module's {@code src/main/java} was copied to
   * @param generatedRoots the build's generated source directories of the module
   * @param files the module's sources, then its generated sources, each ordered by path
   */
  record ModuleSources(
      String directory,
      EntityId id,
      BuildModule build,
      Path sourceRoot,
      List<Path> generatedRoots,
      List<SourceFile> files) {}

  final RepoBuild repo;
  final List<ModuleSources> modules;

  private RepoSources(RepoBuild repo, List<ModuleSources> modules) {
    this.repo = repo;
    this.modules = modules;
  }

  /** Copies the repository's sources at its commit into {@code target}, which must not exist. */
  static RepoSources read(RepoBuild repo, Path target) throws IOException {
    Map<String, EntityId> directories = MavenExtractor.moduleDirectories(repo);
    Map<String, BuildModule> built = new HashMap<>();
    if (repo.build() != null) {
      for (BuildModule m : repo.build().modules()) {
        built.put(m.path(), m);
      }
    }
    List<String> paths = list(repo);
    Map<String, List<String>> sources = new HashMap<>();
    List<String> wanted = new ArrayList<>();
    for (String path : paths) {
      String name = path.substring(path.lastIndexOf('/') + 1);
      if (name.equals(LOMBOK_CONFIG)) {
        wanted.add(path);
      }
    }
    for (String directory : directories.keySet()) {
      String prefix = (directory.isEmpty() ? "" : directory + "/") + MAIN_JAVA;
      List<String> files = new ArrayList<>();
      for (String path : paths) {
        if (path.startsWith(prefix)
            && path.endsWith(".java")
            && !path.endsWith("/module-info.java")) {
          files.add(path);
        }
      }
      sources.put(directory, files);
      wanted.addAll(files);
    }
    Files.createDirectories(target);
    Map<String, String> hashes = copy(repo, wanted, target);

    List<ModuleSources> modules = new ArrayList<>();
    for (Map.Entry<String, EntityId> entry : directories.entrySet()) {
      String directory = entry.getKey();
      BuildModule build = built.get(directory);
      EntityId id = entry.getValue();
      if (build != null) {
        id = new EntityId(BuiltInTypes.MODULE, build.groupId() + ":" + build.artifactId());
      }
      List<SourceFile> files = new ArrayList<>();
      for (String path : sources.get(directory)) {
        String hash = hashes.get(path);
        if (hash != null) {
          files.add(new SourceFile(path, target.resolve(path), hash));
        }
      }
      List<Path> generatedRoots = new ArrayList<>();
      if (build != null && build.generatedSources() != null) {
        generated(repo, build.generatedSources(), generatedRoots, files);
      }
      if (!files.isEmpty()) {
        String prefix = (directory.isEmpty() ? "" : directory + "/") + MAIN_JAVA;
        modules.add(
            new ModuleSources(
                directory,
                id,
                build,
                target.resolve(prefix),
                List.copyOf(generatedRoots),
                List.copyOf(files)));
      }
    }
    return new RepoSources(repo, List.copyOf(modules));
  }

  /** The generated sources of a module: each directory under generated-sources is a root. */
  private static void generated(
      RepoBuild repo, String relative, List<Path> roots, List<SourceFile> files)
      throws IOException {
    Path build = repo.build().directory().toAbsolutePath().normalize();
    Path work = build.resolve("work");
    Path dir = build.resolve(relative).normalize();
    if (!dir.startsWith(build) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    List<Path> found;
    try (Stream<Path> children = Files.list(dir)) {
      found =
          children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
    }
    for (Path root : found) {
      roots.add(root);
      List<Path> javaFiles;
      try (Stream<Path> walk = Files.walk(root)) {
        javaFiles =
            walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                .sorted()
                .toList();
      }
      for (Path file : javaFiles) {
        String path = work.relativize(file).toString().replace('\\', '/');
        files.add(new SourceFile(path, file, sha256(Files.readAllBytes(file))));
      }
    }
  }

  /** Every file path at the commit. */
  private static List<String> list(RepoBuild repo) {
    Git.Result result =
        Git.run(
            repo.repo().path(), "ls-tree", "-r", "-z", "--full-tree", "--name-only", repo.commit());
    if (!result.ok()) {
      throw new IllegalStateException(
          "Cannot list the files of " + repo.repo().name() + ": " + result.err());
    }
    return Arrays.stream(result.out().split("\0"))
        .filter(p -> !p.isEmpty() && p.indexOf('\n') < 0)
        .sorted()
        .toList();
  }

  /**
   * Copies the files from the commit to the target with one {@code git cat-file --batch}, and
   * returns their content hashes. Files over 16 MiB are left out.
   */
  private static Map<String, String> copy(RepoBuild repo, List<String> paths, Path target)
      throws IOException {
    Map<String, String> hashes = new HashMap<>();
    if (paths.isEmpty()) {
      return hashes;
    }
    Process process = Git.command(repo.repo().path(), "cat-file", "--batch").start();
    try {
      CompletableFuture<Void> requests =
          CompletableFuture.runAsync(
              () -> {
                try (OutputStream out = process.getOutputStream()) {
                  for (String path : paths) {
                    out.write((repo.commit() + ":" + path + "\n").getBytes(StandardCharsets.UTF_8));
                  }
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              },
              Thread.ofVirtual()::start);
      CompletableFuture<byte[]> errors =
          CompletableFuture.supplyAsync(
              () -> {
                try (InputStream err = process.getErrorStream()) {
                  return err.readAllBytes();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              },
              Thread.ofVirtual()::start);
      try (InputStream in = new BufferedInputStream(process.getInputStream())) {
        for (String path : paths) {
          String header = line(in);
          if (header == null) {
            throw new IOException("git cat-file ended early in " + repo.repo().name());
          }
          String[] parts = header.split(" ");
          if (parts.length != 3 || !parts[1].equals("blob")) {
            continue; // "<name> missing", or not a file
          }
          long size = Long.parseLong(parts[2]);
          if (size > MAX_FILE_BYTES) {
            in.skipNBytes(size + 1);
            continue;
          }
          byte[] content = in.readNBytes((int) size);
          if (content.length != size || in.read() != '\n') {
            throw new IOException("Truncated output from git cat-file for " + path);
          }
          Path file = target.resolve(path).normalize();
          if (!file.startsWith(target)) {
            continue;
          }
          Files.createDirectories(file.getParent());
          Files.write(file, content);
          hashes.put(path, sha256(content));
        }
      }
      requests.join();
      if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("git cat-file timed out in " + repo.repo().name());
      }
      errors.join();
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "git cat-file failed in "
                + repo.repo().name()
                + ": "
                + new String(errors.join(), StandardCharsets.UTF_8));
      }
      return hashes;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running git", e);
    } finally {
      process.destroyForcibly();
    }
  }

  private static String line(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != '\n') {
      if (b < 0) {
        return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
      }
      bytes.write(b);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
