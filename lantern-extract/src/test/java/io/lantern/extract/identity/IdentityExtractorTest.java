package io.lantern.extract.identity;

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
}
