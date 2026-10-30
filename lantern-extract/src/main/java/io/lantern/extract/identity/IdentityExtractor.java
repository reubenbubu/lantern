package io.lantern.extract.identity;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.GitFiles;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
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
 *       KeycloakBuilder.serverUrl}, {@code Keycloak.getInstance}), with the server URL as written;
 *   <li>{@code jwt-decoder} on a method that builds Spring Security's JWT decoder from a JWK set
 *       URI, an issuer or a public key, with the URL as written when it can be followed;
 *   <li>{@code http-security} on a method that sets up how callers sign in or show a token with
 *       Spring Security's DSL ({@code oauth2Login}, {@code oauth2ResourceServer}, {@code
 *       oauth2Client}, {@code oauth2AuthorizationServer}, {@code formLogin}, {@code httpBasic},
 *       {@code saml2Login}, {@code x509}, {@code oneTimeTokenLogin}, {@code webAuthn}), or on a
 *       type annotated with legacy Spring Security OAuth's {@code @EnableAuthorizationServer},
 *       {@code @EnableResourceServer}, {@code @EnableOAuth2Sso} or {@code @EnableOAuth2Client};
 *   <li>{@code jwt-key-pair} on a method that signs or checks JWTs with an RSA or EC key (Nimbus's
 *       {@code RSASSASigner}, {@code RSASSAVerifier}, {@code ECDSASigner}, {@code ECDSAVerifier});
 *   <li>{@code identity-client} on a repository for each client a Keycloak realm export registers,
 *       with its flows and redirect URIs, never its secret.
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
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "identity-extractor";

  /** Attribute: the method makes an HMAC key for JWTs from a secret in config. */
  public static final String JWT_SECRET_KEY = "jwt-secret-key";

  /** Attribute: the method builds an identity provider's admin client. */
  public static final String IDENTITY_ADMIN_CLIENT = "identity-admin-client";

  /** Attribute: the method builds a JWT decoder that checks tokens with a provider's keys. */
  public static final String JWT_DECODER = "jwt-decoder";

  /** Attribute: the method sets up how callers sign in or show a token. */
  public static final String HTTP_SECURITY = "http-security";

  /** Attribute: the method signs or checks JWTs with an RSA or EC key. */
  public static final String JWT_KEY_PAIR = "jwt-key-pair";

  /** Attribute: a client a Keycloak realm export in the repository registers. */
  public static final String IDENTITY_CLIENT = "identity-client";

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
  private static final Set<String> DECODER_FACTORIES =
      Set.of(
          "org.springframework.security.oauth2.jwt.JwtDecoders",
          "org.springframework.security.oauth2.jwt.ReactiveJwtDecoders");

  /** How a decoder gets its keys, by the call that builds it. */
  private static final Map<String, String> DECODER_CALLS =
      Map.of(
          "withJwkSetUri", "jwk-set-uri",
          "withIssuerLocation", "issuer",
          "fromIssuerLocation", "issuer",
          "fromOidcIssuerLocation", "issuer",
          "withPublicKey", "public-key");

  /** Spring Security's configuration DSLs, servlet and reactive. */
  private static final Map<String, String> SECURITY_DSLS =
      Map.of(
          "org.springframework.security.config.annotation.web.builders.HttpSecurity",
          "HttpSecurity",
          "org.springframework.security.config.web.server.ServerHttpSecurity",
          "ServerHttpSecurity");

  /** The DSL calls that say how callers sign in or show a token, and what the fact calls them. */
  private static final Map<String, String> SIGN_IN_CALLS =
      Map.of(
          "oauth2Login", "oauth2-login",
          "oauth2ResourceServer", "oauth2-resource-server",
          "oauth2Client", "oauth2-client",
          "formLogin", "form-login",
          "httpBasic", "http-basic",
          "saml2Login", "saml2-login",
          "x509", "x509",
          "oauth2AuthorizationServer", "oauth2-authorization-server",
          "oneTimeTokenLogin", "one-time-token-login",
          "webAuthn", "webauthn");

  /**
   * Legacy Spring Security OAuth's annotations (end of life, still common in older systems), and
   * what the fact calls each.
   */
  private static final Map<String, String> LEGACY_OAUTH =
      Map.of(
          "org.springframework.security.oauth2.config.annotation.web.configuration"
              + ".EnableAuthorizationServer",
          "oauth2-authorization-server",
          "org.springframework.security.oauth2.config.annotation.web.configuration"
              + ".EnableResourceServer",
          "oauth2-resource-server",
          "org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso",
          "oauth2-login",
          "org.springframework.security.oauth2.config.annotation.web.configuration"
              + ".EnableOAuth2Client",
          "oauth2-client");

  /** Nimbus's signers and verifiers of a key pair, and the kind of key each takes. */
  private static final Map<String, String> KEY_PAIR_CRYPTO =
      Map.of(
          "com.nimbusds.jose.crypto.RSASSASigner", "RSA",
          "com.nimbusds.jose.crypto.RSASSAVerifier", "RSA",
          "com.nimbusds.jose.crypto.ECDSASigner", "EC",
          "com.nimbusds.jose.crypto.ECDSAVerifier", "EC");

  /** Keycloak realm export files. */
  private static final Pattern REALM_FILE = Pattern.compile("(?i)(.*/)?[^/]*realm[^/]*\\.json");

  /** The clients every Keycloak realm has, which name no application. */
  private static final Set<String> BUILT_IN_CLIENTS =
      Set.of(
          "account",
          "account-console",
          "admin-cli",
          "broker",
          "realm-management",
          "security-admin-console");

  private static final long MAX_BYTES = 2L * 1024 * 1024;

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
    return extract(JavaExtractor.analyze(repos), repos, snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link JavaExtractor#analyze} and of
   * the Keycloak realm exports in its repositories, those without code too.
   *
   * @param code the code of the snapshot's repositories
   * @param repos the repositories of the snapshot, each at its commit
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate and ID
   */
  public List<Fact> extract(JavaAnalysis code, List<RepoBuild> repos, long snapshot) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(repos, "repos");
    Reader reader = new Reader(code, Validity.from(snapshot));
    repos.forEach(reader::realms);
    return reader.read();
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
        for (TypeDecl type : module.structure().types()) {
          legacyOAuth(module, type);
        }
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
          decoder(module, i);
          httpSecurity(module, i);
          keyPair(module, i);
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

    private void decoder(JavaAnalysis.Module module, Invocation i) {
      String root = i.rootType();
      if (!SPRING_DECODERS.contains(root) && !DECODER_FACTORIES.contains(root)) {
        return;
      }
      for (Expression.Invoke call : i.expression().chain()) {
        String kind = DECODER_CALLS.get(call.method());
        if (kind == null) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("library", "spring-security");
        attributes.put("call", root.substring(root.lastIndexOf('.') + 1) + "." + call.method());
        if (!kind.equals("public-key") && call.arguments().size() == 1) {
          Expression argument = call.arguments().getFirst();
          String url = text(argument, i.caller(), 0);
          if (url != null) {
            attributes.put("url", url);
          }
          Set<String> keys = keys(argument, i.caller(), 0);
          if (!keys.isEmpty()) {
            ArrayNode keyArray = attributes.putArray("keys");
            keys.forEach(keyArray::add);
          }
        }
        add(module, i, JWT_DECODER, kind, attributes);
      }
    }

    private void httpSecurity(JavaAnalysis.Module module, Invocation i) {
      String dsl = SECURITY_DSLS.get(i.rootType());
      if (dsl == null) {
        return;
      }
      for (Expression.Invoke call : i.expression().chain()) {
        String feature = SIGN_IN_CALLS.get(call.method());
        if (feature != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("via", dsl);
          attributes.put("call", call.method());
          add(module, i, HTTP_SECURITY, feature, attributes);
        }
      }
    }

    private void legacyOAuth(JavaAnalysis.Module module, TypeDecl type) {
      for (Map.Entry<String, String> legacy : LEGACY_OAUTH.entrySet()) {
        List<Annotation> path = code.annotationPath(type.annotations(), legacy.getKey());
        if (path == null) {
          continue;
        }
        String name = legacy.getKey().substring(legacy.getKey().lastIndexOf('.') + 1);
        ObjectNode attributes = FactJson.newObject();
        attributes.put("via", "annotation");
        attributes.put("call", "@" + name);
        add(
            module,
            new EntityId(BuiltInTypes.TYPE, type.binaryName()),
            path.getFirst().where(),
            HTTP_SECURITY,
            legacy.getValue(),
            attributes);
      }
    }

    private void keyPair(JavaAnalysis.Module module, Invocation i) {
      String kind = KEY_PAIR_CRYPTO.get(i.rootType());
      if (kind == null) {
        return;
      }
      for (Expression.Invoke call : i.expression().chain()) {
        if (!call.method().equals(ModuleStructure.CONSTRUCTOR)) {
          continue;
        }
        String root = i.rootType();
        ObjectNode attributes = FactJson.newObject();
        attributes.put("library", "nimbus-jose-jwt");
        attributes.put("call", "new " + root.substring(root.lastIndexOf('.') + 1));
        attributes.put("use", root.endsWith("Signer") ? "sign" : "verify");
        Set<String> keys = new TreeSet<>();
        for (Expression argument : call.arguments()) {
          keys.addAll(keys(argument, i.caller(), 0));
        }
        if (!keys.isEmpty()) {
          ArrayNode keyArray = attributes.putArray("keys");
          keys.forEach(keyArray::add);
        }
        add(module, i, JWT_KEY_PAIR, kind, attributes);
      }
    }

    /** The clients of the Keycloak realm exports in a repository. */
    void realms(RepoBuild repo) {
      for (String path : GitFiles.list(repo, "")) {
        if (!path.contains("node_modules/")
            && !path.contains("src/test/")
            && REALM_FILE.matcher(path).matches()) {
          realm(repo, path);
        }
      }
    }

    private void realm(RepoBuild repo, String path) {
      byte[] bytes = GitFiles.read(repo, path, MAX_BYTES);
      if (bytes == null) {
        return;
      }
      String text = new String(bytes, StandardCharsets.UTF_8);
      JsonNode realm;
      try {
        realm = FactJson.parse(text);
      } catch (RuntimeException e) {
        return;
      }
      if (!realm.isObject() || !realm.path("clients").isArray()) {
        return;
      }
      String[] lines = text.split("\n", -1);
      EntityId subject = new EntityId("repo", repo.repo().name());
      for (JsonNode client : realm.path("clients")) {
        String id = client.path("clientId").asString("");
        if (id.isBlank() || BUILT_IN_CLIENTS.contains(id)) {
          continue;
        }
        int at = 0;
        Pattern clientId = Pattern.compile("\"clientId\"\\s*:\\s*\"" + Pattern.quote(id) + "\"");
        for (int n = 0; n < lines.length && at == 0; n++) {
          if (clientId.matcher(lines[n]).find()) {
            at = n + 1;
          }
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("product", "Keycloak");
        if (realm.path("realm").isString()) {
          attributes.put("realm", realm.path("realm").asString());
        }
        attributes.put("public", client.path("publicClient").asBoolean(false));
        attributes.put("enabled", client.path("enabled").asBoolean(true));
        attributes.put("protocol", client.path("protocol").asString("openid-connect"));
        if (client.path("bearerOnly").asBoolean(false)) {
          attributes.put("bearerOnly", true);
        }
        for (String url : List.of("rootUrl", "baseUrl")) {
          String value = UrlCredentials.withoutCredentials(client.path(url).asString(""));
          if (!value.isBlank()) {
            attributes.put(url, value);
          }
        }
        ArrayNode flows = attributes.putArray("flows");
        // Keycloak enables the authorization code flow unless the export turns it off.
        if (client.path("standardFlowEnabled").asBoolean(true)) {
          flows.add("authorization-code");
        }
        if (client.path("implicitFlowEnabled").asBoolean(false)) {
          flows.add("implicit");
        }
        if (client.path("directAccessGrantsEnabled").asBoolean(false)) {
          flows.add("password");
        }
        if (client.path("serviceAccountsEnabled").asBoolean(false)) {
          flows.add("client-credentials");
        }
        String pkce = client.path("attributes").path("pkce.code.challenge.method").asString("");
        if (!pkce.isBlank()) {
          attributes.put("pkce", pkce);
        }
        ArrayNode redirects = attributes.putArray("redirectUris");
        client
            .path("redirectUris")
            .forEach(
                uri -> {
                  String withoutCredentials = UrlCredentials.withoutCredentials(uri.asString(""));
                  if (!withoutCredentials.isBlank()) {
                    redirects.add(withoutCredentials);
                  }
                });
        ArrayNode origins = attributes.putArray("webOrigins");
        client.path("webOrigins").forEach(o -> origins.add(o.asString("")));
        Evidence evidence =
            new Evidence(
                repo.repo().name(),
                repo.commit(),
                path,
                at > 0 ? new Evidence.LineRange(at, at) : null,
                sha256(bytes));
        Fact fact =
            Fact.create(
                subject,
                IDENTITY_CLIENT,
                FactObject.string(id),
                attributes,
                ORIGIN,
                evidence,
                1.0,
                validity);
        facts.putIfAbsent(fact.id(), fact);
      }
    }

    private static String sha256(byte[] bytes) {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }

    private void add(
        JavaAnalysis.Module module,
        Invocation i,
        String predicate,
        String value,
        ObjectNode attributes) {
      add(module, i.caller(), i.where(), predicate, value, attributes);
    }

    private void add(
        JavaAnalysis.Module module,
        EntityId subject,
        Location where,
        String predicate,
        String value,
        ObjectNode attributes) {
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
              subject,
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
        } else if (root.equals(MAC_SIGNER)
            || (KEY_PAIR_CRYPTO.containsKey(root) && root.endsWith("Signer"))) {
          uses.add("sign");
        } else if (root.equals(MAC_VERIFIER)
            || SPRING_DECODERS.contains(root)
            || KEY_PAIR_CRYPTO.containsKey(root)) {
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
