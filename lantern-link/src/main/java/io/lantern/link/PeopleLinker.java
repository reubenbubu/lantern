package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.people.PeopleExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes people of what the code shows (ADR 0074): one person for each role the code checks, for the
 * members of each thing the code has members of, for those invited, and, when the code lets people
 * sign up or in and names no member, for the user. A person is always inferred: it is named in the
 * code's own words, cites the facts it rests on, and says which frontends have screens for it.
 * Nothing is added to what the facts show.
 */
final class PeopleLinker {

  /** Entity: a person who uses the system. */
  static final String PERSON = "person";

  /** Attribute of a person: the kind of evidence it rests on, with its description. */
  static final String PERSON_BASIS = "person-basis";

  /** The basis of a person made of a role. */
  static final String ROLE = "role";

  /** The basis of a person made of a membership. */
  static final String MEMBER = "member";

  /** The basis of the invited user. */
  static final String INVITED = "invited";

  /** The basis of the user who signs up and in. */
  static final String ACCOUNT = "account";

  private static final String KEYCLOAK = "keycloak-realm";

  /** The most roles that become people; a code with more says nothing clear about who uses it. */
  static final int MAX_ROLES = 12;

  /** The most facts a person cites. */
  static final int MAX_CITED = 12;

  /** Confidence of a person: it is an inference from what the code names. */
  static final double CONFIDENCE = 0.7;

  private static final Set<String> SIGN_UP = PeopleExtractor.SIGN_UP;
  private static final Set<String> SIGN_IN = PeopleExtractor.SIGN_IN;

  /** The bases from the one that wins when two kinds of evidence make one person. */
  private static final List<String> PRIORITY = List.of(MEMBER, INVITED, ROLE, ACCOUNT);

