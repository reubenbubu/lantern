package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.FactJson;
import io.lantern.core.vocabulary.AttributeSchema.Property;
import io.lantern.core.vocabulary.AttributeSchema.ValueType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

class AttributeRecordsTest {

  private static Vocabulary vocabulary() {
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    types.add(
        new VocabularyType(
                "http-call",
                TypeKind.ATTRIBUTE,
                null,
                null,
                null,
                null,
                "A call <to> a URL.",
                new AttributeSchema(
                    Map.of(
                        "path",
                        new Property(ValueType.STRING, "The path, @ the end.", List.of(), null),
                        "class",
                        Property.of(ValueType.STRING),
                        "port",
                        Property.of(ValueType.INTEGER),
                        "tags",
                        new Property(
                            ValueType.ARRAY, null, List.of(), Property.of(ValueType.STRING))),
                    Set.of(),
                    false))
            .withKeyAttributes(Set.of("path")));
    types.add(
        new VocabularyType(
            "CALLS_HTTP",
            TypeKind.RELATIONSHIP,
            BuiltInTypes.CALLS,
            null,
            null,
            null,
            null,
            new AttributeSchema(Map.of("url", Property.of(ValueType.STRING)), Set.of(), false)));
    return new Vocabulary(1, types);
  }

  @Test
  void namesFollowTheTypeAndAttributeNames() {
    assertThat(AttributeRecords.recordName("http-call")).isEqualTo("HttpCallAttributes");
    assertThat(AttributeRecords.recordName("CALLS_HTTP")).isEqualTo("CallsHttpAttributes");
    assertThat(AttributeRecords.recordName("redis-use")).isEqualTo("RedisUseAttributes");
    assertThat(AttributeRecords.componentName("httpMethod")).isEqualTo("httpMethod");
    assertThat(AttributeRecords.componentName("class")).isEqualTo("classValue");
    assertThat(AttributeRecords.componentName("line.count")).isEqualTo("lineCount");
  }

  @Test
  void generatesOneRecordPerPredicateTypeWithAttributes() {
    Map<String, String> sources =
        AttributeRecords.generate(
            vocabulary(),
            List.of("http-call", "CALLS_HTTP", BuiltInTypes.CALLS, BuiltInTypes.SERVICE),
            "io.acme.attributes");

    assertThat(sources).containsOnlyKeys("HttpCallAttributes.java", "CallsHttpAttributes.java");
    String call = sources.get("HttpCallAttributes.java");
    assertThat(call)
        .contains("package io.acme.attributes;")
        .contains("public record HttpCallAttributes(")
        .contains(
            "    String classValue,\n    String path,\n    Long port,\n    List<String> tags)")
        .contains("public static final String TYPE = \"http-call\";")
        .contains("KEY_ATTRIBUTES = List.of(\"path\");")
        .contains("AttributeValues.strings(attributes, \"tags\")")
        .contains("AttributeValues.put(attributes, \"class\", classValue);")
        .contains("@param path Key. The path, &#64; the end.")
        .contains("@param port Descriptive.")
        .contains("A call &lt;to&gt; a URL.");
  }

  @Test
  void checksAndWritesADirectoryOfRecords(@TempDir Path dir) throws IOException {
    Map<String, String> sources = Map.of("A.java", "a\n", "B.java", "b\n");
    Files.writeString(dir.resolve("A.java"), "a\n");
    Files.writeString(dir.resolve("Old.java"), "old\n");

    assertThat(AttributeRecords.check(dir, sources, false))
        .containsExactlyInAnyOrder("Old.java is not generated from any type", "B.java is missing");

    assertThat(AttributeRecords.check(dir, sources, true)).isEmpty();
    assertThat(AttributeRecords.check(dir, sources, false)).isEmpty();
    assertThat(Files.exists(dir.resolve("Old.java"))).isFalse();
  }

  @Test
  void valuesReadAndWriteByType() {
    ObjectNode attributes =
        (ObjectNode)
            FactJson.parse(
                "{\"s\": \"x\", \"i\": 3, \"b\": true, \"l\": [\"a\", \"b\"], \"o\": {\"k\": 1}}");

    assertThat(AttributeValues.string(attributes, "s")).isEqualTo("x");
    assertThat(AttributeValues.integer(attributes, "i")).isEqualTo(3L);
    assertThat(AttributeValues.bool(attributes, "b")).isTrue();
    assertThat(AttributeValues.strings(attributes, "l")).containsExactly("a", "b");
    assertThat(AttributeValues.object(attributes, "o").get("k").intValue()).isEqualTo(1);
    assertThat(AttributeValues.string(attributes, "absent")).isNull();

    ObjectNode written = FactJson.newObject();
    AttributeValues.put(written, "s", "x");
    AttributeValues.put(written, "i", 3L);
    AttributeValues.put(written, "b", true);
    AttributeValues.put(written, "l", List.of("a", "b"));
    AttributeValues.put(written, "o", AttributeValues.object(attributes, "o"));
    AttributeValues.put(written, "absent", null);
    assertThat(FactJson.normalize(written)).isEqualTo(FactJson.normalize(attributes));
  }
}
