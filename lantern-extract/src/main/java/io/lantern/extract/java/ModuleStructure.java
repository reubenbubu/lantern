package io.lantern.extract.java;

import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an analyzer found in the sources of one module, before it becomes facts: the named types
 * (top-level and member types, not local or anonymous ones) with their members, supertypes and
 * annotations, and the calls their code makes. Both analyzers fill it the same way, so the facts do
 * not depend on which one ran, except for what each can see.
 *
 * <p>Other extractors read it through {@link JavaAnalysis}; it is not changed after analysis.
 */
public final class ModuleStructure {

  /** Type kinds, as the {@code kind} attribute holds them. */
  public static final String CLASS = "class";

  public static final String INTERFACE = "interface";
  public static final String ENUM = "enum";
  public static final String RECORD = "record";
  public static final String ANNOTATION = "annotation";

  /**
   * A source file the analyzer read.
   *
   * @param path the path facts give as evidence: relative to the top of the repository, or for a
   *     generated source, to the top of the build's work directory
   * @param file where the file was on disk during analysis; it is deleted afterwards
   * @param contentHash the SHA-256 of the file's content
   */
  public record SourceFile(String path, Path file, String contentHash) {}

  /**
   * Where something is: a file and an inclusive range of 1-based lines, or no lines (0) when the
   * compiler gives no position, as for some generated members.
   */
  public record Location(SourceFile file, int start, int end) {

    static Location of(SourceFile file, long startLine, long endLine) {
      if (startLine < 1) {
        return new Location(file, 0, 0);
      }
      return new Location(file, (int) startLine, (int) Math.max(startLine, endLine));
    }
  }

  /**
   * A named type.
   *
   * @param binaryName the binary name, such as {@code com.example.Outer$Inner}
   * @param packageName the package, empty for the unnamed package
   * @param outer the binary name of the enclosing type, or null for a top-level type
   * @param kind one of the kind constants
   * @param where the declaration
   * @param supertypes the supertypes it declares
   * @param members its methods, constructors, fields and enum constants
   * @param annotations the annotations written on it
   */
  public record TypeDecl(
      String binaryName,
      String packageName,
      String outer,
      String kind,
      Location where,
      List<Supertype> supertypes,
      List<Member> members,
      List<Annotation> annotations) {}

  /**
   * A declared supertype.
   *
   * @param predicate {@code EXTENDS} or {@code IMPLEMENTS}
   * @param binaryName the supertype's binary name
   * @param where the declaration
   */
  public record Supertype(String predicate, String binaryName, Location where) {}

  /**
   * A member: a {@code method:} or {@code field:} entity, its declaration and the annotations
   * written on it.
   */
  public record Member(EntityId id, Location where, List<Annotation> annotations) {

    /** A member without annotations. */
    Member(EntityId id, Location where) {
      this(id, where, List.of());
    }
  }

  /**
   * An annotation, with the values written for its elements, constants folded.
   *
   * <p>Each value is a list of strings: one for a single value, one per element of an array. A
   * string or number is its value as Java prints it, a class literal its binary name, and an enum
   * constant its name. A value that cannot be folded (in degraded mode, a constant the parser
   * cannot find) is left out of its list. Nested annotations are not kept, except that a container
   * of repeated annotations ({@code @Schedules}) is read as the annotations it contains.
   *
   * @param type the annotation type's binary name
   * @param values the values written, by element name ({@code value} for a single one)
   * @param where where it is written, or null for one read from the classpath
   */
  public record Annotation(String type, Map<String, List<String>> values, Location where) {

    public Annotation {
      values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** The values written for an element, empty if none. */
    public List<String> values(String element) {
      return values.getOrDefault(element, List.of());
    }
  }

  /**
   * A call from code of a named type to a method or constructor.
   *
   * @param caller the calling method, or the type for code outside methods (field initializers and
   *     initializer blocks)
   * @param callee the method or constructor called, as resolved
   * @param where the call
   */
  public record Call(EntityId caller, EntityId callee, Location where) {}

  final List<TypeDecl> types = new ArrayList<>();
  final List<Call> calls = new ArrayList<>();

  /**
   * The annotations on annotation types read from the classpath (meta-annotations), by the
   * annotation type's binary name. Only the compiler path can read them.
   */
  final Map<String, List<Annotation>> annotationTypes = new LinkedHashMap<>();

  /** The named types, in the order read. */
  public List<TypeDecl> types() {
    return Collections.unmodifiableList(types);
  }

  /** The calls the code makes, in the order read. */
  public List<Call> calls() {
    return Collections.unmodifiableList(calls);
  }

  /** The meta-annotations of the annotation types read from the classpath, by binary name. */
  public Map<String, List<Annotation>> annotationTypes() {
    return Collections.unmodifiableMap(annotationTypes);
  }

  // ---------------------------------------------------------------------------------------------
  // Entity IDs

  public static final String METHOD = "method";
  public static final String FIELD = "field";
  public static final String CONSTRUCTOR = "<init>";

  /** A {@code type:} entity, or null if the name does not form an entity key. */
  static EntityId typeId(String binaryName) {
    return entity(BuiltInTypes.TYPE, binaryName);
  }

  /** A {@code method:} entity: {@code <type>#<name>(<erased parameter types>)}. */
  static EntityId methodId(String typeBinaryName, String name, List<String> parameterTypes) {
    return entity(
        METHOD, typeBinaryName + "#" + name + "(" + String.join(",", parameterTypes) + ")");
  }

  /** A {@code field:} entity: {@code <type>#<name>}. */
  static EntityId fieldId(String typeBinaryName, String name) {
    return entity(FIELD, typeBinaryName + "#" + name);
  }

  private static EntityId entity(String type, String key) {
    try {
      return new EntityId(type, key);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
