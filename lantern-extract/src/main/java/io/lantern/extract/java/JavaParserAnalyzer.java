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
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.AssociableToAST;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.java.ModuleStructure.Annotation;
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
import java.util.LinkedHashMap;
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

  /** The element a single annotation value is written for. */
  private static final String VALUE = "value";

  /** How many constants a constant's value may lead through. */
  private static final int MAX_CONSTANT_DEPTH = 16;

  /** The annotations in {@code java.lang}, which need no import. */
  private static final Set<String> JAVA_LANG_ANNOTATIONS =
      Set.of("Deprecated", "FunctionalInterface", "Override", "SafeVarargs", "SuppressWarnings");

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
  private final TypeSolver solver;

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
    this.solver = solver;
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
        new Reader(one, result.getResult().get(), file, solver).read();
      } catch (RuntimeException | StackOverflowError e) {
        continue;
      }
      structure.types.addAll(one.types);
      structure.calls.addAll(one.calls);
      structure.invocations.addAll(one.invocations);
      structure.assignments.addAll(one.assignments);
      structure.ownCalls.addAll(one.ownCalls);
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
    private final TypeSolver solver;

    Reader(ModuleStructure structure, CompilationUnit unit, SourceFile file, TypeSolver solver) {
      this.structure = structure;
      this.unit = unit;
      this.file = file;
      this.solver = solver;
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
        call(call, () -> call.resolve(), name, call.getArguments(), call.getScope().orElse(null));
      }
      for (ObjectCreationExpr creation : unit.findAll(ObjectCreationExpr.class)) {
        // An anonymous class is not an entity; calls in its body count for the enclosing code.
        if (creation.getAnonymousClassBody().isEmpty()) {
          call(creation, () -> creation.resolve(), null, creation.getArguments(), null);
        }
      }
      for (ExplicitConstructorInvocationStmt invocation :
          unit.findAll(ExplicitConstructorInvocationStmt.class)) {
        call(invocation, () -> invocation.resolve(), null, invocation.getArguments(), null);
      }
      for (MethodReferenceExpr reference : unit.findAll(MethodReferenceExpr.class)) {
        call(reference, () -> reference.resolve(), null, List.of(), reference.getScope());
      }
      for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
        chain(call);
      }
      for (ObjectCreationExpr creation : unit.findAll(ObjectCreationExpr.class)) {
        if (creation.getAnonymousClassBody().isEmpty()) {
          chain(creation);
        }
      }
      for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
        ownCall(call);
      }
      fieldValues();
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
            EntityId id = ModuleStructure.fieldId(binary, constant.getNameAsString());
            if (id != null) {
              members.add(
                  new Member(
                      id,
                      location(constant),
                      annotations(constant.getAnnotations()),
                      binary,
                      List.of()));
            }
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
              EntityId id = ModuleStructure.fieldId(binary, v.getNameAsString());
              if (id != null) {
                members.add(
                    new Member(
                        id,
                        location(f),
                        annotations(f.getAnnotations()),
                        describe(v.getType(), v),
                        List.of()));
              }
            }
          }
          case TypeDeclaration<?> t -> nested.add(t);
          default -> {}
        }
      }
      structure.types.add(
          new TypeDecl(
              binary,
              packageName,
              outer,
              kind,
              where,
              supertypes,
              members,
              annotations(type.getAnnotations())));
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
        EntityId field = ModuleStructure.fieldId(binary, name);
        if (field != null) {
          members.add(
              new Member(
                  field,
                  location(component),
                  List.of(),
                  describe(component.getType(), record),
                  List.of()));
        }
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

    private void callable(List<Member> members, BodyDeclaration<?> declaration, EntityId id) {
      if (id != null) {
        callables.put(declaration, id);
        List<ModuleStructure.Parameter> parameters = new ArrayList<>();
        // As javac reads them: none for a record's constructors.
        if (declaration instanceof MethodDeclaration
            || declaration instanceof ConstructorDeclaration
                && !(declaration.getParentNode().orElse(null) instanceof RecordDeclaration)) {
          for (Parameter p : ((CallableDeclaration<?>) declaration).getParameters()) {
            parameters.add(
                new ModuleStructure.Parameter(
                    p.getNameAsString(), annotations(p.getAnnotations())));
          }
        }
        String returns =
            switch (declaration) {
              case MethodDeclaration m -> describe(m.getType(), m);
              case AnnotationMemberDeclaration m -> describe(m.getType(), m);
              default -> null;
            };
        members.add(
            new Member(
                id,
                location(declaration),
                annotations(declaration.getAnnotations()),
                null,
                parameters,
                returns));
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
          List<String> parameters = new ArrayList<>();
          if (context instanceof NodeWithTypeParameters<?> generic) {
            generic.getTypeParameters().forEach(p -> parameters.add(p.getNameAsString()));
          }
          List<String> arguments = new ArrayList<>();
          List<Integer> variables = new ArrayList<>();
          for (Type a : t.getTypeArguments().orElse(new NodeList<>())) {
            arguments.add(describe(a, context));
            int variable = -1;
            if (a instanceof ClassOrInterfaceType c
                && c.getScope().isEmpty()
                && c.getTypeArguments().isEmpty()) {
              variable = parameters.indexOf(c.getNameAsString());
              if (variable < 0 && typeVariables(context).containsKey(c.getNameAsString())) {
                // A type variable of an enclosing type: not one of the type's own.
                variable = Integer.MAX_VALUE;
              }
            }
            variables.add(variable);
          }
          supertypes.add(new Supertype(predicate, name, location(t), arguments, variables));
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
    // Annotations

    private List<Annotation> annotations(List<AnnotationExpr> written) {
      List<Annotation> annotations = new ArrayList<>();
      for (AnnotationExpr a : written) {
        annotation(a, location(a), annotations);
      }
      return annotations;
    }

    /** Adds an annotation, or the annotations a container of repeated annotations holds. */
    private void annotation(AnnotationExpr a, Location where, List<Annotation> annotations) {
      Map<String, List<String>> values = new LinkedHashMap<>();
      Map<String, List<AnnotationExpr>> nestedByElement = new LinkedHashMap<>();
      elements(a, values, nestedByElement);
      List<AnnotationExpr> nested = nestedByElement.getOrDefault(VALUE, List.of());
      if (!nested.isEmpty() && values.size() == 1 && values.get(VALUE).isEmpty()) {
        for (AnnotationExpr n : nested) {
          annotation(n, where, annotations);
        }
        return;
      }
      annotations.add(
          new Annotation(annotationType(a), values, nested(nestedByElement, where), where));
    }

    /** An annotation's element values, with the annotations written as values apart. */
    private void elements(
        AnnotationExpr a,
        Map<String, List<String>> values,
        Map<String, List<AnnotationExpr>> nestedByElement) {
      if (a instanceof SingleMemberAnnotationExpr single) {
        List<AnnotationExpr> inner = new ArrayList<>();
        values.put(VALUE, values(single.getMemberValue(), inner));
        if (!inner.isEmpty()) {
          nestedByElement.put(VALUE, inner);
        }
      } else if (a instanceof NormalAnnotationExpr normal) {
        for (MemberValuePair pair : normal.getPairs()) {
          String element = pair.getNameAsString();
          List<AnnotationExpr> inner = new ArrayList<>();
          values.put(element, values(pair.getValue(), inner));
          if (!inner.isEmpty()) {
            nestedByElement.put(element, inner);
          }
        }
      }
    }

    /** Annotations written as element values, read as written annotations are, recursively. */
    private Map<String, List<Annotation>> nested(
        Map<String, List<AnnotationExpr>> byElement, Location where) {
      Map<String, List<Annotation>> nested = new LinkedHashMap<>();
      byElement.forEach(
          (element, written) -> {
            List<Annotation> list = new ArrayList<>();
            for (AnnotationExpr n : written) {
              Map<String, List<String>> values = new LinkedHashMap<>();
              Map<String, List<AnnotationExpr>> inner = new LinkedHashMap<>();
              elements(n, values, inner);
              list.add(new Annotation(annotationType(n), values, nested(inner, where), where));
            }
            nested.put(element, list);
          });
      return nested;
    }

    /** An element's value as strings, constants folded; nested annotations go to {@code nested}. */
    private List<String> values(
        com.github.javaparser.ast.expr.Expression value, List<AnnotationExpr> nested) {
      List<com.github.javaparser.ast.expr.Expression> items =
          value instanceof ArrayInitializerExpr array ? array.getValues() : List.of(value);
      List<String> values = new ArrayList<>();
      for (com.github.javaparser.ast.expr.Expression item : items) {
        if (item instanceof AnnotationExpr a) {
          if (nested != null) {
            nested.add(a);
          }
          continue;
        }
        Object constant = constant(item, 0);
        if (constant != null) {
          values.add(String.valueOf(constant));
        }
      }
      return List.copyOf(values);
    }

    /**
     * Folds a constant expression as javac would: a String, Long, Double, Character or Boolean, a
     * class literal's or enum constant's name as a String, or null if it cannot be folded. Integer
     * arithmetic is done in {@code long}, so a result that overflows an {@code int} differs.
     */
    private Object constant(com.github.javaparser.ast.expr.Expression e, int depth) {
      if (depth > MAX_CONSTANT_DEPTH) {
        return null;
      }
      try {
        return switch (e) {
          case StringLiteralExpr s -> s.asString();
          case TextBlockLiteralExpr t -> t.asString();
          case CharLiteralExpr c -> c.asChar();
          case IntegerLiteralExpr i -> i.asNumber().longValue();
          case LongLiteralExpr l -> l.asNumber().longValue();
          case DoubleLiteralExpr d -> d.asDouble();
          case BooleanLiteralExpr b -> b.getValue();
          case EnclosedExpr inner -> constant(inner.getInner(), depth + 1);
          case CastExpr cast -> constant(cast.getExpression(), depth + 1);
          case UnaryExpr u -> unary(u, constant(u.getExpression(), depth + 1));
          case BinaryExpr b ->
              binary(
                  b.getOperator(),
                  constant(b.getLeft(), depth + 1),
                  constant(b.getRight(), depth + 1));
          case ClassExpr c -> describe(c.getType(), c);
          case NameExpr n -> reference(JavaParserFacade.get(solver).solve(n), depth);
          case FieldAccessExpr f -> reference(JavaParserFacade.get(solver).solve(f), depth);
          default -> null;
        };
      } catch (RuntimeException | StackOverflowError ex) {
        // Not resolvable without the classpath the build would have given, except a constant of
        // a well-known enum that annotations take (RequestMethod.GET), or a well-known string
        // constant (ExchangeTypes.TOPIC), named by its imports.
        if (e instanceof FieldAccessExpr f) {
          String type = guessType(f.getScope().toString());
          if (WellKnownAnnotations.isKnownEnum(type)) {
            return f.getNameAsString();
          }
          return WellKnownAnnotations.knownConstant(type, f.getNameAsString());
        }
        return null;
      }
    }

    /** The value of a constant or enum constant a name refers to, or null. */
    private Object reference(SymbolReference<? extends ResolvedValueDeclaration> ref, int depth) {
      if (!ref.isSolved()) {
        return null;
      }
      ResolvedValueDeclaration d = ref.getCorrespondingDeclaration();
      if (d.isEnumConstant()) {
        return d.getName();
      }
      if (d instanceof AssociableToAST declaration
          && declaration.toAst().orElse(null) instanceof Node node) {
        List<VariableDeclarator> declared =
            switch (node) {
              case VariableDeclarator v -> List.of(v);
              case FieldDeclaration f -> f.getVariables();
              case VariableDeclarationExpr local -> local.getVariables();
              default -> List.of();
            };
        VariableDeclarator variable =
            declared.stream()
                .filter(v -> v.getNameAsString().equals(d.getName()))
                .findFirst()
                .orElse(null);
        if (variable != null && variable.getInitializer().isPresent() && isFinal(variable)) {
          return constant(variable.getInitializer().get(), depth + 1);
        }
      }
      return null;
    }

    /**
     * Whether a variable is final, as a constant variable must be: declared final, or a field of an
     * interface or annotation type, which is implicitly so.
     */
    private static boolean isFinal(VariableDeclarator variable) {
      Node parent = variable.getParentNode().orElse(null);
      if (parent instanceof FieldDeclaration field) {
        return field.isFinal()
            || field
                .getParentNode()
                .map(
                    owner ->
                        owner instanceof AnnotationDeclaration
                            || (owner instanceof ClassOrInterfaceDeclaration c && c.isInterface()))
                .orElse(false);
      }
      return parent instanceof VariableDeclarationExpr local && local.isFinal();
    }

    private static Object unary(UnaryExpr u, Object value) {
      return switch (u.getOperator()) {
        case PLUS -> value instanceof Long || value instanceof Double ? value : null;
        case MINUS ->
            value instanceof Long l
                ? (Object) (-l)
                : value instanceof Double d ? (Object) (-d) : null;
        case LOGICAL_COMPLEMENT -> value instanceof Boolean b ? (Object) !b : null;
        default -> null;
      };
    }

    private static Object binary(BinaryExpr.Operator operator, Object left, Object right) {
      if (left == null || right == null) {
        return null;
      }
      if (operator == BinaryExpr.Operator.PLUS
          && (left instanceof String || right instanceof String)) {
        return String.valueOf(left) + right;
      }
      if (left instanceof Long a && right instanceof Long b) {
        return switch (operator) {
          case PLUS -> a + b;
          case MINUS -> a - b;
          case MULTIPLY -> a * b;
          case DIVIDE -> b == 0 ? null : a / b;
          case REMAINDER -> b == 0 ? null : a % b;
          default -> null;
        };
      }
      if (left instanceof Number a
          && right instanceof Number b
          && (left instanceof Double || right instanceof Double)) {
        double x = a.doubleValue();
        double y = b.doubleValue();
        return switch (operator) {
          case PLUS -> x + y;
          case MINUS -> x - y;
          case MULTIPLY -> x * y;
          case DIVIDE -> x / y;
          default -> null;
        };
      }
      return null;
    }

    /** An annotation's type: resolved if possible, else guessed from the name as written. */
    private String annotationType(AnnotationExpr a) {
      try {
        return binaryName(a.resolve());
      } catch (RuntimeException | StackOverflowError e) {
        return guessType(a.getNameAsString());
      }
    }

    /**
     * Names a type that does not resolve, as written in this file: from a single-type import, as
     * qualified when written qualified, from a wildcard import that holds a well-known annotation
     * or enum of that name, as {@code java.lang}'s annotation, or else as if it were in the same
     * package.
     */
    private String guessType(String name) {
      int dot = name.indexOf('.');
      String first = dot < 0 ? name : name.substring(0, dot);
      String rest = dot < 0 ? "" : name.substring(dot).replace('.', '$');
      for (ImportDeclaration i : unit.getImports()) {
        if (!i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(first)) {
          return i.getNameAsString() + rest;
        }
      }
      if (dot >= 0) {
        return name;
      }
      for (ImportDeclaration i : unit.getImports()) {
        if (!i.isStatic() && i.isAsterisk()) {
          String candidate = i.getNameAsString() + "." + name;
          if (WellKnownAnnotations.isKnown(candidate)
              || WellKnownAnnotations.isKnownEnum(candidate)) {
            return candidate;
          }
        }
      }
      if (JAVA_LANG_ANNOTATIONS.contains(name)) {
        return "java.lang." + name;
      }
      return packageName.isEmpty() ? name : packageName + "." + name;
    }

    // -------------------------------------------------------------------------------------------
    // Calls

    private void call(
        Node node,
        Supplier<? extends ResolvedMethodLikeDeclaration> resolve,
        Position name,
        List<com.github.javaparser.ast.expr.Expression> arguments,
        com.github.javaparser.ast.expr.Expression on) {
      EntityId caller = owner(node);
      if (caller == null) {
        return;
      }
      EntityId callee;
      String receiver = null;
      try {
        ResolvedMethodLikeDeclaration method = resolve.get();
        callee = calleeId(method);
        if (on != null && method instanceof ResolvedMethodDeclaration) {
          receiver = receiver(on);
          if (receiver != null && receiver.equals(binaryName(method.declaringType()))) {
            receiver = null;
          }
        }
      } catch (RuntimeException | StackOverflowError e) {
        // Not resolvable without the classpath the build would have given.
        return;
      }
      if (callee == null) {
        return;
      }
      int start = name != null ? name.line : node.getBegin().map(p -> p.line).orElse(0);
      int end = node.getEnd().map(p -> p.line).orElse(start);
      List<String> constants = new ArrayList<>();
      for (int i = 0; i < arguments.size(); i++) {
        constants.add(
            ModuleStructure.stringParameter(callee, i)
                    && constant(arguments.get(i), 0) instanceof String s
                ? s
                : null);
      }
      structure.calls.add(
          new Call(caller, callee, Location.of(file, start, end), constants, receiver));
    }

    /**
     * The binary name of the static type of what a method call or method reference is written on,
     * when it is a named type: a value's type, or the type itself for a type name; null otherwise.
     */
    private String receiver(com.github.javaparser.ast.expr.Expression on) {
      ResolvedReferenceTypeDeclaration type = null;
      try {
        if (on instanceof TypeExpr t) {
          ResolvedType resolved = t.getType().resolve();
          type =
              resolved.isReferenceType()
                  ? resolved.asReferenceType().getTypeDeclaration().orElse(null)
                  : null;
        } else {
          ResolvedType resolved = on.calculateResolvedType();
          type =
              resolved.isReferenceType()
                  ? resolved.asReferenceType().getTypeDeclaration().orElse(null)
                  : null;
        }
      } catch (RuntimeException | StackOverflowError e) {
        // Not a value, or not resolvable: a type name is tried next.
      }
      if (type == null && (on instanceof NameExpr || on instanceof FieldAccessExpr)) {
        try {
          var reference = JavaParserFactory.getContext(on, solver).solveType(on.toString());
          if (reference.isSolved()
              && reference.getCorrespondingDeclaration()
                  instanceof ResolvedReferenceTypeDeclaration r) {
            type = r;
          }
        } catch (RuntimeException | StackOverflowError e) {
          // Not resolvable without the classpath the build would have given.
        }
      }
      try {
        // A local or anonymous class is not an entity, as for javac.
        boolean local =
            type != null
                && type.toAst().orElse(null) instanceof Node declaration
                && (declaration.findAncestor(BlockStmt.class).isPresent()
                    || declaration.findAncestor(ObjectCreationExpr.class).isPresent());
        return type == null || local ? null : binaryName(type);
      } catch (RuntimeException e) {
        return null;
      }
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
    // Expressions: calls on tracked APIs and values given to fields, read as javac reads them

    /** Records a call on a tracked API that is not what another call is made on. */
    private void chain(com.github.javaparser.ast.expr.Expression call) {
      if (isReceiver(call)) {
        return;
      }
      String rootType = rootType(call);
      if (!TrackedApis.isTracked(rootType)) {
        return;
      }
      EntityId caller = owner(call);
      if (caller != null && expression(call, caller, 0) instanceof Expression.Invoke invoke) {
        structure.invocations.add(
            new ModuleStructure.Invocation(caller, invoke, location(call), rootType));
      }
    }

    /**
     * Records a call of a method its caller's own type declares, written without a receiver or on
     * {@code this}, with its arguments: the only method of that name taking as many arguments, else
     * the one the call resolves to.
     */
    private void ownCall(MethodCallExpr call) {
      if (call.getArguments().isEmpty()
          || call.getScope().isPresent()
              && !(call.getScope().get() instanceof ThisExpr t && t.getTypeName().isEmpty())) {
        return;
      }
      EntityId caller = owner(call);
      TypeDeclaration<?> type = null;
      for (Node n = call.getParentNode().orElse(null);
          n != null && type == null;
          n = n.getParentNode().orElse(null)) {
        if (n instanceof TypeDeclaration<?> t && binaryNames.containsKey(t)) {
          type = t;
        }
      }
      if (caller == null || type == null) {
        return;
      }
      String binary = binaryNames.get(type);
      String name = call.getNameAsString();
      int arity = call.getArguments().size();
      List<MethodDeclaration> candidates =
          type.getMethods().stream()
              .filter(m -> m.getNameAsString().equals(name))
              .filter(m -> m.getParameters().size() == arity)
              .filter(m -> m.getParameters().stream().noneMatch(Parameter::isVarArgs))
              .toList();
      EntityId callee = candidates.size() == 1 ? callables.get(candidates.getFirst()) : null;
      if (callee == null) {
        try {
          ResolvedMethodDeclaration method = call.resolve();
          callee = binary.equals(binaryName(method.declaringType())) ? calleeId(method) : null;
        } catch (RuntimeException | StackOverflowError e) {
          // Not resolvable without the classpath the build would have given.
        }
      }
      if (callee == null
          || !(caller.type().equals(BuiltInTypes.TYPE)
              ? caller.key().equals(binary)
              : caller.key().startsWith(binary + "#"))) {
        return;
      }
      structure.ownCalls.add(
          new ModuleStructure.OwnCall(
              caller, callee, arguments(call.getArguments(), caller, 0), location(call)));
    }

    private static boolean isReceiver(Node node) {
      return node.getParentNode().orElse(null) instanceof MethodCallExpr parent
          && parent.getScope().orElse(null) == node;
    }

    /** Field initializers, and assignments to a type's own fields in its constructors. */
    private void fieldValues() {
      for (FieldDeclaration f : unit.findAll(FieldDeclaration.class)) {
        String type = binaryNames.get(f.getParentNode().orElse(null));
        if (type == null) {
          continue;
        }
        EntityId in = ModuleStructure.typeId(type);
        for (VariableDeclarator v : f.getVariables()) {
          if (v.getInitializer().isEmpty()
              || in == null
              || isConstant(f, v)
              || !TrackedApis.isFollowed(describe(v.getType(), v))) {
            continue;
          }
          EntityId field = ModuleStructure.fieldId(type, v.getNameAsString());
          if (field != null) {
            structure.assignments.add(
                new ModuleStructure.Assignment(
                    field, in, expression(v.getInitializer().get(), in, 0), location(v)));
          }
        }
      }
      for (AssignExpr a : unit.findAll(AssignExpr.class)) {
        if (a.getOperator() != AssignExpr.Operator.ASSIGN) {
          continue;
        }
        String name;
        if (a.getTarget() instanceof NameExpr n) {
          name = n.getNameAsString();
        } else if (a.getTarget() instanceof FieldAccessExpr f
            && f.getScope() instanceof ThisExpr t
            && t.getTypeName().isEmpty()) {
          name = f.getNameAsString();
        } else {
          continue;
        }
        Binding binding =
            a.getTarget() instanceof NameExpr ? bind(a.getTarget(), name) : field(a, name);
        if (!(binding instanceof Binding.Field field)
            || field.declarator() == null
            || !TrackedApis.isFollowed(describe(field.fieldType(), a))) {
          continue;
        }
        EntityId in = owner(a);
        String type = field.type();
        if (in == null
            || !(in.type().equals(BuiltInTypes.TYPE)
                ? in.key().equals(type)
                : in.key().startsWith(type + "#" + ModuleStructure.CONSTRUCTOR + "("))) {
          continue;
        }
        EntityId id = ModuleStructure.fieldId(type, name);
        if (id != null) {
          structure.assignments.add(
              new ModuleStructure.Assignment(id, in, expression(a.getValue(), in, 0), location(a)));
        }
      }
    }

    /** Whether a field is a constant variable: final, of a primitive type or String, folded. */
    private boolean isConstant(FieldDeclaration f, VariableDeclarator v) {
      boolean isFinal =
          f.isFinal()
              || f.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration c
                  && c.isInterface();
      String type = v.getType().asString();
      boolean constantType =
          v.getType().isPrimitiveType() || type.equals("String") || type.equals("java.lang.String");
      return isFinal
          && constantType
          && v.getInitializer().isPresent()
          && constant(v.getInitializer().get(), 0) != null;
    }

    /** What a name in the code refers to. */
    private sealed interface Binding {

      /** A local variable declared in a block. */
      record Local(VariableDeclarator declarator) implements Binding {}

      /** A parameter of a method or constructor. */
      record Param(CallableDeclaration<?> callable, int index, Type type) implements Binding {}

      /**
       * A field of a named type, declared as a variable or as a record component (declarator null).
       */
      record Field(
          String type, FieldDeclaration declaration, VariableDeclarator declarator, Type fieldType)
          implements Binding {}

      /** A lambda's parameter, not followed; its type is known only in some cases. */
      record LambdaParam(LambdaExpr lambda, int index) implements Binding {}

      /** Something else in scope, not followed: a catch parameter, a loop variable. */
      record Other() implements Binding {}
    }

    /** The variable a simple name refers to where it is written, or null if none is in scope. */
    private Binding bind(Node at, String name) {
      Node child = at;
      for (Node p = at.getParentNode().orElse(null);
          p != null;
          child = p, p = p.getParentNode().orElse(null)) {
        switch (p) {
          case BlockStmt block -> {
            VariableDeclarator found = null;
            for (Statement st : block.getStatements()) {
              if (st == child) {
                break;
              }
              if (st instanceof ExpressionStmt es
                  && es.getExpression() instanceof VariableDeclarationExpr declaration) {
                for (VariableDeclarator v : declaration.getVariables()) {
                  if (v.getNameAsString().equals(name)) {
                    found = v;
                  }
                }
              }
            }
            if (found != null) {
              return new Binding.Local(found);
            }
          }
          case SwitchEntry entry -> {
            for (Statement st : entry.getStatements()) {
              if (st == child) {
                break;
              }
              if (declares(st, name)) {
                return new Binding.Other();
              }
            }
          }
          case LambdaExpr lambda -> {
            NodeList<Parameter> parameters = lambda.getParameters();
            for (int i = 0; i < parameters.size(); i++) {
              if (parameters.get(i).getNameAsString().equals(name)) {
                return new Binding.LambdaParam(lambda, i);
              }
            }
          }
          case CatchClause c -> {
            if (c.getParameter().getNameAsString().equals(name)) {
              return new Binding.Other();
            }
          }
          case ForEachStmt f -> {
            if (f.getVariable().getVariables().stream()
                .anyMatch(v -> v.getNameAsString().equals(name))) {
              return new Binding.Other();
            }
          }
          case ForStmt f -> {
            if (f.getInitialization().stream().anyMatch(i -> declares(i, name))) {
              return new Binding.Other();
            }
          }
          case TryStmt t -> {
            if (t.getResources().stream().anyMatch(r -> declares(r, name))) {
              return new Binding.Other();
            }
          }
          case CallableDeclaration<?> callable -> {
            NodeList<Parameter> parameters = callable.getParameters();
            for (int i = 0; i < parameters.size(); i++) {
              if (parameters.get(i).getNameAsString().equals(name)) {
                return new Binding.Param(callable, i, parameters.get(i).getType());
              }
            }
          }
          case ObjectCreationExpr anonymous when anonymous.getAnonymousClassBody().isPresent() -> {
            for (BodyDeclaration<?> m : anonymous.getAnonymousClassBody().get()) {
              if (m instanceof FieldDeclaration f
                  && f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(name))) {
                return new Binding.Other();
              }
            }
          }
          case TypeDeclaration<?> type -> {
            Binding field = ownField(type, name);
            if (field != null) {
              return field;
            }
          }
          default -> {}
        }
      }
      return null;
    }

    /** The field {@code this.name} refers to: one of the type whose code it is written in. */
    private Binding field(Node at, String name) {
      for (Node p = at.getParentNode().orElse(null);
          p != null;
          p = p.getParentNode().orElse(null)) {
        if (p instanceof ObjectCreationExpr anonymous
            && anonymous.getAnonymousClassBody().isPresent()) {
          return new Binding.Other();
        }
        if (p instanceof TypeDeclaration<?> type) {
          return ownField(type, name);
        }
      }
      return null;
    }

    private Binding ownField(TypeDeclaration<?> type, String name) {
      String binary = binaryNames.get(type);
      if (binary == null) {
        return null;
      }
      for (BodyDeclaration<?> m : type.getMembers()) {
        if (m instanceof FieldDeclaration f) {
          for (VariableDeclarator v : f.getVariables()) {
            if (v.getNameAsString().equals(name)) {
              return new Binding.Field(binary, f, v, v.getType());
            }
          }
        }
      }
      if (type instanceof RecordDeclaration record) {
        for (Parameter component : record.getParameters()) {
          if (component.getNameAsString().equals(name)) {
            return new Binding.Field(binary, null, null, component.getType());
          }
        }
      }
      return null;
    }

    private static boolean declares(Node node, String name) {
      return node.findAll(VariableDeclarator.class).stream()
          .anyMatch(v -> v.getNameAsString().equals(name));
    }

    /** Whether a local variable is assigned anywhere in the code it is declared in. */
    private static boolean isReassigned(VariableDeclarator v) {
      Node scope = v;
      while (scope.getParentNode().isPresent()
          && !(scope instanceof CallableDeclaration<?>)
          && !(scope instanceof InitializerDeclaration)
          && !(scope instanceof LambdaExpr)) {
        scope = scope.getParentNode().get();
      }
      String name = v.getNameAsString();
      for (AssignExpr a : scope.findAll(AssignExpr.class)) {
        if (a.getTarget() instanceof NameExpr n && n.getNameAsString().equals(name)) {
          return true;
        }
      }
      for (UnaryExpr u : scope.findAll(UnaryExpr.class)) {
        if (u.getExpression() instanceof NameExpr n
            && n.getNameAsString().equals(name)
            && (u.isPrefix() || u.isPostfix())
            && u.getOperator() != UnaryExpr.Operator.PLUS
            && u.getOperator() != UnaryExpr.Operator.MINUS
            && u.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
            && u.getOperator() != UnaryExpr.Operator.BITWISE_COMPLEMENT) {
          return true;
        }
      }
      return false;
    }

    private Expression expression(Node e, EntityId owner, int depth) {
      if (depth > JavacAnalyzer.MAX_EXPRESSION_DEPTH) {
        return unknown(e);
      }
      switch (e) {
        case StringLiteralExpr x -> {
          return new Expression.Literal(x.asString());
        }
        case TextBlockLiteralExpr x -> {
          return new Expression.Literal(x.asString());
        }
        case CharLiteralExpr x -> {
          return literal(x);
        }
        case IntegerLiteralExpr x -> {
          return literal(x);
        }
        case LongLiteralExpr x -> {
          return literal(x);
        }
        case DoubleLiteralExpr x -> {
          return literal(x);
        }
        case BooleanLiteralExpr x -> {
          return literal(x);
        }
        case EnclosedExpr x -> {
          return expression(x.getInner(), owner, depth + 1);
        }
        case CastExpr x -> {
          return expression(x.getExpression(), owner, depth + 1);
        }
        case BinaryExpr b when b.getOperator() == BinaryExpr.Operator.PLUS && isString(b) -> {
          return Expression.concat(
              List.of(
                  expression(b.getLeft(), owner, depth + 1),
                  expression(b.getRight(), owner, depth + 1)));
        }
        case NameExpr n -> {
          return name(n, owner, depth);
        }
        case FieldAccessExpr f -> {
          return fieldAccess(f);
        }
        case MethodCallExpr call -> {
          // A chain's receivers are not nesting: a long chain of calls is read whole.
          Expression receiver =
              call.getScope().isPresent() ? expression(call.getScope().get(), owner, depth) : null;
          return new Expression.Invoke(
              receiver, call.getNameAsString(), arguments(call.getArguments(), owner, depth));
        }
        case ClassExpr c when c.getType() instanceof ClassOrInterfaceType -> {
          return new Expression.TypeRef(describe(c.getType(), c));
        }
        case ObjectCreationExpr created -> {
          return new Expression.Invoke(
              new Expression.TypeRef(describe(created.getType(), created)),
              ModuleStructure.CONSTRUCTOR,
              arguments(created.getArguments(), owner, depth));
        }
        case LambdaExpr lambda -> {
          return lambda(lambda, owner, depth);
        }
        default -> {
          return unknown(e);
        }
      }
    }

    /** A lambda: its source, and what its body returns when that is one expression. */
    private Expression lambda(LambdaExpr lambda, EntityId owner, int depth) {
      Expression value;
      if (lambda.getExpressionBody().isPresent()) {
        value = expression(lambda.getExpressionBody().get(), owner, depth + 1);
      } else if (lambda.getBody() instanceof BlockStmt block
          && block.getStatements().size() == 1
          && block.getStatements().getFirst().orElseThrow() instanceof ReturnStmt returned
          && returned.getExpression().isPresent()) {
        value = expression(returned.getExpression().get(), owner, depth + 1);
      } else {
        value = unknown(lambda.getBody());
      }
      Location where = location(lambda);
      return new Expression.Lambda(
          ((Expression.Unknown) unknown(lambda)).source(), value, where.start(), where.end());
    }

    private List<Expression> arguments(
        NodeList<com.github.javaparser.ast.expr.Expression> arguments, EntityId owner, int depth) {
      List<Expression> list = new ArrayList<>();
      for (com.github.javaparser.ast.expr.Expression a : arguments) {
        list.add(expression(a, owner, depth + 1));
      }
      return list;
    }

    private Expression literal(com.github.javaparser.ast.expr.Expression e) {
      Object value = constant(e, 0);
      return value == null ? unknown(e) : new Expression.Literal(String.valueOf(value));
    }

    /** A simple name: a local variable, parameter, field, constant or type. */
    private Expression name(NameExpr n, EntityId owner, int depth) {
      String name = n.getNameAsString();
      Binding binding = bind(n, name);
      switch (binding) {
        case Binding.Local local -> {
          if (!(local.declarator().getParentNode().orElse(null) instanceof VariableDeclarationExpr)
              || local.declarator().getInitializer().isEmpty()
              || isReassigned(local.declarator())) {
            return unknown(n);
          }
          return expression(local.declarator().getInitializer().get(), owner, depth + 1);
        }
        case Binding.Param param -> {
          return owner.equals(callables.get(param.callable()))
              ? new Expression.Param(param.index())
              : unknown(n);
        }
        case Binding.Field field -> {
          return field(field, n);
        }
        case Binding.LambdaParam lambda -> {
          return unknown(n);
        }
        case Binding.Other other -> {
          return unknown(n);
        }
        case null -> {}
      }
      Object constant = constant(n, 0);
      if (constant != null) {
        return new Expression.Literal(String.valueOf(constant));
      }
      try {
        SymbolReference<? extends ResolvedValueDeclaration> ref =
            JavaParserFacade.get(solver).solve(n);
        if (ref.isSolved() && ref.getCorrespondingDeclaration().isField()) {
          return new Expression.FieldRef(
              binaryName(
                  (ResolvedReferenceTypeDeclaration)
                      ref.getCorrespondingDeclaration().asField().declaringType()),
              name);
        }
        if (ref.isSolved() && ref.getCorrespondingDeclaration().isEnumConstant()) {
          return new Expression.FieldRef(
              binaryName(
                  ref.getCorrespondingDeclaration()
                      .asEnumConstant()
                      .getType()
                      .asReferenceType()
                      .getTypeDeclaration()
                      .orElseThrow()),
              name);
        }
      } catch (RuntimeException | StackOverflowError ex) {
        // Not resolvable; named from the imports below.
      }
      return Character.isUpperCase(name.charAt(0))
          ? new Expression.TypeRef(guessType(name))
          : unknown(n);
    }

    /** A field of a type in the snapshot's sources, by its declaration. */
    private Expression field(Binding.Field field, Node at) {
      if (field.declaration() != null && isConstant(field.declaration(), field.declarator())) {
        return new Expression.Literal(
            String.valueOf(constant(field.declarator().getInitializer().get(), 0)));
      }
      String name =
          field.declarator() != null
              ? field.declarator().getNameAsString()
              : ((NodeWithSimpleName<?>) at).getNameAsString();
      return new Expression.FieldRef(field.type(), name);
    }

    /** {@code this.name}, {@code Type.NAME} or {@code object.name}. */
    private Expression fieldAccess(FieldAccessExpr f) {
      String name = f.getNameAsString();
      if (f.getScope() instanceof ThisExpr t && t.getTypeName().isEmpty()) {
        Binding binding = field(f, name);
        return binding instanceof Binding.Field field ? field(field, f) : unknown(f);
      }
      Object constant = constant(f, 0);
      if (constant != null) {
        return new Expression.Literal(String.valueOf(constant));
      }
      if (isPackageName(f.getScope()) && Character.isUpperCase(name.charAt(0))) {
        // A type named in full, a static call's receiver: org.apache.http.client.fluent.Request
        return new Expression.TypeRef(f.toString());
      }
      if (!isTypeName(f.getScope())) {
        return unknown(f);
      }
      try {
        SymbolReference<? extends ResolvedValueDeclaration> ref =
            JavaParserFacade.get(solver).solve(f);
        if (ref.isSolved() && ref.getCorrespondingDeclaration().isField()) {
          return new Expression.FieldRef(
              binaryName(
                  (ResolvedReferenceTypeDeclaration)
                      ref.getCorrespondingDeclaration().asField().declaringType()),
              name);
        }
      } catch (RuntimeException | StackOverflowError ex) {
        // Named from the imports below.
      }
      return new Expression.FieldRef(guessType(f.getScope().toString()), name);
    }

    /** Whether an expression names a type rather than a value: no variable is in scope by it. */
    private boolean isTypeName(Node scope) {
      return switch (scope) {
        case NameExpr n ->
            bind(n, n.getNameAsString()) == null
                && Character.isUpperCase(n.getNameAsString().charAt(0));
        case FieldAccessExpr f ->
            isPackageOrTypeName(f.getScope())
                && Character.isUpperCase(f.getNameAsString().charAt(0));
        default -> false;
      };
    }

    /** Whether an expression names a package: lower-case names, no variable in scope by one. */
    private boolean isPackageName(Node scope) {
      return switch (scope) {
        case NameExpr n ->
            bind(n, n.getNameAsString()) == null
                && Character.isLowerCase(n.getNameAsString().charAt(0));
        case FieldAccessExpr f ->
            isPackageName(f.getScope()) && Character.isLowerCase(f.getNameAsString().charAt(0));
        default -> false;
      };
    }

    private boolean isPackageOrTypeName(Node scope) {
      return switch (scope) {
        case NameExpr n -> bind(n, n.getNameAsString()) == null;
        case FieldAccessExpr f -> isPackageOrTypeName(f.getScope());
        default -> false;
      };
    }

    /** Whether a {@code +} concatenates strings, as far as parsing can tell. */
    private boolean isString(Node e) {
      return switch (e) {
        case StringLiteralExpr x -> true;
        case TextBlockLiteralExpr x -> true;
        case EnclosedExpr x -> isString(x.getInner());
        case BinaryExpr b ->
            b.getOperator() == BinaryExpr.Operator.PLUS
                && (isString(b.getLeft()) || isString(b.getRight()));
        case NameExpr n -> {
          Binding binding = bind(n, n.getNameAsString());
          yield binding != null
              ? "java.lang.String".equals(declaredType(binding, n))
              : constant(n, 0) instanceof String;
        }
        case FieldAccessExpr f -> constant(f, 0) instanceof String;
        default -> false;
      };
    }

    /** The declared type of a variable, or null if it cannot be named (a {@code var}). */
    private String declaredType(Binding binding, Node at) {
      Type type =
          switch (binding) {
            case Binding.Local local -> local.declarator().getType();
            case Binding.Param param -> param.type();
            case Binding.Field field -> field.fieldType();
            default -> null;
          };
      if (type == null || type.isVarType()) {
        return null;
      }
      return describe(type, at);
    }

    /**
     * The type of what the innermost call of a chain is made on, or of the object a constructor
     * call makes; null for a call without a receiver or when parsing cannot name it.
     */
    private String rootType(Node e) {
      Node n = e;
      while (true) {
        switch (n) {
          case MethodCallExpr call -> {
            if (call.getScope().isEmpty()) {
              return null;
            }
            n = call.getScope().get();
          }
          case EnclosedExpr x -> n = x.getInner();
          case ObjectCreationExpr created -> {
            return describe(created.getType(), created);
          }
          case CastExpr cast -> {
            return describe(cast.getType(), cast);
          }
          case NameExpr name -> {
            Binding binding = bind(name, name.getNameAsString());
            if (binding instanceof Binding.Other) {
              return null;
            }
            if (binding instanceof Binding.LambdaParam lambda) {
              return lambdaParameterType(lambda.lambda(), lambda.index());
            }
            if (binding != null) {
              return declaredType(binding, name);
            }
            return isTypeName(name) ? guessType(name.getNameAsString()) : null;
          }
          case FieldAccessExpr f -> {
            if (f.getScope() instanceof ThisExpr t && t.getTypeName().isEmpty()) {
              Binding binding = field(f, f.getNameAsString());
              return binding != null ? declaredType(binding, f) : null;
            }
            try {
              return describe(f.calculateResolvedType());
            } catch (RuntimeException | StackOverflowError ex) {
              return isTypeName(f) ? guessType(f.toString()) : null;
            }
          }
          default -> {
            return null;
          }
        }
      }
    }

    /**
     * The type of a lambda's parameter, as javac infers it: its type if written, else, for the
     * lambdas of the tracked APIs whose functional types {@link TrackedApis} names, the type it
     * gives; null if parsing cannot tell.
     */
    private String lambdaParameterType(LambdaExpr lambda, int index) {
      Parameter parameter = lambda.getParameters().get(index);
      if (!(parameter.getType() instanceof UnknownType)) {
        return describe(parameter.getType(), parameter);
      }
      Node parent = lambda.getParentNode().orElse(null);
      if (parent instanceof MethodCallExpr call && call.getArguments().contains(lambda)) {
        // groups.forEachClient((group, builder) -> ...): the receiver's type, and its type argument
        // for a parameter of the receiver's generic type.
        String receiver = call.getScope().map(this::rootType).orElse(null);
        List<String> types =
            TrackedApis.lambdaParameters(
                receiver, call.getNameAsString(), lambda.getParameters().size());
        String type = index < types.size() ? types.get(index) : null;
        if (TrackedApis.TYPE_ARGUMENT.equals(type)) {
          return call.getScope().map(this::typeArgument).orElse(null);
        }
        return type;
      }
      String functional = functionalType(lambda);
      List<String> types = TrackedApis.lambdaParameters(functional);
      return index < types.size() ? types.get(index) : null;
    }

    /**
     * The functional type a lambda is written for, from where it is written: the return type of the
     * method it is returned from, the type of the variable it initializes, or a cast.
     */
    private String functionalType(LambdaExpr lambda) {
      Node parent = lambda.getParentNode().orElse(null);
      Type type = null;
      if (parent instanceof ReturnStmt) {
        for (Node p = parent.getParentNode().orElse(null);
            p != null && !(p instanceof LambdaExpr);
            p = p.getParentNode().orElse(null)) {
          if (p instanceof MethodDeclaration method) {
            type = method.getType();
            break;
          }
          if (p instanceof CallableDeclaration<?> || p instanceof TypeDeclaration<?>) {
            break;
          }
        }
      } else if (parent instanceof VariableDeclarator v) {
        type = v.getType();
      } else if (parent instanceof CastExpr cast) {
        type = cast.getType();
      }
      return type == null || type.isVarType() ? null : describe(type, lambda);
    }

    /**
     * The first type argument of the type of what the innermost call of a chain is made on, when it
     * is a parameter written with one ({@code Groups<RestClient.Builder> groups}) or a lambda's
     * parameter whose functional type {@link TrackedApis} names it for.
     */
    private String typeArgument(Node e) {
      Node n = e;
      while (n instanceof MethodCallExpr call && call.getScope().isPresent()) {
        n = call.getScope().get();
      }
      if (!(n instanceof NameExpr name)) {
        return null;
      }
      Binding binding = bind(name, name.getNameAsString());
      Type type =
          switch (binding) {
            case Binding.Param param -> param.type();
            case Binding.Local local -> local.declarator().getType();
            case Binding.Field field -> field.fieldType();
            case Binding.LambdaParam lambda -> {
              Parameter p = lambda.lambda().getParameters().get(lambda.index());
              if (p.getType() instanceof UnknownType) {
                yield null;
              }
              yield p.getType();
            }
            case null, default -> null;
          };
      if (type == null) {
        return binding instanceof Binding.LambdaParam lambda
            ? TrackedApis.typeArgument(functionalType(lambda.lambda()))
            : null;
      }
      if (type instanceof ClassOrInterfaceType c
          && c.getTypeArguments().isPresent()
          && !c.getTypeArguments().get().isEmpty()) {
        return describe(c.getTypeArguments().get().get(0), e);
      }
      return null;
    }

    /** An expression not followed, with its source text. */
    private static Expression unknown(Node e) {
      return new Expression.Unknown(
          e.getTokenRange().map(r -> r.toString()).orElseGet(e::toString));
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
