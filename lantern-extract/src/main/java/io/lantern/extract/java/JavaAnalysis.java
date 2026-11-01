package io.lantern.extract.java;

import io.lantern.core.fact.EntityId;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Java code of a snapshot's repositories as the Java structure extractor read it, one {@link
 * ModuleStructure} per Maven module, so that other extractors (such as the Spring extractor) use
 * the same reading without compiling or parsing again. Made by {@link JavaExtractor#analyze}.
 */
public final class JavaAnalysis {

  /**
   * One module's code.
   *
   * @param repo the repository, at its commit with its build, if any
   * @param directory the module's directory relative to the top of the repository, {@code ""} for
   *     the top
   * @param id the module's entity ID, {@code module:<groupId>:<artifactId>}
   * @param compiled whether it was read through javac rather than parsed (degraded mode)
   * @param confidence the confidence of facts read from it: 1.0 compiled, 0.7 parsed
   * @param structure what was read
   */
  public record Module(
      RepoBuild repo,
      String directory,
      EntityId id,
      boolean compiled,
      double confidence,
      ModuleStructure structure) {

    public Module {
      Objects.requireNonNull(repo, "repo");
      Objects.requireNonNull(directory, "directory");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(structure, "structure");
    }
  }

  private final List<Module> modules;
  private final Map<String, TypeDecl> types = new HashMap<>();
  private final Map<String, Module> declaringModules = new HashMap<>();
  private final Map<String, List<Annotation>> classpathAnnotationTypes = new HashMap<>();
  private final Values values;

  JavaAnalysis(List<Module> modules) {
    this.modules = List.copyOf(modules);
    for (Module m : this.modules) {
      for (TypeDecl t : m.structure().types) {
        types.putIfAbsent(t.binaryName(), t);
        declaringModules.putIfAbsent(t.binaryName(), m);
      }
      m.structure().annotationTypes.forEach(classpathAnnotationTypes::putIfAbsent);
    }
    this.values = new Values(this);
  }

  /** The values of the code, followed one hop (#283). */
  public Values values() {
    return values;
  }

  /** The modules, ordered by repository name and commit, then as each repository lists them. */
  public List<Module> modules() {
    return modules;
  }

  /**
   * A type declared in the analyzed sources, the first read if several modules declare it.
   *
   * @param binaryName the type's binary name
   * @return the type, or null if no module declares it
   */
  public TypeDecl type(String binaryName) {
    return types.get(binaryName);
  }

  /**
   * Whether a type, or a type its type arguments hold, is declared in the analyzed sources, such as
   * a message class of a shared library of the set (#520); arrays count as their element type.
   */
  public boolean declaresAny(ModuleStructure.TypeUse use) {
    if (use == null) {
      return false;
    }
    if (type(use.type().replace("[]", "")) != null) {
      return true;
    }
    return use.arguments().stream().anyMatch(a -> type(a.replace("[]", "")) != null);
  }

  /**
   * The module that declares the type {@link #type} returns, which may be in another repository
   * than code that uses it.
   *
   * @param binaryName the type's binary name
   * @return the module, or null if no module declares the type
   */
  public Module module(String binaryName) {
    return declaringModules.get(binaryName);
  }

  /**
   * The annotations on an annotation type (its meta-annotations): from its declaration if it is in
   * the analyzed sources, else as javac read it from a module's classpath, else from Lantern's list
   * of well-known library annotations (for code that was only parsed).
   *
   * @param binaryName the annotation type's binary name
   * @return the meta-annotations, or an empty list if the type is unknown
   */
  public List<Annotation> metaAnnotations(String binaryName) {
    return metaAnnotationsOf(binaryName);
  }

  /**
   * The first annotation written that is, or carries through meta-annotations, the given type: the
   * path from the one written to the one found, or null.
   *
   * @param annotations the annotations written on an element
   * @param type the binary name of the annotation type looked for
   */
  public List<Annotation> annotationPath(List<Annotation> annotations, String type) {
    for (Annotation a : annotations) {
      List<Annotation> path = annotationPath(a, type);
      if (path != null) {
        return path;
      }
    }
    return null;
  }

  /**
   * The path from an annotation to one of the given type among its meta-annotations, the annotation
   * itself first, or null if it is not one and carries none.
   */
  public List<Annotation> annotationPath(Annotation a, String type) {
    return find(a, type, new HashSet<>());
  }

  private List<Annotation> find(Annotation a, String type, Set<String> seen) {
    if (a.type().equals(type)) {
      return List.of(a);
    }
    if (!seen.add(a.type())) {
      return null;
    }
    for (Annotation meta : metaAnnotationsOf(a.type())) {
      List<Annotation> path = find(meta, type, seen);
      if (path != null) {
        List<Annotation> full = new ArrayList<>();
        full.add(a);
        full.addAll(path);
        return full;
      }
    }
    return null;
  }

  private List<Annotation> metaAnnotationsOf(String binaryName) {
    TypeDecl declared = types.get(binaryName);
    if (declared != null) {
      return declared.annotations();
    }
    List<Annotation> read = classpathAnnotationTypes.get(binaryName);
    if (read != null) {
      return read;
    }
    List<Annotation> known = WellKnownAnnotations.metaAnnotations(binaryName);
    return known != null ? known : List.of();
  }
}
