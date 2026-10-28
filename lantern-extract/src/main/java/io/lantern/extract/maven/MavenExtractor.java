package io.lantern.extract.maven;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.repo.Git;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns Maven build metadata into facts: modules, the artifacts they depend on (internal or
 * third-party), whether each builds something deployable or a library, and which library a thin
 * service packages as its main code. See {@code docs/adr/0006-maven-extractor.md}.
 *
 * <p>The extractor works on all repositories of a snapshot at once, because whether a dependency is
 * internal depends on which repositories are analyzed. Each module is read from the effective POM
 * its sandbox build left; a module without one (no build, or a build that could not even read the
 * POM) is read from its POM as written, with lower confidence. Modules are found by following
 * {@code <modules>} from the repository's top {@code pom.xml} at the commit.
 *
 * <p>Entity IDs use Maven coordinates without the version, so they are stable across releases:
 * {@code module:<groupId>:<artifactId>} for a module, {@code service:...} or {@code library:...}
 * for what it builds, and {@code artifact:...} for a dependency no analyzed repository builds.
 */
public final class MavenExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "maven";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "maven-extractor";

  /** Entity type of an artifact that no analyzed repository builds. */
  public static final String ARTIFACT = "artifact";

  /** Relationship: a module declares a Maven dependency on a module or artifact. */
  public static final String BUILD_DEPENDS_ON = "BUILD_DEPENDS_ON";

  /** Relationship: a service packages a library as its main code. */
  public static final String PACKAGES = "PACKAGES";

  /** Attribute: the version a module's POM declares. */
  public static final String VERSION_ATTRIBUTE = "version";

  /** Attribute: a module's Maven packaging. */
  public static final String PACKAGING = "packaging";

  /** Confidence of facts read from an effective POM. */
  static final double EFFECTIVE_CONFIDENCE = 1.0;

  /** Confidence of facts read from a POM as written, without the build's effective POM. */
  static final double WRITTEN_CONFIDENCE = 0.7;

  /** Confidence of a {@code PACKAGES} link relative to the module it starts from. */
  static final double PACKAGES_FACTOR = 0.8;

  private static final String RESOURCE = "/vocabulary/maven-types.json";
  private static final String POM = "pom.xml";
  private static final long MAX_POM_BYTES = 64L * 1024 * 1024;
  private static final long GIT_TIMEOUT_SECONDS = 30;
  private static final int MAX_ANCESTORS = 20;
  private static final Pattern NAME_SEPARATOR = Pattern.compile("[.\\-_]");

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = MavenExtractor.class.getResourceAsStream(RESOURCE)) {
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
   * @throws UncheckedIOException if git or a stored build output cannot be read
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    Objects.requireNonNull(repos, "repos");
    Validity validity = Validity.from(snapshot);
    List<Written> written = new ArrayList<>();
    Map<RepoBuild, Map<String, Pom>> effective = new HashMap<>();
    for (RepoBuild repo : repos) {
      written.addAll(discover(repo));
      effective.put(repo, effectiveProjects(repo));
    }
    Map<String, Written> byCoordinates = new HashMap<>();
    for (Written w : written) {
      byCoordinates.putIfAbsent(w.coordinates(), w);
    }
    List<Module> modules = new ArrayList<>();
    // Written and Module are compared by identity: they are records only for brevity.
    Map<Written, Module> moduleOf = new IdentityHashMap<>();
    for (Written w : written) {
      Module module = resolve(w, effective.get(w.repo()), byCoordinates);
      if (module != null) {
        modules.add(module);
        moduleOf.put(w, module);
      }
    }
    Map<String, Module> internal = new HashMap<>();
    for (Module m : modules) {
      internal.putIfAbsent(m.key(), m);
    }

    Map<String, Fact> facts = new LinkedHashMap<>();
    for (Module m : modules) {
      EntityId id = m.id();
      Evidence pom = m.evidence(null);
      XmlElement xml = m.written().pom().xml();
      if (m.version() != null) {
        XmlElement declared =
            xml.child("version")
                .or(() -> xml.child("parent").flatMap(p -> p.child("version")))
                .orElse(null);
        add(
            facts,
            id,
            VERSION_ATTRIBUTE,
            FactObject.string(m.version()),
            m.evidence(declared),
            m,
            validity);
      }
      add(
          facts,
          id,
          PACKAGING,
          FactObject.string(m.packaging()),
          m.evidence(xml.child("packaging").orElse(null)),
          m,
          validity);
      EntityId container = m.container();
      if (container != null) {
        add(
            facts,
            container,
            BuiltInTypes.CONTAINS,
            FactObject.entity(id),
            pom,
            m.confidence(),
            validity);
      }
      for (Written.Child child : m.written().children()) {
        Module sub = moduleOf.get(child.module());
        if (sub != null) {
          add(
              facts,
              id,
              BuiltInTypes.CONTAINS,
              FactObject.entity(sub.id()),
              m.evidence(child.element()),
              m.confidence(),
              validity);
        }
      }
      for (Dep dep : m.dependencies()) {
        Module target = internal.get(dep.key());
        EntityId object = target != null ? target.id() : entity(ARTIFACT, dep.key());
        if (object == null || object.equals(id)) {
          continue;
        }
        add(
            facts,
            id,
            BUILD_DEPENDS_ON,
            FactObject.entity(object),
            dep.attributes(),
            m.evidence(m.declaration(dep)),
            m.confidence(),
            validity);
      }
      Dep packaged = packagedLibrary(m, internal);
      if (packaged != null) {
        add(
            facts,
            container,
            PACKAGES,
            FactObject.entity(internal.get(packaged.key()).container()),
            FactJson.newObject(),
            m.evidence(m.declaration(packaged)),
            // Rounded, so the stored confidence reads as it was meant: 0.56, not 0.5599...
            Math.round(m.confidence() * PACKAGES_FACTOR * 100) / 100.0,
            validity);
      }
    }
    return facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // Finding the modules of a repository

  /** A POM as written in a repository, found by following {@code <modules>} from the top. */
  private record Written(
      RepoBuild repo,
      String directory,
      String path,
      String contentHash,
      Pom pom,
      List<Child> children) {

    /** A {@code <module>} entry and the POM it leads to. */
    record Child(XmlElement element, Written module) {}

    /** The coordinates as written, with the parent's group ID and version filled in. */
    String coordinates() {
      Map<String, String> own = new HashMap<>(pom.properties());
      projectProperties(own, pom);
      return Pom.interpolate(pom.effectiveGroupId(), own)
          + ":"
          + Pom.interpolate(pom.artifactId(), own)
          + ":"
          + Pom.interpolate(pom.effectiveVersion(), own);
    }

    String key() {
      String c = coordinates();
      return c.substring(0, c.lastIndexOf(':'));
    }
  }

  private List<Written> discover(RepoBuild repo) {
    List<Written> found = new ArrayList<>();
    Map<String, Written> byPath = new HashMap<>();
    Map<String, List<XmlElement>> pending = new HashMap<>();
    Deque<String> queue = new ArrayDeque<>(List.of(POM));
    Set<String> seen = new HashSet<>();
    while (!queue.isEmpty()) {
      String path = queue.poll();
      if (!seen.add(path)) {
        continue;
      }
      byte[] bytes = show(repo, path);
      if (bytes == null) {
        continue;
      }
      XmlElement xml;
      try {
        xml = XmlElement.parse(bytes);
      } catch (IOException e) {
        continue;
      }
      if (!xml.name().equals("project") || xml.text("artifactId") == null) {
        continue;
      }
      Pom pom = Pom.of(xml);
      int slash = path.lastIndexOf('/');
      String directory = slash < 0 ? "" : path.substring(0, slash);
      Written w = new Written(repo, directory, path, sha256(bytes), pom, new ArrayList<>());
      found.add(w);
      byPath.put(path, w);
      for (XmlElement module : pom.modules()) {
        String child = modulePom(directory, module.text());
        if (child != null) {
          pending.computeIfAbsent(path, p -> new ArrayList<>()).add(module);
          queue.add(child);
        }
      }
    }
    for (Written w : found) {
      for (XmlElement module : pending.getOrDefault(w.path(), List.of())) {
        Written child = byPath.get(modulePom(w.directory(), module.text()));
        if (child != null && child != w) {
          w.children().add(new Written.Child(module, child));
        }
      }
    }
    return found;
  }

  /**
   * The repository path of the POM a {@code <module>} entry names, or null if it is empty or leaves
   * the repository.
   */
  static String modulePom(String directory, String entry) {
    if (entry == null || entry.isBlank()) {
      return null;
    }
    String joined = (directory.isEmpty() ? "" : directory + "/") + entry.strip().replace('\\', '/');
    Deque<String> parts = new ArrayDeque<>();
    for (String part : joined.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (parts.isEmpty()) {
          return null;
        }
        parts.removeLast();
      } else {
        parts.addLast(part);
      }
    }
    if (parts.isEmpty()) {
      return null;
    }
    if (!parts.getLast().endsWith(".xml")) {
      parts.addLast(POM);
    }
    return String.join("/", parts);
  }

  /** The projects of the build's effective POM by {@code groupId:artifactId}; empty if none. */
  private static Map<String, Pom> effectiveProjects(RepoBuild repo) {
    if (repo.build() == null || repo.build().effectivePom() == null) {
      return Map.of();
    }
    Path file = repo.build().resolve(repo.build().effectivePom());
    XmlElement root;
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) > MAX_POM_BYTES) {
        return Map.of();
      }
      root = XmlElement.parse(Files.readAllBytes(file));
    } catch (IOException e) {
      return Map.of();
    }
    List<XmlElement> projects =
        switch (root.name()) {
          case "project" -> List.of(root);
          case "projects" -> root.children("project");
          default -> List.of();
        };
    Map<String, Pom> result = new HashMap<>();
    for (XmlElement project : projects) {
      Pom pom = Pom.of(project);
      result.putIfAbsent(pom.effectiveGroupId() + ":" + pom.artifactId(), pom);
    }
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // Resolving a module from its effective POM or its POM as written

  /** A dependency with its values resolved. */
  private record Dep(
      String groupId,
      String artifactId,
      String version,
      String type,
      String classifier,
      String scope,
      boolean optional) {

    String key() {
      return groupId + ":" + artifactId;
    }

    ObjectNode attributes() {
      ObjectNode a = FactJson.newObject();
      a.put("scope", scope);
      if (version != null) {
        a.put("version", version);
      }
      if (optional) {
        a.put("optional", true);
      }
      if (classifier != null) {
        a.put("classifier", classifier);
      }
      if (!type.equals("jar")) {
        a.put("artifactType", type);
      }
      return a;
    }
  }

  /** A module with everything the facts need resolved. */
  private record Module(
      Written written,
      String groupId,
      String artifactId,
      String version,
      String packaging,
      boolean deployable,
      List<Dep> dependencies,
      Map<String, String> properties,
      double confidence) {

    String key() {
      return groupId + ":" + artifactId;
    }

    EntityId id() {
      return new EntityId(BuiltInTypes.MODULE, key());
    }

    /** The service or library the module builds, or null for a {@code pom} module. */
    EntityId container() {
      if (packaging.equals("pom")) {
        return null;
      }
      return new EntityId(deployable ? BuiltInTypes.SERVICE : BuiltInTypes.LIBRARY, key());
    }

    /** Evidence pointing at the module's POM, at the element's lines if one is given. */
    Evidence evidence(XmlElement element) {
      RepoBuild repo = written.repo();
      return new Evidence(
          repo.repo().name(),
          repo.commit(),
          written.path(),
          element == null ? null : new Evidence.LineRange(element.startLine(), element.endLine()),
          written.contentHash());
    }

    /** The dependency's {@code <dependency>} element in the module's own POM, or null. */
    XmlElement declaration(Dep dep) {
      for (Pom.Dependency d : written.pom().dependencies()) {
        if (dep.groupId().equals(Pom.interpolate(d.groupId(), properties))
            && dep.artifactId().equals(Pom.interpolate(d.artifactId(), properties))
            && dep.type().equals(orDefault(Pom.interpolate(d.type(), properties), "jar"))
            && Objects.equals(dep.classifier(), blankToNull(interpolate(d.classifier())))) {
          return d.element();
        }
      }
      return null;
    }

    private String interpolate(String value) {
      return Pom.interpolate(value, properties);
    }
  }

  private static Module resolve(
      Written w, Map<String, Pom> effective, Map<String, Written> byCoordinates) {
    Pom pom = effective.get(w.key());
    if (pom != null) {
      return fromEffective(w, pom);
    }
    return fromWritten(w, byCoordinates);
  }

  private static Module fromEffective(Written w, Pom pom) {
    Map<String, String> properties = new HashMap<>(pom.properties());
    projectProperties(properties, pom);
    List<Dep> deps = new ArrayList<>();
    for (Pom.Dependency d : pom.dependencies()) {
      Dep dep = dep(d, null, properties);
      if (dep != null) {
        deps.add(dep);
      }
    }
    String packaging = pom.effectivePackaging();
    String groupId = pom.effectiveGroupId();
    if (entity(BuiltInTypes.MODULE, groupId + ":" + pom.artifactId()) == null) {
      return null;
    }
    return new Module(
        w,
        groupId,
        pom.artifactId(),
        Pom.interpolate(pom.effectiveVersion(), properties),
        packaging,
        pom.deployable(packaging, false),
        deps,
        writtenProperties(w, properties),
        EFFECTIVE_CONFIDENCE);
  }

  /**
   * Reads a module from its POM as written. Parents are taken from the analyzed repositories when
   * one there has exactly the coordinates the {@code <parent>} element names; a parent from
   * elsewhere is not read, so what it adds (properties, managed versions, plugins) is missing.
   */
  private static Module fromWritten(Written w, Map<String, Written> byCoordinates) {
    List<Pom> lineage = new ArrayList<>();
    lineage.add(w.pom());
    Set<Written> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    seen.add(w);
    Pom current = w.pom();
    while (current.parent() != null && lineage.size() < MAX_ANCESTORS) {
      Pom.Coordinates p = current.parent();
      Written parent = byCoordinates.get(p.groupId() + ":" + p.artifactId() + ":" + p.version());
      if (parent == null || !seen.add(parent)) {
        break;
      }
      lineage.add(parent.pom());
      current = parent.pom();
    }
    // Inherited first, so the module's own values override its ancestors'.
    Map<String, String> properties = new HashMap<>();
    Map<String, Pom.Dependency> declared = new LinkedHashMap<>();
    Map<String, Pom.Dependency> managed = new HashMap<>();
    List<XmlElement> plugins = new ArrayList<>();
    for (Pom pom : lineage.reversed()) {
      properties.putAll(pom.properties());
      pom.dependencies().forEach(d -> declared.put(d.managementKey(), d));
      pom.managedDependencies().forEach(d -> managed.put(d.managementKey(), d));
      plugins.addAll(pom.plugins());
    }
    Pom own = w.pom();
    projectProperties(properties, own);
    Map<String, Pom.Dependency> managedResolved = new HashMap<>();
    for (Pom.Dependency d : managed.values()) {
      Dep m = dep(d, null, properties);
      if (m != null) {
        managedResolved.put(managementKey(m), d);
      }
    }
    List<Dep> deps = new ArrayList<>();
    for (Pom.Dependency d : declared.values()) {
      Dep resolved = dep(d, null, properties);
      if (resolved == null) {
        continue;
      }
      Dep withManaged = dep(d, managedResolved.get(managementKey(resolved)), properties);
      deps.add(withManaged);
    }
    String groupId = Pom.interpolate(own.effectiveGroupId(), properties);
    String artifactId = Pom.interpolate(own.artifactId(), properties);
    if (groupId == null
        || artifactId == null
        || entity(BuiltInTypes.MODULE, groupId + ":" + artifactId) == null) {
      return null;
    }
    String packaging = orDefault(Pom.interpolate(own.effectivePackaging(), properties), "jar");
    Pom withPlugins =
        new Pom(
            own.xml(),
            own.parent(),
            own.groupId(),
            own.artifactId(),
            own.version(),
            own.packaging(),
            own.properties(),
            own.modules(),
            own.dependencies(),
            own.managedDependencies(),
            plugins);
    return new Module(
        w,
        groupId,
        artifactId,
        Pom.interpolate(own.effectiveVersion(), properties),
        packaging,
        withPlugins.deployable(packaging, true),
        deps,
        properties,
        WRITTEN_CONFIDENCE);
  }

  /**
   * Resolves a dependency, taking a missing version and scope from its managed entry if there is
   * one. Returns null for an import-scoped entry or one whose group or artifact cannot be resolved.
   */
  private static Dep dep(Pom.Dependency d, Pom.Dependency managed, Map<String, String> properties) {
    String groupId = Pom.interpolate(d.groupId(), properties);
    String artifactId = Pom.interpolate(d.artifactId(), properties);
    if (groupId == null || artifactId == null || groupId.isEmpty() || artifactId.isEmpty()) {
      return null;
    }
    String version = Pom.interpolate(d.version(), properties);
    String scope = Pom.interpolate(d.scope(), properties);
    if (managed != null) {
      if (version == null) {
        version = Pom.interpolate(managed.version(), properties);
      }
      if (scope == null) {
        scope = Pom.interpolate(managed.scope(), properties);
      }
    }
    scope = orDefault(scope, "compile");
    if (scope.equals("import")) {
      return null;
    }
    return new Dep(
        groupId,
        artifactId,
        blankToNull(version),
        orDefault(Pom.interpolate(d.type(), properties), "jar"),
        blankToNull(Pom.interpolate(d.classifier(), properties)),
        scope,
        "true".equals(Pom.interpolate(d.optional(), properties)));
  }

  private static String managementKey(Dep d) {
    return d.groupId()
        + ":"
        + d.artifactId()
        + ":"
        + d.type()
        + ":"
        + (d.classifier() == null ? "" : d.classifier());
  }

  /** Adds the {@code project.*} properties Maven defines for a project. */
  private static void projectProperties(Map<String, String> properties, Pom pom) {
    putIfNotNull(properties, "project.groupId", pom.effectiveGroupId());
    putIfNotNull(properties, "project.artifactId", pom.artifactId());
    putIfNotNull(properties, "project.version", pom.effectiveVersion());
    if (pom.parent() != null) {
      putIfNotNull(properties, "project.parent.groupId", pom.parent().groupId());
      putIfNotNull(properties, "project.parent.artifactId", pom.parent().artifactId());
      putIfNotNull(properties, "project.parent.version", pom.parent().version());
    }
  }

  /**
   * The properties to resolve references in the POM as written with, for finding where something is
   * declared: the written POM's own, then the effective POM's.
   */
  private static Map<String, String> writtenProperties(
      Written w, Map<String, String> effectiveProperties) {
    Map<String, String> properties = new HashMap<>(effectiveProperties);
    properties.putAll(w.pom().properties());
    projectProperties(properties, w.pom());
    return properties;
  }

  // ---------------------------------------------------------------------------------------------
  // Linking a thin service to its library

  /**
   * The library a deployable module packages as its main code, or null. It is the internal library
   * the module depends on directly at compile or runtime scope whose artifact ID shares the most
   * leading name parts with the module's (after any leading group ID), sharing at least one and
   * more than any other candidate, and which has at least as many main Java sources as the module.
   * The last condition keeps a service with code of its own from being linked to, say, its API
   * library.
   */
  private Dep packagedLibrary(Module m, Map<String, Module> internal) {
    if (!m.deployable() || m.container() == null) {
      return null;
    }
    List<String> own = nameParts(m.groupId(), m.artifactId());
    Dep best = null;
    int bestScore = 0;
    boolean tie = false;
    for (Dep dep : m.dependencies()) {
      Module target = internal.get(dep.key());
      if (target == null
          || target == m
          || target.deployable()
          || target.container() == null
          || dep.optional()
          || !dep.type().equals("jar")
          || !(dep.scope().equals("compile") || dep.scope().equals("runtime"))) {
        continue;
      }
      int score = sharedPrefix(own, nameParts(target.groupId(), target.artifactId()));
      if (score > bestScore) {
        best = dep;
        bestScore = score;
        tie = false;
      } else if (score == bestScore && best != null && !best.key().equals(dep.key())) {
        tie = true;
      }
    }
    if (best == null || tie) {
      return null;
    }
    Module library = internal.get(best.key());
    return mainSources(library) >= mainSources(m) ? best : null;
  }

  /** The parts of an artifact ID split at {@code . - _}, without a leading group ID. */
  static List<String> nameParts(String groupId, String artifactId) {
    String name = artifactId;
    if (name.startsWith(groupId + ".") || name.startsWith(groupId + "-")) {
      name = name.substring(groupId.length() + 1);
    }
    return List.of(NAME_SEPARATOR.split(name));
  }

  private static int sharedPrefix(List<String> a, List<String> b) {
    int n = 0;
    while (n < a.size() && n < b.size() && a.get(n).equals(b.get(n))) {
      n++;
    }
    return n;
  }

  private final Map<Module, Integer> sourceCounts = new IdentityHashMap<>();

  /** How many {@code .java} files the module has under {@code src/main/java} at the commit. */
  private int mainSources(Module m) {
    return sourceCounts.computeIfAbsent(
        m,
        key -> {
          Written w = key.written();
          String dir = (w.directory().isEmpty() ? "" : w.directory() + "/") + "src/main/java";
          Git.Result result =
              Git.run(
                  w.repo().repo().path(),
                  "ls-tree",
                  "-r",
                  "--name-only",
                  "--full-tree",
                  w.repo().commit(),
                  "--",
                  dir);
          if (!result.ok()) {
            throw new IllegalStateException(
                "Cannot list " + dir + " in " + w.repo().repo().name() + ": " + result.err());
          }
          return (int) result.out().lines().filter(l -> l.endsWith(".java")).count();
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  private static void add(
      Map<String, Fact> facts,
      EntityId subject,
      String predicate,
      FactObject object,
      Evidence evidence,
      Module m,
      Validity validity) {
    add(
        facts,
        subject,
        predicate,
        object,
        FactJson.newObject(),
        evidence,
        m.confidence(),
        validity);
  }

  private static void add(
      Map<String, Fact> facts,
      EntityId subject,
      String predicate,
      FactObject object,
      Evidence evidence,
      double confidence,
      Validity validity) {
    add(facts, subject, predicate, object, FactJson.newObject(), evidence, confidence, validity);
  }

  private static void add(
      Map<String, Fact> facts,
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      Evidence evidence,
      double confidence,
      Validity validity) {
    Fact fact =
        Fact.create(subject, predicate, object, attributes, ORIGIN, evidence, confidence, validity);
    facts.putIfAbsent(fact.id(), fact);
  }

  /** An entity ID, or null if the key is not a valid entity key. */
  private static EntityId entity(String type, String key) {
    try {
      return new EntityId(type, key);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The file's content at the commit, or null if the commit has no such file. */
  private static byte[] show(RepoBuild repo, String path) {
    ProcessBuilder builder =
        Git.command(repo.repo().path(), "cat-file", "blob", repo.commit() + ":" + path);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot run git; is it installed?", e);
    }
    try {
      process.getOutputStream().close();
      CompletableFuture<byte[]> err =
          CompletableFuture.supplyAsync(
              () -> {
                try (InputStream in = process.getErrorStream()) {
                  return in.readAllBytes();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              },
              Thread.ofVirtual()::start);
      byte[] out;
      try (InputStream in = process.getInputStream()) {
        out = in.readNBytes((int) MAX_POM_BYTES + 1);
      }
      if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("git timed out reading " + path);
      }
      err.join();
      if (process.exitValue() != 0 || out.length > MAX_POM_BYTES) {
        return null;
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running git", e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void putIfNotNull(Map<String, String> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
