package io.lantern.extract.java;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.ModuleStructure.Call;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import io.lantern.extract.java.RepoSources.ModuleSources;
import io.lantern.sandbox.BuildResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Turns Java sources into facts: packages, types, their members and supertypes, and the calls
 * between methods of the analyzed code. See {@code docs/adr/0007-java-structure-extractor.md}.
 *
 * <p>A module whose repository built in the sandbox is read through javac with the build's
 * classpath and Lombok, so the facts show the code as the compiler sees it (confidence 1.0). A
 * module without a successful build, or one javac reports errors for, is parsed with JavaParser
 * instead (degraded mode, confidence 0.7).
 *
 * <p>The extractor works on all repositories of a snapshot at once, because a call is kept only
 * when it reaches a method declared in the analyzed sources, which may be in another repository.
 *
 * <p>Entity IDs: {@code package:<groupId>:<artifactId>:<package>} for a package of a module, {@code
 * type:<binary name>} for a type, {@code method:<type>#<name>(<erased parameter types>)} for a
 * method or constructor ({@code <init>}), and {@code field:<type>#<name>} for a field or enum
 * constant.
 */
public final class JavaExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "java";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "java-extractor";

  /** Entity type of a method or constructor. */
  public static final String METHOD = ModuleStructure.METHOD;

  /** Entity type of a field or enum constant. */
  public static final String FIELD = ModuleStructure.FIELD;

  /** Attribute: what sort of type the subject is (class, interface, enum, record, annotation). */
  public static final String KIND = "kind";

  /** Confidence of facts read through javac. */
  static final double COMPILED_CONFIDENCE = 1.0;

  /** Confidence of facts read by parsing, in degraded mode. */
  static final double PARSED_CONFIDENCE = 0.7;

  private static final String RESOURCE = "/vocabulary/java-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = JavaExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Extracts the facts of a snapshot's repositories.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   * @throws UncheckedIOException if the sources cannot be read or copied
   * @throws IllegalStateException if git fails or times out, or Lantern runs without a compiler
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(analyze(repos), snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link #analyze}.
   *
   * @param analysis the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   */
  public List<Fact> extract(JavaAnalysis analysis, long snapshot) {
    Objects.requireNonNull(analysis, "analysis");
    Facts facts = new Facts(Validity.from(snapshot));
    for (JavaAnalysis.Module module : analysis.modules()) {
      facts.add(module.repo(), module.id(), module.structure(), module.confidence());
    }
    return facts.result();
  }

  /**
   * Reads the code of a snapshot's repositories: through javac for each module whose repository
   * built, else by parsing. Other extractors use the result so the code is read once.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @return the code, by module
   * @throws UncheckedIOException if the sources cannot be read or copied
   * @throws IllegalStateException if git fails or times out, or Lantern runs without a compiler
   */
  public static JavaAnalysis analyze(List<RepoBuild> repos) {
    Objects.requireNonNull(repos, "repos");
    List<RepoBuild> sorted =
        repos.stream()
            .sorted(
                Comparator.comparing((RepoBuild r) -> r.repo().name())
                    .thenComparing(RepoBuild::commit))
            .toList();
    Path work = null;
    try {
      work = Files.createTempDirectory("lantern-java-");
      // Lombok looks for lombok.config files up from each source; stop it above the copies.
      Files.writeString(work.resolve("lombok.config"), "config.stopBubbling = true\n");
      List<RepoSources> sources = new ArrayList<>();
      for (int i = 0; i < sorted.size(); i++) {
        sources.add(RepoSources.read(sorted.get(i), work.resolve(Integer.toString(i))));
      }
      List<JavaAnalysis.Module> modules = new ArrayList<>();
      for (RepoSources repo : sources) {
        for (ModuleSources module : repo.modules) {
          modules.add(analyze(repo, module, sources));
        }
      }
      delete(work);
      return new JavaAnalysis(modules);
    } catch (IOException e) {
      UncheckedIOException failure = new UncheckedIOException("Cannot read the Java sources", e);
      deleteAfterFailure(work, failure);
      throw failure;
    } catch (RuntimeException | Error e) {
      deleteAfterFailure(work, e);
      throw e;
    }
  }

  private static JavaAnalysis.Module analyze(
      RepoSources repo, ModuleSources module, List<RepoSources> all) throws IOException {
    BuildResult build = repo.repo.build();
    ModuleStructure structure = null;
    if (build != null && !build.degraded() && module.build() != null) {
      List<Path> classpath = new ArrayList<>();
      for (String entry : module.build().classpath()) {
        Path path = build.resolve(entry);
        if (Files.exists(path)) {
          classpath.add(path);
        }
      }
      structure = JavacAnalyzer.analyze(module.files(), classpath, release(build.jdk()));
    }
    boolean compiled = structure != null;
    if (!compiled) {
      structure =
          new JavaParserAnalyzer(sourceRoots(repo, all), jars(repo, module))
              .analyze(module.files());
    }
    return new JavaAnalysis.Module(
        repo.repo,
        module.directory(),
        module.id(),
        compiled,
        compiled ? COMPILED_CONFIDENCE : PARSED_CONFIDENCE,
        structure);
  }

  /** The Java release to compile for: the build's JDK if this JDK can compile for it. */
  private static int release(int jdk) {
    return jdk >= 8 && jdk <= Runtime.version().feature() ? jdk : 0;
  }

  /** Every source root of the snapshot, the repository's own first. */
  private static List<Path> sourceRoots(RepoSources own, List<RepoSources> all) {
    List<Path> roots = new ArrayList<>();
    addRoots(own, roots);
    for (RepoSources other : all) {
      if (other != own) {
        addRoots(other, roots);
      }
    }
    return roots;
  }

  private static void addRoots(RepoSources repo, List<Path> roots) {
    for (ModuleSources m : repo.modules) {
      roots.add(m.sourceRoot());
      roots.addAll(m.generatedRoots());
    }
  }

  /** The jars on the module's classpath, if its failed build got as far as resolving them. */
  private static List<Path> jars(RepoSources repo, ModuleSources module) {
    if (module.build() == null) {
      return List.of();
    }
    BuildResult build = repo.repo.build();
    return module.build().classpath().stream()
        .filter(e -> e.endsWith(".jar"))
        .map(build::resolve)
        .toList();
  }

  /** Deletes the working directory without hiding the failure that ended the extraction. */
  private static void deleteAfterFailure(Path work, Throwable failure) {
    if (work == null) {
      return;
    }
    try {
      delete(work);
    } catch (RuntimeException e) {
      failure.addSuppressed(e);
    }
  }

  private static void delete(Path root) {
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot delete " + root, e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Facts

  /** The facts of a whole extraction, and the calls kept until every module is read. */
  private static final class Facts {

    /**
     * Types whose methods every class, enum, record or exception inherits: calls to them through a
     * type of the sources are not kept.
     */
    private static final Set<String> UNIVERSAL_SUPERTYPES =
        Set.of("java.lang.Object", "java.lang.Enum", "java.lang.Record", "java.lang.Throwable");

    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final Set<EntityId> methods = new HashSet<>();
    private final Set<String> types = new HashSet<>();
    private final List<PendingCall> calls = new ArrayList<>();

    /** Per module and package, the evidence of the package with the smallest path. */
    private final Map<List<Object>, PackageFact> packages = new HashMap<>();

    private record PendingCall(Call call, RepoBuild repo, double confidence) {}

    private record PackageFact(
        EntityId module, EntityId pkg, RepoBuild repo, String path, double confidence) {}

    Facts(Validity validity) {
      this.validity = validity;
    }

    void add(RepoBuild repo, EntityId moduleId, ModuleStructure structure, double confidence) {
      String moduleKey = moduleId.key();
      for (TypeDecl t : structure.types) {
        EntityId type = ModuleStructure.typeId(t.binaryName());
        if (type == null) {
          continue;
        }
        types.add(t.binaryName());
        EntityId container;
        if (t.outer() != null) {
          container = ModuleStructure.typeId(t.outer());
        } else if (t.packageName().isEmpty()) {
          container = moduleId;
        } else {
          container = new EntityId(BuiltInTypes.PACKAGE, moduleKey + ":" + t.packageName());
          String path = t.where().file().path();
          String directory = path.substring(0, Math.max(0, path.lastIndexOf('/')));
          packages.merge(
              List.of(moduleId, container, repo),
              new PackageFact(moduleId, container, repo, directory, confidence),
              (a, b) -> a.path().compareTo(b.path()) <= 0 ? a : b);
        }
        if (container != null) {
          fact(
              container,
              BuiltInTypes.CONTAINS,
              FactObject.entity(type),
              repo,
              t.where(),
              confidence);
        }
        fact(type, KIND, FactObject.string(t.kind()), repo, t.where(), confidence);
        for (Supertype s : t.supertypes()) {
          EntityId supertype = ModuleStructure.typeId(s.binaryName());
          if (supertype != null) {
            fact(type, s.predicate(), FactObject.entity(supertype), repo, s.where(), confidence);
          }
        }
        for (Member m : t.members()) {
          fact(type, BuiltInTypes.CONTAINS, FactObject.entity(m.id()), repo, m.where(), confidence);
          if (m.id().type().equals(METHOD)) {
            methods.add(m.id());
          }
        }
      }
      for (Call c : structure.calls) {
        calls.add(new PendingCall(c, repo, confidence));
      }
    }

    List<Fact> result() {
      for (PackageFact p : packages.values()) {
        Evidence evidence =
            new Evidence(
                p.repo().repo().name(),
                p.repo().commit(),
                p.path().isEmpty() ? null : p.path(),
                null,
                null);
        put(
            p.module(),
            BuiltInTypes.CONTAINS,
            FactObject.entity(p.pkg()),
            evidence,
            p.confidence());
      }
      // One fact per caller and callee, with the first call as evidence.
      Map<List<EntityId>, PendingCall> first = new HashMap<>();
      Comparator<PendingCall> order =
          Comparator.comparing((PendingCall p) -> p.repo().repo().name())
              .thenComparing(p -> p.call().where().file().path())
              .thenComparingInt(p -> p.call().where().start())
              .thenComparingInt(p -> p.call().where().end());
      for (PendingCall p : calls) {
        EntityId callee = callee(p.call());
        if (callee != null) {
          first.merge(
              List.of(p.call().caller(), callee), p, (a, b) -> order.compare(a, b) <= 0 ? a : b);
        }
      }
      for (Map.Entry<List<EntityId>, PendingCall> e : first.entrySet()) {
        PendingCall p = e.getValue();
        fact(
            p.call().caller(),
            BuiltInTypes.CALLS,
            FactObject.entity(e.getKey().get(1)),
            p.repo(),
            p.call().where(),
            p.confidence());
      }
      return facts.values().stream()
          .sorted(
              Comparator.comparing((Fact f) -> f.subject().toString())
                  .thenComparing(Fact::predicate)
                  .thenComparing(f -> f.object().value())
                  .thenComparing(Fact::id))
          .toList();
    }

    /**
     * What a call is recorded against: the method called when the sources declare it; when it is
     * inherited from a type outside them (not {@link #UNIVERSAL_SUPERTYPES}) and the call is
     * written on a type the sources declare, the method as a member of that type, as in {@code
     * orders.save(o)} on a Spring Data repository; otherwise null.
     */
    private EntityId callee(Call call) {
      EntityId callee = call.callee();
      if (methods.contains(callee)) {
        return callee;
      }
      String declaring = callee.key().substring(0, Math.max(callee.key().indexOf('#'), 0));
      if (call.receiver() == null
          || !types.contains(call.receiver())
          || types.contains(declaring)
          || UNIVERSAL_SUPERTYPES.contains(declaring)) {
        return null;
      }
      return call.calleeOfReceiver();
    }

    private void fact(
        EntityId subject,
        String predicate,
        FactObject object,
        RepoBuild repo,
        Location where,
        double confidence) {
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              where.file().path(),
              where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
              where.file().contentHash());
      put(subject, predicate, object, evidence, confidence);
    }

    private void put(
        EntityId subject,
        String predicate,
        FactObject object,
        Evidence evidence,
        double confidence) {
      Fact fact =
          Fact.create(
              subject,
              predicate,
              object,
              FactJson.newObject(),
              ORIGIN,
              evidence,
              confidence,
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }
}
