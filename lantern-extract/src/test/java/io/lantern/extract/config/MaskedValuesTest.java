package io.lantern.extract.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaskedValuesTest {

  private static List<String> taken(String original, String shown) {
    List<String> values = new ArrayList<>();
    MaskedValues.taken(original, shown, values::add);
    return values;
  }

  @Test
  void aMaskedValueIsWhatTheShownLineReplaced() {
    assertThat(taken("a\npassword: hunter2-very-secret\nb", "a\npassword: ******\nb"))
        .containsExactly("hunter2-very-secret");
    assertThat(
            taken(
                "String KEY = \"sk-live-1234567890\";",
                SourceSecrets.scrub("String apiKey = \"sk-live-1234567890\";")
                    .replace("apiKey", "KEY")))
        .containsExactly("sk-live-1234567890");
  }

  @Test
  void aCredentialRemovedFromAUrlGivesItsPasswordButNotItsHostOrDatabase() {
    String original = "url = \"jdbc:postgresql://shop:s3cr3t-pass@db:5432/shop\"";
    String shown = SourceSecrets.scrub("String " + original + ";").replace("String ", "");
    assertThat(shown).doesNotContain("s3cr3t-pass");
    List<String> values = taken(original, shown.substring(0, shown.length() - 1));
    assertThat(values).contains("s3cr3t-pass");
    assertThat(values).doesNotContain("shop", "db", "5432", "jdbc", "postgresql");
    assertThat(
            taken("u = \"postgres://billing:collections-pw@billing-db/billing\"", "u = \"******\""))
        .containsExactly("postgres://billing:collections-pw@billing-db/billing", "collections-pw");
  }

  @Test
  void maskedProseIsOneValueNotItsWords() {
    assertThat(taken("throw new X(\"Invalid username or password\");", "throw new X(\"******\");"))
        .containsExactly("Invalid username or password");
  }

  @Test
  void aPasswordIsFoundInASpanThatHoldsMoreThanTheUrl() {
    assertThat(
            taken(
                "a(\"secret value\", \"jdbc:x://app:pw-123456@db/app\");",
                "a(\"******\", \"******\");"))
        .contains("pw-123456");
  }

  @Test
  void anUnchangedTextGivesNothing() {
    assertThat(taken("a\nb", "a\nb")).isEmpty();
  }
}
