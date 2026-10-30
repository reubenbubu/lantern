package io.lantern.extract.identity;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.UrlCredentials;
import io.lantern.extract.java.Expression;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Assignment;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the code that signs or checks JWTs with a shared secret, and the code that builds an
 * identity provider's admin client, into facts (ADR 0085):
 *
 * <ul>
 *   <li>{@code jwt-secret-key} on a method that makes an HMAC key for JWTs from config (jjwt's
 *       {@code Keys.hmacShaKeyFor}, {@code signWith}, {@code setSigningKey} and {@code verifyWith};
 *       Nimbus's {@code MACSigner} and {@code MACVerifier}; Spring Security's {@code
 *       withSecretKey}; java-jwt's {@code Algorithm.HMAC256}), naming the config keys the secret
 *       comes from, never its value;
 *   <li>{@code identity-admin-client} on a method that builds Keycloak's admin client ({@code
 *       KeycloakBuilder.serverUrl}, {@code Keycloak.getInstance}), with the server URL as written.
 * </ul>
 *
 * A value is followed into the call from a {@code @Value} field or parameter, through calls made on
 * it ({@code secret.getBytes()}, {@code Decoders.BASE64.decode(secret)}) and one hop from a field
 * given a constructor's {@code @Value} parameter. A secret written in the code is not recorded.
 */
