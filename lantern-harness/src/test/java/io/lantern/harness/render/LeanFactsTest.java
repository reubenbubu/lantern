package io.lantern.harness.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Fact lines in the lean form (#873). */
class LeanFactsTest {

  private static final String TYPE = "type:com.crm.auth.service.AuthorizationService";
  private static final String AUTHORIZE =
      "method:com.crm.auth.service.AuthorizationService#authorize("
          + "com.crm.sharedlib.core.dto.request.AuthorizationRequest)";

  private static Fact fact(String subject, String predicate, FactObject object, String path) {
    return fact(subject, predicate, object, path, "{}");
  }

  private static Fact fact(
      String subject, String predicate, FactObject object, String path, String json) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        (ObjectNode) FactJson.parse(json),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        Evidence.of("Auth-Service", path, null, null),
        1.0,
        Validity.from(1));
  }

  private static FactObject entity(String id) {
    return FactObject.entity(EntityId.parse(id));
  }

  private static final String FILE = "src/main/java/com/crm/auth/service/AuthorizationService.java";

  /** The facts of one class as the Summarize test's project-4 item shows them, in line order. */
  private static List<Fact> facts() {
    return List.of(
        fact(
            AUTHORIZE,
            "CALLS",
            entity("method:com.crm.sharedlib.rbac.dto.JwtPayload#getId()"),
            FILE),
        fact(AUTHORIZE, "CALLS", entity("method:java.lang.String#trim()"), FILE),
        fact(TYPE, "CONTAINS", entity("field:com.crm.auth.service.AuthorizationService#log"), FILE),
        fact(TYPE, "CONTAINS", entity(AUTHORIZE), FILE),
        fact(TYPE, "kind", FactObject.string("class"), FILE),
        fact(
            TYPE,
            "stereotype",
            FactObject.string("service"),
            FILE,
            "{\"annotation\":\"org.springframework.stereotype.Service\"}"),
        fact("type:com.crm.auth.Other", "kind", FactObject.string("interface"), "src/Other.java"));
  }

  private static List<LeanFacts.Labeled> labeled(List<Fact> facts) {
    List<LeanFacts.Labeled> labeled = new ArrayList<>();
    for (int i = 0; i < facts.size(); i++) {
      labeled.add(new LeanFacts.Labeled("F" + (i + 1), facts.get(i)));
    }
    return labeled;
  }

  @Test
  void factsAreGroupedByFileAndSubjectWithMembersUnderTheirTypeAndShortNames() {
    List<Fact> facts = facts();
    LeanFacts.Names names = new LeanFacts.Names(facts);

    String text = LeanFacts.render(labeled(facts), names);

    assertThat(text)
        .isEqualTo(
            """
            @ Auth-Service:src/main/java/com/crm/auth/service/AuthorizationService.java
            AuthorizationService
              [F3] CONTAINS field log
              [F4] CONTAINS method authorize(AuthorizationRequest)
                [F1] CALLS JwtPayload#getId()
                [F2] CALLS String#trim()
              [F5] kind "class"
              [F6] stereotype "service" {"annotation":"Service"}
            @ Auth-Service:src/Other.java
            Other
              [F7] kind "interface"
            """);
    assertThat(names.legend())
        .isEqualTo(
            """
            AuthorizationService = com.crm.auth.service.AuthorizationService
            AuthorizationRequest = com.crm.sharedlib.core.dto.request.AuthorizationRequest
            JwtPayload = com.crm.sharedlib.rbac.dto.JwtPayload
            Service = org.springframework.stereotype.Service
            Other = com.crm.auth.Other
            """);
  }

  @Test
  void everyFactIsListedOnceWithItsReference() {
    List<Fact> facts = new ArrayList<>(facts());
    // A cycle of containment and a part contained twice must not hide or repeat a fact.
    facts.add(fact("package:a", "CONTAINS", entity("package:b"), "x"));
    facts.add(fact("package:b", "CONTAINS", entity("package:a"), "x"));
    facts.add(fact("package:b", "CONTAINS", entity(TYPE), FILE));
    String text = LeanFacts.render(labeled(facts), new LeanFacts.Names(facts));

    assertThat(text)
        .contains(
            "@ Auth-Service:x\npackage:a\n  [F8] CONTAINS package:b\n    [F9] CONTAINS package:a\n")
        .contains(
            "package:b\n  [F10] CONTAINS type AuthorizationService\n    [F3] CONTAINS field log\n");
    Matcher refs = Pattern.compile("\\[(F\\d+)]").matcher(text);
    List<String> seen = new ArrayList<>();
    while (refs.find()) {
      seen.add(refs.group(1));
    }
    assertThat(seen)
        .containsExactlyInAnyOrderElementsOf(
            labeled(facts).stream().map(LeanFacts.Labeled::label).toList());
  }

  @Test
  void twoClassesOfOneSimpleNameKeepTheirFullNames() {
    List<Fact> facts =
        List.of(
            fact("type:a.b.Order", "kind", FactObject.string("class"), "A.java"),
            fact("type:c.d.Order", "kind", FactObject.string("record"), "B.java"));
    LeanFacts.Names names = new LeanFacts.Names(facts);

    assertThat(LeanFacts.render(labeled(facts), names))
        .contains("\na.b.Order\n")
        .contains("\nc.d.Order\n");
    assertThat(names.legend()).isEmpty();
  }
}
