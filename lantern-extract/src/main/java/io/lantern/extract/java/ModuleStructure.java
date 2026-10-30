package io.lantern.extract.java;

import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
   * @param generated whether the build generated it, rather than it being in the repository
   */
  public record SourceFile(String path, Path file, String contentHash, boolean generated) {}

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
   * @param typeArguments the erasures of the type arguments written for it, as in a member ID (a
   *     type variable as its bound), such as {@code com.example.Order} and {@code java.lang.Long}
   *     for {@code JpaRepository<Order, Long>}; empty if none are written
   * @param typeVariables for each type argument, the index of the declaring type's own type
   *     parameter it is ({@code T} in {@code Base<T> extends JpaRepository<T, Long>} is 0), {@link
   *     Integer#MAX_VALUE} for a type variable declared elsewhere (an enclosing type's), or -1 if
   *     it is not a type variable
   */
  public record Supertype(
      String predicate,
      String binaryName,
      Location where,
      List<String> typeArguments,
      List<Integer> typeVariables) {

    public Supertype {
      typeArguments = List.copyOf(typeArguments);
      typeVariables = List.copyOf(typeVariables);
      if (typeVariables.size() != typeArguments.size()) {
        throw new IllegalArgumentException("One type variable index per type argument");
      }
    }
  }

  /**
   * A member: a {@code method:} or {@code field:} entity, its declaration and the annotations
   * written on it.
   *
   * @param type for a field, its erased type as in a member ID; null for a method or constructor
   * @param parameters for a method or constructor written in the source, its parameters in order;
   *     empty for a field, for a member the compiler declares implicitly and for a record's
   *     constructor
   * @param returns for a method written in the source, its erased return type as in a member ID
   *     ({@code void} for none); null for a field, a constructor and a member the compiler declares
   *     implicitly
   */
  public record Member(
      EntityId id,
      Location where,
      List<Annotation> annotations,
      String type,
      List<Parameter> parameters,
      String returns) {

    public Member {
      annotations = List.copyOf(annotations);
      parameters = List.copyOf(parameters);
    }

    /** A member without a return type. */
    public Member(
        EntityId id,
        Location where,
        List<Annotation> annotations,
        String type,
        List<Parameter> parameters) {
      this(id, where, annotations, type, parameters, null);
    }

    /** A member without annotations, type or parameters. */
    Member(EntityId id, Location where) {
      this(id, where, List.of(), null, List.of());
    }
  }

  /** A parameter of a method or constructor: its name and the annotations written on it. */
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
   * cannot find) is left out of its list, but an element written keeps its (then empty) list.
   * Annotations written as values ({@code bindings = @QueueBinding(...)}) are kept apart, by
   * element, each where the outer one is written; a container of repeated annotations
   * ({@code @Schedules}) is read as the annotations it contains instead.
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
   * @param arguments the {@code String} constants passed, by position, with constants folded as for
   *     annotation values; null for an argument that is not one; empty when none is
   * @param receiver the binary name of the static type of what a method call or method reference is
   *     written on ({@code orders} in {@code orders.save(o)}, {@code Orders} in {@code
   *     Orders::save}) when it is a named type other than the callee's, else null
   */
  public record Call(
      EntityId caller, EntityId callee, Location where, List<String> arguments, String receiver) {

    public Call {
      arguments =
          arguments.stream().allMatch(Objects::isNull)
              ? List.of()
              : Collections.unmodifiableList(new ArrayList<>(arguments));
    }

    public Call(EntityId caller, EntityId callee, Location where, List<String> arguments) {
      this(caller, callee, where, arguments, null);
    }

    /**
     * The callee as a member of the receiver's type ({@code method:<receiver>#<name>(<parameters as
     * declared>)}), or null without a receiver.
     */
    public EntityId calleeOfReceiver() {
      if (receiver == null) {
        return null;
      }
      String key = callee.key();
      return new EntityId(callee.type(), receiver + key.substring(key.indexOf('#')));
    }

    /** The {@code String} constant passed at a position, or null if there is none. */
    public String argument(int index) {
      return index < arguments.size() ? arguments.get(index) : null;
    }
  }

  /**
   * A chain of calls on an API that extractors follow values into ({@link TrackedApis}), such as an
   * HTTP client's request: a call that is not itself the receiver of another call, whose innermost
   * receiver has a type of a tracked API.
   *
   * @param caller the method whose code makes it, or the type for code outside methods; a {@link
   *     Expression.Param} in it is that method's parameter
   * @param expression the outermost call of the chain
   * @param where the chain
   * @param rootType the binary name of the tracked type the chain starts from: of what its
   *     innermost call is made on, or of the object a constructor call makes
   */
  public record Invocation(
      EntityId caller, Expression.Invoke expression, Location where, String rootType) {}

  /**
   * A value given to a {@code String} field or a field of a tracked API's type ({@link
   * TrackedApis}) of a named type: its initializer, or an assignment to it in a constructor or
   * initializer block of its type.
   *
   * @param field the field
   * @param in the constructor the assignment is in, or the type for an initializer; a {@link
   *     Expression.Param} in the value is that constructor's parameter
   * @param value the value
   * @param where the initializer or assignment
   */
  public record Assignment(EntityId field, EntityId in, Expression value, Location where) {}

  /**
   * A call from a named type's code to one of that type's own methods, written without a receiver
   * or on {@code this}, with its arguments as expressions, so that extractors can follow a value
   * passed as an argument into the method that uses it (a URL built in one method and sent in
   * another). Calls without arguments are not kept.
   *
   * @param caller the calling method or constructor, or the type for code outside methods; a {@link
   *     Expression.Param} in an argument is that member's parameter
   * @param callee the method called, as resolved, declared by the caller's type
   * @param arguments the arguments, in order
   * @param where the call
   */
  public record OwnCall(
      EntityId caller, EntityId callee, List<Expression> arguments, Location where) {

    public OwnCall {
      arguments = List.copyOf(arguments);
    }
  }

  final List<TypeDecl> types = new ArrayList<>();
  final List<Call> calls = new ArrayList<>();
  final List<Invocation> invocations = new ArrayList<>();
  final List<Assignment> assignments = new ArrayList<>();
  final List<OwnCall> ownCalls = new ArrayList<>();

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

  /** The calls on tracked APIs, in the order read. */
  public List<Invocation> invocations() {
    return Collections.unmodifiableList(invocations);
  }

  /** The values given to fields, in the order read. */
  public List<Assignment> assignments() {
    return Collections.unmodifiableList(assignments);
  }

  /** The calls of each type's own methods with their arguments, in the order read. */
  public List<OwnCall> ownCalls() {
    return Collections.unmodifiableList(ownCalls);
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

  /**
   * Whether an argument of a call is passed to a {@code String} parameter of the callee: one
   * declared {@code java.lang.String}, or a last one declared {@code java.lang.String[]} (varargs).
   * Only these arguments are folded into {@link Call#arguments}.
   */
  static boolean stringParameter(EntityId callee, int index) {
    String key = callee.key();
    int open = key.lastIndexOf('(');
    String list = key.substring(open + 1, key.length() - 1);
    if (list.isEmpty()) {
      return false;
    }
    String[] parameters = list.split(",");
    if (index < parameters.length - 1) {
      return parameters[index].equals("java.lang.String");
    }
    String last = parameters[parameters.length - 1];
    return last.equals("java.lang.String[]")
        || (index == parameters.length - 1 && last.equals("java.lang.String"));
  }

  private static EntityId entity(String type, String key) {
    try {
      return new EntityId(type, key);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
