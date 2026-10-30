package io.lantern.extract.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests that need no Docker: the code parsed without a build (degraded mode). */
class IdentityExtractorTest {

  private static final long SNAPSHOT = 5;

  private static final String POM =
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0"
          + "</modelVersion><groupId>com.example</groupId><artifactId>auth"
          + "</artifactId><version>1</version></project>";

  @TempDir Path temp;

  @Test
  void hmacKeysNameTheConfigKeysTheirSecretComesFromNeverItsValue() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("jwt"),
            "jwt",
            Map.of(
                "pom.xml",
                POM,
                "src/main/java/com/example/JwtService.java",
                """
                package com.example;

                import io.jsonwebtoken.Claims;
                import io.jsonwebtoken.Jwts;
                import io.jsonwebtoken.io.Decoders;
                import io.jsonwebtoken.security.Keys;
                import javax.crypto.SecretKey;
                import org.springframework.beans.factory.annotation.Value;

                public class JwtService {
                  @Value("${app.token.signing.key}")
                  private String signingKey;

                  public String token(String user) {
                    return Jwts.builder().subject(user).signWith(key()).compact();
                  }

                  public Claims claims(String token) {
                    return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token)
                        .getPayload();
                  }

                  private SecretKey key() {
                    byte[] bytes = Decoders.BASE64.decode(signingKey);
                    return Keys.hmacShaKeyFor(bytes);
                  }
                }
                """,
                "src/main/java/com/example/Verifier.java",
                """
                package com.example;

                import com.nimbusds.jose.crypto.MACVerifier;
                import org.springframework.beans.factory.annotation.Value;

                public class Verifier {
                  private final String secret;

                  public Verifier(@Value("${jwt.shared-secret}") String secret) {
                    this.secret = secret;
                  }

                  public MACVerifier verifier() throws Exception {
                    return new MACVerifier(secret.getBytes());
                  }
                }
                """,
                "src/main/java/com/example/RsaVerifier.java",
                """
                package com.example;

                import io.jsonwebtoken.Claims;
                import io.jsonwebtoken.Jwts;
                import java.security.KeyFactory;
                import java.security.spec.X509EncodedKeySpec;
                import java.util.Base64;
                import org.springframework.beans.factory.annotation.Value;

                public class RsaVerifier {
                  @Value("${jwt.public-key}")
                  private String publicKey;

                  public Claims claims(String token) throws Exception {
                    return Jwts.parser()
                        .verifyWith(
                            KeyFactory.getInstance("RSA")
                                .generatePublic(
                                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))))
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                  }
                }
                """,
                "src/main/java/com/example/HardCoded.java",
                """
                package com.example;

                import io.jsonwebtoken.security.Keys;
                import javax.crypto.SecretKey;

                public class HardCoded {
                  public SecretKey key() {
                    return Keys.hmacShaKeyFor("written-in-the-code-0123456789abcdef".getBytes());
                  }
                }
                """));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new IdentityExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        IdentityExtractor.NAME,
        IdentityExtractor.VERSION);

    assertThat(facts)
        .extracting(f -> f.subject() + " " + f.predicate() + " " + f.object().value())
        .containsExactly(
            "method:com.example.JwtService#key() jwt-secret-key app.token.signing.key",
            "method:com.example.Verifier#verifier() jwt-secret-key jwt.shared-secret");
    Fact jjwt = facts.getFirst();
    assertThat(jjwt.attributes().get("library").stringValue()).isEqualTo("jjwt");
    assertThat(jjwt.attributes().get("call").stringValue()).isEqualTo("Keys.hmacShaKeyFor");
    assertThat(jjwt.attributes().get("uses").toString()).isEqualTo("[\"sign\",\"verify\"]");
    Fact nimbus = facts.get(1);
    assertThat(nimbus.attributes().get("library").stringValue()).isEqualTo("nimbus-jose-jwt");
    assertThat(nimbus.attributes().get("uses").toString()).isEqualTo("[\"verify\"]");
    // No fact holds a secret's value.
    assertThat(facts).noneMatch(f -> f.toString().contains("written-in-the-code"));
    assertThat(new IdentityExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
  }

  @Test
  void keycloakAdminClientsRecordTheServerUrlAsWritten() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("admin"),
            "admin",
            Map.of(
                "pom.xml",
                POM,
                "src/main/java/com/example/KeycloakConfig.java",
                """
                package com.example;

                import org.keycloak.admin.client.Keycloak;
                import org.keycloak.admin.client.KeycloakBuilder;
                import org.springframework.beans.factory.annotation.Value;

                public class KeycloakConfig {
                  @Value("${keycloak.admin.server-url}")
                  private String serverUrl;

                  public Keycloak keycloak() {
                    return KeycloakBuilder.builder()
                        .serverUrl(serverUrl)
                        .realm("master")
                        .clientId("admin-cli")
                        .build();
                  }

                  public Keycloak other() {
                    return Keycloak.getInstance(
                        "https://admin:pw@sso.example.com", "master", "admin", "x", "admin-cli");
                  }
                }
                """));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new IdentityExtractor().extract(inputs, SNAPSHOT);

    assertThat(facts)
        .extracting(f -> f.subject() + " " + f.predicate() + " " + f.object().value())
        .containsExactly(
            "method:com.example.KeycloakConfig#keycloak() identity-admin-client"
                + " ${keycloak.admin.server-url}",
            "method:com.example.KeycloakConfig#other() identity-admin-client"
                + " https://sso.example.com");
    assertThat(facts).noneMatch(f -> f.toString().contains("pw@"));
  }

  @Test
  void aConfigValueUnderAKeyTheCodeUsesAsAJwtSecretIsMasked() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("masked"),
            "masked",
            Map.of(
                "pom.xml",
                POM,
                "src/main/java/com/example/JwtService.java",
                """
                package com.example;

                import io.jsonwebtoken.security.Keys;
                import javax.crypto.SecretKey;
                import org.springframework.beans.factory.annotation.Value;

                public class JwtService {
                  @Value("${jwt.key}")
                  private String key;

                  public SecretKey key() {
                    return Keys.hmacShaKeyFor(key.getBytes());
                  }
                }
                """,
                "src/main/resources/application.properties",
                "jwt.key=literal-test-value-0123456789abcdef\n"
                    + "jwt.copy=${jwt.key}\n"
                    + "routing.key=orders.created\n"));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> identity = new IdentityExtractor().extract(inputs, SNAPSHOT);
    List<Fact> config =
        new ConfigExtractor().extract(inputs, SNAPSHOT, ConfigExtractor.jwtSecretKeys(identity));

    assertThat(config)
        .extracting(f -> f.attributes().get("key").stringValue() + "=" + f.object().value())
        .containsExactlyInAnyOrder(
            "jwt.key=" + ConfigExtractor.MASK,
            "jwt.copy=" + ConfigExtractor.MASK,
            "routing.key=orders.created");
    assertThat(config).noneMatch(f -> f.toString().contains("literal-test-value"));
    // Without the code's keys, the value is recorded: the rule by key name alone is not enough.
    assertThat(new ConfigExtractor().extract(inputs, SNAPSHOT))
        .anyMatch(f -> f.toString().contains("literal-test-value"));
  }

  @Test
  void decodersSignInSetUpAndKeyPairsSayHowTokensAreChecked() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("security"),
            "security",
            Map.of(
                "pom.xml",
                POM,
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.oauth2.jwt.JwtDecoder;
                import org.springframework.security.oauth2.jwt.JwtDecoders;
                import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
                import org.springframework.security.web.SecurityFilterChain;

                public class SecurityConfig {
                  public JwtDecoder jwks(@Value("${platform.security.jwt.jwk-set-uri}") String uri) {
                    return NimbusJwtDecoder.withJwkSetUri(uri).build();
                  }

                  public JwtDecoder issuer() {
                    return JwtDecoders.fromIssuerLocation("https://sso.example.com/realms/shop");
                  }

                  public JwtDecoder fromProperties(Properties properties) {
                    return NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
                  }

                  public SecurityFilterChain chain(HttpSecurity http) throws Exception {
                    http.csrf(csrf -> csrf.disable())
                        .oauth2Login(login -> login.defaultSuccessUrl("/"))
                        .oauth2ResourceServer(server -> server.jwt(jwt -> {}));
                    http.formLogin(form -> {});
                    return http.build();
                  }

                  record Properties(String jwkSetUri) {}
                }
                """,
                "src/main/java/com/example/ReactiveConfig.java",
                """
                package com.example;

                import org.springframework.security.config.web.server.ServerHttpSecurity;
                import org.springframework.security.web.server.SecurityWebFilterChain;

                public class ReactiveConfig {
                  public SecurityWebFilterChain chain(ServerHttpSecurity http) {
                    return http.oauth2ResourceServer(server -> server.jwt(jwt -> {})).build();
                  }
                }
                """,
                "src/main/java/com/example/SessionSigner.java",
                """
                package com.example;

                import com.nimbusds.jose.crypto.RSASSASigner;
                import com.nimbusds.jose.crypto.RSASSAVerifier;
                import java.security.PrivateKey;
                import java.security.interfaces.RSAPublicKey;

                public class SessionSigner {
                  private RSASSASigner signer;
                  private RSASSAVerifier verifier;

                  void init(PrivateKey privateKey, RSAPublicKey publicKey) {
                    this.signer = new RSASSASigner(privateKey);
                    this.verifier = new RSASSAVerifier(publicKey);
                  }
                }
                """));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new IdentityExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        IdentityExtractor.NAME,
        IdentityExtractor.VERSION);

    assertThat(facts)
        .extracting(f -> f.subject() + " " + f.predicate() + " " + f.object().value())
        .containsExactlyInAnyOrder(
            "method:com.example.ReactiveConfig#chain(org.springframework.security.config.web.server"
                + ".ServerHttpSecurity) http-security oauth2-resource-server",
            "method:com.example.SecurityConfig#chain(org.springframework.security.config.annotation"
                + ".web.builders.HttpSecurity) http-security oauth2-login",
            "method:com.example.SecurityConfig#chain(org.springframework.security.config.annotation"
                + ".web.builders.HttpSecurity) http-security oauth2-resource-server",
            "method:com.example.SecurityConfig#chain(org.springframework.security.config.annotation"
                + ".web.builders.HttpSecurity) http-security form-login",
            "method:com.example.SecurityConfig#fromProperties(com.example.SecurityConfig$Properties)"
                + " jwt-decoder jwk-set-uri",
            "method:com.example.SecurityConfig#issuer() jwt-decoder issuer",
            "method:com.example.SecurityConfig#jwks(java.lang.String) jwt-decoder jwk-set-uri",
            "method:com.example.SessionSigner#init(java.security.PrivateKey,"
                + "java.security.interfaces.RSAPublicKey) jwt-key-pair RSA",
            "method:com.example.SessionSigner#init(java.security.PrivateKey,"
                + "java.security.interfaces.RSAPublicKey) jwt-key-pair RSA");
    Fact jwks =
        facts.stream()
            .filter(f -> f.subject().toString().contains("#jwks("))
            .findFirst()
            .orElseThrow();
    assertThat(jwks.attributes().get("url").stringValue())
        .isEqualTo("${platform.security.jwt.jwk-set-uri}");
    assertThat(jwks.attributes().get("keys").toString())
        .isEqualTo("[\"platform.security.jwt.jwk-set-uri\"]");
    assertThat(jwks.attributes().get("call").stringValue())
        .isEqualTo("NimbusJwtDecoder.withJwkSetUri");
    Fact issuer =
        facts.stream()
            .filter(f -> f.subject().toString().contains("#issuer("))
            .findFirst()
            .orElseThrow();
    assertThat(issuer.attributes().get("url").stringValue())
        .isEqualTo("https://sso.example.com/realms/shop");
    // A URL passed on from elsewhere is not followed, but the decoder is still recorded.
    assertThat(
            facts.stream()
                .filter(f -> f.subject().toString().contains("#fromProperties("))
                .findFirst()
                .orElseThrow()
                .attributes()
                .has("url"))
        .isFalse();
    // The signer and the verifier made in init are a fact each.
    assertThat(
            facts.stream()
                .filter(f -> f.predicate().equals(IdentityExtractor.JWT_KEY_PAIR))
                .map(f -> f.attributes().get("use").stringValue())
                .toList())
        .containsExactlyInAnyOrder("sign", "verify");
  }

  @Test
  void keycloakRealmExportsRecordTheirClientsWithoutSecrets() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("realm"),
            "realm",
            Map.of(
                "keycloak/realm-config/realm-export.json",
                """
                {
                  "realm" : "shop",
                  "clients" : [ {
                    "clientId" : "account",
                    "publicClient" : true
                  }, {
                    "clientId" : "web-client",
                    "secret" : "very-secret-value",
                    "rootUrl" : "${BFF_URL}",
                    "publicClient" : false,
                    "serviceAccountsEnabled" : true,
                    "redirectUris" : [ "http://localhost:8081/login/oauth2/code/*" ]
                  }, {
                    "clientId" : "mobile-client",
                    "publicClient" : true,
                    "redirectUris" : [ "com.example.app:/callback" ],
                    "attributes" : { "pkce.code.challenge.method" : "S256" }
                  } ]
                }
                """,
                "src/main/resources/application.json",
                "{\"clients\": []}\n"));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new IdentityExtractor().extract(inputs, SNAPSHOT);

    assertThat(facts)
        .extracting(
            f ->
                f.subject()
                    + " "
                    + f.predicate()
                    + " "
                    + f.object().value()
                    + " @"
                    + f.evidence().lines().start())
        .containsExactlyInAnyOrder(
            "repo:realm identity-client mobile-client @14",
            "repo:realm identity-client web-client @7");
    Fact mobile =
        facts.stream()
            .filter(f -> f.object().value().equals("mobile-client"))
            .findFirst()
            .orElseThrow();
    Fact web =
        facts.stream()
            .filter(f -> f.object().value().equals("web-client"))
            .findFirst()
            .orElseThrow();
    assertThat(mobile.attributes().get("realm").stringValue()).isEqualTo("shop");
    assertThat(mobile.attributes().get("public").booleanValue()).isTrue();
    assertThat(mobile.attributes().get("flows").toString()).isEqualTo("[\"authorization-code\"]");
    assertThat(mobile.attributes().get("pkce").stringValue()).isEqualTo("S256");
    assertThat(web.attributes().get("flows").toString())
        .isEqualTo("[\"authorization-code\",\"client-credentials\"]");
    assertThat(web.attributes().get("rootUrl").stringValue()).isEqualTo("${BFF_URL}");
    assertThat(web.attributes().get("enabled").booleanValue()).isTrue();
    assertThat(web.attributes().get("protocol").stringValue()).isEqualTo("openid-connect");
    assertThat(facts).noneMatch(f -> f.toString().contains("very-secret-value"));
  }

  @Test
  void legacyOAuthAnnotationsNewSignInCallsAndOlderJjwtAreRead() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("legacy"),
            "legacy",
            Map.of(
                "pom.xml",
                POM,
                "src/main/java/com/example/AuthServerConfig.java",
                """
                package com.example;

                import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;

                @EnableAuthorizationServer
                public class AuthServerConfig {}
                """,
                "src/main/java/com/example/ResourceConfig.java",
                """
                package com.example;

                import org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso;
                import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;

                @EnableResourceServer
                @EnableOAuth2Sso
                public class ResourceConfig {}
                """,
                "src/main/java/com/example/IssuerConfig.java",
                """
                package com.example;

                import org.springframework.security.config.annotation.web.builders.HttpSecurity;

                public class IssuerConfig {
                  void chain(HttpSecurity http) throws Exception {
                    http.oauth2AuthorizationServer(server -> server.oidc(oidc -> {}))
                        .oneTimeTokenLogin(ott -> {})
                        .webAuthn(passkeys -> passkeys.rpId("example.com"));
                  }
                }
                """,
                "src/main/java/com/example/TokenProvider.java",
                """
                package com.example;

                import io.jsonwebtoken.Jwts;
                import io.jsonwebtoken.SignatureAlgorithm;
                import org.springframework.beans.factory.annotation.Value;

                public class TokenProvider {
                  @Value("${jwt.secret}")
                  private String secret;

                  public String token(String user) {
                    return Jwts.builder().setSubject(user)
                        .signWith(SignatureAlgorithm.HS512, secret).compact();
                  }

                  public String user(String token) {
                    return Jwts.parser().setSigningKey(secret).parseClaimsJws(token).getBody()
                        .getSubject();
                  }
                }
                """));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new IdentityExtractor().extract(inputs, SNAPSHOT);

    assertThat(facts)
        .extracting(
            f ->
                f.subject()
                    + " "
                    + f.predicate()
                    + " "
                    + f.object().value()
                    + " "
                    + f.attributes().path("call").stringValue())
        .containsExactlyInAnyOrder(
            "type:com.example.AuthServerConfig http-security oauth2-authorization-server"
                + " @EnableAuthorizationServer",
            "type:com.example.ResourceConfig http-security oauth2-resource-server"
                + " @EnableResourceServer",
            "type:com.example.ResourceConfig http-security oauth2-login @EnableOAuth2Sso",
            "method:com.example.IssuerConfig#chain(org.springframework.security.config.annotation"
                + ".web.builders.HttpSecurity) http-security oauth2-authorization-server"
                + " oauth2AuthorizationServer",
            "method:com.example.IssuerConfig#chain(org.springframework.security.config.annotation"
                + ".web.builders.HttpSecurity) http-security one-time-token-login oneTimeTokenLogin",
            "method:com.example.IssuerConfig#chain(org.springframework.security.config.annotation"
                + ".web.builders.HttpSecurity) http-security webauthn webAuthn",
            "method:com.example.TokenProvider#token(java.lang.String) jwt-secret-key jwt.secret"
                + " signWith",
            "method:com.example.TokenProvider#user(java.lang.String) jwt-secret-key jwt.secret"
                + " setSigningKey");
    // The annotation's line is the evidence.
    assertThat(
            facts.stream()
                .filter(f -> f.subject().toString().equals("type:com.example.AuthServerConfig"))
                .findFirst()
                .orElseThrow()
                .evidence()
                .lines()
                .start())
        .isEqualTo(5);
  }

  @Test
  void everyDecoderSignInCallAndKeyPairOfTheTablesIsReadAndDisabledSignInIsNot()
      throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("patterns"),
            "patterns",
            Map.of(
                "pom.xml",
                POM,
                "src/main/java/com/example/Decoders.java",
                """
                package com.example;

                import java.security.interfaces.RSAPublicKey;
                import org.springframework.security.oauth2.jwt.JwtDecoder;
                import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
                import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
                import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
                import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;

                public class Decoders {
                  JwtDecoder publicKey(RSAPublicKey key) {
                    return NimbusJwtDecoder.withPublicKey(key).build();
                  }

                  JwtDecoder issuer() {
                    return NimbusJwtDecoder.withIssuerLocation("https://sso.example.com").build();
                  }

                  ReactiveJwtDecoder reactiveJwks() {
                    return NimbusReactiveJwtDecoder.withJwkSetUri("https://sso.example.com/jwks")
                        .build();
                  }

                  ReactiveJwtDecoder reactiveOidc() {
                    return ReactiveJwtDecoders.fromOidcIssuerLocation("https://oidc.example.com");
                  }
                }
                """,
                "src/main/java/com/example/Chains.java",
                """
                package com.example;

                import static org.springframework.security.config.Customizer.withDefaults;

                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
                import org.springframework.security.config.web.server.ServerHttpSecurity;

                public class Chains {
                  void enabled(HttpSecurity http) throws Exception {
                    http.oauth2Client(withDefaults())
                        .httpBasic(withDefaults())
                        .saml2Login(withDefaults())
                        .x509(withDefaults());
                  }

                  void disabled(HttpSecurity http) throws Exception {
                    http.httpBasic(AbstractHttpConfigurer::disable)
                        .formLogin(form -> form.disable());
                  }

                  void olderDsl(HttpSecurity http) throws Exception {
                    http.httpBasic().disable().formLogin();
                  }

                  void reactive(ServerHttpSecurity http) {
                    http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                        .formLogin(ServerHttpSecurity.FormLoginSpec::disable);
                  }
                }
                """,
                "src/main/java/com/example/ClientConfig.java",
                """
                package com.example;

                import org.springframework.security.oauth2.config.annotation.web.configuration.EnableOAuth2Client;

                @EnableOAuth2Client
                public class ClientConfig {}
                """,
                "src/main/java/com/example/EcSigner.java",
                """
                package com.example;

                import com.nimbusds.jose.crypto.ECDSASigner;
                import com.nimbusds.jose.crypto.ECDSAVerifier;
                import org.springframework.beans.factory.annotation.Value;

                public class EcSigner {
                  @Value("${session.private-key}")
                  private String privateKey;

                  @Value("${session.public-key}")
                  private String publicKey;

                  void init() throws Exception {
                    new ECDSASigner(Keys.privateKey(privateKey));
                    new ECDSAVerifier(Keys.publicKey(publicKey));
                  }
                }
                """,
                "src/main/resources/application.properties",
                "session.private-key=literal-private-key-0123456789\n"
                    + "session.public-key=literal-public-key-0123456789\n"));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new IdentityExtractor().extract(inputs, SNAPSHOT);

    assertThat(facts)
        .extracting(
            f ->
                f.subject().toString().replaceAll("\\(.*", "")
                    + " "
                    + f.predicate()
                    + " "
                    + f.object().value()
                    + " "
                    + f.attributes().path("call").stringValue()
                    + " "
                    + f.attributes().path("url").asString(""))
        .containsExactlyInAnyOrder(
            "method:com.example.Decoders#publicKey jwt-decoder public-key"
                + " NimbusJwtDecoder.withPublicKey ",
            "method:com.example.Decoders#issuer jwt-decoder issuer"
                + " NimbusJwtDecoder.withIssuerLocation https://sso.example.com",
            "method:com.example.Decoders#reactiveJwks jwt-decoder jwk-set-uri"
                + " NimbusReactiveJwtDecoder.withJwkSetUri https://sso.example.com/jwks",
            "method:com.example.Decoders#reactiveOidc jwt-decoder issuer"
                + " ReactiveJwtDecoders.fromOidcIssuerLocation https://oidc.example.com",
            "method:com.example.Chains#enabled http-security oauth2-client oauth2Client ",
            "method:com.example.Chains#enabled http-security http-basic httpBasic ",
            "method:com.example.Chains#enabled http-security saml2-login saml2Login ",
            "method:com.example.Chains#enabled http-security x509 x509 ",
            // In the older DSL, only the call that disable() follows is turned off.
            "method:com.example.Chains#olderDsl http-security form-login formLogin ",
            "type:com.example.ClientConfig http-security oauth2-client @EnableOAuth2Client ",
            "method:com.example.EcSigner#init jwt-key-pair EC new ECDSASigner ",
            "method:com.example.EcSigner#init jwt-key-pair EC new ECDSAVerifier ");
    assertThat(
            facts.stream()
                .filter(f -> f.predicate().equals(IdentityExtractor.JWT_KEY_PAIR))
                .map(
                    f -> f.attributes().get("use").stringValue() + " " + f.attributes().get("keys"))
                .toList())
        .containsExactlyInAnyOrder(
            "sign [\"session.private-key\"]", "verify [\"session.public-key\"]");
    // The config keys a key pair comes from are masked like an HMAC secret's.
    List<Fact> config =
        new ConfigExtractor().extract(inputs, SNAPSHOT, ConfigExtractor.jwtSecretKeys(facts));
    assertThat(config).isNotEmpty().noneMatch(f -> f.toString().contains("literal-"));
  }

  @Test
  void realmClientsRecordEveryFlowAndSwitchAndOnlyRealmExportsAreRead() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("flows"),
            "flows",
            Map.of(
                "realm/shop-realm.json",
                """
                {
                  "realm" : "shop",
                  "clients" : [ {
                    "clientId" : "legacy-spa",
                    "publicClient" : true,
                    "standardFlowEnabled" : false,
                    "implicitFlowEnabled" : true,
                    "directAccessGrantsEnabled" : true,
                    "baseUrl" : "/app/",
                    "redirectUris" : [ "https://app:pw@web.example.com/*" ],
                    "webOrigins" : [ "https://user:pw@web.example.com", "+" ]
                  }, {
                    "clientId" : "api",
                    "bearerOnly" : true,
                    "enabled" : false,
                    "protocol" : "saml"
                  } ]
                }
                """,
                "config/client-realm-list.json",
                "{\"clients\": [ { \"clientId\": \"not-a-realm\" } ]}\n"));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new IdentityExtractor().extract(inputs, SNAPSHOT);

    assertThat(facts)
        .extracting(f -> f.object().value())
        .containsExactlyInAnyOrder("legacy-spa", "api");
    Fact spa =
        facts.stream()
            .filter(f -> f.object().value().equals("legacy-spa"))
            .findFirst()
            .orElseThrow();
    Fact api =
        facts.stream().filter(f -> f.object().value().equals("api")).findFirst().orElseThrow();
    assertThat(spa.attributes().get("flows").toString()).isEqualTo("[\"implicit\",\"password\"]");
    assertThat(spa.attributes().get("baseUrl").stringValue()).isEqualTo("/app/");
    assertThat(spa.attributes().get("redirectUris").toString())
        .isEqualTo("[\"https://web.example.com/*\"]");
    assertThat(spa.attributes().get("webOrigins").toString())
        .isEqualTo("[\"https://web.example.com\",\"+\"]");
    assertThat(spa.attributes().has("bearerOnly")).isFalse();
    assertThat(api.attributes().get("bearerOnly").booleanValue()).isTrue();
    assertThat(api.attributes().get("enabled").booleanValue()).isFalse();
    assertThat(api.attributes().get("protocol").stringValue()).isEqualTo("saml");
    assertThat(facts).noneMatch(f -> f.toString().contains(":pw@"));
  }
}
