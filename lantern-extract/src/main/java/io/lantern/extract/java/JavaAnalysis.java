package io.lantern.extract.java;

import io.lantern.core.fact.EntityId;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
  private final Map<String, List<Annotation>> classpathAnnotationTypes = new HashMap<>();

  JavaAnalysis(List<Module> modules) {
    this.modules = List.copyOf(modules);
    for (Module m : this.modules) {
      for (TypeDecl t : m.structure().types) {
        types.putIfAbsent(t.binaryName(), t);
      }
      m.structure().annotationTypes.forEach(classpathAnnotationTypes::putIfAbsent);
    }
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
   * The annotations on an annotation type (its meta-annotations): from its declaration if it is in
   * the analyzed sources, else as javac read it from a module's classpath, else from Lantern's list
   * of well-known library annotations (for code that was only parsed).
   *
   * @param binaryName the annotation type's binary name
   * @return the meta-annotations, or an empty list if the type is unknown
   */
  public List<Annotation> metaAnnotations(String binaryName) {
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
