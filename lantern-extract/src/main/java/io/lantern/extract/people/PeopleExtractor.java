package io.lantern.extract.people;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Call;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records what the code shows about the people who use a system, for the linker to make people of
 * (ADR 0074): the roles that Spring Security, {@code @Secured}, {@code @RolesAllowed} and a
 * Keycloak realm name; types about members of something ({@code OrganizationMembership}) and about
 * invitations; and the screens of a frontend, read from its router. Nothing is invented: every fact
 * names the line it comes from, and a name is the code's own word.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}), with the
 * same confidence, and the other files from git at the commit.
 */
public final class PeopleExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "people";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "people-extractor";

  /** Attribute: the subject shows people use the system; its value is the kind of evidence. */
  public static final String PERSON_EVIDENCE = "person-evidence";

  /** The kinds of evidence, as the value of {@link #PERSON_EVIDENCE}. */
  public static final String ROLE = "role";

  public static final String MEMBERSHIP = "membership";
  public static final String INVITATION = "invitation";
  public static final String ROUTE = "route";

  private static final String RESOURCE = "/vocabulary/people-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final Set<String> PREAUTHORIZE =
      Set.of(
          "org.springframework.security.access.prepost.PreAuthorize",
          "org.springframework.security.access.prepost.PostAuthorize");
  private static final String SECURED = "org.springframework.security.access.annotation.Secured";
  private static final Set<String> ROLES_ALLOWED =
      Set.of("jakarta.annotation.security.RolesAllowed", "javax.annotation.security.RolesAllowed");
  private static final Set<String> ROLE_CALLS =
      Set.of("hasRole", "hasAnyRole", "hasAuthority", "hasAnyAuthority");

  /** Roles in a method security expression: {@code hasRole('ADMIN')}, {@code hasAnyRole(...)}. */
  private static final Pattern EXPRESSION =
      Pattern.compile(
          "has(Any)?(Role|Authority)\\(\\s*((?:'[^']*'|\"[^\"]*\")(?:\\s*,\\s*(?:'[^']*'|\"[^\"]*\"))*)\\s*\\)");

  private static final Pattern QUOTED = Pattern.compile("'([^']*)'|\"([^\"]*)\"");

  /** Annotations (by simple name) that make a type a thing the system keeps or runs. */
  private static final Set<String> CONCEPT_ANNOTATIONS =
      Set.of(
          "Entity",
          "Table",
          "Document",
          "Component",
          "Service",
          "Aspect",
          "Controller",
          "RestController",
          "Repository",
          "Configuration");

  /** Words before Member in a type's name that say what the type does, not whose member it is. */
  private static final Set<String> NOT_A_SCOPE =
      Set.of(
          "Requires",
          "Require",
          "Required",
          "Has",
          "Is",
          "Check",
          "Verify",
          "Ensure",
          "Enable",
          "Validate",
          "Abstract",
          "Base",
          "Default");

  /** Words that may follow Member in a type's name: what the type does about members. */
  private static final Set<String> MEMBER_ROLES =
      Set.of(
          "Control",
          "Controller",
          "Check",
          "Checker",
          "Service",
          "Repository",
          "Aspect",
          "Filter",
          "Validator",
          "Manager",
          "Entity",
          "Facade",
          "Mapper",
          "Processor",
          "Interceptor");

  private static final Pattern WORDS =
      Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

  private static final Pattern SOURCE_FILE = Pattern.compile("(?i).+\\.(ts|tsx|js|jsx|mjs|vue)");
  private static final Pattern ROUTER_IMPORT =
      Pattern.compile(
          "from\\s+['\"](@angular/router|react-router(?:-dom)?|vue-router)['\"]"
              + "|require\\(\\s*['\"](react-router(?:-dom)?|vue-router)['\"]");
  private static final Pattern ROUTE_PATH =
      Pattern.compile(
          "\\bpath\\s*:\\s*(['\"`])([^'\"`]*)\\1|<Route\\b[^>]*?\\bpath\\s*=\\s*\\{?\\s*(['\"`])([^'\"`]*)\\3");

  /** Realm export files of Keycloak. */
  private static final Pattern REALM_FILE = Pattern.compile("(?i)(.*/)?[^/]*realm[^/]*\\.json");

  /** Roles every Keycloak realm has and that name no kind of person. */
  private static final Set<String> BUILT_IN_REALM_ROLES =
      Set.of("offline_access", "uma_authorization");

  private static final long MAX_BYTES = 2L * 1024 * 1024;
  private static final int MAX_ROUTES_PER_FILE = 100;

  /** The vocabulary types this extractor's facts use. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = PeopleExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Extracts the facts of a snapshot's repositories, reading their code first.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, ordered by subject, predicate, object, evidence, attributes and ID
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(io.lantern.extract.java.JavaExtractor.analyze(repos), repos, snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read, and of the other files of all the given
   * repositories, those without code too: a frontend's router and a Keycloak realm file.
   *
   * @param code the code of the snapshot's repositories
   * @param repos the repositories of the snapshot; those of the code are read whether listed or not
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, evidence,
   *     attributes and ID
   * @throws IllegalStateException if git fails or times out while reading files
   */
  public List<Fact> extract(JavaAnalysis code, List<RepoBuild> repos, long snapshot) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(repos, "repos");
    Run run = new Run(Validity.from(snapshot));
    Set<RepoBuild> all = new LinkedHashSet<>(repos);
    for (JavaAnalysis.Module module : code.modules()) {
      all.add(module.repo());
      run.code(module);
    }
    for (RepoBuild repo : all) {
      run.files(repo);
    }
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.evidence().path())
                .thenComparing(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /** The words of a name written in camel case. */
  static List<String> words(String name) {
    return List.of(WORDS.split(name));
  }

  /**
   * The scope of a type named about members: the word before Member or Membership, as in
   * Organization for {@code RequiresOrganizationMembership}; the empty string for a name without
   * one ({@code Member}). Null if the name is not about members: Member or Membership must end the
   * name or be followed only by what a type does about members ({@code MembershipControlAspect}),
   * so {@code MemberSelector} is not about members.
   */
  static String memberScope(String simpleName) {
    List<String> words = words(simpleName);
    for (int i = 0; i < words.size(); i++) {
      if (words.get(i).equals("Member") || words.get(i).equals("Membership")) {
        if (!MEMBER_ROLES.containsAll(words.subList(i + 1, words.size()))) {
          return null;
        }
        return i > 0 && !NOT_A_SCOPE.contains(words.get(i - 1)) ? words.get(i - 1) : "";
      }
    }
    return null;
  }

  /** The roles a method security expression names, as written. */
  static List<String> expressionRoles(String expression) {
    List<String> roles = new ArrayList<>();
    Matcher call = EXPRESSION.matcher(expression);
    while (call.find()) {
      boolean authority = call.group(2).equals("Authority");
      Matcher quoted = QUOTED.matcher(call.group(3));
      while (quoted.find()) {
        String name = quoted.group(1) != null ? quoted.group(1) : quoted.group(2);
        role(name, authority, roles);
      }
    }
    return roles;
  }

  /** The name of the method a callee ID names if it is one of Spring Security's role checks. */
  static String securityMethod(String calleeKey) {
    int hash = calleeKey.indexOf('#');
    int paren = calleeKey.indexOf('(');
    if (hash < 0 || paren < hash) {
      return null;
    }
    String method = calleeKey.substring(hash + 1, paren);
    return ROLE_CALLS.contains(method)
            && calleeKey.substring(0, hash).contains("springframework.security")
        ? method
        : null;
  }

  /** The roles a role check names in the arguments written out in the call. */
  static List<String> callRoles(String method, List<String> arguments) {
    List<String> roles = new ArrayList<>();
    for (String argument : arguments) {
      if (argument != null) {
        role(argument, method.endsWith("Authority") || method.endsWith("Authorities"), roles);
      }
    }
    return roles;
  }

  /** Adds a role name: a role as it is, an authority only if it is a ROLE_ one. */
  private static void role(String name, boolean authority, List<String> roles) {
    boolean prefixed = name.startsWith("ROLE_");
    if (authority && !prefixed) {
      return;
    }
    String role = prefixed ? name.substring("ROLE_".length()) : name;
    if (!role.isBlank() && role.length() <= 60 && !roles.contains(role)) {
      roles.add(role);
    }
  }

  private static String simpleName(String binaryName) {
    int cut = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
    return binaryName.substring(cut + 1);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static EntityId typeId(String binaryName) {
    try {
      return new EntityId("type", binaryName);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** One extraction: the facts so far. */
  private static final class Run {

    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();

    Run(Validity validity) {
      this.validity = validity;
    }

    // -------------------------------------------------------------------------------------------
    // Code

    void code(JavaAnalysis.Module m) {
      for (TypeDecl type : m.structure().types()) {
        if (type.where() == null || type.where().file().path().contains("src/test/")) {
          continue;
        }
        EntityId typeId = typeId(type.binaryName());
        if (typeId != null) {
          for (Annotation a : type.annotations()) {
            roles(m, typeId, a, type.where());
          }
          concept(m, type, typeId);
        }
        for (Member member : type.members()) {
          for (Annotation a : member.annotations()) {
            roles(m, member.id(), a, member.where());
          }
        }
      }
      for (Call call : m.structure().calls()) {
        securityCall(m, call);
      }
    }

    /** The roles an annotation names. */
    private void roles(JavaAnalysis.Module m, EntityId subject, Annotation a, Location at) {
      List<String> roles = new ArrayList<>();
      String source = simpleName(a.type());
      if (PREAUTHORIZE.contains(a.type())) {
        a.values("value").forEach(v -> roles.addAll(expressionRoles(v)));
      } else if (a.type().equals(SECURED) || ROLES_ALLOWED.contains(a.type())) {
        for (String value : a.values("value")) {
          role(value, a.type().equals(SECURED), roles);
        }
      } else {
        return;
      }
      Location where = a.where() != null ? a.where() : at;
      for (String role : roles) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("name", role);
        attributes.put("source", source);
        code(m, subject, ROLE, attributes, where);
      }
    }

    /** A call of Spring Security's {@code hasRole} and its like with the role written out. */
    private void securityCall(JavaAnalysis.Module m, Call call) {
      if (call.where() == null || call.where().file().path().contains("src/test/")) {
        return;
      }
      String key = call.callee().key();
      String method = securityMethod(key);
      if (method == null) {
        return;
      }
      for (String role : callRoles(method, call.arguments())) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("name", role);
        attributes.put("source", method);
        code(m, call.caller(), ROLE, attributes, call.where());
      }
    }

    /**
     * A type that is about members or invitations and is a thing the system keeps or runs: an
     * annotation, an entity, a controller or another bean.
     */
    private void concept(JavaAnalysis.Module m, TypeDecl type, EntityId subject) {
      String name = simpleName(type.binaryName());
      boolean kept =
          type.kind().equals(ModuleStructure.ANNOTATION)
              || type.annotations().stream()
                  .anyMatch(a -> CONCEPT_ANNOTATIONS.contains(simpleName(a.type())));
      if (!kept || type.kind().equals(ModuleStructure.ENUM)) {
        return;
      }
      String scope = memberScope(name);
      if (scope != null) {
        ObjectNode attributes = FactJson.newObject();
        if (!scope.isEmpty()) {
          attributes.put("scope", scope);
        }
        attributes.put("type", type.binaryName());
        code(m, subject, MEMBERSHIP, attributes, type.where());
      }
      if (words(name).contains("Invitation")) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("type", type.binaryName());
        code(m, subject, INVITATION, attributes, type.where());
      }
    }

    private void code(
        JavaAnalysis.Module m, EntityId subject, String kind, ObjectNode attributes, Location at) {
      RepoBuild repo = m.repo();
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              at.file().path(),
              at.start() > 0 ? new Evidence.LineRange(at.start(), at.end()) : null,
              at.file().contentHash());
      add(subject, kind, attributes, evidence, m.confidence());
    }

    // -------------------------------------------------------------------------------------------
    // Files

    /** The Keycloak realm files and the routers of frontends in the repository. */
    void files(RepoBuild repo) {
      EntityId subject = new EntityId("repo", repo.repo().name());
      for (String path : GitFiles.list(repo, "")) {
        if (path.contains("node_modules/")
            || path.contains("/dist/")
            || path.contains("src/test/")
            || path.matches("(?i).*\\.(spec|test)\\.[a-z]+")) {
          continue;
        }
        if (REALM_FILE.matcher(path).matches()) {
          realm(repo, subject, path);
        } else if (SOURCE_FILE.matcher(path).matches()) {
          routes(repo, subject, path);
        }
      }
    }

    private void realm(RepoBuild repo, EntityId subject, String path) {
      byte[] bytes = GitFiles.read(repo, path, MAX_BYTES);
      if (bytes == null) {
        return;
      }
      String text = new String(bytes, StandardCharsets.UTF_8);
      JsonNode roles;
      try {
        roles = FactJson.parse(text).path("roles").path("realm");
      } catch (RuntimeException e) {
        return;
      }
      String[] lines = text.split("\n", -1);
      // The realm roles are listed under roles.realm, an array; a client role of the same name
      // is elsewhere in the file, so the search starts at that array.
      int from = 0;
      for (int n = 0; n < lines.length && from == 0; n++) {
        if (lines[n].matches("\\s*\"realm\"\\s*:\\s*\\[.*")) {
          from = n;
        }
      }
      Set<String> serviceHolders = new LinkedHashSet<>();
      Set<String> otherHolders = new LinkedHashSet<>();
      FactJson.parse(text)
          .path("users")
          .forEach(
              user -> {
                boolean service = user.has("serviceAccountClientId");
                user.path("realmRoles")
                    .forEach(r -> (service ? serviceHolders : otherHolders).add(r.asString("")));
              });
      FactJson.parse(text)
          .path("groups")
          .forEach(
              group -> group.path("realmRoles").forEach(r -> otherHolders.add(r.asString(""))));
      for (JsonNode role : roles) {
        String name = role.path("name").asString("");
        if (name.isBlank()
            || BUILT_IN_REALM_ROLES.contains(name)
            || name.startsWith("default-roles-")) {
          continue;
        }
        int at = 0;
        for (int n = from; n < lines.length && at == 0; n++) {
          if (lines[n].contains("\"" + name + "\"")) {
            at = n + 1;
          }
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("name", name);
        attributes.put("source", "keycloak-realm");
        if (serviceHolders.contains(name) && !otherHolders.contains(name)) {
          attributes.put("serviceAccountOnly", true);
        }
        add(
            subject,
            ROLE,
            attributes,
            new Evidence(
                repo.repo().name(),
                repo.commit(),
                path,
                at > 0 ? new Evidence.LineRange(at, at) : null,
                sha256(bytes)),
            1.0);
      }
    }

    /** The paths of a file that imports a router: Angular's, React's or Vue's. */
    private void routes(RepoBuild repo, EntityId subject, String path) {
      byte[] bytes = GitFiles.read(repo, path, MAX_BYTES);
      if (bytes == null) {
        return;
      }
      String text = new String(bytes, StandardCharsets.UTF_8);
      Matcher router = ROUTER_IMPORT.matcher(text);
      if (!router.find()) {
        return;
      }
      String library = router.group(1) != null ? router.group(1) : router.group(2);
      String framework =
          library.startsWith("@angular")
              ? "angular"
              : library.startsWith("react") ? "react" : "vue";
      String hash = sha256(bytes);
      String[] lines = text.split("\n", -1);
      int found = 0;
      for (int n = 0; n < lines.length && found < MAX_ROUTES_PER_FILE; n++) {
        Matcher route = ROUTE_PATH.matcher(lines[n]);
        while (route.find()) {
          String routePath = route.group(2) != null ? route.group(2) : route.group(4);
          if (routePath.contains("${") || routePath.equals("*") || routePath.equals("**")) {
            continue;
          }
          ObjectNode attributes = FactJson.newObject();
          attributes.put("path", "/" + routePath.replaceFirst("^/+", ""));
          attributes.put("framework", framework);
          add(
              subject,
              ROUTE,
              attributes,
              new Evidence(
                  repo.repo().name(),
                  repo.commit(),
                  path,
                  new Evidence.LineRange(n + 1, n + 1),
                  hash),
              0.8);
          found++;
        }
      }
    }

    private void add(
        EntityId subject,
        String kind,
        ObjectNode attributes,
        Evidence evidence,
        double confidence) {
      Fact fact =
          Fact.create(
              subject,
              PERSON_EVIDENCE,
              FactObject.string(kind),
              attributes,
              ORIGIN,
              evidence,
              confidence,
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }
}
