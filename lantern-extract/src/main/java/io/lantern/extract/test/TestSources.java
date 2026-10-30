package io.lantern.extract.test;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.lantern.extract.deployment.Image;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /**
   * A container a test starts: a Testcontainers container, or an embedded Kafka broker.
   *
   * @param value the image as written, else the container's class (or {@code EmbeddedKafka})
   * @param className the container's class as written, or null for an embedded broker
   * @param image the image as written, or null when the code names none in a literal
   * @param technology the product and version, when known
   * @param role what the product is to the system ({@link Image#role()}), when known
   * @param serviceConnection whether Spring Boot's {@code @ServiceConnection} connects the app to
   *     it
   * @param topics the topics an embedded Kafka broker creates, as written
   * @param line the line of its declaration
   */
  record Container(
      String value,
      String className,
      String image,
      String technology,
      String role,
      boolean serviceConnection,
      List<String> topics,
      int line) {}

  /**
   * An architecture rule an ArchUnit library rule declares.
   *
   * @param kind {@code layered}, {@code onion} or {@code slices}
   * @param layers each layer's (or onion ring's or adapter's) name and package patterns, in order
   * @param access each layer's access rule: the method's name and the layers it names
   * @param slices the package pattern that makes the slices, for {@code slices}
   * @param checks what the rule checks of the slices, such as {@code beFreeOfCycles}
   * @param because the reason the rule gives, if any
   * @param frozen whether {@code FreezingArchRule.freeze} wraps it, so known violations are let be
   * @param ignored whether {@code @ArchIgnore} or {@code @Disabled} switches it off
   * @param unresolved whether an argument is not a string literal, so is left out
   * @param startLine the rule's first line
   * @param endLine its last line
   */
  record Rule(
      String kind,
      Map<String, List<String>> layers,
      Map<String, List<String>> access,
      String slices,
      List<String> checks,
      String because,
      boolean frozen,
      boolean ignored,
      boolean unresolved,
      int startLine,
      int endLine) {}

  /** Testcontainers' classes whose type names the product, for images built by a variable. */
  private static final Map<String, String[]> CONTAINER_CLASSES =
      Map.ofEntries(
          Map.entry("PostgreSQLContainer", new String[] {"PostgreSQL", "database"}),
          Map.entry("MySQLContainer", new String[] {"MySQL", "database"}),
          Map.entry("MariaDBContainer", new String[] {"MariaDB", "database"}),
          Map.entry("MSSQLServerContainer", new String[] {"SQL Server", "database"}),
          Map.entry("OracleContainer", new String[] {"Oracle Database", "database"}),
          Map.entry("MongoDBContainer", new String[] {"MongoDB", "database"}),
          Map.entry("CassandraContainer", new String[] {"Cassandra", "database"}),
          Map.entry("Neo4jContainer", new String[] {"Neo4j", "database"}),
          Map.entry("ElasticsearchContainer", new String[] {"Elasticsearch", "database"}),
          Map.entry("RedisContainer", new String[] {"Redis", "cache"}),
          Map.entry("KafkaContainer", new String[] {"Kafka", "broker"}),
          Map.entry("ConfluentKafkaContainer", new String[] {"Kafka", "broker"}),
          Map.entry("RedpandaContainer", new String[] {"Redpanda", "broker"}),
          Map.entry("RabbitMQContainer", new String[] {"RabbitMQ", "broker"}),
          Map.entry("PulsarContainer", new String[] {"Pulsar", "broker"}),
          Map.entry("ArtemisContainer", new String[] {"ActiveMQ Artemis", "broker"}),
          Map.entry("KeycloakContainer", new String[] {"Keycloak", "identity"}),
          Map.entry("LocalStackContainer", new String[] {"LocalStack", "cloud"}));

  /** Containers that are not one product: a compose file's services, a browser. */
  private static final Set<String> NOT_A_PRODUCT =
      Set.of("ComposeContainer", "DockerComposeContainer", "BrowserWebDriverContainer");

  private static final String SERVICE_CONNECTION =
      "org.springframework.boot.testcontainers.service.connection.ServiceConnection";
  private static final String EMBEDDED_KAFKA =
      "org.springframework.kafka.test.context.EmbeddedKafka";
  private static final String ARCH_IGNORE = "com.tngtech.archunit.junit.ArchIgnore";
  private static final String DISABLED = "org.junit.jupiter.api.Disabled";

  /** ArchUnit's layer access rules, as {@code whereLayer(name).<rule>(layers...)}. */
  private static final Set<String> ACCESS_RULES =
      Set.of(
          "mayNotBeAccessedByAnyLayer",
          "mayOnlyBeAccessedByLayers",
          "mayNotAccessAnyLayer",
          "mayOnlyAccessLayers",
          "mayOnlyAccessPackages",
          "mayNotBeAccessedByLayers");

  /** The onion architecture's rings, by ArchUnit's method that defines each. */
  private static final Set<String> ONION_RINGS =
      Set.of("domainModels", "domainServices", "applicationServices");

  private TestSources() {}

  /** The file's top-level classes that declare tests; empty if it does not parse. */
  static List<TestClass> read(String source) {
    Parsed parsed = parse(source);
    return parsed == null ? List.of() : parsed.classes(Set.of());
  }

  /** A parsed file, or null if it does not parse. */
  static Parsed parse(String source) {
    ParseResult<CompilationUnit> result =
        new JavaParser(new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL)).parse(source);
    if (!result.isSuccessful() || result.getResult().isEmpty()) {
      return null;
    }
    return new Parsed(result.getResult().get());
  }

  /**
   * The test annotations a repository composes of others: each annotation type declared in its
   * files that is annotated, directly or through another such annotation, with a test annotation.
   */
  static Set<String> composed(List<Parsed> files) {
    Map<String, Set<String>> declared = new LinkedHashMap<>();
    files.forEach(f -> declared.putAll(f.annotationDeclarations()));
    Set<String> composed = new HashSet<>();
    boolean grew = true;
    while (grew) {
      grew = false;
      for (Map.Entry<String, Set<String>> d : declared.entrySet()) {
        if (!composed.contains(d.getKey())
            && d.getValue().stream()
                .anyMatch(m -> TEST_ANNOTATIONS.contains(m) || composed.contains(m))) {
          composed.add(d.getKey());
          grew = true;
        }
      }
    }
    return composed;
  }

  /** A parsed test source file. */
  static final class Parsed {

    private final CompilationUnit unit;
    private final Imports imports;
    private final String pkg;

    private Parsed(CompilationUnit unit) {
      this.unit = unit;
      this.pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
      this.imports = new Imports(unit, pkg);
    }

    /** Each annotation type the file declares, with the annotations on it, by qualified name. */
    Map<String, Set<String>> annotationDeclarations() {
      Map<String, Set<String>> found = new LinkedHashMap<>();
      for (AnnotationDeclaration a : unit.findAll(AnnotationDeclaration.class)) {
        Optional<String> name = a.getFullyQualifiedName();
        if (name.isPresent()) {
          Set<String> meta = new LinkedHashSet<>();
          a.getAnnotations().forEach(m -> meta.addAll(imports.candidates(m.getNameAsString())));
          found.put(name.get(), meta);
        }
      }
      return found;
    }

    /**
     * The file's top-level classes that declare tests.
     *
     * @param composed the repository's own annotations that make a method a test
     */
    List<TestClass> classes(Set<String> composed) {
      Set<String> testAnnotations = new HashSet<>(TEST_ANNOTATIONS);
      testAnnotations.addAll(composed);
      List<TestClass> found = new ArrayList<>();
      for (TypeDeclaration<?> type : unit.getTypes()) {
        // An abstract class's tests run in its subclasses, which count instead.
        if (!(type instanceof ClassOrInterfaceDeclaration c) || c.isInterface() || c.isAbstract()) {
          continue;
        }
        int tests = tests(c, imports, testAnnotations);
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

    /** The containers the file's tests start: Testcontainers, and embedded Kafka brokers. */
    List<Container> containers() {
      List<Container> found = new ArrayList<>();
      if (imports.uses("org.testcontainers.") || imports.uses("com.redis.testcontainers.")) {
        for (ObjectCreationExpr created : unit.findAll(ObjectCreationExpr.class)) {
          String className = created.getType().getNameAsString();
          if (!className.endsWith("Container")
              || NOT_A_PRODUCT.contains(className)
              || created.getBegin().isEmpty()) {
            continue;
          }
          String image = created.getArguments().isEmpty() ? null : image(created.getArgument(0));
          Image known = image == null ? null : Image.of(image);
          String[] byClass = CONTAINER_CLASSES.get(className);
          String technology =
              known != null ? known.technology() : byClass == null ? null : byClass[0];
          String role = known != null ? known.role() : byClass == null ? null : byClass[1];
          boolean connection =
              declaration(created)
                  .map(d -> annotated(d, Set.of(SERVICE_CONNECTION), imports))
                  .orElse(false);
          found.add(
              new Container(
                  image != null ? image : className,
                  className,
                  image,
                  technology,
                  role,
                  connection,
                  List.of(),
                  created.getBegin().get().line));
        }
      }
      for (AnnotationExpr a : unit.findAll(AnnotationExpr.class)) {
        if (imports.names(a.getNameAsString(), EMBEDDED_KAFKA) && a.getBegin().isPresent()) {
          List<String> topics = new ArrayList<>();
          member(a, "topics").ifPresent(e -> topics.addAll(strings(e)));
          found.add(
              new Container(
                  "EmbeddedKafka",
                  null,
                  null,
                  "Kafka",
                  "broker",
                  false,
                  topics,
                  a.getBegin().get().line));
        }
      }
      return found;
    }

    /** The architecture rules ArchUnit's library rules in the file declare. */
    List<Rule> rules() {
      List<Rule> found = new ArrayList<>();
      if (!imports.uses("com.tngtech.archunit.")) {
        return found;
      }
      for (MethodCallExpr root : unit.findAll(MethodCallExpr.class)) {
        String kind =
            switch (root.getNameAsString()) {
              case "layeredArchitecture" -> "layered";
              case "onionArchitecture" -> "onion";
              case "slices" -> "slices";
              default -> null;
            };
        if (kind == null || !root.getArguments().isEmpty()) {
          continue;
        }
        // The chain of calls on the root, in order.
        List<MethodCallExpr> chain = new ArrayList<>();
        Node top = root;
        chain.add(root);
        while (top.getParentNode().orElse(null) instanceof MethodCallExpr call
            && call.getScope().filter(s -> s == chain.getLast()).isPresent()) {
          chain.add(call);
          top = call;
        }
        found.add(rule(kind, chain, top));
      }
      return found;
    }

    private Rule rule(String kind, List<MethodCallExpr> chain, Node top) {
      Map<String, List<String>> layers = new LinkedHashMap<>();
      Map<String, List<String>> access = new LinkedHashMap<>();
      String slices = null;
      List<String> checks = new ArrayList<>();
      String because = null;
      boolean unresolved = false;
      String layer = null;
      String where = null;
      for (MethodCallExpr call : chain) {
        List<String> args = new ArrayList<>();
        for (Expression arg : call.getArguments()) {
          String value = literal(arg);
          if (value == null) {
            unresolved = true;
          } else {
            args.add(value);
          }
        }
        String name = call.getNameAsString();
        switch (name) {
          case "layer", "optionalLayer" -> layer = args.isEmpty() ? null : args.getFirst();
          case "definedBy" -> {
            if (layer != null) {
              layers.put(layer, List.copyOf(args));
            }
          }
          case "whereLayer" -> where = args.isEmpty() ? null : args.getFirst();
          case "adapter" -> {
            if (!args.isEmpty()) {
              layers.put("adapter " + args.getFirst(), List.copyOf(args.subList(1, args.size())));
            }
          }
          case "matching" -> slices = args.isEmpty() ? null : args.getFirst();
          case "because" -> because = args.isEmpty() ? null : args.getFirst();
          case "beFreeOfCycles", "notDependOnEachOther" -> checks.add(name);
          default -> {
            if (ONION_RINGS.contains(name)) {
              layers.put(name, List.copyOf(args));
            } else if (ACCESS_RULES.contains(name) && where != null) {
              List<String> named = new ArrayList<>();
              named.add(name);
              named.addAll(args);
              access.put(where, named);
            }
          }
        }
      }
      boolean frozen =
          top.getParentNode().orElse(null) instanceof MethodCallExpr wrap
              && wrap.getNameAsString().equals("freeze");
      boolean ignored =
          declaration(top)
              .map(d -> annotated(d, Set.of(ARCH_IGNORE, DISABLED), imports))
              .orElse(false);
      return new Rule(
          kind,
          layers,
          access,
          slices,
          checks,
          because,
          frozen,
          ignored,
          unresolved,
          chain.getFirst().getBegin().map(p -> p.line).orElse(0),
          top.getEnd().map(p -> p.line).orElse(0));
    }

    /**
     * A string argument's value: a literal, or a constant of the file initialised with one; null
     * otherwise.
     */
    private String literal(Expression e) {
      if (e instanceof StringLiteralExpr s) {
        return s.getValue();
      }
      if (e instanceof NameExpr n) {
        for (VariableDeclarator v : unit.findAll(VariableDeclarator.class)) {
          if (v.getNameAsString().equals(n.getNameAsString())
              && v.getInitializer().orElse(null) instanceof StringLiteralExpr s) {
            return s.getValue();
          }
        }
      }
      return null;
    }

    /** An image argument: a literal, a constant, or {@code DockerImageName.parse(...)} of one. */
    private String image(Expression e) {
      String value = literal(e);
      if (value != null) {
        return value;
      }
      for (MethodCallExpr call : e.findAll(MethodCallExpr.class)) {
        if (call.getNameAsString().equals("parse") && call.getArguments().size() == 1) {
          return literal(call.getArgument(0));
        }
      }
      return null;
    }

    private List<String> strings(Expression e) {
      List<Expression> items =
          e instanceof ArrayInitializerExpr array ? array.getValues() : List.of(e);
      List<String> values = new ArrayList<>();
      for (Expression item : items) {
        String value = literal(item);
        if (value != null) {
          values.add(value);
        }
      }
      return values;
    }
  }

  /** The field or method a node is declared in, for its annotations. */
  private static Optional<NodeWithAnnotations<?>> declaration(Node node) {
    Node n = node;
    while (n != null) {
      if (n instanceof FieldDeclaration || n instanceof MethodDeclaration) {
        return Optional.of((NodeWithAnnotations<?>) n);
      }
      n = n.getParentNode().orElse(null);
    }
    return Optional.empty();
  }

  /** An annotation's member value: the single member's for {@code value}, else the named pair's. */
  private static Optional<Expression> member(AnnotationExpr a, String name) {
    if (a instanceof NormalAnnotationExpr normal) {
      return normal.getPairs().stream()
          .filter(p -> p.getNameAsString().equals(name))
          .map(MemberValuePair::getValue)
          .findFirst();
    }
    if (a instanceof SingleMemberAnnotationExpr single && name.equals("value")) {
      return Optional.of(single.getMemberValue());
    }
    return Optional.empty();
  }

  /** The tests a class and its member classes declare. */
  private static int tests(
      ClassOrInterfaceDeclaration c, Imports imports, Set<String> testAnnotations) {
    boolean testng = annotated(c, Set.of(TESTNG_TEST), imports);
    boolean junit3 =
        c.getExtendedTypes().stream()
            .anyMatch(t -> imports.names(t.getNameWithScope(), JUNIT3_TEST_CASE));
    int tests = 0;
    for (BodyDeclaration<?> member : c.getMembers()) {
      if (member instanceof MethodDeclaration m) {
        if (annotated(m, testAnnotations, imports)
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
        tests += tests(nested, imports, testAnnotations);
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
    private final String pkg;

    Imports(CompilationUnit unit, String pkg) {
      this.pkg = pkg;
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
      // A type of the file's own package needs no import.
      return single.contains(qualified)
          || onDemand.contains(qualified.substring(0, dot))
          || qualified.equals(pkg + written);
    }

    /** The qualified names a name as written may mean: as written, imported, or of the package. */
    Set<String> candidates(String written) {
      Set<String> names = new LinkedHashSet<>();
      names.add(written);
      if (!written.contains(".")) {
        single.stream().filter(s -> s.endsWith("." + written)).forEach(names::add);
        onDemand.forEach(p -> names.add(p + "." + written));
        names.add(pkg + written);
      }
      return names;
    }

    /** Whether the file imports anything of a package. */
    boolean uses(String prefix) {
      return all.stream().anyMatch(name -> (name + ".").startsWith(prefix));
    }

    /** Whether the file imports anything of an architecture test library. */
    boolean architecture() {
      return all.stream()
          .anyMatch(
              name -> ARCHITECTURE_PACKAGES.stream().anyMatch(p -> (name + ".").startsWith(p)));
    }
  }
}
