package io.lantern.extract.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.java.ModuleStructure.Call;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.SourceFile;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reads a module's structure by parsing its sources with JavaParser, for degraded mode when the
 * module could not be compiled. Types resolve against the JDK, the sources of the snapshot's
 * repositories and whatever classpath jars the failed build left. Annotation processors do not run,
 * so members Lombok would generate are missing, and calls that do not resolve are left out.
 *
 * <p>It mirrors {@link JavacAnalyzer}: the same members, including the implicit ones javac adds
 * (default constructors and the members of records), get the same IDs, so a module's entities do
 * not change when its build is fixed. A parameter or supertype that does not resolve is named from
 * the imports, or else as if it were in the same package.
 */
final class JavaParserAnalyzer {

  /** The newest language level the parser supports without preview features. */
  static final ParserConfiguration.LanguageLevel LANGUAGE_LEVEL =
      ParserConfiguration.LanguageLevel.JAVA_25;

  private static final String LOMBOK = "lombok.";

  /** Lombok annotations that add constructors, so a class with one has no default constructor. */
  private static final Set<String> LOMBOK_CONSTRUCTORS =
      Set.of(
          "Value",
          "Data",
          "Builder",
          "SuperBuilder",
          "AllArgsConstructor",
          "RequiredArgsConstructor");

  private final JavaParser parser;

