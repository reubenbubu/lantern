package io.lantern.extract.deployment;

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
 * The {@code deployment} fixture: a compose file with a committed {@code .env} and a second compose
 * file, a multi-stage Dockerfile, a kustomize base with a production overlay, a Helm chart with a
 * staging values file and a plain manifest. Needs no Docker.
 */
class DeploymentExtractorTest {

  private static final long SNAPSHOT = 3;

  @TempDir Path temp;

  @Test
  void fixture() throws IOException {
    Repo repo = Fixtures.repo("deployment", temp.resolve("deployment"), "deployment");
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));

    List<Fact> facts = new DeploymentExtractor().extract(inputs, SNAPSHOT);

    Fixtures.checkEnvelope(
        facts, List.of(repo), SNAPSHOT, DeploymentExtractor.NAME, DeploymentExtractor.VERSION);
    assertThat(new DeploymentExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    Fixtures.assertGolden("deployment/deployment.txt", facts);
    // Secrets never reach a fact: not from the compose file, its .env or a URL.
    assertThat(Fixtures.render(facts)).doesNotContain("secret\"", "hunter2", "user:pw", "\"9\"");
  }

  @Test
  void imagesNameTheirProductAndVersion() {
    assertThat(Image.of("redis:8.4.0-alpine").technology()).isEqualTo("Redis 8.4.0");
    assertThat(Image.of("docker.io/bitnami/redis:7").technology()).isEqualTo("Redis 7");
    assertThat(Image.of("valkey/valkey:7.2").technology()).isEqualTo("Valkey 7.2");
    assertThat(Image.of("redis:alpine").technology()).isEqualTo("Redis");
    assertThat(Image.of("postgres:17.5-alpine3.22").technology()).isEqualTo("PostgreSQL 17.5");
    assertThat(Image.of("rabbitmq:3-management").technology()).isEqualTo("RabbitMQ 3");
    assertThat(Image.of("quay.io/keycloak/keycloak:26.0").role()).isEqualTo("identity");
    assertThat(Image.of("redis@sha256:abc")).isNotNull();
    assertThat(Image.of("aappaa/auth-service:develop")).isNull();
    assertThat(Image.of("${IMAGE}")).isNull();
  }

  @Test
  void helmTemplatesAreFilledFromValues() {
    Map<String, Object> values = Map.of("image", Map.of("repo", "acme", "tag", ""));
    Map<String, Object> chart = Map.of("name", "shop", "appVersion", "2.0");
    String rendered =
        HelmTemplate.render(
            """
            {{- if .Values.enabled }}
            image: {{ .Values.image.repo }}/app:{{ .Values.image.tag | default .Chart.AppVersion }}
            name: {{ .Release.Name }}-app
            {{- toYaml .Values.resources | nindent 2 }}
            {{ end }}
            """,
            values,
            chart);
    assertThat(rendered).isEqualTo("\nimage: acme/app:2.0\nname: shop-app\n\n\n");
  }
}
