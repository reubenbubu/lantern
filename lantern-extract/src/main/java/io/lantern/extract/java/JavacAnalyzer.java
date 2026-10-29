package io.lantern.extract.java;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Assignment;
import io.lantern.extract.java.ModuleStructure.Call;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Parameter;
import io.lantern.extract.java.ModuleStructure.SourceFile;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Processor;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Reads a module's structure through javac: its sources are parsed and attributed against the
 * build's classpath, with Lantern's own Lombok as the only annotation processor, so members Lombok
 * generates are seen and calls resolve as the compiler resolves them. Other annotation processors
 * do not run; what they generated is in the build's generated sources, which are compiled with the
 * module's own.
 */
final class JavacAnalyzer {

  /** Superclasses every class of a kind has, which are not worth an {@code EXTENDS} fact. */
  private static final Set<String> IMPLICIT_SUPERCLASSES =
      Set.of("java.lang.Object", "java.lang.Enum", "java.lang.Record");

  private static final String LOMBOK_PROCESSOR =
      "lombok.launch.AnnotationProcessorHider$AnnotationProcessor";

  private static final String ANNOTATION_INTERFACE = "java.lang.annotation.Annotation";

  /** Annotations on annotation types that only say how annotations work, not worth recording. */
  private static final String JAVA_LANG_ANNOTATION = "java.lang.annotation.";

  /** The element a single annotation value is written for. */
  private static final String VALUE = "value";

  /** How deep a constant expression is folded, against pathological code. */
  private static final int MAX_CONSTANT_DEPTH = 16;

  /** How deep expressions are followed, through nested calls and local variables. */
  static final int MAX_EXPRESSION_DEPTH = 24;

  private JavacAnalyzer() {}

  /**
   * Analyzes the sources of one module.
   *
   * @param files the module's sources and generated sources
   * @param classpath the module's compile classpath
   * @param release the Java release to compile for, or 0 for the running JDK's
   * @return the structure, or null if javac reported an error, so the caller falls back to parsing
   */
  static ModuleStructure analyze(List<SourceFile> files, List<Path> classpath, int release)
      throws IOException {
    ModuleStructure structure = compile(files, classpath, release);
    if (structure == null && release > 0) {
      // --release hides JDK-internal APIs (sun.misc and the like) that the build may have used.
      structure = compile(files, classpath, 0);
    }
    return structure;
  }

