package io.lantern.extract.config;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ModuleConfig.Written;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The config defaults a library adds in code: the literal keys and values of an {@code
 * EnvironmentPostProcessor} registered in {@code META-INF/spring.factories} or in a {@code
 * META-INF/spring/*.imports} file (ADR 0032, amendment for #333). Keys or values that are computed
 * are not read.
 */
final class EnvironmentDefaults {

  /** The key under which {@code spring.factories} lists post-processors. */
  private static final String FACTORIES_KEY =
      "org.springframework.boot.env.EnvironmentPostProcessor";

  private static final String INTERFACE = "EnvironmentPostProcessor";

  private static final String JAVA = "src/main/java/";

  private static final long MAX_BYTES = 1024L * 1024;

  /**
   * What a property key looks like (dotted, so header and system property names are not); anything
   * else written in the same places is not config.
   */
  private static final Pattern KEY =
      Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]*(\\.[A-Za-z0-9_.\\-\\[\\]]+)+");

  private static final JavaParser PARSER =
      new JavaParser(
          new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

  private EnvironmentDefaults() {}

  /**
   * The defaults one module's post-processors add, as one document in a config, or null if none.
   *
   * @param directory the module's directory, {@code ""} for the top
   * @param paths all paths of the repository
   */
  static ModuleConfig.Document read(RepoBuild repo, String directory, List<String> paths) {
    String prefix = directory.isEmpty() ? "" : directory + "/";
    Set<String> classes = new LinkedHashSet<>();
    for (String path : paths) {
      if (!path.startsWith(prefix + "src/main/resources/META-INF/")) {
        continue;
      }
      String name = path.substring(path.lastIndexOf('/') + 1);
      if (name.equals("spring.factories")) {
        classes.addAll(factories(repo, path));
      } else if (name.endsWith(".imports") && path.contains("/META-INF/spring/")) {
        classes.addAll(imports(repo, path));
      }
    }
    Set<String> available = new LinkedHashSet<>(paths);
    Map<String, Written> values = new LinkedHashMap<>();
    for (String className : classes) {
      String file = prefix + JAVA + className.replace('.', '/') + ".java";
      if (!available.contains(file)) {
        continue;
      }
      byte[] content = GitFiles.read(repo, file, MAX_BYTES);
      if (content == null) {
        continue;
      }
      String source = new String(content, StandardCharsets.UTF_8);
      String hash = sha256(content);
      for (Map.Entry<String, Found> e : defaults(source, className).entrySet()) {
        values.putIfAbsent(
            e.getKey(),
            new Written(
                e.getValue().value(), new ConfigValue.Where(file, hash, e.getValue().line())));
      }
    }
    return values.isEmpty() ? null : new ModuleConfig.Document(null, List.of(), values);
  }

  private static List<String> factories(RepoBuild repo, String path) {
    byte[] content = GitFiles.read(repo, path, MAX_BYTES);
    if (content == null) {
      return List.of();
    }
    Properties properties = new Properties();
    try {
      properties.load(new StringReader(new String(content, StandardCharsets.ISO_8859_1)));
    } catch (IOException | IllegalArgumentException e) {
      return List.of();
    }
    List<String> classes = new ArrayList<>();
    String listed = properties.getProperty(FACTORIES_KEY);
    if (listed != null) {
      for (String c : listed.split(",")) {
        if (!c.isBlank()) {
          classes.add(c.trim());
        }
      }
    }
    return classes;
  }

  /** The classes an {@code .imports} file lists, those whose source implements the interface. */
  private static List<String> imports(RepoBuild repo, String path) {
    byte[] content = GitFiles.read(repo, path, MAX_BYTES);
    if (content == null) {
      return List.of();
    }
    List<String> classes = new ArrayList<>();
    for (String line : new String(content, StandardCharsets.UTF_8).split("\\R")) {
      String c = line.strip();
      if (!c.isEmpty() && !c.startsWith("#")) {
        classes.add(c);
      }
    }
    return classes;
  }

  private record Found(String value, int line) {}

  /** The literal pairs a post-processor's class sets, or none if it is not one. */
  private static Map<String, Found> defaults(String source, String className) {
    CompilationUnit unit = PARSER.parse(source).getResult().orElse(null);
    Map<String, Found> found = new LinkedHashMap<>();
    if (unit == null) {
      return found;
    }
    String simple = className.substring(className.lastIndexOf('.') + 1);
    for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
      if (!type.getNameAsString().equals(simple)
          || type.getImplementedTypes().stream()
              .noneMatch(t -> t.getNameAsString().equals(INTERFACE))) {
        continue;
      }
      Map<String, String> constants = new HashMap<>();
      for (FieldDeclaration field : type.getFields()) {
        if (!field.isStatic() || !field.isFinal()) {
          continue;
        }
        for (VariableDeclarator v : field.getVariables()) {
          String text = v.getInitializer().map(i -> literal(i, Map.of())).orElse(null);
          if (text != null) {
            constants.put(v.getNameAsString(), text);
          }
        }
      }
      for (MethodCallExpr call : type.findAll(MethodCallExpr.class)) {
        pairs(call, constants, found);
      }
    }
    return found;
  }

  /** Adds the key-value pairs a call writes with literals: {@code Map.of}, {@code put}, ... */
  private static void pairs(
      MethodCallExpr call, Map<String, String> constants, Map<String, Found> found) {
    List<Expression> args = call.getArguments();
    String name = call.getNameAsString();
    boolean pair =
        (name.equals("of") && isScope(call, "Map") && !args.isEmpty() && args.size() % 2 == 0)
            || (name.equals("entry") && isScope(call, "Map") && args.size() == 2)
            || (name.equals("singletonMap") && args.size() == 2)
            || ((name.equals("put") || name.equals("setProperty") || name.equals("putIfAbsent"))
                && args.size() == 2);
    if (!pair) {
      return;
    }
    for (int i = 0; i + 1 < args.size(); i += 2) {
      String key = literal(args.get(i), constants);
      String value = literal(args.get(i + 1), constants);
      if (key != null && value != null && KEY.matcher(key).matches()) {
        int line = args.get(i).getBegin().map(p -> p.line).orElse(0);
        found.putIfAbsent(key, new Found(value, line));
      }
    }
  }

  private static boolean isScope(MethodCallExpr call, String type) {
    return call.getScope().map(s -> s.toString().equals(type)).orElse(false);
  }

  /** A string made of literals and the class's own string constants, or null. */
  private static String literal(Expression e, Map<String, String> constants) {
    if (e instanceof StringLiteralExpr s) {
      return s.asString();
    }
    if (e instanceof TextBlockLiteralExpr t) {
      return t.asString();
    }
    if (e instanceof EnclosedExpr enclosed) {
      return literal(enclosed.getInner(), constants);
    }
    if (e instanceof NameExpr n) {
      return constants.get(n.getNameAsString());
    }
    if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
      String left = literal(b.getLeft(), constants);
      String right = left == null ? null : literal(b.getRight(), constants);
      return right == null ? null : left + right;
    }
    return null;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
