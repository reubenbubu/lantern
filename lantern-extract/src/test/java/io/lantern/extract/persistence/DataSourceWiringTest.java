package io.lantern.extract.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.core.types.Types;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaExtractor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which datasource a class's JDBC access runs on, as Spring wires it (#962), on the shapes Spring's
 * and Spring Boot's documentation give for several datasources: {@code @Qualifier} on constructor
 * parameters and fields, templates and clients built on a named DataSource bean, {@code @Primary},
 * DataSourceProperties bound by {@code @ConfigurationProperties}, a URL read by {@code @Value}, and
 * Spring Boot's own datasource.
 */
class DataSourceWiringTest {

  @TempDir Path temp;

  private static final String POM =
      "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
          + "<artifactId>%s</artifactId><version>1</version></project>\n";

  private static final String IMPORTS =
      "package com.example;\n\n"
          + "import javax.sql.DataSource;\n"
          + "import org.springframework.beans.factory.annotation.Autowired;\n"
          + "import org.springframework.beans.factory.annotation.Qualifier;\n"
          + "import org.springframework.beans.factory.annotation.Value;\n"
          + "import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;\n"
          + "import org.springframework.boot.context.properties.ConfigurationProperties;\n"
          + "import org.springframework.context.annotation.Bean;\n"
          + "import org.springframework.context.annotation.Configuration;\n"
          + "import org.springframework.context.annotation.Primary;\n"
          + "import org.springframework.jdbc.core.JdbcOperations;\n"
          + "import org.springframework.jdbc.core.JdbcTemplate;\n"
          + "import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;\n"
          + "import org.springframework.jdbc.core.simple.JdbcClient;\n"
          + "import org.springframework.stereotype.Repository;\n\n";

