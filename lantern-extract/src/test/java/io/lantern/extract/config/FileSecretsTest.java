package io.lantern.extract.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A secret's placeholder keeps its variable name; only what is written is masked (#912). */
class FileSecretsTest {

  private static String masked(String path, String text) {
    return FileSecrets.masked(path, text.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        // Spring properties and YAML
        "application.properties | bff.jwt.signing-key=${BFF_JWT_SIGNING_KEY:dev-signing-key-123}"
            + " | bff.jwt.signing-key=${BFF_JWT_SIGNING_KEY:******}",
        "application.yml | `  password: ${DB_PASSWORD:hunter2}` | `  password: ${DB_PASSWORD:******}`",
        "application.yml | `  password: \"${DB_PASSWORD:hunter2}\"`"
            + " | `  password: \"${DB_PASSWORD:******}\"`",
        "application.yml | `  password: ${DB_PASSWORD}` | `  password: ${DB_PASSWORD}`",
        "application.yml | `  password: ${DB_PASSWORD:}` | `  password: ${DB_PASSWORD:}`",
        "application.properties | app.secret=${OUTER:${INNER:s3cret-value}}"
            + " | app.secret=${OUTER:${INNER:******}}",
        "application.properties | app.secret=${app.jwt-secret:s3cret-value}"
            + " | app.secret=${app.jwt-secret:******}",
        "application.properties | app.secret=pre-${TOKEN}-post | app.secret=******${TOKEN}******",
        // .env and Compose: the shell's forms
        ".env | DB_PASSWORD=${DB_PASSWORD:-hunter2} | DB_PASSWORD=${DB_PASSWORD:-******}",
        "docker-compose.yml | `      POSTGRES_PASSWORD: ${PG_PASSWORD:?set it}`"
            + " | `      POSTGRES_PASSWORD: ${PG_PASSWORD:?******}`",
        // Literals are masked whole, as before
        "application.yml | `  password: hunter2` | `  password: ******`",
        ".env | DB_PASSWORD=hunter2 | DB_PASSWORD=******",
        // A shell default without a colon is never read as a name
        ".env | DB_PASSWORD=${DB-hunter2} | DB_PASSWORD=******",
        "application.properties | app.secret=${UNCLOSED:hunter2 | app.secret=******",
        "application.properties | app.secret=${A:pa}ss} | app.secret=${A:******}******",
        ".env | API_KEY=${API_KEY:+set-value} | API_KEY=${API_KEY:+******}",
      })
  void aPlaceholderKeepsItsNameAndOnlyWhatIsWrittenIsMasked(
      String path, String line, String shown) {
    assertThat(masked(path, line)).isEqualTo(shown);
  }

  @Test
  void noDefaultEverShowsAndTheMaskedValuesListStillHoldsIt() {
    String text =
        String.join(
            "\n",
            "spring:",
            "  datasource:",
            "    password: ${DB_PASSWORD:hunter2-very-secret}",
            "bff:",
            "  jwt:",
            "    signing-key: ${OUTER:${INNER:another-secret-42}}",
            "  other: ${A_TOKEN:-token-value-99}");
    String shown = masked("application.yml", text);

    assertThat(shown)
        .doesNotContain("hunter2", "another-secret", "token-value")
        .contains("${DB_PASSWORD:******}", "${OUTER:${INNER:******}}", "${A_TOKEN:-******}");
    assertThat(shown.split("\n", -1)).hasSize(7);
    List<String> values = new ArrayList<>();
    MaskedValues.taken(text, shown, values::add);
    assertThat(values).contains("hunter2-very-secret", "another-secret-42", "token-value-99");
  }

  @Test
  void aWindowsLineIsMaskedAsAnyOtherAndKeepsItsEnd() {
    assertThat(masked("application.properties", "a=1\r\npassword=hunter2\r\nb=2\r\n"))
        .isEqualTo("a=1\r\npassword=******\r\nb=2\r\n");
    assertThat(masked(".env", "DB_PASSWORD=${DB_PASSWORD:hunter2}\r\n"))
        .isEqualTo("DB_PASSWORD=${DB_PASSWORD:******}\r\n");
  }

  @Test
  void aJavaSecretKeepsItsPlaceholderNameToo() {
    String shown =
        SourceSecrets.scrub(
            "class A {\n  @Value(\"${JWT_SECRET:dev-secret-123}\") String jwtSecret;\n"
                + "  String password = \"hunter2\";\n}");

    assertThat(shown)
        .contains("\"${JWT_SECRET:******}\"")
        .contains("password = \"******\"")
        .doesNotContain("dev-secret-123", "hunter2");
  }
}
