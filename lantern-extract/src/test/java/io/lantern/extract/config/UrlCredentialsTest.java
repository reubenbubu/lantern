package io.lantern.extract.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # User info before the host, of any scheme
          jdbc:postgresql://user:pass@db:5432/shop                  | jdbc:postgresql://db:5432/shop
          jdbc:mysql://user:p/a@ss@db/shop?useSSL=false             | jdbc:mysql://db/shop?useSSL=false
          r2dbc:postgresql://user:pass@db/shop                      | r2dbc:postgresql://db/shop
          r2dbc:pool:postgresql://user:pass@db/shop                 | r2dbc:pool:postgresql://db/shop
          amqp://guest:guest@rabbit:5672/orders                     | amqp://rabbit:5672/orders
          amqps://app:s3cret@rabbit/vhost                           | amqps://rabbit/vhost
          redis://:s3cret@cache:6379/0                              | redis://cache:6379/0
          rediss://default:s3cret@cache:6380                        | rediss://cache:6380
          mongodb://app:s3cret@m1:27017,m2:27017/shop?replicaSet=rs | mongodb://m1:27017,m2:27017/shop?replicaSet=rs
          mongodb+srv://app:s3cret@cluster.example.net/shop         | mongodb+srv://cluster.example.net/shop
          https://bot:tok@api.example.com/v1                        | https://api.example.com/v1
          # Query and JDBC properties naming a user or a secret, any case
          jdbc:postgresql://db/shop?user=app&password=x&ssl=true    | jdbc:postgresql://db/shop?ssl=true
          jdbc:postgresql://db/shop?PASSWORD=x&sslmode=require      | jdbc:postgresql://db/shop?sslmode=require
          jdbc:sqlserver://db:1433;databaseName=shop;user=sa;Password=x;encrypt=true | jdbc:sqlserver://db:1433;databaseName=shop;encrypt=true
          jdbc:h2:mem:test;USER=sa;PWD=x                            | jdbc:h2:mem:test
          https://api.example.com/v1?apikey=k&q=1                   | https://api.example.com/v1?q=1
          https://api.example.com/v1?q=1&api_key=k&token=t          | https://api.example.com/v1?q=1
          https://s3.example.com/b?access_key=a&secret_key=s&region=eu | https://s3.example.com/b?region=eu
          https://hook.example.com/x?client_secret=s#top            | https://hook.example.com/x#top
          # Oracle thin and OCI
          jdbc:oracle:thin:scott/tiger@//db:1521/ORCL               | jdbc:oracle:thin:@//db:1521/ORCL
          jdbc:oracle:thin:scott/tiger@db:1521:ORCL                 | jdbc:oracle:thin:@db:1521:ORCL
          jdbc:oracle:oci:scott/tiger@ORCL                          | jdbc:oracle:oci:@ORCL
          # Inside placeholders, and placeholders inside a URL
          ${DB_URL:jdbc:oracle:thin:scott/tiger@//db:1521/X}        | ${DB_URL:jdbc:oracle:thin:@//db:1521/X}
          ${REDIS_URL:redis://:pw@cache:6379}                       | ${REDIS_URL:redis://cache:6379}
          jdbc:postgresql://${DB_USER}:${DB_PASS}@${DB_HOST:db}/shop?password=${PW} | jdbc:postgresql://${DB_HOST:db}/shop
          # Several URLs in one value
          amqp://a:1@r1,amqp://b:2@r2                               | amqp://r1,amqp://r2
          # A placeholder as the scheme or subprotocol
          jdbc:${DB_VENDOR:postgresql}://user:pass@db/shop          | jdbc:${DB_VENDOR:postgresql}://db/shop
          ${PROTO:https}://u:p@h/x                                  | ${PROTO:https}://h/x
          # A password holding # ? / or @
          amqp://u:p#ss@rabbit/vh                                   | amqp://rabbit/vh
          amqp://u:p?ss@rabbit                                      | amqp://rabbit
          amqp://u:p/ss@rabbit/vh                                   | amqp://rabbit/vh
          amqp://u:p@ss@rabbit/vh?heartbeat=10                      | amqp://rabbit/vh?heartbeat=10
          # Secret property names beyond config keys'
          https://acct.blob.core.windows.net/c?sv=2020&sig=abc&se=1 | https://acct.blob.core.windows.net/c?sv=2020&se=1
          https://s3.example.com/b?X-Amz-Credential=c&X-Amz-Signature=s&x=1 | https://s3.example.com/b?x=1
          https://api.example.com/v1?key=k&q=1                      | https://api.example.com/v1?q=1
          https://api.example.com/v1?q=1&pass=p&auth=a              | https://api.example.com/v1?q=1
          # User info without a scheme
          guest:guest@r1:5672,guest:guest@r2                        | r1:5672,r2
          user:p@ss@host                                            | host
          amqp://a:1@r1,b:2@r2                                      | amqp://r1,r2
          # Separators are copied as written
          https://x.example/a?a=1?password=p&b=2;c=3                | https://x.example/a?a=1?b=2;c=3
          jdbc:h2:mem:t;PASSWORD=x;                                 | jdbc:h2:mem:t;
          primary=jdbc:postgresql://u:p@db1/shop backup=redis://:p@c | primary=jdbc:postgresql://db1/shop backup=redis://c
          """)
  void removesCredentialsOfEachShapeAndKeepsTheRest(String written, String expected) {
    assertThat(clean(written)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:postgresql://db:5432/shop?sslmode=require",
        "jdbc:sqlserver://db:1433;databaseName=shop;encrypt=true",
        "jdbc:oracle:thin:@//db:1521/ORCL",
        "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "http://localhost:8080/api/orders",
        "redis://cache:6379",
        "file:///var/data",
        "${TAX_SERVICE_URL}",
        "orders@example.com",
        "jdbc:postgresql://db/shop?sslkey=/k&sslmode=require",
        "https://medium.com/@team/post",
        "http://host:8080/@team/post",
        "https://x.example/a?a=1?b=2",
        "https://x.example/a?",
        "https://x.example/a;p=1?q=2&&r=3",
        "jdbc:h2:mem:t;",
        "git@github.com:org/repo.git",
        "classpath:db/migration",
        "order.created",
        "password=x",
        "a , b",
        ""
      })
  void textWithoutCredentialsInAUrlIsUnchanged(String text) {
    assertThat(clean(text)).isEqualTo(text);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:mysql://address=(host=db)(user=u)(password=p)/shop",
        "jdbc:db2://db:50000/shop:password=p;",
        "jdbc:mysql://address=(host=db)(pass=p)/shop",
        "jdbc:mysql://address=(host=db)(key=k)/shop"
      })
  void aCredentialInAFormNotUnderstoodIsReported(String text) {
    assertThat(UrlCredentials.mayHoldCredential(clean(text))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdbc:postgresql://db/shop?sslkey=/k", "amqp://rabbit?routingkey=a"})
  void ordinaryPropertiesAreNotReportedAsCredentials(String text) {
    assertThat(UrlCredentials.mayHoldCredential(clean(text))).isFalse();
  }
}