  /** The datasource each class's access runs on, by class, with the beans on the way. */
  private Map<String, String> uses(String name, Map<String, String> sources) throws IOException {
    Map<String, String> files = new TreeMap<>();
    files.put("pom.xml", POM.formatted(name));
    sources.forEach(
        (type, body) -> files.put("src/main/java/com/example/" + type + ".java", IMPORTS + body));
    Repo repo = Fixtures.repo(temp.resolve(name), name, files);
    List<Fact> facts =
        new PersistenceExtractor()
            .extract(
                JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null))), 1);
    Map<String, String> uses = new TreeMap<>();
    for (Fact f : facts) {
      if (f.predicate().equals(Types.DATASOURCE_USE)) {
        String beans = f.attributes().has("beans") ? " " + f.attributes().get("beans") : "";
        String declared =
            f.attributes().has("declared") ? " beside " + f.attributes().get("declared") : "";
        uses.merge(
            f.subject().key().substring("com.example.".length()),
            f.object().value() + beans + declared,
            (a, b) -> a + "; " + b);
      }
    }
    return uses;
  }

  @Test
  void eachClassRunsOnTheDatasourceItsInjectedBeanIsBuiltOn() throws IOException {
    Map<String, String> uses =
        uses(
            "orders",
            Map.of(
                "DataConfig",
                "@Configuration\npublic class DataConfig {\n"
                    + "  @Bean @Primary @ConfigurationProperties(\"app.datasource.orders\")\n"
                    + "  DataSource ordersDataSource() { return null; }\n"
                    + "  @Bean @ConfigurationProperties(\"app.datasource.audit.hikari\")\n"
                    + "  DataSource auditDataSource() { return null; }\n"
                    + "  @Bean JdbcTemplate auditJdbcTemplate(@Qualifier(\"auditDataSource\")"
                    + " DataSource ds) { return new JdbcTemplate(ds); }\n"
                    + "  @Bean NamedParameterJdbcTemplate ordersTemplate(DataSource ds) {"
                    + " return new NamedParameterJdbcTemplate(ds); }\n"
                    + "  @Bean @ConfigurationProperties(\"app.datasource.archive\")\n"
                    + "  DataSourceProperties archiveProperties() { return new"
                    + " DataSourceProperties(); }\n"
                    + "  @Bean DataSource archiveDataSource(@Qualifier(\"archiveProperties\")"
                    + " DataSourceProperties p) { return p.initializeDataSourceBuilder().build();"
                    + " }\n"
                    + "  @Bean DataSource legacy(@Value(\"${legacy.db.url}\") String url) {"
                    + " return null; }\n"
                    + "}\n",
                "OrdersDao",
                "@Repository\npublic class OrdersDao {\n"
                    + "  private final NamedParameterJdbcTemplate jdbc;\n"
                    + "  public OrdersDao(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }\n"
                    + "}\n",
                "AuditDao",
                "@Repository\npublic class AuditDao {\n"
                    + "  @Autowired @Qualifier(\"auditJdbcTemplate\") private JdbcOperations jdbc;\n"
                    + "}\n",
                "ReportDao",
                "@Repository\npublic class ReportDao {\n"
                    + "  private final JdbcClient client;\n"
                    + "  public ReportDao(JdbcClient client) { this.client = client; }\n"
                    + "}\n",
                "ArchiveDao",
                "@Repository\npublic class ArchiveDao {\n"
                    + "  public ArchiveDao(@Qualifier(\"archiveDataSource\") DataSource ds) {}\n"
                    + "}\n",
                "LegacyDao",
                "@Repository\npublic class LegacyDao {\n"
                    + "  public LegacyDao(@Qualifier(\"legacy\") DataSource ds) {}\n"
                    + "}\n",
                "UnknownDao",
                "@Repository\npublic class UnknownDao {\n"
                    + "  public UnknownDao(@Qualifier(\"nowhere\") DataSource ds) {}\n"
                    + "}\n"));

    assertThat(uses)
        .containsEntry(
            "OrdersDao", "app.datasource.orders [\"ordersTemplate\",\"ordersDataSource\"]")
        .containsEntry(
            "AuditDao", "app.datasource.audit [\"auditJdbcTemplate\",\"auditDataSource\"]")
        // No JdbcClient bean: Spring Boot's own, on the primary datasource.
        .containsEntry("ReportDao", "app.datasource.orders [\"ordersDataSource\"]")
        .containsEntry(
            "ArchiveDao", "app.datasource.archive [\"archiveDataSource\",\"archiveProperties\"]")
        .containsEntry("LegacyDao", "legacy.db [\"legacy\"]")
        // A qualifier no bean answers: which datasource is not certain.
        .doesNotContainKey("UnknownDao")
        .doesNotContainKey("DataConfig");
  }

  @Test
  void withoutAPrimaryAnUnqualifiedPointIsSpringBootsOwnDatasource() throws IOException {
    Map<String, String> uses =
        uses(
            "billing",
            Map.of(
                "DataConfig",
                "@Configuration\npublic class DataConfig {\n"
                    + "  @Bean @Qualifier(\"reportsDataSource\")\n"
                    + "  DataSource reportsDataSource(@Value(\"${billing.reports.url}\") String"
                    + " url) { return null; }\n"
                    + "  @Bean @Qualifier(\"reportsClient\")\n"
                    + "  JdbcClient reportsClient(@Qualifier(\"reportsDataSource\") DataSource ds)"
                    + " { return JdbcClient.create(ds); }\n"
                    + "  @Bean @Qualifier(\"ledgerClient\")\n"
                    + "  JdbcClient ledgerClient(DataSource dataSource) {"
                    + " return JdbcClient.create(dataSource); }\n"
                    + "}\n",
                "LedgerDao",
                "@Repository\npublic class LedgerDao {\n"
                    + "  public LedgerDao(@Qualifier(\"ledgerClient\") JdbcClient client) {}\n"
                    + "}\n",
                "ReportsReader",
                "@Repository\npublic class ReportsReader {\n"
                    + "  public ReportsReader(@Qualifier(\"reportsClient\") JdbcClient client) {}\n"
                    + "}\n",
                "PlainDao",
                "@Repository\npublic class PlainDao {\n"
                    + "  public PlainDao(JdbcTemplate jdbc) {}\n"
                    + "}\n"));

    assertThat(uses)
        // Spring Boot's own datasource, from spring.datasource.*: Spring Boot backs off when the
        // code declares one, which the declared prefixes record.
        .containsEntry(
            "LedgerDao", "spring.datasource [\"ledgerClient\"] beside [\"billing.reports\"]")
        .containsEntry("ReportsReader", "billing.reports [\"reportsClient\",\"reportsDataSource\"]")
        .containsEntry("PlainDao", "spring.datasource beside [\"billing.reports\"]");
  }
}
