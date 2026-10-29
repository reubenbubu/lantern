package io.lantern.core.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemTitleTest {

  @Test
  void isTheWordsAllRepositoryNamesStartWith() {
    assertThat(
            SystemTitle.derive(
                "project-3",
                List.of(
                    "mediahub-api-gateway",
                    "mediahub-config-repo",
                    "mediahub-config-server",
                    "mediahub-user-service")))
        .isEqualTo("mediahub");
    assertThat(SystemTitle.derive("s", List.of("acme_shop.orders", "acme_shop-billing")))
        .isEqualTo("acme_shop");
    // Compared ignoring case, spelled as the first name in sorted order spells them.
    assertThat(SystemTitle.derive("s", List.of("shop-orders", "Shop-billing"))).isEqualTo("Shop");
    // A name that is all shared words counts too.
    assertThat(SystemTitle.derive("s", List.of("catalog", "catalog-lib"))).isEqualTo("catalog");
  }

  @Test
  void isTheNameOfALoneRepository() {
    assertThat(SystemTitle.derive("project-1", List.of("Enterprise-Spring-Microservices-Template")))
        .isEqualTo("Enterprise-Spring-Microservices-Template");
  }

  @Test
  void isOtherwiseTheSystemsName() {
    assertThat(
            SystemTitle.derive(
                "project-4",
                List.of("Auth-Service", "CRM-Frontend", "Main-Service", "crm-shared-lib")))
        .isEqualTo("project-4");
    // Only whole words count: "cat" is not a word of "catalog".
    assertThat(SystemTitle.derive("project-0", List.of("cat", "catalog"))).isEqualTo("project-0");
    assertThat(SystemTitle.derive("empty", List.of())).isEqualTo("empty");
  }

  @Test
  void isFirstTheLeadingWordsTheReadmeHeadingsShare() {
    // project-4's Maven repositories: three of five headings start with APA-CRM.
    Map<String, String> headings =
        Map.of(
            "Auth-Service", "APA-CRM Auth-Service",
            "Discovery-and-Gateway", "Discovery-and-Gateway",
            "File-Service", "APA-CRM — File-Service",
            "Main-Service", "APA-CRM — Main-Service",
            "Notification-Service", "Notification-Service");
    List<String> repos =
        List.of(
            "Auth-Service",
            "Discovery-and-Gateway",
            "File-Service",
            "Main-Service",
            "Notification-Service",
            "crm-shared-lib");
    assertThat(SystemTitle.derive("project-4", repos, headings)).isEqualTo("APA-CRM");
    // Compared ignoring case and spelled as the first repository spells it; the longer run wins.
    assertThat(
            SystemTitle.derive(
                "s",
                List.of("a", "b"),
                Map.of("a", "Beta Shop: Library", "b", "beta shop — Service")))
        .isEqualTo("Beta Shop");
  }

  @Test
  void needsTwoHeadingsAndHalfOfThoseThatExist() {
    // One heading is not shared.
    assertThat(
            SystemTitle.derive(
                "project-1",
                List.of("Enterprise-Spring-Microservices-Template"),
                Map.of(
                    "Enterprise-Spring-Microservices-Template",
                    "Enterprise Spring Microservices Template")))
        .isEqualTo("Enterprise-Spring-Microservices-Template");
    // Two of five is less than half, so the repository names decide.
    assertThat(
            SystemTitle.derive(
                "set",
                List.of("shop-a", "shop-b", "shop-c", "shop-d", "shop-e"),
                Map.of(
                    "shop-a", "Acme orders",
                    "shop-b", "Acme billing",
                    "shop-c", "Catalog",
                    "shop-d", "Delivery",
                    "shop-e", "Emails")))
        .isEqualTo("shop");
    // Only a run at least half the headings start with counts.
    assertThat(
            SystemTitle.derive(
                "s",
                List.of("a", "b", "c", "d", "e"),
                Map.of(
                    "a", "Alpha one",
                    "b", "Alpha two",
                    "c", "Beta one",
                    "d", "Beta two",
                    "e", "Beta three")))
        .isEqualTo("Beta");
  }

  @Test
  void readsTheFirstLevelOneHeading() {
    assertThat(SystemTitle.heading("Intro\n## Not this\n# APA-CRM — File-Service ##\n# Later"))
        .contains("APA-CRM — File-Service");
    assertThat(SystemTitle.heading("#NoSpace\n## Two\n")).isEmpty();
    assertThat(SystemTitle.heading("#   \n")).isEmpty();
  }
}
