package io.lantern.core.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
}
