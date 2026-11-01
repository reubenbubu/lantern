package io.lantern.extract.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.sandbox.BuildModule;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildStatus;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code spring} fixture and the {@code spring-api} fixture whose
 * interfaces it implements: golden facts with every module parsed (degraded mode), and the same
 * facts with the spring fixture read through javac, with Lantern's own test classpath (Spring's
 * context, scheduling and Boot annotations) and stand-ins for Spring MVC and Jakarta REST. {@link
 * Project0SpringIT} reads the synthetic test set built in the sandbox.
 */
class SpringExtractorTest {

  private static final long SNAPSHOT = 5;

  @TempDir Path temp;

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = Resolved.facts(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        SpringExtractor.NAME,
        SpringExtractor.VERSION);
    // Running again on the same input gives the same facts.
    assertThat(Resolved.facts(inputs, SNAPSHOT)).isEqualTo(facts);
    // The extractor itself keeps endpoints and schedules as written, resolving none (#519).
    List<Fact> written = new SpringExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        written,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        SpringExtractor.NAME,
        SpringExtractor.VERSION);
    assertThat(written)
        .noneMatch(f -> f.predicate().equals(SpringExtractor.ENDPOINT))
        .noneMatch(f -> f.attributes().has("profile"));
    return facts;
  }

  /** The spring fixture and the spring-api fixture, whose interfaces the first implements. */
  private List<Repo> fixtures() throws IOException {
    return List.of(
        Fixtures.repo("spring", temp.resolve("spring"), "spring"),
        Fixtures.repo("spring-api", temp.resolve("spring-api"), "spring-api"));
  }

  private static List<RepoBuild> unbuilt(List<Repo> repos) {
    return repos.stream().map(r -> new RepoBuild(r, Fixtures.head(r), null)).toList();
  }

  @Test
  void parsed() throws IOException {
    List<Fact> facts = extract(unbuilt(fixtures()));

    Fixtures.assertGolden("spring/spring-parsed.txt", facts);
    // A secret from the config never reaches a fact.
    assertThat(Fixtures.render(facts)).doesNotContain("s3cret");
  }

  @Test
  void reviewCases() throws IOException {
    List<String> facts = Fixtures.render(extract(unbuilt(fixtures()))).lines().toList();

    assertThat(facts)
        .anyMatch(
            // A path on the custom annotation's meta-annotation counts.
            l ->
                l.startsWith(
                    "method:com.example.shop.web.v3.ItemController#items() endpoint GET"
                        + " /api/v3/items "))
        .anyMatch(
            // An inherited mapping's evidence is in the repository that declares it.
            l ->
                l.startsWith(
                        "method:com.example.shop.web.remote.RemoteOrders#orders() endpoint GET"
                            + " /v4/orders ")
                    && l.contains(" @spring-api:src/main/java/com/example/api/OrdersApi.java#9-9 "))
        .anyMatch(
            // A class whose @Path is on its interface is a resource.
            l ->
                l.startsWith("type:com.example.shop.web.remote.RemoteLegacy stereotype controller")
                    && l.contains(
                        " @spring-api:src/main/java/com/example/api/LegacyApi.java#7-7 "));
  }

  @Test
  void jobsOfJobRunrAndQuartz() throws IOException {
    List<String> facts = Fixtures.render(extract(unbuilt(fixtures()))).lines().toList();

    assertThat(facts)
        .anyMatch(
            // A request handler is a job, named by @Job, scheduled where its request is built.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.ReminderHandler#run("
                            + "com.example.shop.app.jobs.ReminderRequest) background-job Task's reminder"
                            + " job ")
                    && l.contains("\"kind\":\"job\"")
                    && l.contains("Reminders#remindAt(long,java.time.Instant)")
                    && l.contains("Reminders#remindNow(long)"))
        .anyMatch(
            // A request scheduled recurrently with a cron is on a schedule, like @Scheduled, and
            // a call in another repository counts.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.AuditHandler#run("
                            + "com.example.api.AuditRequest) scheduled 0 0 1 * * * ")
                    && l.contains("\"trigger\":\"cron\"")
                    && l.contains("method:com.example.api.AuditTrigger#auditNow()")
                    && l.contains("method:com.example.api.AuditTrigger#auditNightly()"))
        .anyMatch(
            // A Quartz job takes the cron of the trigger it is scheduled with.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.ExpiryJob#execute("
                            + "org.quartz.JobExecutionContext) scheduled 0 15 2 * * ? ")
                    && l.contains("QuartzSetup#schedule(org.quartz.Scheduler)"))
        .anyMatch(
            l ->
                l.startsWith(
                    "method:com.example.shop.app.jobs.DigestJob#executeInternal("
                        + "org.quartz.JobExecutionContext) background-job"
                        + " com.example.shop.app.jobs.DigestJob "))
        .anyMatch(
            // @Recurring resolves its cron like @Scheduled.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.NightlyJob#cleanUp() scheduled 0 0 3")
                    && l.contains("\"framework\":\"jobrunr\""))
        .anyMatch(
            // A @Recurring interval is a background job.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.NightlyJob#poll() background-job PT5M ")
                    && l.contains("\"kind\":\"interval\""))
        .anyMatch(
            l ->
                l.startsWith(
                    "method:com.example.shop.app.jobs.NightlyJob#reindex() background-job Manual"
                        + " re-index "));
  }

  @Test
  void compiledGivesTheSameFactsAsParsed() throws IOException {
    List<Repo> repos = fixtures();
    Repo spring = repos.getFirst();
    String commit = Fixtures.head(spring);
    List<RepoBuild> inputs = new ArrayList<>(unbuilt(repos));
    inputs.set(0, new RepoBuild(spring, commit, build(spring, commit)));

    JavaAnalysis code = JavaExtractor.analyze(inputs);
    List<Fact> compiled = Resolved.facts(code, SNAPSHOT);
    List<Fact> parsed = Resolved.facts(unbuilt(repos), SNAPSHOT);

    assertThat(code.modules())
        .extracting(m -> m.repo().repo().name() + ":" + m.directory() + " " + m.compiled())
        .containsExactly("spring:app true", "spring:web true", "spring-api: false");
    List<String> lines = withoutConfidence(compiled);
    assertThat(lines).hasSameSizeAs(compiled).isEqualTo(withoutConfidence(parsed));
    assertThat(Fixtures.render(compiled).lines())
        .filteredOn(line -> line.contains(" @spring:"))
        .isNotEmpty()
        .allMatch(line -> line.endsWith(" 1.0"));
  }

  private static List<String> withoutConfidence(List<Fact> facts) {
    return Fixtures.render(facts).lines().map(l -> l.replaceAll(" [01]\\.[07]$", "")).toList();
  }

  /**
   * A successful build of the spring fixture's two modules, with Lantern's test classpath and, for
   * what that lacks, stand-ins for the Spring MVC and Jakarta REST annotations compiled here with
   * the spring-api fixture's interfaces.
   */
  private BuildResult build(Repo repo, String commit) throws IOException {
    List<String> classpath =
        new ArrayList<>(
            Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.endsWith(".jar"))
                .toList());
    classpath.add(compileStubs().toString());
    List<BuildModule> modules = new ArrayList<>();
    for (String module : List.of("app", "web")) {
      modules.add(
          new BuildModule(
              module,
              "com.example.shop",
              "shop-" + module,
              "jar",
              "1.0.0",
              List.of(),
              classpath,
              null,
              null,
              null));
    }
    Path directory = Files.createDirectories(temp.resolve("builds").resolve(repo.name()));
    return new BuildResult(
        repo.name(),
        commit,
        BuildStatus.SUCCEEDED,
        null,
        21,
        "test",
        "test",
        0,
        Instant.EPOCH,
        Duration.ZERO,
        null,
        modules,
        directory);
  }

  private Path compileStubs() throws IOException {
    List<Path> sources = new ArrayList<>();
    for (String root : List.of("spring-web-stubs", "spring-api/src/main/java")) {
      try (Stream<Path> files = Files.walk(Fixtures.fixture(root))) {
        files.filter(f -> f.toString().endsWith(".java")).forEach(sources::add);
      }
    }
    Path classes = Files.createDirectories(temp.resolve("stub-classes"));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  null,
                  List.of(
                      "-d",
                      classes.toString(),
                      "-classpath",
                      System.getProperty("java.class.path")),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      assertThat(ok).as("the stand-in annotations compile").isTrue();
    }
    return classes;
  }

  @Test
  void pathsAreJoinedWithOneSlash() {
    assertThat(SpringExtractor.join("", "")).isEqualTo("/");
    assertThat(SpringExtractor.join("/api/orders", "")).isEqualTo("/api/orders");
    assertThat(SpringExtractor.join("", "{id}")).isEqualTo("/{id}");
    assertThat(SpringExtractor.join("/api/", "/{id}")).isEqualTo("/api/{id}");
    assertThat(SpringExtractor.join("legacy", "lines")).isEqualTo("/legacy/lines");
  }

  @Test
  void aValueSubstitutedFromAKnownSecretKeyIsMasked() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("jwt"),
            "shop",
            Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>shop</artifactId><version>1</version></project>\n",
                "src/main/java/com/example/Api.java",
                """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class Api {
                  @GetMapping("/items/${jwt.key}")
                  public String items() {
                    return "";
                  }
                }
                """,
                "src/main/resources/application.properties",
                "jwt.key=literal-test-value-0123456789abcdef\n"));
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    // Without the code's keys the name alone does not show jwt.key is a secret.
    assertThat(Fixtures.render(Resolved.facts(code, SNAPSHOT))).contains("literal-test-value");
    String masked = Fixtures.render(Resolved.facts(code, SNAPSHOT, java.util.Set.of("jwt.key")));
    assertThat(masked).doesNotContain("literal-test-value").contains("/items/${jwt.key}");
  }

  @Test
  void registrationsAccessChecksAndPropertyGates() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("security"),
            "shop",
            Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>shop</artifactId><version>1</version></project>\n",
                "src/main/java/com/example/App.java",
                """
                package com.example;

                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
                import org.springframework.cloud.config.server.EnableConfigServer;

                @SpringBootApplication
                @EnableDiscoveryClient(autoRegister = false)
                @EnableConfigServer
                public class App {}
                """,
                "src/main/java/com/example/Security.java",
                """
                package com.example;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

                @Configuration
                @EnableMethodSecurity(securedEnabled = true)
                public class Security {}
                """,
                "src/main/java/com/example/AdminOnly.java",
                """
                package com.example;

                import org.springframework.security.access.prepost.PreAuthorize;

                @PreAuthorize("hasRole('ADMIN')")
                public @interface AdminOnly {}
                """,
                "src/main/java/com/example/Admin.java",
                """
                package com.example;

                import org.springframework.security.access.annotation.Secured;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @PreAuthorize("isAuthenticated()")
                public class Admin {
                  @Secured({"ROLE_A", "ROLE_B"})
                  public void both() {}

                  @AdminOnly
                  public void admin() {}
                }
                """,
                "src/main/java/com/example/Cache.java",
                """
                package com.example;

                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @ConditionalOnProperty(prefix = "shop.cache", name = "enabled", havingValue = "true")
                public class Cache {
                  @Bean
                  @ConditionalOnProperty(value = "shop.metrics", matchIfMissing = true)
                  public Object metrics() {
                    return new Object();
                  }
                }
                """));
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));
    String facts = Fixtures.render(Resolved.facts(code, SNAPSHOT));
    assertThat(facts.lines())
        .contains(
            "type:com.example.App discovery-client discovery"
                + " {\"annotation\":\"org.springframework.cloud.client.discovery.EnableDiscoveryClient\","
                + "\"autoRegister\":false} @shop:src/main/java/com/example/App.java#8-8 0.7",
            "type:com.example.App infrastructure-server config"
                + " {\"annotation\":\"org.springframework.cloud.config.server.EnableConfigServer\"}"
                + " @shop:src/main/java/com/example/App.java#9-9 0.7",
            "type:com.example.Security method-security on {\"annotation\":\"org.springframework"
                + ".security.config.annotation.method.configuration.EnableMethodSecurity\","
                + "\"jsr250Enabled\":false,\"prePostEnabled\":true,\"securedEnabled\":true}"
                + " @shop:src/main/java/com/example/Security.java#7-7 0.7",
            "type:com.example.Admin access-check isAuthenticated() {\"kind\":\"pre-authorize\"}"
                + " @shop:src/main/java/com/example/Admin.java#8-8 0.7",
            "method:com.example.Admin#both() access-check ROLE_A, ROLE_B {\"kind\":\"secured\"}"
                + " @shop:src/main/java/com/example/Admin.java#10-10 0.7",
            "method:com.example.Admin#admin() access-check hasRole('ADMIN')"
                + " {\"annotation\":\"com.example.AdminOnly\",\"kind\":\"pre-authorize\"}"
                + " @shop:src/main/java/com/example/Admin.java#13-13 0.7",
            "type:com.example.Cache property-gate shop.cache.enabled=true"
                + " {\"havingValue\":\"true\",\"properties\":[\"shop.cache.enabled\"]}"
                + " @shop:src/main/java/com/example/Cache.java#8-8 0.7",
            "method:com.example.Cache#metrics() property-gate shop.metrics"
                + " {\"matchIfMissing\":true,\"properties\":[\"shop.metrics\"]}"
                + " @shop:src/main/java/com/example/Cache.java#11-11 0.7");
  }

  @Test
  void otherRegistrationsChecksAndAdvice() throws IOException {
    Map<String, String> files = new java.util.HashMap<>();
    files.put(
        "pom.xml",
        "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></project>\n");
    files.put(
        "src/main/java/com/example/Servers.java",
"""
package com.example;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@EnableEurekaServer
@EnableAdminServer
@EnableEurekaClient
public class Servers {}
""");
    files.put(
        "src/main/java/com/example/Legacy.java",
"""
package com.example;

import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;

@EnableGlobalMethodSecurity(jsr250Enabled = true)
public class Legacy {}
""");
    files.put(
        "src/main/java/com/example/Reactive.java",
"""
package com.example;

import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;

@EnableReactiveMethodSecurity
public class Reactive {}
""");
    files.put(
        "src/main/java/com/example/Ledger.java",
"""
package com.example;

import jakarta.annotation.security.RolesAllowed;
import javax.annotation.security.DenyAll;
import javax.annotation.security.PermitAll;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.stereotype.Service;

@Service
@Audited
public class Ledger {
  @RolesAllowed({"ADMIN", "CLERK"})
  public void post() {}

  @PermitAll
  public void read() {}

  @DenyAll
  public void purge() {}

  @PostAuthorize("returnObject == principal")
  @Audit
  @Skip
  public String owner() {
    return "";
  }
}
""");
    files.put(
        "src/main/java/com/example/Audited.java",
"""
package com.example;

public @interface Audited {}
""");
    files.put(
        "src/main/java/com/example/Audit.java",
"""
package com.example;

public @interface Audit {}
""");
    files.put(
        "src/main/java/com/example/Skip.java",
"""
package com.example;

public @interface Skip {}
""");
    files.put(
        "src/main/java/com/example/Trail.java",
"""
package com.example;

import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class Trail {
  @Around("@within(com.example.Audited)")
  public Object around(Object joinPoint) {
    return joinPoint;
  }

  @Before("!@annotation(com.example.Skip) || @annotation(com.example.Audit)")
  public void before() {}

  @Pointcut("@annotation(com.example.Audit)")
  void audit() {}

  @After("execution(* *(..)) && audit()")
  public void after() {}
}
""");
    files.put(
        "src/main/java/com/example/OnFeature.java",
"""
package com.example;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(prefix = "feature.", name = {"a", "b"}, havingValue = "on")
public @interface OnFeature {}
""");
    files.put(
        "src/main/java/com/example/Features.java",
"""
package com.example;

import org.springframework.context.annotation.Configuration;

@Configuration
@OnFeature
public class Features {}
""");
    Repo repo = Fixtures.repo(temp.resolve("other"), "shop", files);
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));
    String facts = Fixtures.render(Resolved.facts(code, SNAPSHOT));
    // The negated @annotation(Skip) does not select owner(); @annotation(Audit) does, also
    // through the named pointcut audit() after another term.
    assertThat(facts.lines())
        .contains(
            "method:com.example.Ledger#owner() access-check returnObject == princip"
                + "al {\"kind\":\"post-authorize\"} @shop:src/main/java/com/example/Ledge"
                + "r.java#21-21 0.7",
            "method:com.example.Ledger#owner() advised-by com.example.Trail {\"advi"
                + "ce\":\"method:com.example.Trail#after()\",\"annotation\":\"com.example"
                + ".Audit\",\"kind\":\"after\"} @shop:src/main/java/com/example/Ledger.ja"
                + "va#22-22 0.7",
            "method:com.example.Ledger#owner() advised-by com.example.Trail {\"advi"
                + "ce\":\"method:com.example.Trail#before()\",\"annotation\":\"com.exampl"
                + "e.Audit\",\"kind\":\"before\"} @shop:src/main/java/com/example/Ledger."
                + "java#22-22 0.7",
            "method:com.example.Ledger#post() access-check ADMIN, CLERK {\"kind\":"
                + "\"roles-allowed\"} @shop:src/main/java/com/example/Ledger.java#12-12 0."
                + "7",
            "method:com.example.Ledger#post() advised-by com.example.Trail {\"advic"
                + "e\":\"method:com.example.Trail#around(java.lang.Object)\",\"annotation"
                + "\":\"com.example.Audited\",\"kind\":\"around\"} @shop:src/main/java/co"
                + "m/example/Ledger.java#10-10 0.7",
            "method:com.example.Ledger#purge() access-check deny-all {\"kind\":\"de"
                + "ny-all\"} @shop:src/main/java/com/example/Ledger.java#18-18 0.7",
            "method:com.example.Ledger#read() access-check permit-all {\"kind\":\"p"
                + "ermit-all\"} @shop:src/main/java/com/example/Ledger.java#15-15 0.7",
            "type:com.example.Features property-gate feature.a & feature.b=on {\"an"
                + "notation\":\"com.example.OnFeature\",\"havingValue\":\"on\",\"properti"
                + "es\":[\"feature.a\",\"feature.b\"]} @shop:src/main/java/com/example/Fe"
                + "atures.java#6-6 0.7",
            "type:com.example.Legacy method-security on {\"annotation\":\"org.sprin"
                + "gframework.security.config.annotation.method.configuration.EnableGloba"
                + "lMethodSecurity\",\"jsr250Enabled\":true,\"prePostEnabled\":false,\"se"
                + "curedEnabled\":false} @shop:src/main/java/com/example/Legacy.java#5-5 "
                + "0.7",
            "type:com.example.Reactive method-security on {\"annotation\":\"org.spr"
                + "ingframework.security.config.annotation.method.configuration.EnableRea"
                + "ctiveMethodSecurity\",\"jsr250Enabled\":false,\"prePostEnabled\":true,"
                + "\"securedEnabled\":false} @shop:src/main/java/com/example/Reactive.jav"
                + "a#5-5 0.7",
            "type:com.example.Servers discovery-client discovery {\"annotation\":\""
                + "org.springframework.cloud.netflix.eureka.EnableEurekaClient\"} @shop:s"
                + "rc/main/java/com/example/Servers.java#9-9 0.7",
            "type:com.example.Servers infrastructure-server admin {\"annotation\":"
                + "\"de.codecentric.boot.admin.server.config.EnableAdminServer\"} @shop:sr"
                + "c/main/java/com/example/Servers.java#8-8 0.7",
            "type:com.example.Servers infrastructure-server eureka {\"annotation\":"
                + "\"org.springframework.cloud.netflix.eureka.server.EnableEurekaServer\""
                + "} @shop:src/main/java/com/example/Servers.java#7-7 0.7")
        .noneMatch(l -> l.contains("advised-by") && l.contains("\"com.example.Skip\""));
  }

  @Test
  void whatTheInvestigateLeadsNeed() throws IOException {
    Map<String, String> files = new java.util.HashMap<>();
    files.put(
        "pom.xml",
        "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></project>\n");
    // Stand-ins for the request and CORS types, which the test classpath lacks.
    files.put(
        "src/main/java/jakarta/servlet/http/HttpServletRequest.java",
"""
package jakarta.servlet.http;

public interface HttpServletRequest {
  String getHeader(String name);
}
""");
    files.put(
        "src/main/java/org/springframework/http/HttpHeaders.java",
"""
package org.springframework.http;

public class HttpHeaders {
  public String getFirst(String name) { return null; }
}
""");
    files.put(
        "src/main/java/org/springframework/http/server/reactive/ServerHttpRequest.java",
"""
package org.springframework.http.server.reactive;

import org.springframework.http.HttpHeaders;

public interface ServerHttpRequest {
  HttpHeaders getHeaders();
  Builder mutate();
  interface Builder {
    Builder header(String name, String... values);
  }
}
""");
    files.put(
        "src/main/java/org/springframework/web/server/ServerWebExchange.java",
"""
package org.springframework.web.server;

import org.springframework.http.server.reactive.ServerHttpRequest;

public interface ServerWebExchange {
  ServerHttpRequest getRequest();
  ServerHttpRequest getResponse();
}
""");
    files.put(
        "src/main/java/org/springframework/web/cors/CorsConfiguration.java",
"""
package org.springframework.web.cors;

import java.util.List;

public class CorsConfiguration {
  public void setAllowedOrigins(List<String> origins) {}
  public void setAllowCredentials(Boolean allow) {}
}
""");
    files.put(
        "src/main/java/org/springframework/web/servlet/config/annotation/CorsRegistry.java",
"""
package org.springframework.web.servlet.config.annotation;

public class CorsRegistry {
  public CorsRegistration addMapping(String path) { return null; }
}
""");
    files.put(
        "src/main/java/org/springframework/web/servlet/config/annotation/CorsRegistration.java",
"""
package org.springframework.web.servlet.config.annotation;

public class CorsRegistration {
  public CorsRegistration allowedOrigins(String... origins) { return this; }
  public CorsRegistration allowCredentials(boolean allow) { return this; }
}
""");
    files.put(
        "src/main/java/com/example/Headers.java",
"""
package com.example;

public final class Headers {
  public static final String USER_ID = "X-User-Id";
  public static final String PERMISSIONS = "X-User-" + "Permissions";
}
""");
    files.put(
        "src/main/java/com/example/OrgController.java",
"""
package com.example;

import java.util.Map;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin
public class OrgController {
  @PostMapping("/orgs")
  @RequiresPermission(resource = "ORGANIZATION", action = "CREATE")
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void create(
      @RequestHeader(Headers.USER_ID) String userId,
      @RequestHeader Map<String, String> all,
      @CookieValue(name = "session") String session,
      @RequestParam String orgId) {}

  @PostMapping("/orgs/{id}/notes")
  @RequiresPermission
  @RateLimit(5)
  public void note() {}
}
""");
    files.put(
        "src/main/java/com/example/RequiresPermission.java",
"""
package com.example;

public @interface RequiresPermission {
  String resource() default "ALL";
  String action() default "ALL";
}
""");
    files.put(
        "src/main/java/com/example/Logged.java",
"""
package com.example;

public @interface Logged {
  String level() default "INFO";
}
""");
    files.put(
        "src/main/java/com/example/PermissionAspect.java",
"""
package com.example;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PermissionAspect {
  private HttpServletRequest request;

  @Before("@annotation(requires)")
  public void check(RequiresPermission requires) {
    String granted = request.getHeader(Headers.PERMISSIONS);
    if (granted == null) throw new ForbiddenException(requires.resource());
  }

  @After("@annotation(com.example.Logged)")
  public void log() {}
}
""");
    files.put(
        "src/main/java/com/example/ForbiddenException.java",
"""
package com.example;

public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String what) {}
}
""");
    // Aspects that read their annotation but are not access checks (#612): one whose name has
    // "auth" only inside a word, one named for a policy that logs and refuses nothing.
    files.put(
        "src/main/java/com/example/AuthorAuditAspect.java",
"""
package com.example;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuthorAuditAspect {
  @Before("@annotation(audited)")
  public void audit(Audited audited) {
    if (audited == null) throw new IllegalStateException();
  }
}
""");
    files.put(
        "src/main/java/com/example/PolicyLogging.java",
"""
package com.example;

import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PolicyLogging {
  @Before("@annotation(traced)")
  public void log(Traced traced) {
    String level = traced.level();
  }
}
""");
    files.put(
        "src/main/java/com/example/Audited.java",
"""
package com.example;

public @interface Audited {}
""");
    // An aspect that hands the decision to a permission checker is a check; one that reads the
    // same access annotation only to log it is not (#612).
    files.put(
        "src/main/java/com/example/AdminController.java",
"""
package com.example;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {
  @DeleteMapping("/cache")
  @RequiresRole("ADMIN")
  public void purge() {}
}
""");
    files.put(
        "src/main/java/com/example/RequiresRole.java",
"""
package com.example;

public @interface RequiresRole {
  String value();
}
""");
    files.put(
        "src/main/java/com/example/PermissionChecker.java",
"""
package com.example;

public class PermissionChecker {
  public void verify(String role) {}
}
""");
    files.put(
        "src/main/java/com/example/RoleGuard.java",
"""
package com.example;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RoleGuard {
  private PermissionChecker checker;

  @Before("@annotation(role)")
  public void check(RequiresRole role) {
    String wanted = role.value();
    checker.verify(wanted);
  }
}
""");
    files.put(
        "src/main/java/com/example/RoleTrail.java",
"""
package com.example;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RoleTrail {
  @Before("@annotation(role)")
  public void log(RequiresRole role) {
    String wanted = role.value();
  }
}
""");
    files.put(
        "src/main/java/com/example/Traced.java",
"""
package com.example;

public @interface Traced {
  String level() default "INFO";
}
""");
    files.put(
        "src/main/java/com/example/Limiter.java",
"""
package com.example;

import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class Limiter {
  @Around("@annotation(limit)")
  public Object around(Object joinPoint, RateLimit limit) {
    return joinPoint;
  }
}
""");
    files.put(
        "src/main/java/com/example/RateLimit.java",
"""
package com.example;

public @interface RateLimit {
  int value() default 10;
}
""");
    files.put(
        "src/main/java/com/example/Service.java",
"""
package com.example;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@org.springframework.stereotype.Service
@Transactional(readOnly = true)
public class Service {
  @Logged @Audited @Traced
  public void read() {}

  @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
  public void rolledBack(Object event) {}

  @TransactionalEventListener
  public void committed(Object event) {}
}
""");
    files.put(
        "src/main/java/com/example/OrgFilter.java",
"""
package com.example;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

public class OrgFilter {
  public ServerHttpRequest filter(ServerWebExchange exchange) {
    String org = exchange.getRequest().getHeaders().getFirst("X-Organization-Id");
    String type = exchange.getResponse().getHeaders().getFirst("Content-Type");
    return exchange.getRequest().mutate().header(Headers.USER_ID, org);
  }
}
""");
    files.put(
        "src/main/java/com/example/Web.java",
"""
package com.example;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

public class Web {
  @Value("${cors.origins}")
  private String origins;

  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**").allowedOrigins("https://shop.example", origins);
    registry.addMapping("/public/**");
  }

  public CorsConfiguration cors() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("*"));
    config.setAllowCredentials(true);
    return config;
  }
}
""");
    Repo repo = Fixtures.repo(temp.resolve("leads"), "shop", files);
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));
    List<String> facts =
        Fixtures.render(Resolved.facts(code, SNAPSHOT))
            .lines()
            .filter(
                l ->
                    l.contains(" reads-request-value ")
                        || l.contains(" transactional ")
                        || l.contains(" cors-policy ")
                        || l.contains(" custom-access-check "))
            .toList();
    // Header reads bound or called, in a handler, a gateway filter and an aspect; transactions
    // and transactional listeners; the aspect's permission checks, but not the after-advice of
    // Logged, the rate limiter, the author audit nor the policy logging; and CORS from an
    // annotation, a registry (every origin where it
    // names none) and a configuration, a @Value kept.
    assertThat(facts)
        .containsExactly(
            "method:com.example.AdminController#purge() custom-access-check ADMIN {\"advice\":\""
                + "method:com.example.RoleGuard#check(com.example.RequiresRole)\",\"annotation\":"
                + "\"com.example.RequiresRole\",\"aspect\":\"com.example.RoleGuard\",\"via\":"
                + "\"aspect\"} @shop:src/main/java/com/example/AdminController.java#9-9 0.7",
            "method:com.example.OrgController#create(java.lang.String,java.util.Map"
                + ",java.lang.String,java.lang.String) custom-access-check resource=ORGANIZATION"
                + ", action=CREATE {\"advice\":\"method:com.example.PermissionAspect#check(c"
                + "om.example.RequiresPermission)\",\"annotation\":\"com.example.RequiresPerm"
                + "ission\",\"aspect\":\"com.example.PermissionAspect\",\"via\":"
                + "\"aspect\"} @shop:src/main/java/com/example/OrgController.java#17-17 0.7",
            "method:com.example.OrgController#create(java.lang.String,java.util.Map"
                + ",java.lang.String,java.lang.String) reads-request-value * {\"annotation"
                + "\":\"org.springframework.web.bind.annotation.RequestHeader\",\"parameter\":"
                + "\"all\",\"source\":\"header\",\"via\":\"binding\"} @shop:src/main/java/com/examp"
                + "le/OrgController.java#21-21 0.7",
            "method:com.example.OrgController#create(java.lang.String,java.util.Map"
                + ",java.lang.String,java.lang.String) reads-request-value X-User-Id {\"an"
                + "notation\":\"org.springframework.web.bind.annotation.RequestHeader\",\"par"
                + "ameter\":\"userId\",\"source\":\"header\",\"via\":\"binding\"} @shop:src/main/jav"
                + "a/com/example/OrgController.java#20-20 0.7",
            "method:com.example.OrgController#create(java.lang.String,java.util.Map"
                + ",java.lang.String,java.lang.String) reads-request-value orgId {\"annota"
                + "tion\":\"org.springframework.web.bind.annotation.RequestParam\",\"paramete"
                + "r\":\"orgId\",\"source\":\"parameter\",\"via\":\"binding\"} @shop:src/main/java/c"
                + "om/example/OrgController.java#23-23 0.7",
            "method:com.example.OrgController#create(java.lang.String,java.util.Map"
                + ",java.lang.String,java.lang.String) reads-request-value session {\"anno"
                + "tation\":\"org.springframework.web.bind.annotation.CookieValue\",\"paramet"
                + "er\":\"session\",\"source\":\"cookie\",\"via\":\"binding\"} @shop:src/main/java/c"
                + "om/example/OrgController.java#22-22 0.7",
            "method:com.example.OrgController#create(java.lang.String,java.util.Map"
                + ",java.lang.String,java.lang.String) transactional REQUIRES_NEW {\"annot"
                + "ation\":\"org.springframework.transaction.annotation.Transactional\",\"kin"
                + "d\":\"transaction\"} @shop:src/main/java/com/example/OrgController.java#1"
                + "8-18 0.7",
            "method:com.example.OrgController#note() custom-access-check RequiresPermissio"
                + "n {\"advice\":\"method:com.example.PermissionAspect#check(com.example.Req"
                + "uiresPermission)\",\"annotation\":\"com.example.RequiresPermission\",\"aspec"
                + "t\":\"com.example.PermissionAspect\",\"via\":\"aspect\"} @sho"
                + "p:src/main/java/com/example/OrgController.java#26-26 0.7",
            "method:com.example.OrgFilter#filter(org.springframework.web.server.Ser"
                + "verWebExchange) reads-request-value X-Organization-Id {\"call\":\"getFirs"
                + "t\",\"requestType\":\"org.springframework.web.server.ServerWebExchange\",\"s"
                + "ource\":\"header\",\"via\":\"call\"} @shop:src/main/java/com/example/OrgFilte"
                + "r.java#8-8 0.7",
            "method:com.example.PermissionAspect#check(com.example.RequiresPermissi"
                + "on) reads-request-value X-User-Permissions {\"call\":\"getHeader\",\"reques"
                + "tType\":\"jakarta.servlet.http.HttpServletRequest\",\"source\":\"header\",\"vi"
                + "a\":\"call\"} @shop:src/main/java/com/example/PermissionAspect.java#16-16"
                + " 0.7",
            "method:com.example.Service#committed(java.lang.Object) transactional A"
                + "FTER_COMMIT {\"annotation\":\"org.springframework.transaction.event.Trans"
                + "actionalEventListener\",\"kind\":\"event-listener\"} @shop:src/main/java/co"
                + "m/example/Service.java#16-16 0.7",
            "method:com.example.Service#rolledBack(java.lang.Object) transactional "
                + "AFTER_ROLLBACK {\"annotation\":\"org.springframework.transaction.event.Tr"
                + "ansactionalEventListener\",\"kind\":\"event-listener\"} @shop:src/main/java"
                + "/com/example/Service.java#13-13 0.7",
            "method:com.example.Web#addCorsMappings(org.springframework.web.servlet.config"
                + ".annotation.CorsRegistry) cors-policy * {\"origins\":[\"*\"],\"path\":\"/pu"
                + "blic/**\",\"source\":\"cors-registry\"} @shop:src/main/java/com/example/Web"
                + ".java#14-14 0.7",
            "method:com.example.Web#addCorsMappings(org.springframework.web.servlet"
                + ".config.annotation.CorsRegistry) cors-policy https://shop.example, ${c"
                + "ors.origins} {\"origins\":[\"https://shop.example\",\"${cors.origins}\"],\"pa"
                + "th\":\"/api/**\",\"source\":\"cors-registry\"} @shop:src/main/java/com/exampl"
                + "e/Web.java#13-13 0.7",
            "method:com.example.Web#cors() cors-policy * {\"credentials\":\"true\",\"ori"
                + "gins\":[\"*\"],\"source\":\"cors-configuration\"} @shop:src/main/java/com/exa"
                + "mple/Web.java#19-19 0.7",
            "type:com.example.OrgController cors-policy * {\"origins\":[\"*\"],\"source\""
                + ":\"cross-origin\"} @shop:src/main/java/com/example/OrgController.java#14"
                + "-14 0.7",
            "type:com.example.Service transactional REQUIRED {\"annotation\":\"org.spr"
                + "ingframework.transaction.annotation.Transactional\",\"kind\":\"transaction"
                + "\",\"readOnly\":\"true\"} @shop:src/main/java/com/example/Service.java#8-8 "
                + "0.7");
  }
}
