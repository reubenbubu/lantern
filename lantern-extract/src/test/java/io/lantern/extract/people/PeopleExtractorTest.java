package io.lantern.extract.people;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

  @Test
  void accountEndpointsRoleTypesAndOtherRoleChecks() throws IOException {
    Map<String, String> files = new java.util.HashMap<>();
    files.put(
        "pom.xml",
        "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId><artifactId>shop</artifactId><version>1</version></project>\n");
    files.put(
        "src/main/java/com/example/Registration.java",
"""
package com.example;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class Registration {
  @PostMapping("/register")
  @Operation(summary = "Register an attendee account")
  public void register() {}

  @GetMapping("/confirm/{token}")
  public void confirm() {}

  @PostMapping("/users/register")
  @PreAuthorize("hasRole('INTERNAL_SERVICE')")
  public void provision() {}

  @GetMapping("/items")
  public void items() {}

  @IsAdmin
  @PostMapping(path = "/reset-password")
  public void reset() {}
}
""");
    files.put(
        "src/main/java/com/example/IsAdmin.java",
"""
package com.example;

import org.springframework.security.access.prepost.PreAuthorize;

@PreAuthorize("hasRole('ADMIN')")
public @interface IsAdmin {}
""");
    files.put(
        "src/main/java/com/example/AppUser.java",
"""
package com.example;

import jakarta.annotation.security.DeclareRoles;
import jakarta.persistence.Entity;
import java.util.Set;

@Entity
@DeclareRoles({"AUDITOR"})
public class AppUser {
  private String passwordHash;
  private Role role;
  private Set<Authority> authorities;
  private Status status;
}
""");
    files.put(
        "src/main/java/com/example/Role.java",
"""
package com.example;

public enum Role {
  USER,
  ADMIN,
  SYSTEM
}
""");
    files.put(
        "src/main/java/com/example/Authority.java",
"""
package com.example;

public enum Authority {
  ORGANIZER
}
""");
    files.put(
        "src/main/java/com/example/Status.java",
"""
package com.example;

public enum Status {
  ACTIVE
}
""");
    files.put(
        "src/main/java/com/example/Actor.java",
"""
package com.example;

public class Actor {
  boolean hasRole(String role) {
    return false;
  }

  void pay() {
    if (hasRole("ORGANIZER")) {
      return;
    }
  }
}
""");
    files.put(
        "realm-export.json",
"""
{
  "realm": "shop",
  "registrationAllowed": true,
  "roles": {
    "realm": []
  }
}
""");
    Repo repo = Fixtures.repo(temp.resolve("accounts"), "shop", files);
    String facts =
        Fixtures.render(
            new PeopleExtractor()
                .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT));
    assertThat(facts.lines())
        .contains(
            "field:com.example.Authority#ORGANIZER person-evidence role-type {\""
                + "field\":\"field:com.example.AppUser#authorities\",\"name\":\"ORGAN"
                + "IZER\",\"type\":\"com.example.Authority\"} @shop:src/main/java/com"
                + "/example/Authority.java#4-4 0.7",
            "field:com.example.Role#ADMIN person-evidence role-type {\"field\":"
                + "\"field:com.example.AppUser#role\",\"name\":\"ADMIN\",\"type\":\"c"
                + "om.example.Role\"} @shop:src/main/java/com/example/Role.java#5-5 0"
                + ".7",
            "field:com.example.Role#USER person-evidence role-type {\"field\":\""
                + "field:com.example.AppUser#role\",\"name\":\"USER\",\"type\":\"com."
                + "example.Role\"} @shop:src/main/java/com/example/Role.java#4-4 0.7",
            "method:com.example.Actor#pay() person-evidence role {\"name\":\"OR"
                + "GANIZER\",\"source\":\"hasRole\"} @shop:src/main/java/com/example/"
                + "Actor.java#9-9 0.7",
            "method:com.example.Registration#confirm() person-evidence account "
                + "{\"action\":\"confirm\",\"path\":\"/api/public/confirm/{token}\"} "
                + "@shop:src/main/java/com/example/Registration.java#17-17 0.7",
            "method:com.example.Registration#provision() person-evidence accoun"
                + "t {\"action\":\"sign-up\",\"guard\":[\"INTERNAL_SERVICE\"],\"path\""
                + ":\"/api/public/users/register\"} @shop:src/main/java/com/example/R"
                + "egistration.java#20-20 0.7",
            "method:com.example.Registration#register() person-evidence account"
                + " {\"action\":\"sign-up\",\"path\":\"/api/public/register\",\"summa"
                + "ry\":\"Register an attendee account\"} @shop:src/main/java/com/exa"
                + "mple/Registration.java#13-14 0.7",
            "method:com.example.Registration#reset() person-evidence account {\""
                + "action\":\"reset\",\"guard\":[\"ADMIN\"],\"path\":\"/api/public/re"
                + "set-password\"} @shop:src/main/java/com/example/Registration.java#"
                + "28-28 0.7",
            "method:com.example.Registration#reset() person-evidence role {\"an"
                + "notation\":\"com.example.IsAdmin\",\"name\":\"ADMIN\",\"source\":\""
                + "PreAuthorize\"} @shop:src/main/java/com/example/Registration.java#"
                + "27-27 0.7",
            "repo:shop person-evidence account {\"action\":\"sign-up\",\"source"
                + "\":\"keycloak-realm\"} @shop:realm-export.json#3-3 1.0",
            "type:com.example.AppUser person-evidence role {\"name\":\"AUDITOR\""
                + ",\"source\":\"DeclareRoles\"} @shop:src/main/java/com/example/AppU"
                + "ser.java#8-8 0.7")
        // Items is no account step, Status types no role, SYSTEM names software.
        .noneMatch(l -> l.contains("items()") || l.contains("Status") || l.contains("SYSTEM"));
  }
}
