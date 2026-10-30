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
    List<Fact> facts = new SpringExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        SpringExtractor.NAME,
        SpringExtractor.VERSION);
    // Running again on the same input gives the same facts.
    assertThat(new SpringExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
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
    List<Fact> compiled = new SpringExtractor().extract(code, SNAPSHOT);
    List<Fact> parsed = new SpringExtractor().extract(unbuilt(repos), SNAPSHOT);

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
    assertThat(Fixtures.render(new SpringExtractor().extract(code, SNAPSHOT)))
        .contains("literal-test-value");
    String masked =
        Fixtures.render(new SpringExtractor().extract(code, SNAPSHOT, java.util.Set.of("jwt.key")));
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
    String facts = Fixtures.render(new SpringExtractor().extract(code, SNAPSHOT));
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
    String facts = Fixtures.render(new SpringExtractor().extract(code, SNAPSHOT));
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
}
