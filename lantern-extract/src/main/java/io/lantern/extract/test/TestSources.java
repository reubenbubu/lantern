package io.lantern.extract.test;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a test source file, without compiling it or resolving its types: which of its top-level
 * classes declare tests, how many, and whether they test the code's architecture. An annotation
 * counts when its name, as written, is a known annotation's qualified name, or its simple name with
 * a single-type or on-demand import of it.
 */
final class TestSources {

  /** The newest language level the parser supports without preview features. */
  private static final ParserConfiguration.LanguageLevel LANGUAGE_LEVEL =
      ParserConfiguration.LanguageLevel.JAVA_25;

  /** Annotations that make a method a test: JUnit 4 and 5, TestNG and ArchUnit. */
  static final Set<String> TEST_ANNOTATIONS =
      Set.of(
          "org.junit.jupiter.api.Test",
          "org.junit.jupiter.api.RepeatedTest",
          "org.junit.jupiter.api.TestFactory",
          "org.junit.jupiter.api.TestTemplate",
          "org.junit.jupiter.params.ParameterizedTest",
          "org.junit.Test",
          "org.testng.annotations.Test",
          "com.tngtech.archunit.junit.ArchTest");

  /** Annotations that make a field a test: an ArchUnit rule. */
  private static final Set<String> TEST_FIELD_ANNOTATIONS =
      Set.of("com.tngtech.archunit.junit.ArchTest");

  /** TestNG's annotation, which on a class makes its public methods tests. */
  private static final String TESTNG_TEST = "org.testng.annotations.Test";

  /** JUnit 3's base class, whose subclasses' public {@code test...} methods are tests. */
  private static final String JUNIT3_TEST_CASE = "junit.framework.TestCase";

  /**
   * The packages whose use makes a test an architecture test: ArchUnit, Taikai, jMolecules'
   * ArchUnit rules and Spring Modulith's module verification.
   */
  static final List<String> ARCHITECTURE_PACKAGES =
      List.of(
          "com.tngtech.archunit.",
          "com.enofex.taikai.",
          "org.jmolecules.archunit.",
          "org.springframework.modulith.core.");

  /**
   * A top-level class that declares tests.
   *
   * @param binaryName the class's binary name
   * @param tests how many tests it and its member classes declare
   * @param architecture whether the file imports an architecture test library
   * @param startLine the first line of the class's declaration
   * @param endLine its last line
   */
  record TestClass(
      String binaryName, int tests, boolean architecture, int startLine, int endLine) {}

  private TestSources() {}

  /** The file's top-level classes that declare tests; empty if it does not parse. */
  static List<TestClass> read(String source) {
    ParseResult<CompilationUnit> result =
        new JavaParser(new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL)).parse(source);
    if (!result.isSuccessful() || result.getResult().isEmpty()) {
      return List.of();
    }
    CompilationUnit unit = result.getResult().get();
    Imports imports = new Imports(unit);
    String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
    List<TestClass> found = new ArrayList<>();
    for (TypeDeclaration<?> type : unit.getTypes()) {
      if (!(type instanceof ClassOrInterfaceDeclaration c) || c.isInterface()) {
        continue;
      }
      int tests = tests(c, imports);
      if (tests > 0 && c.getRange().isPresent()) {
        found.add(
            new TestClass(
                pkg + c.getNameAsString(),
                tests,
                imports.architecture(),
                c.getRange().get().begin.line,
                c.getRange().get().end.line));
      }
    }
    return found;
  }

  /** The tests a class and its member classes declare. */
  private static int tests(ClassOrInterfaceDeclaration c, Imports imports) {
    boolean testng = annotated(c, Set.of(TESTNG_TEST), imports);
    boolean junit3 =
        c.getExtendedTypes().stream()
            .anyMatch(t -> imports.names(t.getNameWithScope(), JUNIT3_TEST_CASE));
    int tests = 0;
    for (BodyDeclaration<?> member : c.getMembers()) {
      if (member instanceof MethodDeclaration m) {
        if (annotated(m, TEST_ANNOTATIONS, imports)
            || (testng && isPublicInstanceVoid(m) && m.getAnnotations().isEmpty())
            || (junit3
                && isPublicInstanceVoid(m)
                && m.getParameters().isEmpty()
                && m.getNameAsString().startsWith("test"))) {
          tests++;
        }
      } else if (member instanceof FieldDeclaration f) {
        if (annotated(f, TEST_FIELD_ANNOTATIONS, imports)) {
          tests += f.getVariables().size();
        }
      } else if (member instanceof ClassOrInterfaceDeclaration nested && !nested.isInterface()) {
        tests += tests(nested, imports);
      }
    }
    return tests;
  }

  private static boolean isPublicInstanceVoid(MethodDeclaration m) {
    return m.hasModifier(Modifier.Keyword.PUBLIC)
        && !m.isStatic()
        && !m.isAbstract()
        && m.getType().isVoidType();
  }

  private static boolean annotated(
      NodeWithAnnotations<?> node, Set<String> annotations, Imports imports) {
    for (AnnotationExpr annotation : node.getAnnotations()) {
      String name = annotation.getNameAsString();
      for (String known : annotations) {
        if (imports.names(name, known)) {
          return true;
        }
      }
    }
    return false;
  }

  /** A file's imports, for telling which type a simple name means. */
  private static final class Imports {

    private final Set<String> single = new HashSet<>();
    private final Set<String> onDemand = new HashSet<>();
    private final List<String> all = new ArrayList<>();

    Imports(CompilationUnit unit) {
      for (ImportDeclaration i : unit.getImports()) {
        String name = i.getNameAsString();
        all.add(name);
        if (i.isStatic()) {
          continue;
        }
        if (i.isAsterisk()) {
          onDemand.add(name);
        } else {
          single.add(name);
        }
      }
    }

    /** Whether a name as written means the type with the given qualified name. */
    boolean names(String written, String qualified) {
      if (written.equals(qualified)) {
        return true;
      }
      int dot = qualified.lastIndexOf('.');
      if (!written.equals(qualified.substring(dot + 1))) {
        return false;
      }
      return single.contains(qualified) || onDemand.contains(qualified.substring(0, dot));
    }

    /** Whether the file imports anything of an architecture test library. */
    boolean architecture() {
      return all.stream()
          .anyMatch(
              name -> ARCHITECTURE_PACKAGES.stream().anyMatch(p -> (name + ".").startsWith(p)));
    }
  }
}
