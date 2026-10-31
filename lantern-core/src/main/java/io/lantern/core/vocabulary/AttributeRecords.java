package io.lantern.core.vocabulary;

import io.lantern.core.vocabulary.AttributeSchema.Property;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Generates a Java record for each relationship and attribute type's attribute schema (ADR 0090),
 * so code reads {@code call.path()} instead of {@code attributes.path("path")}. A record holds
 * every attribute the type declares or inherits, sorted by name, with {@code null} for an absent
 * one; it reads a fact's attributes with {@code from} and writes them with {@code toJson}.
 * Attributes a type allows without declaring them are not part of its record.
 *
 * <p>The records are committed with the module that defines the types, in its {@code attributes}
 * package, and a test of that module checks they are what this class generates from the types as
 * they are; the test rewrites them when run with {@code -Dlantern.records.write=true}. The package
 * is left out of the formatting check, as generated code.
 */
public final class AttributeRecords {

  /** The system property with which a module's test asks {@link #check} to write the records. */
  public static final String WRITE_PROPERTY = "lantern.records.write";

  private static final Set<String> KEYWORDS =
      Set.of(
          "abstract",
          "assert",
          "boolean",
          "break",
          "byte",
          "case",
          "catch",
          "char",
          "class",
          "const",
          "continue",
          "default",
          "do",
          "double",
          "else",
          "enum",
          "extends",
          "final",
          "finally",
          "float",
          "for",
          "goto",
          "if",
          "implements",
          "import",
          "instanceof",
          "int",
          "interface",
          "long",
          "native",
          "new",
          "package",
          "private",
          "protected",
          "public",
          "return",
          "short",
          "static",
          "strictfp",
          "super",
          "switch",
          "synchronized",
          "this",
          "throw",
          "throws",
          "transient",
          "try",
          "void",
          "volatile",
          "while",
          "true",
          "false",
          "null",
          "record",
          "var",
          "yield",
          "sealed",
          "permits",
          "when");

  private AttributeRecords() {}

  /**
   * The source of a record for each of the named types that is a relationship or attribute type and
   * declares or inherits at least one attribute.
   *
   * @param vocabulary a vocabulary holding the types and all their ancestors
   * @param types the names of the types to generate records for
   * @param packageName the package of the records
   * @return the sources by file name, such as {@code HttpCallAttributes.java}, sorted
   */
  public static Map<String, String> generate(
      Vocabulary vocabulary, Collection<String> types, String packageName) {
    Map<String, String> sources = new TreeMap<>();
    for (String name : new TreeSet<>(types)) {
      VocabularyType type = vocabulary.find(name).orElseThrow();
      AttributeSchema schema = vocabulary.attributeSchema(name);
      if (!type.kind().isPredicate() || schema.properties().isEmpty()) {
        continue;
      }
      String record = recordName(name);
      if (sources.put(record + ".java", source(vocabulary, type, record, packageName)) != null) {
        throw new IllegalArgumentException("Two types give the record name " + record);
      }
    }
    return sources;
  }