  /**
   * A parser resolving types against the JDK, the given source roots and the given jars, searched
   * in that order.
   */
  JavaParserAnalyzer(List<Path> sourceRoots, List<Path> jars) {
    CombinedTypeSolver solver = new CombinedTypeSolver();
    solver.add(new ReflectionTypeSolver(true));
    ParserConfiguration plain = new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL);
    for (Path root : sourceRoots) {
      if (Files.isDirectory(root)) {
        solver.add(new JavaParserTypeSolver(root, plain));
      }
    }
    for (Path jar : jars) {
      if (Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar")) {
        try {
          solver.add(new JarTypeSolver(jar));
        } catch (IOException | RuntimeException e) {
          // An unreadable jar resolves nothing; carry on without it.
        }
      }
    }
    this.parser =
        new JavaParser(
            new ParserConfiguration()
                .setLanguageLevel(LANGUAGE_LEVEL)
                .setSymbolResolver(new JavaSymbolSolver(solver)));
  }

  /** Analyzes the sources of one module; files that do not parse are left out. */
  ModuleStructure analyze(List<SourceFile> files) throws IOException {
    ModuleStructure structure = new ModuleStructure();
    for (SourceFile file : files) {
      // Each file is read on its own, so one the parser cannot handle adds nothing.
      ModuleStructure one = new ModuleStructure();
      try {
        ParseResult<CompilationUnit> result = parser.parse(file.file());
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
          continue;
        }
        new Reader(one, result.getResult().get(), file).read();
      } catch (RuntimeException | StackOverflowError e) {
        continue;
      }
      structure.types.addAll(one.types);
      structure.calls.addAll(one.calls);
    }
    return structure;
  }

  /** Reads one compilation unit. */
  private static final class Reader {

    private final ModuleStructure structure;
    private final CompilationUnit unit;
    private final SourceFile file;
    private final String packageName;
    private final Map<Node, String> binaryNames = new IdentityHashMap<>();
    private final Map<Node, EntityId> callables = new IdentityHashMap<>();

    Reader(ModuleStructure structure, CompilationUnit unit, SourceFile file) {
      this.structure = structure;
      this.unit = unit;
      this.file = file;
      this.packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    }

    void read() {
      for (TypeDeclaration<?> type : unit.getTypes()) {
        String binary =
            packageName.isEmpty()
                ? type.getNameAsString()
                : packageName + "." + type.getNameAsString();
        readType(type, binary, null);
      }
      for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
        Position name = call.getName().getBegin().orElse(null);
        call(call, () -> call.resolve(), name);
      }
      for (ObjectCreationExpr creation : unit.findAll(ObjectCreationExpr.class)) {
        // An anonymous class is not an entity; calls in its body count for the enclosing code.
        if (creation.getAnonymousClassBody().isEmpty()) {
          call(creation, () -> creation.resolve(), null);
        }
      }
      for (ExplicitConstructorInvocationStmt invocation :
          unit.findAll(ExplicitConstructorInvocationStmt.class)) {
        call(invocation, () -> invocation.resolve(), null);
      }
      for (MethodReferenceExpr reference : unit.findAll(MethodReferenceExpr.class)) {
        call(reference, () -> reference.resolve(), null);
      }
    }

    private void readType(TypeDeclaration<?> type, String binary, String outer) {
      binaryNames.put(type, binary);
      Location where = location(type);
      List<Supertype> supertypes = new ArrayList<>();
      List<Member> members = new ArrayList<>();
      String kind;
      // A class Lombok gives constructors has no default one, though parsing cannot see them.
      boolean hasConstructor =
          usesLombok(type)
              || type.getMembers().stream()
                  .anyMatch(
                      m ->
                          m instanceof ConstructorDeclaration
                              || m instanceof CompactConstructorDeclaration);
      switch (type) {
        case ClassOrInterfaceDeclaration c when c.isInterface() -> {
          kind = ModuleStructure.INTERFACE;
          supertypes(c.getExtendedTypes(), BuiltInTypes.EXTENDS, type, supertypes);
        }
        case ClassOrInterfaceDeclaration c -> {
          kind = ModuleStructure.CLASS;
          supertypes(c.getExtendedTypes(), BuiltInTypes.EXTENDS, type, supertypes);
          supertypes(c.getImplementedTypes(), BuiltInTypes.IMPLEMENTS, type, supertypes);
          if (!hasConstructor) {
            add(
                members,
                ModuleStructure.methodId(binary, ModuleStructure.CONSTRUCTOR, List.of()),
                where);
          }
        }
        case EnumDeclaration e -> {
          kind = ModuleStructure.ENUM;
          supertypes(e.getImplementedTypes(), BuiltInTypes.IMPLEMENTS, type, supertypes);
          for (EnumConstantDeclaration constant : e.getEntries()) {
            add(
                members,
                ModuleStructure.fieldId(binary, constant.getNameAsString()),
                location(constant));
          }
          if (!hasConstructor) {
            add(
                members,
                ModuleStructure.methodId(binary, ModuleStructure.CONSTRUCTOR, List.of()),
                where);
          }
        }
        case RecordDeclaration r -> {
          kind = ModuleStructure.RECORD;
          supertypes(r.getImplementedTypes(), BuiltInTypes.IMPLEMENTS, type, supertypes);
          recordMembers(r, binary, where, members);
        }
        case AnnotationDeclaration a -> kind = ModuleStructure.ANNOTATION;
        default -> kind = ModuleStructure.CLASS;
      }
      List<TypeDeclaration<?>> nested = new ArrayList<>();
      for (BodyDeclaration<?> member : type.getMembers()) {
        switch (member) {
          case MethodDeclaration m ->
              callable(
                  members,
                  m,
                  ModuleStructure.methodId(
                      binary, m.getNameAsString(), parameters(m.getParameters(), m)));
          case ConstructorDeclaration c ->
              callable(
                  members,
                  c,
                  ModuleStructure.methodId(
                      binary, ModuleStructure.CONSTRUCTOR, parameters(c.getParameters(), c)));
          case CompactConstructorDeclaration c ->
              callable(
                  members,
                  c,
                  ModuleStructure.methodId(
                      binary,
                      ModuleStructure.CONSTRUCTOR,
                      parameters(((RecordDeclaration) type).getParameters(), type)));
          case AnnotationMemberDeclaration m ->
              callable(
                  members, m, ModuleStructure.methodId(binary, m.getNameAsString(), List.of()));
          case FieldDeclaration f -> {
            for (VariableDeclarator v : f.getVariables()) {
              add(members, ModuleStructure.fieldId(binary, v.getNameAsString()), location(f));
            }
          }
          case TypeDeclaration<?> t -> nested.add(t);
          default -> {}
        }
      }
      structure.types.add(
          new TypeDecl(binary, packageName, outer, kind, where, supertypes, members));
      for (TypeDeclaration<?> t : nested) {
        readType(t, binary + "$" + t.getNameAsString(), binary);
      }
    }

    /**
     * A record's components as fields, and the members javac declares implicitly unless the record
     * does: accessors, the canonical constructor, {@code equals}, {@code hashCode} and {@code
     * toString}.
     */
    private void recordMembers(
        RecordDeclaration record, String binary, Location where, List<Member> members) {
      List<String> canonical = parameters(record.getParameters(), record);
      Set<EntityId> declared = new HashSet<>();
      for (BodyDeclaration<?> m : record.getMembers()) {
        if (m instanceof MethodDeclaration md) {
          declared.add(
              ModuleStructure.methodId(
                  binary, md.getNameAsString(), parameters(md.getParameters(), md)));
        } else if (m instanceof ConstructorDeclaration cd) {
          declared.add(
              ModuleStructure.methodId(
                  binary, ModuleStructure.CONSTRUCTOR, parameters(cd.getParameters(), cd)));
        } else if (m instanceof CompactConstructorDeclaration) {
          declared.add(ModuleStructure.methodId(binary, ModuleStructure.CONSTRUCTOR, canonical));
        }
      }
      for (Parameter component : record.getParameters()) {
        String name = component.getNameAsString();
        add(members, ModuleStructure.fieldId(binary, name), location(component));
        EntityId accessor = ModuleStructure.methodId(binary, name, List.of());
        if (!declared.contains(accessor)) {
          add(members, accessor, location(component));
        }
      }
      List<EntityId> implicit =
          List.of(
              ModuleStructure.methodId(binary, ModuleStructure.CONSTRUCTOR, canonical),
              ModuleStructure.methodId(binary, "equals", List.of("java.lang.Object")),
              ModuleStructure.methodId(binary, "hashCode", List.of()),
              ModuleStructure.methodId(binary, "toString", List.of()));
      for (EntityId id : implicit) {
        if (!declared.contains(id)) {
          add(members, id, where);
        }
      }
    }

    /** Whether the type has a Lombok annotation that generates constructors. */
    private boolean usesLombok(TypeDeclaration<?> type) {
      for (AnnotationExpr annotation : type.getAnnotations()) {
        String name = annotation.getNameAsString();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (!LOMBOK_CONSTRUCTORS.contains(simple)) {
          continue;
        }
        if (name.startsWith(LOMBOK)) {
          return true;
        }
        for (ImportDeclaration i : unit.getImports()) {
          String imported = i.getNameAsString();
          if (!i.isStatic()
              && (i.isAsterisk()
                  ? imported.equals("lombok") || imported.equals("lombok.experimental")
                  : imported.equals("lombok." + name)
                      || imported.equals("lombok.experimental." + name))) {
            return true;
          }
        }
      }
      return false;
    }

    private void callable(List<Member> members, Node declaration, EntityId id) {
      if (id != null) {
        callables.put(declaration, id);
        members.add(new Member(id, location(declaration)));
      }
    }

    private static void add(List<Member> members, EntityId id, Location where) {
      if (id != null) {
        members.add(new Member(id, where));
      }
    }

    private void supertypes(
        NodeList<ClassOrInterfaceType> declared,
        String predicate,
        Node context,
        List<Supertype> supertypes) {
      for (ClassOrInterfaceType t : declared) {
        String name = describe(t, context);
        if (!name.equals("java.lang.Object")) {
          supertypes.add(new Supertype(predicate, name, location(t)));
        }
      }
    }

    private List<String> parameters(List<Parameter> parameters, Node context) {
      List<String> types = new ArrayList<>();
      for (Parameter p : parameters) {
        types.add(describe(p.getType(), context) + (p.isVarArgs() ? "[]" : ""));
      }
      return types;
    }

    // -------------------------------------------------------------------------------------------
    // Calls

    private void call(
        Node node, Supplier<? extends ResolvedMethodLikeDeclaration> resolve, Position name) {
      EntityId caller = owner(node);
      if (caller == null) {
        return;
      }
      EntityId callee;
      try {
        callee = calleeId(resolve.get());
      } catch (RuntimeException | StackOverflowError e) {
        // Not resolvable without the classpath the build would have given.
        return;
      }
      if (callee == null) {
        return;
      }
      int start = name != null ? name.line : node.getBegin().map(p -> p.line).orElse(0);
      int end = node.getEnd().map(p -> p.line).orElse(start);
      structure.calls.add(new Call(caller, callee, Location.of(file, start, end)));
    }

    private static EntityId calleeId(ResolvedMethodLikeDeclaration method) {
      List<String> parameters = new ArrayList<>();
      for (int i = 0; i < method.getNumberOfParams(); i++) {
        ResolvedParameterDeclaration p = method.getParam(i);
        ResolvedType type = p.getType();
        parameters.add(describe(type) + (p.isVariadic() && !type.isArray() ? "[]" : ""));
      }
      String name =
          method
                  instanceof
                  com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration
              ? ModuleStructure.CONSTRUCTOR
              : method.getName();
      return ModuleStructure.methodId(binaryName(method.declaringType()), name, parameters);
    }

    /**
     * The method or constructor whose code makes a call, or the type for code outside methods. Code
     * in local and anonymous classes counts for the named type's member enclosing them.
     */
    private EntityId owner(Node node) {
      for (Node n = node; n.getParentNode().isPresent(); n = n.getParentNode().get()) {
        Node parent = n.getParentNode().get();
        String type = binaryNames.get(parent);
        if (type != null) {
          EntityId callable = callables.get(n);
          return callable != null ? callable : ModuleStructure.typeId(type);
        }
      }
      return null;
    }

    // -------------------------------------------------------------------------------------------
    // Types

    /** A type's erasure as it appears in a member ID, resolved if possible, else guessed. */
    private String describe(Type type, Node context) {
      try {
        return describe(type.resolve());
      } catch (RuntimeException | StackOverflowError e) {
        return guess(type, context);
      }
    }

    private static String describe(ResolvedType type) {
      ResolvedType erased = type.erasure();
      if (erased.isArray()) {
        return describe(erased.asArrayType().getComponentType()) + "[]";
      }
      if (erased.isPrimitive()) {
        return erased.asPrimitive().describe();
      }
      if (erased.isReferenceType()) {
        return binaryName(erased.asReferenceType().getTypeDeclaration().orElseThrow());
      }
      throw new IllegalArgumentException("Cannot describe " + erased.describe());
    }

    private static String binaryName(ResolvedReferenceTypeDeclaration type) {
      String pkg = type.getPackageName();
      String name = type.getClassName().replace('.', '$');
      return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /**
     * Names a type that does not resolve: a type variable as {@code java.lang.Object}, a name
     * imported by a single-type import as imported, a qualified name as written, and any other name
     * as if it were in the same package.
     */
    private String guess(Type type, Node context) {
      if (type instanceof ArrayType array) {
        return guess(array.getComponentType(), context) + "[]";
      }
      if (type instanceof PrimitiveType) {
        return type.asString();
      }
      if (!(type instanceof ClassOrInterfaceType c)) {
        return type.asString();
      }
      String name = c.getNameWithScope();
      int dot = name.indexOf('.');
      String first = dot < 0 ? name : name.substring(0, dot);
      String rest = dot < 0 ? "" : name.substring(dot).replace('.', '$');
      if (dot < 0) {
        TypeParameter variable = typeVariables(context).get(name);
        if (variable != null) {
          // Erased to its first bound, as javac erases it.
          return variable.getTypeBound().isEmpty()
              ? "java.lang.Object"
              : describe(variable.getTypeBound().getFirst().get(), variable);
        }
      }
      for (ImportDeclaration i : unit.getImports()) {
        if (!i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(first)) {
          return i.getNameAsString() + rest;
        }
      }
      if (dot >= 0) {
        return name;
      }
      return packageName.isEmpty() ? name : packageName + "." + name;
    }

    /** The type variables in scope at a node, by name, the innermost winning. */
    private static Map<String, TypeParameter> typeVariables(Node context) {
      Map<String, TypeParameter> variables = new HashMap<>();
      for (Optional<Node> n = Optional.of(context); n.isPresent(); n = n.get().getParentNode()) {
        if (n.get() instanceof NodeWithTypeParameters<?> generic) {
          for (TypeParameter p : generic.getTypeParameters()) {
            variables.putIfAbsent(p.getNameAsString(), p);
          }
        }
      }
      return variables;
    }

    private Location location(Node node) {
      int start = node.getBegin().map(p -> p.line).orElse(0);
      int end = node.getEnd().map(p -> p.line).orElse(start);
      return Location.of(file, start, end);
    }
  }
}
