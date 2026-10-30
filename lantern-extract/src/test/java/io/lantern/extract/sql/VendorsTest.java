package io.lantern.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VendorsTest {

  @Test
  void filesWrittenForADatabaseByFolderOrPlatformScript() {
    assertThat(Vendors.of("src/main/resources/db/migration/postgresql/V1__a.sql"))
        .isEqualTo("PostgreSQL");
    assertThat(Vendors.of("app/src/main/resources/db/mysql/schema.sql")).isEqualTo("MySQL");
    assertThat(Vendors.of("src/main/resources/schema-h2.sql")).isEqualTo("H2");
    assertThat(Vendors.of("src/main/resources/db/migration/V1__mysql_users.sql")).isNull();
    // A module named after its database is not a database's copy.
    assertThat(Vendors.of("postgres/src/main/resources/db/migration/V1__a.sql")).isNull();
  }

  @Test
  void selectsTheDatabasesCopyAndKeepsEveryFileOtherwise() {
    List<String> files =
        List.of("db/h2/schema.sql", "db/mysql/schema.sql", "db/data.sql", "schema-mysql.sql");

    assertThat(Vendors.select(files, "MySQL"))
        .containsExactly("db/mysql/schema.sql", "db/data.sql", "schema-mysql.sql");
    assertThat(Vendors.select(files, "Oracle")).isEqualTo(files);
    assertThat(Vendors.select(files, null)).isEqualTo(files);
  }

  @Test
  void liquibaseDbmsLists() {
    assertThat(Vendors.dbms("postgresql, h2", "PostgreSQL")).isTrue();
    assertThat(Vendors.dbms("postgresql, h2", "MySQL")).isFalse();
    assertThat(Vendors.dbms("!h2", "MySQL")).isTrue();
    assertThat(Vendors.dbms("!h2", "H2")).isFalse();
    assertThat(Vendors.dbms("all", "Oracle")).isTrue();
    assertThat(Vendors.dbms("mssql", "SQL Server")).isTrue();
    assertThat(Vendors.dbms("none", null)).isFalse();
    assertThat(Vendors.dbms("oracle", null)).isTrue();
    assertThat(Vendors.dbms(null, "H2")).isTrue();
  }
}
