package io.lantern.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsTest {

  private static Settings flyway(Map<String, String> placeholders, String schema) {
    return new Settings(null, placeholders, "${", "}", schema, List.of(), Map.of(), List.of());
  }

  @Test
  void flywayPlaceholdersAndDefaultSchema() {
    Settings settings = flyway(Map.of("prefix", "app_", "Type", "text"), "sales");

    assertThat(
            settings.flyway("CREATE TABLE ${flyway:defaultSchema}.${prefix}t (a ${type}, b ${x})"))
        .isEqualTo("CREATE TABLE sales.app_t (a text, b ${x})");
  }

  @Test
  void withoutADefaultSchemaTheNameIsLeftUnqualified() {
    assertThat(flyway(Map.of(), null).flyway("CREATE TABLE ${flyway:defaultSchema}.t (id int)"))
        .isEqualTo("CREATE TABLE t (id int)");
  }

  @Test
  void replacementTurnedOffLeavesTheScript() {
    assertThat(flyway(null, "s").flyway("CREATE TABLE ${flyway:defaultSchema}.t (id int)"))
        .isEqualTo("CREATE TABLE ${flyway:defaultSchema}.t (id int)");
  }

  @Test
  void liquibasePropertiesKeepUnknownOnes() {
    assertThat(Settings.expand("${ a }_${b}", Map.of("a", "x"))).isEqualTo("x_${b}");
  }
}
