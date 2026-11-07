package io.lantern.extract.persistence;

import io.lantern.core.types.Types;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Parameter;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which datasource a class's JDBC or R2DBC access runs on, as Spring wires it (#962): the
 * DataSource, JdbcTemplate, NamedParameterJdbcTemplate, JdbcClient, DatabaseClient or
 * ConnectionFactory injected into it (through a constructor, an {@code @Autowired} method or field,
 * or a final field a generated constructor sets), the bean Spring injects there ({@code @Qualifier}
 * naming a bean or a bean's qualifier, else {@code @Primary}, else the one named as the point), the
 * datasource that bean is built on, through its {@code @Bean} method's parameters, and the config
 * that datasource is bound to ({@code @ConfigurationProperties} on its method or on the {@code
 * DataSourceProperties} it is built from, or the {@code @Value} placeholder of its URL). An
 * unqualified point with no such bean is Spring Boot's own datasource, {@code spring.datasource}
 * ({@code spring.r2dbc} for R2DBC), which Spring Boot builds from that config; the datasource beans
 * the code declares beside it are noted, since Spring Boot backs off its own when the code declares
 * one.
 */
final class DataSourceWiring {

  private static final String DATA_SOURCE = "javax.sql.DataSource";
  private static final String CONNECTION_FACTORY = "io.r2dbc.spi.ConnectionFactory";
  private static final String PROPERTIES =
      "org.springframework.boot.autoconfigure.jdbc.DataSourceProperties";
  private static final String R2DBC_PROPERTIES =
      "org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties";

  private static final String BEAN = "org.springframework.context.annotation.Bean";
  private static final String PRIMARY = "org.springframework.context.annotation.Primary";
  private static final String QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";
  private static final String VALUE = "org.springframework.beans.factory.annotation.Value";
  private static final String CONFIGURATION_PROPERTIES =
      "org.springframework.boot.context.properties.ConfigurationProperties";
  private static final Set<String> INJECT =
      Set.of(
          "org.springframework.beans.factory.annotation.Autowired",
          "jakarta.inject.Inject",
          "javax.inject.Inject",
          "jakarta.annotation.Resource",
          "javax.annotation.Resource");

  /** What a type of JDBC or R2DBC access is, as an injection point names it. */
  private enum Kind {
    DATA_SOURCE(false),
    PROPERTIES(false),
    TEMPLATE(false),
    CONNECTION_FACTORY(true),
    R2DBC_PROPERTIES(true),
    R2DBC_CLIENT(true);

    final boolean reactive;

    Kind(boolean reactive) {
      this.reactive = reactive;
    }
  }

  /** The types an injection point may name, and what each is. */
  private static final Map<String, Kind> KINDS =
      Map.ofEntries(
          Map.entry(DATA_SOURCE, Kind.DATA_SOURCE),
          Map.entry(PROPERTIES, Kind.PROPERTIES),
          Map.entry("org.springframework.jdbc.core.JdbcTemplate", Kind.TEMPLATE),
          Map.entry("org.springframework.jdbc.core.JdbcOperations", Kind.TEMPLATE),
          Map.entry(
              "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate", Kind.TEMPLATE),
          Map.entry(
              "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
              Kind.TEMPLATE),
          Map.entry("org.springframework.jdbc.core.simple.JdbcClient", Kind.TEMPLATE),
          Map.entry(CONNECTION_FACTORY, Kind.CONNECTION_FACTORY),
          Map.entry("io.r2dbc.pool.ConnectionPool", Kind.CONNECTION_FACTORY),
          Map.entry(R2DBC_PROPERTIES, Kind.R2DBC_PROPERTIES),
          Map.entry("org.springframework.r2dbc.core.DatabaseClient", Kind.R2DBC_CLIENT),
          Map.entry("org.springframework.data.r2dbc.core.R2dbcEntityTemplate", Kind.R2DBC_CLIENT),
          Map.entry(
              "org.springframework.data.r2dbc.core.R2dbcEntityOperations", Kind.R2DBC_CLIENT));

  /** Pool configuration nested under a datasource's prefix, which is not part of it. */
  private static final Pattern POOL_SUFFIX =
      Pattern.compile("\\.(hikari|tomcat|dbcp2|oracleucp|pool|configuration)$");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)");

  /** The URL keys a datasource bound by {@code @Value} reads. */
  private static final Pattern URL_KEY = Pattern.compile("(?i)(.+)\\.(url|jdbc-?url|r2dbc-?url)$");

  /**
   * A class's datasource: the config prefix it is bound to ({@code spring.datasource} for Spring
   * Boot's own), the beans Spring injects on the way, and the prefixes of the datasource beans the
   * code declares beside Spring Boot's own.
   */
  record Use(
      TypeDecl type,
      JavaAnalysis.Module module,
      String prefix,
      List<String> beans,
      Set<String> declared,
      Location where) {}

  private final JavaAnalysis code;

  DataSourceWiring(JavaAnalysis code) {
    this.code = code;
  }

  /** A {@code @Bean} method of the repository, with its module. */
  private record Bean(Member member, JavaAnalysis.Module module, String name, String qualifier) {}

  /** A point Spring injects a bean at: its type, the name written there and its qualifier. */
  private record Point(String type, String name, String qualifier) {}

  /** A datasource found: its prefix, the beans on the way, the declared ones beside it. */
  private record Found(String prefix, List<String> beans, Set<String> declared) {}

  /**
   * The datasources of the classes of a module that inject JDBC or R2DBC access; a class with a
   * point Lantern cannot follow has none, since which datasource it uses is then not certain.
   */
  List<Use> uses(JavaAnalysis.Module module) {
    List<Use> uses = new ArrayList<>();
    types:
    for (TypeDecl type : module.structure().types()) {
      if (!type.kind().equals(ModuleStructure.CLASS)) {
        continue;
      }
      Map<String, Found> found = new java.util.TreeMap<>();
      for (Point point : points(type)) {
        Found f = resolve(point, module, 0);
        if (f == null) {
          continue types;
        }
        found.putIfAbsent(f.prefix(), f);
      }
      for (Found f : found.values()) {
        uses.add(new Use(type, module, f.prefix(), f.beans(), f.declared(), type.where()));
      }
    }
    return uses;
  }

  /** Where Spring injects JDBC or R2DBC access into a class. */
  private List<Point> points(TypeDecl type) {
    List<Point> points = new ArrayList<>();
    boolean constructorTakes = false;
    for (Member member : type.members()) {
      if (!member.id().type().equals(Types.METHOD)) {
        continue;
      }
      boolean constructor = member.id().key().contains("#" + ModuleStructure.CONSTRUCTOR + "(");
      if (!constructor && code.annotationPath(member.annotations(), BEAN) != null) {
        continue;
      }
      if (constructor || injects(member.annotations())) {
        for (Parameter parameter : member.parameters()) {
          if (parameter.type() != null && KINDS.containsKey(parameter.type().type())) {
            points.add(
                new Point(
                    parameter.type().type(), parameter.name(), qualifier(parameter.annotations())));
            constructorTakes |= constructor;
          }
        }
      }
    }
    for (Member member : type.members()) {
      if (member.id().type().equals(Types.FIELD)
          && member.type() != null
          && KINDS.containsKey(member.type())
          && (injects(member.annotations()) || !constructorTakes && generatedConstructor(type))) {
        String key = member.id().key();
        points.add(
            new Point(
                member.type(),
                key.substring(key.indexOf('#') + 1),
                qualifier(member.annotations())));
      }
    }
    return points;
  }

  /** Whether Lombok writes the class's constructor, which takes its final fields. */
  private static boolean generatedConstructor(TypeDecl type) {
    return type.annotations().stream()
        .anyMatch(
            a ->
                a.type().equals("lombok.RequiredArgsConstructor")
                    || a.type().equals("lombok.AllArgsConstructor"));
  }

  private boolean injects(List<Annotation> annotations) {
    return INJECT.stream().anyMatch(t -> code.annotationPath(annotations, t) != null);
  }

  /** The datasource Spring injects at a point, or null when that is not certain. */
  private Found resolve(Point point, JavaAnalysis.Module at, int depth) {
    Kind kind = KINDS.get(point.type());
    if (kind == null || depth > 4) {
      return null;
    }
    List<Bean> candidates = candidates(point.type(), at);
    Bean bean;
    if (point.qualifier() != null) {
      bean =
          one(
              candidates.stream()
                  .filter(
                      b ->
                          point.qualifier().equals(b.name())
                              || point.qualifier().equals(b.qualifier()))
                  .toList());
      if (bean == null) {
        return null;
      }
    } else {
      bean = one(candidates.stream().filter(b -> primary(b.member())).toList());
      if (bean == null) {
        bean = one(candidates.stream().filter(b -> b.name().equals(point.name())).toList());
      }
      if (bean == null && candidates.size() == 1 && kind != Kind.DATA_SOURCE) {
        bean = candidates.getFirst();
      }
    }
    if (bean == null) {
      // Spring Boot's own: built from its config, on its own datasource for a template or client.
      Set<String> declared = new TreeSet<>();
      if (kind == Kind.DATA_SOURCE || kind == Kind.CONNECTION_FACTORY) {
        for (Bean other : candidates) {
          Found f = dataSource(other, depth + 1);
          if (f != null) {
            declared.add(f.prefix());
          }
        }
      } else {
        Found own =
            resolve(
                new Point(kind.reactive ? CONNECTION_FACTORY : DATA_SOURCE, null, null),
                at,
                depth + 1);
        if (own == null) {
          return null;
        }
        declared.addAll(own.declared());
        if (!own.beans().isEmpty()) {
          return own;
        }
      }
      return new Found(kind.reactive ? "spring.r2dbc" : "spring.datasource", List.of(), declared);
    }
    return switch (kind) {
      case DATA_SOURCE, CONNECTION_FACTORY, PROPERTIES, R2DBC_PROPERTIES -> dataSource(bean, depth);
      default -> builtOn(bean, kind, depth);
    };
  }

  /** The datasource a template or client bean is built on: what its method takes. */
  private Found builtOn(Bean bean, Kind kind, int depth) {
    for (Parameter parameter : bean.member().parameters()) {
      if (parameter.type() == null) {
        continue;
      }
      Kind taken = KINDS.get(parameter.type().type());
      if (taken != null && taken.reactive == kind.reactive && taken != Kind.PROPERTIES) {
        Found f =
            resolve(
                new Point(
                    parameter.type().type(), parameter.name(), qualifier(parameter.annotations())),
                bean.module(),
                depth + 1);
        return f == null ? null : withBean(bean, f);
      }
    }
    return null;
  }

  /** The config a datasource bean (or its properties bean) is bound to. */
  private Found dataSource(Bean bean, int depth) {
    String prefix = configurationProperties(bean.member().annotations());
    if (prefix == null) {
      for (Parameter parameter : bean.member().parameters()) {
        String key = value(parameter.annotations());
        Matcher url = key == null ? null : URL_KEY.matcher(key);
        if (url != null && url.matches()) {
          prefix = url.group(1);
          break;
        }
      }
    }
    if (prefix == null) {
      // Built from a DataSourceProperties (or R2dbcProperties) bean it takes.
      for (Parameter parameter : bean.member().parameters()) {
        if (parameter.type() != null
            && (parameter.type().type().equals(PROPERTIES)
                || parameter.type().type().equals(R2DBC_PROPERTIES))) {
          Found f =
              resolve(
                  new Point(
                      parameter.type().type(),
                      parameter.name(),
                      qualifier(parameter.annotations())),
                  bean.module(),
                  depth + 1);
          return f == null ? null : withBean(bean, f);
        }
      }
      return null;
    }
    return new Found(POOL_SUFFIX.matcher(prefix).replaceAll(""), List.of(bean.name()), Set.of());
  }

  private static Found withBean(Bean bean, Found f) {
    List<String> beans = new ArrayList<>(List.of(bean.name()));
    beans.addAll(f.beans());
    return new Found(f.prefix(), beans, f.declared());
  }

  /** The {@code @Bean} methods of the repository making a bean a point of the type takes. */
  private List<Bean> candidates(String type, JavaAnalysis.Module at) {
    List<Bean> own = new ArrayList<>();
    List<Bean> others = new ArrayList<>();
    for (JavaAnalysis.Module m : code.modules()) {
      if (!m.repo().equals(at.repo())) {
        continue;
      }
      for (TypeDecl t : m.structure().types()) {
        for (Member member : t.members()) {
          List<Annotation> path = code.annotationPath(member.annotations(), BEAN);
          if (path == null || member.returns() == null || !assignable(member.returns(), type)) {
            continue;
          }
          String name = first(path.getLast(), "name", "value");
          if (name == null) {
            String key = member.id().key();
            name = key.substring(key.indexOf('#') + 1, key.indexOf('('));
          }
          (m == at ? own : others).add(new Bean(member, m, name, qualifier(member.annotations())));
        }
      }
    }
    return own.isEmpty() ? others : own;
  }

  /** Whether a bean of the returned type fills a point of the type. */
  private static boolean assignable(String returned, String type) {
    if (returned.equals(type)) {
      return true;
    }
    String simple = returned.substring(returned.lastIndexOf('.') + 1);
    return switch (type) {
      case DATA_SOURCE -> simple.endsWith("DataSource");
      case CONNECTION_FACTORY ->
          simple.endsWith("ConnectionFactory") || simple.equals("ConnectionPool");
      case "org.springframework.jdbc.core.JdbcOperations" ->
          returned.equals("org.springframework.jdbc.core.JdbcTemplate");
      case "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations" ->
          returned.equals("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate");
      case "org.springframework.data.r2dbc.core.R2dbcEntityOperations" ->
          returned.equals("org.springframework.data.r2dbc.core.R2dbcEntityTemplate");
      default -> false;
    };
  }

  private boolean primary(Member member) {
    return code.annotationPath(member.annotations(), PRIMARY) != null;
  }

  private String qualifier(List<Annotation> annotations) {
    List<Annotation> path = code.annotationPath(annotations, QUALIFIER);
    if (path == null) {
      // @Resource(name = ...) names the bean too.
      for (Annotation a : annotations) {
        if (a.type().endsWith(".annotation.Resource")) {
          return first(a, "name");
        }
      }
      return null;
    }
    String written = first(path.getLast(), "value");
    if (written == null && path.size() > 1) {
      // A custom qualifier annotation stands for itself.
      return path.getFirst().type();
    }
    return written == null || written.isBlank() ? null : written;
  }

  private String configurationProperties(List<Annotation> annotations) {
    List<Annotation> path = code.annotationPath(annotations, CONFIGURATION_PROPERTIES);
    String prefix = path == null ? null : first(path.getLast(), "prefix", "value");
    return prefix == null || prefix.isBlank() ? null : prefix;
  }

  /** The key of the placeholder an {@code @Value} writes, or null. */
  private String value(List<Annotation> annotations) {
    List<Annotation> path = code.annotationPath(annotations, VALUE);
    String written = path == null ? null : first(path.getLast(), "value");
    if (written == null) {
      return null;
    }
    Matcher m = PLACEHOLDER.matcher(written);
    return m.find() ? m.group(1).strip() : null;
  }

  private static String first(Annotation a, String... elements) {
    for (String element : elements) {
      List<String> values = a.values(element);
      if (!values.isEmpty() && !values.getFirst().isBlank()) {
        return values.getFirst();
      }
    }
    return null;
  }

  private static <T> T one(List<T> list) {
    return list.size() == 1 ? list.getFirst() : null;
  }
}
