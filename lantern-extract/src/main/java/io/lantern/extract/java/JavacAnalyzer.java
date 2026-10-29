package io.lantern.extract.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
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
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
        new CallScanner(unit, file).scan(unit, null);
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
              new Supertype(BuiltInTypes.EXTENDS, name, clauseLocation(type, clause, where, file)));
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
                clauseLocation(type, clause, where, file)));
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
        members.add(new Member(id, where, annotations(e, where, file)));
      }
    }

    // -------------------------------------------------------------------------------------------
    // Annotations

    /** The annotations written on an element, each where it is written. */
    private List<Annotation> annotations(Element e, Location fallback, SourceFile file) {
      List<Annotation> annotations = new ArrayList<>();
      for (AnnotationMirror m : e.getAnnotationMirrors()) {
        TreePath path = trees.getPath(e, m);
        Location where = path == null ? fallback : location(path, file, fallback);
        annotation(m, where, annotations);
      }
      return annotations;
    }

    /**
     * Adds an annotation, or the annotations a container of repeated annotations holds, and records
     * the meta-annotations of its type.
     */
    private void annotation(AnnotationMirror m, Location where, List<Annotation> annotations) {
      TypeElement type = (TypeElement) m.getAnnotationType().asElement();
      Map<String, List<String>> values = new LinkedHashMap<>();
      List<AnnotationMirror> nested = new ArrayList<>();
      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
          m.getElementValues().entrySet()) {
        String element = e.getKey().getSimpleName().toString();
        List<String> list = new ArrayList<>();
        value(e.getValue().getValue(), list, element.equals(VALUE) ? nested : null);
        values.put(element, List.copyOf(list));
      }
      if (!nested.isEmpty() && values.size() == 1 && values.get(VALUE).isEmpty()) {
        for (AnnotationMirror n : nested) {
          annotation(n, where, annotations);
        }
        return;
      }
      annotations.add(new Annotation(binaryName(type), values, where));
      metaAnnotations(type);
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
        annotation(m, null, meta);
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

    /** Finds the calls in one compilation unit. */
    private final class CallScanner extends TreePathScanner<Void, Void> {

      private final CompilationUnitTree unit;
      private final SourceFile file;

      CallScanner(CompilationUnitTree unit, SourceFile file) {
        this.unit = unit;
        this.file = file;
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        if (!isImplicitSuperCall(node)) {
          // The line of the method's name, which in a chain of calls is not the chain's first.
          long name = end(unit, node.getMethodSelect());
          long start = name > 0 ? name - 1 : positions.getStartPosition(unit, node);
          call(start, end(unit, node));
        }
        return super.visitMethodInvocation(node, unused);
      }

      @Override
      public Void visitNewClass(NewClassTree node, Void unused) {
        // An anonymous class is not an entity; calls in its body count for the enclosing code.
        // An enum constant's creation is not written as a call, and parsing does not see one.
        if (node.getClassBody() == null && !isEnumConstant(getCurrentPath().getParentPath())) {
          call(positions.getStartPosition(unit, node), end(unit, node));
        }
        return super.visitNewClass(node, unused);
      }

      @Override
      public Void visitMemberReference(MemberReferenceTree node, Void unused) {
        call(positions.getStartPosition(unit, node), end(unit, node));
        return super.visitMemberReference(node, unused);
      }

      private void call(long start, long end) {
        TreePath path = getCurrentPath();
        if (!(trees.getElement(path) instanceof ExecutableElement callee)
            || !(callee.getEnclosingElement() instanceof TypeElement declaring)
            || !isNamed(declaring)) {
          return;
        }
        EntityId caller = owner(path);
        EntityId target = methodId(callee);
        if (caller != null && target != null) {
          structure.calls.add(new Call(caller, target, lines(unit, file, start, end, null)));
        }
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
