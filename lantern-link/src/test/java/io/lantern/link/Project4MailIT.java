package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.mail.MailExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The mail links of the real test set project-4 (CRM, #260): notification-service sends mail
 * through Gmail SMTP. Runs only when {@code lantern.test.project4} names the folder {@code
 * testsets/project-4/setup.sh} set it up in; reads the repositories from git, builds nothing.
 */
@EnabledIfSystemProperty(named = "lantern.test.project4", matches = ".+")
class Project4MailIT {

  @Test
  void notificationServiceSendsMailThroughGmailSmtp() throws IOException {
    Path root = Path.of(System.getProperty("lantern.test.project4"));
    List<RepoBuild> repos = new ArrayList<>();
    try (var folders = Files.list(root)) {
      for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
        if (!Files.exists(folder.resolve(".git"))) {
          continue;
        }
        Git.Result head = Git.run(folder, "rev-parse", "HEAD");
        repos.add(
            new RepoBuild(
                new Repo(
                    folder.getFileName().toString(), folder.toRealPath(), RepoSettings.DEFAULT),
                head.out(),
                null));
      }
    }
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new PersistenceExtractor().extract(code, 1));
    facts.addAll(new HttpClientExtractor().extract(code, 1));
    facts.addAll(new MessagingExtractor().extract(code, 1));
    facts.addAll(new MailExtractor().extract(code, 1));
    facts.addAll(new ValueResolution().derive(facts, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    List<String> mail =
        new CrossRepoLinks()
            .derive(facts, 1).stream()
                .filter(f -> f.predicate().equals(CrossRepoLinks.SENDS_MAIL))
                .map(
                    f ->
                        f.subject()
                            + " SENDS_MAIL "
                            + f.object().value()
                            + " "
                            + f.attributes().get("protocol").stringValue()
                            + " "
                            + f.attributes().path("source").asString("")
                            + " "
                            + f.evidence().repo()
                            + ":"
                            + f.evidence().path()
                            + "#"
                            + (f.evidence().lines() == null ? "" : f.evidence().lines().start()))
                .toList();
    mail.forEach(System.out::println);
    assertThat(mail)
        .hasSize(1)
        .singleElement()
        .asString()
        .contains("external-system:smtp.gmail.com SMTP")
        .contains("EmailService");
  }
}
