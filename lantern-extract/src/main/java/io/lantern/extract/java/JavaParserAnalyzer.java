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
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
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
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.AssociableToAST;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
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
        call(call, () -> call.resolve(), name, call.getArguments());
      }
      for (ObjectCreationExpr creation : unit.findAll(ObjectCreationExpr.class)) {
        // An anonymous class is not an entity; calls in its body count for the enclosing code.
        if (creation.getAnonymousClassBody().isEmpty()) {
          call(creation, () -> creation.resolve(), null, creation.getArguments());
        }
      }
      for (ExplicitConstructorInvocationStmt invocation :
          unit.findAll(ExplicitConstructorInvocationStmt.class)) {
        call(invocation, () -> invocation.resolve(), null, invocation.getArguments());
      }
      for (MethodReferenceExpr reference : unit.findAll(MethodReferenceExpr.class)) {
        call(reference, () -> reference.resolve(), null, List.of());
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
            EntityId id = ModuleStructure.fieldId(binary, constant.getNameAsString());
            if (id != null) {
              members.add(
                  new Member(id, location(constant), annotations(constant.getAnnotations())));
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
                members.add(new Member(id, location(f), annotations(f.getAnnotations())));
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

    private void callable(List<Member> members, BodyDeclaration<?> declaration, EntityId id) {
      if (id != null) {
        callables.put(declaration, id);
        members.add(
            new Member(id, location(declaration), annotations(declaration.getAnnotations())));
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
      List<AnnotationExpr> nested = new ArrayList<>();
      if (a instanceof SingleMemberAnnotationExpr single) {
        values.put(VALUE, values(single.getMemberValue(), nested));
      } else if (a instanceof NormalAnnotationExpr normal) {
        for (MemberValuePair pair : normal.getPairs()) {
          String element = pair.getNameAsString();
          values.put(element, values(pair.getValue(), element.equals(VALUE) ? nested : null));
        }
      }
      if (!nested.isEmpty() && values.size() == 1 && values.get(VALUE).isEmpty()) {
        for (AnnotationExpr n : nested) {
          annotation(n, where, annotations);
        }
        return;
      }
      annotations.add(new Annotation(annotationType(a), values, where));
    }

    /** An element's value as strings, constants folded; nested annotations go to {@code nested}. */
    private List<String> values(Expression value, List<AnnotationExpr> nested) {
      List<Expression> items =
          value instanceof ArrayInitializerExpr array ? array.getValues() : List.of(value);
      List<String> values = new ArrayList<>();
      for (Expression item : items) {
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
    private Object constant(Expression e, int depth) {
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
        // a well-known enum that annotations take (RequestMethod.GET), named by its imports.
        if (e instanceof FieldAccessExpr f
            && WellKnownAnnotations.isKnownEnum(guessType(f.getScope().toString()))) {
          return f.getNameAsString();
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
        List<Expression> arguments) {
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
      List<String> constants = new ArrayList<>();
      for (int i = 0; i < arguments.size(); i++) {
        constants.add(
            ModuleStructure.stringParameter(callee, i)
                    && constant(arguments.get(i), 0) instanceof String s
                ? s
                : null);
      }
      structure.calls.add(new Call(caller, callee, Location.of(file, start, end), constants));
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
