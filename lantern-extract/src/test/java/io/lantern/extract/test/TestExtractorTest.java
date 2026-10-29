package io.lantern.extract.test;

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

/** The test extractor on small repositories written for each case, read from git. */
class TestExtractorTest {

  private static final long SNAPSHOT = 3;

  @TempDir Path temp;

  private static String pom(String artifactId, String body) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>"
        + artifactId
        + "</artifactId><version>1</version>"
        + body
        + "</project>";
  }

  private List<String> extract(Repo... repos) throws IOException {
    List<RepoBuild> inputs =
        List.of(repos).stream().map(r -> new RepoBuild(r, Fixtures.head(r), null)).toList();
    List<Fact> facts = new TestExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts, List.of(repos), SNAPSHOT, TestExtractor.NAME, TestExtractor.VERSION);
    return Fixtures.render(facts).lines().filter(l -> !l.isEmpty()).toList();
  }

  @Test
  void countsTheTestsOfEachTestClass() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("shop"),
            "shop",
            Map.of(
                "pom.xml",
                pom("shop", "<packaging>pom</packaging><modules><module>app</module></modules>"),
                "app/pom.xml",
                pom("app", ""),
                "app/src/test/java/com/example/OrderTest.java",
                """
                package com.example;

                import org.junit.jupiter.api.Nested;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.*;

                class OrderTest {
                  @Test void total() {}
                  @ParameterizedTest void lines(int n) {}
                  void helper() {}
                  @Nested class Empty {
                    @Test void none() {}
                  }
                }
                """,
                "app/src/test/java/com/example/LegacyTest.java",
                """
                package com.example;

                public class LegacyTest {
                  @org.junit.Test public void works() {}
                }
                """,
                "app/src/test/java/com/example/CaseTest.java",
                """
                package com.example;

                import junit.framework.TestCase;

                public class CaseTest extends TestCase {
                  public void testOne() {}
                  public void testTwo() {}
                  public void setUp() {}
                  void testHidden() {}
                }
                """,
                "app/src/test/java/com/example/NgTest.java",
                """
                package com.example;

                import org.testng.annotations.Test;
                import org.testng.annotations.BeforeMethod;

                @Test
                public class NgTest {
                  @BeforeMethod public void before() {}
                  public void first() {}
                  public void second() {}
                  private void helper() {}
                }
                """,
                "app/src/test/java/com/example/BaseTest.java",
                """
                package com.example;

                import org.junit.jupiter.api.Test;

                abstract class BaseTest {
                  @Test void inherited() {}
                }
                """,
                "app/src/test/java/com/example/Fixture.java",
                """
                package com.example;

                import com.example.annotations.Test;

                class Fixture {
                  @Test void notAJUnitTest() {}
                }
                """,
                "app/src/main/java/com/example/Main.java",
                """
                package com.example;

                import org.junit.jupiter.api.Test;

                class Main { @Test void inMainSources() {} }
                """));

    assertThat(extract(repo))
        .containsExactly(
            "test-class:g:app:com.example.CaseTest TESTS module:g:app {\"tests\":2}"
                + " @shop:app/src/test/java/com/example/CaseTest.java#5-10 0.9",
            "test-class:g:app:com.example.LegacyTest TESTS module:g:app {\"tests\":1}"
                + " @shop:app/src/test/java/com/example/LegacyTest.java#3-5 0.9",
            "test-class:g:app:com.example.NgTest TESTS module:g:app {\"tests\":2}"
                + " @shop:app/src/test/java/com/example/NgTest.java#6-12 0.9",
            "test-class:g:app:com.example.OrderTest TESTS module:g:app {\"tests\":3}"
                + " @shop:app/src/test/java/com/example/OrderTest.java#7-14 0.9");
  }

  @Test
  void marksArchitectureTests() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("arch"),
            "arch",
            Map.of(
                "pom.xml",
                pom("arch", ""),
                "src/test/java/com/example/LayerTest.java",
                """
                package com.example;

                import com.tngtech.archunit.junit.AnalyzeClasses;
                import com.tngtech.archunit.junit.ArchTest;
                import com.tngtech.archunit.lang.ArchRule;

                @AnalyzeClasses(packages = "com.example")
                class LayerTest {
                  @ArchTest static final ArchRule a = null, b = null;
                  @ArchTest static void c(Object classes) {}
                }
                """,
                "src/test/java/com/example/ModulesTest.java",
                """
                package com.example;

                import org.junit.jupiter.api.Test;
                import org.springframework.modulith.core.ApplicationModules;

                class ModulesTest {
                  @Test void verifies() { ApplicationModules.of(Object.class).verify(); }
                }
                """));

    assertThat(extract(repo))
        .containsExactly(
            "test-class:g:arch:com.example.LayerTest TESTS module:g:arch"
                + " {\"architecture\":true,\"tests\":3}"
                + " @arch:src/test/java/com/example/LayerTest.java#7-11 0.9",
            "test-class:g:arch:com.example.ModulesTest TESTS module:g:arch"
                + " {\"architecture\":true,\"tests\":1}"
                + " @arch:src/test/java/com/example/ModulesTest.java#6-8 0.9");
  }

  @Test
  void readsTheTestSourceDirectoryThePomSets() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("custom"),
            "custom",
            Map.of(
                "pom.xml",
                pom(
                    "custom",
                    "<build><testSourceDirectory>${project.basedir}/tests</testSourceDirectory>"
                        + "</build>"),
                "tests/ATest.java",
                "class ATest { @org.junit.jupiter.api.Test void a() {} }\n",
                "src/test/java/BTest.java",
                "class BTest { @org.junit.jupiter.api.Test void b() {} }\n"));

    assertThat(extract(repo))
        .containsExactly(
            "test-class:g:custom:ATest TESTS module:g:custom {\"tests\":1}"
                + " @custom:tests/ATest.java#1-1 0.9");
  }

  @Test
  void aRepositoryWithoutPomHasNoTestClasses() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("plain"),
            "plain",
            Map.of(
                "src/test/java/ATest.java",
                "class ATest { @org.junit.jupiter.api.Test void a() {} }\n"));

    assertThat(extract(repo)).isEmpty();
  }
}
