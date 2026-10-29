package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Keeps the prompt's version in step with its text, so cached answers are not reused wrongly. */
class ResolvePromptTest {

  /**
   * The fingerprint of the prompt texts for {@link ResolvePrompt#VERSION}. When this fails, the
   * prompt changed: raise the version, then update the fingerprint and the version here. The way
   * facts and candidates are written into the prompt is checked by {@code LinkResolverTest}.
   */
  @Test
  void theVersionIsRaisedWhenThePromptChanges() throws Exception {
    String texts =
        ResolvePrompt.SYSTEM
            + "\n--\n"
            + ResolvePrompt.RETRY
            + "\n--\n"
            + LinkResolver.MAX_OUTPUT_TOKENS
            + "\n--\n"
            + LinkResolver.MAX_CANDIDATE_FACTS;
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(ResolvePrompt.VERSION + " " + fingerprint)
        .as("The prompt changed: raise ResolvePrompt.VERSION and update this fingerprint")
        .isEqualTo("1 174aa33ccb9ee78865a05f5346f4cf84b6d84df7da037ca6060989159b75d273");
  }
}
