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
   * A member: a {@code method:} or {@code field:} entity, its declaration, the annotations written
   * on it and, for a method or constructor, its parameters in order.
   */
  public record Member(
      EntityId id, Location where, List<Annotation> annotations, List<Parameter> parameters) {

    public Member {
      annotations = List.copyOf(annotations);
      parameters = List.copyOf(parameters);
    }

    /** A member without parameters. */
    Member(EntityId id, Location where, List<Annotation> annotations) {
      this(id, where, annotations, List.of());
    }

    /** A member without annotations or parameters. */
    Member(EntityId id, Location where) {
      this(id, where, List.of(), List.of());
    }
  }

  /**
   * A parameter of a method or constructor.
   *
   * @param name its name as written, or as the class file gives it ({@code arg0} without debug
   *     information)
   * @param annotations the annotations written on it
   */
  public record Parameter(String name, List<Annotation> annotations) {

    public Parameter {
      annotations = List.copyOf(annotations);
    }
  }

  /**
   * An annotation, with the values written for its elements, constants folded.
   *
   * <p>Each value is a list of strings: one for a single value, one per element of an array. A
   * string or number is its value as Java prints it, a class literal its binary name, and an enum
   * constant its name. A value that cannot be folded (in degraded mode, a constant the parser
   * cannot find) is left out of its list. Annotations written as values ({@code bindings
   * = @QueueBinding(...)}) are kept apart, by element, each where the outer one is written; a
   * container of repeated annotations ({@code @Schedules}) is read as the annotations it contains
   * instead.
   *
   * @param type the annotation type's binary name
   * @param values the values written, by element name ({@code value} for a single one)
   * @param nested the annotations written as values, by element name
   * @param where where it is written, or null for one read from the classpath
   */
  public record Annotation(
      String type,
      Map<String, List<String>> values,
      Map<String, List<Annotation>> nested,
      Location where) {

    public Annotation {
      values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
      Map<String, List<Annotation>> copy = new LinkedHashMap<>();
      nested.forEach((k, v) -> copy.put(k, List.copyOf(v)));
      nested = Collections.unmodifiableMap(copy);
    }

    /** An annotation without annotations written as values. */
    public Annotation(String type, Map<String, List<String>> values, Location where) {
      this(type, values, Map.of(), where);
    }

    /** The values written for an element, empty if none. */
    public List<String> values(String element) {
      return values.getOrDefault(element, List.of());
    }

    /** The annotations written as values of an element, empty if none. */
    public List<Annotation> nested(String element) {
      return nested.getOrDefault(element, List.of());
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

  /**
   * A call or object creation as written, with what its receiver and arguments are, for following a
   * value into the call that uses it (a topic name into a template's {@code send}). Unlike a {@link
   * Call}, it is kept when the callee does not resolve (in degraded mode, a library type with no
   * jar), with its declaring type guessed from the receiver as declared.
   *
   * @param caller the calling method, or the type for code outside methods
   * @param callee the method or constructor called, or null if it does not resolve
   * @param type the binary name of the type declaring the callee; when the callee does not resolve,
   *     the type the receiver is declared with (or the type created or named), guessed as a
   *     parameter type is, or null if not known
   * @param method the method's name, or {@code <init>} for a constructor
   * @param receiver what the method is called on, or null for a static or unqualified call or a
   *     creation
   * @param arguments the arguments, in order
   * @param where the call
   */
  public record Invocation(
      EntityId caller,
      EntityId callee,
      String type,
      String method,
      Value receiver,
      List<Value> arguments,
      Location where) {

    public Invocation {
      arguments = List.copyOf(arguments);
    }

    /** The callee's parameter types as its ID gives them, or null if it does not resolve. */
    public List<String> parameterTypes() {
      if (callee == null) {
        return null;
      }
      String key = callee.key();
      String list = key.substring(key.indexOf('(') + 1, key.length() - 1);
      return list.isEmpty() ? List.of() : List.of(list.split(","));
    }
  }

  /**
   * What an expression in code is, as far as following a value needs: a constant, a field or
   * parameter read, the result of another invocation, or something else.
   */
  public sealed interface Value {}

  /**
   * A constant expression, folded as javac folds it: a string or number as Java prints it, an enum
   * constant by name.
   */
  public record Constant(String value) implements Value {}

  /** A read of a field that is not a constant, by its {@code field:} entity. */
  public record FieldRead(EntityId field) implements Value {}

  /** A read of a parameter of the method or constructor the code is in. */
  public record ParameterRead(int index, String name) implements Value {}

  /** The value another invocation returns, or the object a creation makes. */
  public record Result(Invocation invocation) implements Value {}

  /** Anything else: a local variable, a lambda, an expression that is not a constant. */
  public record Other() implements Value {}

  /**
   * An assignment to a field of the type the code is in ({@code this.topic = topic}), or a field's
   * initializer.
   *
   * @param in the method or constructor assigning it, or the type for an initializer
   * @param field the field
   * @param value what is assigned
   * @param where the assignment
   */
  public record Assignment(EntityId in, EntityId field, Value value, Location where) {}

  final List<TypeDecl> types = new ArrayList<>();
  final List<Call> calls = new ArrayList<>();
  final List<Invocation> invocations = new ArrayList<>();
  final List<Assignment> assignments = new ArrayList<>();

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

  /**
   * The calls and object creations, in the order read, including those whose callee does not
   * resolve. An invocation nested in another's receiver or arguments is listed too, as the same
   * object.
   */
  public List<Invocation> invocations() {
    return Collections.unmodifiableList(invocations);
  }

  /** The assignments to fields and the fields' initializers, in the order read. */
  public List<Assignment> assignments() {
    return Collections.unmodifiableList(assignments);
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
