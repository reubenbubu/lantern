package io.lantern.extract.people;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code people} fixture: golden facts with the code parsed
 * without a classpath (degraded mode), and the pieces that read names and expressions.
 */
class PeopleExtractorTest {

  private static final long SNAPSHOT = 7;

  @TempDir Path temp;

  @Test
  void parsed() throws IOException {
    Repo repo = Fixtures.repo("people", temp.resolve("people"), "people");
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));

    List<Fact> facts = new PeopleExtractor().extract(inputs, SNAPSHOT);

    Fixtures.checkEnvelope(
        facts, List.of(repo), SNAPSHOT, PeopleExtractor.NAME, PeopleExtractor.VERSION);
    assertThat(new PeopleExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    Fixtures.assertGolden("people/people-parsed.txt", facts);
    // Test code, a permission, a plain class and a file without a router name no one.
    assertThat(Fixtures.render(facts))
        .doesNotContain("TESTER", "orders:write", "not-a-route", "crm.Members", "offline_access")
        .doesNotContain("default-roles", "InvitationStatus", "InvitationRequest", "\"**\"");
  }

  @Test
  void aMembershipTypeNamesTheScopeItIsAMemberOf() {
    assertThat(PeopleExtractor.memberScope("RequiresOrganizationMembership"))
        .isEqualTo("Organization");
    assertThat(PeopleExtractor.memberScope("OrganizationMembershipControlAspect"))
        .isEqualTo("Organization");
    assertThat(PeopleExtractor.memberScope("TeamMember")).isEqualTo("Team");
    assertThat(PeopleExtractor.memberScope("Member")).isEmpty();
    assertThat(PeopleExtractor.memberScope("RequiresMembership")).isEmpty();
    assertThat(PeopleExtractor.memberScope("MemberSelector")).isNull();
    assertThat(PeopleExtractor.memberScope("Members")).isNull();
    assertThat(PeopleExtractor.memberScope("OrderService")).isNull();
  }

  @Test
  void securityCallsNameRoles() {
    String key =
        "org.springframework.security.config.annotation.web.configurers.authorization."
            + "AuthorizeHttpRequestsConfigurer$AuthorizedUrl#hasAnyRole(java.lang.String[])";
    assertThat(PeopleExtractor.securityMethod(key)).isEqualTo("hasAnyRole");
    assertThat(PeopleExtractor.securityMethod("com.example.Checks#hasRole(java.lang.String)"))
        .isNull();
    assertThat(PeopleExtractor.callRoles("hasAnyRole", java.util.Arrays.asList("A", null, "B")))
        .containsExactly("A", "B");
    assertThat(PeopleExtractor.callRoles("hasAuthority", List.of("ROLE_C", "read")))
        .containsExactly("C");
  }

  @Test
  void expressionsNameRolesAndRoleAuthorities() {
    assertThat(PeopleExtractor.expressionRoles("hasRole('ADMIN') and #id == 1"))
        .containsExactly("ADMIN");
    assertThat(PeopleExtractor.expressionRoles("hasAnyRole('A', \"B\") or hasRole('A')"))
        .containsExactly("A", "B");
    assertThat(PeopleExtractor.expressionRoles("hasAuthority('ROLE_STAFF')"))
        .containsExactly("STAFF");
    assertThat(PeopleExtractor.expressionRoles("hasAuthority('orders:write')")).isEmpty();
    assertThat(PeopleExtractor.expressionRoles("isAuthenticated()")).isEmpty();
  }
}
