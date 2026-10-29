package io.lantern.extract.mail;

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
class MailExtractorTest {

  private static final long SNAPSHOT = 5;

  @TempDir Path temp;

  @Test
  void methodsThatSendMailAreRecorded() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("mail"),
            "mail",
            Map.of(
                "pom.xml",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0"
                    + "</modelVersion><groupId>com.example</groupId><artifactId>mailer"
                    + "</artifactId><version>1</version></project>",
                "src/main/java/com/example/EmailService.java",
                """
                package com.example;

                import org.springframework.mail.javamail.JavaMailSender;
                import org.springframework.mail.SimpleMailMessage;

                public class EmailService {
                  private final JavaMailSender mailSender;

                  public EmailService(JavaMailSender mailSender) {
                    this.mailSender = mailSender;
                  }

                  public void welcome(String to) {
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setTo(to);
                    mailSender.send(message);
                  }

                  public void noMail() {
                    mailSender.createMimeMessage();
                  }
                }
                """,
                "src/main/java/com/example/Plain.java",
                """
                package com.example;

                public class Plain {
                  public void send(String text) {
                    new StringBuilder().append(text);
                  }
                }
                """));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new MailExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        MailExtractor.NAME,
        MailExtractor.VERSION);

    assertThat(facts).hasSize(1);
    Fact fact = facts.getFirst();
    assertThat(fact.subject().toString())
        .isEqualTo("method:com.example.EmailService#welcome(java.lang.String)");
    assertThat(fact.predicate()).isEqualTo(MailExtractor.MAIL_SEND);
    assertThat(fact.attributes().get("template").stringValue())
        .isEqualTo("org.springframework.mail.javamail.JavaMailSender");
    assertThat(new MailExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
  }

  @Test
  void transportSendAndSendsWrappedInLambdasAreRecorded() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("mail2"),
            "mail2",
            Map.of(
                "pom.xml",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0"
                    + "</modelVersion><groupId>com.example</groupId><artifactId>mailer"
                    + "</artifactId><version>1</version></project>",
                "src/main/java/com/example/Statics.java",
                """
                package com.example;

                import jakarta.mail.Message;
                import jakarta.mail.Transport;

                public class Statics {
                  public void direct(Message message) throws Exception {
                    Transport.send(message);
                  }
                }
                """,
                "src/main/java/com/example/Async.java",
                """
                package com.example;

                import java.util.concurrent.CompletableFuture;
                import org.springframework.mail.MailSender;
                import org.springframework.mail.SimpleMailMessage;

                public class Async {
                  private final MailSender mailSender;

                  public Async(MailSender mailSender) {
                    this.mailSender = mailSender;
                  }

                  public void later(SimpleMailMessage message) {
                    CompletableFuture.runAsync(() -> mailSender.send(message));
                  }
                }
                """));
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));
    List<Fact> facts = new MailExtractor().extract(inputs, SNAPSHOT);

    assertThat(facts)
        .extracting(f -> f.subject().toString())
        .anyMatch(s -> s.startsWith("method:com.example.Statics#direct("))
        .anyMatch(s -> s.startsWith("method:com.example.Async#later("));
  }
}