  private static ModuleStructure compile(List<SourceFile> files, List<Path> classpath, int release)
      throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("No Java compiler: Lantern must run on a JDK, not a JRE");
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
      // No source path: only the given sources are compiled, never sources found on the classpath.
      fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
      Map<Path, SourceFile> byPath = new HashMap<>();
      for (SourceFile f : files) {
        byPath.put(f.file().toAbsolutePath().normalize(), f);
      }
      List<String> options =
          new ArrayList<>(List.of("-proc:full", "-implicit:none", "-nowarn", "-Xlint:none"));
      if (release > 0) {
        options.addAll(List.of("--release", Integer.toString(release)));
      }
      JavacTask task =
          (JavacTask)
              compiler.getTask(
                  new StringWriter(),
                  fileManager,
                  diagnostics,
                  options,
                  null,
                  fileManager.getJavaFileObjectsFromPaths(
                      files.stream().map(SourceFile::file).toList()));
      task.setProcessors(List.of(lombok()));
      List<CompilationUnitTree> units = new ArrayList<>();
      try {
        task.parse().forEach(units::add);
        task.analyze();
        if (diagnostics.getDiagnostics().stream()
            .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
          return null;
        }
        return new Reader(task).read(units, byPath);
      } catch (RuntimeException | StackOverflowError | AssertionError | LinkageError e) {
        // javac or Lombok failed outright on this code; parsing still gives facts.
        return null;
      }
    }
  }

  /** Lantern's own Lombok annotation processor. */
  private static Processor lombok() {
    try {
      // Lombok hides its processor in a package-private class, so it is named rather than imported.
      return (Processor)
          Class.forName(LOMBOK_PROCESSOR, true, JavacAnalyzer.class.getClassLoader())
              .getConstructor()
              .newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot load Lombok's annotation processor", e);
    }
  }

  /** Reads the attributed trees of one compilation. */
  private static final class Reader {

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final SourcePositions positions;
    private final ModuleStructure structure = new ModuleStructure();

    Reader(JavacTask task) {
      this.trees = Trees.instance(task);
      this.elements = task.getElements();
      this.types = task.getTypes();
      this.positions = trees.getSourcePositions();
    }

    ModuleStructure read(List<CompilationUnitTree> units, Map<Path, SourceFile> byPath) {
      for (CompilationUnitTree unit : units) {
        SourceFile file = byPath.get(Path.of(unit.getSourceFile().toUri()).normalize());
        if (file == null) {
          continue;
        }
        for (Tree decl : unit.getTypeDecls()) {
          if (decl instanceof ClassTree) {
            Element e = trees.getElement(TreePath.getPath(unit, decl));
            if (e instanceof TypeElement type) {
              readType(type, file);
            }
          }
        }
        CallScanner scanner = new CallScanner(unit, file);
        scanner.scan(unit, null);
        scanner.values();
      }
      return structure;
    }

    private void readType(TypeElement type, SourceFile file) {
      String binary = binaryName(type);
      Location where = location(trees.getPath(type), file, null);
      ClassTree tree = trees.getTree(type);
      String outer =
          type.getNestingKind() == NestingKind.MEMBER
              ? binaryName((TypeElement) type.getEnclosingElement())
              : null;
      List<Supertype> supertypes = new ArrayList<>();
      boolean isInterface =
          type.getKind() == ElementKind.INTERFACE || type.getKind() == ElementKind.ANNOTATION_TYPE;
      TypeMirror superclass = type.getSuperclass();
      if (superclass.getKind() == TypeKind.DECLARED) {
        String name = describe(superclass);
        if (!IMPLICIT_SUPERCLASSES.contains(name)) {
          Tree clause = tree == null ? null : tree.getExtendsClause();
          supertypes.add(
              new Supertype(
                  BuiltInTypes.EXTENDS,
                  name,
                  clauseLocation(type, clause, where, file),
                  typeArguments(superclass),
                  typeVariables(type, superclass)));
        }
      }
      List<? extends Tree> clauses = tree == null ? List.of() : tree.getImplementsClause();
      List<? extends TypeMirror> interfaces = type.getInterfaces();
      for (int i = 0; i < interfaces.size(); i++) {
        String name = describe(interfaces.get(i));
        if (type.getKind() == ElementKind.ANNOTATION_TYPE && name.equals(ANNOTATION_INTERFACE)) {
          continue;
        }
        Tree clause = i < clauses.size() ? clauses.get(i) : null;
        supertypes.add(
            new Supertype(
                isInterface ? BuiltInTypes.EXTENDS : BuiltInTypes.IMPLEMENTS,
                name,
                clauseLocation(type, clause, where, file),
                typeArguments(interfaces.get(i)),
                typeVariables(type, interfaces.get(i))));
      }
      List<Member> members = new ArrayList<>();
      List<TypeElement> nested = new ArrayList<>();
      for (Element e : type.getEnclosedElements()) {
        switch (e.getKind()) {
          case METHOD, CONSTRUCTOR -> {
            if (!isImplicitEnumMethod(type, e)) {
              add(members, methodId((ExecutableElement) e), e, where, file);
            }
          }
          case FIELD, ENUM_CONSTANT ->
              add(
                  members,
                  ModuleStructure.fieldId(binary, e.getSimpleName().toString()),
                  e,
                  where,
                  file);
          case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> nested.add((TypeElement) e);
          default -> {}
        }
      }
      structure.types.add(
          new TypeDecl(
              binary,
              elements.getPackageOf(type).getQualifiedName().toString(),
              outer,
              kind(type),
              where,
              supertypes,
              members,
              annotations(type, where, file)));
      for (TypeElement n : nested) {
        readType(n, file);
      }
    }

    private void add(
        List<Member> members, EntityId id, Element e, Location typeLocation, SourceFile file) {
      if (id != null) {
        Location where = location(trees.getPath(e), file, typeLocation);
        String type =
            e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.ENUM_CONSTANT
                ? describe(e.asType())
                : null;
        TreePath path = e.getKind() == ElementKind.METHOD ? trees.getPath(e) : null;
        String returns =
            path != null && path.getLeaf() instanceof MethodTree
                ? describe(((ExecutableElement) e).getReturnType())
                : null;
        members.add(
            new Member(
                id, where, annotations(e, where, file), type, parameters(e, where, file), returns));
      }
    }

    /**
     * The parameters of a method or constructor written in the source, with their annotations; none
     * for a field, an implicit member or a record's constructor, which parsing cannot tell apart.
     */
    private List<Parameter> parameters(Element e, Location where, SourceFile file) {
      if (!(e instanceof ExecutableElement method)
          || e.getEnclosingElement().getKind() == ElementKind.RECORD
              && e.getKind() == ElementKind.CONSTRUCTOR) {
        return List.of();
      }
      TreePath path = trees.getPath(e);
      if (path == null
          || !(path.getLeaf() instanceof MethodTree tree)
          || tree.getParameters().size() != method.getParameters().size()) {
        return List.of();
      }
      List<Parameter> parameters = new ArrayList<>();
      for (int i = 0; i < method.getParameters().size(); i++) {
        VariableElement p = method.getParameters().get(i);
        TreePath written = new TreePath(path, tree.getParameters().get(i));
        Location at = location(written, file, where);
        List<Annotation> annotations = new ArrayList<>();
        List<? extends AnnotationTree> annotationTrees =
            tree.getParameters().get(i).getModifiers().getAnnotations();
        List<? extends AnnotationMirror> mirrors = p.getAnnotationMirrors();
        for (int j = 0; j < mirrors.size(); j++) {
          Location own =
              mirrors.size() == annotationTrees.size()
                  ? location(new TreePath(written, annotationTrees.get(j)), file, at)
                  : at;
          annotation(mirrors.get(j), own, annotations, null, file);
        }
        parameters.add(new Parameter(p.getSimpleName().toString(), annotations));
      }
      return parameters;
    }

    // -------------------------------------------------------------------------------------------
    // Annotations

    /** The annotations written on an element, each where it is written. */
    private List<Annotation> annotations(Element e, Location fallback, SourceFile file) {
      List<Annotation> annotations = new ArrayList<>();
      for (AnnotationMirror m : e.getAnnotationMirrors()) {
        annotation(m, where(e, m, fallback, file), annotations, e, file);
      }
      return annotations;
    }

    /** Where an annotation of a source element is written, or the fallback if javac cannot say. */
    private Location where(Element e, AnnotationMirror m, Location fallback, SourceFile file) {
      TreePath path = trees.getPath(e, m);
      return path == null ? fallback : location(path, file, fallback);
    }

    /**
     * Adds an annotation, or the annotations a container of repeated annotations holds, and records
     * the meta-annotations of its type.
     *
     * @param element the source element it is written on, or null for a meta-annotation
     */
    private void annotation(
        AnnotationMirror m,
        Location where,
        List<Annotation> annotations,
        Element element,
        SourceFile file) {
      TypeElement type = (TypeElement) m.getAnnotationType().asElement();
      Map<String, List<String>> values = new LinkedHashMap<>();
      Map<String, List<AnnotationMirror>> nestedByElement = new LinkedHashMap<>();
      elements(m, values, nestedByElement);
      List<AnnotationMirror> nested = nestedByElement.getOrDefault(VALUE, List.of());
      if (!nested.isEmpty() && values.size() == 1 && values.get(VALUE).isEmpty()) {
        // javac makes up the container of repeated annotations; each has its own place.
        List<Location> places = element == null ? List.of() : repeated(element, nested, file);
        for (int i = 0; i < nested.size(); i++) {
          Location own = i < places.size() && places.get(i) != null ? places.get(i) : where;
          annotation(nested.get(i), own, annotations, element, file);
        }
        return;
      }
      annotations.add(
          new Annotation(binaryName(type), values, nested(nestedByElement, where), where));
      metaAnnotations(type);
    }

    /** An annotation's element values, with the annotations written as values apart. */
    private void elements(
        AnnotationMirror m,
        Map<String, List<String>> values,
        Map<String, List<AnnotationMirror>> nestedByElement) {
      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
          m.getElementValues().entrySet()) {
        String name = e.getKey().getSimpleName().toString();
        List<String> list = new ArrayList<>();
        List<AnnotationMirror> inner = new ArrayList<>();
        value(e.getValue().getValue(), list, inner);
        values.put(name, List.copyOf(list));
        if (!inner.isEmpty()) {
          nestedByElement.put(name, inner);
        }
      }
    }

    /** Annotations written as element values, read as written annotations are, recursively. */
    private Map<String, List<Annotation>> nested(
        Map<String, List<AnnotationMirror>> byElement, Location where) {
      Map<String, List<Annotation>> nested = new LinkedHashMap<>();
      byElement.forEach(
          (element, mirrors) -> {
            List<Annotation> list = new ArrayList<>();
            for (AnnotationMirror n : mirrors) {
              Map<String, List<String>> values = new LinkedHashMap<>();
              Map<String, List<AnnotationMirror>> inner = new LinkedHashMap<>();
              elements(n, values, inner);
              TypeElement type = (TypeElement) n.getAnnotationType().asElement();
              list.add(new Annotation(binaryName(type), values, nested(inner, where), where));
            }
            nested.put(element, list);
          });
      return nested;
    }

    /**
     * Where repeated annotations are written on an element, in order: the annotations of their type
     * among those written on it, since javac gives the ones it collected into a container no place.
     */
    private List<Location> repeated(
        Element element, List<AnnotationMirror> nested, SourceFile file) {
      TreePath path = trees.getPath(element);
      List<? extends AnnotationTree> written =
          switch (path == null ? null : path.getLeaf()) {
            case ClassTree c -> c.getModifiers().getAnnotations();
            case MethodTree m -> m.getModifiers().getAnnotations();
            case VariableTree v -> v.getModifiers().getAnnotations();
            case null, default -> List.of();
          };
      String type = binaryName((TypeElement) nested.getFirst().getAnnotationType().asElement());
      List<Location> places = new ArrayList<>();
      for (AnnotationTree a : written) {
        TreePath at = new TreePath(path, a);
        TypeMirror mirror = trees.getTypeMirror(at);
        if (mirror != null
            && mirror.getKind() == TypeKind.DECLARED
            && describe(mirror).equals(type)) {
          places.add(location(at, file, null));
        }
      }
      return places.size() == nested.size() ? places : List.of();
    }

    /** Records the annotations on an annotation type, and on theirs, once each. */
    private void metaAnnotations(TypeElement type) {
      String name = binaryName(type);
      if (name.startsWith(JAVA_LANG_ANNOTATION) || structure.annotationTypes.containsKey(name)) {
        return;
      }
      // Recorded before its own annotations are read, since annotation types can be cyclic.
      structure.annotationTypes.put(name, List.of());
      List<Annotation> meta = new ArrayList<>();
      for (AnnotationMirror m : type.getAnnotationMirrors()) {
        annotation(m, null, meta, null, null);
      }
      structure.annotationTypes.put(name, List.copyOf(meta));
    }

    /** Adds an annotation element's value as strings; nested annotations go to {@code nested}. */
    private void value(Object value, List<String> out, List<AnnotationMirror> nested) {
      switch (value) {
        case AnnotationMirror m -> {
          if (nested != null) {
            nested.add(m);
          }
        }
        case List<?> list -> {
          for (Object item : list) {
            if (item instanceof AnnotationValue v) {
              value(v.getValue(), out, nested);
            }
          }
        }
        case TypeMirror t -> out.add(describe(t));
        case VariableElement constant -> out.add(constant.getSimpleName().toString());
        default -> out.add(String.valueOf(value));
      }
    }

    /**
     * The {@code values()} and {@code valueOf(String)} methods every enum has implicitly. An enum
     * cannot declare them itself, so the name and parameters are enough to tell.
     */
    private boolean isImplicitEnumMethod(TypeElement type, Element e) {
      if (type.getKind() != ElementKind.ENUM || e.getKind() != ElementKind.METHOD) {
        return false;
      }
      ExecutableElement method = (ExecutableElement) e;
      String name = method.getSimpleName().toString();
      List<? extends VariableElement> parameters = method.getParameters();
      return (name.equals("values") && parameters.isEmpty())
          || (name.equals("valueOf")
              && parameters.size() == 1
              && describe(parameters.get(0).asType()).equals("java.lang.String"));
    }

    private Location clauseLocation(
        TypeElement type, Tree clause, Location typeLocation, SourceFile file) {
      if (clause == null) {
        return typeLocation;
      }
      TreePath path = trees.getPath(type);
      if (path == null) {
        return typeLocation;
      }
      return location(new TreePath(path, clause), file, typeLocation);
    }

    /** Where a tree is, or the fallback if it has no position (some generated members). */
    private Location location(TreePath path, SourceFile file, Location fallback) {
      if (path == null) {
        return fallback != null ? fallback : new Location(file, 0, 0);
      }
      CompilationUnitTree unit = path.getCompilationUnit();
      Tree tree = path.getLeaf();
      return lines(unit, file, positions.getStartPosition(unit, tree), end(unit, tree), fallback);
    }

    private long end(CompilationUnitTree unit, Tree tree) {
      return positions.getEndPosition(unit, tree);
    }

    private Location lines(
        CompilationUnitTree unit, SourceFile file, long start, long end, Location fallback) {
      if (start < 0) {
        return fallback != null ? fallback : new Location(file, 0, 0);
      }
      LineMap lines = unit.getLineMap();
      long startLine = lines.getLineNumber(start);
      long endLine = end > start ? lines.getLineNumber(end - 1) : startLine;
      return Location.of(file, startLine, endLine);
    }

    private EntityId methodId(ExecutableElement method) {
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      List<String> parameters = new ArrayList<>();
      for (VariableElement p : method.getParameters()) {
        parameters.add(describe(p.asType()));
      }
      String name =
          method.getKind() == ElementKind.CONSTRUCTOR
              ? ModuleStructure.CONSTRUCTOR
              : method.getSimpleName().toString();
      return ModuleStructure.methodId(binaryName(owner), name, parameters);
    }

    /** The erasures of a declared supertype's type arguments, as {@link #describe} names them. */
    private List<String> typeArguments(TypeMirror supertype) {
      List<String> arguments = new ArrayList<>();
      for (TypeMirror argument : ((DeclaredType) supertype).getTypeArguments()) {
        arguments.add(describe(argument));
      }
      return arguments;
    }

    /**
     * For each type argument of a supertype, the index of the type's own parameter it is, or -1.
     */
    private List<Integer> typeVariables(TypeElement type, TypeMirror supertype) {
      List<Integer> indexes = new ArrayList<>();
      for (TypeMirror argument : ((DeclaredType) supertype).getTypeArguments()) {
        int index = -1;
        if (argument.getKind() == TypeKind.TYPEVAR) {
          index = type.getTypeParameters().indexOf(((TypeVariable) argument).asElement());
          if (index < 0) {
            // A type variable of an enclosing type or method: not one of the type's own.
            index = Integer.MAX_VALUE;
          }
        }
        indexes.add(index);
      }
      return indexes;
    }

    /** A type's erasure as it appears in a member ID: binary names, arrays with {@code []}. */
    private String describe(TypeMirror type) {
      TypeMirror erased = types.erasure(type);
      return switch (erased.getKind()) {
        case ARRAY -> describe(((ArrayType) erased).getComponentType()) + "[]";
        case DECLARED -> binaryName((TypeElement) ((DeclaredType) erased).asElement());
        default -> erased.toString();
      };
    }

    private String binaryName(TypeElement type) {
      return elements.getBinaryName(type).toString();
    }

    private static String kind(TypeElement type) {
      return switch (type.getKind()) {
        case INTERFACE -> ModuleStructure.INTERFACE;
        case ENUM -> ModuleStructure.ENUM;
        case RECORD -> ModuleStructure.RECORD;
        case ANNOTATION_TYPE -> ModuleStructure.ANNOTATION;
        default -> ModuleStructure.CLASS;
      };
    }

    /** Whether the type is top-level or a member of named types, not local or anonymous. */
    private static boolean isNamed(TypeElement type) {
      TypeElement t = type;
      while (t.getNestingKind() == NestingKind.MEMBER) {
        if (!(t.getEnclosingElement() instanceof TypeElement outer)) {
          return false;
        }
        t = outer;
      }
      return t.getNestingKind() == NestingKind.TOP_LEVEL;
    }

    /**
     * Finds the calls in one compilation unit, and the calls on tracked APIs and the values given
     * to fields, which it reads as expressions once the whole unit is scanned.
     */
    private final class CallScanner extends TreePathScanner<Void, Void> {

      private final CompilationUnitTree unit;
      private final SourceFile file;

      /** Outermost calls of chains, read as invocations if they are on a tracked API. */
      private final List<TreePath> chains = new ArrayList<>();

      /** Field initializers and assignments to fields: the field, the value and the whole. */
      private final List<TreePath[]> fieldValues = new ArrayList<>();

      /** Local variables declared in blocks, by element. */
      private final Map<Element, TreePath> locals = new HashMap<>();

      /** Local variables assigned after their declaration. */
      private final Set<Element> reassigned = new HashSet<>();

      private CharSequence source;

      CallScanner(CompilationUnitTree unit, SourceFile file) {
        this.unit = unit;
        this.file = file;
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        if (!isReceiver(getCurrentPath())) {
          chains.add(getCurrentPath());
        }
        if (!isImplicitSuperCall(node)) {
          // The line of the method's name, which in a chain of calls is not the chain's first.
          long name = end(unit, node.getMethodSelect());
          long start = name > 0 ? name - 1 : positions.getStartPosition(unit, node);
          call(start, end(unit, node), node.getArguments());
        }
        return super.visitMethodInvocation(node, unused);
      }

      @Override
      public Void visitNewClass(NewClassTree node, Void unused) {
        // An anonymous class is not an entity; calls in its body count for the enclosing code.
        // An enum constant's creation is not written as a call, and parsing does not see one.
        if (node.getClassBody() == null && !isEnumConstant(getCurrentPath().getParentPath())) {
          call(positions.getStartPosition(unit, node), end(unit, node), node.getArguments());
          if (!isReceiver(getCurrentPath())) {
            chains.add(getCurrentPath());
          }
        }
        return super.visitNewClass(node, unused);
      }

      @Override
      public Void visitVariable(VariableTree node, Void unused) {
        TreePath path = getCurrentPath();
        Element e = trees.getElement(path);
        if (e != null && e.getKind() == ElementKind.LOCAL_VARIABLE) {
          if (path.getParentPath().getLeaf() instanceof BlockTree) {
            locals.put(e, path);
          }
        } else if (e != null
            && e.getKind() == ElementKind.FIELD
            && node.getInitializer() != null
            && ((VariableElement) e).getConstantValue() == null) {
          fieldValues.add(new TreePath[] {path, new TreePath(path, node.getInitializer()), path});
        }
        return super.visitVariable(node, unused);
      }

      @Override
      public Void visitAssignment(AssignmentTree node, Void unused) {
        TreePath target = new TreePath(getCurrentPath(), node.getVariable());
        Element e = trees.getElement(target);
        if (e != null && e.getKind() == ElementKind.LOCAL_VARIABLE) {
          reassigned.add(e);
        } else if (e != null && e.getKind() == ElementKind.FIELD && isOwnField(target)) {
          fieldValues.add(
              new TreePath[] {
                target, new TreePath(getCurrentPath(), node.getExpression()), getCurrentPath()
              });
        }
        return super.visitAssignment(node, unused);
      }

      @Override
      public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
        Element e = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
        if (e != null && e.getKind() == ElementKind.LOCAL_VARIABLE) {
          reassigned.add(e);
        }
        return super.visitCompoundAssignment(node, unused);
      }

      @Override
      public Void visitUnary(UnaryTree node, Void unused) {
        Element e = trees.getElement(new TreePath(getCurrentPath(), node.getExpression()));
        if (e != null && e.getKind() == ElementKind.LOCAL_VARIABLE) {
          reassigned.add(e);
        }
        return super.visitUnary(node, unused);
      }

      /** A field written as {@code name} or {@code this.name}. */
      private boolean isOwnField(TreePath target) {
        return target.getLeaf() instanceof IdentifierTree
            || target.getLeaf() instanceof MemberSelectTree m
                && m.getExpression() instanceof IdentifierTree id
                && id.getName().contentEquals("this");
      }

      /** Whether the call at the path is what another call is made on. */
      private boolean isReceiver(TreePath path) {
        TreePath parent = path.getParentPath();
        return parent != null
            && parent.getLeaf() instanceof MemberSelectTree select
            && parent.getParentPath() != null
            && parent.getParentPath().getLeaf() instanceof MethodInvocationTree call
            && call.getMethodSelect() == select;
      }

      /** Reads the invocations and field values found, now that every local is known. */
      void values() {
        for (TreePath path : chains) {
          String rootType = rootType(path);
          if (!TrackedApis.isTracked(rootType)) {
            continue;
          }
          EntityId caller = owner(path);
          if (caller != null && expression(path, caller, 0) instanceof Expression.Invoke invoke) {
            Tree leaf = path.getLeaf();
            structure.invocations.add(
                new Invocation(
                    caller,
                    invoke,
                    lines(
                        unit, file, positions.getStartPosition(unit, leaf), end(unit, leaf), null),
                    rootType));
          }
        }
        for (TreePath[] value : fieldValues) {
          VariableElement field = (VariableElement) trees.getElement(value[0]);
          if (!(field.getEnclosingElement() instanceof TypeElement declaring)
              || !isNamed(declaring)
              || !TrackedApis.isFollowed(describe(field.asType()))) {
            continue;
          }
          String type = binaryName(declaring);
          EntityId in = owner(value[2]);
          if (in == null || !inOwnCode(in, type)) {
            continue;
          }
          EntityId id = ModuleStructure.fieldId(type, field.getSimpleName().toString());
          if (id != null) {
            structure.assignments.add(
                new Assignment(
                    id, in, expression(value[1], in, 0), location(value[2], file, null)));
          }
        }
      }

      /** Whether code of the member is the type's initializer or one of its constructors. */
      private boolean inOwnCode(EntityId in, String type) {
        return in.type().equals(BuiltInTypes.TYPE)
            ? in.key().equals(type)
            : in.key().startsWith(type + "#" + ModuleStructure.CONSTRUCTOR + "(");
      }

      /**
       * The type of what the innermost call of a chain is made on, or of the object a constructor
       * call makes; null for a call without a receiver.
       */
      private String rootType(TreePath path) {
        TreePath p = path;
        while (true) {
          Tree leaf = p.getLeaf();
          if (leaf instanceof MethodInvocationTree call) {
            if (!(call.getMethodSelect() instanceof MemberSelectTree select)) {
              return null;
            }
            p = new TreePath(new TreePath(p, select), select.getExpression());
          } else if (leaf instanceof ParenthesizedTree paren) {
            p = new TreePath(p, paren.getExpression());
          } else if (leaf instanceof NewClassTree created) {
            return typeName(new TreePath(p, created.getIdentifier()));
          } else {
            return typeName(p);
          }
        }
      }

      private String typeName(TreePath path) {
        TypeMirror type = trees.getTypeMirror(path);
        return type != null && type.getKind() == TypeKind.DECLARED ? describe(type) : null;
      }

      // -----------------------------------------------------------------------------------------
      // Expressions

      private Expression expression(TreePath path, EntityId owner, int depth) {
        if (depth > MAX_EXPRESSION_DEPTH) {
          return unknown(path);
        }
        Tree leaf = path.getLeaf();
        return switch (leaf) {
          case LiteralTree l ->
              l.getValue() == null
                  ? unknown(path)
                  : new Expression.Literal(String.valueOf(l.getValue()));
          case ParenthesizedTree p ->
              expression(new TreePath(path, p.getExpression()), owner, depth + 1);
          case TypeCastTree c ->
              expression(new TreePath(path, c.getExpression()), owner, depth + 1);
          case BinaryTree b when b.getKind() == Tree.Kind.PLUS && isString(path) ->
              Expression.concat(
                  List.of(
                      expression(new TreePath(path, b.getLeftOperand()), owner, depth + 1),
                      expression(new TreePath(path, b.getRightOperand()), owner, depth + 1)));
          case IdentifierTree id -> name(path, owner, depth);
          case MemberSelectTree select when select.getIdentifier().contentEquals("class") -> {
            String type = typeName(new TreePath(path, select.getExpression()));
            yield type == null ? unknown(path) : new Expression.TypeRef(type);
          }
          case MemberSelectTree select -> name(path, owner, depth);
          case MethodInvocationTree call -> {
            Expression receiver = null;
            String method;
            if (call.getMethodSelect() instanceof MemberSelectTree select) {
              TreePath selected = new TreePath(path, select);
              // A chain's receivers are not nesting: a long chain of calls is read whole.
              receiver = expression(new TreePath(selected, select.getExpression()), owner, depth);
              method = select.getIdentifier().toString();
            } else if (call.getMethodSelect() instanceof IdentifierTree id) {
              method = id.getName().toString();
            } else {
              yield unknown(path);
            }
            yield new Expression.Invoke(
                receiver,
                method,
                arguments(path, call.getArguments(), owner, depth),
                parameterTypes(path));
          }
          case NewClassTree created -> {
            String type = typeName(new TreePath(path, created.getIdentifier()));
            yield type == null
                ? unknown(path)
                : new Expression.Invoke(
                    new Expression.TypeRef(type),
                    ModuleStructure.CONSTRUCTOR,
                    arguments(path, created.getArguments(), owner, depth),
                    parameterTypes(path));
          }
          case LambdaExpressionTree lambda -> lambda(path, lambda, owner, depth);
          default -> unknown(path);
        };
      }

      /** A lambda: its source, and what its body returns when that is one expression. */
      private Expression lambda(
          TreePath path, LambdaExpressionTree lambda, EntityId owner, int depth) {
        TreePath body = new TreePath(path, lambda.getBody());
        Expression value;
        if (lambda.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION) {
          value = expression(body, owner, depth + 1);
        } else if (lambda.getBody() instanceof BlockTree block
            && block.getStatements().size() == 1
            && block.getStatements().getFirst() instanceof ReturnTree returned
            && returned.getExpression() != null) {
          value =
              expression(
                  new TreePath(new TreePath(body, returned), returned.getExpression()),
                  owner,
                  depth + 1);
        } else {
          value = unknown(body);
        }
        String source = ((Expression.Unknown) unknown(path)).source();
        Location where =
            lines(unit, file, positions.getStartPosition(unit, lambda), end(unit, lambda), null);
        return new Expression.Lambda(source, value, where.start(), where.end());
      }

      /** The erased parameter types of the method or constructor called, or none if unknown. */
      private List<String> parameterTypes(TreePath path) {
        if (!(trees.getElement(path) instanceof ExecutableElement callee)) {
          return List.of();
        }
        List<String> types = new ArrayList<>();
        for (VariableElement p : callee.getParameters()) {
          types.add(describe(p.asType()));
        }
        return types;
      }

      private List<Expression> arguments(
          TreePath path, List<? extends ExpressionTree> arguments, EntityId owner, int depth) {
        List<Expression> list = new ArrayList<>();
        for (ExpressionTree a : arguments) {
          list.add(expression(new TreePath(path, a), owner, depth + 1));
        }
        return list;
      }

      /** A name: a constant, field, parameter, local variable or type. */
      private Expression name(TreePath path, EntityId owner, int depth) {
        Tree leaf = path.getLeaf();
        if (leaf instanceof IdentifierTree id
            && (id.getName().contentEquals("this") || id.getName().contentEquals("super"))) {
          return unknown(path);
        }
        Element e = trees.getElement(path);
        if (e instanceof TypeElement type) {
          return new Expression.TypeRef(binaryName(type));
        }
        if (!(e instanceof VariableElement variable)) {
          return unknown(path);
        }
        Object constant = variable.getConstantValue();
        if (constant != null) {
          return new Expression.Literal(String.valueOf(constant));
        }
        switch (variable.getKind()) {
          case FIELD, ENUM_CONSTANT -> {
            boolean plain =
                leaf instanceof IdentifierTree
                    || leaf instanceof MemberSelectTree select
                        && (select.getExpression() instanceof IdentifierTree self
                                && self.getName().contentEquals("this")
                            || trees.getElement(new TreePath(path, select.getExpression()))
                                instanceof TypeElement);
            if (plain && variable.getEnclosingElement() instanceof TypeElement declaring) {
              return new Expression.FieldRef(
                  binaryName(declaring), variable.getSimpleName().toString());
            }
            return unknown(path);
          }
          case PARAMETER -> {
            if (variable.getEnclosingElement() instanceof ExecutableElement method) {
              int index = method.getParameters().indexOf(variable);
              if (index >= 0 && owner.equals(methodId(method))) {
                return new Expression.Param(index);
              }
            }
            return unknown(path);
          }
          case LOCAL_VARIABLE -> {
            TreePath declared = locals.get(variable);
            if (declared != null
                && !reassigned.contains(variable)
                && declared.getLeaf() instanceof VariableTree v
                && v.getInitializer() != null) {
              return expression(new TreePath(declared, v.getInitializer()), owner, depth + 1);
            }
            return unknown(path);
          }
          default -> {
            return unknown(path);
          }
        }
      }

      private boolean isString(TreePath path) {
        return "java.lang.String".equals(typeName(path));
      }

      /** An expression not followed, with its source text. */
      private Expression unknown(TreePath path) {
        Tree leaf = path.getLeaf();
        long start = positions.getStartPosition(unit, leaf);
        long end = end(unit, leaf);
        try {
          if (source == null) {
            source = unit.getSourceFile().getCharContent(true);
          }
          if (start >= 0 && end > start && end <= source.length()) {
            return new Expression.Unknown(source.subSequence((int) start, (int) end).toString());
          }
        } catch (IOException e) {
          // Fall back to the tree as javac prints it.
        }
        return new Expression.Unknown(leaf.toString());
      }

      @Override
      public Void visitMemberReference(MemberReferenceTree node, Void unused) {
        call(positions.getStartPosition(unit, node), end(unit, node), List.of());
        return super.visitMemberReference(node, unused);
      }

      private void call(long start, long end, List<? extends ExpressionTree> arguments) {
        TreePath path = getCurrentPath();
        if (!(trees.getElement(path) instanceof ExecutableElement callee)
            || !(callee.getEnclosingElement() instanceof TypeElement declaring)
            || !isNamed(declaring)) {
          return;
        }
        EntityId caller = owner(path);
        EntityId target = methodId(callee);
        if (caller != null && target != null) {
          List<String> constants = new ArrayList<>();
          for (int i = 0; i < arguments.size(); i++) {
            constants.add(
                ModuleStructure.stringParameter(target, i)
                        && constant(new TreePath(path, arguments.get(i)), 0) instanceof String s
                    ? s
                    : null);
          }
          structure.calls.add(
              new Call(
                  caller,
                  target,
                  lines(unit, file, start, end, null),
                  constants,
                  receiver(path, callee, declaring)));
        }
      }

      /**
       * The binary name of the static type of what a method call or method reference is written on,
       * when it is a named type other than the one declaring the method; null otherwise.
       */
      private String receiver(TreePath path, ExecutableElement callee, TypeElement declaring) {
        if (callee.getKind() != ElementKind.METHOD) {
          return null;
        }
        ExpressionTree on =
            switch (path.getLeaf()) {
              case MethodInvocationTree invocation
                  when invocation.getMethodSelect() instanceof MemberSelectTree select ->
                  select.getExpression();
              case MemberReferenceTree reference -> reference.getQualifierExpression();
              default -> null;
            };
        if (on == null) {
          return null;
        }
        TreePath onPath = new TreePath(path, on);
        TypeElement type;
        if (trees.getElement(onPath) instanceof TypeElement named) {
          type = named;
        } else {
          TypeMirror mirror = trees.getTypeMirror(onPath);
          if (mirror == null
              || mirror.getKind() != TypeKind.DECLARED
              || !(types.asElement(mirror) instanceof TypeElement named)) {
            return null;
          }
          type = named;
        }
        return type.equals(declaring) || !isNamed(type) ? null : binaryName(type);
      }

      /**
       * Folds a constant expression as javac does for the parts that make strings: literals,
       * parentheses, casts, {@code +} and constant variables. Returns null if it is not one.
       */
      private Object constant(TreePath path, int depth) {
        if (depth > MAX_CONSTANT_DEPTH) {
          return null;
        }
        return switch (path.getLeaf()) {
          case LiteralTree literal -> literal.getValue();
          case ParenthesizedTree p -> constant(new TreePath(path, p.getExpression()), depth + 1);
          case TypeCastTree cast -> constant(new TreePath(path, cast.getExpression()), depth + 1);
          case BinaryTree b when b.getKind() == Tree.Kind.PLUS -> {
            Object left = constant(new TreePath(path, b.getLeftOperand()), depth + 1);
            Object right = constant(new TreePath(path, b.getRightOperand()), depth + 1);
            yield left instanceof String || right instanceof String
                ? left == null || right == null ? null : String.valueOf(left) + right
                : null;
          }
          case IdentifierTree id -> variableConstant(path);
          case MemberSelectTree select -> variableConstant(path);
          default -> null;
        };
      }

      private Object variableConstant(TreePath path) {
        return trees.getElement(path) instanceof VariableElement v ? v.getConstantValue() : null;
      }

      private boolean isEnumConstant(TreePath path) {
        return path != null
            && path.getLeaf() instanceof VariableTree
            && trees.getElement(path) != null
            && trees.getElement(path).getKind() == ElementKind.ENUM_CONSTANT;
      }

      /**
       * A {@code super(...)} call javac writes itself: the {@code super()} it adds to a constructor
       * that does not start with one, which has the position of the constructor's body, and the
       * call in the constructor it generates for an anonymous class.
       */
      private boolean isImplicitSuperCall(MethodInvocationTree node) {
        if (!(node.getMethodSelect() instanceof IdentifierTree id)
            || !id.getName().contentEquals("super")) {
          return false;
        }
        for (TreePath p = getCurrentPath(); p != null; p = p.getParentPath()) {
          if (p.getLeaf() instanceof MethodTree method) {
            if (trees.getElement(p) instanceof ExecutableElement constructor
                && constructor.getEnclosingElement() instanceof TypeElement type
                && type.getNestingKind() == NestingKind.ANONYMOUS) {
              return true;
            }
            return node.getArguments().isEmpty()
                && method.getBody() != null
                && positions.getStartPosition(unit, node)
                    == positions.getStartPosition(unit, method.getBody());
          }
        }
        return false;
      }

      /**
       * The method or constructor whose code makes a call, or the type for code outside methods.
       * Code in local and anonymous classes counts for the named type's member enclosing them.
       */
      private EntityId owner(TreePath path) {
        for (TreePath p = path; p.getParentPath() != null; p = p.getParentPath()) {
          TreePath parent = p.getParentPath();
          if (parent.getLeaf() instanceof ClassTree
              && trees.getElement(parent) instanceof TypeElement type
              && isNamed(type)) {
            if (p.getLeaf() instanceof MethodTree
                && trees.getElement(p) instanceof ExecutableElement method) {
              return methodId(method);
            }
            return ModuleStructure.typeId(binaryName(type));
          }
        }
        return null;
      }
    }
  }
}