  /** Path segments of endpoints that are for operators and services, not for people. */
  private static final Set<String> TECHNICAL_SEGMENTS =
      Set.of("actuator", "management", "internal");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}|:[A-Za-z0-9_]+");
  private static final Pattern WORDS =
      Pattern.compile("[_\\-\\s]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

  private final LinkIndex index;
  private final FoundLinks out;

  PeopleLinker(LinkIndex index, FoundLinks out) {
    this.index = index;
    this.out = out;
  }

  /** What a person rests on, while people are being made. */
  private static final class Draft {
    final String name;
    String basis;
    final List<Fact> facts = new ArrayList<>();

    Draft(String name, String basis) {
      this.name = name;
      this.basis = basis;
    }
  }

  void link(Origin origin, Validity validity) {
    // By the person's key, so two kinds of evidence for one name make one person.
    Map<String, Draft> people = new TreeMap<>();
    List<Fact> evidence = new ArrayList<>(index.of(PeopleExtractor.PERSON_EVIDENCE));
    evidence.sort(LinkIndex.BY_ID);

    roles(evidence, people);
    members(evidence, people);
    List<Fact> invitations = of(evidence, PeopleExtractor.INVITATION);
    if (!invitations.isEmpty()) {
      draft(people, "Invited user", INVITED).facts.addAll(invitations);
    }
    List<Fact> routes = of(evidence, PeopleExtractor.ROUTE);
    List<Fact> accounts = new ArrayList<>(accountEndpoints());
    routes.stream().filter(r -> account(path(r))).forEach(accounts::add);
    Draft holder =
        people.values().stream().filter(d -> d.basis.equals(MEMBER)).findFirst().orElse(null);
    if (holder == null && !accounts.isEmpty()) {
      holder = draft(people, "User", ACCOUNT);
    }
    if (holder != null) {
      holder.facts.addAll(accounts);
    }
    if (people.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Draft> person : people.entrySet()) {
      write(person.getKey(), person.getValue(), routes, origin, validity);
    }
  }

  /** The roles the code checks: each is a person, the most often checked first, up to a limit. */
  private void roles(List<Fact> evidence, Map<String, Draft> people) {
    Map<String, List<Fact>> byRole = new TreeMap<>();
    for (Fact fact : of(evidence, PeopleExtractor.ROLE)) {
      byRole
          .computeIfAbsent(
              slug(words(fact.attributes().path("name").asString(""))), r -> new ArrayList<>())
          .add(fact);
    }
    byRole.entrySet().stream()
        .filter(e -> !technical(e.getValue()))
        .sorted(
            Comparator.<Map.Entry<String, List<Fact>>>comparingInt(e -> -e.getValue().size())
                .thenComparing(Map.Entry::getKey))
        .limit(MAX_ROLES)
        .forEach(
            e -> {
              String role = e.getValue().getFirst().attributes().path("name").asString();
              draft(people, words(role), ROLE).facts.addAll(e.getValue());
            });
  }

  /**
   * Whether a role is for software, not people: a Keycloak realm role that only service accounts
   * hold, or, with no realm role, one whose every check guards only endpoints for operators and
   * services (actuator, management, internal paths). Judged by how the role is used, never by its
   * name.
   */
  private boolean technical(List<Fact> role) {
    List<Fact> realm =
        role.stream()
            .filter(f -> f.attributes().path("source").asString("").equals(KEYCLOAK))
            .toList();
    if (!realm.isEmpty()) {
      return realm.stream()
          .allMatch(f -> f.attributes().path("serviceAccountOnly").asBoolean(false));
    }
    return role.stream().allMatch(this::guardsOnlyTechnicalEndpoints);
  }

  private boolean guardsOnlyTechnicalEndpoints(Fact check) {
    List<String> paths = new ArrayList<>();
    for (Fact endpoint : index.of(SpringExtractor.ENDPOINT)) {
      String controller = endpoint.attributes().path("controller").asString("");
      if (endpoint.subject().equals(check.subject())
          || ("type:" + controller).equals(check.subject().toString())) {
        paths.add(endpoint.attributes().path("path").asString(""));
      }
    }
    return !paths.isEmpty()
        && paths.stream()
            .allMatch(
                p ->
                    java.util.Arrays.stream(p.toLowerCase(Locale.ROOT).split("/"))
                        .anyMatch(TECHNICAL_SEGMENTS::contains));
  }

  /** The members of each scope the code names: Organization member, Team member. */
  private void members(List<Fact> evidence, Map<String, Draft> people) {
    Map<String, List<Fact>> byScope = new TreeMap<>();
    for (Fact fact : of(evidence, PeopleExtractor.MEMBERSHIP)) {
      byScope
          .computeIfAbsent(fact.attributes().path("scope").asString(""), s -> new ArrayList<>())
          .add(fact);
    }
    byScope.forEach(
        (scope, facts) ->
            draft(people, scope.isEmpty() ? "Member" : scope + " member", MEMBER)
                .facts
                .addAll(facts));
  }

  private static Draft draft(Map<String, Draft> people, String name, String basis) {
    Draft draft = people.computeIfAbsent(slug(name), s -> new Draft(name, basis));
    // One name, two kinds of evidence: the stronger kind describes the person.
    if (PRIORITY.indexOf(basis) < PRIORITY.indexOf(draft.basis)) {
      draft.basis = basis;
    }
    return draft;
  }

  private static List<Fact> of(List<Fact> evidence, String kind) {
    return evidence.stream().filter(f -> f.object().value().equals(kind)).toList();
  }

  /** The endpoints where people sign up or in. */
  private List<Fact> accountEndpoints() {
    List<Fact> result = new ArrayList<>();
    for (Fact fact : index.of(SpringExtractor.ENDPOINT)) {
      String path = fact.attributes().path("path").asString("");
      String[] segments = PLACEHOLDER.matcher(path).replaceAll("").split("/");
      String last = segments.length == 0 ? "" : segments[segments.length - 1];
      if (SIGN_UP.contains(last.toLowerCase(Locale.ROOT))
          || SIGN_IN.contains(last.toLowerCase(Locale.ROOT))) {
        result.add(fact);
      }
    }
    return result;
  }

  /** Whether a path is a screen or endpoint for signing up or in: one of its segments says so. */
  private static boolean account(String path) {
    for (String segment :
        PLACEHOLDER.matcher(path).replaceAll("").toLowerCase(Locale.ROOT).split("/")) {
      if (SIGN_UP.contains(segment) || SIGN_IN.contains(segment)) {
        return true;
      }
    }
    return false;
  }

  private static String path(Fact route) {
    return route.attributes().path("path").asString("");
  }

  /** Writes a person: its name and the basis with its description, citations and frontends. */
  private void write(
      String slug, Draft draft, List<Fact> routes, Origin origin, Validity validity) {
    EntityId person = new EntityId(PERSON, slug);
    // The screens of frontends that are for this person.
    List<Fact> mine = new ArrayList<>();
    for (Fact route : routes) {
      String path = path(route);
      boolean theirs =
          switch (draft.basis) {
            case INVITED -> path.toLowerCase(Locale.ROOT).contains("invit");
            case MEMBER, ACCOUNT -> account(path);
            default ->
                java.util.Arrays.stream(path.toLowerCase(Locale.ROOT).split("/"))
                    .anyMatch(s -> s.equals(slug));
          };
      if (theirs) {
        mine.add(route);
      }
    }
    // What it cites is all that it names: the evidence first (the entity before its controller),
    // then the sign-in endpoints, then the screens.
    List<Fact> evidence = new ArrayList<>(distinct(draft.facts));
    evidence.removeIf(f -> f.predicate().equals(SpringExtractor.ENDPOINT));
    evidence.sort(
        Comparator.comparingInt(
                (Fact f) -> simple(f.attributes().path("type").asString("")).length())
            .thenComparing(LinkIndex.BY_ID));
    List<Fact> endpoints =
        distinct(draft.facts).stream()
            .filter(f -> f.predicate().equals(SpringExtractor.ENDPOINT))
            .toList();
    List<Fact> facts = new ArrayList<>(evidence);
    facts.addAll(endpoints);
    facts.addAll(distinct(mine));
    facts = new ArrayList<>(facts.stream().limit(MAX_CITED).toList());
    Map<String, List<String>> screens = new TreeMap<>();
    for (Fact fact : facts) {
      if (fact.object().value().equals(PeopleExtractor.ROUTE)
          && fact.predicate().equals(PeopleExtractor.PERSON_EVIDENCE)) {
        screens.computeIfAbsent(fact.evidence().repo(), r -> new ArrayList<>()).add(path(fact));
      }
    }
    ObjectNode attributes = FactJson.newObject();
    attributes.put("basis", draft.basis);
    attributes.put("description", description(draft, facts, screens));
    ArrayNode cited = attributes.putArray("cites");
    facts.forEach(f -> cited.add(f.id()));
    ArrayNode repos = attributes.putArray("repos");
    new TreeSet<>(facts.stream().map(f -> f.evidence().repo()).toList()).forEach(repos::add);
    ArrayNode frontends = attributes.putArray("frontends");
    screens.keySet().forEach(frontends::add);
    Fact first = facts.getFirst();
    out.add(
        Fact.create(
            person,
            PERSON_BASIS,
            FactObject.string(draft.basis),
            attributes,
            origin,
            first.evidence(),
            CONFIDENCE,
            validity));
    out.add(
        Fact.create(
            person,
            DataLinker.DISPLAY_NAME,
            FactObject.string(draft.name),
            FactJson.newObject(),
            origin,
            first.evidence(),
            CONFIDENCE,
            validity));
  }

  private static List<Fact> distinct(List<Fact> facts) {
    Map<String, Fact> byId = new LinkedHashMap<>();
    facts.stream().sorted(LinkIndex.BY_ID).forEach(f -> byId.putIfAbsent(f.id(), f));
    return new ArrayList<>(byId.values());
  }

  /** One sentence in the code's words, saying only what the facts show. */
  private static String description(
      Draft draft, List<Fact> facts, Map<String, List<String>> screens) {
    StringBuilder text = new StringBuilder();
    switch (draft.basis) {
      case ROLE -> {
        Fact fact = firstOf(facts, PeopleExtractor.ROLE);
        text.append("The code checks the role ")
            .append(fact.attributes().path("name").asString())
            .append(" (")
            .append(how(fact))
            .append(").");
      }
      case MEMBER -> {
        Fact fact = firstOf(facts, PeopleExtractor.MEMBERSHIP);
        String scope = fact.attributes().path("scope").asString("");
        text.append("The code has members")
            .append(
                scope.isEmpty()
                    ? ""
                    : " of " + article(scope) + " " + scope.toLowerCase(Locale.ROOT))
            .append(": it checks membership in ")
            .append(simple(fact.attributes().path("type").asString()))
            .append(" (")
            .append(where(fact))
            .append(").");
      }
      case INVITED -> {
        Fact fact = firstOf(facts, PeopleExtractor.INVITATION);
        text.append("The code has invitations that people receive: ")
            .append(simple(fact.attributes().path("type").asString()))
            .append(" (")
            .append(where(fact))
            .append(").");
      }
      default -> text.append("The code lets people sign up or in.");
    }
    List<String> accounts =
        facts.stream()
            .filter(f -> f.predicate().equals(SpringExtractor.ENDPOINT))
            .map(PeopleLinker::endpoint)
            .distinct()
            .toList();
    if (!accounts.isEmpty() && !draft.basis.equals(INVITED)) {
      text.append(" It signs up and in at ").append(String.join(", ", accounts)).append(".");
    }
    screens.forEach(
        (repo, paths) ->
            text.append(" ")
                .append(repo)
                .append(" has the screens ")
                .append(String.join(", ", paths.stream().distinct().limit(3).toList()))
                .append("."));
    return text.toString();
  }

  private static String article(String noun) {
    return "aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an" : "a";
  }

  /** The fact of a kind whose type has the shortest name (the entity, not its controller). */
  private static Fact firstOf(List<Fact> facts, String kind) {
    return facts.stream()
        .filter(
            f ->
                f.predicate().equals(PeopleExtractor.PERSON_EVIDENCE)
                    && f.object().value().equals(kind))
        .min(
            Comparator.comparingInt(
                    (Fact f) -> simple(f.attributes().path("type").asString("")).length())
                .thenComparing(LinkIndex.BY_ID))
        .orElse(facts.getFirst());
  }

  private static String endpoint(Fact fact) {
    String method = fact.attributes().path("httpMethod").asString("");
    return (method.isEmpty() ? "" : method + " ") + fact.attributes().path("path").asString();
  }

  /** How a role is named: the annotation or call, and where. */
  private static String how(Fact role) {
    String source = role.attributes().path("source").asString("");
    if (source.equals("keycloak-realm")) {
      return "a role of the Keycloak realm, " + where(role);
    }
    return Character.isUpperCase(source.charAt(0))
        ? "@" + source + " in " + where(role)
        : "a " + source + " check in " + where(role);
  }

  private static String where(Fact fact) {
    String path = fact.evidence().path();
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String simple(String binaryName) {
    return binaryName.substring(
        Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$')) + 1);
  }

  /** A role written as ADMIN, EVENT_STAFF or superAdmin, as words: Admin, Event staff. */
  static String words(String role) {
    List<String> words = new ArrayList<>();
    for (String word : WORDS.split(role)) {
      if (!word.isEmpty()) {
        words.add(word.toLowerCase(Locale.ROOT));
      }
    }
    if (words.isEmpty()) {
      return role;
    }
    String first = words.getFirst();
    words.set(0, first.substring(0, 1).toUpperCase(Locale.ROOT) + first.substring(1));
    return String.join(" ", words);
  }

  /** The key of a person: its name in lower case with dashes between the words. */
  static String slug(String name) {
    return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
  }
}
