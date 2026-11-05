package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The links and facts a lead's first message keeps: those that mention its rarest word (#860). */
class LeadWordsTest {

  @Test
  void theWordsOfALeadComeFromWhatItIsAbout() {
    assertThat(
            LeadWords.of(
                List.of(
                    "lead:contract-mismatch/frontend:Web/GET%20/api/organizations/{id}/preview")))
        .containsExactly("organizations", "preview");
    assertThat(
            LeadWords.of(List.of("lead:identity-from-request/service:g:a/header:Organization-Id")))
        .containsExactly("Organization-Id");
    assertThat(LeadWords.of(List.of("lead:single-instance/service:g:a (again)"))).isEmpty();
  }

  @Test
  void onlyTheLinesHoldingTheRarestWordAreKept() {
    List<String> links =
        List.of(
            "web -> gateway GET /api/organizations",
            "web -> gateway GET /api/organizations/1/preview",
            "gateway -> main GET /api/organizations/{id}",
            "gateway -> auth POST /login");

    assertThat(LeadWords.aboutLines(List.of("organizations", "preview"), links))
        .containsExactly("web -> gateway GET /api/organizations/1/preview");
    assertThat(LeadWords.aboutLines(List.of("absent"), links)).isEmpty();
  }
}