public final class IdentityExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "identity";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "identity-extractor";

  /** Attribute: the method makes an HMAC key for JWTs from a secret in config. */
  public static final String JWT_SECRET_KEY = "jwt-secret-key";

  /** Attribute: the method builds an identity provider's admin client. */
  public static final String IDENTITY_ADMIN_CLIENT = "identity-admin-client";

  private static final String RESOURCE = "/vocabulary/identity-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final String VALUE = "org.springframework.beans.factory.annotation.Value";

  private static final String JJWT = "io.jsonwebtoken.";
  private static final String KEYS = JJWT + "security.Keys";
  private static final String MAC_SIGNER = "com.nimbusds.jose.crypto.MACSigner";
  private static final String MAC_VERIFIER = "com.nimbusds.jose.crypto.MACVerifier";
  private static final Set<String> SPRING_DECODERS =
      Set.of(
          "org.springframework.security.oauth2.jwt.NimbusJwtDecoder",
          "org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder");
  private static final String AUTH0_ALGORITHM = "com.auth0.jwt.algorithms.Algorithm";
  private static final String AUTH0_JWT = "com.auth0.jwt.JWT";
  private static final String KEYCLOAK_BUILDER = "org.keycloak.admin.client.KeycloakBuilder";
  private static final String KEYCLOAK = "org.keycloak.admin.client.Keycloak";

  /** jjwt's builder and parser calls that take the key, whatever it is. */
  private static final Set<String> JJWT_KEY_CALLS =
      Set.of("signWith", "setSigningKey", "verifyWith", "decryptWith");

  private static final Set<String> JJWT_SIGN = Set.of("signWith", "compact");
  private static final Set<String> JJWT_VERIFY =
      Set.of("verifyWith", "setSigningKey", "parseSignedClaims", "parseClaimsJws");

  /**
   * What makes a public or private key of a key pair, not a shared secret: a value that passes
   * through these types or methods is not followed.
   */
  private static final Set<String> KEY_PAIR_TYPES =
      Set.of(
          "java.security.KeyFactory",
          "java.security.spec.X509EncodedKeySpec",
          "java.security.spec.PKCS8EncodedKeySpec",
          "java.security.spec.RSAPublicKeySpec",
          "java.security.spec.RSAPrivateKeySpec",
          "com.nimbusds.jose.jwk.RSAKey",
          "com.nimbusds.jose.jwk.ECKey",
          "com.nimbusds.jose.jwk.JWK");

  private static final Set<String> KEY_PAIR_METHODS =
      Set.of("generatePublic", "generatePrivate", "toRSAPublicKey", "toRSAPrivateKey");

  /** The field types a shared secret is held in. */
  private static final Set<String> SECRET_TYPES = Set.of("java.lang.String", "byte[]", "char[]");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)");

  /** The vocabulary types this extractor's facts use. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = IdentityExtractor.class.getResourceAsStream(RESOURCE)) {
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
   * @return the facts, without duplicates, ordered by subject, predicate and ID
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(JavaExtractor.analyze(repos), snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link JavaExtractor#analyze}.
   *
   * @param code the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate and ID
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    return new Reader(code, Validity.from(snapshot)).read();
  }

  /** A call that takes an HMAC secret: the library and how the call reads. */
  private record SecretCall(String library, String call, Expression.Invoke invoke) {}

  private static final class Reader {
    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<EntityId, Member> members = new HashMap<>();
    private final Map<EntityId, List<Assignment>> assignments = new HashMap<>();
    private final Map<String, Set<String>> usesByType = new HashMap<>();
    private final Map<String, Fact> facts = new LinkedHashMap<>();

    Reader(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
      for (JavaAnalysis.Module module : code.modules()) {
        for (TypeDecl type : module.structure().types()) {
          for (Member member : type.members()) {
            members.putIfAbsent(member.id(), member);
          }
        }
        for (Assignment a : module.structure().assignments()) {
          assignments.computeIfAbsent(a.field(), f -> new ArrayList<>()).add(a);
        }
        for (Invocation i : module.structure().invocations()) {
          Set<String> uses = uses(i);
          if (!uses.isEmpty()) {
            usesByType.computeIfAbsent(typeOf(i.caller()), t -> new TreeSet<>()).addAll(uses);
          }
        }
      }
    }

    List<Fact> read() {
      for (JavaAnalysis.Module module : code.modules()) {
        for (Invocation i : module.structure().invocations()) {
          for (SecretCall call : secretCalls(i)) {
            Set<String> keys = new TreeSet<>();
            for (Expression argument : call.invoke().arguments()) {
              keys.addAll(keys(argument, i.caller(), 0));
            }
            if (!keys.isEmpty()) {
              secret(module, i, call, keys);
            }
          }
          adminClient(module, i);
        }
      }
      return facts.values().stream()
          .sorted(
              Comparator.comparing((Fact f) -> f.subject().toString())
                  .thenComparing(Fact::predicate)
                  .thenComparing(Fact::id))
          .toList();
    }

    private void secret(
        JavaAnalysis.Module module, Invocation i, SecretCall call, Set<String> keys) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("library", call.library());
      attributes.put("call", call.call());
      ArrayNode keyArray = attributes.putArray("keys");
      keys.forEach(keyArray::add);
      Set<String> uses = usesByType.getOrDefault(typeOf(i.caller()), Set.of());
      if (!uses.isEmpty()) {
        ArrayNode useArray = attributes.putArray("uses");
        uses.forEach(useArray::add);
      }
      add(module, i, JWT_SECRET_KEY, keys.iterator().next(), attributes);
    }

    private void adminClient(JavaAnalysis.Module module, Invocation i) {
      Expression url = null;
      if (KEYCLOAK_BUILDER.equals(i.rootType())) {
        for (Expression.Invoke call : i.expression().chain()) {
          if (call.method().equals("serverUrl") && call.arguments().size() == 1) {
            url = call.arguments().getFirst();
          }
        }
      } else if (KEYCLOAK.equals(i.rootType())) {
        Expression.Invoke first = i.expression().chain().getFirst();
        if (first.method().equals("getInstance") && !first.arguments().isEmpty()) {
          url = first.arguments().getFirst();
        }
      }
      String text = url == null ? null : text(url, i.caller(), 0);
      if (text == null) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("product", "Keycloak");
      attributes.put("url", text);
      add(module, i, IDENTITY_ADMIN_CLIENT, text, attributes);
    }

    private void add(
        JavaAnalysis.Module module,
        Invocation i,
        String predicate,
        String value,
        ObjectNode attributes) {
      Location where = i.where();
      RepoBuild repo = module.repo();
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              where.file().path(),
              where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
              where.file().contentHash());
      Fact fact =
          Fact.create(
              i.caller(),
              predicate,
              FactObject.string(value),
              attributes,
              ORIGIN,
              evidence,
              module.confidence(),
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }

    /** The calls of a chain that take an HMAC secret for JWTs. */
    private static List<SecretCall> secretCalls(Invocation i) {
      String root = i.rootType();
      List<SecretCall> result = new ArrayList<>();
      if (root == null) {
        return result;
      }
      for (Expression.Invoke call : i.expression().chain()) {
        String method = call.method();
        if (root.equals(KEYS) && method.equals("hmacShaKeyFor")) {
          result.add(new SecretCall("jjwt", "Keys.hmacShaKeyFor", call));
        } else if (root.startsWith(JJWT) && JJWT_KEY_CALLS.contains(method)) {
          result.add(new SecretCall("jjwt", method, call));
        } else if ((root.equals(MAC_SIGNER) || root.equals(MAC_VERIFIER))
            && method.equals(ModuleStructure.CONSTRUCTOR)) {
          result.add(
              new SecretCall(
                  "nimbus-jose-jwt", "new " + root.substring(root.lastIndexOf('.') + 1), call));
        } else if (SPRING_DECODERS.contains(root) && method.equals("withSecretKey")) {
          result.add(
              new SecretCall(
                  "spring-security",
                  root.substring(root.lastIndexOf('.') + 1) + ".withSecretKey",
                  call));
        } else if (root.equals(AUTH0_ALGORITHM) && method.startsWith("HMAC")) {
          result.add(new SecretCall("java-jwt", "Algorithm." + method, call));
        }
      }
      return result;
    }

    /** Whether a chain signs JWTs ({@code sign}), checks them ({@code verify}), or neither. */
    private static Set<String> uses(Invocation i) {
      String root = i.rootType();
      Set<String> uses = new TreeSet<>();
      if (root == null) {
        return uses;
      }
      for (Expression.Invoke call : i.expression().chain()) {
        String method = call.method();
        if (root.startsWith(JJWT)) {
          if (JJWT_SIGN.contains(method)) {
            uses.add("sign");
          } else if (JJWT_VERIFY.contains(method)) {
            uses.add("verify");
          }
        } else if (root.equals(MAC_SIGNER)) {
          uses.add("sign");
        } else if (root.equals(MAC_VERIFIER) || SPRING_DECODERS.contains(root)) {
          uses.add("verify");
        } else if (root.equals(AUTH0_JWT)) {
          if (method.equals("create")) {
            uses.add("sign");
          } else if (method.equals("require")) {
            uses.add("verify");
          }
        }
      }
      return uses;
    }

    /**
     * The config keys a value comes from: the placeholders of the {@code @Value} on a field or
     * parameter it is read from, through calls made on it or taking it, and one hop from a field to
     * what a constructor gives it.
     */
    private Set<String> keys(Expression e, EntityId in, int depth) {
      Set<String> keys = new LinkedHashSet<>();
      switch (e) {
        case Expression.FieldRef f -> {
          Member field = members.get(fieldId(f));
          if (field != null && field.type() != null && !SECRET_TYPES.contains(field.type())) {
            return keys;
          }
          String value = field == null ? null : value(field.annotations());
          if (value != null) {
            keys.addAll(placeholders(value));
          } else if (depth < 1) {
            Assignment given = single(f);
            if (given != null) {
              keys.addAll(keys(given.value(), given.in(), depth + 1));
            }
          }
        }
        case Expression.Param p -> {
          String value = parameterValue(in, p.index());
          if (value != null) {
            keys.addAll(placeholders(value));
          }
        }
        case Expression.Invoke call -> {
          if (KEY_PAIR_METHODS.contains(call.method())
              || (call.receiver() instanceof Expression.TypeRef t
                  && KEY_PAIR_TYPES.contains(t.binaryName()))) {
            return keys;
          }
          if (call.receiver() != null) {
            keys.addAll(keys(call.receiver(), in, depth));
          }
          for (Expression argument : call.arguments()) {
            keys.addAll(keys(argument, in, depth));
          }
        }
        case Expression.Concat c -> {
          for (Expression part : c.parts()) {
            keys.addAll(keys(part, in, depth));
          }
        }
        default -> {}
      }
      return keys;
    }

    /** A URL as written: a {@code @Value}'s text with its placeholders, or a literal URL. */
    private String text(Expression e, EntityId in, int depth) {
      return switch (e) {
        case Expression.Literal l -> url(l.value());
        case Expression.FieldRef f -> {
          Member field = members.get(fieldId(f));
          String value = field == null ? null : value(field.annotations());
          if (value != null) {
            yield url(value);
          }
          Assignment given = depth < 1 ? single(f) : null;
          yield given == null ? null : text(given.value(), given.in(), depth + 1);
        }
        case Expression.Param p -> {
          String value = parameterValue(in, p.index());
          yield value == null ? null : url(value);
        }
        default -> null;
      };
    }

    /**
     * A text that is a URL or a placeholder for one, without credentials written into it; null
     * otherwise.
     */
    private static String url(String text) {
      String t = text.strip();
      if (!t.startsWith("http://") && !t.startsWith("https://") && !t.startsWith("${")) {
        return null;
      }
      return UrlCredentials.withoutCredentials(t);
    }

    private String parameterValue(EntityId method, int index) {
      Member m = members.get(method);
      if (m == null || index >= m.parameters().size()) {
        return null;
      }
      return value(m.parameters().get(index).annotations());
    }

    /** The text of a {@code @Value} annotation among those given, or null. */
    private String value(List<Annotation> annotations) {
      List<Annotation> path = code.annotationPath(annotations, VALUE);
      if (path == null || path.getLast().values("value").isEmpty()) {
        return null;
      }
      return path.getLast().values("value").getFirst();
    }

    /** The one value given to a field, if all the values given to it are the same. */
    private Assignment single(Expression.FieldRef f) {
      List<Assignment> given = assignments.get(fieldId(f));
      if (given == null || given.isEmpty()) {
        return null;
      }
      Assignment first = given.getFirst();
      return given.stream().allMatch(a -> a.value().equals(first.value())) ? first : null;
    }

    private static List<String> placeholders(String text) {
      List<String> keys = new ArrayList<>();
      Matcher m = PLACEHOLDER.matcher(text);
      while (m.find()) {
        keys.add(m.group(1).strip());
      }
      return keys;
    }

    private static EntityId fieldId(Expression.FieldRef f) {
      try {
        return new EntityId(ModuleStructure.FIELD, f.owner() + "#" + f.name());
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    private static String typeOf(EntityId code) {
      String key = code.key();
      int hash = key.indexOf('#');
      return hash < 0 ? key : key.substring(0, hash);
    }
  }
}