  /**
   * Checks that a directory holds exactly the records {@link #generate} gives, or writes them there
   * and deletes the {@code .java} files no type gives any more.
   *
   * @param write whether to write instead of check; modules' tests pass whether the system property
   *     {@value #WRITE_PROPERTY} is {@code true}
   * @return the problems found, empty when the directory is up to date or was written
   */
  public static List<String> check(Path directory, Map<String, String> sources, boolean write) {
    List<String> problems = new ArrayList<>();
    try {
      Set<String> existing = new TreeSet<>();
      if (Files.isDirectory(directory)) {
        try (var files = Files.list(directory)) {
          files
              .map(p -> p.getFileName().toString())
              .filter(n -> n.endsWith(".java"))
              .forEach(existing::add);
        }
      }
      if (write) {
        Files.createDirectories(directory);
        for (String stale : existing) {
          if (!sources.containsKey(stale)) {
            Files.delete(directory.resolve(stale));
          }
        }
        for (Map.Entry<String, String> source : sources.entrySet()) {
          Files.writeString(directory.resolve(source.getKey()), source.getValue());
        }
        return problems;
      }
      for (String stale : existing) {
        if (!sources.containsKey(stale)) {
          problems.add(stale + " is not generated from any type");
        }
      }
      for (Map.Entry<String, String> source : sources.entrySet()) {
        Path file = directory.resolve(source.getKey());
        if (!Files.exists(file)) {
          problems.add(source.getKey() + " is missing");
        } else if (!Files.readString(file, StandardCharsets.UTF_8).equals(source.getValue())) {
          problems.add(source.getKey() + " differs from what its type generates");
        }
      }
      return problems;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The record's name: the type's name in upper camel case, then {@code Attributes}. */
  static String recordName(String type) {
    StringBuilder out = new StringBuilder();
    boolean upper = true;
    boolean allCaps = type.equals(type.toUpperCase(Locale.ROOT));
    for (char c : type.toCharArray()) {
      if (c == '-' || c == '_' || c == '.') {
        upper = true;
      } else if (upper) {
        out.append(Character.toUpperCase(c));
        upper = false;
      } else {
        out.append(allCaps ? Character.toLowerCase(c) : c);
      }
    }
    return out + "Attributes";
  }

  /** The record component of an attribute: its name, or with {@code Value} after a keyword. */
  static String componentName(String attribute) {
    StringBuilder out = new StringBuilder();
    boolean upper = false;
    for (char c : attribute.toCharArray()) {
      if (c == '-' || c == '_' || c == '.') {
        upper = true;
      } else {
        out.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    String name = out.toString();
    return KEYWORDS.contains(name) ? name + "Value" : name;
  }

  private static String source(
      Vocabulary vocabulary, VocabularyType type, String record, String packageName) {
    AttributeSchema schema = vocabulary.attributeSchema(type.name());
    Set<String> keys = vocabulary.keyAttributes(type.name());
    StringBuilder out = new StringBuilder();
    out.append(
            "// Generated by io.lantern.core.vocabulary.AttributeRecords from the vocabulary type '")
        .append(type.name())
        .append("'. Do not edit.\n");
    out.append("package ").append(packageName).append(";\n\n");
    out.append("import io.lantern.core.fact.FactJson;\n");
    out.append("import io.lantern.core.vocabulary.AttributeValues;\n");
    out.append("import java.util.List;\n");
    out.append("import tools.jackson.databind.node.ObjectNode;\n\n");
    out.append("/**\n");
    wrap(
        out,
        " * ",
        " * ",
        "The attributes of a fact of the type {@code "
            + type.name()
            + "}"
            + (type.description() == null ? "." : ". " + escape(type.description())));
    out.append(" *\n");
    for (Map.Entry<String, Property> attribute : schema.properties().entrySet()) {
      Property property = attribute.getValue();
      wrap(
          out,
          " * @param ",
          " *     ",
          componentName(attribute.getKey())
              + (keys.contains(attribute.getKey()) ? " Key. " : " Descriptive. ")
              + (property.description() == null ? "" : escape(property.description())));
    }
    out.append(" */\n");
    out.append("public record ").append(record).append("(\n");
    List<String> components = new ArrayList<>();
    for (Map.Entry<String, Property> attribute : schema.properties().entrySet()) {
      components.add(
          "    " + javaType(attribute.getValue()) + " " + componentName(attribute.getKey()));
    }
    out.append(String.join(",\n", components)).append(") {\n\n");
    out.append("  /** The vocabulary type these are the attributes of. */\n");
    out.append("  public static final String TYPE = \"").append(type.name()).append("\";\n\n");
    out.append("  /** The key attributes: those that are part of what the fact claims. */\n");
    out.append("  public static final List<String> KEY_ATTRIBUTES = List.of(");
    out.append(String.join(", ", keys.stream().map(k -> "\"" + k + "\"").toList()));
    out.append(");\n\n");
    out.append("  /** Reads the declared attributes of a fact; undeclared ones are ignored. */\n");
    out.append("  public static ").append(record).append(" from(ObjectNode attributes) {\n");
    out.append("    return new ").append(record).append("(\n");
    List<String> reads = new ArrayList<>();
    for (Map.Entry<String, Property> attribute : schema.properties().entrySet()) {
      reads.add(
          "        AttributeValues."
              + reader(attribute.getValue())
              + "(attributes, \""
              + attribute.getKey()
              + "\")");
    }
    out.append(String.join(",\n", reads)).append(");\n  }\n\n");
    out.append("  /** The attributes as a fact holds them; absent ones are left out. */\n");
    out.append("  public ObjectNode toJson() {\n");
    out.append("    ObjectNode attributes = FactJson.newObject();\n");
    for (String attribute : schema.properties().keySet()) {
      out.append("    AttributeValues.put(attributes, \"")
          .append(attribute)
          .append("\", ")
          .append(componentName(attribute))
          .append(");\n");
    }
    out.append("    return attributes;\n  }\n}\n");
    return out.toString();
  }

  private static String javaType(Property property) {
    return switch (property.type()) {
      case STRING -> "String";
      case INTEGER -> "Long";
      case NUMBER -> "java.math.BigDecimal";
      case BOOLEAN -> "Boolean";
      case OBJECT -> "ObjectNode";
      case ARRAY ->
          "List<"
              + (property.items() == null
                  ? "tools.jackson.databind.JsonNode"
                  : property.items().type() == AttributeSchema.ValueType.ARRAY
                      ? "List<tools.jackson.databind.JsonNode>"
                      : javaType(property.items()))
              + ">";
    };
  }

  private static String reader(Property property) {
    return switch (property.type()) {
      case STRING -> "string";
      case INTEGER -> "integer";
      case NUMBER -> "number";
      case BOOLEAN -> "bool";
      case OBJECT -> "object";
      case ARRAY ->
          property.items() == null
              ? "array"
              : switch (property.items().type()) {
                case STRING -> "strings";
                case INTEGER -> "integers";
                case NUMBER -> "numbers";
                case BOOLEAN -> "bools";
                case OBJECT -> "objects";
                case ARRAY -> "arrays";
              };
    };
  }

  /** Appends text as comment lines of at most 100 characters, breaking between words. */
  private static void wrap(StringBuilder out, String first, String rest, String text) {
    StringBuilder line = new StringBuilder(first);
    boolean empty = true;
    for (String word : text.strip().split("\\s+")) {
      if (!empty && line.length() + 1 + word.length() > 100) {
        out.append(line.toString().stripTrailing()).append('\n');
        line = new StringBuilder(rest);
        empty = true;
      }
      if (!empty) {
        line.append(' ');
      }
      line.append(word);
      empty = false;
    }
    out.append(line.toString().stripTrailing()).append('\n');
  }

  /** Text safe inside a Javadoc comment. */
  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("@", "&#64;")
        .replace("*/", "*&#47;")
        .replace("\\u", "\\\\u");
  }
}
