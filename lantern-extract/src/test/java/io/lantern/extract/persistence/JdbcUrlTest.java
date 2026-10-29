package io.lantern.extract.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JdbcUrlTest {

  @Test
  void readsTheTechnologyServerAndDatabase() {
    assertThat(JdbcUrl.of("jdbc:postgresql://shop-db:5432/shop", null))
        .isEqualTo(
            new JdbcUrl(
                "jdbc:postgresql://shop-db:5432/shop", "PostgreSQL", "shop-db:5432", "shop"));
    assertThat(JdbcUrl.of("jdbc:mysql://db1:3306,db2:3306/orders?useSSL=true", null))
        .isEqualTo(
            new JdbcUrl(
                "jdbc:mysql://db1:3306,db2:3306/orders?useSSL=true",
                "MySQL",
                "db1:3306,db2:3306",
                "orders"));
    assertThat(JdbcUrl.of("jdbc:sqlserver://legacy:1433;databaseName=old;encrypt=true", null))
        .isEqualTo(
            new JdbcUrl(
                "jdbc:sqlserver://legacy:1433;databaseName=old;encrypt=true",
                "SQL Server",
                "legacy:1433",
                "old"));
    assertThat(JdbcUrl.of("jdbc:oracle:thin:@//wh-db:1521/WH", null))
        .isEqualTo(new JdbcUrl("jdbc:oracle:thin:@//wh-db:1521/WH", "Oracle", "wh-db:1521", "WH"));
    assertThat(JdbcUrl.of("jdbc:oracle:thin:@wh-db:1521:ORCL", null))
        .isEqualTo(
            new JdbcUrl("jdbc:oracle:thin:@wh-db:1521:ORCL", "Oracle", "wh-db:1521", "ORCL"));
    assertThat(JdbcUrl.of("jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1", null))
        .isEqualTo(new JdbcUrl("jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1", "H2", null, "shop"));
    assertThat(JdbcUrl.of("jdbc:tc:postgresql:16:///test", null))
        .isEqualTo(new JdbcUrl("jdbc:tc:postgresql:16:///test", "PostgreSQL", null, "test"));
    assertThat(JdbcUrl.of("r2dbc:postgresql://db/shop", null).technology()).isEqualTo("PostgreSQL");
  }

  @Test
  void takesTheTechnologyFromTheDriverWhenTheUrlDoesNotSay() {
    assertThat(JdbcUrl.of("${DATABASE_URL}", "org.postgresql.Driver"))
        .isEqualTo(new JdbcUrl("${DATABASE_URL}", "PostgreSQL", null, null));
    assertThat(JdbcUrl.of("${DATABASE_URL}", null).technology()).isNull();
  }

  @Test
  void removesCredentials() {
    assertThat(JdbcUrl.withoutCredentials("jdbc:postgresql://shop:s3cr/et@shop-db:5432/shop"))
        .isEqualTo("jdbc:postgresql://shop-db:5432/shop");
    assertThat(
            JdbcUrl.withoutCredentials(
                "jdbc:postgresql://db/shop?user=shop&sslmode=require&password=s3cret"))
        .isEqualTo("jdbc:postgresql://db/shop?sslmode=require");
    assertThat(JdbcUrl.withoutCredentials("jdbc:mysql://db/shop?password=x&user=y"))
        .isEqualTo("jdbc:mysql://db/shop");
    assertThat(
            JdbcUrl.withoutCredentials(
                "jdbc:sqlserver://db:1433;user=sa;password=x;databaseName=old;trustStorePassword=y"))
        .isEqualTo("jdbc:sqlserver://db:1433;databaseName=old");
    assertThat(JdbcUrl.withoutCredentials("jdbc:oracle:thin:scott/tiger@//wh-db:1521/WH"))
        .isEqualTo("jdbc:oracle:thin:@//wh-db:1521/WH");
    assertThat(JdbcUrl.withoutCredentials("jdbc:h2:mem:test;USER=sa;PASSWORD=x"))
        .isEqualTo("jdbc:h2:mem:test");
    assertThat(JdbcUrl.withoutCredentials("jdbc:postgresql://db/shop?sslkey=/k&sslpassword=x"))
        .isEqualTo("jdbc:postgresql://db/shop?sslkey=/k");
    assertThat(
            JdbcUrl.withoutCredentials(
                "${DB_URL:jdbc:oracle:thin:scott/tiger@//wh:1521/WH} or"
                    + " ${OTHER:jdbc:postgresql://u:p@db/shop?password=x}"))
        .isEqualTo(
            "${DB_URL:jdbc:oracle:thin:@//wh:1521/WH} or ${OTHER:jdbc:postgresql://db/shop}");
  }

  @Test
  void givesNoUrlWhenACredentialMightBeLeft() {
    JdbcUrl url = JdbcUrl.of("jdbc:mysql://address=(host=db)(user=u)(password=x)/shop", null);
    assertThat(url.url()).isNull();
    assertThat(url.technology()).isEqualTo("MySQL");
  }
}
