package io.lantern.extract.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SourceSecretsTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '~',
      value = {
        // Fields, constants and variables named as a secret.
        "private String password = \"hunter2\";|private String password = \"******\";",
        "static final String API_KEY = \"sk-live-123\";|static final String API_KEY = \"******\";",
        "String accessToken = \"eyJhbGc\";|String accessToken = \"******\";",
        "String clientSecret = \"abc\";|String clientSecret = \"******\";",
        "this.dbPwd = \"x\";|this.dbPwd = \"******\";",
        "char[] pw = \"x\".toCharArray(); String passwd = \"y\";"
            + "|char[] pw = \"x\".toCharArray(); String passwd = \"******\";",
        "String signingKey = \"a\" + \"b\";|String signingKey = \"******\" + \"******\";",
        "String[] apiKeys = {\"a\", \"b\"};|String[] apiKeys = {\"******\", \"******\"};",
        // Annotation elements.
        "@DataSourceDefinition(name = \"ds\", password = \"x\")"
            + "|@DataSourceDefinition(name = \"ds\", password = \"******\")",
        // Setters, builders and constructors named as a secret.
        "ds.setPassword(\"hunter2\");|ds.setPassword(\"******\");",
        "Client.builder().token(\"t0k\").build();|Client.builder().token(\"******\").build();",
        "new Credentials(\"user\", \"pass\");|new Credentials(\"******\", \"******\");",
        // A literal key naming a secret.
        "props.put(\"spring.datasource.password\", \"x\");"
            + "|props.put(\"spring.datasource.password\", \"******\");",
        "Map.of(\"user\", \"app\", \"api-key\", \"k\");|Map.of(\"user\", \"app\", \"api-key\", \"******\");",
        "headers.set(\"Authorization\", \"Bearer abc\");|headers.set(\"Authorization\", \"******\");",
        // A constant key naming a secret.
        "headers.set(HttpHeaders.AUTHORIZATION, \"Bearer abc\");"
            + "|headers.set(HttpHeaders.AUTHORIZATION, \"******\");",
        "props.put(PASSWORD_KEY, \"x\");|props.put(PASSWORD_KEY, \"******\");",
        // Comparisons.
        "if (password.equals(\"letmein\"))|if (password.equals(\"******\"))",
        "if (\"letmein\".equals(password))|if (\"******\".equals(password))",
        // Placeholders.
        "@Value(\"${db.password:changeme}\")|@Value(\"${db.password:******}\")",
        "@Value(\"${db.url:${DB_TOKEN:t}}\")|@Value(\"${db.url:${DB_TOKEN:******}}\")",
        // URLs and options in any literal.
        "String url = \"jdbc:postgresql://app:s3cret@db/shop\";"
            + "|String url = \"jdbc:postgresql://db/shop\";",
        "jaas(\"PlainLoginModule required username=\\\"app\\\" password=\\\"x\\\";\");"
            + "|jaas(\"PlainLoginModule required username=\\\"app\\\";\");",
        "String url = \"https://api.example.com/v1?api_key=abc&q=1\";"
            + "|String url = \"https://api.example.com/v1?q=1\";",
        // Comments.
        "// connect with amqp://guest:guest@rabbit|// connect with amqp://rabbit",
        "/* password=hunter2 */ int a;|/* */ int a;",
        "/* password=hunter2*/ int a;|/* */ int a;",
        "// default ${db.password:hunter2}|// default ${db.password:******}",
      })
  void masksSecrets(String code, String scrubbed) {
    assertThat(SourceSecrets.scrub(code)).isEqualTo(scrubbed);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '~',
      value = {
        "String password = \"\";",
        "String password = \"${DB_PASSWORD}\";",
        "@Value(\"${shop.token}\") String token;",
        "String routingKey = \"orders.created\";",
        "String tokenUrl = \"https://auth.example.com/token\";",
        "log.info(\"Password reset for {}\", user);",
        "passwordEncoder.encode(raw); String name = \"orders\";",
        "if (\"a\" == b) { c = 'x'; }",
        "@RequestMapping(\"/orders/{id}\")",
      })
  void keepsWhatIsNotASecret(String code) {
    assertThat(SourceSecrets.scrub(code)).isEqualTo(code);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '~',
      value = {
        "String password = \"unterminated",
        "String s = \"\"\"\n  never closed",
        "/* never closed password=x",
        "String password = \"x\\",
        "char c = '",
        "foo(,\"x\")",
        "= \"x\"",
      })
  void malformedSourceDoesNotFail(String code) {
    assertThat(SourceSecrets.scrub(code)).isNotNull().doesNotContain("password=x");
  }

  @Test
  void masksTextBlocksKeepingTheirLines() {
    String code =
        """
        String key = \"""
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBg
            -----END PRIVATE KEY-----
            \""";
        int line = 6;
        """;
    String scrubbed = SourceSecrets.scrub(code);
    assertThat(scrubbed).doesNotContain("MIIEvQIBADANBg").contains("******");
    assertThat(scrubbed.lines()).hasSameSizeAs(code.lines().toList());
    assertThat(scrubbed.lines().toList().getLast()).isEqualTo("int line = 6;");
  }

  @Test
  void masksAPrivateKeyWhateverItIsNamed() {
    String code =
        "load(\"-----BEGIN RSA PRIVATE KEY-----\\nMIIE\\n-----END RSA PRIVATE KEY-----\");";
    assertThat(SourceSecrets.scrub(code)).isEqualTo("load(\"******\");");
  }

  @Test
  void scrubsAWholeClass() {
    String code =
        """
        package shop;

        /** Talks to the payment provider at https://merchant:pa55@pay.example.com. */
        @Configuration
        class PaymentConfig {
          private static final String WEBHOOK_SECRET = "whsec_123";
          private final String endpoint = "https://pay.example.com";

          @Bean
          PaymentClient client() {
            return PaymentClient.builder()
                .endpoint(endpoint)
                .apiKey("pk_live_456")
                .build();
          }
        }
        """;
    String scrubbed = SourceSecrets.scrub(code);
    assertThat(scrubbed)
        .doesNotContain("pa55", "merchant", "whsec_123", "pk_live_456")
        .contains(
            "https://pay.example.com.",
            "WEBHOOK_SECRET = \"******\"",
            ".apiKey(\"******\")",
            "endpoint = \"https://pay.example.com\"");
    assertThat(scrubbed.lines()).hasSameSizeAs(code.lines().toList());
  }

  @Test
  void leavesSourceWithoutSecretsUnchanged() {
    String code =
        """
        class Order {
          private final String id;
          String describe() { return "Order " + id + " for \\"x\\""; }
        }
        """;
    assertThat(SourceSecrets.scrub(code)).isEqualTo(code);
  }

  @Test
  void masksValuesUnderKnownSecretKeysWhateverTheyAreCalled() {
    String code =
        """
        class Jwt {
          @Value("${jwt.key:literal-one}") String a;
          @Value("${app.token.signing.key:literal-two}") String b;
          void init(Map<String, String> m) { m.put("jwt.key", "literal-three"); }
          String other = "${app.name:literal-four}";
        }
        """;

    String scrubbed = SourceSecrets.scrub(code, Set.of("jwt.key", "app.token.signing.key"));

    assertThat(scrubbed)
        .doesNotContain("literal-one", "literal-two", "literal-three")
        .contains("${jwt.key:******}", "${app.token.signing.key:******}", "literal-four");
    assertThat(SourceSecrets.scrub(code)).contains("literal-one");
  }
}
