package io.lantern.extract.java;

import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What an analyzer found in the sources of one module, before it becomes facts: the named types
 * (top-level and member types, not local or anonymous ones) with their members and supertypes, and
 * the calls their code makes. Both analyzers fill it the same way, so the facts do not depend on
 * which one ran, except for what each can see.
 */
final class ModuleStructure {

  /** Type kinds, as the {@code kind} attribute holds them. */
  static final String CLASS = "class";

  static final String INTERFACE = "interface";
  static final String ENUM = "enum";
  static final String RECORD = "record";
  static final String ANNOTATION = "annotation";

  /**
   * A source file the analyzer read.
   *
   * @param path the path facts give as evidence: relative to the top of the repository, or for a
   *     generated source, to the top of the build's work directory
   * @param file where the file is on disk
   * @param contentHash the SHA-256 of the file's content
   */
  record SourceFile(String path, Path file, String contentHash) {}

  /**
   * Where something is: a file and an inclusive range of 1-based lines, or no lines (0) when the
   * compiler gives no position, as for some generated members.
   */
  record Location(SourceFile file, int start, int end) {

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
   */
  record TypeDecl(
      String binaryName,
      String packageName,
      String outer,
      String kind,
      Location where,
      List<Supertype> supertypes,
      List<Member> members) {}

  /**
   * A declared supertype.
   *
   * @param predicate {@code EXTENDS} or {@code IMPLEMENTS}
   * @param binaryName the supertype's binary name
   * @param where the declaration
   */
  record Supertype(String predicate, String binaryName, Location where) {}

  /** A member: a {@code method:} or {@code field:} entity, and its declaration. */
  record Member(EntityId id, Location where) {}

  /**
   * A call from code of a named type to a method or constructor.
   *
   * @param caller the calling method, or the type for code outside methods (field initializers and
   *     initializer blocks)
   * @param callee the method or constructor called, as resolved
   * @param where the call
   */
  record Call(EntityId caller, EntityId callee, Location where) {}

  final List<TypeDecl> types = new ArrayList<>();
  final List<Call> calls = new ArrayList<>();

  // ---------------------------------------------------------------------------------------------
  // Entity IDs

  static final String METHOD = "method";
  static final String FIELD = "field";
  static final String CONSTRUCTOR = "<init>";

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
