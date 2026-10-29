package io.lantern.extract.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlCredentialsTest {

  private static String clean(String text) {
    return UrlCredentials.withoutCredentials(text);
  }

  @Test
  void removesUserInfo() {
    assertThat(clean("jdbc:postgresql://shop:s3cr/et@shop-db:5432/shop"))
        .isEqualTo("jdbc:postgresql://shop-db:5432/shop");
    // A password holding ';' too: the user info ends at the last '@' before the query.
    assertThat(clean("jdbc:postgresql://u:pa;ss@db/shop")).isEqualTo("jdbc:postgresql://db/shop");
    assertThat(clean("https://user:pw@api.example.com/v1?x=1"))
        .isEqualTo("https://api.example.com/v1?x=1");
  }

  @Test
  void removesUserAndSecretProperties() {
    assertThat(clean("jdbc:postgresql://db/shop?user=shop&sslmode=require&password=s3cret"))
        .isEqualTo("jdbc:postgresql://db/shop?sslmode=require");
    assertThat(clean("jdbc:mysql://db/shop?password=x&user=y")).isEqualTo("jdbc:mysql://db/shop");
    assertThat(
            clean(
                "jdbc:sqlserver://db:1433;user=sa;password=x;databaseName=old;trustStorePassword=y"))
        .isEqualTo("jdbc:sqlserver://db:1433;databaseName=old");
    assertThat(clean("jdbc:h2:mem:test;USER=sa;PASSWORD=x")).isEqualTo("jdbc:h2:mem:test");
    assertThat(clean("jdbc:postgresql://db/shop?sslkey=/k&sslpassword=x"))
        .isEqualTo("jdbc:postgresql://db/shop?sslkey=/k");
  }

  @Test
  void removesOracleCredentials() {
    assertThat(clean("jdbc:oracle:thin:scott/tiger@//wh-db:1521/WH"))
        .isEqualTo("jdbc:oracle:thin:@//wh-db:1521/WH");
    assertThat(clean("jdbc:oracle:thin:scott/\"p@ss\"@wh-db:1521:ORCL"))
        .isEqualTo("jdbc:oracle:thin:@wh-db:1521:ORCL");
    assertThat(clean("jdbc:oracle:thin:@//wh-db:1521/WH"))
        .isEqualTo("jdbc:oracle:thin:@//wh-db:1521/WH");
  }

  @Test
  void readsPastPlaceholders() {
    // A placeholder's default in the password goes with it.
    assertThat(clean("jdbc:postgresql://shop:${DB_PASSWORD:changeme}@db/shop"))
        .isEqualTo("jdbc:postgresql://db/shop");
    assertThat(clean("jdbc:postgresql://${DB_USER}:${DB_PASS:changeme}@${DB_HOST:db}/shop"))
        .isEqualTo("jdbc:postgresql://${DB_HOST:db}/shop");
    assertThat(clean("jdbc:sqlserver://${DB_HOST};databaseName=x;user=sa"))
        .isEqualTo("jdbc:sqlserver://${DB_HOST};databaseName=x");
    assertThat(clean("jdbc:postgresql://${DB_HOST:db}/shop?token=abc&ssl=true"))
        .isEqualTo("jdbc:postgresql://${DB_HOST:db}/shop?ssl=true");
    assertThat(clean("jdbc:postgresql://db/shop?password=${DB_PASS:changeme}&ssl=${SSL:true}"))
        .isEqualTo("jdbc:postgresql://db/shop?ssl=${SSL:true}");
  }

  @Test
  void cleansUrlsInsidePlaceholderDefaults() {
    assertThat(
            clean(
                "${DB_URL:jdbc:oracle:thin:scott/tiger@//wh:1521/WH} or"
                    + " ${OTHER:jdbc:postgresql://u:p@db/shop?password=x}"))
        .isEqualTo(
            "${DB_URL:jdbc:oracle:thin:@//wh:1521/WH} or ${OTHER:jdbc:postgresql://db/shop}");
    assertThat(clean("${A:${B:jdbc:postgresql://u:${P:pw}@db/shop}}"))
        .isEqualTo("${A:${B:jdbc:postgresql://db/shop}}");
  }

  @Test
  void leavesOtherTextAlone() {
    assertThat(clean("${DATABASE_URL}")).isEqualTo("${DATABASE_URL}");
    assertThat(clean("jdbc:postgresql://db:5432/shop")).isEqualTo("jdbc:postgresql://db:5432/shop");
    assertThat(UrlCredentials.mayHoldCredential("jdbc:mysql://address=(password=x)/s")).isTrue();
    assertThat(UrlCredentials.mayHoldCredential("jdbc:mysql://db/s")).isFalse();
  }
}
