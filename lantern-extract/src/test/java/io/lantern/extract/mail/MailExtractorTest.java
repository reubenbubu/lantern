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

  /**
   * Mail sent through the other common libraries and the providers' SDKs, and the hosts that code
   * sets; a host computed at run time, and calls that only look like sends, are not recorded.
   */
  @Test
  void otherLibrariesProviderSdksAndHostsSetInCode() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("mail"),
            "mail",
            Map.of(
                "pom.xml",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0"
                    + "</modelVersion><groupId>com.example</groupId><artifactId>mailer"
                    + "</artifactId><version>1</version></project>",
                "src/main/java/com/example/Senders.java",
                """
                package com.example;

                import com.mailgun.api.v3.MailgunMessagesApi;
                import com.sendgrid.Request;
                import com.sendgrid.SendGrid;
                import io.micronaut.email.Email;
                import io.micronaut.email.EmailSender;
                import io.quarkus.mailer.Mail;
                import io.quarkus.mailer.Mailer;
                import org.apache.commons.mail.HtmlEmail;
                import org.simplejavamail.api.mailer.Mailer;
                import org.simplejavamail.mailer.MailerBuilder;
                import org.springframework.mail.javamail.JavaMailSenderImpl;
                import software.amazon.awssdk.services.sesv2.SesV2Client;
                import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

                public class Senders {
                  private SendGrid sendGrid;
                  private SesV2Client ses;
                  private MailgunMessagesApi mailgun;
                  private io.quarkus.mailer.Mailer quarkus;
                  private EmailSender<?, ?> micronaut;
                  private org.simplejavamail.api.mailer.Mailer simple;

                  public void viaSendGrid(Request request) throws Exception {
                    sendGrid.api(request);
                  }

                  public void viaSes(SendEmailRequest request) {
                    ses.sendEmail(request);
                  }

                  public void viaMailgun(Object message) {
                    mailgun.sendMessage("example.com", message);
                  }

                  public void viaQuarkus() {
                    quarkus.send(Mail.withText("a@example.com", "Hi", "Hello"));
                  }

                  public void viaMicronaut(Email.Builder email) {
                    micronaut.send(email);
                  }

                  public void viaSimpleJavaMail(Object email) {
                    simple.sendMail(email);
                  }

                  public void viaCommons(String host) throws Exception {
                    HtmlEmail email = new HtmlEmail();
                    email.setHostName("smtp.example.com");
                    email.send();
                    HtmlEmail other = new HtmlEmail();
                    other.setHostName(host);
                  }

                  public JavaMailSenderImpl springSender() {
                    JavaMailSenderImpl sender = new JavaMailSenderImpl();
                    sender.setHost("mail.example.org");
                    return sender;
                  }

                  public Object simpleMailer() {
                    return MailerBuilder.withSMTPServer("smtp.sendgrid.net", 587).buildMailer();
                  }

                  public void notMail() {
                    ses.listContactLists(null);
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

    assertThat(
            facts.stream()
                .map(
                    f ->
                        f.subject().key().replace("com.example.Senders#", "")
                            + " "
                            + f.predicate()
                            + " "
                            + f.object().value()
                            + (f.attributes().has("provider")
                                ? " " + f.attributes().get("provider").stringValue()
                                : ""))
                .toList())
        .containsExactlyInAnyOrder(
            "viaSendGrid(com.sendgrid.Request) mail-send api SendGrid",
            "viaSes(software.amazon.awssdk.services.sesv2.model.SendEmailRequest) mail-send"
                + " sendEmail Amazon SES",
            "viaMailgun(java.lang.Object) mail-send sendMessage Mailgun",
            "viaQuarkus() mail-send send",
            "viaMicronaut(io.micronaut.email.Email$Builder) mail-send send",
            "viaSimpleJavaMail(java.lang.Object) mail-send sendMail",
            "viaCommons(java.lang.String) mail-send send",
            "viaCommons(java.lang.String) mail-host smtp.example.com",
            "springSender() mail-host mail.example.org",
            "simpleMailer() mail-host smtp.sendgrid.net");
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
